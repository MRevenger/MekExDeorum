# MekExDeorum 重制 — 独立验证报告（T5）

> 本报告由 **未参与任何代码编写**的验证工作流 T5 产出。结论全部来自本机实跑/实读，
> 见到的原始输出已写入 `_plan/t5_scratch/`（`formB2.txt`、`p1.txt`、`p2.txt`、`p3.txt`、
> `tile.txt`、`sievebatch.txt`、`compat.txt`）。
> 环境：Windows + pwsh 7；JDK 21.0.10 Alibaba Dragonwell；Python 3.10.6 / Pillow 12.2.0；
> Gradle 8.14.2（`~/.gradle/wrapper/dists`）；构建一律经 `build.ps1`（含代理与 cp936 处理）。

判定口径：
- ✅ = 该条要求已用可复现的命令/实读证据证完；
- ⚠️ = 已静态/单测证到，但**明确有部分内容必须开游戏才能确认**（在行内写明哪一部分）；
- ❌ = 与该条要求不符或尚未完成。

---

## 1. 构建命令形式（实测结论，README 与迁移报告必须照此写）

`build.ps1` 的参数是
`[Parameter(ValueFromRemainingArguments = $true)][string[]]$GradleArgs = @('build')`。

| 形式 | 结果 | 原始证据 |
|---|---|---|
| `pwsh -File build.ps1 @('clean','build','--no-build-cache','--rerun-tasks')` | ✅ **可用**（本报告采用） | `BUILD SUCCESSFUL in 9s` / `7 actionable tasks: 7 executed`，exit 0 |
| `pwsh -File build.ps1 @('build')` | ✅ 可用 | `BUILD SUCCESSFUL in 5s`，exit 0 |
| `pwsh -File build.ps1`（不带参数，默认 `build`） | ✅ 可用（param 默认值） | — |
| `pwsh -File build.ps1 -GradleArgs @('build')` | ✅ 可用（**仅单元素数组能用**） | `BUILD SUCCESSFUL in 4s`，exit 0 |
| `pwsh -File build.ps1 -GradleArgs @('clean','build')` | ❌ **失败** | `build.ps1: 找不到接受自变量 'build' 的位置参数。`（A positional parameter cannot be found that accepts argument 'build'）exit 1 |
| `pwsh -File build.ps1 -GradleArgs @('test','--rerun-tasks')` | ❌ **失败** | `build.ps1: 找不到与参数名称 '-rerun-tasks' 匹配的参数。`（A parameter cannot be found that matches parameter name '-rerun-tasks'）exit 1 |

结论：
- **多参数（含 `--flag`）只有「剩余参数数组」形式可用**：`@('clean','build','--no-build-cache','--rerun-tasks')`。
  原因：`pwsh -File` 会把 `-GradleArgs` 之后的下一个 token 绑成它的值，剩下的 token 再被当作脚本参数名/位置参数解析，于是第二个参数就报错。
- `-GradleArgs` 单元素时之所以"能用"，是因为那一个 token 恰好被当作参数值吃掉，**不是**多参数可用；
  第一个作者报告的「`-GradleArgs @('clean','build')` 报位置参数错误」是**正确的**；
  第二个作者/`build.ps1` 注释里记的 `-GradleArgs @('test','--rerun-tasks')` 是**错的**（实测报参数名错误）。
- 结论应当写进 `README.md` 与 `MIGRATION_REPORT.md`：**用 `pwsh -File build.ps1 @('...')`，不要用 `-GradleArgs`**。

---

## 2. 交付物 1：`MekExDeorum/tools/validate_resources.py`

新增文件（Python 3，Pillow + stdlib；TOML 用 `tomllib`/`tomli`，两者都不可用时退回定向正则解析器）。
从仓库根运行：

```powershell
python tools/validate_resources.py
```

原始输出（当前仓库状态）：

