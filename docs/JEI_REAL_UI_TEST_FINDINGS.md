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

## 二、逐页测试结果（当轮快照：68 个已接入页面；后续轮次的接入与修复见 §八 起）

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

**同一条规则也适用于 `jei-verify` 的客户端测试**：`runClientTest` 会进一个一次性世界，
而「在关卡里 + 无界面」正是游戏会抓光标（并把它居中、限制在窗口内）的状态——所以只要
门禁在跑，操作者的鼠标就会被反复抢走。原先只有 AutoJS 工装有交还步骤，测试模组没有。
现在 `JeiEditorClientTests` 的 `ClientTickEvent.Post` 里**每 tick** 调一次
`releaseGrabbedMouse()`（`isMouseGrabbed()` 就 `releaseMouse()`），且这一步排在
`finished` 早退**之前**——报告写完之后客户端还会活一小会儿（等 harness 收尾），那段时间
同样不能再抓着光标。释放之所以安全，是因为这条 walk 全程走 JEI API（`RecipePageClientTest`），
没有任何一步依赖物理鼠标。

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

**根因已定性（后续轮次）**，而且不在 `count` 字段上：

1. 抛出点在 `ItemApplicationRecipe.getRequiredHeldItem()`（javap 确认）：该方法就是 `ingredients.get(1)`，并且**先判 `ingredients.size() < 2`**，不足就抛 `IllegalStateException("Item Application Recipe has no tool!")`；Create 的 `ItemApplicationCategory.setRecipe` 正是通过它建第二个输入槽，所以异常发生在 JEI 建布局时。
2. Create 自己**不会**合并或过滤原料：全 jar 里引用 `condense…` 的只有 `ItemHelper`（定义）、`CreateJEI`、`BasinCategory`、`BlockCuttingCategory`，`ProcessingRecipe`/`ProcessingRecipeParams` 的构造只是原样拷贝列表。
3. 那两份 `.bak` 文件里都有 **2** 个 ingredient —— 也就是说它们已是**修好之后**的状态（当时排查途中我可能已经把缺的那条补回去了，文档早先引用的"写出的内容"也是这一份），所以真正的触发条件是"**配方的 ingredient 数组被缩到只剩 1 条**"。
4. 编辑器确实能做出这件事，而且是自己代码里的一条规则走偏了：**清空一个输入槽**时，写回层对"数组元素字段"一律**删掉该元素**（而不是写入 `minecraft:air`/count 0 —— 那两种写法 mod 的 codec 都不接受）。这条"删元素"规则本来只属于 `%d` 这类**可变元列表**（如 blueprint 的 `inputs.%d`），却同样作用在**固定元素**上：`create:item_application` 声明的是 `["ingredients.0","ingredients.1"]`，清空 `input.1` 就把 `ingredients` 缩成 1 条 → Create 抛异常 → JEI 整个配方区崩。

**修法**（通用机制，数据仍在声明里）：

* `ModdedRecipeAdapter` 新增 `isRepeatField(path)`：只有被 `%d` 模式命中的字段才算"可删除的可变元元素"；`ModdedRecipeDeclaration` 实现它。
* 服务端写回层：清空只在 `isRepeatField(declaredField)` 为真时删元素，否则**整条补丁拒绝**（不会再写出一个把列表缩小的文件）。
* 客户端：`ModdedRecipeAdapters.clearSlot` 对固定元素直接抛 `IllegalArgumentException`（UI 显示拒绝），不再给出一个服务端必然拒绝的补丁。

**证据**（`scripts/jei-declared-smoke.ps1` 两条新用例，一正一反）：

| 用例 | 补丁 | 结果 |
|---|---|---|
| `create:item_application (a fixed element cannot be cleared)` | `{"input.1.item":"minecraft:air","input.1.count":"0"}` | 前置门径拒绝（`stale or unsupported patch`），**不写文件** |
| `immersiveengineering:blueprint (a cleared repeat element is removed)` | `{"input.2.item":"minecraft:air","input.2.count":"0"}` | 该元素被删掉，前两条原样保留（`inputs.0.basePredicate.item=empty_casing`、`inputs.1.tag=c:gunpowders`，`inputs.2` 消失） |

也就是说："固定元素不能清空"与"可变元元素才可清空"这两半现在都被断言钉住了。

### 7.5 配置文件实测（改色 / 非法值回退）

| 配置 | 结果 |
|---|---|
| `"tagBorderColor": "CYAN"`（默认） | 582 个青色像素（10 条 × ~58 边框像素），bbox 全在右侧索引区 |
| 改成 `"GOLD"` 重启 | 577 个金色像素（同一条数），青色归零 —— **改配置确实生效** |
| 填非法值 `"MAGENTA"` | 日志 `tagBorderColor 'MAGENTA' in jei-editor.json is not one of CYAN, GOLD, PURPLE, GREEN, RED; using CYAN`，边框回到青色、金色 0 —— **只能填预设** |

（三个数都是"10 条可见标签条目 × 约 58 个边框像素"，与可见条目数吻合，不是碰巧取到别的 UI 元素。）

## 八、本轮：Immersive Engineering 剩余页面全部接入 + 一个流体标签写入缺陷

### 8.1 本轮接入的页面

| 页面 | 配方数 | 声明形式 | 为什么不能用字段列表 |
|---|---|---|---|
| `immersiveengineering:squeezer` | 6 | 定位列表 `["result","fluid"]` | 见 8.2：类别无条件建了物品输出槽，流体配方页面**真是两个产出槽**（0 空、1 流体） |
| `immersiveengineering:fermenter` | 8 | 同上 | 同上（honey 是唯一两个槽都非空的） |
| `immersiveengineering:cloche` | 40 | 派生槽位序列 | `results` 数组把普通条目与 `{"chance":…,"output":…}` 混在同一序号序列里：字段列表只能寻址数组元素整体，改一个带概率的条目会静默把 25% 变成 100% |
| `immersiveengineering:bottling_machine` | 28 | 派生槽位序列 | 物品输入 23 个文件写 `input`（单对象）、5 个写 `inputs`（数组），页面槽数因此是 2/3/4；`%d` 必须是最后一个字段，会吞掉后面的流体槽 |
| `immersiveengineering:fertilizer` | 5 | 派生槽位序列（0 产出） | 页面只有 1 个输入槽、**没有产出槽**，而字段列表至少要有一个产出字段 |
| `immersiveengineering:blast_furnace_fuel` | 4 | 同上 | 同上 |

同 serializer 的 `immersiveengineering:bottling_buckets`(37) / `bottling_potions`(175) 用 `withReadOnlyPages` 标为只读：配方只在内存里（`jei_bucket_*` 由 `JEIHelper` 按流体表生成、`potion_bottling_list*` 由 `GeneratedListRecipe` 从 `potion_bottling_list.json` 生成），没有配方 JSON 可打补丁 —— 清单因此记 `gap`，客户端测试反过来断言这两页建出 0 个模型。

至此 IE 侧只剩这 4 个"内存配方"页面不可编辑，其余页面全部可编辑。

### 8.2 本轮定性的两条机制（都写进了代码注释与门禁）

