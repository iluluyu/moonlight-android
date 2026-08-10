# 触控板工作交接 Note

> 状态：**进行中，未完成**。交给后续模型继续优化。

## 目标
OPPO Pad 4 Pro 官方磁吸键盘触控板 → 远程 Windows 主机，接近 Mac 触控板手感。

## 已完成 ✅
1. **轻点卡键修复**：tap 后延迟抬起与双击拖拽竞态导致左键卡住 → 已修复（显式 leftButtonDown 跟踪 + 放弃路径补发 UP）
2. **拖拽修复**：手指1按住 + 手指2移动可拖拽（primaryButtonDown 全局标志）
3. **三指手势**（Game 框架路径直接检测）：
   - 左右滑 = Alt+Tab 切应用（手势驱动切换器，3s 安全兜底防卡）
   - 上滑 = 任务视图 + 四向滑导航 + 轻点选中
   - 下滑 = 显示桌面，轻点 = Alt+Tab
4. **Mac 参数移植**（基于调研）：动量滚动 FLICK_FRICTION=0.98（τ≈500ms）、轻点 10px/180ms、分段加速曲线、滚动速度 Y=2

## 未完成 ❌
- **捏合缩放误触**：点积判别仍偶发把滚动当捏合，需调阈值/时序
- 任务视图导航层级感、左右滑切换器流畅度仍待打磨
- Mac 参数未实测验证

## 关键事实（重要，勿再走弯路）
- **三指事件能到达 app**（TP-PROBE 证实 566 个 pc=3 事件；早期"ColorOS 拦截"结论是错的）
- **框架 `getClassification()` 在本机恒返回 NONE**（分类不可用，需自研判别）
- 串流场景 OS 级手势**无原生通道**，只能发快捷键；手感靠参数（动量/时序/曲线）
- 触控板手势已从 Shizuku 移到 Game 框架路径；Shizuku 仅保留键盘抓取（稳定）

## 代码位置
- `Game.java`：三指检测 `handleTrackpadGestures`、捏合 `handleTrackpadPinch`、映射 `handleTrackpadGesture`
- `TrackpadContext.java`：1/2 指滚动/点击/拖拽/动量
- `ShizukuInputService.java`：键盘 EVIOCGRAB 抓取
- `docs/trackpad-gestures-design.md`：完整手势设计文档
