# 第五轮 + 第六轮：弹出逻辑 / 平分按钮修复报告（1.0.1）

> 第六轮（用户报的两个问题）追加在 §7–§9：**A. 未开启主动弹出也照推**（缺陷）、
> **B. 输入栏自动平分按钮**（功能）。§1–§6 是第五轮的原文，未改动；§6「运行时验证」占位仍然留给
> 之后的运行时工作流，本文不含任何运行时证据。

范围：只改「产物推到哪些面」这一件事。筛分/并行路径、`SieveBatch`、`SieveMath`、输出槽容量
（`OversizedOutputSlot`）、溢出缓冲语义、纹理、模型、GUI 布局**一律未动**。
版本：`1.0.0` → `1.0.1`，产物 `build/libs/mekexDeorum-1.0.1+mc1.21.1.jar`。

## 1. 旧实现（本轮被替换的部分）

`TileEntitySieve.ejectSides()` 的侧面来源是一条**自定义规则**，与 Mekanism 的侧面配置无关：

```java
// 旧代码（已删除）
private List<Direction> ejectSides() {
    Direction facing = getDirection();
    EjectDirection policy = MekExDeorumConfig.ejectDirection();   // all_sides / all_sides_except_front / back
    if (policy == EjectDirection.BACK) {
        return List.of(facing.getOpposite());
    }
    ...
    if (policy == EjectDirection.ALL_SIDES_EXCEPT_FRONT && side == facing) { continue; }
    ...
}
```

它把机器朝向（`getDirection()`）当成「正面」来排除，因此：

- 产物会推到**玩家没有配置过**的面（包括被配成 `输入` 的面，那等于把产物塞回去）；
- 玩家在机器 GUI 的侧配置页签里设成 `输出` 的面**没有任何作用**——
  `mekexDeorum-common.toml` 的 `[eject] ejectDirection` 才是唯一开关；
- 默认值 `all_sides_except_front` 让 6 个面里的 5 个无条件可推，与 Mekanism 机器「默认全 `无`、
  谁被设成 `输出` 才推」的语义相反。

## 2. 根因

弹出面**不该由我们算**，它本来就存在：`TileComponentEjector` 通过
`setOutputData(configComponent, TransmissionType.ITEM)` 拿到了 ITEM 的 `ConfigInfo`，
而 `ConfigInfo` 的 `Map<RelativeSide, DataType> sideConfig` 就是 GUI 侧配置页签所编辑的东西
（`OUTPUT / INPUT / INPUT_OUTPUT / EXTRA / ENERGY / NONE`）。也就是说：侧面配置、GUI 页签、
NBT 持久化都在，**只有取侧面的那一步没去读它们**。

Mekanism 10.7.11.76 的权威规则（`_research_mek/src10711/mekanism/common/tile/component/TileComponentEjector.java:332-345`）：

```java
private Set<Direction> getSidesForData(ConfigInfo info, @NotNull Direction facing, @NotNull DataType dataType) {
    Set<Direction> directions = null;
    for (Map.Entry<RelativeSide, DataType> entry : info.getSideConfig()) {
        if (entry.getValue() == dataType) { ... directions.add(entry.getKey().getDirection(facing)); }
    }
    return directions == null ? Collections.emptySet() : directions;
}
```

要点两条：**`DataType` 精确相等**（不是 `canOutput()`，`INPUT_OUTPUT` 也不算），
以及 **`RelativeSide#getDirection(facing)`** 把相对面映射成世界方向。该方法是 `private`，
所以本地照抄这段语义。

## 3. 改动

### 3.1 `TileEntitySieve.ejectSides()`：改用侧面配置的 `OUTPUT` 面

```java
private Set<Direction> ejectSides() {
    ConfigInfo info = configComponent.getConfig(TransmissionType.ITEM);
    if (info == null) {
        return Set.of();
    }
    Direction facing = getDirection();
    Set<Direction> sides = EnumSet.noneOf(Direction.class);
    for (Map.Entry<RelativeSide, DataType> entry : info.getSideConfig()) {
        if (entry.getValue() == DataType.OUTPUT) {
            sides.add(entry.getKey().getDirection(facing));
        }
    }
    return sides;
}
```

- 侧面只来自 ITEM 的 `ConfigInfo`，`DataType` 用 `==` 精确比较：`INPUT`、`INPUT_OUTPUT`、
  `EXTRA`、`ENERGY`、`NONE` 都不推；
- 相对面经 `RelativeSide#getDirection(getDirection())` 映射，**没有硬编码任何方向**，
  机器转个朝向结果会自动跟着转；
- 返回类型由 `List<Direction>` 改为 `Set<Direction>`（`EnumSet`），调用方 `ejectAll()`
  只做 `isEmpty()` 与遍历，语义不变。
