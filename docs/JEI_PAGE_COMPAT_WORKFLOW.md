# 单个 JEI 配方页面接入可视化编辑：工作流

本文件定义"把 JEI 里的一个配方页面变成可可视化编辑"的标准流程。兼容清单见
[JEI_PAGE_COMPAT_TODO.md](JEI_PAGE_COMPAT_TODO.md)，每个页面按本流程落地。

- 目标环境：Minecraft 1.21.1 / NeoForge 21.1.238 / JEI 19.44.0.403
- 现有已支持的页面（vanilla）见 [SUPPORTED_RECIPES.md](SUPPORTED_RECIPES.md)
- 架构边界见 [ARCHITECTURE.md](ARCHITECTURE.md)：`common` 不允许出现 Minecraft / JEI 类型

## 0. 什么叫"已支持"

一个页面算接入完成，必须同时具备下面这些能力：

| 能力 | 客户端落点 | 服务端落点 |
| --- | --- | --- |
| 悬停时能识别出语义槽 | `EditorModel`（`input.N` / `output`） | — |
| 拖入物品替换槽 | `RecipeEditorAdapters.replaceSlot` | `RecipeEditsApplier.createRecipeJson` |
| 清空输入槽 | `RecipeEditorAdapters.clearSlot` | 同左 |
| 滚轮改产出数量 | `RecipeEditorAdapters.setOutputCount` | 同左 |
| 删除该配方 | `RecipeDeletionAdapter` | 同左 |
| 以该页为模板新建配方 | `RecipeCreationAdapter` + `RecipeCreationRules` | 同左 |

只要"拖入替换 + 落盘 + 重载后生效"这条主链通了，就算该页面已接入；其余能力可以后补。

## 1. 页面形态判定

动手前先判定页面属于哪一类，这决定了能不能做、怎么做：

| 形态 | 判据 | 能否编辑 | 处理方式 |
| --- | --- | --- | --- |
| A 数据包配方页 | 页面上的对象是 `RecipeHolder<?>`，`type` 能在 `BuiltInRegistries.RECIPE_SERIALIZER` 里查到 | 能 | 新增 adapter + 写回配方 JSON |
| B JEI 合成页 | 对象不是 `RecipeHolder`，由 JEI 插件代码生成（燃料、酿造、铁砧、砂轮、堆肥、信息页） | 视页面而定 | 照 `JeiVanillaRecipeEditorAdapter` 的模式，SavedData + 运行时覆盖 |
| C 信息页 | 本身不是配方，只是展示（效果说明、掉落、图鉴、进度） | 不适用 | 不接入 |
| D 第三方面板 | JEI 页面里嵌的自定义 GUI，没有槽位概念 | 不适用 | 不接入 |

判据来自运行时导出：`targets/neoforge-1.21.1/run/jei-category-dump.txt`
（由临时诊断类 `JeiCategoryDiagnostic` 生成，取证完成后删除），其中：

- `recipeClass` 是 `net.minecraft.world.item.crafting.RecipeHolder` → 形态 A
- `sampleRecipe` 里带 `id=` 和 `type=` 的同样是形态 A，`type=` 就是 `serializerId`
- 既不是 `RecipeHolder` 也没有 `type=` → 形态 B/C/D

### 1.1 A 组的四种边界（都已有页面踩过，判完要落到 `gen_checklist.py` 的集合里）

