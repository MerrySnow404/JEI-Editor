# 1.21.1 NeoForge JEI 页面兼容清单（TODO）

本清单列出开发环境里 JEI 实际注册的**全部配方页面**，并按能否可视化编辑分组，作为逐页接入的待办表。单页接入流程见 [JEI_PAGE_COMPAT_WORKFLOW.md](JEI_PAGE_COMPAT_WORKFLOW.md)，已支持的 vanilla 页面见 [SUPPORTED_RECIPES.md](SUPPORTED_RECIPES.md)。

- 目标环境：Minecraft 1.21.1 / NeoForge 21.1.238 / JEI 19.44.0.403
- 模组环境：`targets/neoforge-1.21.1/run/mods/` 下 68 个启用 jar（清单见 [JEI_ADDONS_1.21.1_NEOFORGE.md](JEI_ADDONS_1.21.1_NEOFORGE.md) 与 [JEI_ADDONS_CONTENT_MODS.md](JEI_ADDONS_CONTENT_MODS.md)）
- 数据来源：开发客户端实际启动并进世界后，由临时诊断导出 `run/jei-category-dump.txt`（188 个页面）。静态扫描脚本 `scan_categories.py` 作为交叉验证，只能看到显式 `RecipeType.create` 调用，因此列表以运行时为准。
- `run/` 被 `.gitignore` 忽略，导出文件与两个扫描脚本都不入库。

## 分类总览

| 组 | 页面数 | 判据 | 能否可视化编辑 | 处理方式 |
| --- | --- | --- | --- | --- |
| 0 已支持 | 13 | vanilla serializer，编辑器已有适配器 | 能，已实现 | 无 |
| A1 可直接接入 | 40 | 配方对象是 `RecipeHolder`，原料全是物品 | 能 | 纯物品配方适配器 |
| A2 需先扩展模型 | 36 | 配方对象是 `RecipeHolder`，但含流体/化学品原料 | 能，槽位按原料种类打补丁 | `EditorIngredient` 携带种类 + 数量 |
| B JEI 合成页 | 33 | 配方对象不是 `RecipeHolder`，由模组代码生成 | 视页面而定 | 照 `JeiVanillaRecipeEditorAdapter` 模式 |
| C 信息页 | 66 | 不是配方页（战利品、图鉴、统计、多方块预览） | 不适用 | 不接入 |
| 合计 | 188 | | | |

判据取运行时导出的 `recipeClass` 与 `shape` 两列：

- `recipeClass` 含 `RecipeHolder` → 页面显示的是注册表里的配方对象，背后有配方 JSON，属于可编辑的 A 组；
- `shape` 里出现 `fluid_stack` 等非 `item_stack` 原料 → A2 组；
- 两者都不满足 → B/C 组。

## 0 组：已支持的 vanilla 页面（13）

| 页面 uid | 配方数 | JEI 原料槽（入/出） | 状态 |
| --- | --- | --- | --- |
| `jei:information` | 3322 | 1 / 1 | done |
| `minecraft:anvil` | 1511 | 2 / 1 | done |
| `minecraft:blasting` | 131 | 10 / 1 | done |
| `minecraft:brewing` | 280 | 4 / 1 | done |
| `minecraft:campfire_cooking` | 11 | 1 / 1 | done |
| `minecraft:compostable` | 254 | 1 / 0 | done |
| `minecraft:crafting` | 4096 | 1 / 1 | done |
| `minecraft:fuel` | 319 | 1 / 0 | done |
| `minecraft:grindstone` | 3571 | 2 / 0 | done |
| `minecraft:smelting` | 284 | 1 / 1 | done |
| `minecraft:smithing` | 156 | 7 / 0 | done |
| `minecraft:smoking` | 12 | 1 / 1 | done |
| `minecraft:stonecutting` | 738 | 15 / 1 | done |

> 注：`minecraft:smithing` 页面里混入了其他模组的锻造配方（示例配方 serializer 为 `silentgear:smithing/upgrade`），这些非 vanilla serializer 的子项当前由兜底路径处理，需要单独核对。

## A1 组：可直接接入的配方页面（40）

配方对象是 `RecipeHolder`，且槽位原料全是普通物品，是第一批实现目标。

**Create**（`create`）

| 页面 uid | 配方数 | JEI 原料槽（入/出） | serializer | 状态 |
| --- | --- | --- | --- | --- |
| `create:automatic_packing` | 221 | 9 / 1 | `create:basin` | done |
| `create:automatic_shaped` | 3002 | 1 / 1 | `minecraft:crafting_shapeless` | done |
| `create:automatic_shapeless` | 1094 | 17 / 1 | `create:basin` | done |
| `create:block_cutting` | 135 | 15 / 19 | `minecraft:stonecutting` | done |
| `create:crushing` | 264 | 1 / 2 | `create:crushing` | declared |
| `create:deploying` | 176 | 2 / 1 | `create:deploying` | declared |
| `create:fan_blasting` | 273 | 1 / 1 | `minecraft:smelting` | done |
| `create:fan_haunting` | 22 | 1 / 1 | `create:haunting` | declared |
| `create:fan_smoking` | 12 | 1 / 1 | `minecraft:smoking` | done |
| `create:fan_washing` | 42 | 1 / 1 | `create:splashing` | declared |
| `create:item_application` | 28 | 14 / 1 | `create:item_application` | declared |
| `create:mechanical_crafting` | 4 | 77 / 1 | `create:mechanical_crafting` | declared |
| `create:milling` | 125 | 1 / 2 | `create:milling` | declared |
| `create:mystery_conversion` | 2 | 1 / 1 | `create:conversion` | gap |
| `create:pressing` | 16 | 2 / 1 | `create:pressing` | declared |
| `create:sandpaper_polishing` | 1 | 1 / 1 | `create:sandpaper_polishing` | declared |
| `create:sawing` | 185 | 1 / 1 | `create:cutting` | declared |
| `create:sequenced_assembly` | 3 | 7 / 1 | `create:sequenced_assembly` | declared |

**Immersive Engineering**（`immersiveengineering`）

| 页面 uid | 配方数 | JEI 原料槽（入/出） | serializer | 状态 |
| --- | --- | --- | --- | --- |
| `immersiveengineering:alloy` | 5 | 26 / 1 | `immersiveengineering:alloy` | declared |
| `immersiveengineering:arc_furnace` | 54 | 2 / 1 | `immersiveengineering:arc_furnace` | declared |
| `immersiveengineering:arc_recycling` | 188 | 1 / 1 | `immersiveengineering:arc_furnace` | gap |
| `immersiveengineering:blast_furnace` | 2 | 1 / 2 | `immersiveengineering:blast_furnace` | declared |
| `immersiveengineering:blast_furnace_fuel` | 4 | 1 / 0 | `immersiveengineering:blast_furnace_fuel` | declared |
| `immersiveengineering:blueprint` | 61 | 7 / 1 | `immersiveengineering:blueprint` | declared |
| `immersiveengineering:crusher` | 82 | 2 / 1 | `immersiveengineering:crusher` | declared |
| `immersiveengineering:fertilizer` | 5 | 1 / 0 | `immersiveengineering:fertilizer` | declared |
| `immersiveengineering:metal_press` | 24 | 1 / 1 | `immersiveengineering:metal_press` | declared |
| `immersiveengineering:sawmill` | 72 | 1 / 4 | `immersiveengineering:sawmill` | declared |