```
[PASS] 1 JSON parses           35/35 files under src/main/resources
[PASS] 2 textures              30/30 block textures are 16x16 RGBA, 2/2 icons are 256x256 RGBA
[PASS] 3 texture refs resolve  30 distinct repo textures referenced and present; skipped 0 out-of-namespace texture targets and 1 vanilla/external models (minecraft:block/block)
[PASS] 4 lang keys             37 keys identical in en_us/zh_cn, 14 config keys covered, 5 block names matched
[PASS] 5 mods.toml             jar:build/libs/mekexDeorum-1.0.1+mc1.21.1.jar!: modId mekexdeorum, mekanism '[10.7.11,)', exdeorum '[3.12,)'
[PASS] 6 built jar             build/libs/mekexDeorum-1.0.1+mc1.21.1.jar (89590 bytes) contains all 68 required entries, 126 entries total
[PASS] 7 enchantment tags      both item/enchantable tags list all 6 exdeorum meshes with replace=false

7/7 checks passed

RESULT: all resource checks passed
EXIT=0
```

实现要点（与任务书 7 条一一对应）：
1. `src/main/resources` 下全部 `.json` 逐个 `json.loads`（35 个）。
2. 30 张 `textures/block/{id}_{front,back,side,top_outer,top_inner,bottom}.png` 必须是 16×16 RGBA；
   仓库根 `icon.png` 与 `src/main/resources/icon.png` 必须是 256×256 RGBA。
3. 遍历 blockstate 的 `model`、每个模型的 `parent` 链与 `textures` 映射的每个值、
   每个 `elements[*].faces.*.texture`；`#变量` 会在合并后的父链 textures 里递归解析。
   非 `mekexdeorum` 命名空间的引用（此处只有 vanilla 的 `minecraft:block/block` 父模型）记为外部引用并跳过。
4. `en_us`/`zh_cn` 键集合必须完全一致；`MekExDeorumConfig.java` 里
   `.translation("mekexdeorum.configuration.*")` 抽出 14 个键，全部要落在 lang 里；
   `block.mekexdeorum.*` 键与 `MekExDeorumBlocks.java` 里注册的 block 名双向比对。
5. `mods.toml` 优先读 jar 内生成版（其次是源码模板，模板里的 `${...}` 会用 `gradle.properties` 展开），
   校验 `[[mods]].modId == mekexdeorum` 且 `mekanism`/`exdeorum` 的 `versionRange` **逐字**为 `[10.7.11,)` / `[3.12,)`。
6. 校验 jar 存在于**逐字**路径 `build/libs/mekexDeorum-1.0.1+mc1.21.1.jar`，且含 68 项必需条目
   （30 纹理、icon.png、pack.mcmeta、mods.toml、5 blockstate、10 方块模型 + 5 物品模型、2 lang、5 掉落表、5 配方、3 标签文件）；
   顺带报出 `build/libs` 里多余的 jar。
7. 两个 `item/enchantable` 标签必须 `"replace": false` 且列全 6 个 `exdeorum:*_mesh`。

任意一项失败即打印 `RESULT: FAILED` 并 **exit 1**。

### 2.1 反向测试（证明这个校验器不是"永远通过"）

`_plan/t5_scratch/negative_test.py` 把仓库复制到 scratch 目录，逐个注入缺陷后重跑：

```
scenario                                       expect               got      failed checks
baseline (untouched copy)                      0                    exit 0   -
broken recipe JSON -> check 1                  1                    exit 1   1 JSON
32x32 texture -> check 2                       1                    exit 1   2 textures
deleted active model -> check 3                1                    exit 1   3 texture
lang key removed -> check 4                    1                    exit 1   4 lang
jar range [10.7.10,) -> check 5                1                    exit 1   5 mods.toml
jar without pack.mcmeta -> check 6             1                    exit 1   6 built
mesh missing from tag -> check 7               1                    exit 1   7 enchantment
no jar: check 5 still passes from source       5 PASS / 6 FAIL      exit 1   6 built

negative-test harness: OK - every mutation was caught
```

7 类缺陷全部被对应的检查抓到；无 jar 时第 5 项仍能靠展开后的源码模板通过（证明 TOML 回退分支有效）。

---

## 3. §九 验证清单逐条

