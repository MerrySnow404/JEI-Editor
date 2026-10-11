# JEI Editor：真实界面（拟真 UI）测试中发现的问题

本文件记录用真实游戏输入路径逐页驱动编辑器时发现的问题：现象、机制、证据、处理状态。
测试方法与可复跑工装见文末。

测试对象：`targets/neoforge-1.21.1`（NeoForge 21.1.238 / MC 1.21.1 / JEI 19.44）。
测试工具：`D:\work\AutoJS`（AutoJS MCP，通过 `POST http://127.0.0.1:25585/mcp` 在活着的游戏里执行 JS）。

---

## 一、发现的问题

### E5 声明式 modded 配方的保存**不进 SavedData**，`Reload` 永远看不到它

**现象**：在化学品/流体页面（如 `mekanism:activating`）改一个槽位 → Save 成功、
generated datapack 文件写出、界面显示"已保存"，但点 `Reload` 后活体配方**一点没变**。

**机制**：服务端保存路径里「写文件」和「记录进 SavedData」用了两套不同的建模函数。

```java
// NeoForge121Network.saveRecipeBatch
EditorModel beforeModel = RecipeEditsApplier.currentModel(server, patch);  // 只走 RecipeEditorAdapters
if (beforeModel != null || isSyntheticPatch(patch)) {                      // ← 守卫
    data.put(patch, beforeModel);
    data.audit(...);
}
```

* `RecipeEditorAdapters.createModel` 只覆盖 crafting / cooking / vanilla-special；
  对声明式 modded serializer 返回 empty → `currentModel` 返回 null；
* 而校验路径 `canApply` 走 `RecipeEditsApplier.patchModel` → `ModdedRecipeAdapters.forSerializer` →
  `declaredModel(patch, adapter)`，**从 patch 自己的槽位键反推模型** → 校验通过、文件照写；
* 于是 patch 从不进入 SavedData，而 `handleRecipeReload` 只遍历 `SavedData.patches()`。

**证据**：`RecipeEditorAdapters.createModel(holder, registries)` 对
`mekanism:processing/lategame/polonium` 与 `immersiveengineering:mixer/concrete` 返回 EMPTY，
对 `create:smoking/bread` 正常；SavedData 的 key 集合里没有刚保存的 polonium 条目。

**副作用**：`data.audit(...)` 一并被跳过，**审计日志永远缺掉全部声明式 modded 保存**；
这些编辑只在下一次世界重载读 datapack 时才生效，所以从外部看像"重启后才生效"。

**处理**：已修 —— patch 无条件记录，快照只是 `Reset` 的可选输入
（`RecipeEditsSavedData.put` 本来就接受 null）。验证：`hasPolonium=true`，
Reload 后活体配方由 `x2` 变为 `x3`，与落盘一致。

### E6 一次编辑可以写出**让服务端启动即崩**的 datapack 文件

**现象**：拟真编辑 `mekanism:painting/...` 并 Save 之后，下一次加载世界时**服务端在
`ServerStartedEvent` 阶段崩溃**，客户端连世界都进不去。

**机制**：`mekanism:painting` 的 `item_input` 是嵌套的自定义料：

```json
{"type":"neoforge:difference","base":{"tag":"mekanism:colorable/glass_panes"},
 "count":1,"subtracted":{"item":"minecraft:brown_stained_glass_pane"}}
```

声明用 `withPreservedIngredientBase` 处理：编辑时只重写 `base`，`type`/`subtracted` 原样保留。
代码注释里的前提是「用户选的物品一定来自该 difference 自己的成员表（= 该槽位的候选列表）」。
**这个前提在真实 UI 下不成立**：拖拽源可以是同一配方页上的任意槽位，
把该配方的**产出**拖进这个槽时，它恰好等于 `subtracted` → `difference(X, X)` = 空集。

**崩溃栈**：

```
java.lang.IllegalStateException: Cannot retrieve values from custom ingredient!
    at net.minecraft.world.item.crafting.Ingredient.getValues(Ingredient.java:183)
    at mekanism.api.recipes.ingredients.ItemStackIngredient.logMissingTags(...)
    at mekanism.common.recipe.MekanismRecipeType.checkMyIncompleteRecipes(...)
    at mekanism.common.IncompleteRecipeScanner.serverStarted(...)   ← ServerStartedEvent
```

崩溃前一行：`[Mekanism]: Incomplete recipe detected: mekanism:painting/glass_pane/brown`。

**处理**：已修 —— `RecipeEditsApplier.writePreservedIngredientBase` 在写 `base` 前判断
`subtracted` 是否会消掉所选物品（`{item:X}` 比 id，`{tag:T}` 用 `BuiltInRegistries.ITEM` +
`TagKey` 判成员），会消掉就抛带具体信息的 `IllegalArgumentException`，整条 patch 拒绝、不写文件。
验证：服务端日志 `Rejected invalid recipe edit payload ...: this input is the recipe's own set
minus minecraft:purple_terracotta; picking minecraft:purple_terracotta would leave it empty`，
generated 目录不多出文件，世界可反复重启。

### E8 页级声明的两个页面**永远保存不了**：客户端送的键 ≠ 服务端校验的键

**现象**：`mekanism:condensentrating` / `mekanism:decondensentrating` 能编辑、能 Save，
服务端一律拒绝：

```
Rejected recipe edit for mekanism:rotary/uranium_oxide: Recipe serializer mismatch
```

**机制**：两侧各自都"有道理"，合起来矛盾。