**Just Enough Archaeology**（`jearchaeology`）

| 页面 uid | 配方数 | JEI 原料槽（入/出） | serializer | 状态 |
| --- | --- | --- | --- | --- |
| `jearchaeology:brush` | 7 | 2 / 10 | `jearchaeology:brush` | gap |
| `jearchaeology:sniff` | 1 | 0 / 2 | `jearchaeology:sniff` | gap |

**Mekanism**（`mekanism`）

| 页面 uid | 配方数 | JEI 原料槽（入/出） | serializer | 状态 |
| --- | --- | --- | --- | --- |
| `mekanism:combining` | 82 | 2 / 1 | `mekanism:combining` | declared |
| `mekanism:crushing` | 230 | 1 / 1 | `mekanism:crushing` | declared |
| `mekanism:energy_conversion` | 2 | 1 / 0 | `mekanism:energy_conversion` | declared |
| `mekanism:enriching` | 195 | 1 / 1 | `mekanism:enriching` | declared |
| `mekanism:sawing` | 124 | 1 / 1 | `mekanism:sawing` | declared |
| `mekanism:smelting` | 284 | 1 / 1 | `mekanism:smelting` | declared |

**Minecraft / JEI 本体**（`minecraft`）

| 页面 uid | 配方数 | JEI 原料槽（入/出） | serializer | 状态 |
| --- | --- | --- | --- | --- |
| `minecraft:overworld` | 53 | 3 / 112 | `—` | gap |
| `minecraft:the_end` | 5 | 1 / 24 | `—` | gap |
| `minecraft:the_nether` | 5 | 9 / 38 | `—` | gap |

**NNP Easy Farming**（`nnp_easy_farming`）

| 页面 uid | 配方数 | JEI 原料槽（入/出） | serializer | 状态 |
| --- | --- | --- | --- | --- |
| `nnp_easy_farming:farming_stem` | 3 | 8 / 47 | `—` | gap |

A1 表里状态 `done` 但 serializer 列写着 vanilla 名称的 4 页，显示的**就是**配方管理器里那条 vanilla 配方（id 未被改写、serializer 也没变），写回走它自己的 vanilla 适配器，因此既不需要声明、也不能加声明（按 serializer 的声明会把页面从 vanilla 适配器手里拿走）。它们的 `done` 由 `scripts/jei-client-tests.ps1` 断言（每页至少一个模型），写回形状由 `scripts/jei-declared-smoke.ps1` 里对应 serializer 的用例钉住：

| 页面 uid | 配方数 | 显示的配方 serializer | 说明 |
| --- | --- | --- | --- |
| `create:automatic_shaped` | 3002 | `minecraft:crafting_shapeless` | 自动合成 |
| `create:block_cutting` | 135 | `minecraft:stonecutting` | 方块切割 |
| `create:fan_blasting` | 273 | `minecraft:smelting` | 批量熔炼 |
| `create:fan_smoking` | 12 | `minecraft:smoking` | 批量烟熏 |

另有两页的 serializer 列写着模组自己的名字，但**那条 id 指向的真正配方**是 vanilla 的：Create 把这两页的条目包成自己的 `BasinRecipeHolder` 展示，而 id 落到配方管理器里仍是 `minecraft:crafting_shaped` / `crafting_shapeless`，编辑器按 identity/`byKey` 找到真配方再按 vanilla 分支写回（与 `mekanism:smelting` 的别名同一情形，只是 id 没被改写，静态规则看不出来，所以在这里具名）：

| 页面 uid | 配方数 | 说明 | 客户端建模 |
| --- | --- | --- | --- |
| `create:automatic_packing` | 221 | 自动压缩 | 见 §门禁页面的建模覆盖 |
| `create:automatic_shapeless` | 1094 | 自动搅拌 | 见 §门禁页面的建模覆盖 |

## A2 组：含流体/化学品原料的配方页面（36）

这些页面的槽位里含流体/化学品等非物品原料。`EditorIngredient` 现在携带**原料种类**（`IngredientKind`：item / fluid / chemical）、资源 id 和**数量**，数量范围按种类区分（物品 1..64；流体/化学品是 mB 体积，1..1000000），补丁字段名也随之分种类：物品沿用 `.item`/`.count`，流体是 `.fluid`/`.fluid_amount`，化学品是 `.chemical`/`.chemical_amount`。声明用 `withFluidFields`/`withChemicalFields`标出每个字段的种类，客户端只对得上种类的槽位给出拖放目标，服务端拒绝种类不符的补丁，写回时按该模组自己的 stack JSON 形状生成（IE 的流体输入是`{"fluid":id,"amount":n}`，流体结果是 `{"amount":n,"id":id}`；Mekanism 的化学品输入是 `{"amount":n,"chemical":id}`，输出是 `{"amount":n,"id":id}`）。

本组 36 页里 31 页已声明、5 页标 `gap`（共用已声明 serializer 但配方在 reload 时生成、没有配方 JSON 可打补丁），`todo` 0 页；每页的具体成因见各声明文件里紧邻该页的注释。

这一轮为「声明语言本身挡住的页面」补了四样最小能力，机制都只在声明层与写回层，不针对某个模组特判（只有具体推导写在对应模组的声明文件里）：