| 边界 | 样子 | 处置 |
| --- | --- | --- |
| 显示的是 vanilla 配方本身 | holder 是模组的 wrapper，但 `id` 落回管理器里那条 vanilla `crafting_shaped` / `crafting_shapeless`（Create 的 `create:automatic_packing`、`create:fan_smoking` …） | **不加声明**（按 serializer 的声明会把页面从 vanilla 适配器手里拿走），在 `RESOLVED_VANILLA_UIDS` 具名 + 客户端测试断言"至少一个模型"，标 `done` |
| 有 serializer，但配方只在代码里 | 管理器里没有、也没有配方 JSON（`jearchaeology`、IE 的 `arc_recycling`、Create 的 `mystery_conversion`、Silent Gear 的 fabric/super 合金） | `NOT_COVERED_UIDS` → 标 `gap`，门禁反过来断言 0 模型 |
| 显示对象是模组 JEI 插件里的展示类 | 类在 `compat` / `integration` / 客户端 recipe-viewer 包里，没有 `id`、没有 holder（`cobblemon:berry_recipe`、`just_enough_beacons:*`、`mekanism:*_casing` …） | 同上一行：`NOT_COVERED_UIDS` → `gap` |
| 真配方、真 JSON，但槽位寻址不了 | 页面把 `pattern` 的格子画成槽而 `key` 按字母共享；输入槽是带组件补丁的堆叠；页面上有"没有 JSON 字段"的展示槽；槽位顺着配方内部结构排布、每个槽各在一层（`create:mechanical_crafting`、`create:draining`、`cobblemon:campfire_pot_recipe`、`create:sequenced_assembly`） | 三种已有解：① `create:mechanical_crafting` / `cobblemon:campfire_pot_recipe` 已用**派生序列 + `fieldBackedInputOrdinals`** 接入（无字段的槽位如实标记、不可寻址）；② `create:sequenced_assembly` 用**逐槽路径**接入（槽位跟着 `sequence` 的每一步走，序号到字段是一张路径表）；③ 找不到机制就记 `gap`，并把**实测**到的失败原文写进理由（`create:draining` 就是这么回退的：57 条 `[B]` 失败）。清单里 `todo` 已清空 |

## 2. 数据模型

跨边界的三件套都在 `common`（纯 Java，无 Minecraft 依赖）：

```text
EditorModel(recipeId, serializerId, baseFingerprint, slots, properties)
EditorSlot(key, role, ingredient)        role ∈ {"input", "output"}
EditorIngredient(kind, id, amount)       kind ∈ {item, fluid, chemical}
RecipePatch(recipeId, serializerId, baseFingerprint, fields)
    fields 的键随 ingredient 的种类走（IngredientKind / SlotPatchFields）：
      item     <slotKey>.item     / <slotKey>.count
      fluid    <slotKey>.fluid    / <slotKey>.fluid_amount
      chemical <slotKey>.chemical / <slotKey>.chemical_amount
```

- `recipeId`：形态 A 用配方注册 ID（`<ns>:<path>`）。
- `serializerId`：`BuiltInRegistries.RECIPE_SERIALIZER` 的键，也就是配方 JSON 里的 `type`。
- `baseFingerprint`：由 `RecipeAdapterSupport.fingerprint(recipeId, serializerId, slots, properties)`
  生成（`RecipeModelSupport` 里是 SHA-256）。服务端拿它做并发保护，不匹配直接拒绝落盘，
  所以 fingerprint 必须覆盖所有会影响写盘结果的来源数据。
- `properties`：键值对，上限 64 条。既用来存 JEI 槽位映射，也用来存写 JSON 时要还原的
  mod 特有字段。

## 3. 槽位键 ↔ JEI 槽位

映射由 `CraftingSlotMapper.slotKey` 统一处理，按下面顺序命中：

1. JEI 槽名等于模型槽键（`IRecipeSlotView.getSlotName()`）——最稳，JEI 层直接给语义；
2. `properties["jei.name.<JEI槽名>"]` → 模型槽键（`NAMED_SLOT_PROPERTY_PREFIX`）；
3. 非合成类：`properties["jei.input.<JEI输入序号>"]` → 模型槽键（`VISUAL_INPUT_PROPERTY_PREFIX`）；
4. 非合成类兜底：`input.<JEI输入序号>`；
5. 合成类：按 JEI 的实际网格几何推算。

因此新 adapter 只要**按 JEI 的输入顺序**给槽键 `input.0..input.N`，就能不动
`CraftingSlotMapper`。顺序对不上时，用第 2/3 条的 properties 显式写映射，不要改映射器。

## 4. 六步流程

### 第 1 步：取证

1. 从 `jei-category-dump.txt` 取该页面的 `uid`、`recipeClass`、`recipes`（配方数量）、
   `shape`（样例配方的槽位结构：角色、原料类型、具体物品）、`sampleRecipe`（`type=`）。