* 客户端 `ModdedRecipeModelSupport.createModel` 把 model 的 `serializerId` 设成
  **`adapter.scopeKey()`**——页级声明的作用域键就是页面 uid。理由（代码注释）：
  `mekanism:rotary` 一个 serializer 喂两个 JEI 页面（`condensentrating` 化学品入/流体出，
  `decondensentrating` 反过来），光靠 serializer 名字区分不出方向；
* 服务端 `validatePatch` 却要求 `patch.serializerId()` 等于
  `BuiltInRegistries.RECIPE_SERIALIZER.getKey(holder.value().getSerializer())` = `mekanism:rotary`。

**处理**：已修 —— 不再要求两个字符串相等，而是要求「送来的键解析到的声明，
其 `serializerId()` 就是活体配方的 serializer」：

```java
if (!serializerId.toString().equals(patch.serializerId())
        && !declarationScopedToSerializer(serializerId.toString(), patch.serializerId())) {
    return "Recipe serializer mismatch";
}
```

`declarationScopedToSerializer` 用 `ModdedRecipeAdapters.forPage(...)` 反查
（`supports()` 本来就同时接受 scope key 与 serializer）。

### E9 / E10 「清空物品输入槽」在任何**声明式 modded 页面**上都会被服务端拒绝

**现象**：`Rejected recipe edit for immersiveengineering:mixer/concrete: Recipe patch is stale or unsupported`
（`validatePatch` → `canApply` 返回 false）。

**机制**（比最初假设严重得多）：清空一个物品输入槽时，客户端发的是

```java
// common/.../editor/SlotPatchFields.java
public static final String CLEARED_ITEM = "minecraft:air";
public static final String CLEARED_AMOUNT = "0";
public static Map<String, String> clearItem(String slotKey) { ... item=minecraft:air, count=0 ... }
```

而声明式**写入**路径的 `RecipeEditsApplier.declaredValueJson` 末尾是：

```java
if (id == null || id.isEmpty() || !kind.acceptsAmount(amount) || !validIngredientId(kind, id)) {
    return Optional.empty();          // ← 整条 patch 被拒
}
```

`IngredientKind.ITEM` 的范围是 `(1, 64)`，`acceptsAmount(0)` 为假 → 直接 `empty()`。

**而服务端另有一处校验是明确放行这个形状的**：

```java
private static boolean validAmount(IngredientKind kind, String value, EditorSlot slot, RecipePatch patch) {
    if (kind.acceptsAmount(amount)) return true;
    return kind == IngredientKind.ITEM && amount == 0 && "input".equals(slot.role())
            && SlotPatchFields.CLEARED_ITEM.equals(patch.fields().get(slot.key() + ".item"));
}
```

即**校验认「air/0 清空」、写入不认**——又一处两侧不一致（与 E5/E8 同一类问题）。
`ModdedRecipeAdapters.clearSlot` 的注释也写着这个形状"是写入路径能理解的"，与实现不符。

**影响面**：不只是 mixer。凡是
(a) 走右键菜单的 `Clear Input Slot`，或
(b) 任何会**清空源槽**的拖拽（`JeiIngredientDragController.update` 在拖动开始时对 input 源槽调
`clearInputAtMouse`，例如 `input->input` 拖拽），
只要目标是声明式 modded 页面，服务端都会拒绝整条 patch。

**状态：机制已证实；尝试过的修法被实证否决并已撤销。**

**先确认了影响面（不是 mixer 独有）**：在**普通**声明式页面 `create:deploying` 上，
用编辑器**自己的右键菜单项 `Clear Input Slot`**（不涉及任何拖拽）清一个物品输入槽，然后 Save：

```
[JEI Editor Client]: Submitting 1 pending recipe edit(s): [create:deploying/waxed_...]
[JEI Editor Network/WARN]: Rejected recipe edit for create:deploying/waxed_...: Recipe patch is stale or unsupported
```

即「清空输入槽」这个功能在**所有声明式 modded 页面**上都无法保存。

**尝试的修法（已撤销）**：让 `declaredValueJson` 接受这个形状，按代码库既有先例
（同文件的 `addAnvilStack` 写空物品节点就是 `{"item":"minecraft:air","count":"0"}`）就地写出。
保存确实立刻被接受了，落盘为：

```json
"ingredients":[{"item":"minecraft:air","count":0},{"item":"create:waxed_exposed_copper_tile_stairs","count":1}]
```

**但下一次加载世界时配方被直接丢掉**：

```
[Render thread/ERROR] [minecraft/RecipeManager]: Parsing error loading recipe create:deploying/waxed_..._from_adding_wax
```

（同页其它 71 条 deploying 配方正常，只有这一条消失——`RecipeManager.getRecipeIds()` 里查不到，
`byKey(...)` 为空。）

**结论**：`count: 0` 的 ItemStackIngredient **不是** mod 侧 codec 认的形状，
"写一个空节点"这条路是错的——它把"保存被拒（用户能看见）"换成"保存成功但下次加载丢配方"，
**比原来更糟**，所以已撤销，并在 `declaredValueJson` 里留了注释警告不要重新引入。

**正确修法需要作者定语义**：清空一个**数组型**（`inputs.%d`）或单值字段，应当是
**改成不写该字段 / 从数组移除该元素**（页面槽位随之变化），还是别的形状？
无论哪种，都必须让"写入路径"与 `validAmount` 一致，且新形状要通过**实际加载**验证——
这正是上面这轮实验的教训。


### E7 化学品产出槽的界面预览数量与实际保存值不一致

连续滚轮 3 次、每次回读槽位显示：