1. **JEI 会保留"类别建了、但没往里放原料"的空槽。** squeezer/fermenter 的类别是
   `addSlot(OUTPUT)`（无条件）→ 有 itemOutput 才放物品 → 有 fluidOutput 才再建一个槽，所以 5+7 条流体配方的页面画的是**两个**产出槽：序号 0 是空物品槽、序号 1 是流体。
   第一次把这两页声明成"有序段列表"（照 `horsepowered:pressing` 的样子）时，客户端测试报了 12 条
   `[B] … leaves the item/fluid slot output.0 empty, so a drag of that kind has nothing to resolve` —— 正是这条断言把事实钉住了，改成定位列表后 6/6 与 8/8 全绿。
   顺带发现并改正了 `horsepowered:pressing` 注释里的一处错误：它的类别是 **if/else**（两分支各自建槽，所以两种形状都只有一个产出槽），原文写成"先建流体槽再建物品槽"，照那个说法流体配方会有两个产出槽。结论不变（有序段列表仍是对的），但理由写对了。
2. **派生槽位序列第一次用于非 Create 页面。** 客户端测试里有一条 Create 专属的独立断言：
   "物品输入槽数 == `ItemHelper.condenseIngredients(getIngredients())` 的组数"。IE 的 `IESerializableRecipe` 没有实现 `getIngredients()`（走接口空默认值），它的原料存在自己的字段里，这条断言对 IE 页面必然失败。
   处理方式是把它收窄到序列自己声明上：`DerivedSlotSequence` 新增 `condensesItemInputs()`（默认 false，`CreateBasinSlotSequence` 覆写为 true），客户端测试只在为 true 时才比对这个组数；IE 的序列仍受"物品带在流体带之前""槽数与页面一致"两条通用断言约束。

三个序列都遵守"推不出来就拒绝"：`cloche` 里 `results` 不是数组、条目不是对象、`output` 不是对象都返回空（整条补丁被拒，而不是猜一个字段）；`bottling_machine` 在 `input` 与 `inputs` **同时存在**时也拒绝（codec 的 `listOrSingle` 只会读一个，推导无法知道是哪个）；`SingleItemInput` 只认 `input` 对象。

### 8.3 顺带修掉的真实缺陷（E12）：流体标签写入丢 amount

标签原料（§7）可以拖到流体槽，但写出的节点是 `{"tag":"ns:path"}` ——**没有 amount**，对两类页面都是错的：

* IE 的体积流体（`refinery`/`mixer`/`bottling_machine` …）：`SizedFluidIngredient.FLAT_CODEC` 的 `amount` 是"可选、默认 1000 mB"，所以不会报错，而是**静默把配方的体积改成 1000**（例：`refinery/biodiesel.json` 的 `input0` 是 8 mB，改完变 1000）。
* Create 的 `TYPED_FLUID`：连 `type` 都没写，而 Create 的流体 ingredient codec 要求显式 type → 写出的配方**直接解析失败**（下次 reload 丢配方）。

修法：`ModdedJsonStyle` 增加 `tagTypeId`（`TYPED_FLUID` = `neoforge:tag`，其余为 null），`valueJson`/`valueElement` 改成按 kind 决定标签形状：

| 情况 | 写出的节点 | 依据 |
|---|---|---|
| 物品标签 | `{"tag":"ns:path"}`（仍只有 tag） | 模组自己的文件就是这个形状；物品 style 的 amount 键是 `count`，属于"带体积的 ingredient"节点，在普通 `Ingredient` 分支会被忽略 |
| 流体标签（IE style） | `{"tag":"ns:path","amount":20}` | `refinery/biodiesel.json` 自己就写 `{"amount":8,"tag":"c:ethanol"}` |
| 流体标签（Create style） | `{"type":"neoforge:tag","tag":"ns:path","amount":250}` | `create/recipe/mixing/chocolate.json` 自己就写这个形状 |

两条 smoke 用例钉住写入结果（`immersiveengineering:refinery (fluid tag input)`、`create:mixing (fluid tag input, typed)`），物品标签那条老用例的断言（`Absent = ingredients.0.count`）不变，证明物品侧没被改动。

### 8.4 本轮门禁数字（`jei-verify` 三段）

| 阶段 | 结果 |
|---|---|
| unit-tests | 59 个单测，0 失败 |
| client-page-tests | 188 页、**1987 个模型**（上轮 1859）；**75 页 wired**（上轮 69）、5 页 `gap`、3 页已知不支持；0 失败 |
| declared-smoke | **43 条**声明配方改写并保留（上轮 32），14 条探针 |

新增/变化的客户端断言行（节选）：

```
immersiveengineering:squeezer     6   6   input.0,output | input.0,output.0,output.1
immersiveengineering:fermenter    8   8   input.0,output.0,output.1
immersiveengineering:cloche      40  40   input.0,input.1,output | input.0,input.1,output.0,output.1
immersiveengineering:bottling_machine 28 28 input.0,input.1,input.2,input.3,output.0,output.1 | input.0,input.1,input.2,output.0,output.1 | input.0,input.1,output | input.0,input.1,output.0,output.1
immersiveengineering:fertilizer           5   5   input.0
immersiveengineering:blast_furnace_fuel   4   4   input.0
immersiveengineering:bottling_buckets    37   0   GAP, no model
immersiveengineering:bottling_potions   175 → 40 采样   0   GAP, no model
```

（`bottling_potions` 实际 175 条，采样上限 40 —— 与所有大页面一致。）

## 九、清单里 4 个"编辑器早就能编辑、却记 todo"的页面 → 按事实改为 done

| 页面 | 配方数 | 页面显示的**就是**这条配方 | 采样覆盖 |
|---|---|---|---|
| `create:automatic_shaped` | 3002 | `minecraft:crafting_shapeless` | 32/40 |
| `create:fan_blasting` | 273 | `minecraft:smelting` | 40/40 |
| `create:block_cutting` | 135 | `minecraft:stonecutting` | 40/40 |
| `create:fan_smoking` | 12 | `minecraft:smoking` | 12/12 |

**为什么会误记**：清单状态一直只看"有没有针对该 serializer 的声明"。这 4 页不需要声明——它们显示的就是配方管理器里那条 vanilla 配方（id 没被改写、serializer 也没变），编辑器用 vanilla 适配器建模，补丁携带的是那条配方自己的 serializer。反过来"补一个声明"是错的：按 serializer 的声明会把页面从 vanilla 适配器手里拿走。

**判定规则**（`scripts/jei-category-dump/gen_checklist.py`）：`implemented_vanilla_serializers()` 直接从三个 vanilla 适配器 `supportsSerializer` 的方法体里抠字符串（和别的集合一样，不手抄、不会漂移），凡"页面显示的配方携带的 serializer ∈ 该集合"、且 uid 不是那 7 个 vanilla 页本身 → `done`；文档里另出一张表解释为什么 serializer 列写着 vanilla 名称却是已接入。

**覆盖率的诚实说明**：`create:automatic_shaped` 采样的 40 条里 8 条没有模型——客户端报告把它们的 serializer 也列了出来（`immersiveengineering:powerpack`、`silentgear:conversion`），也就是这一页同时陈列了别的模组的配方，那些条目既不属于本判定、也没有适配器。门禁只要求"每页至少一个模型"，所以这类部分覆盖不会失败（与 `immersiveengineering:refinery` 3/4 同类）。

**门禁怎么钉住**：
- 客户端测试现在对这 4 页断言"至少一个模型"（本轮 **79 页** wired，全绿）；
- smoke 新增三条**真指纹**探针（未声明页面必须给真指纹）：stonecutting（block_cutting）、smoking（fan_smoking）、crafting_shapeless（automatic_shaped）；crafting_shaped 由早先的 automatic_packing 探针覆盖，minecraft:smelting 由 mekanism 别名用例覆盖。

### 9.1 新探针立刻抓到一处静默丢字段（E13：vanilla cooking 编辑丢 `category`）