- **按页面作用域的声明**（`withPageUid`）：一个 serializer 对应两个 JEI 页面、且两页槽位角色相反时（`mekanism:rotary`：`condensentrating` 是化学品入 + 流体出，`decondensentrating` 反过来，两页读同一份配方 JSON），按 serializer 的声明只能描述其中一页。页面作用域声明用它自己的 uid 作键，客户端把该 uid 当作补丁的 `serializer` 发出去，服务端按同一个键取回声明；serializer 本身因此不再被声明，只报 serializer 的补丁会被`stale or unsupported patch` 拒掉，不会落进任意一个方向；
- **裸数量的字段 + 固定 id**（`ModdedJsonStyle.AMOUNT` + `withFixedFieldIds`）：IE 焦炉的副产物写成 `"creosote": 250`，字段值就是数量、流体由字段名隐含。这种字段写回时不产生对象，并且声明必须固定它的 id，否则拖入另一种流体也会只写数量、被读回成 creosote；客户端建模时只接受该 id 的候选，写回时拒绝别的 id；
- **混合种类的有序段列表**：段列表原先要求所有段同一种类，而 `mekanism:reaction` 的可选输出是「有则加一个物品产出、有则再加一个化学品产出」，两段种类不同。现在客户端对段列表的输出槽接受任一声明过的种类（由页面上真正的原料决定），服务端在写回时按配方 JSON 解析出的那一段字段核对种类，因此 `output.0` 是物品而 `output.1` 是化学品也能各自写对；
- **配方推导的槽位序列**（`DerivedSlotSequence`，实现见 `CreateBasinSlotSequence`）：`create:mixing` / `create:packing` 的槽位不是字段列表能命名的——`BasinCategory.setRecipe` 先用 `ItemHelper.condenseIngredients` 把 `Ingredient.getItems()` 相同的物品原料合并成一个槽（`compacting/ice.json` 的 9 条 `snow_block` 只画一个槽），再把全部物品槽排在全部流体槽之前，而配方 JSON 里既没有这个合并也没有这个重排。声明只写下「这个槽位序列由这个推导产生」：客户端没有配方 JSON，因此只校验该推导允许的槽数下界与它声明的种类；服务端按同一份 JSON 与模组加载好的配方推出整条序列，再按补丁里的槽位序号取回该槽的全部 JSON 路径——合并槽的多个入口一次全写同一个值，所以重建页面画出同样的合并布局。推导对不上配方（物品入口数与模组自己加载的 `Ingredient` 列表不一致、或逐项 `getItems()` 不同）时整份补丁被拒，不写猜测结果；
- **嵌套原料节点只改写 base**（`withPreservedIngredientBase`）：`mekanism:painting` 的 `item_input` 在 176 份文件里有 160 份是 `{"type":"neoforge:difference","base":…,"count":1,"subtracted":…}`，页面上显示的正是 base 减 subtracted。按普通物品字段写回会把整个节点换成 `{"item":…,"count":…}`、丢掉 `subtracted`；声明把这个字段标成「保留嵌套结构」后，写回只替换 `base`，`type`/`count`/`subtracted` 原样保留，重建页面仍是同一条 difference。


**Create**（`create`）

| 页面 uid | 配方数 | JEI 原料槽（入/出） | serializer | 状态 |
| --- | --- | --- | --- | --- |
| `create:automatic_brewing` | 286 | 2 / 1 | `create:mixing` | gap |
| `create:draining` | 80 | 1 / 2 | `create:emptying` | gap |
| `create:mixing` | 13 | 4 / 1 | `create:mixing` | declared |
| `create:packing` | 7 | 2 / 1 | `create:compacting` | declared |
| `create:spout_filling` | 50 | 16 / 1 | `create:filling` | declared |

**Immersive Engineering**（`immersiveengineering`）

| 页面 uid | 配方数 | JEI 原料槽（入/出） | serializer | 状态 |
| --- | --- | --- | --- | --- |
| `immersiveengineering:bottling_buckets` | 37 | 2 / 1 | `immersiveengineering:bottling_machine` | gap |
| `immersiveengineering:bottling_machine` | 28 | 3 / 1 | `immersiveengineering:bottling_machine` | declared |
| `immersiveengineering:bottling_potions` | 175 | 3 / 1 | `immersiveengineering:bottling_machine` | gap |
| `immersiveengineering:cloche` | 40 | 2 / 1 | `immersiveengineering:cloche` | declared |
| `immersiveengineering:coke_oven` | 3 | 41 / 2 | `immersiveengineering:coke_oven` | declared |
| `immersiveengineering:fermenter` | 8 | 1 / 1 | `immersiveengineering:fermenter` | declared |
| `immersiveengineering:mixer` | 3 | 7 / 1 | `immersiveengineering:mixer` | declared |
| `immersiveengineering:mixer_potions` | 255 | 3 / 1 | `immersiveengineering:mixer` | gap |
| `immersiveengineering:refinery` | 4 | 2 / 1 | `immersiveengineering:refinery` | declared |
| `immersiveengineering:squeezer` | 6 | 1 / 1 | `immersiveengineering:squeezer` | declared |

**Mekanism**（`mekanism`）

| 页面 uid | 配方数 | JEI 原料槽（入/出） | serializer | 状态 |
| --- | --- | --- | --- | --- |
| `mekanism:activating` | 1 | 1 / 1 | `mekanism:activating` | declared |
| `mekanism:centrifuging` | 2 | 1 / 1 | `mekanism:centrifuging` | declared |
| `mekanism:chemical_conversion` | 24 | 4 / 1 | `mekanism:chemical_conversion` | declared |
| `mekanism:chemical_infusing` | 4 | 2 / 1 | `mekanism:chemical_infusing` | declared |
| `mekanism:compressing` | 2 | 2 / 1 | `mekanism:compressing` | declared |
| `mekanism:condensentrating` | 17 | 1 / 1 | `mekanism:rotary` | declared |
| `mekanism:crystallizing` | 10 | 1 / 1 | `mekanism:crystallizing` | declared |
| `mekanism:decondensentrating` | 17 | 2 / 1 | `mekanism:rotary` | declared |
| `mekanism:dissolution` | 23 | 3 / 1 | `mekanism:dissolution` | declared |
| `mekanism:evaporating` | 2 | 2 / 1 | `mekanism:evaporating` | declared |
| `mekanism:injecting` | 91 | 2 / 1 | `mekanism:injecting` | declared |
| `mekanism:metallurgic_infusing` | 30 | 2 / 1 | `mekanism:metallurgic_infusing` | declared |
| `mekanism:nucleosynthesizing` | 21 | 2 / 1 | `mekanism:nucleosynthesizing` | declared |
| `mekanism:oxidizing` | 25 | 1 / 1 | `mekanism:oxidizing` | declared |
| `mekanism:painting` | 176 | 17 / 1 | `mekanism:painting` | declared |
| `mekanism:pigment_extracting` | 178 | 1 / 1 | `mekanism:pigment_extracting` | declared |
| `mekanism:pigment_mixing` | 16 | 2 / 1 | `mekanism:pigment_mixing` | declared |
| `mekanism:purifying` | 28 | 2 / 1 | `mekanism:purifying` | declared |
| `mekanism:reaction` | 14 | 4 / 1 | `mekanism:reaction` | declared |
| `mekanism:separating` | 2 | 2 / 2 | `mekanism:separating` | declared |
| `mekanism:washing` | 7 | 3 / 1 | `mekanism:washing` | declared |

## B 组：JEI 合成页（33）

页面对象由模组代码生成，不来自配方 JSON。可以编辑的只有少数（例如 `horsepowered:*`、`cobblemon:*` 是模组自己的数据驱动配方），其余基本是展示型页面。

**Better Piglin Trades**（`betterpiglintrades`）

| 页面 uid | 配方数 | JEI 原料槽（入/出） | 说明 | 状态 |
| --- | --- | --- | --- | --- |
| `betterpiglintrades:piglin_barter` | 3 | 1 / 20 | Piglin Bartering | gap |