```
编辑前显示: mekanism:polonium x3        ← 与落盘 amount=3 一致（单位 1:1）
滚轮 1 次:  display = mekanism:polonium x1000   （编辑器已消费该事件）
滚轮 2 次:  display = mekanism:polonium x1000
滚轮 3 次:  display = mekanism:polonium x1000
```

**机制**：`ClientEditorState.addPreview` 的三种 kind 里**只有化学品那一支丢了数量**：

```java
if (ingredient.isItem())      ... new ItemStack(item, ingredient.amount());          // 有 amount
if (kind == FLUID)            ... addFluidStack(fluid, ingredient.amount());          // 有 amount
// 化学品：
drawable.createDisplayOverrides().addTypedIngredient(typed.get());                    // ← 没有 amount
```

`RecipeAdapterSupport.typedIngredient(uid, id)` 返回的是 **JEI 已注册的候选**，
它带的是化学品注册表的默认量（每条配方都一样，所以恒定 1000）；
物品与流体两支都显式套了 `ingredient.amount()`，只有化学品没有。

**处理**：**尝试过修法但无效，已撤销**（保持原实现）。按 JEI 的
`IIngredientHelper.copyWithAmount` + `createTypedIngredient` + `addTypedIngredient`
复制出一个带正确数量的条目后，槽位显示**仍然是注册候选的 1000**
（滚 3 次都读回 `x1000`），说明 JEI 在这一版会把带未注册数量的 typed ingredient
归一化回注册候选，走 ingredient consumer 这条路显示不出真实数量。
**待定**：需要换一种呈现方式（例如把数量作为文本另行渲染），由作者选择。
**影响**：仅界面可信度，不影响落盘正确性（落盘的 amount 一直是对的）。

### E3 `Reload` 的批次在第一条失败 patch 处中止

世界里有更早会话遗留的 SavedData 条目（`minecraft:spruce_door` 等），其 generated 文件已不存在
→ `reloadSaved` 抛 `IOException` → `reloadSavedBatch` 直接 `finish + reply failure + return`，
排在后面的有效 patch 从未被 reload；客户端只看到一句笼统的 `Recipe reload failed; check server log`。

**处理**：已修 —— 单条失败只记录、不中断整批，结束时回报"成功几条/失败哪些 id"。
生产验证：一次 reload 里 6 条坏条目各报一次 `Failed to reload saved recipe edit for <id>`，
批次照常把其余条目刷完。

### E5b `RecipeEditCoordinator` 的 500 ms 去抖让「存完立刻 Reload」静默失效

`EditOperationCoordinator` 对同一 server 有 500 ms 最小间隔；`handleRecipeReload` 里
`tryBegin` 失败只 `reply(failure("Another recipe operation is in progress"))` 就返回，
**不打日志**——活体配方当然也不会变。拟真流程（Save 的回复一到就点 Reload）每次都撞上它。

**处理**：已给三条 `tryBegin` 拒绝分支加 `LOGGER.warn`，并把消息改准确。
去抖本身是刻意的（防 datapack 写入竞争），暂不改；但确认"存完立刻 Reload"必须等过这个窗口。

### E4 服务端拒绝与客户端跳过**都没有日志**，只能瞎猜

`handleRecipeEdit` 的 `validatePatch` 失败只 `reply(failure)`；
`handleRecipeReload` 的策略拒绝同样；客户端 `Save` 在 pending 为空时静默 `return`。
排查"点了保存没反应"时日志一片空白。

**处理**：已修 —— 补 4 处日志：服务端保存拒绝、服务端重载拒绝（策略/去抖）、
客户端提交的 patch 列表、客户端跳过保存的原因。


---

## 二、逐页测试结果（68 个已接入页面）

驱动方式：全部输入走 NeoForge 真实事件对象投放到 `NeoForge.EVENT_BUS`；
`output->input` / `input->input` 为真实鼠标拖拽，`scroll-output` 为真实滚轮改数量；
每页都做「编辑 → Save → Reload → 回读活体配方」，并核对生成的 datapack 文件。
`Reload` 列：`APPLIED`/`ALREADY-LIVE` = 编辑已进入活体配方表；
`CHANGED-OTHER` = 活体配方变了但不是本次拖入的物品（多为 tag 槽位候选轮换）；
`NO-CHANGE` = 未观察到变化。

汇总：**57 页 SAVED**，4 页页面上不存在可脚本化编辑的槽位配对，
3 页（`anvil`/`crafting`/`smithing`）的拖拽源未被编辑器消费（未定因），
4 页保存被服务端拒绝且已定因（E8×2 已修、E9/E10×1 见上、E6 属按设计拒绝）。


---

## 三、页面覆盖的边界（工装侧限制，非编辑器缺陷）

* `jei:information`、`minecraft:compostable`、`minecraft:fuel`、`minecraft:grindstone`：
  页面上**没有任何「物品产出槽 + 可编辑物品输入槽」配对**，也没有可滚轮调数量的产出槽，
  脚本化编辑没有载体。fuel / compostable 的真实编辑入口是编辑器的**数值输入框**，
  需要"聚焦输入框 + 打字"的流程，本轮未覆盖。