| # | 清单条目 | 验证手段（命令/实读） | 观察结果 | 结论 |
|---|---|---|---|---|
| 1 | 5 个机器等级均可正常注册，工厂安装可正确升级 | 读 `registry/MekExDeorumBlocks.java`、`registry/MekExDeorumBlockTypes.java`、`tile/TileEntitySieve.java`；`javap -p -cp Mekanism.jar mekanism.common.tile.base.TileEntityMekanism` | 5 个 `BLOCKS.register("universal_sieve"/"basic_sieve_factory"/"advanced_sieve_factory"/"elite_sieve_factory"/"ultimate_sieve_factory")`；tier0 无 `AttributeTier` 但有 `AttributeUpgradeable(() -> BASIC_SIEVE_FACTORY)`；tier1→2→3→4 依次 `AttributeUpgradeable(下一级)`，tier4 只有 `AttributeTier<>(ULTIMATE)`；`TileEntitySieve implements ITierUpgradable` 并实现 `getUpgradeData`/`parseUpgradeData`（javap 确认）；Mekanism 侧 `canBeUpgraded()` 是 `public final`（继承自 `TileEntityMekanism`，由方块 `AttributeUpgradeable` 决定），故无需也不能覆写 | ⚠️ 静态证据完整、编译通过；"注册成功""安装器真的把放置的方块换成上一级"必须在游戏里点一次才能确认 |
| 2 | 每个输入栏的进度条独立运行，互不干扰 | 读 `TileEntitySieve` 193–196/258–263/269–314/686–696；`addContainerTrackers` | `int[] progress / requiredTicks / batchSize`、`boolean[] paused` 全部按栏索引；`onUpdateServer` 对每栏调用 `tickInputSlot(server,index)`，每栏各自判网格/物料/能量/输出；`container.trackArray(progress/requiredTicks/batchSize)` 每栏一个被同步的值；单测 `everySlotOfOneTierHasItsOwnPosition`、`everyProgressBarSitsDirectlyUnderItsInputSlot` 通过 | ✅ |
| 3 | 筛网等级正确映射到并行数 1, 4, 8, 16, 32, 64 | 读 `MeshTiers.java`；`MeshTiersTest.theSixMeshesMapToTheSpecifiedParallelism` | `IDS` 顺序 string→netherite，`PARALLELISM` 逐条 = 1/4/8/16/32/64（与契约表逐字一致），未知/`null` → 0；单测 6 项断言 + 未知项断言；`MeshTiersTest` 5 个测试全 PASS | ✅ |
| 4 | 批量筛矿使用二项分布采样，未循环调用完整筛矿事件 | 源码 grep + **字节码** `javap -c -p` | 源码：`binomialSample` 只出现 3 次（`SieveMath` 定义、`SieveBatch.outputFor` 调用、`SieveBatch` 注释）；`SieveBatch.outputFor` 只在 `TileEntitySieve:352` 被调用一次。字节码：`SieveBatch.class` 里 `invokestatic SieveMath.binomialSample` 只有 1 处，位于 `outputFor` 方法体内；`TileEntitySieve.class` 里 `SieveBatch.outputFor` 只有 1 处，位于 `finishSlot` 内一个遍历 `List<Output>` 的迭代器循环体（`aload/iterator/hasNext/next` + 回跳），循环体内无任何逐物品内层循环；`binomialSample` 在 `TileEntitySieve` 字节码里出现 0 次 | ✅ |
| 5 | 产物数量使用 `long` 计算，可正确拆分为 `Integer.MAX_VALUE` 堆叠 | 读 `SieveMath`、`SieveBatch`、`OversizedOutputSlot`；单测 | `SieveMath.MAX_CHUNK = Integer.MAX_VALUE`；`forEachChunk(long,...)` 按块拆分且总和守恒、余数不丢；`SieveBatch.addClamped/multiplyClamped` 溢出饱和到 `Long.MAX_VALUE` 而非回绕；合计用 `Map<Item,Long>`；`OversizedOutputSlot.SLOT_LIMIT = Integer.MAX_VALUE` 且构造后 `obeyStackLimit = false`（走 Mekanism `SerializerHelper` 超大存档 + varint）；单测 `forEachChunkSplitsExactlyAtTheChunkLimit`（断言 `MAX_CHUNK==Integer.MAX_VALUE`、`chunksOf(Integer.MAX_VALUE)==[MAX_VALUE]`）、`forEachChunkConservesTheTotal`、`oneBatchMergedPerItemNeverWraps`、`multiplyClampedSaturatesInsteadOfWrapping` 全 PASS | ✅ |
| 6 | 输出槽溢出时存入内部缓冲，不丢失物品 | 读 `TileEntitySieve.finishSlot/insertResult/drainOverflow/saveAdditional/loadAdditional/saveOverflow/loadOverflow/getUpgradeData/parseUpgradeData` | `private final Map<Item, Long> overflow`（**long** 计数）；`insertResult` 返回没塞进的余量，逐项 `overflow.merge(..., SieveBatch::addClamped)` 并 `paused[index]=true`；每 tick `drainOverflow()` 先回填再 `unpauseIfBufferEmpty()`；NBT 写 `putLong("amount")`（不是 `putInt`），列表逐项 `item`+`amount`；升级数据带 `overflowItems` + `long[] overflowAmounts`（`SieveUpgradeData` 字段与契约 §3.2 逐字一致） | ⚠️ 静态路径完整、无截断分支；**真实存档往返（存档→重载→物品没少）与升级时不丢物品必须在游戏里跑**，此处只审了代码路径 |
| 7 | 效率附魔减少处理时间，时运附魔增加掉落概率 | 单测 + 读 `SieveMath`/`TileEntitySieve` + 校验标签文件 | `requiredTicks = ceil(base / (1+level*bonus))`、`fortuneChance = min(1, p + bonus*level)`、`fortuneCount = (long)(count*(1+bonus*level))`；等级从**筛网槽物品**读取（`level.registryAccess().registry(Registries.ENCHANTMENT).getHolder(...)` + `stack.getEnchantmentLevel`，无静态字段）；`tools/validate_resources.py` 检查 7 证明 `mining.json`/`mining_loot.json` 都带 6 个筛网且 `replace:false`（没有这两个标签则附魔根本不生效）；相关单测（`requiredTicksIsReducedPerEfficiencyLevel`、`fortuneChanceAddsTheBonusPerLevelAndCapsAtOne`、`fortuneRaisesTheChance` 等）全 PASS | ✅ |
| 8 | 弹出逻辑：每 tick 尝试，成功则归零，200 tick 无成功后降频至 20 tick | 读 `EjectThrottle.java`、`TileEntitySieve.tickEjection/ejectAll/ejectSides`；`EjectThrottleTest` 9 个测试 | `IDLE_THRESHOLD=200`/`COOLDOWN_TICKS=20` 硬编码常量；`tick()` 与契约伪代码逐字等价（cooldown>0 → `cooldown--` 且 return false；`dynamic=false` 恒 true 且清零计数）；`report(true)` 归零 idle、`idle>=200` 置 cooldown=20；`onUpdateServer()` 每 server tick 第一件事就是 `tickEjection()`，`ejectAll()` 一次遍历**全部**输出槽 + 溢出缓冲；`setCanEject(type -> type != TransmissionType.ITEM)` 关掉 Mekanism 自带物品弹出。9 个单测含 `twoHundredFailedTicksStartATwentyTickCooldown`、`anIdleMachineEndsUpAtOneAttemptPerTwentyTicks`、`aSuccessfulEjectResetsTheIdleCounter` 全 PASS | ✅（"真的推进相邻容器"另见第 5 节，不属本条要求） |
| 9 | 配置项翻译键完整，游戏内显示为可读文本 | `python tools/validate_resources.py` 检查 4 | Java 里 14 个 `.translation("mekexdeorum.configuration.*")`（4 个 `[节]` + 10 个项）全部命中；`en_us.json` 与 `zh_cn.json` 键集合**完全一致**，各 37 键（含 10 个 `.tooltip` 与 `mekexdeorum.configuration.title`） | ⚠️ "键完整"已证；"游戏内显示为可读文本"要开配置界面才能看 |
| 10 | 顶部纹理具有向内凹陷的视觉效果，网格纹理使用白色半透明 | `_plan/t5_scratch/geometry_audit.py`（读 10 个模型 JSON + 5 张 `top_inner.png` 像素） | 10/10 模型（5 级 × 非激活/激活）全部满足契约 §6 几何：主体 `[0,0,0]..[16,14,16]` 且**无 `up` 面**；4 条边框环元素在 y=14..16、`up`=`#top_outer`、朝内的一面 = `#top_inner`；凹陷地板 `[2,13,2]..[14,14,14]`、`up`=`#top_inner`、顶面 y=14（比 16 低 2px）；`#top_outer` 只出现在这 4 个 `up` 面上。像素：5 张 `top_inner` 各 256 px 全不透明，实测合成后的白网格不透明度 **0.200（0.197–0.203）**，网格落在 x%4==0（间距 4px），亮度 10..91（边缘阴影 #0A0A0A=10） | ⚠️ 几何与像素数值已实测；"看起来是凹陷的"只能靠渲染。另注：网格的 20% 白是**合成进 RGB 的不透明像素**（alpha 通道全 255、`gen_textures.py` 注释写明 "baked into opaque RGB"），不是字面意义的 0.15–0.25 alpha 通道 |
| 11 | `icon.png` 已生成并放入根目录 | 校验器检查 2 | 仓库根 `icon.png` 与 `src/main/resources/icon.png` 都是 256×256 RGBA；jar 内 `icon.png` 存在且 `mods.toml` 的 `logoFile="icon.png"` | ✅ |
| 12 | 旧版代码已推送至 `legacy-1.0.7` 分支 | `git -c http.proxy=... ls-remote --heads origin` | `95dc181a0081036e28f29c0d109c8a575afdde21  refs/heads/legacy-1.0.7`（远端分支存在且指向 95dc181） | ✅ |
| 13 | `main` 分支已清空并推送新代码 | `git log --oneline -1`、`git rev-parse HEAD`、`git ls-tree -r HEAD --name-only \| Measure-Object`、`git ls-remote`、`git status --porcelain -uall` | `main` = `5afd895 "Clear main branch for remake"`，本地=远端一致；**HEAD 里 0 个文件**（清空已做且已推送）；但全部新文件（T5 动手前 106 个，现 108 个）都处于 **untracked** 状态，没有任何提交包含它们 → "推送新代码"**尚未发生** | ❌ 前半（清空并推送）已完成，后半（推送新代码）未完成；属 T6 调度者步骤，见第 6 节 |
| 14 | 构建通过，产物位于 `MekExDeorum/build/libs/` | `pwsh -File build.ps1 @('clean','build','--no-build-cache','--rerun-tasks')` | `BUILD SUCCESSFUL in 9s`（`7 actionable tasks: 7 executed`），exit 0；产物 `build/libs/mekexDeorum-1.0.1+mc1.21.1.jar`（逐字一致，89590 bytes，126 entries） | ✅ |

