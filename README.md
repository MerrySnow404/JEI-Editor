# JEI Editor

在游戏内直接编辑 JEI 配方页面的 NeoForge 模组：改原料、改产出数量、改燃料燃烧时间、建/删配方，以及增删标签成员、建/删标签。编辑结果落盘成世界数据包里的 JSON，`/reload` 与重启都保留。

当前实现只面向 **NeoForge 1.21.1**（`targets/neoforge-1.21.1`）；`common` 是不含 Minecraft 依赖的纯逻辑与测试。另外三个 target 目录已停用，不参与构建与发布。

## 功能

### 编辑开关

JEI 配方页右键 → `Editing: ON` / `Editing: OFF`（没有按键绑定）。打开后：

- 配方页上属于本编辑器的槽位可以直接拖放编辑；
- 右侧原料索引里会多出**标签**这一种原料（显示成 `#命名空间:标签`，只在编辑模式下出现），可以像物品一样拖进配方槽。

### 手势

| 想做什么 | 怎么做 |
| --- | --- |
| 设置/替换某个槽的原料 | 从 JEI 索引、书签或页面里拖一个原料到该槽上 |
| 清空输入槽 | 右键槽位 → `Clear Input Slot`（只对可变长输入列表生效；固定字段的清空会被拒绝） |
| 改产出数量 | 产出槽上滚滚轮 |
| 删除整条配方 | 右键配方 → `Delete Recipe`；再点一次 `Cancel Recipe Delete` 撤销 |
| 新建一条配方 | 在 JEI 空白处右键 → `New Recipe`，然后在页面上输入配方 id（命名空间强制为 `jeieditor`） |
| 提交 / 重载 / 撤销本页改动 | 右键 → `Save` / `Reload` / `Reset Page Changes` |
| 改燃料燃烧时间 | 燃料页上的输入框 |
| 改铁砧经验花费 | 铁砧页上的输入框 |
| 标签：加一个成员 | 把原料拖到标签页的标签槽上 |
| 标签：去掉一个成员 | 右键成员格 → `Remove <id> From Tag` |
| 标签：用某个成员建一个新标签 | 右键成员格 → `New Tag From This Element...`，再输入标签名 |
| 标签：删除整个标签 | 右键标签槽 → `Delete Tag` |

### 能编辑哪些页面

按三种方式接入，[docs/JEI_PAGE_COMPAT_TODO.md](docs/JEI_PAGE_COMPAT_TODO.md) 是权威清单（由运行时导出生成，一页一行给出状态与理由）：

1. **vanilla 适配器**（按 serializer 精确匹配，不看配方类）：`crafting_shaped` / `crafting_shapeless`、四个烹饪 serializer、`stonecutting`、`smithing_transform` / `smithing_trim`。
2. **声明式模组页**：一条声明描述一个模组配方类型（Create、Immersive Engineering、Mekanism、Silent Gear、Cobblemon 等）。页面的槽位顺序与 JSON 字段对不上时，声明可以写成**配方推导的槽位序列**——Create 盆地页把相同原料合并成一条带、序列组装页按 `sequence` 的每一步逐槽给路径、机械合成页按 `pattern` 的字母寻址；页面上"没有字段"的槽位（预览槽、图案空格）声明成空路径，客户端把它们建模成只读，补丁打到上面会被整条拒绝。
3. **JEI 自己生成的页面**：酿造、铁砧、砂轮、堆肥、信息页、燃料，以及**三个标签页**（`minecraft:tag_recipes/item|block|fluid`）。

当前覆盖率：188 个注册页面里 **94 页可编辑**、**28 页 `gap`**（每页都有实测理由，门禁反过来断言它们一个模型都不产生）、0 页"已知不支持"。

### 数据落在哪