fan_smoking 探针第一次运行就红了，写出的文件是：

```json
{"type":"minecraft:smoking","ingredient":{"item":"create:dough"},"experience":0.0,"cookingtime":150,"result":{"id":"minecraft:cooked_porkchop","count":2}}
```

原文件 `data/create/recipe/smoking/bread.json` 里有 `"category":"food"`，写出来没了。MC 的 codec 把 `category` 读成"可选、默认 `misc`"，所以**不报错**，只是把这条烟熏食物从配方书的 food 页悄悄搬到 misc 页。

同一个分支里 `group` 早就按"它是配方记录的一部分"写回了（代码注释原话：dropping it would silently change the recipe book grouping），`category` 是同类字段，属于漏写 → 已一并写回（无条件写，不留"只在非默认值时写"的歧义）。crafting shaped/shapeless 分支本来就写 category，所以只有 cooking 这一支漏。

### 9.2 同一轮修掉的第二个静默丢字段（E14：vanilla 重建路径丢 `neoforge:conditions`）

declared 路径是**就地改原始 JSON**，所以 chance/conditions 等未声明键都活着；vanilla 重建路径（crafting / cooking / stonecutting / smithing）是从 recipe 对象重建整条记录，而条件只存在于 JSON 里——原 JSON 的 `neoforge:conditions` 因此不会出现在写出的文件里，给一条带条件的 vanilla 配方做编辑会让它变成无条件配方。静态扫描整包：**103 个** vanilla 类型的配方文件带它（例如 `create:smelting/ingot_lead_compat_mekanism` 的 `{"type":"neoforge:mod_loaded","modid":"mekanism"}`、`cobblemon:mod_compatibility/farmersdelight/apple_pie`），所以这条路径是可达的。

修法：重建完成后把原 JSON 的 `neoforge:conditions` 整块拷回（读法沿用 declared 路径的 `readRecipeJson`：先看生成包里的文件、再看模组资源，所以第二次编辑看到的是第一次写下的条件）。读不到原 JSON 时保持旧行为——不为一个条件把整次编辑失败掉。

证据是新增探针 `create:fan_blasting (a gated smelting recipe keeps its neoforge:conditions)`：一个 patch 同时钉住 `category=blocks`（9.1 的修复）与 `neoforge:conditions.0.{type,modid}` 存活。

### 9.3 "报告但不算失败"的灰区现在只剩 3 页

客户端的 `PAGES THE EDITOR MATCHES BUT THAT ARE NOT WIRED` 剩：`create:automatic_packing`、`create:automatic_shapeless`（两页显示的 holder serializer 是 Create 自己的 `create:basin`，真正被编辑的是底下那条 vanilla crafting 配方，静态规则看不出这一点；automatic_packing 的写入形状已由探针钉住）与 `jerm:jerm_repair`（显示的是 JEI 的 `IJeiAnvilRecipe`，编辑器能建模但管理器里没有对应 holder，现有探针记录的正是这种拒绝）。这三页要么需要一条"运行时解析后判定"的规则，要么单独判定，留给后面。

### 9.4 本轮门禁数字

| 阶段 | 结果 |
|---|---|
| unit-tests | 59 个单测，0 失败 |
| client-page-tests | 188 页、1987 个模型；**79 页 wired**（75→79）、5 页 `gap`、3 页已知不支持；0 失败 |
| declared-smoke | **43 条**声明配方改写并保留，**18 条**探针（三条是 vanilla 接管页探针，一条是条件保真探针） |

### 9.5 过程记录：一条夹具 bug 与它排除掉的怀疑

crafting_shapeless 探针第一次失败（`stale or unsupported patch`），第一反应怀疑"服务端 shapeless 建模走了 JEI 的 `CraftingGridHelper`（客户端库类），dedicated server 上是否可用"。逐条排除：

1. `mezz.jei.library.gui.helpers.CraftingGridHelper.getGuiSlotToIngredientMap` 是纯函数（只读 `RecipeHolder.value().getIngredients()`，再按 `getShapelessSize`/`getCraftingIndex` 算格子），不碰任何客户端状态，javap 已确认；
2. 拒绝信息来自 `RecipeEditorCommands` 的 `canApply` 前置门，而不是写回路径，说明是"模型或指纹"不符；
3. 客户端的 `report.json` 里 `modelled_samples` 给出了这条配方的完整槽位（单条目 shapeless 落在 3×3 网格的**中心格 input.4**，其余 8 格为空），据此重写 `BaseSlots`；
4. 仍然失败——最后一步是自己算一遍 SHA-256：`BaseSlots` 少了 `input.0` 前的那个 `|` 分隔符，`28edebd3…` 正是脚本里那份错串的哈希，而正确答案 `09941a4c…` 才对。是我拼错了夹具，不是服务端的问题。

（这条记下来是因为它花掉两轮 run：夹具里的分隔符错误会让"前缀门径"看起来像产品缺陷。以后先自己把 canonic 串打出来比哈希，再去动 Java。）

## 十、Silent Gear：3 页接入 + 2 页判为不可接入（B 组的"裸配方对象"）

**背景**：silentgear 的 JEI 插件把**裸 recipe 对象**塞进 JEI，不是 `RecipeHolder`，所以 dump 里连 `id=` / `type=` 都没有 → 这些页一直记 todo、0 模型。编辑器客户端本来就有"按实例在配方管理器里反查 holder"这条路径（`JeiRecipeEditorPlugin.resolveRecipeHolder`，原本给 pending preview 用），所以这次真正缺的只是**声明**，不需要新能力。

| 页面 | 页面配方数 | 结果 | 建模 |
|---|---|---|---|
| `silentgear:alloy_making/metal` | 8 | declared | 5/8 |
| `silentgear:alloy_making/gem` | 4 | declared | 1/4 |
| `silentgear:tool_action` | 9 | declared | 9/9 |
| `silentgear:alloy_making/fabric` | 3 | **不可接入**（清单记 `gap`） | 0/3 |
| `silentgear:alloy_making/super` | 3 | **不可接入**（清单记 `gap`） | 0/3 |

**形状依据**（JSON 样本 + category 字节码）：

* alloy（`AlloyMakingRecipeCategory.setRecipe`）：按 `AlloyRecipe.getIngredients()` 顺序一条 ingredient 一个 INPUT 槽，然后一个 OUTPUT 槽 → 声明是 `ingredients.%d` + `result`。两个特殊点：输入是普通 `Ingredient`（`{"tag":…}`/`{"item":…}`）；**产出是 `{"item":…,"count":…}`（item 形状），不是别处通用的 id 形状** —— mod 自己就这么写（`AlloyRecipe.Result` 的键是 item / count / material），所以声明的 outputStyle 必须用 ITEM。
* tool_action（`ToolActionRecipeCategory.setRecipe`）：槽序是 INPUT(tool) → INPUT(ingredient) → CATALYST(铁砧方块) → OUTPUT，所以 per-role 是 input.0=工具、input.1=被加工物（工具在前，和界面视觉相反）；这里的产出反而是 **id 形状**（`{"count":1,"id":…}`），因为 mod 的文件那么写。`damage_to_tool` 与 `sound` 未声明、逐字保留。

**两个动态产出的配方**：`alloying/gem/custom_gem.dimerald.json` 与 `alloying/metal/custom_ingot.high_carbon_steel.json` 的 `result` 带 `material`（那个 key 才是产出的身份），而声明式写回会重建整个节点、把它丢掉。但它们不会真的被写坏：产出的材质带在数据组件上，`RecipeAdapterSupport.simpleStack` 拒绝带组件补丁的栈，所以界面把这个产出槽显示为**空**、并拒绝改写（与 IE blueprint 的彩色信号弹同类），输入仍可编辑。