统计：**✅ 9 条 / ⚠️ 4 条 / ❌ 1 条**。

---

## 4. 其他独立核对（任务书点名项）

### 4.1 构建与单元测试
```powershell
pwsh -File build.ps1 @('clean','build','--no-build-cache','--rerun-tasks')
```
→ `BUILD SUCCESSFUL in 9s`；`createMinecraftArtifacts` 全 `REUSE`（缓存命中），`compileJava/processResources/jar/test` 真实执行。

`build/test-results/test/*.xml`（用 XML 属性统计，非日志 grep）：

```
TEST-com.mekexdeorum.EjectDirectionTest.xml tests=6 failures=0 errors=0 skipped=0
TEST-com.mekexdeorum.EjectThrottleTest.xml tests=9 failures=0 errors=0 skipped=0
TEST-com.mekexdeorum.MeshTiersTest.xml tests=5 failures=0 errors=0 skipped=0
TEST-com.mekexdeorum.SieveBatchTest.xml tests=14 failures=0 errors=0 skipped=0
TEST-com.mekexdeorum.SieveLayoutTest.xml tests=8 failures=0 errors=0 skipped=0
TEST-com.mekexdeorum.SieveMathTest.xml tests=12 failures=0 errors=0 skipped=0
---TOTALS--- tests=54 failures=0 errors=0 skipped=0
```
与预期 **54（12/14/9/8/6/5）逐项一致** —— 这是本轮当时的原始记录，予以保留。后续变化：第 3 轮把 `SieveLayoutTest` 扩到 18；第 5 轮弹出逻辑改用 Mekanism 侧配置后，`EjectDirectionTest` 的 6 个用例随旧规则一并删除，故当前套件为 **58**（12/14/9/18/5）。

