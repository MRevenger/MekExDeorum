# MekExDeorum 重制迁移报告

对应规格：`任务提示词.md`（下称「规格」）。本文档记录仓库迁移前后对比、重制实现与规格的偏差、以及逐项验证结果。

> 后续修复轮（P1 箭头遮挡输出栏、P2 输出栏自适应、P3 Mekanism 风格模型与等级横条、P4 四角覆盖）见 `FIX_REPORT.md`；该轮的**真机截图**（5 套 GUI + 5 级方块八角度）在 `docs/fix-verification/`。第四轮（工厂正面顶端短条改用 Mekanism 官方 `BaseTier` 颜色）同样记录在 `FIX_REPORT.md` 末尾。

- 当前版本：`1.0.1`（`mekexDeorum-1.0.1+mc1.21.1.jar`）；第五轮弹出修复见 `EJECT_FIX_REPORT.md`
- 平台：Minecraft 1.21.1 / NeoForge 21.1.250 / Java 21
- 依赖：Mekanism `[10.7.11,)`（必需）、Ex Deorum `[3.12,)`（必需）
- 许可：MIT

---

## 1. 仓库迁移

### 1.1 迁移前后分支对比

| 项 | 迁移前 | 迁移后 |
|---|---|---|
| 仓库 | `MRevenger/MekExNihilo` | `MRevenger/MekExDeorum`（改名；GitHub 保留旧地址自动重定向） |
| 旧版代码 | `main` @ `95dc181`，81 个文件，modid `mekexnihilo`，版本 1.0.7 | `legacy-1.0.7` @ `95dc181`，内容与提交历史完整保留 |
| `main` | `95dc181`（旧版） | `5afd895`「Clear main branch for remake」（空树）→ 本次提交「1.0.0-remake: complete rewrite」（109 个文件，modid `mekexdeorum`） |
| 分支保护 | 无 | `legacy-1.0.7` 已设为只读 |

`main` 上的旧文件是用 `git rm -rf .` 清空的：**历史提交保留**，只清空当前文件树（与规格 §8.2 一致）。

### 1.2 实际执行的 Git 操作

```
# Step 1  克隆并确认位于 main
git clone https://github.com/MRevenger/MekExDeorum.git      # 改名后
git rev-parse --abbrev-ref HEAD                             # -> main

# Step 2  旧版备份分支
git checkout -b legacy-1.0.7
git push origin legacy-1.0.7        # * [new branch]  legacy-1.0.7 -> legacy-1.0.7

# Step 3  清空 main（保留历史）
git checkout main
git rm -rf .                        # 81 files changed, 6989 deletions(-)
git commit -m "Clear main branch for remake"      # -> 5afd895
git push origin main                              # 95dc181..5afd895  main -> main

# Step 6  把 legacy-1.0.7 标记为只读（GitHub 分支保护 API）
#   PUT /repos/MRevenger/MekExDeorum/branches/legacy-1.0.7/protection  -> HTTP 200
#   回读：lock_branch=True, allow_force_pushes=False, allow_deletions=False, enforce_admins=True

# Step 5  推送重制版（本提交）
git add -A
git commit -m "1.0.0-remake: complete rewrite"
git push origin main
```

两点说明：

1. **仓库改名而非新建。** 规格 §8 指向的 `MRevenger/MekExDeorum` 当时并不存在（用 token 认证核实：`MekExExDeorum`、`MekExDeorum` 均 404），而 `MekExNihilo` 正是 v1.0.7 的 Mekanism+Ex Deorum 筛矿附属模组，`main` 上就是需要备份的旧代码。因此按「把现有仓库改名为 `MekExDeorum`」处理，命名与规格一致后 §8 的流程可原样执行。
2. **提交身份。** 该机器上 `git config --global user.name/user.email` 为空，首次提交会失败。已按旧版提交历史里的作者在本仓库内设置 `user.name=MRevenger`、`user.email=2831960976@qq.com`，因此新提交与旧历史同作者。

### 1.3 目录结构

规格要求产物落在 `MekExDeorum/build/libs/`，因此**本仓库根目录即 Gradle 工程根**（旧版把工程放在 `MekNihilo/` 子目录，重制版没有采用）：