- 新增 import：`java.util.EnumSet`、`java.util.Set`、`mekanism.api.RelativeSide`；删除
  `com.mekexdeorum.EjectDirection`。

### 3.2 删除旧规则与它的所有旋钮（不留死代码）

| 删除项 | 说明 |
|---|---|
| `src/main/java/com/mekexdeorum/EjectDirection.java` | 枚举整体删除 |
| `MekExDeorumConfig` 的 `EJECT_DIRECTION` / `EJECT_DIRECTION_DEFAULT` / `ejectDirection()` 及 builder 条目 | `[eject]` 段只剩 `dynamicEject` |
| `en_us.json` / `zh_cn.json` 的 `...eject.ejectDirection` 与 `...tooltip` 两条 | 中英各删 2 条，键集仍 35 = 35 完全对齐 |
| `src/test/java/com/mekexdeorum/EjectDirectionTest.java` | 6 个测试整体删除（64 → 58） |
| `build.gradle` 依赖注释里的 `EjectDirection` | 仅注释 |
| `README.md` 配置块、`MIGRATION_REPORT.md` / `FIX_REPORT.md` / `VERIFICATION.md` 的相关行 | 见下 |

`[eject] dynamicEject` 及其两条翻译键**原样保留**；`tools/validate_resources.py` 里
`EXPECTED_TRANSLATION_KEYS` 由 14 改为 13（删掉的那一条配置项）。

文档侧只做「去掉旧规则、换成新产物名」，不重写历史叙述：README 的「工作方式」新增一条说明
「只有配成 `输出` 的面才推」并删掉 `ejectDirection` 配置行、`test` 注释的测试数改 58；
`VERIFICATION.md`/`MIGRATION_REPORT.md`/`FIX_REPORT.md` 中提及被删测试类的那几行改成
「第 5 轮删除了 6 个用例」的说法并把合计改为 58 / 48；四份文档里的产物名统一为
`mekexDeorum-1.0.1+mc1.21.1.jar`。`_plan/**` 属历史材料，未动。

### 3.3 为什么是「配置」而不是「替换」`TileComponentEjector`

构造函数**一行没改**，仍然是：

```java
ejectorComponent = new TileComponentEjector(this);
ejectorComponent.setOutputData(configComponent, TransmissionType.ITEM);
ejectorComponent.setCanEject(type -> type != TransmissionType.ITEM);   // 组件自己的物品弹出仍然关闭
```

理由：

1. 组件是**侧面配置、GUI 页签、NBT 持久化**的所有者。扔掉它等于自己重写这三样，
   而且 GUI 页签会跟着失效（玩家就没有地方设 `输出` 面了）。
2. 组件自带的物品弹出已经用 `setCanEject` 关掉，`tickServer()` 对 `TransmissionType.ITEM`
   直接被 `isEjecting(...)` 短路 → **不存在双重弹出**，本轮也不需要重新接线。
3. 组件真正的短板是它的物品节流（每次尝试后 `tickDelay = TICKS_PER_HALF_SECOND`，
   即 10 tick 才推一次），这个短板已经由我们自己的 `tickEjection()` 绕开；
   本轮只需要它**数据面**（`ConfigInfo`），不需要它**执行面**。

所以：`TileComponentEjector` 保留并继续持有配置，弹出执行仍在我们自己的路径里，
侧面则统一从它持有的 `ConfigInfo` 读取。

## 4. 保留不变的行为（本轮明确不碰）

- **每游戏刻全量一遍**：`tickEjection()` → `ejectAll()`，一次遍历全部 `outputSlots`
  （每个 `pushSlot()` 会把该槽的整堆逐个邻居槽位塞到邻居拒绝为止）加溢出缓冲
  （`pushOverflow()`），不是 `tickDelay` 驱动的分批；`dynamicEject` 只决定「这一 tick 要不要试」。
- **动态节流**：`EjectThrottle` 的 200 tick 全灭 → 20 tick 冷却，成功即清零；数值仍写死不可配。
- 溢出缓冲语义、`OversizedOutputSlot` 容量、暂停/恢复逻辑、筛分与并行路径：零改动。
- 纹理、模型、GUI 布局：零改动。

## 5. 静态验证（本轮实跑）