**为什么 fabric/super 判为不可接入**：这两页各 3 条配方，整包**没有任何配方 JSON** —— silentgear 按材质数据在加载时生成它们，而且它们连配方管理器都不进（客户端也反查不到 holder），所以 0 模型。第一次把它们一起声明后，客户端测试直接报了 2 条 `[A] no editor model for any of the 3 sampled recipes` —— 正是"wired 必须至少一个模型"这条门禁在起作用；随后把它们从声明里去掉、写进 `gen_checklist.py` 的 `NOT_COVERED_UIDS`：清单记 `gap`，门禁反过来断言这两页建出 **0** 个模型。同页的 metal/gem 也各有 3 条生成配方（8 与 4 里只有 5 和 1 有 JSON），所以那两页是**部分覆盖**，与 `refinery` 3/4 同类，客户端测试逐页报出 5/8 与 1/4。

**本轮门禁数字**

| 阶段 | 结果 |
|---|---|
| unit-tests | 59 个单测，0 失败 |
| client-page-tests | 188 页、**2002 个模型**（1987→2002，+15 正是 15 条有 JSON 的 silentgear 配方：metal 5 + gem 1 + tool_action 9）；**82 页 wired**（79→82）、**7 页 `gap`**（5→7）、3 页已知不支持；0 失败 |
| declared-smoke | **45 条**声明配方改写并保留（43→45，新增 alloy 与 tool_action 各一条），18 条探针 |

## 十一、Silent Gear 第二批：gear_crafting 页（113 条）接入

**一个页面、两种 serializer**：`silentgear:gear_crafting` 页显示 70 条 `silentgear:gear_crafting` + 43 条 `silentgear:compound_part`，正好是页面报告的 113 条。两条声明都挂在 serializer 上（页面 uid 本身没有声明，客户端按配方自身的 serializer 回落）。

这一页是文档里点名的样本："`silentgear:compound_part` 的配方类继承 `ShapelessRecipe`，曾被当作合成网格 offer 出来，而服务端对它的补丁一律回答 `stale or unsupported patch`（补丁从未落盘），收紧 serializer 门后该页回到 0 模型"。现在 serializer 有声明了，补丁改走声明路径，页面重新可编辑。

**布局事实**（客户端 `report.json` 的 `modelled_samples` + Silent Gear 自己 category 的字节码）：页面把 ingredient 列表画成网格，**entry i 落在第 i 格**（silentgear 自己算坐标，不是 JEI 那套居中算法），所以槽位序号就是数组下标 → 声明用 `ingredients.%d`；**第一个超出列表的格子**就是声明机制里那个"备用槽"。因此原来那条断言"服务端拒绝该补丁"的探针已经过期，改成断言"**drop 到第一个空格会追加一条 ingredient**"——写入结果实测：

```json
{"type":"silentgear:compound_part","category":"equipment",
 "ingredients":[{"type":"silentgear:blueprint",…},{"type":"silentgear:material",…} ×3,
                {"item":"minecraft:oak_planks","count":1}],
 "result":{"count":1,"id":"silentgear:sickle_blade"}}
```

四条原条目（连同它们自己的 `type`/`gear_type`/`part_type` 键）逐字保留，第 5 条是追加进来的——与 `create:blueprint` 的 `inputs.%d` 同一契约，现在是被断言的**事实**而不是猜测。

**产出可编辑**：这一页的 `result` 是纯 id 形状（`{"count":1,"id":"silentgear:arrow"}`），因为 Silent Gear 的动态装备是**合成时**才注入材质，JSON 里本来就不带——所以写回 `{"id":…,"count":…}` 与 mod 自己的形状一致（与 alloy 那种带 `material` 的结果不同：那种槽位显示为空、只读）。ingredient 条目是 Silent Gear 注册的 `IngredientType`（NeoForge 派发、回落到 vanilla 单件/标签形状），所以声明的 item 形状能解码；编辑其中一条时，另一条 `{"type":"silentgear:part_type"}` 逐字保留（两条声明用例都断言了这一点）。

**本轮门禁数字**

| 阶段 | 结果 |
|---|---|
| unit-tests | 59 个单测，0 失败 |
| client-page-tests | 188 页、**2042 个模型**（2002→2042，+40 = gear_crafting 页采样 40 条全部建模）；**83 页 wired**、7 页 `gap`、3 页已知不支持；0 失败 |
| declared-smoke | **47 条**声明配方改写并保留（45→47），18 条探针（其中一条由"期望拒绝"改为"期望追加"） |

## 十二、Silent Gear 第三批：salvaging 页（88 条）

这一页显示**三种**配方类型：54 条 `silentgear:salvaging` + 33 条 `silentgear:salvaging/gear` + 1 条 `silentgear:salvaging/compound_part`。

**决定性的机制**在 `SalvagingRecipeCategoryJei.setRecipe`：先加 INPUT(ingredient)，再按 `SalvagingRecipe.getPossibleResultsForDisplay()` 的条目逐个加 OUTPUT（最多 9 个）。而两个子类对同一个方法的实现完全不同：

```
SalvageRecipe.getPossibleResultsForDisplay()      -> 它的 "results" 数组
GearSalvagingRecipe.getPossibleResultsForDisplay() -> List.of()   // 字节码里就是 invokestatic List.of 直接返回
```

所以 `/gear` 类型的页面**永远只有 1 个槽、没有产出槽**（33 个文件里也确实只有 `ingredient` 一个键）。据此：

| 类型 | 声明方式 | 依据 |
|---|---|---|
| `silentgear:salvaging` | 字段列表 `ingredient` + `results.%d` | 54 个文件的 97 个结果条目全是 `{"count":…,"id":…}`，每条 1..4 个 |
| `silentgear:salvaging/gear` | 新增派生序列 `SilentGearSlotSequences.SalvageGear`（1 入 0 出，字段 `ingredient`） | 字段列表至少要有一个产出字段，表达不了"没有产出槽"的页面 |
| `silentgear:salvaging/compound_part` | **故意不声明** | 那一个文件只有 `{"type":…}`：没有 ingredient、没有 results，页面画的是一个空输入槽 + 算出来的产出，没有任何字段可编辑 |

客户端报告的 4 种槽形正好对上：`input.0`（/gear 类型）与 `input.0,output` / `input.0,output.0,output.1` / `…output.2`（普通类型，产出数 = `results` 数组长度）。

**本轮门禁数字**

| 阶段 | 结果 |
|---|---|
| unit-tests | 59 个单测，0 失败 |
| client-page-tests | 188 页、**2081 个模型**（2042→2081，+39 = salvaging 页采样 40 条里 39 条建模，唯一没建模的就是那条故意不声明的 compound_part）；**84 页 wired**、7 页 `gap`、3 页已知不支持；0 失败 |
| declared-smoke | **49 条**声明配方改写并保留（47→49），18 条探针 |

（同轮还把 §7.4 的 E11 定性并修好：清空只对 `%d` 可变元元素生效，固定元素的清空整条拒绝。此后 smoke 是 **50 条声明 + 19 条探针**，两条新用例一正一反钉住这条规则。）

## 十三、清单账目收口：两个 Create 页记入、`grading` 判为不可接入