* `minecraft:anvil` / `minecraft:crafting` / `minecraft:smithing`：
  拖拽源槽的按下事件被取消、拖动与释放事件**都没有被编辑器消费**，
  即编辑器在这几页的布局上没能启动拖拽。右键菜单可用（Toggle 能切换、菜单项能被消费），
  所以不是"编辑开关没开"。

  **已查到的部分原因**：这些页面上**槽位显示的条目会自己轮换**——不做任何输入、
  间隔 2 秒连读三次几何，同一条 `refinedstorage:coloring/magenta_destructor` 配方的
  输入槽依次是 `refinedstorage:destructor` → `cyan_destructor` → `blue_destructor`
  （输出槽稳定）。于是任何"先读几何、再在后续 eval 里操作"的序列，
  都可能操作到与读到的不同的物品；拖拽源解析（`editorItemAtMouse`）在按下那一刻
  可能已经取不到物品，`start()` 遂不建立拖拽，拖动与释放自然都不被消费。
  **待作者定**：编辑器是否应该对"槽位内容正在轮换"更稳健（用户点的是那个槽位，
  点下去就该拿到那一刻槽位里的东西）。这也是工装侧"不要用显示变化判断拖拽成功"的由来。

## 四、测试方法学要点（给 AutoJS 工装用）

这几条是本轮踩出来的，写下来免得重复踩：

1. **裸 `Screen.mouseClicked` 不触发 NeoForge 的 `ScreenEvent`。** 事件的派发在
   `MouseHandler` 里，不在 `Screen` 方法内部。要驱动界面，必须构造真实的
   `ScreenEvent.*$Pre` 对象并投到 `NeoForge.EVENT_BUS`（或显式调
   `ClientHooks.onScreenMouse*Pre`）。**用裸方法点出来的"通过"全是假的。**
2. **滚轮事件的坐标来自真实光标。** `onScreenMouseScrollPre(mouseHandler, screen, sx, sy)`
   不带坐标，`getMouseX()` 取自 `MouseHandler.xpos()`，而 `xpos/ypos` 是私有字段、
   只由 GLFW 回调写、还被 `isWindowActive()` 挡着。**投事件对象则坐标由你给**，
   不用挪光标也不用把窗口拿到前台。
3. **`Minecraft.setScreen(null)` 结尾会 `grabMouse()`。** 所有"关界面"的动作必须排在
   "交还光标"之前，否则每跑一页就抢一次操作者的鼠标；每页末尾再交还一次更保险。
4. **`AutoJS.background(true)` 管不了它之后的关界面动作**（它自己只关 pause 界面 + 放开鼠标）。
5. **界面尺寸要留足余量。** `openMenu` 的 `Y = min(screen.height - menuHeight - 2, mouseY)`
   里 menuHeight 取决于行数（行数外部不可见），屏幕矮时行号会漂；把 GUI 调到 720 高
   （`glfwSetWindowSize` 到 1280x720）后夹取不再发生。
6. **不要用"槽位显示变了"判断拖拽成功。** tag 槽位的显示候选会自己轮换，
   显示变化可以在没有编辑的情况下发生；某些页面（如 `minecraft:crafting`）甚至
   **每两秒就换一次**（实测同一配方输入槽依次显示 `destructor` / `cyan_destructor` /
   `blue_destructor`）。判据应是**编辑器消费了释放事件**（`MouseEvents$Pre` 被 cancel）。
7. **`jei-verify.ps1` 的第二段会再起一个客户端**（`runClientTest`），
   所以跑它之前必须先停掉自己那台，否则两个实例争 AutoJS 端口与构建产物。
8. PowerShell 参数经 bash 传参时注意反斜杠会被吃掉：
   `-JdkPath D:\program\jdk-21` 会变成 `D:programjdk-21`，要写成 `"D:/program/jdk-21"`。