**Cobblemon**（`cobblemon`）

| 页面 uid | 配方数 | JEI 原料槽（入/出） | 说明 | 状态 |
| --- | --- | --- | --- | --- |
| `cobblemon:berry_recipe` | 77 | 2 / 1 | Berry Mutation | gap |
| `cobblemon:campfire_pot_recipe` | 118 | 4 / 1 | 营火锅 | declared |

**Resource Geodes & Catalysts**（`createresourcegeodes`）

| 页面 uid | 配方数 | JEI 原料槽（入/出） | 说明 | 状态 |
| --- | --- | --- | --- | --- |
| `createresourcegeodes:catalyst_generation` | 17 | 2 / 1 | Catalyst Generation | gap |

**Horse Powered**（`horsepowered`）

| 页面 uid | 配方数 | JEI 原料槽（入/出） | 说明 | 状态 |
| --- | --- | --- | --- | --- |
| `horsepowered:bottling` | 1 | 1 / 1 | Bottling | gap |
| `horsepowered:chopping` | 25 | 17 / 1 | Horse Chopping | declared |
| `horsepowered:crushing` | 10 | 1 / 1 | Crushing | declared |
| `horsepowered:drying` | 6 | 1 / 1 | Drying | declared |
| `horsepowered:grinding` | 28 | 1 / 1 | Horse Grinding | declared |
| `horsepowered:manual_chopping` | 25 | 17 / 1 | Manual Chopping | declared |
| `horsepowered:manual_grinding` | 28 | 1 / 1 | Manual Grinding | declared |
| `horsepowered:pressing` | 37 | 1 / 1 | Horse Pressing | declared |
| `horsepowered:trapping` | 9 | 1 / 1 | Trapping | gap |

**Just Enough Beacons Reforged**（`just_enough_beacons`）

| 页面 uid | 配方数 | JEI 原料槽（入/出） | 说明 | 状态 |
| --- | --- | --- | --- | --- |
| `just_enough_beacons:beacon_base_block` | 1 | 0 / 0 | Beacon Base Blocks | gap |
| `just_enough_beacons:beacon_payment` | 1 | 0 / 0 | Beacon Payments | gap |
| `just_enough_beacons:conduit_frame_block` | 1 | 0 / 0 | Conduit Frame Blocks | gap |

**Mekanism**（`mekanism`）

| 页面 uid | 配方数 | JEI 原料槽（入/出） | 说明 | 状态 |
| --- | --- | --- | --- | --- |
| `mekanism:boiler_casing` | 2 | 2 / 1 | 热力锅炉 | gap |
| `mekanism:nutritional_liquifier` | 89 | 1 / 1 | 营养液化器 | gap |
| `mekanism:sps_casing` | 1 | 1 / 1 | 超临界移相器 | gap |

**Minecraft / JEI 本体**（`minecraft`）

| 页面 uid | 配方数 | JEI 原料槽（入/出） | 说明 | 状态 |
| --- | --- | --- | --- | --- |
| `minecraft:tag_recipes/block` | 575 | 1 / 1 | 方块标签 | gap |
| `minecraft:tag_recipes/fluid` | 45 | 2 / 2 | 流体标签 | gap |
| `minecraft:tag_recipes/item` | 1052 | 1 / 1 | 物品标签 | gap |

**NNP Easy Farming**（`nnp_easy_farming`）

| 页面 uid | 配方数 | JEI 原料槽（入/出） | 说明 | 状态 |
| --- | --- | --- | --- | --- |
| `nnp_easy_farming:grindstone_recipe` | 6 | 1 / 1 | Grinding Recipes | declared |

**Rack It Up**（`rackitup`）

| 页面 uid | 配方数 | JEI 原料槽（入/出） | 说明 | 状态 |
| --- | --- | --- | --- | --- |
| `rackitup:drying` | 10 | 1 / 1 | 晾干 | declared |

**Silent Gear**（`silentgear`）

| 页面 uid | 配方数 | JEI 原料槽（入/出） | 说明 | 状态 |
| --- | --- | --- | --- | --- |
| `silentgear:alloy_making/fabric` | 3 | 52 / 1 | Fabric Alloys | gap |
| `silentgear:alloy_making/gem` | 4 | 2 / 1 | Gem Alloys | declared |
| `silentgear:alloy_making/metal` | 8 | 4 / 1 | Metal Alloys | declared |
| `silentgear:alloy_making/super` | 3 | 276 / 1 | Super Alloys | gap |
| `silentgear:gear_crafting` | 113 | 272 / 1 | Gear Crafting | declared |
| `silentgear:grading` | 1 | 173 / 0 | Grading | gap |
| `silentgear:salvaging` | 88 | 1 / 0 | Salvaging | declared |
| `silentgear:tool_action` | 9 | 4 / 1 | Tool Action | declared |

**Smithing Template Viewer**（`smithingtemplateviewer`）

| 页面 uid | 配方数 | JEI 原料槽（入/出） | 说明 | 状态 |
| --- | --- | --- | --- | --- |
| `smithingtemplateviewer:smithing_trim` | 19 | 52 / 0 | Smithing Trim Viewer | gap |

## C 组：信息页（66，不适用可视化编辑）

战利品表、图鉴、世界生成、附魔/效果说明、多方块预览等，本身不是配方，没有可落盘的编辑操作。

**Advanced Loot Info**（`ali`）

| 页面 uid | 配方数 | JEI 原料槽（入/出） | 说明 |
| --- | --- | --- | --- |
| `ali:archaeology_loot` | 6 | 0 / 8 | 考古战利品 |
| `ali:block_loot` | 1380 | 1 / 2 | 方块掉落物 |
| `ali:cat_morning_gift` | 1 | 0 / 7 | 猫的晨间礼物 |
| `ali:chest_loot` | 46 | 0 / 29 | 箱子战利品 |
| `ali:entity_loot` | 81 | 0 / 1 | 实体掉落物 |
| `ali:fishing_loot` | 4 | 0 / 24 | 钓鱼战利品 |
| `ali:gameplay_loot` | 290 | 0 / 19 | 游戏机制战利品 |
| `ali:hero_loot` | 19 | 0 / 4 | 村庄英雄战利品 |
| `ali:panda_sneeze` | 1 | 0 / 1 | 熊猫打喷嚏 |
| `ali:piglin_bartering` | 4 | 0 / 18 | 猪灵交易 |
| `ali:plant_loot` | 11 | 1 / 3 | 植物掉落物 |
| `ali:shearing` | 1 | 0 / 2 | 修剪 |
| `ali:sniffer_digging` | 1 | 0 / 2 | 嗅探兽挖掘 |
| `ali:trade_loot` | 21 | 19 / 18 | 交易获得物 |
| `ali:trial_chambers` | 22 | 0 / 10 | 试炼密室 |