**`create:automatic_packing`(221) 与 `create:automatic_shapeless`(1094) → `done`。** 这两页显示的 holder 是 Create 自己的 `BasinRecipeHolder`（dump 的 serializer 列因此写 `create:basin`），但**那个 id 指向的真正配方**是配方管理器里的 vanilla crafting 配方（`minecraft:crafting_shaped` / `crafting_shapeless`）：编辑器按 identity / `byKey` 找到真配方、走 vanilla 分支写回。这与 `mekanism:smelting` 的别名是同一情形，只是这里 id 没被改写、wrapper 是 Create 的，所以**静态规则看不出来** → 具名在 `gen_checklist.py` 的 `RESOLVED_VANILLA_UIDS`（附理由），文档 A1 组也多了张表说明。

守卫两头都在：
* 客户端测试断言"每页至少一个模型"：40/40 与 24/40（`automatic_shapeless` 的其余 16 条采样带的是编辑器没实现的 serializer，例如 `refinedstorage:recoloring`）；
* 写入形状由既有的 `create:automatic_packing` 探针钉住——它 patch `minecraft:bone_block` 并断言写出的 `minecraft:crafting_shaped` JSON。

**`silentgear:grading`(1) → `gap`。** 页面显示的对象是 `MaterialGraderRecipeCategory$GraderRecipe`：category 内部造的一个 helper record（一条"配方"里塞了 173 个候选原料）。它不是配方管理器里的配方，没有 id、没有 holder，客户端建不出模型 → 记入 `NOT_COVERED_UIDS`，门禁反过来断言它 0 模型。

顺带把文档里那句"实测到 7 个没有声明却能建模的页面"改成现状：其中 **6 个**已按事实记入清单（4 个 vanilla 接管 + 这 2 个），只剩 `jerm:jerm_repair`（JEI 自己的铁砧页，走 `jei:*` 合成页路径、编辑被只读策略拒绝）留在客户端的"报告但不算失败"一侧。

**本轮门禁数字**

| 阶段 | 结果 |
|---|---|
| unit-tests | 59 个单测，0 失败 |
| client-page-tests | 188 页、2081 个模型；**86 页 wired**（84→86：67 declared + 20 done 的 vanilla 页）、**8 页 `gap`**（7→8）、3 页已知不支持；0 失败 |
| declared-smoke | 50 条声明配方改写并保留，19 条探针 |

## 十四、共享的"单入无出"序列 + `mekanism:energy_conversion` 接入

### 14.1 把四处同形状的序列合并成一个

这四个页面的形状完全相同（1 个输入槽、0 个产出槽），字段名只有两种，于是合并成一个按字段名参数化的 `SingleItemInputSequence`，删掉了 `ImmersiveEngineeringSlotSequences.SingleItemInput` 与整个 `SilentGearSlotSequences.java`：

| 页面 | 字段 | "结果"其实是什么 |
|---|---|---|
| `immersiveengineering:fertilizer` | `input` | `growthModifier` |
| `immersiveengineering:blast_furnace_fuel` | `input` | `time` |
| `silentgear:salvaging/gear` | `ingredient` | 由齿轮材质算出的回收物（`getPossibleResultsForDisplay()` 恒为空表） |
| `mekanism:energy_conversion` | `input` | 裸 FE 数值（页面用文字显示，根本没有产出槽） |

为什么必须走序列：字段列表至少要有一个产出字段，而"页面没有产出槽"这件事只有序列的 `outputCountFits(0)` 能表达。

### 14.2 `mekanism:energy_conversion`（2 条）接入

```json
{"type":"mekanism:energy_conversion","input":{"count":1,"tag":"c:dusts/redstone"},"output":10000}
```

`output` 是**裸能量数值**（既不是物品，也不是流体/化学品，没有任何声明种类能承载它），页面也只有一个槽 → 声明走单入无出序列，`output` 保持未声明、逐字保留。新 smoke 用例实测：patch 输入后写出

```json
{"type":"mekanism:energy_conversion","input":{"item":"minecraft:redstone","count":1},"output":10000}
```

能量没被动过。

### 14.3 两处账目与三条勘察记录

* `create:mystery_conversion` → `gap`：页面显示的是 `com.simibubi.create.compat.jei.ConversionRecipe`——JEI compat 包里的展示类（不在 Create 的配方注册表里），id 是合成的 `create:conversion_0/_1`、没有配方 JSON，客户端 40/40 采样建 0 模型 → 记入 `NOT_COVERED_UIDS`。
* **留待下一批的勘察结论**（都已实测）：
  - `create:spout_filling`(50) 与 `create:draining`(80)：显示的 holder 和 id 都是真的（`create:filling/compat/immersiveengineering/treated_wood_in_spout.json` 这类文件确实存在），但页面**没有声明**，所以 40/40 采样全 0 模型 —— 也就是说这两页**是可接入的**。难点：槽序要按 `SpoutCategory` / 空桶页各自的 `setRecipe` 重新推导（不能照搬 basin 的序列），而两页里大部分条目是运行时生成的（`data/create/recipe/filling/**` 只有 19 个文件、`emptying/**` 只有 5 个），接入后覆盖率会明显偏低（要按 `refinery` 3/4 那样如实记录）。
  - `create:mechanical_crafting`(4)：holder 与文件都真实，页面把 `pattern` 的格子画成槽位，而 `key` 是按**字母**共享的——一个格子对应的是"某个字母的原料"，改一格会连带改掉所有用到该字母的格子。要么按字母寻址并在声明里写清这个语义，要么拒绝该页，需要单独一轮判断。

## 十五、清单收口：18 个永远不会被接入的页面从 `todo` 改成 `gap`

`todo` 应当只是"待接入的工作队列"。这 18 页要么配方只存在于代码里、要么显示的只是一个展示类、要么是真配方但没有任何可命名的槽位——它们不会变成"已接入"，所以记 `gap`：门禁反过来**断言它们建出 0 个模型**，理由逐条写在生成器里（每条都先实测过 0 模型）。

| 类别 | 页面 | 判据 |
|---|---|---|
| 配方只在代码里，没有可打补丁的 JSON | `jearchaeology:brush`、`jearchaeology:sniff` | 该 jar 只发 assets 与 class（无 `data/**` JSON），配方按战利品表在代码里构建 |
| 显示对象是 JEI 集成类，不是管理器里的配方 | `cobblemon:berry_recipe`、`createresourcegeodes:catalyst_generation`、`betterpiglintrades:piglin_barter`、`just_enough_beacons` ×3、`mekanism:boiler_casing`、`mekanism:sps_casing`、`smithingtemplateviewer:smithing_trim` | 各自的类都在 `compat`/`integration`/客户端 recipe-viewer 包里，dump 的配方 id 列为空 |
| 披着 RecipeHolder 外衣的信息页 | `minecraft:overworld`、`minecraft:the_nether`、`minecraft:the_end`、`nnp_easy_farming:farming_stem` | holder 是 AWI 自己的 `com.yanny.awi…RecipeHolder`——这也是清单早先把它们错分进配方组的原因（现在按事实记 `gap`） |
| 真配方，但槽位无法命名 | `horsepowered:bottling`、`horsepowered:trapping`、`mekanism:nutritional_liquifier` | 产出带组件补丁（写回会丢药水内容）／产出是由 `entity` 字符串推出来的刷怪蛋（没有物品字段）／客户端专属的 recipe-viewer 配方（没有可寻址的 serializer） |

**结果：`todo` 队列从 27 行降到 8 行，且每一行都是真待办。**

