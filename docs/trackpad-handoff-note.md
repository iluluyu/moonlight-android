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
5. **捏合缩放误触修复**（2026-08 重写判别器，见下）：
   - 旧方案：纯累计点积（|dot|>800 判捏合）→ 慢速滚动 + "走路"手指会漂成负点积偶发误触
   - 新方案：**confirm-and-lock 状态机** — 三信号联合判别（累积分离变化 |sep|>45px 且 >1.25×质心位移、净点积 <0、双指各自移动 >15px），且一旦质心位移 >70px 而分离变化 <35% 即**锁定为滚动**，本手势内永不再判捏合（Mac/Win 同为"手势身份一经确立即固定"）
   - 缩放改**位置式**（相对 Ctrl 按下时的分离度），静止不漂移、反向即缩回
6. **三指状态机重写**（2026-08）：
   - 一次 stroke 至多触发一次同向滑动（修复长滑双击 Alt+Tab）；每次 3 指 DOWN 即武装，触发时才按方向门控：同向 250ms 冷却内吞掉，**反向立即触发**（不等待冷却）
   - **中途反向重武装**：右滑→左滑不抬手即可来回切（循环改主意）
   - **速度自适应阈值**：快甩（<200ms）60/45px 即触发，慢滑 150/120px；快慢主轴比 1.3/1.5
   - 轻点判定收紧：<250ms、位移 <30px（原 300ms/60px 会把小滑当轻点）
   - ACTION_CANCEL 按抬手处理（不判轻点）
7. **3→2 指尾部隔离**（2026-08）：三指 stroke 结束时 `cancelTouch()` 两个 TrackpadContext 槽 + 200ms 捏合守卫 —— 抬手尾巴不会误成双指滚动/捏合；`TrackpadContext.touchDownEvent` 增加 cancelled 守卫，杜绝"剩余手指转主指"路径的幽灵右键（此前 3 指轻点 = Alt+Tab + 右键双触发）
8. **任务视图导航缓冲**（2026-08）：Win+Tab 展开动画 ~350ms 内到达的方向键/回车先缓冲再注入（否则被 Windows 丢弃）；模式切换/超时取消挂起的导航键；超时 4s→8s（避免用户停下来读窗口时被踢出导航）

## 未完成 ❌
- **实机验证**（2026-08 改动全部未上机）：
  - 捏合：慢速滚动不再误触缩放？轻捏（双指同时反向）45px 内能起效？缩放顺滑度（PINCH_ZOOM_GAIN=1.5、30 单位粒度）？
  - 三指：快速连滑切应用（同向 250ms 门控是否跟手）；右→左不抬手反向是否立即响应；快甩 60px 是否太敏感（误触任务视图/显示桌面）
  - 任务视图：350ms 缓冲后方向键是否不再丢失；8s 超时是否合适
  - 尾巴隔离：三指手势后遗留双指移动是否完全无动作
- 若快甩 60/45px 过灵敏 → 调 `TP3_SWIPE_X_FAST/TP3_SWIPE_Y_FAST`；若滚动仍偶发触发捏合 → 降 `PINCH_SEP_CONFIRM_PX` 需配合升 `PINCH_CENTROID_LOCK_PX`（滚动锁更早生效）
- Mac 参数（动量/加速曲线/轻点窗口）仍未实测调优

## 关键事实（重要，勿再走弯路）
- **三指事件能到达 app**（TP-PROBE 证实 566 个 pc=3 事件；早期"ColorOS 拦截"结论是错的）
- **框架 `getClassification()` 在本机恒返回 NONE**（分类不可用，需自研判别）
- 串流场景 OS 级手势**无原生通道**，只能发快捷键；手感靠参数（动量/时序/曲线）
- 触控板手势已从 Shizuku 移到 Game 框架路径；Shizuku 仅保留键盘抓取（稳定）
- `ShizukuInputService.startTrackpadReader` / `nativeOpenTrackpadObserver` 是死代码（evdev_grab.c 过滤 EV_ABS 设备），未启用勿复活
- 3 指以上事件只取前 3 个指针计算质心；4/5 指未实现
- 时间戳统一用 `event.getEventTime()`（uptime）——Game 手势与捏合守卫互相比对，勿混用 `System.currentTimeMillis()`

## 代码位置
- `Game.java`：三指检测 `handleTrackpadGestures`、捏合 `handleTrackpadPinch`、映射 `handleTrackpadGesture`、任务视图缓冲 `postTaskViewKey`、尾部隔离 `resetTrackpadContexts`
- `TrackpadContext.java`：1/2 指滚动/点击/拖拽/动量；`touchDownEvent` cancelled 守卫
- `ShizukuInputService.java`：键盘 EVIOCGRAB 抓取
- `docs/trackpad-gestures-design.md`：完整手势设计文档

## 新判别/状态机参数速查（Game.java）
| 参数 | 值 | 含义 |
|---|---|---|
| TP3_SWIPE_X/Y | 150/120 | 慢滑触发阈值 px |
| TP3_SWIPE_X/Y_FAST | 60/45 | 快甩（<200ms）触发阈值 px |
| TP3_TAP_MAX_DURATION_MS | 250 | 三指轻点最大时长 ms |
| TP3_TAP_MAX_MOVE | 30 | 三指轻点最大位移 px |
| TP3_COOLDOWN_MS | 250 | 同向重复触发门控 ms |
| TP3_REVERSE_PX | 50 | 中途反向重武装阈值 px |
| TP3_TAIL_GUARD_MS | 200 | 3→2 指捏合守卫 ms |
| PINCH_SEP_CONFIRM_PX | 45 | 捏合确认所需累积分离变化 px |
| PINCH_SEP_CENTROID_RATIO | 1.25 | 分离必须 >1.25×质心位移 |
| PINCH_FINGER_MIN_TRAVEL | 15 | 双指各自最小移动 px |
| PINCH_CENTROID_LOCK_PX | 70 | 滚动锁定质心位移 px |
| PINCH_LOCK_SEP_RATIO | 0.35 | 锁定条件：分离 <0.35×质心 |
| PINCH_ZOOM_GAIN | 1.5 | 分离 px → 滚轮单位倍率 |
| PINCH_NOTCH_UNITS | 30 | 单次注入滚轮单位（1/4 格） |
| PINCH_PHASE_RESET_MS | 150 | 停顿超时后累计量减半 |
| TASK_VIEW_TIMEOUT_MS | 8000 | 任务视图导航超时 |
| TASK_VIEW_NAV_ARM_MS | 350 | Win+Tab 展开动画缓冲窗 |