**Cobblemon Info (CobbleDex)**（`cobbledex-rei-emi-jei`）

| 页面 uid | 配方数 | JEI 原料槽（入/出） | 说明 |
| --- | --- | --- | --- |
| `cobbledex-rei-emi-jei:01_overview` | 1122 | 1 / 1 | Pokemon Overview |
| `cobbledex-rei-emi-jei:02_spawns` | 5175 | 1 / 1 | 生成位置 |
| `cobbledex-rei-emi-jei:03_evolution` | 1184 | 1 / 1 | Cobblemon 进化 |
| `cobbledex-rei-emi-jei:04_obtainment` | 0 | 0 / 0 | 特殊获取 |
| `cobbledex-rei-emi-jei:05_drops` | 1292 | 1 / 1 | 掉落物品 |
| `cobbledex-rei-emi-jei:06_stats` | 1082 | 1 / 1 | 基础能力值 |
| `cobbledex-rei-emi-jei:07_moves` | 8675 | 1 / 1 | 招式 |
| `cobbledex-rei-emi-jei:08_tm_recipes` | 335 | 4 / 1 | TM Recipes |
| `cobbledex-rei-emi-jei:09_pokedex_info` | 1082 | 1 / 1 | 宝可梦图鉴信息 |
| `cobbledex-rei-emi-jei:10_pokemon_description` | 1081 | 1 / 1 | 宝可梦图鉴说明 |
| `cobbledex-rei-emi-jei:11_fossils` | 15 | 2 / 1 | 化石复活 |
| `cobbledex-rei-emi-jei:12_type_chart` | 1082 | 1 / 1 | 属性相克 |
| `cobbledex-rei-emi-jei:13_natures` | 1 | 0 / 0 | 性格 |
| `cobbledex-rei-emi-jei:15_jobs` | 0 | 0 / 0 | CobbleCrew 工作 |
| `cobbledex-rei-emi-jei:16_forms` | 194 | 2 / 2 | Alternate Forms |
| `cobbledex-rei-emi-jei:17_riding` | 189 | 1 / 1 | Riding Data |
| `cobbledex-rei-emi-jei:18_herds` | 717 | 3 / 1 | Herds |

**Just Enough Advancements**（`jea`）

| 页面 uid | 配方数 | JEI 原料槽（入/出） | 说明 |
| --- | --- | --- | --- |
| `jea:advancement` | 556 | 1 / 1 | 进度 |

**JEED**（`jeed`）

| 页面 uid | 配方数 | JEI 原料槽（入/出） | 说明 |
| --- | --- | --- | --- |
| `jeed:effect_info` | 51 | 5 / 1 | 效果 |

**JEFA**（`jefa`）