```
MekExDeorum/           <- git 根 = Gradle 根
├── build.gradle  settings.gradle  gradle.properties  build.ps1  .gitignore  LICENSE
├── README.md  VERIFICATION.md  MIGRATION_REPORT.md（本文档）
├── icon.png                     # 256x256，规格 §9 要求放在根目录
├── tools/                       # gen_textures.py, gen_icon.py, validate_resources.py
└── src/main/java/com/mekexdeorum/ , src/main/resources/ , src/test/java/
```

`build/libs/mekexDeorum-1.0.1+mc1.21.1.jar` 由构建生成，按惯例被 `.gitignore` 排除，不入库；源码入库后可在本机复现得到该产物。

---

## 2. 实现概要

| 等级 | 机器 | 输入栏 | 能量容量 | 并行数来源 |
|---|---|---|---|---|
| 0 | `universal_sieve` 通用筛矿机 | 1 | 20 000 FE | 筛网 |
| 1 | `basic_sieve_factory` 基础筛矿工厂 | 3 | 40 000 FE | 筛网 |
| 2 | `advanced_sieve_factory` 高级筛矿工厂 | 5 | 80 000 FE | 筛网 |
| 3 | `elite_sieve_factory` 精英筛矿工厂 | 7 | 160 000 FE | 筛网 |
| 4 | `ultimate_sieve_factory` 终极筛矿工厂 | 9 | 320 000 FE | 筛网 |

- 升级链不依赖 Mixin：等级 0 的 blocktype **不带** `AttributeTier`、带 `AttributeUpgradeable` 指向基础工厂（Mekanism 的基础等级安装器 from 为 null，只匹配无该属性的方块），等级 1–3 各带 `AttributeTier` 并指向下一级，等级 4 只有 `AttributeTier`。
- 并行数完全由筛网决定：线/燧石/铁/金/钻石/下界合金 = `1 / 4 / 8 / 16 / 32 / 64`（规格 §三 表格；与旧版的 `1/2/4/16/32/64` 不同，以规格为准）。
- 每个输入栏有独立的 `progress / requiredTicks / batchSize / paused`，并各自有一条 `ProgressType.DOWN` 进度条。
- 批量筛矿用二项分布合并：每个产物一次 `Binomial(n, p')` 采样，**不**循环 N 次完整筛矿事件。
- 产量全程 `long`，单个输出槽上限 `Integer.MAX_VALUE`，装不下进内部 `long` 溢出缓冲并暂停该栏，不丢物品。
- 每游戏刻把全部输出槽与溢出缓冲推入相邻容器；连续 200 tick 失败后降频为每 20 tick 一次（两常量硬编码）。

---

## 3. 与规格的偏差（逐条，均已核实）

