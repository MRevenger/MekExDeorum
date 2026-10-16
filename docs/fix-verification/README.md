# 真机验证截图（Minecraft 客户端真实渲染）

本目录的 PNG 全部由 Minecraft 客户端通过 `Screenshot.grab` 真实渲染得到，**不是**离屏程序渲染。生成方式见 `_plan/F4_CLIENT_PLAN.md`、`_plan/F4_RESULTS.md` 与开发用截图钩子 `src/main/java/com/mekexdeorum/client/dev/DevScreenshotHarness.java`（三重开关：`production==false` + `-Dmekexdeorum.screenshots=true` + `run/screenshots-enabled.flag`，生产环境不会生效）。

## 图片属于哪一轮

| 文件 | 轮次 | 说明 |
|---|---|---|
| `gui_{universal,basic,advanced,elite,ultimate}_sieve_factory.png` | 第三轮 | 5 套 GUI 真机截图（输入栏 1/3/5/7/9）。证明箭头不再压住输出槽、输出区自适应并居中。 |
| `model_{id}_{front,back,left,right,top,bottom,diagonal_left,diagonal_right}.png`（40 张） | 第三轮 | 5 级 × 8 角度。证明凹陷顶面可见、四角无缝隙、机器外观统一。 |
| `row_tiers_front.png`、`row_tiers_front_close.png`、`row_tiers_diagonal.png` | 第四轮 | **5 台机器并排**（真正相邻），证明等级横条颜色逐级变化。 |
| `bar_{universal,basic,advanced,elite,ultimate}.png` | 第四轮 | 每级正面对准横条的特写，便于逐级比对颜色。 |
| `harness.log` | — | 最近一次客户端运行的完整钩子日志（含门禁状态、每张图的相机参数、功能探针输出）。 |

**重要提示**：第三轮的 45 张截图拍摄于第四轮纹理修改**之前**，因此它们显示的是第三轮外观 —— 工厂正面带罗马数字 I–IV、侧面带 1–4 条蓝色强调条、顶端横条是第三轮的暗色标定值。第三轮要证明的结论（箭头不遮挡、布局自适应、凹陷可见、四角无缝）与这些元素无关，仍然成立；但若要查看**当前**外观，请看第四轮的 8 张。

## 第四轮横条颜色的真机证据

渲染出来的像素 = 官方 RGB × **0.745** 的均匀面向阴影系数（北面朝向被绘制得更暗），色度保持不变：

| 等级 | 官方 RGB | 真机渲染像素 | 逐通道比值 |
|---|---|---|---|
| 基础 | `#5FFFB8` | `#47BE89` | 0.747 / 0.745 / 0.745 |
| 高级 | `#FF806A` | `#BE5F4F` | 0.745 / 0.742 / 0.745 |
| 精英 | `#4BF8FF` | `#38B9BE` | 0.747 / 0.746 / 0.745 |
| 终极 | `#F787FF` | `#B865BE` | 0.745 / 0.748 / 0.745 |
| 通用筛矿机 | —（无彩色横条） | `#373737` | — |

把每个通道除以 0.745 后可在约 1/255 内复原官方十六进制值，即横条确实是 Mekanism 官方等级色，只是被面朝向阴影压暗。

## 已知现象（非本轮引入）

横向视角截图的画面上方会出现一个悬空的深色方块状几何体（附半透明平面）。它在第三轮的截图里同样存在（见 `model_universal_sieve_front.png`），随相机移动、是世界几何体，且不与任何机器或横条重叠；成因未查明，与纹理修复无关。