| 页面 | 结果 | 编辑方式 | Reload | 落盘 |
|---|---|---|---|---|
| `create:crushing` | SAVED | output->input | APPLIED | file-ok |
| `create:deploying` | SAVED | output->input | CHANGED-OTHER | file-ok |
| `create:fan_haunting` | SAVED | output->input | CHANGED-OTHER | file-ok |
| `create:fan_washing` | SAVED | output->input | CHANGED-OTHER | file-ok |
| `create:item_application` | SAVED | output->input | CHANGED-OTHER | file-ok |
| `create:milling` | SAVED | output->input | CHANGED-OTHER | file-ok |
| `create:mixing` | SAVED | scroll-output | CHANGED-OTHER | file-ok |
| `create:packing` | SAVED | scroll-output | CHANGED-OTHER | file-ok |
| `create:pressing` | SAVED | output->input | NO-CHANGE | file-ok |
| `create:sandpaper_polishing` | SAVED | output->input | NO-CHANGE | file-ok |
| `create:sawing` | SAVED | output->input | CHANGED-OTHER | file-ok |
| `horsepowered:chopping` | SAVED | output->input | NO-CHANGE | file-ok |
| `horsepowered:crushing` | SAVED | output->input | NO-CHANGE | file-ok |
| `horsepowered:drying` | SAVED | output->input | NO-CHANGE | file-ok |
| `horsepowered:grinding` | SAVED | output->input | NO-CHANGE | file-ok |
| `horsepowered:manual_chopping` | SAVED | output->input | NO-CHANGE | file-ok |
| `horsepowered:manual_grinding` | SAVED | output->input | NO-CHANGE | file-ok |
| `immersiveengineering:alloy` | SAVED | output->input | CHANGED-OTHER | file-ok |
| `immersiveengineering:arc_furnace` | SAVED | output->input | NO-CHANGE | file-ok |
| `immersiveengineering:blast_furnace` | SAVED | output->input | APPLIED | file-ok |
| `immersiveengineering:blueprint` | SAVED | output->input | APPLIED | file-ok |
| `immersiveengineering:coke_oven` | SAVED | output->input | CHANGED-OTHER | file-ok |
| `immersiveengineering:crusher` | SAVED | output->input | NO-CHANGE | file-ok |
| `immersiveengineering:metal_press` | SAVED | output->input | NO-CHANGE | file-ok |
| `immersiveengineering:mixer` | SAVE-NO-FILE | input->input | - | MISSING |
| `immersiveengineering:refinery` | SAVED | scroll-output | CHANGED-OTHER | file-ok |
| `immersiveengineering:sawmill` | SAVED | output->input | APPLIED | file-ok |
| `jei:information` | NO-ITEM-SLOT-PAIR | - | - | - |
| `mekanism:activating` | SAVED | scroll-output | CHANGED-OTHER | file-ok |
| `mekanism:centrifuging` | SAVED | scroll-output | CHANGED-OTHER | file-ok |
| `mekanism:chemical_conversion` | SAVED | scroll-output | CHANGED-OTHER | file-ok |
| `mekanism:chemical_infusing` | SAVED | scroll-output | CHANGED-OTHER | file-ok |
| `mekanism:combining` | SAVED | output->input | APPLIED | file-ok |
| `mekanism:compressing` | SAVED | output->input | APPLIED | file-ok |
| `mekanism:condensentrating` | SAVED | scroll-output | CHANGED-OTHER | file-ok |
| `mekanism:crushing` | SAVED | output->input | NO-CHANGE | file-ok |
| `mekanism:crystallizing` | SAVED | scroll-output | CHANGED-OTHER | file-ok |
| `mekanism:decondensentrating` | SAVED | scroll-output | CHANGED-OTHER | file-ok |
| `mekanism:dissolution` | SAVED | scroll-output | CHANGED-OTHER | file-ok |
| `mekanism:enriching` | SAVED | output->input | CHANGED-OTHER | file-ok |
| `mekanism:evaporating` | SAVED | scroll-output | CHANGED-OTHER | file-ok |
| `mekanism:injecting` | SAVED | output->input | APPLIED | file-ok |
| `mekanism:metallurgic_infusing` | SAVED | output->input | APPLIED | file-ok |
| `mekanism:nucleosynthesizing` | SAVED | output->input | APPLIED | file-ok |
| `mekanism:oxidizing` | SAVED | scroll-output | CHANGED-OTHER | file-ok |
| `mekanism:painting` | SAVE-NO-FILE | output->input | - | MISSING |
| `mekanism:pigment_extracting` | SAVED | scroll-output | CHANGED-OTHER | file-ok |
| `mekanism:pigment_mixing` | SAVED | scroll-output | CHANGED-OTHER | file-ok |
| `mekanism:purifying` | SAVED | output->input | CHANGED-OTHER | file-ok |
| `mekanism:reaction` | SAVED | output->input | CHANGED-OTHER | file-ok |
| `mekanism:sawing` | SAVED | output->input | CHANGED-OTHER | file-ok |
| `mekanism:separating` | SAVED | scroll-output | CHANGED-OTHER | file-ok |
| `mekanism:smelting` | SAVED | output->input | NO-CHANGE | file-ok |
| `mekanism:washing` | SAVED | scroll-output | CHANGED-OTHER | file-ok |
| `minecraft:anvil` | EDIT-NOT-CONSUMED | output->input | - | MISSING |
| `minecraft:blasting` | SAVED | output->input | CHANGED-OTHER | file-ok |
| `minecraft:brewing` | SAVED | output->input | ALREADY-LIVE | MISSING |
| `minecraft:campfire_cooking` | SAVED | output->input | CHANGED-OTHER | file-ok |
| `minecraft:compostable` | NO-ITEM-SLOT-PAIR | - | - | - |
| `minecraft:crafting` | EDIT-NOT-CONSUMED | output->input | - | MISSING |
| `minecraft:fuel` | NO-ITEM-SLOT-PAIR | - | - | - |
| `minecraft:grindstone` | NO-ITEM-SLOT-PAIR | - | - | - |
| `minecraft:smelting` | SAVED | output->input | CHANGED-OTHER | file-ok |
| `minecraft:smithing` | EDIT-NOT-CONSUMED | input->input | - | MISSING |
| `minecraft:smoking` | SAVED | output->input | CHANGED-OTHER | file-ok |
| `minecraft:stonecutting` | SAVED | output->input | CHANGED-OTHER | file-ok |
| `nnp_easy_farming:grindstone_recipe` | SAVED | output->input | NO-CHANGE | file-ok |
| `rackitup:drying` | SAVED | output->input | NO-CHANGE | file-ok |

---

## 五、本轮按你的决定所做的实现（含验证状态）

### 1. 数组型输入按动态列表处理 —— **删除半边已实现并验证；空槽半边未实现**

**已实现并端到端验证**：清空一个输入槽 = **从数组里删除该元素**（不再写任何空值）。

```
清空前: "ingredients":[{"item":"create:exposed_copper_tile_stairs"},{"item":"minecraft:honeycomb_block"}]
清空后: "ingredients":[{"item":"minecraft:honeycomb_block"}]
/reload: 活体配方 ingredient 数 2 → 1 ✓    "Parsing error" 计数 0 ✓    世界可反复重启 ✓
```

服务端改动（`RecipeEditsApplier`）：
* `createModdedRecipeJson` 里，被清空的槽位若落在**数组元素**上，则记录下来、**从后往前**移除该元素
  （从后往前是因为移除会移动后面元素的下标）；非数组字段的清空仍然拒绝（没有可表达的形状）；