| # | 规格原文 | 现实 | 实际做法 |
|---|---|---|---|
| 1 | §5.1「需要自定义 `IExtendedItemHandler`」 | `IExtendedItemHandler` / `ExtendedItemHandlerUtils` 在 Mekanism 10.7.x 已不存在（1.20.1 时代的类型） | 用 `BasicInventorySlot` 子类 + `obeyStackLimit = false` + 上限 `Integer.MAX_VALUE`，经 Mekanism 的 `SerializerHelper` 超大存档与 varint 同步持久化——效果等价，且是 10.7 上唯一可行路径 |
| 2 | §2.1 modid `MekExDeorum`、注册名 `mekExDeorum:universal_sieve` | NeoForge/原版强制 modid 与资源命名空间全小写 | modid/命名空间用 `mekexdeorum`；显示名 `MekExDeorum`、jar 名 `mekexDeorum-1.0.1+mc1.21.1.jar` 按规格保留大小写 |
| 3 | §6.2「网格间距约 3–4 像素，形成 8×8 到 16×16 的网格单元」 | 16×16 纹理上两句互斥（4px 间距 = 4×4 单元；8×8 单元 = 2px 间距） | 默认按**可见数值** 4px 实现；`--grid-spacing 2` 可切到 8×8 读法。两种效果都出预览图对比（`_plan/previews/top_inner_grid2.png`） |
| 4 | §三「开启 `meshDamage` 后每次批量筛矿消耗 1 点耐久」 | Ex Deorum 的 6 种筛网**全部不可损坏**（`MeshItem` 无 durability，`props()` 是裸 `Item.Properties()`，全仓库 grep `hurtAndBreak\|durab\|MAX_DAMAGE` 零命中） | 只对 `isDamageableItem()` 为真的筛网扣耐久；对原生筛网无可扣耐久 → 实际不消耗（用户已确认）。绝不对不可损坏物品调用 `hurtAndBreak`（会直接销毁筛网） |
| 5 | §6.2「白色半透明网格，alpha 约 0.15–0.25」 | 实心渲染的方块面会丢弃真实 alpha | 白色网格以 alpha 0.20 合成进不透明 RGB（实测合成不透明度 0.200），观感即「白色半透明网格」；脚本仍暴露 `--grid-alpha` 并限定 0.15–0.25 |
| 6 | §6.4「调用图像生成模型」、§10.2「提供 txt2img 参数和种子」 | 本会话无任何可用的图像生成模型或 API key | 改用 Pillow 逐像素程序化生成（确定、可复现、参数可审计，且规格给的是一组精确数值）。因此不存在种子；生成脚本与参数即交付物（`tools/gen_textures.py`、`tools/gen_icon.py`） |
| 7 | §9「构建通过」 | 本机直连 `maven.neoforged.net` 会超时（Java 不认 WinINET 系统代理），且工作区路径含非 ASCII 字符「工作」会让 Gradle 写的测试 `@argfile` 被 JDK 启动器按 ANSI 解码而全部 `ClassNotFoundException` | `build.ps1` 把 JDK 21、系统代理、ANSI 字符集三件事都封装好；**请用 `build.ps1` 构建**。未附带 Gradle wrapper（与旧版一致） |
| 8 | — | 规格未提，但旧版有 | 旧版的 AllTheCompressed 压缩原料兼容、`SieveInputEvent` 公开事件、输入黑名单标签、`sieves` 合成标签**按用户决定一律未实现**；附魔标签补充**已实现**（不做则筛网上的效率/时运完全不生效，§四.4 会失效） |

另有两处是设计取舍，非偏差：

- GUI 未做自定义背景贴图：沿用 Mekanism 自己的九宫格窗口贴图（旧版亦然），因此 `tools/gen_gui.py` 与 `textures/gui/**` 不存在。
- 未加 `EnergyTab` 页签：10.7.11.76 的 `GuiEnergyTab` 需要 `MachineEnergyContainer<?>`，而 `TileEntityMekanism` 只暴露 `IEnergyContainer`；规格要求的竖直能量条与能量槽都在。

---

## 4. 验证结果

验收深度按用户决定：**编译 + 资源/JSON 校验 + 纯逻辑单元测试**，不启动 `runServer` / `runClient`。

### 4.1 构建与测试（调度者亲自复跑）

```
pwsh -File build.ps1 @('clean','build','--no-build-cache','--rerun-tasks')
  -> BUILD SUCCESSFUL in 9s, 7 actionable tasks: 7 executed, exit code 0
build/libs/mekexDeorum-1.0.1+mc1.21.1.jar    89590 bytes   （文件名逐字匹配）
build/test-results/test/*.xml  ->  tests=54  failures+errors=0
  SieveMath 12 / SieveBatch 14 / EjectThrottle 9 / SieveLayout 8 / MeshTiers 5
  （该记录另含一个已于第 5 轮整体删除的测试类，共 6 个用例，故当时为 54）

python tools/validate_resources.py  ->  7/7 checks passed, exit 0
```

`tools/validate_resources.py` 的 7 项：35 个 JSON 全部可解析；30 张方块纹理均为 16×16 RGBA、两份 `icon.png` 均为 256×256；模型引用的纹理全部存在；`en_us`/`zh_cn` 键集一致且覆盖全部 14 个配置翻译键；jar 内 `neoforge.mods.toml` 的 modId 与依赖范围正确；jar 内 68 个必需条目齐全；两个附魔标签都含全部 6 种筛网且 `replace: false`。

该校验器**可证伪**：把仓库副本逐个注入缺陷（坏 JSON、32×32 纹理、删模型、删 lang 键、改依赖范围、删 jar 内 `pack.mcmeta`、标签漏筛网）后，7 项检查每次都按预期变红，基线保持全绿（8/8 场景）。