| 页面 | 配方数 | 待办原因 |
|---|---|---|
| `cobblemon:campfire_pot_recipe` | 118 | 需要"无字段槽位"这一新能力：14 条带 seasoning 的配方会多画出没有 JSON 字段的展示槽，拖进去会被当成"追加一条原料" |
| `create:draining`、`create:spout_filling` | 80 / 50 | 可接入，但槽序要按 `ItemDrainCategory` / `SpoutCategory` 各自推导（draining 的流体产出排在产出槽第一位；filling 的流体是第二个输入，且 5 个文件是"两个流体"形状），而多数条目是运行时生成的（文件数只有 5 / 19），覆盖率会很低 |
| `create:mechanical_crafting` | 4 | `pattern` 的格子共享 `key` 字母，需要决定"按字母寻址"还是"拒绝该页" |
| `create:sequenced_assembly` | 3 | 每一步都要一段独立的路径序列 |
| `minecraft:tag_recipes/{item,block,fluid}` | 1052 / 575 / 45 | 已知不支持（客户端测试另有一张名单单独断言这三页） |

**本轮门禁数字**：87 页 wired、**27 页 `gap`**（9→27）、3 页已知不支持、2083 个模型、0 失败；declared-smoke 51 条 + 19 条探针。

## 十六、`create:spout_filling`（注液器页）接入

**槽序来自字节码**（`SpoutCategory.setRecipe`，整个方法只有三个 `addSlot`）：

```
INPUT (27,51)  FillingRecipe.getIngredients().get(0)   // 物品条目
INPUT (27,32)  FillingRecipe.getRequiredFluid()        // 流体条目
OUTPUT(132,51) getResultItem(recipe)                   // 物品产出
```

两个访问都不是循环（`.get(0)` 与"第一个流体"），所以页面永远是 2 入 1 出、物品在流体之前——但**两个条目在 JSON 数组里的下标是任意的**：Create 的 `ProcessingRecipeParams` 把同一个 `ingredients` 数组拆成物品表和流体表，各自保持相对顺序。

因此**不能**写死 `["ingredients.0","ingredients.1"]`：若某个数据包把流体写在前面，写物品槽就会覆盖流体条目（静默丢掉物品需求）——这条路由已明确否定。改为新序列 `CreateSpoutSlotSequence`：按条目自身种类解析每条槽的路径（"`type` 指向已注册的流体 ingredient 类型"即流体，于是 `neoforge:single`/`tag`/`components` 都算流体、`neoforge:compound` 算物品），并把物品那一半与 mod 自己解析出的 `Ingredient` 逐项对照，不一致即拒绝；条目多于 1 物品 / 1 流体也拒绝（多出来的在页面上不可见、无法寻址）。

**顺带的共享重构**：两处推导共用的 JSON 判定（取数组、流体条目、流体产出、解 `Ingredient`、`sameItems`）提成 `CreateIngredientShapes`，`CreateBasinSlotSequence` 改为调用它（删掉私有副本），新序列共用同一套判定——"什么算流体条目"从此只有一处定义。

**覆盖率（如实记录）**：页面列 50 条，`data/create/recipe/filling/**` 有 19 个文件，专用探针先前测得其中 **12** 条在管理器里且有 JSON 资源（其余文件的条件未满足）。但**声明页会为任何布局相符的条目建模**（本轮客户端报 40/40），所以 Create 现算出来的条目也会拿到模型、而服务端的补丁会因找不到配方 JSON 被拒——`create:draining` 的既有探针钉的正是这种拒绝（那一页采样到的全是现算条目，所以它至今无法按门禁"至少一个模型"接入）。真正可编辑的是那 12 条。

**smoke 证据（两条）**：

* 新用例 `create:filling (item + fluid by shape, condition survives)`：物品条目失去 tag、流体按 Create 的 sized 形状重写（带显式 `"type":"neoforge:single"`）、产出重写、`neoforge:conditions` 原样保留（顺带复核 E14）。
* 原有探针断言的是"页面没声明所以整条被拒"，现已过期 → 改写为 `create:spout_filling (an item cannot be written into the fluid slot)`：把物品写进流体槽，前置门径通过、**推导拒绝**（解析出的字段种类与补丁携带的种类不符），不落盘；它用 `create:filling/glowstone`（流体写成 `{"type":"neoforge:components",…}`），因此同时证明"任何已注册的流体 ingredient 类型都算流体那一半"。

**本轮门禁数字**：88 页 wired（87→88）、27 页 `gap`、3 页已知不支持、**2123 个模型**（2083→2123，+40）、0 失败；declared-smoke **52 条 + 19 条探针**。

## 十七、`create:draining`：先按字节码写好，再按实测回退，记 `gap`

**服务端那半边本来是对的。** 按 `ItemDrainCategory.setRecipe`（同样只有三个槽，但**流体产出在前**）写出了槽序：

```
INPUT (27, 8)  EmptyingRecipe.getIngredients().get(0)   // 物品条目
OUTPUT(132, 8) EmptyingRecipe.getResultingFluid()       // 流体产出
OUTPUT(132,27) getResultItem(recipe)                    // 物品产出
```

新序列 `CreateDrainSlotSequence` 按形状各取一条结果、按**页面顺序**命名（`results` 数组在 5 个真实文件里是 `[item, fluid]`，与页面相反——又一个"区位列表会写错位"的点），smoke 用例也通过了。

**客户端那半边过不去，所以回退。** 声明后 40 条采样全部建模（这一页 80 条里 78 条是 `ItemDrainCategory#consumeRecipes` 按物品流体能力现算的），而客户端测试的派生形状断言对这 78 条报出 **57 条失败**：

```
the derived model of create:empty_mekanism_creative_fluid_tank_of_mekanism_brine
    draws 1 input slots but its item band (0) and fluid band (0) hold 0
    leaves the item/fluid slot output.1 empty, so a drag of that kind has nothing to resolve
    shows an input slot as nothing addressable, which is neither the item band nor the fluid band
```

原因很具体：这些现算条目的输入槽放的是**带组件补丁的堆叠**（Mekanism 创造流体罐），`RecipeAdapterSupport.editorIngredient` 按契约拒绝带组件补丁的东西，于是"nothing addressable"；它们的物品产出本来就是空的。派生声明的契约要求每个输入槽都能寻址（"neither the item band nor the fluid band"这条断言正是为此），**所以这一页无法用现有机制声明**。

**处置**：回退声明并删掉 `CreateDrainSlotSequence`（保留两页共用的 `CreateIngredientShapes.split`），`create:draining` 记 `gap`，理由（含上面三条实测文本）写进生成器；W2 行改成"试过并回退"。它真正需要的是「**槽位不可寻址时怎么表达**」这类能力——与 `cobblemon:campfire_pot` 的"无字段槽位"同源，两页因此一起归入"需要新机制"。

**本轮门禁数字**：88 页 wired、**28 页 `gap`**（27→28）、3 页已知不支持、2123 个模型、0 失败；declared-smoke 52 条 + 19 条探针。

## 十八、清单收尾：`todo` 只剩三个"需要新机制"的页面