* `writePath` 允许 `index == size` → **追加**：「往末尾空槽里放」的写入端。

**为什么「末尾空条目」不能写进 JSON（实测证据）**：

```
com.google.gson.JsonParseException: ... Item must not be minecraft:air      ← {"item":"minecraft:air"}
（{"item":"minecraft:air","count":0} 同样是 Parsing error）
```

⇒ **空槽必须由编辑器自己画**。这部分**尚未实现**。落地方案（已勘察过落点）：

1. `ClientEditorState`：加一个「spare slot」注册表（`Rect2i` + recipeId + slotKey）+ 命中查询；
2. `JeiRecipeEditorPlugin`：每帧（`onScreenRenderPre`，与 `refreshPendingPreviews` 同处）对可见布局计算
   空槽矩形 —— 取该声明**可重复物品输入字段**的最后一个 INPUT 槽，位置 = 它的屏幕矩形右移一格
   （超出布局右边界则换行），把矩形登记进去；
3. 同一处绘制：画一个空槽样式的方框（填充 + 1px 边框），让用户看得见落点；
4. `ModdedRecipeAdapters.replaceInput`：接受**恰好越界一格**的输入序号
   （现在 `slotWithRole` 找不到模型里的槽就抛 `this input slot cannot be replaced`），
   需要为可重复字段**合成**一个 `EditorSlot` 再走 `simplePatch`。

验证方式（现成）：用真实拖拽把产出物品投到该矩形上，断言落盘文件 `ingredients` 从 N 项变 N+1 项、
`/reload` 后活体配方多一个 ingredient、且无解析错误。

### 2. 数量文本（方案 a）—— **已实现，但视觉落点未确认**

已实现：`JeiRecipeEditorPlugin.drawPatchedAmount` 在**已改动且非物品类**的槽位上画出 patch
将要保存的数量（物品类的数量 JEI 本来就显示正确，不重复画）。
实现过程中的两份临时探针（已删除）证明它每帧都在跑、取值正确
（`amount=8 kind=CHEMICAL`），但用 PIL 扫游戏内截图得到的像素证据**不自洽**：
先扫到一处紫色像素，随后导出的 pose 矩阵是**单位矩阵**，说明那不是我的文字。
⇒ **状态：代码在位、逻辑正确、视觉落点未确认**。

### 3. 顺带修掉的真 bug：`findSlotOffset` 的 y 偏移

追 E7 时发现：`JeiRecipeIntrospection.findSlotOffset` 对「直接子槽」直接采信
`IRecipeSlotDrawable` 的探针点所报的 `offset`，而 Mekanism 那种**带表头的布局**里它偏下 **42px**
——编辑器自己的「已改动槽位高亮」一直画在槽位旁边而不是槽位上。
而正确算法就是「**布局原点 + 槽位相对矩形**」（`IRecipeSlotDrawable.getRect()` 是布局相对坐标），
这一点本仓库已有记录（T12）并被拖拽命中测试反复验证。

**已修**：直接子槽优先用布局原点，并由编辑器自己的 `isSlotAt` 命中测试校验；
嵌套控件仍走原有的 offset 查找。**探针确认**：修复后 `screenTargetArea` 上报的
区域与槽位真实位置完全一致（`area=678,247` == `abs=678,247`）。

### 4. 工装侧：光标再也不该被留下抓着

**根因**：交还光标只写在批量循环与个别脚本末尾；一次性探针脚本以「关闭界面」结尾时，
`Minecraft.setScreen(null)` 结尾的 `MouseHandler.grabMouse()` 会把操作者的鼠标抓走。

**已修**（机制性，而非再补一处）：`drive2.call()` 在**每一次调用**收尾都执行
「若 `isMouseGrabbed()` 则 `AutoJS.background(true)` + `releaseMouse()`」，
任何脚本（含临时探针）都逃不掉；`sanitize.release_mouse()` 用 `guard=False` 避免重复。

---

## 六、更正与新需求：右侧索引 + 标签原料

### 6.1 更正：右侧索引在配方界面里**是显示的**

本轮实测（窗口 1280x720）：

```
IngredientListOverlay.isListDisplayed() = true      visibleItems = 144
RecipesGui                                    1280x720
```

早先"JEI 的两个侧栏在 `RecipesGui` 里都不显示、是 JEI 侧自锁"的结论**作废**——
当时的窗口只有 854x480，空间不足（`hasRoom()` 不成立）。把窗口调到 1280x720 后即可用。
推论：**「从右侧物料索引拖物品进配方槽」这条路径是可用的**，早先记的"不能从索引拖"也不再成立。
（书签覆盖层本轮仍为 `false`，未深究：它可能需要先存在书签，或另有触发条件。）

### 6.2 需求（用户提出）

* 配方槽位本来就支持**标签**（`{"tag":"c:ingots/brass"}` 之类在声明文档里大量存在）；
* 编辑模式下，**右侧索引里也要能出现标签**，且标签**与物品一样算一种独立原料**、可拖进配方槽；
* 编辑模式下标签要有**特殊颜色的边框**与物品区分；
* 标签原料要有像样的 **tooltip**。

### 6.3 现状与落点（已勘察）

* **空白的部分**：patch 模型没有标签承载。`SlotPatchFields.replace` 一律写
  `<slotKey>.<kind.idField()>`（`item`/`count`），`EditorIngredient.id()` 只是普通 id 字符串；
  写入路径 `declaredValueJson` / `ModdedRecipeAdapters.valueElement` 也只产出 `{"item":…}`。