```
pwsh -File build.ps1 @('build')              -> BUILD SUCCESSFUL in 5s, 6 actionable tasks: 2 executed, 4 up-to-date
build/libs/mekexDeorum-1.0.1+mc1.21.1.jar    -> 133 entries, 26 个 class
                                               jar 内 META-INF/neoforge.mods.toml: version = "1.0.1"
                                               jar 内 EjectDirection* 条目数 = 0
                                               （字节数随并行 workstream 对 DevScreenshotHarness 的在途改动而变：本轮观测 111495 → 120533）
build/test-results/test/*.xml                -> tests=58 failures=0 errors=0 skipped=0
   EjectThrottleTest 9 / MeshTiersTest 5 / SieveBatchTest 14 / SieveLayoutTest 18 / SieveMathTest 12
python tools/validate_resources.py           -> 7/7 checks passed, exit 0
   4 lang keys: 35 keys identical in en_us/zh_cn, 13 config keys covered, 5 block names matched
   6 built jar: build/libs/mekexDeorum-1.0.1+mc1.21.1.jar, 73 required entries, 133 total
```

`build/libs` 里旧的 `mekexDeorum-1.0.0+mc1.21.1.jar` 已删除（否则校验器第 6 项会报多余 jar）。

> 说明：`MIGRATION_REPORT.md` / `FIX_REPORT.md` / `VERIFICATION.md` 里粘贴的历史日志本轮**只改了产物名**，
> 其中的字节数、条目数与测试数仍是当初 1.0.0 构建时的记录（1.0.1 的实测值是 133 entries / 58 tests），
> 旧测试类被删的那几行已用「第 5 轮删除 6 个用例」的注记替代，未改动其它数字。

## 6. 运行时验证

<!-- RUNTIME EVIDENCE -->

本节记录**真机**（真实客户端 + 集成服务器，`build.ps1 @('runClient')`，真实窗口）的实测结果。共 3 次启动；第 3 次跑在加上「主动弹出」开关之后的构建上，因此开关的开/关两种状态都是在修复后代码上测的。完整原始日志见 `_plan/EJECT_RUNTIME_EVIDENCE.md`（305 行）与 `_plan/g2_scratch/`。

| # | 检查 | 实测（修复后构建，含 tick 号） | 结论 |
|---|---|---|---|
| 1 | 只有一个面标为 OUTPUT | 输出槽停 120 个（64+32+16+8）→ `gt=21728 south=120 outputs=empty`；正面(INPUT)、东(INPUT_OUTPUT)、西(EXTRA)、上(ENERGY)、下(NONE) 全 0 | ✅ |
| 2 | 该面改为 INPUT | `gt=21730 → gt=21742`，六个目标容器全 0，槽内 96 个原样不动 | ✅ |
| 3 | 三个面标为 OUTPUT | 停 4000 个 → `up=1728 south=1728 west=576`，其余 0，合计 4032 = 4000+32，**无丢失** | ✅ |
| 4 | 单 tick 全量 | `gt=21758 south=0 outputs=[cobblestone x3000]` → `gt=21759 south=1728 outputs=[cobblestone x1272]`：**恰好 1 个 game tick 送出 1728 个**，随后 61 tick 无变化（桶已满） | ✅ |
| 5 | 动态节流 | idleTicks 64→207；`gt=21959 idleTicks=200` 首次出现 cooldown=20，此后约每 21 tick 尝试（20→16→6→17→…→0→11）；重放木桶 `gt=22124 (idleTicks=207 cooldown=2)` → `gt=22127` 收到 64 个（3 tick） | ✅ |
| 6 | 侧配置页 | 经 `openGui` + 真实 `GuiSideConfigurationTab#onClick` 打开，截图 `docs/fix-verification/side_config_ejection_probe.png`，显示 Mekanism "Items Config" 六面方格与 `Eject: on` | ✅ |
| 7 | 回归 | 沙 16→13、能量 20000→19400、产物入槽、溢出回填并解除暂停、效率 V `requiredTicks=58` vs 普通 `100` | ✅ |
| 8 | **主动弹出开关（本轮缺陷）** | 开关强制关闭 + 该面 OUTPUT + 槽内 64 个 → 20 tick 后 `south=0 outputs=[cobblestone x64] isEjecting=false`（**完全未弹出**）；随后 `setEjecting(true)` → 12 tick 后 `south=64 outputs=empty isEjecting=true` | ✅ |
| 9 | 平分按钮 | 见 §10 | — |

**一处如实记录、未复现的异常**：仅第 1 次启动时，探针机器继承了上一轮存档里已存在的方块实体，在只有 `BACK=south` 为 OUTPUT 的情况下有 64 个物品被推到 **north** 的桶（单 tick）。第 2、3 次启动用「从空气新放置」的方块实体各跑约 300 tick 均未复现，故记为**未复现异常**而非结论；若今后在全新机器上复现，应作为真实缺陷追查（很可能是旧存档中的侧配置与读取到的配置不一致）。

**运行时未能覆盖**：节流内部被跳过的 tick 是用倒计时（20→0）与约 21 tick 的实际尝试间隔间接证明的，未逐 tick 记录。