2. 从 mod jar 里抽该 `type` 的真实配方 JSON，确认真实 schema：

   ```bash
   unzip -p targets/neoforge-1.21.1/run/mods/<mod>.jar 'data/<ns>/recipe/*.json' | head
   ```

3. 判定四件事：原料是否全为纯物品；是否有 tag/多候选原料；是否含非物品原料
   （流体、能量、气体、化学物质）；产出是否只有一项。
   - 含非物品原料 → 归入"需扩展模型"，本流程暂不处理（见第 6 节坑 2）。
   - 产出多于一项 → 归入"多产出"，同样需要先扩展模型。
4. 在动代码之前先问「页面显示的配方到底在不在服务端」：样例的 `id=` 用
   `server.getRecipeManager().byKey(...)` 查得到，且
   `server.getResourceManager().getResource(<ns>:recipe/<path>.json)` 解析得出配方 JSON，
   才可能用生成数据包写回。两者都不成立时页面可能是「模组在运行时合成」、「id 被改写」
   或「显示的根本不是配方」，对应三种不同处理：合成页按 `gap` 记录并说明来源；
   id 被改写的页在 `platform/recipe/RecipeViewerAliases` 里加一条别名规则（页面 uid →
   该页插入的路径前缀），编辑器与客户端测试会按真实配方建模与写回（`mekanism:smelting` 即此例）；
   都不是配方的页（例如 JEI 的标签页）保持只读。
   这套取证用 `scripts/jei-category-dump/probe-provenance.ps1`（配合临时拷入的
   `RecipeProvenanceProbe.java`）在跑着的服务端上重测：`all:` 一次列出「管理器里存在、
   但配方 JSON 不可达」的全部配方，`serializer:<id>` 按 serializer 计数，
   `jeiprobeforce` 用「改一个字段后重载，看线上配方是否跟着变」判定数据包文件能不能覆盖它。

### 第 2 步：客户端建模

新建 `targets/neoforge-1.21.1/src/main/java/cc/sighs/JEIEditor/platform/recipe/<Name>RecipeEditorAdapter.java`：

```java
public static Optional<EditorModel> createModel(RecipeHolder<?> holder, HolderLookup.Provider registries)
```

- 先用 `holder.value()` 的具体类型 + `serializerId` 白名单判定，不匹配返回 `Optional.empty()`。
- 原料：`RecipeAdapterSupport.simpleIngredient(ingredient)` 归一化成代表物品；
  无法归一化（空数组、只有空气、带组件）时按需决定返回 empty 还是留空槽。
- 产出：`RecipeAdapterSupport.simpleStack(result)` 归一化；失败返回 `Optional.empty()`。
- 需要还原的 mod 特有字段、槽位顺序映射，写进 `properties`。
- `fingerprint` 用 `RecipeAdapterSupport.fingerprint(...)`。

可复用的现成实现：`CraftingRecipeEditorAdapter`（网格）、`CookingRecipeEditorAdapter`（单入单出）、
`VanillaSpecialRecipeEditorAdapter`（切石机 / 锻造）。

### 第 3 步：补丁语义

同一个类里实现四个静态方法，字段名不要自创：

| 方法 | 作用 |
| --- | --- |
| `replaceInput(model, slotKey, stack)` | 替换输入槽 |
| `replaceOutput(model, stack)` | 替换产出 |
| `clearSlot(model, slotKey)` | 清空输入槽（写 `item=minecraft:air`、`count=0`） |
| `setOutputCount(model, count)` | 改产出数量 |

### 第 4 步：注册

`RecipeEditorAdapters` 有五个分发点，**全都要加分支**，否则会出现"能拖但应用失败"
这类半通状态：

- `createModel` → 新 adapter 的 `createModel`
- `replaceInput`
- `replaceSlot`（注意 `output` 与输入的走向不同）
- `clearSlot`
- `setOutputCount`

顺序：放在 vanilla 分支之后、兜底 `CraftingRecipeEditorAdapter` 之前。

