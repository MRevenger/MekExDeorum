# MekExDeorum

把 [Ex Deorum](https://www.curseforge.com/minecraft/mc-mods/ex-deorum) 的筛网机制封装成 [Mekanism](https://www.curseforge.com/minecraft/mc-mods/mekanism) 风格的电力机器，并保留 Mekanism 的工厂升级链。

- Minecraft **1.21.1** / NeoForge **21.1.250**
- 依赖：Mekanism `[10.7.11,)`（必需）、Ex Deorum `[3.12,)`（必需）
- 许可：MIT

## 机器等级

用 Mekanism 的等级安装器（Tier Installer）逐级升级，和 Mekanism 自己的工厂一样：

| 等级 | 机器 | 输入栏 | 能量容量 | 升级物品 |
|---|---|---|---|---|
| 0 | 通用筛矿机 `universal_sieve` | 1 | 20 000 FE | 基础等级安装器 |
| 1 | 基础筛矿工厂 `basic_sieve_factory` | 3 | 40 000 FE | 高级等级安装器 |
| 2 | 高级筛矿工厂 `advanced_sieve_factory` | 5 | 80 000 FE | 精英等级安装器 |
| 3 | 精英筛矿工厂 `elite_sieve_factory` | 7 | 160 000 FE | 终极等级安装器 |
| 4 | 终极筛矿工厂 `ultimate_sieve_factory` | 9 | 320 000 FE | — |

升级时会带走筛网、输入、输出、溢出缓冲、能量与每栏进度。

**机器等级只影响输入栏数量和能量总量，不影响并行数。** 并行数完全由筛网决定：

| 筛网 | 并行数 N |
|---|---|
| 线 `exdeorum:string_mesh` | 1 |
| 燧石 `exdeorum:flint_mesh` | 4 |
| 铁 `exdeorum:iron_mesh` | 8 |
| 金 `exdeorum:golden_mesh` | 16 |
| 钻石 `exdeorum:diamond_mesh` | 32 |
| 下界合金 `exdeorum:netherite_mesh` | 64 |

## 工作方式

- **每个输入栏独立运行**，各自有一条进度条；某一栏等料不会拖住其它栏。
- 某一栏进度条走满时：读筛网 → 得到并行数 `N` → 消耗该栏 `N` 个输入物品 → 执行**一次**批量筛矿。
- 批量筛矿用**二项分布合并**：对每个输出做 `Binomial(n, p)` 一次采样，而不是把完整的筛矿事件循环 N 遍。单次批量只有「输出数 × n」次随机数比较，没有配方查找、没有物品堆创建。
- 产量用 `long` 累加，单个输出槽上限 21 亿（`Integer.MAX_VALUE`）；装不下时进入内部 `long` 溢出缓冲并暂停该栏，绝不丢物品。
- **每游戏刻**尝试把全部输出槽与溢出缓冲推入相邻容器；连续 200 tick 推不出去才降频为每 20 tick 一次。
- 附着在**筛网**上的效率附魔降低处理时间，时运附魔提高掉落概率。

## 配置

配置文件：`config/mekexdeorum-common.toml`

```toml
[machine]
outputSlots = 16            # 输出槽数量
meshDamage = false          # 筛网是否消耗耐久

[enchantment]
efficiencySpeedBonus = 0.15 # 效率每级减少的处理时间比例
fortuneChanceBonus = 0.05   # 时运每级增加的掉落概率
fortuneAmountBonus = 0.0    # 时运每级增加的掉落数量比例（默认关闭）

[eject]
dynamicEject = true                    # 动态弹出节流
ejectDirection = "all_sides_except_front"

[energy]
baseEnergyCapacity = 20000      # 通用筛矿机的能量容量
energyCapacityMultiplier = 2.0  # 每级的容量倍率
energyPerItem = 200             # 每物品能量消耗
```

### 关于 `meshDamage`

Ex Deorum 的六种筛网**本身没有耐久度**。因此打开 `meshDamage` 后：

- 可损坏的筛网（第三方新增、带耐久的筛网）每批量消耗 1 点耐久；
- Ex Deorum 原生的六种筛网没有可消耗的耐久，**实际不会被消耗**。

这是刻意的：对不可损坏的物品扣耐久会直接销毁它。

## 构建

需要 JDK 21 与 Gradle 8.14.2（不附带 wrapper）。

```powershell
pwsh -File build.ps1 build     # 产物：build/libs/mekexDeorum-1.0.0+mc1.21.1.jar
pwsh -File build.ps1 test      # 64 个单元测试，不需要 Minecraft 类路径
pwsh -File build.ps1 @('clean')            # clean 必须单独一次调用
pwsh -File build.ps1 @('build')
```

`build.ps1` 负责三件环境相关的事：

1. 指定 JDK 21（`JAVA_HOME`）；
2. 从系统设置里读取 HTTP 代理并传给构建 JVM（Java 不认 WinINET 系统代理，直连 `maven.neoforged.net` 会超时）；
3. 把构建 JVM 的字符集设为系统的 ANSI 代码页——本仓库路径含非 ASCII 字符时，Gradle 写给测试 JVM 的 `@argfile` 会被 JDK 启动器按 ANSI 解码，用 UTF-8 会导致测试类全部 `ClassNotFoundException`。

因此 **请用 `build.ps1` 构建，不要直接调 `gradle`**。多个参数要作为位置参数数组传给脚本（
`-GradleArgs` 只接得住一个值，两个以上会报「找不到接受该自变量的位置参数」）：

```powershell
pwsh -File build.ps1 @('test','--rerun-tasks')
```

`clean` **不要**和别的任务写在同一次调用里：它与 `processResources` 之间没有顺序约束，Gradle 可能并发执行，于是 `clean` 会在 `processResources` 计算哈希时删掉 `pack.mcmeta`（报 `Failed to create MD5 hash for file … as it does not exist`）。这是竞态，所以时好时坏，请分行调用。

## 纹理

`tools/gen_textures.py`（35 张 16×16 方块纹理：5 台机器 × 7 个面，含正面顶端横条）与 `tools/gen_icon.py`（256×256 图标）用 Pillow 逐像素程序化生成，可重复执行且结果完全一致：

```powershell
python tools/gen_textures.py
python tools/gen_icon.py
```

顶部凹陷面（`top_inner`）的参数可调：`--grid-spacing`（默认 4，可选 2/3/4）与 `--grid-alpha`（默认 0.20，限定 0.15–0.25）。

## 许可

MIT。Ex Deorum 与 Mekanism 各自遵循其自身许可。