（平分开关的真机验证见文末 §10。）

## 7. 第六轮 A：机器没开启「主动弹出」也会推产物（缺陷修复）

### 7.1 现象与根因

玩家报「机器没有开启主动弹出也会把产物弹出」。第五轮把**推哪些面**接到了侧面配置的 `OUTPUT`，
但**要不要推**从来没读过开关：`tickEjection()` 只看「有没有 `OUTPUT` 面」和节流器，于是只要有一个
面是 `输出`，产物就一直往外走，和玩家是否打开主动弹出无关。

开关本来就存在，而且默认是**关**的。Mekanism 10.7.11.76：

```java
// mekanism/common/tile/component/config/ConfigInfo.java:37-39
public ConfigInfo() {
    canEject = true;
    ejecting = false;        // <- 默认不弹出
    ...
}
// mekanism/common/tile/component/TileComponentEjector.java:137-139
public boolean isEjecting(ConfigInfo info, TransmissionType type) {
    return info.isEjecting() && (canEject == null || canEject.test(type));
}
```

### 7.2 改动：`TileEntitySieve.tickEjection()` 先读 `ConfigInfo#isEjecting()`

```java
private void tickEjection() {
    if (getLevel() == null || getLevel().isClientSide()) {
        return;
    }
    ConfigInfo itemConfig = configComponent.getConfig(TransmissionType.ITEM);
    if (itemConfig == null || !itemConfig.isEjecting()) {
        // Auto-eject is off: no push, and the throttle's state stays exactly as it was.
        return;
    }
    ejectThrottle.setDynamic(MekExDeorumConfig.dynamicEject());
    if (!ejectThrottle.tick()) {
        return;
    }
    ejectThrottle.report(ejectAll());
}
```

- 开关是 ITEM 的 `ConfigInfo#isEjecting()`，**在任何一次推送之前**判断，判断失败直接返回；
- 关掉时**不碰节流器**：早退发生在 `setDynamic()` / `tick()` / `report()` 之前，`idleTicks`、
  `cooldown` 原样保留，重新打开时行为与关闭前一致；
- 玩家侧的开关由 Mekanism 自己维护：`PacketEjectConfiguration.handle()` 里
  `info.setEjecting(!info.isEjecting())` 并 `configComponent.tile.markForSave()`，所以开关会随方块
  一起存档，本轮**不需要**新增任何网络包、配置项或 NBT 字段。

### 7.3 为什么**不能**用 `TileComponentEjector#isEjecting(...)`（重要）

构造函数里已经有一行：

```java
ejectorComponent.setCanEject(type -> type != TransmissionType.ITEM);
```

它的用途是**关掉 Mekanism 组件自己的物品弹出**（避免和我们的 `tickEjection()` 双重弹出）。于是
`ejectorComponent.isEjecting(info, TransmissionType.ITEM)` 恒为 `false`——用它做闸门，机器就永久不再
弹出，连玩家把主动弹出打开也不动。所以：

| 名字 | 归属 | 本机取值 | 语义 |
|---|---|---|---|
| `ConfigInfo#isEjecting()` | 玩家的开关 | 默认 `false`，由 GUI 翻转 | **本轮的闸门** |
| `ConfigInfo#canEject()` | 该传输类型是否支持弹出 | 默认 `true`（未改） | 只决定 GUI 那颗按钮是否可点 |
| `TileComponentEjector#canEject` 谓词 | 组件自己的执行面 | ITEM 为 `false` | 防双重弹出，**不是**玩家开关 |

`setCanEject(...)` 一行未改。

两层性质完全不同，别混：

- **`ConfigInfo#isEjecting()` 是玩家开关**，而且是**持久化**的：Mekanism 把它写进方块物品的数据组件
  `MekanismDataComponents.SIDE_CONFIG`（`AttachedSideConfig.LightConfigInfo` 的第二个字段
  `isEjecting`，codec 名为 `SerializationConstants.EJECT`）——见
  `TileComponentConfig#collectImplicitComponents`（`:282-284` → `AttachedSideConfig#create`，`:186`
  逐传输类型抄 `info.isEjecting()`）与 `#applyImplicitComponents`（`:258-266` →
  `info.setEjecting(lightInfo.isEjecting())`）。同一份状态也会随组件自身的 NBT 走：
  `TileComponentConfig#write(configInfo, true)` 在 `:346` 写 `putBoolean(EJECT + ordinal, info.isEjecting())`，
  `read` 在 `:308` 写回 `info::setEjecting`。所以**打掉再放回去不丢，等级升级也不丢**
  （我们的 `getUpgradeData` / `parseUpgradeData` 正好用 `component.write` / `component.read` 搬运这份状态）。