### 第 5 步：服务端写回

`RecipeEditsApplier` 两个落点：

1. `createRecipeJsonContent(holder, patch, model)` 里加
   `else if (holder.value() instanceof XxxRecipe)` 分支，产出该类型的配方 JSON。
   两条路线，按 mod 特有字段多少选：

   - **(a) 从 Recipe 对象重建 JSON**：现有 vanilla 分支都是这条，字段全写死。
   - **(b) 读原始 JSON 再打补丁**：`readRecipeJson(server, recipeId, recipePath)` 已经内置回落，
     当生成包文件不存在时会去 `server.getResourceManager()` 读 mod 原始 JSON。mod 特有字段多、
     又不想逐个还原时走这条，只替换被编辑槽位对应的 JSON 节点，其余原样保留。

2. `validatePatch(model, patch)` 放行新 serializer。字段名按**原料种类**区分，由
   `common` 的 `IngredientKind` / `SlotPatchFields` 统一定义，不要在适配器里自创：

   | 种类 | 字段 | 取值范围 |
   | --- | --- | --- |
   | item | `<slotKey>.item` / `<slotKey>.count` | 1..64 |
   | fluid | `<slotKey>.fluid` / `<slotKey>.fluid_amount` | 1..1000000（mB） |
   | chemical | `<slotKey>.chemical` / `<slotKey>.chemical_amount` | 1..1000000 |

   `recipe.*` 自定义字段仍需要显式加白名单（参考 `CookingRecipeEditorAdapter` 的
   `experience` / `cooking_time` 分支）。声明页面的种类必须与声明的字段一致，
   `validatePatch` 会拒绝不一致的补丁。

### 第 6 步：验证

1. `common/src/test` 加单测（参考 `RecipeEditorModelTest` / `RecipeRulesTest`）：
   模型槽位数量与顺序、补丁字段内容、非法输入被拒。
2. 构建：`cd targets/neoforge-1.21.1 && ./gradlew.bat build`（JDK 21）。
3. 页面接入完成的判定命令是单一入口：它依次跑 common 单测、客户端配方页测试、
   声明式服务端冒烟测试，每阶段打印一行摘要，任一阶段失败即非零退出。**改任何页面级
   代码之前都先跑它**：

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\jei-verify.ps1
   ```

   只想单独看服务端那一半（无 GUI 自动验证）：

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\jei-declared-smoke.ps1
   ```

   它会起一个专用服务端（RCON 驱动），导入声明式补丁，然后断言生成包里的 JSON：
   被编辑的槽改成了该模组自己的字段形态、**没动过的键原样保留**、
   服务端能把它重新解析成配方（否则 `apply` 会回滚并报错，文件根本不会留下）、
   `/reload` 之后内容不变。给新页面加一条用例就是在脚本的 `$cases` 里加一项。
   另一半（客户端建模与补丁构造）由 `scripts/jei-client-tests.ps1` 覆盖，见 §7.2。
   两个合起来才是完整链路：把这一页加进 `docs/JEI_PAGE_COMPAT_TODO.md` 后跑这两个脚本，
   应当都通过。
4. 实机：启动开发客户端 → 进世界 → 打开该页面 → 进入编辑模式 → 拖入物品 → 应用。
5. 落盘检查：

   ```text
   targets/neoforge-1.21.1/run/saves/<世界>/datapacks/jeieditor-generated/data/<ns>/recipe/<path>.json
   ```

6. 生效检查：`/reload` 或重进世界后，配方产出/原料确实变了；JEI 显示同步。
7. 回归检查：不要动过的同类配方，页面显示与原始一致（没被写坏）。

## 5. 验收口径

一个页面只有满足下面全部条件才在清单里标 `done`：

- 编译通过，`./gradlew.bat build` 成功；
- 单一入口 `scripts/jei-verify.ps1` 三个阶段全部通过（common 单测 + 两个 harness）；
- 新增单测通过；
- 实机拖入物品 → 应用 → 生成包里出现预期 JSON；
- reload 后配方实际生效；
- 未编辑的同类配方没有被破坏。

