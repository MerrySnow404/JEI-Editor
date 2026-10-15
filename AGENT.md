# JEI Editor 开发与迁移规范

本仓库使用 `common + targets/<loader>-<minecraft-version>` 结构维护多个 Minecraft 加载器和版本。任何开发者或自动化代理在修改前都必须遵守本文件；更完整的协作、评审和 CI 规则见 [docs/MAINTENANCE_WORKFLOW.md](docs/MAINTENANCE_WORKFLOW.md)。

## 架构边界

```text
common/                         纯 Java 的共享逻辑与共享资源
targets/<loader>-<mc-version>/  一个独立的加载器 + Minecraft 版本工程
gradle/target-conventions/      所有 target 共用的构建约定
```

- `common` 只放 Java 8 兼容、无 Minecraft/loader 依赖的业务逻辑、DTO、算法和测试。
- `targets/*` 只放该 target 的入口、注册、事件、Minecraft API、Mixin、网络、渲染和 metadata。
- 不要在 `common` 引用 `net.minecraft.*`、Forge、NeoForge、Fabric、Mixin 或渲染/网络 API。
- 不要用运行时版本判断、反射或同名 class 覆盖来兼容不同 target；不同 API 应由各 target 的适配器实现。
- 一个发布 jar 只对应一个 loader 与一个 Minecraft 版本，禁止 universal jar。

## 文件与配置约定

| 内容 | 位置 | 规则 |
| --- | --- | --- |
| 共享 Java 代码 | `common/src/main/java/` | 必须保持 Java 8 与无平台依赖。 |
| 共享资源 | `common/src/main/resources/` | 会自动合并到所有 target 的最终 jar。 |
| 目标专属资源 | `targets/<name>/src/main/resources/` | 仅放该版本/loader 专属资源。 |
| Loader metadata | target 的 `src/main/resources/` | 保留在 target；Fabric、Forge、NeoForge 格式不可共用。 |
| 版本/loader 参数 | `targets/<name>/gradle.properties` | 不放在仓库根 `gradle.properties`。 |
| 共享模组信息 | 根 `gradle.properties` | 仅 `mod_*` 和 Gradle 运行参数。 |
| 本地 jar 依赖 | `targets/<name>/libs/` | 自动作为 `implementation` 依赖读取；不需要逐条声明。 |
| 发布配置 | `gradle/target-conventions/publish.gradle` | 统一管理，target 不复制发布逻辑。 |

`libs/` 中的普通 jar 不会自动带来传递依赖；依赖的其他 jar 也必须放入同一个 `libs/`，或改用正常的 Maven 依赖声明。不要把 `*-sources.jar`、`*-javadoc.jar` 或构建产物误放入此目录。

共享资源与 target 资源若有同路径文件，必须明确选择唯一归属；不要依赖覆盖顺序。加载器 metadata、Mixin 配置、access widener/access transformer 和版本专属语言文件一律归 target。

## 日常开发

1. 先判断改动是 `common`、单个 target、多个 target，还是构建/发布配置。
2. 单 target 改动只修改对应 `targets/<name>/`；不因方便而改动其他版本。
3. 修改 `common` 前先定义不含 Minecraft 类型的语义与接口，再为所有受影响 target 实现桥接。
4. 改动资源、metadata、Mixin、注册、事件、网络或渲染时，除构建外必须做相应的运行验证。
5. 不提交 token、账号、密码、私有仓库凭据、IDE 运行缓存或 `build/` 输出。

### 构建命令

每个 target 是独立 Gradle 根工程，应在它自己的目录中构建：

```powershell
cd targets\forge-1.20.1
.\gradlew.bat clean build
```

| Target | Gradle JVM |
| --- | --- |
| `forge-1.20.1` | JDK 21 |
| `fabric-1.20.1` | JDK 21 |
| `neoforge-1.21.1` | JDK 21 |
| `neoforge-26.1` | JDK 25 |

根项目的 `-PallTargets=true build` 只覆盖前三个 JDK 21 target，不能替代 NeoForge 26.1 的独立构建。

### 验证入口与模组环境