### 4.2 反模式扫描（源码 grep + jar 内字节码）
| 检查 | 源码 | 字节码（`javap -c -p` / 常量池裸扫 25 个 class） |
|---|---|---|
| `RecipeUtil` / `RecipeCaches` | 仅出现在 `ExDeorumCompat.java:29-31` 的**注释**里 | 0 命中；`ExDeorumCompat` 实际只引用 `thedarkcolour.exdeorum.{recipe.sieve.SieveRecipe, registry.ERecipeTypes, tag.EItemTags, loot.SummationGenerator}` |
| `hurtAndBreak` | 仅 `TileEntitySieve.java:617` 注释 | 0 命中（`damageMesh()` 用 `isDamageableItem()` 守卫 + `setDamageValue`） |
| 循环调用完整筛矿事件 | `SieveBatch.outputFor` 1 处调用点、`binomialSample` 1 处调用点 | `SieveBatch.outputFor` 1 个调用点（在 `finishSlot` 的输出迭代循环体内）、`binomialSample` 1 个调用点（在 `outputFor` 内）、`TileEntitySieve` 里 `binomialSample` 0 命中 |
| Mixin | 全仓库无 `*.mixins.json`、无 `mixin` 引用 | 25 个 class 常量池里 `mixin`/`Mixin` 均 0 命中 |