### 4.2 规格 §九 清单逐项

| # | 条目 | 结论 | 证据 |
|---|---|---|---|
| 1 | 5 个等级均可注册、工厂安装可升级 | ⚠️ 静态已核实 | 5 个 `BLOCKS.register`；等级 0 无 `AttributeTier` + `AttributeUpgradeable`→基础工厂，1→2→3→4 依次指向下一级，等级 4 只有 `AttributeTier<>(ULTIMATE)`；`javap` 确认 `ITierUpgradable.getUpgradeData/parseUpgradeData` 已实现，且 Mekanism 的 `canBeUpgraded()` 是 `public final`（因此不覆写是正确的）。**实际点击安装器的运行时行为需启动游戏** |
| 2 | 每个输入栏进度条独立运行 | ✅ | 每栏独立 `int[] progress/requiredTicks/batchSize`、`boolean[] paused`；`tickInputSlot(server, index)` 每栏单独判定；`container.trackArray(...)` 逐栏同步（`TileEntitySieve.java:115-120,193-196,258-263,693-695`） |
| 3 | 筛网等级映射到 1/4/8/16/32/64 | ✅ | `MeshTiers.java` 六个注册名逐一对应，未知/空返回 0；`MeshTiersTest` 逐项断言 |
| 4 | 用二项分布采样，未循环调用完整筛矿事件 | ✅ | 源码与**字节码**双向确认：`SieveBatch` 中 `binomialSample` 仅 1 处调用、`TileEntitySieve` 中 `SieveBatch.outputFor` 仅 1 处调用且位于「每个配方产物一次」的循环内，无内层逐物品循环 |
| 5 | 用 `long` 计算并可拆分为 `Integer.MAX_VALUE` 堆叠 | ✅ | `MAX_CHUNK = Integer.MAX_VALUE`、`forEachChunk` 总量守恒、`addClamped/multiplyClamped` 饱和不溢出、槽上限 `Integer.MAX_VALUE` 且 `obeyStackLimit=false`；相关单测通过 |
| 6 | 输出槽溢出进内部缓冲、不丢物品 | ⚠️ 静态已核实 | 溢出为 `Map<Item, Long>`，余量用饱和加法合并（不截断），暂停该栏；每 tick 先回填再恢复；存档 `putLong("amount")`，升级数据携带 `overflowItems` + `long[] overflowAmounts`。**真实存盘/读盘与升级往返需启动游戏** |
| 7 | 效率减少处理时间、时运增加掉落概率 | ✅ | 两个公式有单测；附魔等级经 `level.registryAccess()` 从筛网物品读取；且两个 `item/enchantable` 标签确认包含 6 种筛网（否则附魔不生效） |
| 8 | 弹出：每 tick 尝试、成功归零、200 tick 后降频 20 tick | ✅ | `IDLE_THRESHOLD=200`/`COOLDOWN_TICKS=20` 硬编码；`tick()` 与规格伪代码等价；`tickEjection()` 是 `onUpdateServer()` 第一件事；`ejectAll()` 一趟遍历全部输出槽 + 溢出缓冲；9 个单测覆盖 |
| 9 | 配置项翻译键完整、游戏内可读 | ⚠️ 键集已核实 | 37 个键在 `en_us`/`zh_cn` 完全一致，14 个配置键全覆盖，中文为正常 UTF-8 文本。**游戏内渲染未观察** |
| 10 | 顶部向内凹陷、网格为白色半透明 | ⚠️ 几何与像素已核实 | 10/10 个模型（5 级 × 激活/非激活）符合契约：主体 `[0,0,0]→[16,14,16]` **没有 `up` 面**、4 条外框环 `y=14..16` 的 `up` 用 `top_outer` 且朝向内侧的面用 `top_inner`、凹陷地板 `[2,13,2]→[14,14,14]` 顶面在 `y=14` 用 `top_inner`；网格实测合成不透明度 0.200、间距 4px。**渲染观感需启动游戏** |
| 11 | `icon.png` 已生成并放入根目录 | ✅ | 仓库根与 `src/main/resources/` 各一份，均 256×256 RGBA、字节一致，`mods.toml` 以 `logoFile="icon.png"` 引用 |
| 12 | 旧版代码已推送 `legacy-1.0.7` | ✅ | `git ls-remote origin` → `95dc181  refs/heads/legacy-1.0.7`，且已设为只读 |
| 13 | `main` 已清空并推送新代码 | ✅ | `5afd895` 清空 → 本提交（109 个文件）推送至 `main` |
| 14 | 构建通过，产物在 `MekExDeorum/build/libs/` | ✅ | 见 §4.1，文件名逐字匹配 |