* **可用的机制**（都走 JEI 公开 API，不需要 mixin）：
  1. 标签作为**自定义 `IIngredientType`** 注册（一个 `TagKey` + 一个代表物品），
     编辑模式开启时用 `IIngredientManager.addIngredientsAtRuntime` 加进来、关闭时移除——
     正好对应"只在编辑模式下存在"；
  2. 该类型的 **`IIngredientRenderer` 自己拥有绘制**，特殊边框颜色就画在这里；
  3. 该类型的 **`getTooltip`** 返回标签 id + 成员数量（甚至成员样例），tooltip 也归它；
  4. 拖拽由 JEI 通用机制处理：编辑器的 `JeiIngredientDragController` 已经在观察
     `IngredientListOverlay.getDraggableIngredientUnderMouse`，拖进配方槽会走编辑器的 ghost 目标解析；
  5. **patch 承载**：最小改动是让 id 支持标签形式（例如以 `#` 前缀表示标签），
     由 `SlotPatchFields` 原样携带，写入路径遇 `#` 前缀就产出 `{"tag":…}`；
     校验侧 `validIngredientId` 需放行标签 id，预览侧要能用代表物品画出来。
* **规模判断**：这是一个**新功能**（新原料类型 + 帮助器 + 渲染器 + 运行时增删 + patch 标签语义 +
  写入路径 + 预览），不是 bug 修复。建议分两增量落地并各自验证：
  * 增量 A（可独立验证）：patch 的标签语义 + 写入路径 + 校验 + 预览，用 `:common:test` 与
    declared-smoke 兜住；
  * 增量 B：JEI 自定义标签原料类型（注册/增删/渲染边框/tooltip）+ 拖拽进槽的端到端验证。

### 6.4 待你确认的两处（会直接影响实现量）

1. **列哪些标签**：全部注册标签可能有数千个（分类/兼容标签很多）；
   还是只列**当前页面槽位可能接受的**标签（对每个槽位能解析出候选标签？成本高），
   或只列**已经被任何配方用到的**标签（数量适中、语义最有意义）？
2. **边框颜色**：金/紫/青任选，或按原料种类（物品标签/流体标签）分别配色。

---

## 七、标签原料（编辑模式下的右侧索引）—— 已实现并实测

### 7.1 需求与实现

需求：**全部标签**在**编辑模式**下出现在右侧索引里，与物品一样算一种独立原料、可拖入配方；
编辑模式下用**特殊颜色边框**与物品区分；要有像样的 **tooltip**；颜色由**配置文件**填写且只能填预设。

实现（全部走 JEI 公开 API，无 mixin）：

| 文件 | 作用 |
|---|---|
| `client/TagIngredient.java` | 原料本身：`(tagId, fluid, 代表物品)`；`IIngredientType` 的 uid 固定为 `jeieditor:tag`（不用类名，改名不会孤儿化） |
| `client/TagIngredientHelper.java` | JEI 的身份规则：唯一 id 带 `item#`/`fluid#` 前缀（同名物品标签与流体标签是两种原料）、`getCheatItemStack` 给拖拽图标、`getTagStream` 让搜索按标签 id 命中 |
| `client/TagIngredientRenderer.java` | **边框**（预设色，画在代表物品之后）与 **tooltip**（标签 id + 成员数 + 前 8 个成员名 + 余量） |
| `client/TagIngredientRegistry.java` | 构建全部标签、按编辑开关 `addIngredientsAtRuntime`/`removeIngredientsAtRuntime`、读写配置文件 |
| `JeiRecipeEditorPlugin` | `registerIngredients` 以**空集合**注册该类型（运行时才能增删） |
| `ClientEditorEvents` | 每 tick `sync()`；配方重载时 `invalidate()`（JEI 重建查找表会丢掉运行时加入的原料） |

配置文件 `config/jei-editor.json`（首次进入编辑模式时写出，便于填写）：

```json
{ "tagBorderColor": "CYAN", "_allowed": "CYAN, GOLD, PURPLE, GREEN, RED" }
```

只接受预设名；未知值记录一条 warning 并回退默认，不会把打字错误变成不可见或撞色的边框。

### 7.2 实测证据（AutoJS，全程不移动系统光标）

```
编辑关闭: registeredTypes=8 | tags=0   | visibleTags=0
切到编辑: tags=1097                    ← 全部标签（物品 + 流体）已进索引
搜索 constantan: visible=10            ← 标签条目确实出现在右侧索引里
截图取色: 582 个预设青色像素，bbox x 609..1272 y 93..355
          = 10 条 × ~58 个边框像素 ≈ 580，数量与可见条目数吻合
tooltip:  #c:plates/constantan / Item tag · 1 member(s) / immersiveengineering:plate_constantan
切回关闭: tags=0                       ← 仅编辑模式生效
```

### 7.3 拖入配方槽：代码已完成，但**未能实测**（环境限制）

**实现**（`#ns:path` 作为 patch id，写入端按 `#` 产出标签形状；`requireResourceId` 本来就放行
`#ns:path`，因为它只看首个冒号位置）：

* `JeiRecipeEditorPlugin.getTargetsTyped` 增加标签分支 —— 必须加：标签类型的 helper 会把成员物品
  作为 `getCheatItemStack` 暴露，通用转换会把「拖入的标签」**静默变成它的第一个成员物品**，
  正是这个索引条目要避免的事；