- **配方编辑**写进世界的数据包 `<世界>/datapacks/jeieditor-generated/data/<命名空间>/recipe/<路径>.json`——**生成的数据包文件是唯一事实来源**，不是内存里的临时覆盖；删除配方写一份空覆盖并把它从活着的配方表里摘掉。
- **标签编辑**写进同一个数据包的 `data/<命名空间>/tags/<item|block|fluid>/<路径>.json`，内容是**整份成员快照 + `"replace": true`**（标签文件在多个数据包之间是合并的，只追加一条表达不了删除），写完触发一次资源 reload——标签没有像配方那样的热替换路径。
- **JEI 生成页**（燃料燃烧时间、铁砧花费、酿造）存在世界存档的 `SavedData` 里，并靠运行时事件覆盖。
- **权限与命名空间**：`config/jeieditor.properties`（`min_permission_level`、`allow_namespaces`、`deny_namespaces`）。命令行入口 `/jeieditor import <名字>` 从 `config/jeieditor/exports/<名字>.json` 读补丁包。

## 快速搭建测试环境

### 0. 前置

- Windows + PowerShell 5+
- **JDK 21**。所有脚本按 `-JdkPath` → `JAVA_HOME` → 默认 `D:\program\jdk-21` 的顺序找，都找不到会明确报错。
- Python 3（只在要用模组环境工具链时需要，标准库即可，不需要第三方包）
- 首次构建需要网络

### 1. 两个被 gitignore 的本地依赖镜像

新 clone 里没有这两个目录，而构建会用到：

- **`modrinth-maven/`** —— 主源集与客户端测试源集都依赖 `cc.sighs.modrinthmirror:jei:<jei_modrinth_version>`（版本号在 `targets/neoforge-1.21.1/gradle.properties`，当前是 `LtwmFHuF`）。重建方法：把仓库里已有的 `targets/neoforge-1.21.1/libs/jei-1.21.1-neoforge-19.44.0.403.jar`（同一个 jar）复制成 `modrinth-maven/cc/sighs/modrinthmirror/jei/LtwmFHuF/jei-LtwmFHuF.jar`，并在同目录写一个只声明坐标的 `jei-LtwmFHuF.pom`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>cc.sighs.modrinthmirror</groupId>
  <artifactId>jei</artifactId>
  <version>LtwmFHuF</version>