### 4.3 筛网表
`MeshTiers.PARALLELISM`（`LinkedHashMap`，`Collections.unmodifiableMap`）：
`string=1, flint=4, iron=8, golden=16, diamond=32, netherite=64`；未知/`null` → 0。
`MeshTiersTest.theSixMeshesMapToTheSpecifiedParallelism` 对这 6 个值逐个 `assertEquals`，与 `_legacy` 的 1/2/4/16/32/64 不同——按规格表实现，正确。

### 4.4 等级链与 Mixin
见第 3 节第 1 行；另：`javap` 确认 `mekanism.common.tile.base.TileEntityMekanism` 里 `private boolean canBeUpgraded; public final boolean canBeUpgraded();`
→ 作者的"`canBeUpgraded()` 在基类里是 final"说法**成立**，`TileEntitySieve` 只实现数据半边是正确做法。
`ITierUpgradable` 接口只有 `canBeUpgraded()` + `default getUpgradeData(...)`，`parseUpgradeData` 来自 `TileEntityMekanism`。

### 4.5 顶部凹陷
见第 3 节第 10 行，10/10 模型通过（含 `_active` 变体）。

### 4.6 每栏独立与溢出缓冲（点名要引用的行）
- 每栏独立：`TileEntitySieve.java:115-120`
  ```java
  private int[] progress;
  private int[] requiredTicks;
  private int[] batchSize;
  private boolean[] paused;
  ```
  `193-196` 按 `processes` 分配；`258-263` 逐栏 `tickInputSlot(server, index)`；`693-695` `container.trackArray(progress/requiredTicks/batchSize)`。
- 溢出缓冲：`126` `private final Map<Item, Long> overflow = new LinkedHashMap<>();`；
  `364-371` 落不下就 `overflow.merge(entry.getKey(), leftOver, SieveBatch::addClamped); paused[index] = true;`；
  `737` `tag.put(NBT_OVERFLOW, saveOverflow(provider));`，`764` `element.putLong(NBT_OVERFLOW_AMOUNT, amount);`（**long**），
  `750` `loadOverflow(provider, tag.getList(NBT_OVERFLOW, Tag.TAG_COMPOUND));`；
  `826-829` 升级数据带 `overflowItems()`+`overflowAmounts()`，`846-853` 反向恢复。