清单里的状态词由 `scripts/jei-category-dump/gen_checklist.py` 从源码与 dump 推出，含义固定：

| 状态 | 含义 | 门禁怎么断言 |
| --- | --- | --- |
| `done` | vanilla 页面，或显示 vanilla 配方的页面（`RESOLVED_VANILLA_UIDS`） | `jei-client-tests.ps1` 要求**至少一个模型** |
| `declared` | 该 serializer（或该页面 uid）有声明 | 同上 |
| `gap` | 不可建模：配方只在代码里 / 显示的是展示类 / 缺槽位表达能力（`NOT_COVERED_UIDS`、`withReadOnlyPages`） | 反过来要求**零个模型**——哪天有人修好了，门禁会红，提示回来改清单 |
| `todo` | 还没做，且没有已确认的硬障碍 | 不作断言 |

另有 `scripts/jei-client-tests.ps1` 里一张**已知不支持**名单（`minecraft:tag_recipes/*`），也是按"必须零模型"断言的。

只做了客户端建模、服务端还没通的，标 `partial`，并写清缺哪一环。

## 6. 常见坑

1. **tag / 多候选原料**：未编辑时必须原样保留。现有做法是用 `Ingredient.CODEC` 把原
   ingredient 编码回 JSON，只有被用户编辑过的槽才收窄成具体物品
   （见 `cookingIngredientJson` / `vanillaIngredientJson`）。
2. **非物品原料**：已经支持。`EditorIngredient` 带 `IngredientKind`（item / fluid / chemical），
   槽位补丁按种类用 `<slotKey>.fluid` / `<slotKey>.chemical` 这类字段名传（见第 2 节）。
   声明式页面还要给字段标**原料种类**（`withFieldKind` / `withFluidFields` / `withChemicalFields`，
   默认物品）：客户端建模与补丁构造按该声明收窄，服务端 `validatePatch` 也会拒绝种类与声明
   不符的补丁。只要该页面的配方 JSON 里原料是单值（不是嵌套多候选），按第 8 节的声明式路径
   接入即可；声明不全（某个流体字段漏标种类）会被客户端测试直接判失败。
   实测（`run/jei-ingredient-types.txt`，JEI 运行时导出）这些原料已在 JEI 里注册且全部可见：
   `fluid_stack` 64 条、`mekanism.api.chemical.ChemicalStack` 64 条，可见数等于总数。
   另外 Mekanism 的气体/灌注物/颜料/浆液共用同一个 `ChemicalStack` 类型，
   在索引里是一个整体，不是四个分组。
3. **多产出**：已支持。槽位键是 `output`（只有一个产出时）或 `output.0` / `output.1` / …（多个），
   声明侧用有序段列表（`withConcatenatedOutputs`）或 `%d` 输出字段表达"哪个序号落到哪个 JSON 字段"；
   客户端测试会逐个产出槽核对声明种类与页面实际显示的原料。
4. **槽位顺序**：JEI 的输入顺序不保证等于 JSON 数组顺序。核对不上就用
   `properties["jei.input.<n>"]` 写显式映射。
5. **fingerprint 失效**：服务端 `createRecipeJson` 会校验 `baseFingerprint`，
   建模时漏掉的数据会导致应用被拒（表现为"配方补丁已过期或不支持"）。
6. **物品限制**：`RecipeAdapterSupport.simpleStack` 拒绝空物品、数量超 64、
   以及任何带组件补丁的 `ItemStack`（附魔、自定义名、容器内容）。
7. **不要改共享文件里的通用逻辑**：新页面只加自己的 adapter 与分支，
   通用槽位映射、通用传输、通用 SavedData 不应为单个页面改动。