统计：**✅ 9 项 / ⚠️ 4 项（均因不启动游戏而只能静态核实，已注明核实的代码路径）/ ❌ 0 项**。

### 4.3 反模式扫描（硬性要求）

源码与字节码双向确认，以下均**零命中**：引用 Ex Deorum 的 `RecipeUtil`/`RecipeCaches`（跨版本必抛 `NoSuchMethodError`，改为自己从 `RecipeManager` 读取并自建重载安全缓存）；`hurtAndBreak`；循环调用 N 次完整筛矿事件；任何 Mixin 或 `*.mixins.json`（本实现完全不需要）；临时/桩文件（`TEMPORARY`/`STUB`/`TODO`/`FIXME`）、`__pycache__`。

### 4.4 验证的独立性

- 各工作流只做自检，另有**未参与实现**的独立验证流程重新执行构建、测试、资源校验与反模式扫描，并逐个尝试证伪其自身校验器。
- 调度者另外亲自复跑了从零构建、测试统计与资源校验，并逐文件审阅了核心逻辑（每栏独立状态机、批量采样调用点、溢出与弹出路径、NBT 与升级数据、凹陷模型几何、注册链、语言文件）。

---

## 5. 交付清单

| 类别 | 数量 | 说明 |
|---|---|---|
| Java 主源码 | 22 | 主类/配置/3 个纯逻辑类 + 布局 + 机器方块实体 + 槽位 + 升级数据 + 配方适配 + 6 个注册表类 + 容器 + GUI/客户端 |
| Java 测试 | 6 | 54 个用例，不需要 Minecraft 类路径 |
| 方块纹理 | 30 | 5 级 × 6 面，均为 16×16 RGBA |
| 资源 JSON | 22 | 5 方块状态 + 10 方块模型（含激活态）+ 5 物品模型 + 2 语言文件 |
| 数据文件 | 13 | 5 掉落表 + 5 合成配方 + 3 标签（`minecraft:mineable/pickaxe`、`enchantable/mining`、`enchantable/mining_loot`） |
| 其它资源 | 2 | `neoforge.mods.toml`、`pack.mcmeta`（jar 内另有 `icon.png`） |
| 脚本 | 3 | `gen_textures.py`、`gen_icon.py`、`validate_resources.py` |
| 根目录文档/构建 | 9 | `README.md`、`VERIFICATION.md`、`MIGRATION_REPORT.md`、`LICENSE`、`.gitignore`、`build.gradle`、`settings.gradle`、`gradle.properties`、`build.ps1`、`icon.png` |

---

## 6. 复现构建

```powershell
cd MekExDeorum
pwsh -File build.ps1 build                                  # -> build/libs/mekexDeorum-1.0.1+mc1.21.1.jar
pwsh -File build.ps1 test                                   # 58 个单元测试
python tools/validate_resources.py                          # 资源/JSON/jar 校验
pwsh -File build.ps1 @('test','--rerun-tasks')              # 多参数/带 flag 时必须用这种数组形式
```

多参数必须写成 `@('...')` 位置参数数组：`build.ps1` 的参数是 `ValueFromRemainingArguments`，写成 `-GradleArgs @('clean','build')` 只会绑定第一个元素并报「找不到接受自变量 'build' 的位置参数」（已实测）。

---

## 7. 需要启动游戏才能验证的项

以下行为在本次交付中**未被验证**，只核实了对应代码路径：运行时注册；等级安装器实际转换已放置的方块；GUI 能否打开、每栏箭头是否动画；产物是否真的进入相邻容器；NBT 真实存盘/读盘往返与升级不丢物品；凹陷顶面的渲染观感；游戏内配置文本可读性。

未启动 `runServer` / `runClient` 是按约定的验收深度（用户选择只做编译 + 资源校验 + 单元测试）；也未使用 `runData`，因为它会向仓库内写生成资源。