* 三个 `minecraft:tag_recipes/*`（1052 / 575 / 45）从 `todo` 改成 `gap`：它们根本不是配方页，只是列出标签成员；客户端测试本来就有一张"已知不支持"名单单独断言它们零模型。现在两张名单一致（同一页被断言两次"零模型"，无害），脚本里那句"文档里仍是 todo"的注释也同步改掉了，理由写进 `NOT_COVERED_UIDS` 的注释。
* 路线图同步：**W3**（B 组普查）改成"33 页已逐页判定"（14 已声明 / 18 记 `gap` / 1 待机制），并新增 **W4「新机制」** 行，把三个页面的障碍写清楚：`cobblemon:campfire_pot_recipe`(118) 与 `create:draining`(80，已试接并按 57 条实测失败回退) 需要「槽位不可寻址 / 无字段槽位」的表达能力；`create:mechanical_crafting`(4) 的 `pattern` 格子共享 `key` 字母、`create:sequenced_assembly`(3) 每一步都要独立路径序列，都需要先定语义（按字母寻址并写明连带修改，或拒绝该页）。
* 结果：`todo` 从 6 行降到 **3 行**，且每一行都指着一个明确的机制缺口；`gap` 31 行，理由都在生成器里、并被门禁按"零模型"断言着。

**本轮门禁数字**：88 页 wired、**31 页 `gap`**（28→31，含与"已知不支持"重复断言的 3 行）、2123 个模型、0 失败；declared-smoke 52 条 + 19 条探针。

## 十九、扩派生契约 + `cobblemon:campfire_pot_recipe`（118 条）接入

**新契约**：`DerivedSlotSequence.fieldBackedInputCount(Recipe<?> loaded, int inputCount)`（默认"全都有字段"；§二十把它泛化成 `fieldBackedInputOrdinals`，因为 mechanical_crafting 的无字段槽位夹在中间而不是前缀）。客户端没有配方 JSON，但它**持有模组加载好的配方对象**，所以序列可以据此告诉模型"前 K 个输入槽是有字段的"；其余槽位被建模成**只读**（模型槽的 `ingredient() == null`），拖拽不会被提供；手工补丁落在那序号上也会被拒——`createDerivedRecipeJson` 解析不到该序号，直接拒绝整条补丁。

配套改动极小、只有两处调用点：`ModdedRecipeModelSupport.createModel` 增加 `Recipe<?> loaded` 参数（旧的 4 参重载保留并委托 null），`JeiRecipeEditorPlugin` 与客户端测试分别传入 `RecipeHolder#value()`（或裸配方对象）；模型对 `index >= fieldBacked` 的输入槽不再取代表原料。

**为什么 campfire_pot 非它不可**：`CampfirePotRecipeCategory.setRecipe` 先为 `getIngredients()` 的每条加一个输入槽，**再**在 `getSeasoningProcessors()` 非空时，为每个匹配 `seasoningTag` 的示例调味料**多加一个输入槽**——那是"你可以加这些"的预览，没有任何 JSON 字段对应。用 `ingredients.%d`（可重复字段命中其后所有序号）会把预览槽也命名，而落盘时 `writePath` 对"数组下标 == 长度"的处理是**追加**：等于凭空给配方加一条原料。

**这一页的形状与覆盖**：
* 90/117 个文件是 `cobblemon:cooking_pot_shapeless`（`ingredients` + `result`）→ 本轮声明（派生序列：每条 ingredient 一个槽、一个 `result` 产出、`fieldBackedInputCount = getIngredients().size()`，§二十里改名/泛化成 `fieldBackedInputOrdinals` 的「前 N 个序号」）；
* 27/117 是 `cobblemon:cooking_pot`（`pattern` + `key`）→ **不声明**，客户端不建模（本轮客户端报 40/40 采样建模，槽形最大 7 输入，正是"只建 shapeless 那批"的证明）；
* 14 条带 seasoning 的 shapeless 配方里，预览槽现在被建模成只读；
* 5 条产出带组件（`suspicious_stew_effects` 等）→ 输出槽按契约显示为空、拒绝改写（与 IE blueprint 同类）。

**smoke 证据**：

* 新用例 `cobblemon:campfire_pot_recipe (ingredients by sequence)`：改写一条 ingredient 与产出，`category`/`seasoningTag`/其余 ingredient 逐字保留：

```json
{"category":"medicines","ingredients":[{"item":"minecraft:bucket","count":1},{"item":"cobblemon:pecha_berry"}],
 "result":{"id":"minecraft:potion","count":1},"seasoningProcessors":[],"seasoningTag":"cobblemon:empty",
 "type":"cobblemon:cooking_pot_shapeless"}
```

* 新探针 `(a seasoning preview slot has no field)`：`black_aprijuice`（3 条 ingredient + 1 个 processors）的 `input.3` 打补丁 → `Import failed: stale or unsupported patch`、且**没有留下文件**；若按 `ingredients.%d` 声明，同一条补丁会追加成第 4 条原料。

**本轮门禁数字**：**89 页 wired**（88→89）、31 页 `gap`、3 页已知不支持、**2163 个模型**（2123→2163，+40）、0 失败；declared-smoke **53 条 + 20 条探针**。

## 二十、派生契约泛化 + `create:mechanical_crafting`（4 条）接入

**这一页的形状**：`MechanicalCraftingCategory` 把 `pattern` 的**每个格子**画成一个 INPUT 槽——空格也算一格，
按 reading order——而 `getIngredients()`（它继承 vanilla `ShapedRecipe`，拿到的是 `ShapedRecipePattern`
的稠密格子）一个格子一个 `Ingredient`，空格是 `Ingredient.EMPTY`。实测：`crushing_wheel` 的 5x5 图案画
25 个输入槽（13 个非空），`wand_of_symmetry` 的 3x5 画 15 个。JSON 那边却**按字母**命名原料
（`key.<字母>`），所以「槽位序号 0..N-1」与「字段」不是一对一。

**契约泛化**：上一轮的 `fieldBackedInputCount(loaded, inputCount)` 只能表达「前 K 个槽位有字段」，
而 mechanical_crafting 的无字段槽位**夹在中间**（空格序号的前后都是字母格）。本轮把它换成
`fieldBackedInputOrdinals(loaded, inputCount) -> Set<Integer>`（默认返回全部序号），并让 `DerivedSlot`
允许 **paths 为空**——显式表示「这个槽没有字段」，而不是靠「不在前缀里」去推断。

**两侧的语义**：
* 客户端：`ModdedRecipeModelSupport.createModel` 对不在集合里的输入序号把模型槽建成只读
  （`ingredient()` 为空、不给拖拽）；
* 服务端：`RecipeEditsApplier.createDerivedRecipeJson` 解析到某个槽的 `paths` 为空时**直接拒绝整条补丁**，
  于是空格格既不会被退化成「前缀计数里的一个字段」，也不会按数组下标越界**追加**一条原料。

**`CreateMechanicalCraftingSlotSequence` 的做法与自查**：
* 稠密格子（row-major、含空格）一格一槽，行宽必须一致（codec 的要求）；
* 空格 → 空 `paths`；字母 → `key.<字母>`；
* 与模组加载出的配方**逐项对照**：`loaded.getIngredients().size()` 必须等于格子数；第 i 格「是空格」
  必须等价于第 i 个 `Ingredient.isEmpty()`；字母格的 `key` 必须解码成与第 i 个 ingredient 同一批物品
  （`CreateIngredientShapes.sameItems`）。这几条检查抓下过我第一版「只数非空格字符」的映射
  （客户端报 `draws 25 input slots but its item band (21)`）。
* **同一字母的多个格子共享一个字段**：`key` 是字母级的，Create 的 JSON 表达不了逐格原料，所以补丁打到
  `wand_of_symmetry` 的任一 `G` 格都会改写全部 `G` 格（序号 1、3、5）——这是配方自身的结构，已在类注释与
  smoke 用例里写明。