8. **清空只对"可变元元素"生效**：`ModdedRecipeAdapter.isRepeatField` 是判定，
   只有被 `%d` 模式命中的字段才会在清空时**删掉数组元素**；固定元素/固定列表的清空整条拒绝
   （服务端拒绝、客户端抛 `IllegalArgumentException`）。原因：`minecraft:air` + `count 0`
   在 mod 的 codec 里不合法，而"删元素"会让列表变短——`create:item_application` 就是这样被
   写坏的：它的页面画两个输入，`ItemApplicationRecipe.getRequiredHeldItem()` 在列表少于 2 条时
   抛 `"Item Application Recipe has no tool!"`，而 Create 的 JEI category 建布局时会走这个方法，
   于是**整个配方区渲染崩掉**（E11）。可反之，`create:blueprint` 的 `inputs.%d` 清空是"备用槽移除"，
   属于有意行为，两半都有 smoke 用例钉住。
9. **vanilla 重建路径的两处保真**：重建整条记录时要从**原始 JSON** 拷回 `neoforge:conditions`
   （否则带条件的配方会被静默改成无条件，E14），并按记录写回 `category`
   （否则配方书分组被静默改成 `misc`，E13）。declared 路径是就地改 JSON，不受这两条影响。
10. **流体标签不是"只换 key 的物品标签"**：物品标签保持只写 `{"tag":…}`（模组自己的形状，
   普通 `Ingredient` 会忽略 `count`）；流体标签必须带 `amount`（`SizedFluidIngredient` 的 amount
   有默认值 1000，漏写会静默改体积），Create 的流体还要把 `type` 写成 `neoforge:tag`。
11. **派生序列（`DerivedSlotSequence`）的契约**：客户端只校验数量带与种类带，服务端按 JSON 解析
   每条槽的路径；序列必须复现模组自己的推导（对着 category 的 `setRecipe` 字节码），并在能对照时
   与模组加载出来的对象比对（如 `getIngredients()`），对不上就拒绝整条补丁。
   **页面上"没有 JSON 字段可寻址"的槽位现在是可表达的**：`DerivedSlot` 允许 `paths` 为空（表示
   "这个槽没有字段"），客户端靠 `fieldBackedInputOrdinals(loadedRecipe, inputCount)` 知道**哪些**
   输入序号有字段、其余建模成只读，服务端解析到空 `paths` 就**拒绝整条补丁**（不会退化成前缀计数
   或"追加一条"）。两个已接入的形态：`cobblemon:campfire_pot_recipe` 的无字段槽位在**末尾**（预览
   调味料），`create:mechanical_crafting` 的无字段槽位**夹在中间**（`pattern` 的空格格子）——所以契约
   给的是序号**集合**而不是"前 K 个"。仍表达不了的（输入槽本身是带组件补丁的堆叠，如 `create:draining`）
   记 `gap`。
12. **`pattern` + `key` 类页面（按字母寻址）**：`create:mechanical_crafting` 的页面把 `pattern` 的
   每个格子画成一个输入槽（**含空格**，reading order），JSON 却按**字母**命名原料（`key.<字母>`）。
   所以接入时：格子序号就是槽位序号（稠密网格，空格也算一格）；字母格声明路径 `key.<字母>`；空格格
   声明空 `paths`（无字段）。**同一字母的多个格子共享同一个字段**，补丁打到其中任一格会连带改写其余
   同字母格——这是配方自身的结构，必须在注释与 smoke 用例里写明（`wand_of_symmetry` 的 `G` 落在
   序号 1 / 3 / 5）。接入前先用 `python` 读 jar 里的 JSON 把 `pattern` 的稠密序号算清楚，别靠眼睛数。
13. **写回数组的边界**：`writePath` 对数组下标越界**一位**是"追加"（这是可变元列表的备用槽，
   有意为之，如 `create:blueprint` 的 `inputs.%d`），更远则拒绝；因此一个"按序号打补丁"的声明
   若把格子序号当成数组下标，拖到第一个空格会变成"追加一条原料"——`silentgear:gear_crafting`
   就是这个语义，已有 smoke 用例断言。