- **`TileComponentEjector` 的 `canEject` 谓词不是用户设置**：它是我们内部的防双重弹出守卫，只存在于内存里，
  不写 NBT、不上方块物品；玩家在 GUI 上看不到也改不了它。

### 7.4 玩家怎么打开主动弹出（已核对可点）

`GuiSieve extends GuiConfigurableTile`，后者在 `addGuiElements()` 里挂了 Mekanism 的
`GuiSideConfigurationTab`（窗口左边缘 `x = -26` 那颗 `configuration.png` 页签，提示文字
`MekanismLang.SIDE_CONFIG`）；点开它就是 `GuiSideConfiguration` 窗口。窗口里 ITEM 页签上有：

- 一行状态文字：`MekanismLang.EJECT`（`gui.mekanism.eject` = `"Eject: %1$s"`，参数是
  `OnOff.of(config.isEjecting())`）→ 显示 `Eject: On` / `Eject: Off`；
- 一颗图标按钮：`MekanismImageButton(gui, relativeX + 136, relativeY + 6, 14, getButtonLocation("auto_eject"), …)`，
  提示文字 `MekanismLang.AUTO_EJECT`（`auto.mekanism.eject` = `"Auto-eject"`），点击即
  `PacketUtils.sendToServer(new PacketEjectConfiguration(pos, currentType))`。

可达性：该按钮的 `active` 由 `ejectButton.active = config.canEject()` 决定，而本机的 ITEM
`ConfigInfo#canEject()` 是**未被改动的默认值 `true`**，窗口的页签列表来自
`configComponent` 里已注册的 `TransmissionType`（我们在构造函数里注册了 ITEM 的 INPUT / OUTPUT /
INPUT_OUTPUT / EXTRA / ENERGY 槽位信息）。**结论：开关对我们这台机器是可达的，不需要任何补丁。**

## 8. 第六轮 B：输入栏「自动平分」开关（新功能）

> 用户口径（第六轮中途修正）：「这是一个切换类按钮，开启后输入栏的物品将会自动平分」。所以这颗控件是
> **持续生效的开关**，不是一次性按钮：打开后输入栏一直被保持平均，关掉后永不再自动重排。
> 实现照搬 Mekanism 工厂的 sorting 开关（`TileEntityFactory`）：状态字段 + 脏标记 + 服务端每刻
> 「有脏标记且开关打开才重排」，写在 §8.3。

### 8.1 行为规格（逐条）

1. **参与槽**：某物品类型的参与槽 = 已经装着该类型的槽 + 当前为空的输入槽；一个空槽只能被一个类型
   认领，按「类型首次出现的顺序 + 槽位下标升序」分配；装着**别的**物品的槽永不被抢。
2. **只认领够用的空槽**：一个类型只在「每个参与槽至少能分到 1 个」之前继续认领空槽
   （`participants.size() < total`），认领不到就停手，剩下的空槽留给后面的类型。
3. **均分**：`perSlot = total / participants`，`remainder = total % participants`，前 `remainder` 个
   参与槽（下标最小者）各多拿 1 个。因为第 2 条，`perSlot` 恒 ≥ 1，所以平分**不会清空**原本有货的槽。
4. **上限**：每个份额再夹到「物品最大堆叠数」和「该槽自己的 limit」之间（`min(itemMax, slotLimit)`）。
   夹完之后装不下的余量按槽位顺序补给还有空间的参与槽；如果仍然装不下，**整个类型原样不动**——
   宁可不动，也绝不丢物品。
5. **合法性**：认领空槽前问该槽自己的 `isItemValid`（传给纯函数的 `Acceptor`）。
6. **no-op**：没东西可分（全空 / 只有一个参与槽 / 已经平均）时结果与输入逐槽相同，
   `InputSplit.Result#changed()` 为 `false`，`splitInputs()` 返回 `false` 且**一个槽都不写**。
7. **幂等**：参与槽在结果里仍然全部有货（第 3 条），所以第二次重排算出的参与槽集合与份额完全相同 → 无变化。
8. **触发时机**：输入栏内容一变就置位脏标记（挂在输入槽的 `IContentsListener` 上），下一个服务端刻在开关
   打开时执行**一次**重排并清标记；机器自己消耗物品同样算「变了」，所以一边筛一边保持平均。
9. **打开即生效**：`toggleAutoSplit()` 在置位状态的同时置位脏标记，不需要方块更新，也不需要再塞一次物品。
10. **关闭时**：一次重排都不会发生，只有玩家自己搬动物品才会改变分布（脏标记照样维护，只是没人用）。

### 8.2 算法出处与纯函数

照抄 Mekanism 自己的均分，
`_research_mek/src10711/mekanism/common/tile/factory/TileEntityFactory.java:627` `distributeItems(...)`：