改动任何页面级功能前，先跑单一入口：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\jei-verify.ps1
```

它按顺序执行 `:common:test`、`scripts/jei-client-tests.ps1`、`scripts/jei-declared-smoke.ps1`，
每个阶段打印一行摘要，任一阶段失败则非零退出（阶段日志在 `build/tmp/jei-verify/`）。
该入口只覆盖单测与两个 harness；target 自身的 `./gradlew.bat build` 仍需单独跑。
三个脚本都靠 JDK 21 启动 Gradle：优先 `-JdkPath`，其次 `JAVA_HOME`，最后默认 `D:\program\jdk-21`，
都不存在时明确报错。

两个 harness 需要 `targets/neoforge-1.21.1/run/mods/` 下的测试模组包（68 个启用 jar + 8 个禁用）。
装配该包的工具已移入 `scripts/jei-mod-env/`，用法、可复用脚本与一次性历史步骤见该目录的
`README.md`；`run/mods` 与 `build/tmp/modsearch/` 都不入库，因此新 clone 需要先按该 README 重建——
现在有一条命令：`python scripts/jei-mod-env/download_pack.py`（按入库的 `pack-manifest.json` 只下缺的文件、
按 sha1 校验），随后 `python scripts/jei-mod-env/verify.py` 看第一行是不是 `jars: 68`（它最后一定会把 `jei`
报成缺失前置并以非零码退出，这是正常的：JEI 由构建 classpath 提供，不在这个包里）。该 README 同时列出 76 个
文件的完整清单（68 启用 + 8 禁用）与逐步命令，清单与 manifest 由
`build_pack_manifest.py` 交叉校验；它并说明为什么
`prune.py` 的 `report.json` 在入库文件里没有生产者（诚实缺口，不伪造）。
排查 harness 失败前先确认包里模组齐全，不要把环境缺失误判成页面回归。

服务端 smoke 的 RCON 传输在 `scripts/jei-rcon.ps1`：一次瞬时 socket 关闭或接收超时会在新连接上按
指数退避重发，上限由 `-MaxAttempts` 决定（历史上曾有一次运行因此中止）。该重试路径由
`scripts/jei-rcon-proof.ps1` 用一个假 RCON 服务端证明（断线、静默对端、关闭的端口、非传输错误四类），
它不需要 Minecraft 服务端。

### 客户端配方页测试（neoforge-1.21.1）

`neoforge-1.21.1` 额外有一个开发专用的第二模组 `jeieditortests`（代码在 `targets/neoforge-1.21.1/src/clientTest/`，
mod metadata 在 `src/clientTest/resources/META-INF/neoforge.mods.toml`）。它只由 `clientTest` run 加载
（`RUN mods=[jeieditor, jeieditortests]`），`sourceSets.main` 与发布 jar 都不包含它。
该模组会进入一个一次性世界，遍历 JEI 注册的全部配方页面，用编辑器自己的建模与打补丁路径断言：

- 已接入清单里的每个页面至少有一个可建模配方；
- 声明式页面的槽位键恰好是该声明的字段列表按本页槽位数展开的结果：输入为 `input.0 .. input.N-1`，
  输出为 `output`（本页只有一个输出槽）或 `output.0 .. output.M-1`（多个输出槽）；字段列表里含 `%d` 的字段
  是可重复字段（覆盖其后所有序号，且必须是最后一项），只为某页命名一部分槽位的声明会失败；
  若声明带 `withConcatenatedOutputs`（有序段列表），输出列表按 JEI 类别的加槽顺序拼接：单段仅在配方携带时
  贡献一个槽，重复段按数组长度展开，客户端只校验槽数边界（`RecipeFieldMapping.segmentCountFits`），
  序数落在拼接之外时服务端拒绝整份补丁（`RecipeFieldMapping.expandSegments`）；
  若声明由**配方推导的槽位序列**描述整页槽位（`DerivedSlotSequence`，Create 盆地页的实现在
  `CreateBasinSlotSequence`），客户端没有配方 JSON，因此只校验该序列自己的槽数下界与它声明的种类，
  并由测试模组用模组自己加载好的配方独立重算一遍：输入必须是「每个合并后的物品原料一个槽，随后每个流体原料
  一个槽」（合并规则 = `ItemHelper.condenseIngredients` 的 `Ingredient.getItems()` 逐项相等），
  物品带在流体带之前，槽数对不上就失败——这是客户端侧证明「页面画出的槽位与服务端的推导一致」的那条断言；
  「合并后的物品原料」这条重算只对声明了 `condensesItemInputs()` 的序列生效（Create 盆地那种物品带来自
  `getIngredients()` 的页面），非 Create 的序列（IE/Silent Gear 的物品带在 JSON 字段里）只受
  「物品带在流体带之前」「槽数与页面一致」两条通用断言约束；
  派生序列还可以声明**哪些输入序号没有字段**（`fieldBackedInputOrdinals(loadedRecipe, inputCount)` 返回
  「有字段」的序号**集合**）：客户端把其余输入槽建成只读（`ingredient() == null`）、不提供拖拽，测试模组则要求
  「页面上该槽显示的原料为空」的输入槽必须被该集合排除（即序列确实声明了它没有字段），末尾的
  `itemSlots + fluidSlots + fieldlessSlots == inputs` 断言把这些槽数算清。两种形态都已有页面：
  `cobblemon:campfire_pot_recipe` 的无字段槽位在**末尾**（预览调味料），`create:mechanical_crafting` 的
  无字段槽位**夹在中间**（`pattern` 的空格格子）——所以契约给的是序号集合而不是「前 K 个」；
  每个槽还可以有**自己的路径**（`DerivedSlot` 的 paths 是列表）：`create:sequenced_assembly` 的槽位顺着
  `sequence` 的每一步排布（base `ingredient`、deploying 画该步第 2 个物品、filling 画该步第 1 个流体、
  pressing/cutting 不画），序号到字段因此是一张路径表而不是模式；这类页面若把流体槽排在物品槽之前
  （sequenced assembly 的 filling 步就可能），要声明 `drawsBandsInDisplayOrder() == false`，
  「物品带全在流体带之前」那条断言才会跳过——它是盆地页的性质，不是派生序列的普遍性质；
- 嵌套原料字段（`withPreservedIngredientBase`）：`mekanism:painting` 的 `item_input` 在 160/176 份文件里是
  `{"type":"neoforge:difference","base":…,"subtracted":…}`，写回只替换 `base`、保留 `type`/`count`/`subtracted`。
  这个节点在页面上看不见（JEI 只显示 difference 的成员），所以测试模组自己从数据包资源管理器读出该配方的
  JSON、重算 base 减 subtracted，要求模型里那个槽的原料确实是差集里的成员，并且声明把这个字段标成保留嵌套结构；
  写出的节点本身由服务端冒烟测试断言；
- 声明必须能为每个槽位键解析出配方 JSON 字段（`RecipeFieldMapping.field`；有序段列表的输出槽位由服务端按配方
  JSON 解析，客户端不检查这条），展开规则由测试模组独立重算，不调用被测代码；声明的每个字段还带**原料种类**
  （`withFieldKind` / `withFluidFields` / `withChemicalFields`，默认物品），模型槽位的种类必须与该字段声明的一致，
  页面上该槽**真正显示的原料种类**也必须在该声明里（否则那个槽画得出来却永远拖不进去），
  且声明为流体/化学品的槽位不能为空——这正是「流体页可选」的前提；
  有序段列表的槽位按「任一段的种类」判定，因为哪一段落在该序数只有服务端读得到配方 JSON；
  测试模组独立展开出的字段，其种类必须等于服务端 gate 用的 `ModdedRecipeAdapters.declaredKinds`
  为该槽解析出的种类集合：两边把同一个槽解析成不同字段（即客户端看得见、服务端会拒）时失败，
  这正是曾经那条「流体字段声明不全」的形态；`common` 里另有 `SlotPatchShapeContractTest`
  按种类逐个校验客户端发出的补丁形状与服务端 `SlotPatchFields.acceptsProperty` 的接受/拒绝一致；
- 声明默认按 serializer 作用域，`withPageUid` 可以把一条声明缩到单个 JEI 页面：一个 Minecraft recipe type
  被模组注册成两个方向相反的页面时（`mekanism:rotary` 的 `condensentrating` 是化学品入 + 流体出、
  `decondensentrating` 反过来，两页读同一份配方 JSON），按 serializer 的声明只能描述其中一页，
  页面作用域声明用它自己的 uid 作键，客户端把该 uid 当补丁的 `serializer` 发出去，服务端按同一个键取回声明，
  该 serializer 本身不再被声明（只报 serializer 的补丁会被 `stale or unsupported patch` 拒掉）；
- 一个字段的**值形状**默认是对象（物品 `{"item"|"id",count}`，流体/化学品按 `ModdedJsonStyle`），
  `ModdedJsonStyle.AMOUNT` 表示「字段值就是数量」的裸数字（IE 焦炉的 `"creosote": 250`）：
  这种字段写回时不产生对象，声明还必须用 `withFixedFieldIds` 固定它的 id，否则拖入另一种同种类原料只会写数量、
  被读回成字段名隐含的那一种；客户端建模时只接受该 id 的候选，写回时拒绝别的 id；
- 写回路径：模型里每个**含原料**的输入槽与输出槽（包括多输出页的 `output.N`）必须能经
  `RecipeEditorAdapters.replaceSlot` 生成带该原料、且键带该槽位自身前缀的补丁；槽位应有的键随种类走：
  物品 `.item`/`.count`（旧有的形状不变），流体 `.fluid`/`.fluid_amount`，化学品 `.chemical`/`.chemical_amount`；
  非物品槽位还要反向断言物品拖放被拒，物品槽位反向断言流体拖放被拒（`foreign_kind_refused`）；
  槽位为空时按契约处理：声明页与 JEI 合成页的空产出槽（JEI 信息页、堆肥页、盔甲纹饰，以及
  `mekanism:sawing` 这类按配方可选的第二输出槽）必须抛 `IllegalArgumentException` 拒绝，合成网格的空格子
  必须能填进去，其余页面允许「填进去」或「干净拒绝」二选一（不接受别的 throwable，也不接受键不对的补丁）；
  燃料条目（`neoforge:furnace_fuel`）的 `input.0` 是它的身份（生成的配方 id 里编码了这个物品），
  物品替换同样必须拒绝，只有燃烧时间可编辑；标签页是这条槽位规则的例外——它的补丁带 `tag.*` 字段而不是
  槽位前缀，由客户端测试的标签分支单独断言（见上面 `minecraft:tag_recipes/*` 那条）；
- 清单里状态为 `gap` 的页面必须**一个模型都不产生**（一旦产生说明有人修好了该页，需更新清单；
  与已声明页面共用 serializer 的页面由声明里的只读页面名单保持只读，例如 `immersiveengineering:arc_recycling`）；
- 页面显示的配方可能不是管理器里那条配方的 id：`mekanism:smelting` 由 `MekanismRecipeType` 从 vanilla
  `minecraft:smelting` 现造、并把每条 id 改写成 `<prefix>+原始路径`（`RecipeViewerUtils.synthetic`，前缀
  `/mekanism_generated/`）。`RecipeViewerAliases` 记下这种页面的 id 规则（页面 uid → 前缀），编辑器与测试模组
  都经 `JeiRecipeIntrospection.aliasedRecipeHolder` 按它还原出真实配方 id，再走那条配方自己的 serializer
  建模与写回（补丁携带真实 id 与 `minecraft:smelting`），所以这一页的模型是 `recipe-holder` 路线而不是声明路线；
- `minecraft:tag_recipes/{item,block,fluid}` 已接入，走的是**标签补丁**而不是配方补丁：JEI 直接推
  `TagInfoRecipe`（一个标签的成员列表，既不是 `RecipeHolder` 也不是 `Recipe`），编辑器把它建模成
  **一个** `input.0` 槽（放着该标签的第一个成员）并改写标签本身——一个标签可以有 1052 个成员而
  `EditorModel` 最多 128 个槽，且每次操作只命名一个成员，所以「一成员一槽」既建不出来也用不上。
  每次操作走 `jeieditor:tag` 补丁（`tag.op` 为 `member.add`/`member.remove`/`tag.create`/`tag.delete`），
  配方 id 编码了注册表与标签 id（`TagPatchFields.recipeId`，因为 `minecraft:planks` 同时是物品标签和方块标签）；
  方块标签的成员是方块 id，而页面显示、拖放能带上的只有物品，所以落进方块标签的原料要经 `BlockItem#getBlock`
  换回它放置的方块，放不出方块的物品被拒。这三页在清单里是 `done`，由客户端测试的标签分支按
  「每页至少一个模型 + 一条 `member.add`/`member.remove`/`tag.create`/`tag.delete` 补丁」断言；
  右键菜单的三个手势（在成员槽上删成员 / 由该成员建标签、在标签槽上删标签）走的是同一批适配器方法，
  但客户端测试不驱动鼠标，因此只由代码审查覆盖；
- 编辑器能建模但不在清单里的页面会被列出（说明匹配了没打算接入的页面），不算失败。

vanilla 适配器只对**自己真正实现**的 serializer 建模与改写，按 serializer id 而不是配方类匹配：
`CraftingRecipeEditorAdapter` 只认 `minecraft:crafting_shaped`/`crafting_shapeless`，
`CookingRecipeEditorAdapter` 只认四个烹饪 serializer，`VanillaSpecialRecipeEditorAdapter` 只认
`minecraft:stonecutting`/`smithing_transform`/`smithing_trim`；三者由
`RecipeEditorAdapters.isImplementedVanillaSerializer` 合并，服务端写回（`RecipeEditsApplier`）也用它把门。
模组自带的 serializer 即使配方类继承 vanilla 类也不会被建模：写回会用 vanilla 形状重建整份 JSON，
而该模组的 codec 未必接受（`refinedstorage:recoloring` 要 `ingredient`/`dye`，重建出来的是
`ingredients`/`result`）。要支持这类 serializer 必须写声明（`ModdedRecipeAdapters`），由声明按原 JSON 打补丁。
删除配方不走这条重建路径，因此任何 serializer 都可以删除。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\jei-client-tests.ps1
```

脚本从 `docs/JEI_PAGE_COMPAT_TODO.md` 解析：状态 `done`/`declared` 的行进入已接入清单，`gap` 的行进入零模型断言
（`build\client-test\wired-pages.txt` 只写已接入页面）。随后启动 `runClientTest`，等 `build\client-test\report.json`
出现后结束客户端进程，再根据报告打印逐页表格（`gap` / `KNOWN UNSUPPORTED` / `NOT WIRED` 各有标注）。
客户端内断言失败或 `gap` 页面产生了模型时脚本退出码为 1。脚本里还留着一份「已知不可用」的锁定名单
（`$knownUnsupported`，与 `gap` 一样要求零模型），现在是空的——三个标签页接入标签补丁后由清单按 `done`
读入；`docs/**` 的变更由人工维护，脚本只读它。

测试模组进的是一个一次性世界，「在关卡里 + 无界面」会让游戏抓取光标并把它居中、限制在窗口内——
操作者的鼠标会被抢走。所以 `JeiEditorClientTests` 在每个 `ClientTickEvent.Post` 里（含报告写完之后）
都执行一次 `releaseGrabbedMouse()`：`isMouseGrabbed()` 为真就 `releaseMouse()`。这条 walk 全程走 JEI API、
不依赖物理鼠标，释放它不影响测试；**新增任何自动化客户端步骤时不要绕过这一步**。

### 发布

在对应 target 内运行 `publishMods` 可手动发布该 target 的 jar 到 CurseForge 和 Modrinth。根 `gradle.properties` 中配置非敏感项目 ID；token 只通过环境变量提供：

```powershell
$env:CURSEFORGE_TOKEN = '...'
$env:MODRINTH_TOKEN = '...'
.\gradlew.bat publishMods
```

发布前必须执行该 target 的 `clean build`，检查 jar 内的 metadata、共享 class、共享资源和版本范围。不要从根项目或错误 target 发布。

## 将既有项目迁入本框架

迁移应以“先可构建、再抽取共享代码、最后验证行为”为顺序，禁止先删除旧工程再尝试恢复。

1. **盘点原项目**：记录 Minecraft 版本、loader、JDK、Gradle、mappings、入口、Mixin、资源、数据生成、依赖与运行配置。
2. **建立 target**：为每个 `(loader, Minecraft 版本)` 建立 `targets/<loader>-<mc-version>/` 独立工程，包含 wrapper、`settings.gradle`、本地 `gradle.properties` 与 `../../common` 映射。
3. **复制专属层**：将入口、注册、事件、Mixin、渲染、网络、metadata 和版本专属资源放入对应 target；不要在一个 target 放多版本分支。
4. **抽取 common**：仅将不使用平台类型的状态、规则、计算、DTO 和接口迁到 `common`。把 Minecraft 对象转换为 primitive、字符串、UUID 或自定义 DTO 后再跨边界传递。
5. **迁移资源**：所有 target 共用的 assets/data/lang 放到 `common/src/main/resources/`；将 Fabric/Forge/NeoForge metadata 与版本专属 Mixin 配置保留在 target。
6. **迁移依赖**：可从公开仓库解析的依赖写入对应 target 的 `build.gradle`；仅本地提供的 jar 放入该 target 的 `libs/`。不要把 loader 依赖放入 `common`。
7. **迁移配置**：模组名称、ID、许可证、作者、描述等共享值放根 `gradle.properties`；Minecraft、loader、mappings、版本范围和 JDK 相关值放 target 本地属性。
8. **逐 target 验证**：使用要求的 JDK 运行 `clean build`，检查 jar 内容，并做最小 client 与 dedicated server 启动验证；涉及数据或资源时额外运行 data generation/reload 验证。
9. **记录差异**：不能立即统一的 API 或行为差异写入 `docs/version-differences/`，由 target 适配实现，不能以 common 中的版本判断掩盖。
10. **清理旧结构**：只有所有迁入 target 均可构建且已验证后，才删除旧代码、旧资源与旧构建入口。

## 提交前检查

- `common` 不含平台 import，且所有受影响 target 均已适配。
- 每个新增/修改的 target 使用正确 JDK 独立构建。
- 最终 jar 含正确 metadata、目标专属资源与共享 class/resources。
- 本地 `libs/` 内容明确且没有误提交的旧 jar。
- 发布相关改动不包含 token；项目 ID、版本类型、依赖关系和 changelog 已确认。
- README、版本差异文档和支持矩阵与实际 target 一致。