### 4.7 仓库卫生
- `Select-String -Pattern 'TEMPORARY|compile stub|\bSTUB\b|TODO|FIXME|XXX'` 覆盖 `src/`+`tools/` 全部文件：**0 命中**。
- `__pycache__` / `*.pyc`：**0 个**。
- 仓库内（除 `.git`/`.gradle`/`build`）文件清单只有：`.gitignore`、`build.gradle`、`build.ps1`、`gradle.properties`、`icon.png`、`LICENSE`、`README.md`、`settings.gradle`、`src/**`、`tools/{gen_icon.py,gen_textures.py,validate_resources.py}`。
  **没有 `_plan/` 或预览图泄漏进仓库**（`_plan/` 在仓库外，预览图在 `_plan/previews/`）。
- `git check-ignore -v`：`build/**`（`.gitignore:4`）、`.gradle/**`（`:2`）、`run/**`（`:9`）被忽略；
  `src/main/resources/assets/mekexdeorum/textures/block/universal_sieve_side.png`、`icon.png`、`src/main/resources/icon.png`、`tools/validate_resources.py` **均未被忽略**。`.gitignore` 没有误伤任何需要的 PNG/源码。
- `git status --porcelain -uall` = **108 行** = 96 个 `src/**`（90 main + 6 test）+ 3 个 `tools/*.py` + 9 个仓库根文件（含本报告 `VERIFICATION.md` 与新增的 `tools/validate_resources.py`）。
  T5 动手前是 106 行（10 个根/tools 文件 + 96 个 src）——两个新增文件正是 T5 自己的交付物。区别对待：全部 108 个都是 untracked（main 是空分支，属预期）；无 `build/`、`.gradle/`、`run/` 条目泄漏，也没有 `_plan/` 或预览图泄漏。

---

## 5. NOT VERIFIABLE HERE（必须开游戏才能确认）

| 项 | 为什么无法在这里验 | 已替代做的静态审查 |
|---|---|---|
| 5 级方块/方块实体/容器的**实际注册成功** | 需要 `runServer`/`runClient` 启动注册表；本次验收深度是用户拍板的"编译 + 资源校验 + 纯逻辑单测"（`_plan/TASKS.md` 已敲定决策 5） | 注册代码路径、编译产物、mods.toml 元数据 |
| 等级安装器**真的把放好的方块换级** | 需要手里拿安装器右键 | 升级链属性 + `ITierUpgradable` 实现 + `SieveUpgradeData` 字段 + Mekanism final `canBeUpgraded()` |
| GUI **真的能打开**、每栏箭头独立动 | 需要客户端渲染 | `GuiSieve` 按 `SieveLayout` 逐栏建 `GuiProgress`；`dynamicSlots = true`；`withGui` 接线；`SieveLayout` 单测 8 项 |
| 产物**真的推进相邻容器** | 需要两个方块实体 + 邻箱 | `ejectAll()` 遍历所有输出槽 + 溢出缓冲 + `Capabilities.ItemHandler.BLOCK` 查询 + 侧向策略 |
| NBT **真实存档往返**（退出→进服物品不变） | 需要存档 | `saveAdditional`/`loadAdditional` 逐字段读写（含 `putLong` 数量与数组重分配） |
| 顶部凹陷的**渲染观感** | 需要方块渲染 | 模型几何（无 `up` 主面 + y=14 地板）+ 像素数值（合成白网格 0.200） |
| 游戏内**配置界面文本**可读 | 需要客户端 | 键集合一致且覆盖 14 个 `.translation(...)` |

---

## 6. CONTRADICTIONS / DEFECTS FOUND

1. **`build.ps1` 自身注释与 `README.md` 记的调用形式是错的**（实测失败）。
   - `MekExDeorum/build.ps1:9-11` 注释示例：`pwsh -File build.ps1 -GradleArgs @('test','--rerun-tasks')`
   - `MekExDeorum/README.md:94`：`pwsh -File build.ps1 -GradleArgs @('test','--rerun-tasks')`
   - 实测：`build.ps1: 找不到与参数名称 '-rerun-tasks' 匹配的参数。` exit 1（`_plan/t5_scratch/formB2.txt`）。
   - 正确写法：`pwsh -File build.ps1 @('test','--rerun-tasks')`。**必须改这两处**，否则 README 的构建说明照抄即失败。
   - 附：第一个作者报告"`-GradleArgs @('clean','build')` 报位置参数错误"是对的（实测 `找不到接受自变量 'build' 的位置参数`），第二个作者记的形式是错的——两人结论应以实测为准。