```java
int numberPerSlot = recipeProcessInfo.totalCount / processCount;
int remainder = recipeProcessInfo.totalCount % processCount;
...
int sizeForSlot = numberPerSlot;
if (remainder > 0) { ... sizeForSlot += ...; remainder -= ...; }
...
inputSlot.setStackUnchecked(item.copyWithCount(sizeForSlot));   // Mekanism 自己的写法
```

算术全部放进**新的纯类** `com.mekexdeorum.InputSplit`（没有任何 `net.minecraft` / `mekanism` import，
只依赖 `java.util`），签名：

```java
public static Result distribute(int[] groupOf, int[] count, int[] limit,
                                GroupMax groupMax, Acceptor acceptor);
```

返回 `Result(int[] group, int[] count, boolean changed)`：每个槽「应该装哪个类型（`-1` = 空）」
「装多少个」，以及「这一次到底需不需要重排」——最后这个布尔量就是自动平分『要不要动手』的判定，
它是纯的、被单测覆盖的（见 §8.6），Minecraft 侧只负责准备快照、写回，Minecraft
侧只负责准备快照（哪些槽是哪个类型、各多少个、各槽 limit、物品最大堆叠数、`isItemValid`）以及把结果
写回去，写回用的是 `setStackUnchecked`（与 Mekanism 一致）。

### 8.3 开关的状态机：只在需要时重排（照搬 Mekanism 的 sorting）

字段与 Mekanism 一一对应（`TileEntityFactory.java:99-100 / 169 / 222-233 / 359-367 / 402-408`）：

```java
private boolean autoSplit;                 // sorting
private boolean autoSplitNeeded = true;    // sortingNeeded
private boolean splitting;                 // 我们自己的写回不算「变化」
```

```java
// getInitialInventory()：只有输入槽用这个监听；mesh / 输出 / 能量槽仍用原来的，它们不参与平分
IContentsListener inputListener = () -> {
    listener.onContentsChanged();
    if (!splitting) {
        autoSplitNeeded = true;
    }
};

// onUpdateServer()，在 drainOverflow() 之后、逐槽推进之前
if (autoSplit && autoSplitNeeded) {
    autoSplitNeeded = false;   // 先清标记再重排：重排本身会改背包
    splitInputs();
}
```

`splitInputs()` 就是「do the balancing」那一步：它先看 `InputSplit.Result#changed()`，已经平均就直接返回，
一个槽都不写 → 不触发内容监听 → 脏标记保持清零，所以**不会每刻重排**（Mekanism 在
`TileEntityFactory.java:226-231` 的注释里说「sorting will be marked as needed once more … but this is
fine」；我们靠幂等 + 脏标记 + `splitting` 守卫做到更省，连那一遍多余的都省掉）。

状态同步与持久化：

```java
// addContainerTrackers()：与 Mekanism 的工厂同一写法，客户端拿到的是服务端的真值
container.track(SyncableBoolean.create(this::isAutoSplit, this::setAutoSplit));
// saveAdditional / loadAdditional：tag.putBoolean("autoSplit", autoSplit) / getBoolean("autoSplit")
```

读档后 `setAutoSplit(...)` 会置位脏标记，所以读档后的第一刻机器就自己排一次；GUI 只画下发的值，
开关状态不会和服务器不一致。

### 8.4 服务端权威：复用了原版 `clickMenuButton`

- `SieveContainer` 覆写 `AbstractContainerMenu#clickMenuButton(Player, int)`，
  `BUTTON_SPLIT_INPUTS = 0` → `stillValid(player)` 通过后调用 `tile.toggleAutoSplit()`（**翻转**，不是一次性动作）；
- 客户端只是发**原版**的按钮包：`Minecraft.getInstance().gameMode.handleInventoryButtonClick(menu.containerId, 0)`，
  原版 `ServerboundContainerButtonClickPacket` → 服务端 `clickMenuButton`。单选与专用服务器走同一条路，
  **不需要**自定义 payload，也不需要注册网络通道；真正的改动只在服务端发生。

### 8.5 按钮与坐标（开关状态看得见）

- 控件换成 Mekanism 的 **`ToggleButton`**（`mekanism/client/gui/element/button/ToggleButton.java`，
  `MekanismImageButton` 的子类）：它按 `BooleanSupplier` 在 `mekanism:gui/button/toggle.png` 与
  `toggle_flipped.png` 之间换贴图，所以**开关状态一眼看得见**；两张贴图都是 18×18；
- 提示文字随状态变：`MekExDeorumLang.AUTO_SPLIT` = `gui.mekexdeorum.auto_split` =
  `"Auto-split inputs: %1$s"` / `"自动平分输入栏：%1$s"`，参数是 Mekanism 的 `OnOff`，于是悬停显示
  `Auto-split inputs: On` / `Auto-split inputs: Off`（与 Mekanism 自己 `Eject: %1$s` 的写法同源）；