14. **槽位跟着配方内部结构走（逐槽路径）**：`create:sequenced_assembly` 的页面为 `sequence` 的每一步
   画槽（base `ingredient`；`create:deploying` 画该步**第 2 个物品**条目——第 0 个是它加工的半成品；
   `create:filling` 画该步**第 1 个流体**条目；`create:pressing`/`create:cutting` 不画），所以序号到
   字段是一张路径表（`sequence.<i>.ingredients.<j>`），而不是某个可计算的下标公式。两个要点：①
   `DerivedSlot` 的 paths 本来就是列表，逐槽给就行；② 这类页面**没有模组侧原料表可对照**
   （`SequencedAssemblyRecipe.getIngredients()` 是接口默认的空表），所以只受形状校验保护——每个条目都
   必须按 codec 自己的规则分类（流体看 `type` 是不是注册过的流体原料类型，见
   `CreateIngredientShapes.isFluidIngredient`），分类不了就整条拒绝，不要猜。流体条目写回必须带
   `type`（`ModdedJsonStyle.TYPED_FLUID`），因为写回是**整节点替换**，裸 `{fluid,amount}` 会被
   `SizedFluidIngredient` 的 codec 拒掉。另外这类页面的流体槽可能排在物品槽**之前**，声明
   `drawsBandsInDisplayOrder() == false`。

## 7. 复现取证环境

页面清单、槽位结构和 serializer 都来自一次真实启动的运行时导出，复现步骤：

```powershell
# 1) 把诊断模板临时放回模组源码（用完必须删除，不能提交）
copy scripts\jei-category-dump\JeiCategoryDiagnostic.java targets\neoforge-1.21.1\src\main\java\cc\sighs\JEIEditor\client\

# 2) 启动客户端；诊断会自动创建一个临时世界并在 JEI 启动后导出分类
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\jei-category-dump\dump-categories.ps1 -TimeoutSeconds 1800

# 3) 离线交叉验证：静态扫描各 jar 里的 RecipeType.create 调用
python scripts\jei-category-dump\scan_categories.py
```

- 诊断模板：`scripts/jei-category-dump/JeiCategoryDiagnostic.java`（不在编译范围内，必须手动放回源码目录，取证完删除）。
- 导出结果（都在被忽略的 `run/` 下）：
  - `run/jei-category-dump.txt` —— 每个配方页面的 uid、配方数、槽位结构、serializer；
  - `run/jei-ingredient-types.txt` —— JEI 注册的每种原料类型、总数与可见数（决定它是否出现在右侧索引里）。
- JEI 只在客户端**进入世界后**才创建运行时，因此诊断会自动创建一个临时世界；已有的存档若需要备份确认，程序化加载会崩，所以不要复用旧存档。
- 静态扫描只能看到显式 `RecipeType.create("ns", "path", clazz)` 调用（本次 31 个），
  通过枚举或字段拼出来的 UID 会漏，因此以运行时导出为准。

## 7.1 服务端写回的无 GUI 验证

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\jei-declared-smoke.ps1
```

- 起专用服务端（`runServer` + RCON），把 `run/config/jeieditor/exports/declared-fixture.json`
  里的声明式补丁 import 进去，然后断言生成包 JSON。
- 用具名用例覆盖两类风险：字段被改写成模组自己的形态（IE 的 `{"item",...}` /
  Mekanism 的 `{"item","count"}`），以及未编辑的键（IE 的 `energy`、Mekanism 的
  `main_input`/`output`）与旧 tag 不被残留。
- 另一组用例覆盖「页面显示的配方不在配方管理器里」的那些族（`create:draining`、
  `create:spout_filling`、`immersiveengineering:arc_recycling`、`jearchaeology:brush`/`sniff`、
  `mekanism:smelting` 的改写 id）：每个族一条，断言服务端在写盘前以
  `stale or unsupported patch` 拒绝、且不留下任何文件；`mekanism:smelting` 还多一条正例——
  按 `RecipeViewerAliases` 还原出的真实 id（`cobblemon:passho_berry_smelt_to_dye`）以
  `minecraft:smelting` 写回生成包。这些族的来源实测见 `docs/JEI_PAGE_COMPAT_TODO.md`
  的「运行时合成页面的真实来源」，探针模板在 `scripts/jei-category-dump/`。
- `-RestartCycle` 加一轮停机重启：重启后提交一条只有「已被编辑的配方」才对得上指纹的补丁，
  被接受即证明世界重启后仍然加载生成的数据包（`/reload` 之后同样断言）。
- RCON 传输在 `scripts/jei-rcon.ps1`：瞬时 socket 关闭或接收超时会在新连接上按指数退避重发。
- `chesthelper` 与 `createjeicompat` 会在专用服务端加载客户端类导致启动失败，
  脚本运行期间把它们临时改名、结束后还原（`run/mods` 里的 jar 数量会恢复原状）。

## 7.2 客户端建模与补丁构造的自动验证

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\jei-client-tests.ps1
```