2. **`README.md:82` 的测试数量过时**：写的是 `test      # 40 个单元测试`，实际 `build/test-results/test/*.xml` 合计 **54** 个（6+9+5+14+8+12）。任务书里也有"40 unit tests"的说法，实测 54。

3. **§九 第 13 条未满足：新代码尚未提交/推送到 `main`**。`HEAD = 5afd895`（"Clear main branch for remake"），`git ls-tree -r HEAD` = **0 个文件**，106 个新文件全部 untracked。清空+推送已完成，推送新代码未做。属 T6（调度者）剩余步骤，不是 T1–T4 的缺陷，但 §九 现在不能勾。同时 `MIGRATION_REPORT.md` 在仓库中**不存在**（T6 交付物）。

4. **`tools/gen_gui.py` 不存在**（`SPEC_FREEZE.md` §2 把 `tools/gen_gui.py` 列为 T4 的独占文件，`§6` 也提到 `textures/gui/**`）。实读 `client/gui/GuiSieve.java:22-23` 与类注释：窗口背景直接用 Mekanism 自带的九宫格窗口纹理，"no custom GUI texture is needed"；仓库里也确实没有 `textures/gui/`，且任务书交付清单不要求 GUI 纹理。**判定：文档层面的过时条目，不是功能缺失**——但 `SPEC_FREEZE` §2 的文件名清单与最终产物不一致，迁移报告应记一笔。

5. **`top_inner` 的"白色半透明"不是 alpha 通道**（`gen_textures.py:200-215`：`interior = base*(1-alpha) + WHITE*alpha`，"Return an opaque uint8 (size,size,3) array"、"baked into opaque RGB"）。5 张图 alpha 通道全为 255，实测合成不透明度 0.200（0.197–0.203，落在规格 0.15–0.25 内）。
   对"solid"渲染的方块面而言这是**正确**取舍（alpha 通道在 solid 渲染里会被丢弃/混色出错），但规格 §6.2 字面要求的是"alpha 0.15–0.25"，因此这是一处**实现方式与规格字面的偏差**，需要写进 `MIGRATION_REPORT.md` 由用户确认。

6. **`SPEC_FREEZE.md` §8 第 3 条与实际实现的校验范围不完全对齐**（不是缺陷，是补充）：§8 只要求"30 张纹理存在且为 16×16 PNG"，本次交付的校验器另外强制了 **RGBA** 与"每个纹理引用都在磁盘上"（含 parent 链），比契约更严。

> 未发现的问题：无 `RecipeUtil`/`RecipeCaches` 引用、无 `hurtAndBreak`、无逐物品循环调用完整筛矿事件、无 Mixin、无 `__pycache__`/临时 stub 文件、`.gitignore` 无误伤、jar 路径与名称逐字正确、mods.toml 依赖范围逐字正确、54 个测试全绿。

---

## 7. 复现命令汇总

```powershell
# 1) 真实构建（必须用剩余参数形式）
cd C:\Users\Mechrevo\Desktop\工作\Minecraft\MekExDeorum
pwsh -File build.ps1 @('clean','build','--no-build-cache','--rerun-tasks')

# 2) 资源校验
python tools/validate_resources.py

# 3) 单测计数
Select-String -Path build/test-results/test/*.xml -Pattern 'tests="\d+"'

# 4) 字节码反模式扫描（JAVA_HOME 未全局设置，用绝对路径）
$javap = "C:\Program Files\Java\dragonwell-21.0.10.0.10+7-GA\bin\javap.exe"
& $javap -c -p -cp <解包后的 class 目录> com.mekexdeorum.SieveBatch
& $javap -c -p -cp <解包后的 class 目录> com.mekexdeorum.tile.TileEntitySieve

# 5) 逆向测试（证明校验器会失败）
python C:\Users\Mechrevo\Desktop\工作\Minecraft\_plan\t5_scratch\negative_test.py
python C:\Users\Mechrevo\Desktop\工作\Minecraft\_plan\t5_scratch\geometry_audit.py
```