- 状态取自 `tile::isAutoSplit`，即**服务端同步下来的值**，控件不自己猜；
- 坐标放进 `SieveLayout`（不在屏幕里硬编码），落在「筛网/能量列」与「输入行」之间的空档：

```java
public static final int SPLIT_BUTTON_SIZE = SLOT;                // 18，与它所在的那一行同高
public static final int SPLIT_BUTTON_X = LEFT_PADDING - SLOT;   // 44
public static final int SPLIT_BUTTON_Y = INPUT_Y;               // 17
```

  即 `[44,62) x [17,35)`：左边是筛网槽（止于 44）、右边是第一个输入槽（起于 62）、下面是能量槽
  （43 起）、进度条最左也在 64 之后、能量条在窗口另一侧，所以 1/3/5/7/9 栏 × 输出 1..64 全都不遮
  （矩形半开，正好贴边不算重叠）；
- 开关**始终可点**，没有「灰掉」的形态：点击永远只是翻转，翻到关的时候不会重排任何物品，所以没有需要
  禁用的时机，禁用反而会让玩家以为自己点坏了；状态本身由贴图和提示文字表达。
- 常量名保留 `SPLIT_BUTTON_*` / `BUTTON_SPLIT_INPUTS`（第六轮中途改语义时没有改名）：截图工作流
  `DevScreenshotHarness` 正在引用它们，改名会打断别人的在途改动。
- 不重叠的断言写在 `SplitButtonLayoutTest`（4 个用例，穷举 5 个等级 × 输出 1..64）。

### 8.6 新增测试（15 + 4）

`src/test/java/com/mekexdeorum/InputSplitTest.java`（15）：`evenSplitGivesEverySlotTheSameAmount`、
`remainderGoesOnePerSlotFromTheLowestIndex`、`everyRemainderValueStillAddsUpToTheTotal`、
`mixedItemTypesAreSplitIndependently`、`anItemTypeCannotGrowWhenEveryOtherSlotHoldsADifferentItem`、
`anEmptySlotIsOnlyGivenAnItemItAccepts`、`aShareIsCappedByTheItemMaxStackSize`、
`theSlotOwnLimitWinsAndTheSurplusMovesToTheNextSlot`、`aGroupThatDoesNotFitUnderItsLimitsIsLeftAlone`、
`aSingleSlotMachineHasNothingToSplit`、`doingNothingIsANoOp`、`splittingTwiceChangesNothing`、
`theSplitNeverMovesItemsBetweenGroups`、`aBalancedLayoutNeedsNoRedistribution`、
`anUnbalancedLayoutNeedsARedistributionAndThenStops`。

最后两个专门覆盖开关的判定：『这一次要不要重排』由纯函数 `InputSplit.Result#changed()` 给出，
平均的行返回 `false`（→ 不写任何槽 → 不触发监听 → 不会每刻重来），不平均的行返回 `true`，
而重排之后同一次快照再判定必须回到 `false`。开关本身的接线（字段、监听、tick、同步、NBT）没有可脱离
游戏运行的形态，靠代码走查 + 上面这条纯判定覆盖，**没有**运行时证据。

`src/test/java/com/mekexdeorum/SplitButtonLayoutTest.java`（4）：
`theButtonSitsInsideTheWindowForEveryTierAndOutputCount`、`theButtonCoversNoSlotAndNoProgressArrow`、
`theButtonStaysClearOfTheEnergyBar`、`theButtonIsOneSlotLeftOfTheFirstInputSlot`。

### 8.7 明确未动

筛分/并行路径、`SieveBatch`、`SieveMath`、输出槽容量、溢出缓冲、纹理、模型、blockstate、`data/**`、
`tools/**`、`build.gradle`，以及**除按钮坐标外的 GUI 布局几何**（窗口尺寸、槽位、进度条、能量条一个像素都没动）。

## 9. 第六轮静态验证（实跑）

```
pwsh -File build.ps1 @('build')   -> BUILD SUCCESSFUL in 7s
build/libs/mekexDeorum-1.0.1+mc1.21.1.jar -> 137 entries, 30 class（新增 InputSplit.class + 3 个内部类型）
                                             字节数随并行 workstream 对 DevScreenshotHarness 的在途改动而变
                                             （本轮观测 128865 -> 133012 -> 134605）
build/test-results/test/*.xml     -> tests=77 failures=0 errors=0 skipped=0
   EjectThrottleTest 9 / InputSplitTest 15 / MeshTiersTest 5 / SieveBatchTest 14
   SieveLayoutTest 18 / SieveMathTest 12 / SplitButtonLayoutTest 4     （第五轮 58 -> 第六轮 77）
python tools/validate_resources.py -> 7/7 checks passed, exit 0
   lang: en_us/zh_cn 各 36 个键完全一致（第五轮 35 + gui.mekexdeorum.auto_split），13 个配置键齐全
   jar: 73 个必需条目齐全，共 137 条
   DevScreenshotHarness.java 未被本轮改动（运行时工作流在用），改名后仍能编译
```