</project>
```

- **`.maven-mirror/`** —— 钉住的 NeoForge 21.1.238 产物。本项目所在环境里 Gradle 的 JVM 连 NeoForge Maven 会被 TLS 重置（`build.gradle` 里有注释），所以留了这份本地镜像；网络正常时它不是必需的。

### 2. 构建并手跑

```powershell
cd targets\neoforge-1.21.1
.\gradlew.bat clean build      # 产物：build/libs/
.\gradlew.bat runClient        # 开发客户端（JEI 已经在 classpath 上）
```

客户端起来后：随便打开一个 JEI 配方页，右键 → `Editing: ON`，拖一个原料进槽位，再右键页面 `Save`，就能在世界的数据包里看到生成的 JSON。`.\gradlew.bat runServer` 是开发服务端。

> 根项目的 `build` 只同步 `common`；每个 `targets/*` 是独立的 Gradle 根工程，要在它自己的目录里构建。

### 3. 跑验证门禁（需要测试模组包）

单一入口：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\jei-verify.ps1 -JdkPath "D:\program\jdk-21"
```

它按顺序跑三段，每段一行摘要，任一失败就非零退出（日志在 `build/tmp/jei-verify/`）：

1. `:common:test` —— 纯逻辑单测
2. `scripts\jei-client-tests.ps1` —— 真开发客户端，进一次性世界遍历 JEI 注册的全部页面，断言每页的建模形状与补丁往返
3. `scripts\jei-declared-smoke.ps1` —— 专用服务端 + RCON 导入补丁，断言生成的数据包 JSON 与拒绝行为

后两个也可以单独跑；smoke 加 `-Offline` 让 Gradle 离线。

**测试模组包**：两个 harness 都读 `targets/neoforge-1.21.1/run/mods/`（68 个启用 jar + 8 个 `.jar.disabled`）。`run/` 与 `build/` 都不入库，所以新 clone 里没有这个包。一条命令装好：

```powershell
python scripts/jei-mod-env/download_pack.py    # 只下缺的，下完按清单里的 sha1 校验
python scripts/jei-mod-env/verify.py           # 期望第一行：jars: 68   distinct mod ids provided: 101
```

`download_pack.py` 读的是入库的 `scripts/jei-mod-env/pack-manifest.json`（76 条，共约 241 MiB）：每个文件的 Modrinth 项目/版本/下载地址与 sha1/sha512，以及它是否属于那 8 个保持禁用的文件。清单由 `build_pack_manifest.py` 在**已有安装包的机器上**用每个 jar 的 sha1 向 Modrinth 反查生成（所以项目 slug、文件名、依赖图都不用猜），并与 `scripts/jei-mod-env/README.md` 里的 76 个文件名清单交叉校验，两者不一致就直接失败。
`verify.py` 最后一定会把 `jei` 报成缺失前置并以非零码退出——JEI 不在这个包里，两个 harness 从构建 classpath 提供它——所以要看的是第一行的 jar 数，以及除了 `jei` 没有别的缺失项。

排查 harness 失败前先确认这个包是完整的，别把环境缺失当成页面回归。

> 客户端测试会在每个 tick 把光标交还给操作者（`isMouseGrabbed()` 为真就 `releaseMouse()`），所以跑它不会抢你的鼠标。

### 4. 其他脚本

| 脚本 | 用途 |
| --- | --- |
| `scripts/jei-rcon.ps1` | smoke 用的 RCON 传输（瞬时断连/接收超时按指数退避重发） |
| `scripts/jei-rcon-proof.ps1` | 用假 RCON 服务端证明上面那条重试路径，不需要 Minecraft |
| `scripts/runtime-smoke.ps1 -Target <名字>` | 真客户端 + 服务端跑一遍导入验证 |
| `scripts/build-target.ps1 -Target <名字>` | 带重试地构建某个 target |
| `scripts/discover-targets.ps1` | 列出现有 target |
| `scripts/jei-category-dump/` | 运行时导出 JEI 分类，并据此重新生成兼容清单 |

（`neoForge.runs.sim` 走的是仓库外的 AutoJS 工装，不在快速搭建范围内。）

## 项目结构

```text
common/                          纯 Java（Java 8 兼容）、无 Minecraft / loader 依赖的共享逻辑与测试
targets/<loader>-<mc 版本>/       一个独立的加载器 + Minecraft 版本工程
gradle/target-conventions/       所有 target 共用的构建约定
scripts/                         验证门禁、模组环境工具链、分类导出工具
docs/                            架构、兼容清单、工作流与实测记录
```

- 共享资源放 `common/src/main/resources/`，构建时与 target 自己的 `src/main/resources/` 合并进最终 jar；loader metadata（`META-INF/neoforge.mods.toml`）留在 target。
- `targets/<名字>/libs/` 里的 `*.jar` 会自动作为 `implementation` 依赖读入，不必逐条声明；本地 jar 的传递依赖需要自己补齐。
- 开发约定与迁移规范见 [AGENT.md](AGENT.md)。

## 发布

在 target 目录内运行 `publishMods` 发布到 CurseForge 与 Modrinth。非敏感的项目 ID 填在根 `gradle.properties`，token 只从环境变量读：

```powershell
$env:CURSEFORGE_TOKEN = '...'
$env:MODRINTH_TOKEN = '...'
cd targets\neoforge-1.21.1
.\gradlew.bat publishMods
```

发布前先跑该 target 的 `clean build`，并检查 jar 里的 metadata、共享 class 与资源、版本范围。不要从根项目或错误的 target 发布。

## 文档

- [JEI 页面兼容清单（每页状态与理由）](docs/JEI_PAGE_COMPAT_TODO.md)
- [单个 JEI 配方页面接入可视化编辑的工作流](docs/JEI_PAGE_COMPAT_WORKFLOW.md)
- [支持的配方页与两层适配](docs/SUPPORTED_RECIPES.md)
- [架构](docs/ARCHITECTURE.md)
- [真机 UI 实测记录](docs/JEI_REAL_UI_TEST_FINDINGS.md)
- [测试模组包装配说明](scripts/jei-mod-env/README.md)
- [1.21.1 NeoForge JEI 附属清单](docs/JEI_ADDONS_1.21.1_NEOFORGE.md)、[需要内容模组的附属](docs/JEI_ADDONS_CONTENT_MODS.md)、[被禁用的附属](docs/DISABLED_JEI_ADDONS.md)