照 JEI 本体的做法：`clientTest` source set 编成第二个测试 mod（`jeieditortests`），
挂在独立的 `clientTest` run 上（ModDevGradle 的 `sourceSet` + `loadedMods`，
`gameDirectory=run/clientTest`），客户端进世界后遍历 JEI 注册的全部页面，
用编辑器自己的建模路径建模型并断言，报告写到 `build/client-test/report.json`，
脚本读报告决定退出码。

- **A** 清单里每个已接入页面至少要有一个配方能建模；`gap` 页面与三个 tag 页必须一个模型都不产生；
- **B** 声明页面的槽键必须正好是声明按本页槽位数展开的结果（`input.0..input.N-1` 加
  `output` 或 `output.0..output.M-1`），且声明字段的**原料种类**必须覆盖页面上该槽真正显示的原料；
- **C** 补丁往返：每个含原料的槽都要能经 `RecipeEditorAdapters.replaceSlot` 产出带该槽自身前缀的补丁，
  空槽按契约处理（声明页空产出必须拒绝，合成网格空格子必须能填）；
- **D** 报告「能建模但清单没声明」的页面，防止误匹配；
- **E** 客户端独立展开出的字段种类必须与服务端 gate 用的 `ModdedRecipeAdapters.declaredKinds`
  一致（两边解析成不同字段即失败）。完整断言清单见 `AGENT.md`。

发布 jar 里不含这些测试代码（`clientTest` 不在 `src/main` 里）。
新页面接入后跑这个脚本，它会把该页面是否真的建得出模型直接报出来。

## 8. 模组配方页的落地形态

mod 配方 JSON 的字段名每个模组都不一样，实测举例：

| 页面 | 配方 JSON 骨架 |
| --- | --- |
| `create:milling` | `{"type","ingredients":[...],"results":[{"id","count","chance"}]}` |
| `immersiveengineering:alloy` | `{"type","input0","input1","result":{"basePredicate","count"}}` |
| `mekanism:combining` | `{"type","main_input","extra_input","output":{"id","count"}}` |

所以"JEI 输入序号 → 配方 JSON 字段名"必须逐类型声明，不能靠通用启发式猜。落地形态：

1. 新增一份**声明**（serializer id → 输入字段顺序 + 产出字段 + 写入时的 item 编码方式），
   例如 `mekanism:combining` → 输入 `["main_input","extra_input"]`、产出 `output`、`{"id","count"}`。
2. 客户端建模走通用路径：读当前显示布局的槽位视图
   （`IRecipeSlotsView.getSlotViews()`，实现见 `ModdedRecipeModelSupport`），每个真实 INPUT 槽
   建一个 `input.N`、单个 OUTPUT 槽建 `output`，槽里有多少候选物品都不影响建模，不需要为每个模组写读代码。
   **不能**用 `JeiRecipeIntrospection.recipeIngredients`：它把同一角色的所有槽位摊平成一个候选列表
   （某个槽的 tag 有几个成员就多几条），槽位数量会被算多。
3. 服务端写回走**原始 JSON 打补丁**：`readRecipeJson` 已经能在生成包里没有文件时回落到
   `server.getResourceManager()` 读出模组自带的那份 JSON，只替换声明里指明的字段，
   其余字段（`neoforge:conditions`、`chance`、`keep_held_item` 等）原样保留。
4. 声明文件的证据来源：`unzip -p run/mods/<mod>.jar 'data/<ns>/recipe/<type>/*.json'`
   抽几份同类型样例，逐字段核对 JEI 的显示顺序。