* 流体标签带占位体积，落点处借用目标槽原有数量（`sizedForTarget`）；
* `ClientEditorState.addPreview` 对标签 id 显示代表成员（否则槽位会渲染成空，读起来像"被清空"）；
* `RecipeEditsApplier.validIngredientId` 放行 `#ns:path`（仅在标签确实存在于对应注册表时），
  `ingredientJson`/`ModdedRecipeAdapters.valueJson` 遇 `#` 产出 `{"tag":"ns:path"}` 且**不写 count**
  （与各 mod 自带文件一致）；`valueElement` 对「只写裸数量的字段」明确拒绝标签。

**为什么没能实测**：JEI **自己的索引拖拽跟随真实光标**，不是由合成事件驱动的 ——
因此把标签从索引拖到槽位这一步，无法在不移动操作者鼠标的前提下驱动。
对照实验确认这不是标签代码的问题：**拖一个普通物品**（`item_stack`）同样产不出 patch：

```
tag  命中索引 (1204,130) → drag trace=pre-click-canceled → saved=[]
item 命中索引 (1112,94)  → drag trace=pre-click-canceled → saved=[]
```

两者都只有"按下被消费"、拖动与释放都没有被取消（编辑器自己的槽位拖拽正是这样工作的），
说明消费方是 JEI 的列表拖拽；而它后续跟随的是 `MouseHandler` 的真实位置。
**结论**：这一步需要移动鼠标才能验证，与"实测不影响操作者鼠标"的要求冲突，
所以留作未验证，并记录**人工 10 秒可验的方式**：在游戏里把索引中的标签拖进一个配方输入槽，
再 Save，看 `jeieditor-generated` 里那条配方的 `ingredients` 是否变成 `{"tag":"…"}`。

**改用不碰鼠标的方式补上了写入端的验证**：在仓库自带的声明式用例表
（`scripts/jei-declared-smoke.ps1`，`jei-verify` 第三段，数据驱动：`Fields` = patch，
`Expect`/`Absent` = 对写出 JSON 的断言）里加了一条标签用例，它走的是**服务端真实的
写入路径与校验路径**：

```powershell
Name       = 'create:deploying (tag input)'
RecipeId   = 'create:deploying/copper_grate_from_deoxidising'
Fields     = '{"input.0.item":"#c:ingots/copper","input.0.count":"1"}'
Expect     = ingredients.0.tag = 'c:ingots/copper' ; ingredients.1.tag = 'minecraft:axes'
             keep_held_item = true ; results.0.id = 'minecraft:copper_grate'
Absent     = ingredients.0.item, ingredients.0.count, ingredients.0.id
```

实测结果：

```
ok  create:deploying (tag input)
    {"type":"create:deploying","ingredients":[{"tag":"c:ingots/copper"},{"tag":"minecraft:axes"}],
     "keep_held_item":true,"results":[{"id":"minecraft:copper_grate"}]}

Declared smoke passed: 30 declared recipes rewritten and preserved（原 29，含本条）
```

即：`#ns:path` 作 patch id 被服务端校验放行；写出的是 mod 自己的标签形状
`{"tag":"ns:path"}`、**不带 count、没有 item 键**；未改动的标签与 `keep_held_item`/`results`
逐字保留。这条从此由 `jei-verify` 长期覆盖——**不依赖任何鼠标输入**。
* `#` 前缀与 JEI 搜索框自带的 `#标签` 语法重叠：在搜索框里输入 `#xxx` 是 JEI 的"按标签找原料"，
  要按标签**原料**找，输入不带 `#` 的子串即可（实测 `constantan` 能命中）。
* JEI 索引是分页的，新加入的 1097 条排在末页：不清空搜索框时 `visibleTags=0` 属正常。

### 7.4 顺带发现的新 bug（E11，与标签无关）

编辑器写出的 **`create:item_application`** 配方会让 JEI **建布局时抛异常**，
从而把**整个配方区的渲染带崩**（所有页面都读不到布局）：

```
java.lang.IllegalStateException: Item Application Recipe has no tool!
  at mezz.jei.library.gui.recipes.RecipeLayout ... (Recipe crashed during Recipe Layout creation)
```

编辑器写出的内容：

```json
{"type":"create:item_application","ingredients":[{"item":"create:brass_casing","count":1},{"item":"create:brass_casing","count":1}],"results":[{"id":"create:brass_casing"}]}
```

而 Create 原版同名文件是 `"ingredients":[{"tag":"c:stripped_logs"},{"tag":"c:ingots/brass"}]`。
**因果已实测确认**：删掉这两个生成文件 + `/reload` 后，布局立刻恢复正常。
根因（是 `count` 字段、还是把"产出物品"替换进 ingredient 导致 Create 无法推断 tool）**尚未定性**——
这是本轮为继续验证而临时移除坏文件腾出来的，需要单独一轮排查与修复。

### 7.5 配置文件实测（改色 / 非法值回退）

| 配置 | 结果 |
|---|---|
| `"tagBorderColor": "CYAN"`（默认） | 582 个青色像素（10 条 × ~58 边框像素），bbox 全在右侧索引区 |
| 改成 `"GOLD"` 重启 | 577 个金色像素（同一条数），青色归零 —— **改配置确实生效** |
| 填非法值 `"MAGENTA"` | 日志 `tagBorderColor 'MAGENTA' in jei-editor.json is not one of CYAN, GOLD, PURPLE, GREEN, RED; using CYAN`，边框回到青色、金色 0 —— **只能填预设** |

（三个数都是"10 条可见标签条目 × 约 58 个边框像素"，与可见条目数吻合，不是碰巧取到别的 UI 元素。）