**smoke 证据**：
* 声明用例 `create:mechanical_crafting (a shared key letter) [input.1 = letter G]`：把 `wand_of_symmetry`
  的 `input.1` 改成 `minecraft:iron_ingot`，产物里 `key.G` 从 `{"tag":"c:glass_blocks"}` 变成
  `{"item":"minecraft:iron_ingot","count":1}`，而 `B`/`E`/`O`/`P` 四个 key、`pattern`、
  `accept_mirrored`、`category` 逐字保留；
* 探针 `(a blank pattern cell has no field)`：`crushing_wheel`（`pattern:[" AAA ", …]`，序号 0 是空格、
  字母从序号 1 才开始）的 `input.0` 打补丁 → `Import failed: stale or unsupported patch`、不落文件。
  探针**故意不指向 `wand_of_symmetry`**：探针导入前会先删掉目标产物文件，与声明用例同文件就会把那条用例的
  产物删掉，`after reload` 断言随即报「generated recipe was not written」（这坑踩过一次，别再用同一条配方
  同时做声明用例和拒绝探针）。

**客户端那条新断言**：派生页里「页面上该输入槽显示的原料为空」必须**不在** `fieldBackedInputOrdinals`
里（序列确实声明了它没有字段）；反向——序号在集合里却什么都不显示——才算问题。这两半写反过一次
（`!contains` 写成了正向判定），当时 mechanical_crafting 的每个空格格都会被报成问题；现在由
`itemSlots + fluidSlots + fieldlessSlots == inputs` 把三类槽位一起算平。

**顺带修掉的工装问题**：`runClientTest` 进的一次性世界里，「在关卡里 + 无界面」会让游戏抓走并居中操作者的
光标，而原先只有 AutoJS 工装有交还步骤。现在 `JeiEditorClientTests` 每 tick（含报告写完之后）都执行一次
`releaseGrabbedMouse()`（见 §七.4）。

**本轮门禁数字**：**188 页**、**2167 个模型**（2163→2167，+4）、**90 页 wired**（89→90）、31 页 `gap`、
3 页已知不支持、0 失败；unit 59 条；declared-smoke **54 条 + 21 条探针**。清单里 `todo` 只剩
`create:sequenced_assembly`（3 条，每一步都要独立路径序列）。

## 二十一、`create:sequenced_assembly`（3 条）接入：逐槽路径 + 清单 `todo` 清零

**页面形状**（Create 1.21.1-6.0.10，`javap -c`）：`SequencedAssemblyCategory.setRecipe` 先为一
个 base 输入（`getIngredient()`，JSON 的 `ingredient`）和一个输出（`getResultItem(...)` =
`resultPool.getFirst()`，即 `results` 里**第一个物品结果**）各加一个槽，然后为 `getSequence()` 的每一步
加一个 sub-category，由它决定这一步画不画槽：

| 步骤 `type` | sub-category | 画的槽 |
| --- | --- | --- |
| `create:deploying` | `AssemblyDeploying` | 1 个输入 = `getIngredients().get(1)`，即该步**第 2 个物品**条目（第 0 个是它加工的半成品） |
| `create:filling` | `AssemblySpouting` | 1 个输入 = `getFluidIngredients().get(0)`，即该步**第 1 个流体**条目 |
| `create:pressing` / `create:cutting` | `AssemblyPressing` / `AssemblyCutting` | 不画（只 override `draw`） |

这四个是 Create 里仅有的实现 `IAssemblyRecipe#getJEISubCategory` 的类型（全 jar 扫描确认），所以别的
步骤类型**拒绝**而不是猜。实测槽形与推导一致：`precision_mechanism` 4 输入、`track` 3 输入、
`sturdy_sheet` 2 输入。

**为什么是「逐槽路径」而不是字段列表**：槽位序号跟着 `sequence` 的每一步走，每个槽各在自己的一层
（`ingredient` vs `sequence.<i>.ingredients.<j>`），不存在一个能算出来的下标公式。`DerivedSlot` 的
`paths` 本来就是列表，所以这一步不需要新契约——上一轮的 `fieldBackedInputOrdinals` 在这里也用不上
（每个画出来的槽都有字段，默认「全都有字段」就是对的）。

**唯一的新契约面**：`DerivedSlotSequence.drawsBandsInDisplayOrder()`（默认 true）。客户端测试有一条
通用断言「物品带全在流体带之前」，那是**盆地页**的性质（它把物品原料合并成一带并放在前），用在这里就
错了——filling 步的流体槽可能夹在两个 deploying 步之间。声明 false 即跳过这条断言，其余断言照旧。

**没有模组侧对照表**：`SequencedAssemblyRecipe` 不 override `getIngredients()`（接口默认返回空表），
所以不像盆地页那样能拿模组解析出的原料表逐项对照。保护改由**形状**提供：每个步骤的 `ingredients`
数组都按 codec 自己的规则分类（流体 = `type` 是注册过的流体原料类型，见
`CreateIngredientShapes.isFluidIngredient`），分不了的条目整条拒绝；为此给 `CreateIngredientShapes`
加了 `split(JsonArray)`（无对照版的分类），原来那个带对照的重载改为调用它再比对。

**流体条目写回的坑**：写回是**整节点替换**（`writePath` 的 `array.set` / `add`），而 Create 的
`SizedFluidIngredient` codec **要求显式 `type`**——裸 `{"fluid":…,"amount":…}` 会被拒。所以这一页的
流体输入风格必须是 `ModdedJsonStyle.TYPED_FLUID`（会写出 `type: neoforge:single`）。smoke 用例钉住了
这一点：写完 `input.1`（水 1000）之后，`sequence.0.ingredients.1` 仍是
`{"type":"neoforge:single","fluid":"minecraft:water","amount":1000}`。

**smoke 证据**（`create:sequenced_assembly` 三条各出现一次）：
* `(a deploying step writes its held item and the output)`（`precision_mechanism`）：`input.1` →
  `sequence.0.ingredients.1` 变成 `{"item":"minecraft:iron_ingot","count":1}`；同一步的第 0 个条目、
  第 2/3 步的 held item、`loops`、`transitional_item` 逐字保留；`results` 里**同级**条目
  `results.1` 保住 `chance: 8.0` 与 id。输出写的是 `results.0`，整节点替换 —— 这是所有已声明
  Create `results` 条目共有的已知限制（该类注释里早有记录），本页 `results.0` 的 chance 本来是
  `120.0`、codec 默认就是必掉，所以语义不变；smoke 用 `Absent = @('results.0.chance')` 把这条限制
  钉成回归用例。
* `(a filling step writes its fluid)`（`sturdy_sheet`）：`input.1` → 该步第 1 个条目变成
  `{"type":"neoforge:single","fluid":"minecraft:water","amount":1000}`，两个 pressing 步与 `results`
  逐字保留。
* 探针 `(an ordinal past the steps the page draws)`（`track`，画 3 个输入）：`input.3` →
  `Import failed: stale or unsupported patch`、不落文件。探针**故意用 `track`**（声明用例用的是另两份
  配方）：探针导入前会删掉目标产物文件，与声明用例同文件会把那条用例的产物删掉。

**清单收口**：`docs/JEI_PAGE_COMPAT_TODO.md` 里 `create:sequenced_assembly` 由 `todo` 变 `declared`，
**`todo` 行数归零**（生成器里 W3/W4 的措辞同步改成「`todo` 已清空」）。B 组 W4 行现在只留
`create:draining` 一个「仍需要新机制」的条目。

**本轮门禁数字**：**188 页**、**2170 个模型**（2167→2170，+3）、**91 页 wired**（90→91）、31 页 `gap`、
3 页已知不支持、0 失败；unit 59 条；declared-smoke **56 条 + 22 条探针**。