| 页面 uid | 配方数 | JEI 原料槽（入/出） | 说明 |
| --- | --- | --- | --- |
| `jefa:cat_gift` | 1 | 0 / 7 | Cat Morning Gift |
| `jefa:fishing_loot` | 1 | 0 / 24 | Fishing Loot (No Luck of |
| `jefa:hero_loot` | 14 | 8 / 4 | Hero of the Village Gift |
| `jefa:mob_interaction` | 26 | 1 / 1 | Mob Interaction |
| `jefa:piglin_trade` | 1 | 1 / 18 | Piglin Bartering |
| `jefa:trial_spawner_loot` | 2 | 0 / 6 | Trial Spawner Looting |
| `jefa:vault_loot` | 2 | 1 / 24 | Vault Looting |

**Just Enough Mekanism Multiblocks**（`jei_mekanism_multiblocks`）

| 页面 uid | 配方数 | JEI 原料槽（入/出） | 说明 |
| --- | --- | --- | --- |
| `jei_mekanism_multiblocks:multiblock.mekanism.boiler` | 1 | 5 / 0 | 建造 [热力锅炉] |
| `jei_mekanism_multiblocks:multiblock.mekanism.dynamic_tank` | 1 | 3 / 0 | 建造 [动态储罐] |
| `jei_mekanism_multiblocks:multiblock.mekanism.evaporation_plant` | 1 | 5 / 0 | 建造 [热力蒸馏塔] |
| `jei_mekanism_multiblocks:multiblock.mekanism.lasers` | 1 | 1 / 0 | 建造 [Lasers] |
| `jei_mekanism_multiblocks:multiblock.mekanism.matrix` | 1 | 3 / 0 | 建造 [能量感应矩阵] |
| `jei_mekanism_multiblocks:multiblock.mekanism.sps` | 1 | 5 / 0 | 建造 [超临界移相器] |

**Just Enough Immersive Multiblocks**（`jeimultiblocks`）

| 页面 uid | 配方数 | JEI 原料槽（入/出） | 说明 |
| --- | --- | --- | --- |
| `jeimultiblocks:multiblock` | 24 | 5 / 1 | Multiblock with Hammer |

**JEI WorldGen**（`jeiworldgen`）

| 页面 uid | 配方数 | JEI 原料槽（入/出） | 说明 |
| --- | --- | --- | --- |
| `jeiworldgen:world_generation` | 63 | 2 / 3 | World Generation |

**Just Enough Resources**（`jeresources`）

| 页面 uid | 配方数 | JEI 原料槽（入/出） | 说明 |
| --- | --- | --- | --- |
| `jeresources:dungeon` | 41 | 0 / 24 | 地牢资源 |
| `jeresources:enchantment` | 224 | 1 / 0 | 可用附魔属性 |
| `jeresources:mob` | 99 | 0 / 1 | 生物资源 |
| `jeresources:plant` | 8 | 1 / 2 | 植物掉落信息 |
| `jeresources:villager` | 21 | 26 / 18 | 村民交易 |
| `jeresources:worldgen` | 13 | 0 / 3 | 矿物生成 |

**JERM**（`jerm`）

| 页面 uid | 配方数 | JEI 原料槽（入/出） | 说明 |
| --- | --- | --- | --- |
| `jerm:jerm_repair` | 336 | 2 / 1 | 修复和命名 |

**Just Enough Breeding**（`justenoughbreeding`）

| 页面 uid | 配方数 | JEI 原料槽（入/出） | 说明 |
| --- | --- | --- | --- |
| `justenoughbreeding:allay_duplication` | 1 | 15 / 1 | Allay Duplication |
| `justenoughbreeding:breeding` | 26 | 2 / 1 | 繁殖 |
| `justenoughbreeding:taming` | 3 | 3 / 1 | Taming |
| `justenoughbreeding:temper` | 5 | 7 / 1 | Temper |
| `justenoughbreeding:transformation` | 87 | 2 / 1 | Transformation |
| `justenoughbreeding:trusting` | 2 | 73 / 1 | 信任 |

**Just Enough Professions**（`justenoughprofessions`）

| 页面 uid | 配方数 | JEI 原料槽（入/出） | 说明 |
| --- | --- | --- | --- |
| `justenoughprofessions:professions` | 20 | 0 / 1 | 职业 |

**SilentGearJEI**（`silentgearjei`）

| 页面 uid | 配方数 | JEI 原料槽（入/出） | 说明 |
| --- | --- | --- | --- |
| `silentgearjei:silentgear_material` | 74 | 1 / 0 | 寂静装备-材料 |
| `silentgearjei:silentgear_trait` | 75 | 2 / 0 | 寂静装备-词条 |

**Universal Enchantment Info**（`uei`）

| 页面 uid | 配方数 | JEI 原料槽（入/出） | 说明 |
| --- | --- | --- | --- |
| `uei:ench_info` | 44 | 14 / 1 | 附魔信息 |

## 客户端实测

`scripts/jei-client-tests.ps1` 照 JEI 本体的做法搭了一套客户端自动测试：把 `clientTest` source set 编成第二个测试 mod（`jeieditortests`），挂在一个独立的 `clientTest` run 里（ModDevGradle 的 `sourceSet` + `loadedMods`），客户端进世界后遍历 JEI 注册的全部页面，用编辑器自己的建模路径建模型并断言，结果写到 `build/client-test/report.json`。用例覆盖：

- **A** 清单里每个已接入页面至少要有一个配方能建模；
- **B** 声明页面的槽键必须正好是 `input.0..input.N-1` + `output`，N 等于声明的输入字段数；每个槽的原料种类必须是声明为该字段（有序段列表则为其任一段）的种类，页面上该槽真正显示的原料种类也必须在声明里（否则那槽画得出来却永远拖不进去），声明为流体/化学品的槽位不能为空；
- **C** 补丁往返：每个输入槽与产出槽都要么产出带该槽位自身键的 patch 字段，要么按契约拒绝（声明页里模型没解析出种类的槽位、JEI 合成页的空产出槽都必须抛 `IllegalArgumentException`；合成网格的空格子必须能填进去）；非物品槽位按自己的种类断言——物品拖放必须被拒，同种类拖放（经 `RecipeAdapterSupport.editorIngredient` 从 JEI 注册的原料解析，和拖放路径同一条）必须产出该槽位的 `.fluid`/`.fluid_amount` 或 `.chemical`/`.chemical_amount`；物品槽位反向断言拒绝流体拖放；
- **D** 报告编辑器能建模但清单没有声明的页面（防止误匹配）。

每页最多记录 3 个建模样本的完整槽位内容（`modelled_samples` 里 `{key=role:kind:id:amount;…}` 那段），服务端探针用例用它复现同一模型的 `base_fingerprint`（指纹哈希的就是`kind:id:amount`，所以流体槽位不会和同 id 同数量的物品槽位撞哈希）：`scripts/jei-declared-smoke.ps1` 只把声明页面的指纹当松弛值，未声明页面必须给出真实指纹，否则补丁会被当作过期改动拒绝。

91 个已接入页面（清单里状态为 `done`/`declared` 的行）都要在客户端测试里至少建出一个模型；接入过程中实测发现的三类问题：

| 页面 | 现象 | 性质 |
| --- | --- | --- |
| `immersiveengineering:arc_recycling` | 0 个模型 | 与 `arc_furnace` 共用 serializer，但它的配方在 reload 时由 `arc_recycling_list.json` 生成（没有自己的配方 JSON 可打补丁），声明把它列为只读页 → 清单不能算它已接入 |
| `minecraft:tag_recipes/item`、`/block`、`/fluid` | 0 个模型 | JEI 直接推 `TagInfoRecipe`，既不是 `RecipeHolder` 也不是 `Recipe`，编辑器所有路径都不认 → 不是已支持页面。这不是缺口：标签页显示的是标签的成员列表（入槽与出槽是同一批成员），那是 `tags/` 注册表里的标签数据而不是配方记录，所以没有可写的配方编辑——「编辑」它等于改写标签，会连带改变所有用到该标签的配方 |
| `jei:information`、`minecraft:compostable`、锻造纹饰配方 | 产出槽为空 | 不是缺陷：编辑器的契约就是产出槽为空时不可编辑，客户端测试按同一契约断言 |

另外实测到 7 个**没有声明却能建模**的页面，其中 6 个已经按事实记入清单（见 A1 组的两张说明表）：`create:automatic_packing`、`automatic_shaped`、`automatic_shapeless`、`block_cutting`、`fan_blasting`、`fan_smoking`。它们能建模是因为页面上显示的配方最终就是编辑器真正实现的 serializer 的配方：`block_cutting` 是 `minecraft:stonecutting`，`fan_blasting`/`fan_smoking` 是 `minecraft:smelting`/`smoking`，而三个 `create:automatic_*` 页面的补丁携带的是配方管理器里那份底层配方的 crafting serializer（dump 里的 `create:basin` 只是为 JEI 展示转换出来的 holder 的 serializer），写回走 vanilla 分支，所以既不需要声明、也不能加声明（按 serializer 的声明会把页面从 vanilla 适配器手里拿走）。最后一个 `jerm:jerm_repair` 仍是「能建模但不记入」：它是 JEI 自己的铁砧页，走 `jei:*` 合成页路径，编辑由 UI 与服务端的只读策略拒绝，因此留在客户端的「报告但不算失败」一侧。

vanilla 适配器只认自己实现的 serializer（`RecipeEditorAdapters.isImplementedVanillaSerializer`），不按配方类匹配，所以这份名单比收紧前短：`silentgear:gear_crafting` 的 `silentgear:compound_part` 配方类继承 `ShapelessRecipe`，曾被当作合成网格 offer 出来，而服务端对它的补丁一律回答 `stale or unsupported patch`（补丁从未落盘），收紧后该页回到 0 模型；本轮改为给该页的两个 serializer（`silentgear:gear_crafting`、`silentgear:compound_part`）出**声明**，补丁改走声明路径，页面因此重新可编辑。`create:automatic_shaped`（39 → 32 个模型）与 `create:automatic_shapeless`（40 → 24）只失去 serializer 不是 vanilla crafting 的子项，例如 `refinedstorage:recoloring`——它自己的 codec 要 `ingredient`/`dye`，而重建出来的是 `ingredients`/`result`，写出的 JSON 读不回来。`create:automatic_packing` 不受影响：它的样本全部携带 `minecraft:crafting_shaped`/`crafting_shapeless`。

门禁除了「已接入必须至少建出一个模型」，还反过来断言两个集合必须建出**零**个模型：标 `gap` 的行，以及脚本里锁定的已知不可用页面（三个 `minecraft:tag_recipes/*`）。这样缺口是被断言的事实而不是被忽略的行：哪天有人修好了，门禁会红，提示回来改本清单。

### 实测查出的编译器缺陷

`KNOWN UNSUPPORTED` 这条新断言上线后第一次运行就红了，而且不是文档问题：JEI 的标签页（`TagInfoRecipe`）不是 `RecipeHolder`，`JeiRecipeIntrospection.recipeId` 会回落到类别的注册名，也就是**物品标签 id**；`resolveRecipeHolder` 拿这个 id 去 `recipeManager.byKey(...)` 查，一旦标签 id 和某个配方 id 撞名（本包里 `silentgear:blueprint_paper`、`cobblemon:black_tumblestone_bricks` 都撞过），就会返回一个**毫不相干的配方**，于是标签页变成可编辑，而补丁会落到那个配方上。

修法：注册表查表只对 `RecipeHolder` 生效（它的 `id()` 才是配方自己的 id），其它对象一律走按实例的身份扫描。修完连跑两次，188 个页面的模型数逐页完全一致，标签页稳定为 0，8 个非 `RecipeHolder` 的已接入页面模型数不变。

### 运行时合成页面的真实来源（服务端实测）

「模组在运行时合成、没有配方 JSON 可打补丁」是本清单里 `todo`/`gap` 最常见的成因，所以它被单独核对过：一次性服务端探针在 68 jar 的专用服务器里逐条问了两件事——配方在不在 `RecipeManager` 里（`byKey(id)` 与 `getOrderedRecipes()`），以及 `server.getResourceManager().getResource(<ns>:recipe/<path>.json)` 能不能解析出配方 JSON。先问「资源能否解析」是因为 Create 会注册一个运行时 `DynamicPack`：若它把合成配方的 JSON 也放了进去，那这些页面本来就能用生成的数据包文件覆盖，不需要新机制。该探针的模板与驱动脚本入库在 `scripts/jei-category-dump/`（`RecipeProvenanceProbe.java` + `probe-provenance.ps1`，照 `JeiCategoryDiagnostic` 的做法临时拷进 target、跑完删掉），所以下面的数字可以重测。实测结果：

| serializer / 族 | 管理器里的条数 | 其中 JSON 可达 | 真正来源 |
| --- | --- | --- | --- |
| `create:emptying`（`create:draining`，页面 80 条） | 2 | 2 | 其余 78 条只存在于 JEI 客户端列表：`ItemDrainCategory#consumeRecipes` 按物品的流体 capability 现算配方（id 形如 `create:empty_<item>_of_<fluid>`），管理器里没有、也没有资源文件；Create 的 `DynamicPack`（`Pack$Position.BOTTOM`）只放 cutting/washing 配方与标签 |
| `create:filling`（`create:spout_filling`，页面 50 条） | 12 | 12 | 同上，`SpoutCategory#consumeRecipes` 现算 `fill_<item>_with_<fluid>`；12 条数据包配方已可用。本轮已按 `SpoutCategory.setRecipe` 的槽序（item → fluid → 产出）推导出声明（`CreateSpoutSlotSequence`），这一页现在是 `declared`，覆盖率 12/50 |
| `create:mixing`（`create:automatic_brewing`，页面 286 条） | 13 | 13 | 286 条来自 `PotionMixingRecipes#createRecipes(Level)` 的静态表（读 vanilla `PotionBrewing` 现造 `RecipeHolder`），世界里的机械搅拌器也直接读这份缓存，从不写进管理器 |
| `immersiveengineering:arc_furnace`（`arc_recycling`，页面 188 条） | 54 | 54 | 188 条子配方由 IE 的 `CachedRecipeList#updateCache` 从唯一的生成器配方 `arc_recycling_list.json` 展开，只存在 IE 自己的缓存里；管理器里只有那个 `immersiveengineering:generated_list` 生成器本身 |
| `mekanism:smelting`（页面 284 条） | 0 | — | 页面显示的就是 vanilla `minecraft:smelting` 那 284 条配方，只是被 `RecipeViewerUtils.synthetic(id, "mekanism_generated")` 改了 id；底层配方在管理器里、JSON 也可达 → 按别名接入（见下） |
| `jearchaeology:brush` / `sniff` | 0（无玩家时） | — | 配方由 `Helper#getAllBrushingRecipes` 从战利品表现造，再由 `OnDatapackSyncEvent` 用 `replaceRecipes` 推进管理器；该事件按玩家触发，所以无玩家的专用服务器上一条都没有，jar 里也没有任何 `data/**` 配方文件 |
| `mekanism:nutritional_liquifier`（页面 89 条） | 0 | — | 页面对象是 `BasicItemStackToFluidOptionalItemRecipe`，它的 `getType()`/`getSerializer()` 都是 null，是纯粹的查看器列表（`FakeRVRecipeType`），永远不是数据包配方 |

最关键的一条是全局扫描：**管理器里 8576 条配方，没有一条的配方 JSON 不可达**（同一个探针的 `all:` 查询，`withoutResource=0`）。也就是说「在管理器里、但没有可写配方 JSON」这个集合在本包里是空的——持久化补丁并在载入/重载时替换管理器里那条配方的机制因此没有对象；要支持上表里的页面，缺的不是写回机制，而是那些配方根本不在服务端的管理器里。

`mekanism:smelting` 是这张表里唯一能真正编辑的一族：页面显示的 284 条与 vanilla `minecraft:smelting` 的 284 条一一对应（JEI 的 `minecraft:smelting` 页也是 284 条），例如 `cobblemon:/mekanism_generated/passho_berry_smelt_to_dye` 就是数据包里的 `cobblemon:passho_berry_smelt_to_dye`。编辑器按 `RecipeViewerAliases` 里的别名规则（页面 uid → 该页插入的路径前缀 `/mekanism_generated/`）把显示出来的 id 还原成真实 id，再按那条配方的 serializer（vanilla `minecraft:smelting`）建模并写回生成数据包，所以这一页在表里的 serializer 列是页面自己的 serializer，而补丁携带的是底层那条配方的。

### 已接入页面的真实覆盖率

声明只匹配一种槽位形态，所以「页面建模成功」不等于「整页可编辑」。实测（每页最多采样 40 条）：

- 满覆盖（采样数即模型数）：`mekanism:combining`/`crushing`/`enriching`/`sawing` 40/40、`create:crushing`/`deploying`/`milling`/`sawing`/`fan_washing` 40/40、`immersiveengineering:arc_furnace`/`blueprint`/`crusher`/`sawmill` 40/40、`metal_press` 24/24、`horsepowered:chopping` 25/25、`grinding` 与 `manual_grinding` 28/28 等；
- 部分覆盖：`minecraft:crafting` 37/40、`minecraft:smithing` 21/40（这两个不是声明页，未建模的子项本来就是别的 serializer 的配方）。
- A2 组（流体/化学品槽位）已声明的 24 页里 23 页满覆盖：这一轮接入的 `mekanism:condensentrating`/`decondensentrating` 各 17/17（全部 17 条配方）、`mekanism:reaction` 14/14（全部 14 条）、`immersiveengineering:coke_oven` 3/3（全部 3 条）与采到 40 条上限的 `mekanism:painting` 40/40 都在其中；唯一例外仍是 `immersiveengineering:refinery` 3/4：第 4 条配方（`refinery/acetaldehyde`）只有一个流体输入槽，而声明要求两个，声明描述不了这一形状就不建模。采到 40 条上限的页面（`mekanism:injecting` 40/40、`pigment_extracting` 40/40）实际配方更多，所以是「采样内满覆盖」而非全量。
- 新接入页面里**空槽位**的真实情况也被断言出来了：`create:item_application` 的 28 条模型里有 20 个输入槽读不出原料（整包里该 serializer 只有 8 个配方 JSON 文件，每个都带两个 `ingredient`，28 条页面上另有来源），编辑器的契约是拒绝这种槽位，客户端测试按同一契约断言（拒绝数与检查数一起写进 `patch_roundtrip`）；`create:automatic_shaped` 这类非已接入页面的同类情况只记录、不算失败。

门禁只要求每页至少一个模型，所以部分覆盖目前不会失败；要设覆盖率下限得另加断言。

## TODO 波次

| 波次 | 范围 | 目标 | 状态 |
| --- | --- | --- | --- |
| W0 | 框架 | 声明式模组配方适配器（每模组一份声明 + 按 JEI 槽视图建模 + 原始 JSON 打补丁） | done |
| W1 | A1 组 | A1 表 40 页：已声明 26 个、vanilla 适配器直接接管的 6 个、`gap` 8 个、仍是 todo 0 个；另有 15 个非 RecipeHolder 页（B 组）也已声明。未声明页的成因有三类：serializer 本身就是 vanilla 或与别的模组共用（写回走 vanilla 适配器，不能再加声明，否则会顶掉 vanilla 页）；配方由模组在运行时生成、没有配方 JSON 可打补丁；网格或多输入数组形状。`immersiveengineering:sawmill` 与 `arc_furnace` 的多种输出形状已由「有序段列表」（`withConcatenatedOutputs`：按 JEI 类别的加槽顺序把单段/重复段拼接到配方 JSON 上，客户端只看槽数、服务端按 JSON 解析具体字段）接入；`mekanism:smelting` 显示的就是 vanilla `minecraft:smelting` 的配方本身，只是 id 被模组改写，因此按别名还原成真实配方后接入（见「运行时合成页面的真实来源」） | in_progress |
| W2 | A2 组 | 含流体/化学品的 36 页：声明 31 页、`gap` 5 页，其余 0 页仍未接入——`create:spout_filling` 已按 `SpoutCategory.setRecipe` 的槽序（item → fluid → 产出）由 `CreateSpoutSlotSequence` 接入（覆盖 12/50，其余是运行时生成或条件未满足的文件）；`create:draining` 试过并**回退**：按 `ItemDrainCategory` 的槽序（item → fluid → item）写出的声明让 40 条采样全部建模，但其中 78/80 的条目是现算的（管理器里没有），它们画出的输入槽是带组件补丁的堆叠、物品产出为空，客户端测试的派生形状断言因此报出 57 条失败（`draws 1 input slots but its item band (0) and fluid band (0) hold 0`、`leaves the item/fluid slot output.1 empty`），所以该页按事实记 `gap`。回退的原因**不是**路径寻址：本轮的空路径槽（`DerivedSlot` 的 paths 为空）已经能表达「这个槽没有字段」，但 draining 卡住的是**模型侧解析**——页面上那个槽显示的是带组件补丁的堆叠、产出槽是空的，编辑器的物品原料只有 `id + count`，解析不出代表原料，而派生契约要求声明了非纯物品种类的页面不能留一个解析不出来的槽。它需要的是「一个模型解析不出来的槽」这类表达能力（外加最小的原料模型扩展），而不是一条新声明。（`create:automatic_brewing` 与 `create:mixing` 共用 serializer，声明把它列为只读页，清单因此记 `gap`。）这些数字来自「运行时合成页面的真实来源」一节的专用探针。原先被挡住的 `create:mixing` / `create:packing` 已由**配方推导的槽位序列**接入：它们的槽位顺序来自 `ItemHelper.condenseIngredients` 的合并与「物品槽在前、流体槽在后」的重排，字段列表推不出来，所以声明把这个推导本身写下来（见 A2 组说明里的第四条）；IE 的流体页（`refinery`/`mixer`/`coke_oven`/`fermenter`/`squeezer`）与需要序列的 `cloche`/`bottling_machine`/`fertilizer`/`blast_furnace_fuel` 也已全部接入 | in_progress |
| W3 | B 组 | 普查完成：33 页里 33 页已逐页判定（各自理由写在生成器的集合与注释里：已声明、或按事实记 `gap`；`todo` 已清空），候选调查见 `build/tmp/jei-synthetic-survey.md`。剩下的都不是「再写一条声明」能解决的，见 W4 | in_progress |
| W4 | 新机制 | 三个「需要新机制」的页面都已用**派生契约**接入：`cobblemon:campfire_pot_recipe` 与 `create:mechanical_crafting` 靠 `DerivedSlotSequence.fieldBackedInputOrdinals` 告诉客户端「哪些输入槽位有字段」（campfire_pot 的 seasoning 预览槽在**末尾**、被建模成只读；mechanical_crafting 的`pattern` 按**稠密格子**一格一槽，空格格的空路径夹在字母格**中间**，补丁打到它上面会被整条拒绝）；`create:sequenced_assembly` 靠**逐槽路径**——其槽位顺着 `sequence` 的每一步排布（base `ingredient`、deploying 画该步第 2 个物品、filling 画该步第 1 个流体、pressing/cutting 不画），所以序号到字段是一张路径表而不是模式。仍需要机制的只有 `create:draining`（试接后按 57 条实测失败回退：它卡在模型侧解析——输入槽是带组件补丁的堆叠、物品产出为空，需要「一个模型解析不出来的槽」这类表达能力，空路径槽解决不了它） | pending |
| — | C 组 | 不接入 | n/a |

## 复现

```powershell
# 1) 把诊断模板临时放回模组源码（用完必须删除，不能提交）
copy scripts\jei-category-dump\JeiCategoryDiagnostic.java targets\neoforge-1.21.1\src\main\java\cc\sighs\JEIEditor\client\

# 2) 启动客户端；诊断会自动创建一个临时世界并在 JEI 启动后导出分类
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\jei-category-dump\dump-categories.ps1 -TimeoutSeconds 1800

# 3) 交叉验证（静态扫描，可离线跑）
python scripts\jei-category-dump\scan_categories.py

# 4) 由导出结果重新生成本清单（模组集合变化后重跑）
python scripts\jei-category-dump\gen_checklist.py
```

`run/jei-category-dump.txt` 属于被忽略的 `run/`，不进版本库。