### 9.1 已知边界（本轮未做）

- 开关**仍然没有**镜像到方块物品的数据组件上，所以**打掉机器再放回去会回到默认关闭**（NBT 只保证区块
  存档/重载不丢）。原因是这不是一行代码：要照 Mekanism 的做法（`collectImplicitComponents` /
  `applyImplicitComponents` + 数据组件，Mekanism 用 `MekanismDataComponents.SORTING` 做同一件事）就得先在
  我们的注册里新增一个 `DataComponentType`，再在 `registry/MekExDeorumBlocks.java` 的方块定义上挂
  `.component(...)` —— 这两个文件不在本轮可改范围，所以本轮只补了成本低、又不丢东西的那一半（等级升级）。
- 等级升级（`SieveUpgradeData`）**已补做**：`autoSplit` 现在是升级数据（`SieveUpgradeData`）的一个字段，升级后会带到新机器上并置
  `autoSplitNeeded = true` 立即重排一次；同类的
  `batchSize` / `paused` 本来也不跨升级。
- 一个容易混淆的点：**主动弹出**开关（`ConfigInfo#isEjecting()`）由 Mekanism 自己的 `AttachedSideConfig`
  数据组件承载，所以它随方块物品保留；而本轮的 `autoSplit` 只走 NBT，拆下重放会回到默认关闭。
- 运行时证据见 §6（弹出逻辑）与 §10（平分开关），原始日志在 `_plan/EJECT_RUNTIME_EVIDENCE.md`。



## 10. 第六轮 B：自动平分开关的真机验证

真机（真实客户端 + 集成服务器，第 5 次启动）观测。**全部走真实点击路径**：探针点击 GUI 控件本身 → vanilla 的容器按钮封包 → `SieveContainer#clickMenuButton`，没有任何直接调用 `toggleAutoSplit()`/`splitInputs()` 的旁路。

| # | 观察 | 实测 | 结论 |
|---|---|---|---|
| 1 | 控件位置 | 9 输入：`toggle=(44,17,18x18)`，窗口 240x176，只贴住网格槽右缘，不压任何槽/箭头/能量条；1 输入：窗口 176x212，控件 (169,31) | ✅ |
| 2 | 默认关闭 | `isAutoSplit=false`、`nbt.autoSplit=false`；往 1 号槽放 60 个，过 tick 后仍是 `[60][0]...[0]` → **关闭时不自动重排** | ✅ |
| 3 | 点击 1 → 开 | `consumed=true menu=SieveContainer buttonId=0` → `[7,7,7,7,7,7,6,6,6]`，合计仍 60；客户端 `autoSplit=1`（SyncableBoolean 生效） | ✅ |
| 4 | 不空转 | 20 tick 后逐项相同（`identicalToPreviousSample=true`）→ 已均匀时确实不写槽 | ✅ |
| 5 | 点击 2 → 关，且只往 5 号槽加 45 | `[7,7,7,7,52,7,6,6,6]` 合计 105；20 tick 后逐字节不变 → **关闭后保持不匀** | ✅ |
| 6 | 点击 3 → 再开 | `[12x6, 11x3]` 合计 105（6*12+3*11）→ **再次均摊且总量守恒** | ✅ |
| 7 | 开启状态下边消耗边均衡 | 装线筛网+能量：总量 105 → 96（11x6,10x3）→ 87（10x6,9x3），任两槽差 ≤1，开关全程为真 | ✅ |
| 8 | 截图 | `docs/fix-verification/gui_ultimate_autosplit_{off,on}.png`、`gui_universal_autosplit_on.png`；开关贴图两态明显不同 | ✅ |
| 9 | 持久化 | `saveWithoutMetadata().getBoolean("autoSplit")` 两台机器均为 true（即 `/data get block` 打印的同一份数据） | ✅ |
| 10 | 区块卸载/重载 | **未强制**卸载再加载区块，只读了存档标签 | ⚠️ |

一处清理：第 4 次启动为"一次性语义"拍的两张图已从 `docs/fix-verification/` 移除（仍留在 `_plan/client_shots/` 作为历史），以免该目录展示已被取代的语义。

原始日志：`_plan/EJECT_RUNTIME_EVIDENCE.md`（`# Round 6b — check 9`，434 行）。
