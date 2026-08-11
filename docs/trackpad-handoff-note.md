# 触控板工作交接 Note

> 状态：**第二轮 Mac/PTP 对标与首轮 OPPO 真机测试已完成；单指光标链路已在真实串流恢复，继续回归手势手感**（2026-08-11）。

## 目标
OPPO Pad 4 Pro 官方磁吸键盘触控板 → 远程 Windows 主机，接近 Mac 触控板手感。

## 已完成 ✅
1. **轻点卡键修复**：tap 后延迟抬起与双击拖拽竞态导致左键卡住 → 已修复（显式 leftButtonDown 跟踪 + 放弃路径补发 UP）
2. **拖拽修复**：手指1按住 + 手指2移动可拖拽（primaryButtonDown 全局标志）
3. **三指手势**（Game 框架路径直接检测）：
   - 左右滑 = Alt+Tab 切应用（手势驱动切换器，3s 安全兜底防卡）
   - 上滑 = 任务视图 + 四向滑导航 + 轻点选中
   - 下滑 = 显示桌面，轻点 = Alt+Tab
4. **仿 Mac 手感的经验初值**：动量滚动 FLICK_FRICTION=0.98/10ms（τ≈500ms）、轻点 10px/180ms、分段加速曲线、滚动速度 Y=2。注意：这些不是 Apple 公布参数，仍须实测。
5. **捏合缩放误触与“先滚后缩”修复**（2026-08 重写判别器，见下）：
   - 旧方案：纯累计点积（|dot|>800 判捏合）→ 慢速滚动 + "走路"手指会漂成负点积偶发误触
   - 新方案：**ambiguous → confirm-and-lock 状态机** — 未决 MOVE（含 historical samples）先保留；同向且分离稳定时尽早确认为滚动并按原时间戳回放，分离主导且双指反向时确认为捏合并丢弃缓冲。因此远端不会先收到不可撤销的滚轮事件
   - 捏合确认距离按初始双指间距的 6% 缩放并限制在 20–36px，同时要求 >1.25×质心位移、净点积 <0、双指各移动 ≥10px；滚动早确认使用质心 ≥14px、分离 ≤55%、点积 ≥0，最长未决窗 300ms/48 个 MotionEvent
   - 缩放改**位置式**（相对 Ctrl 按下时的分离度），静止不漂移、反向即缩回
6. **三指状态机重写**（2026-08）：
   - 一次 stroke 至多触发一次同向滑动（修复长滑双击 Alt+Tab）；每次 3 指 DOWN 即武装，触发时才按方向门控：同向 250ms 冷却内吞掉，**反向立即触发**（不等待冷却）
   - **中途反向重武装**：右滑→左滑不抬手即可来回切（循环改主意）
   - **速度自适应阈值**：快甩（<200ms）60/45px 即触发，慢滑 150/120px；快慢主轴比 1.3/1.5
   - 轻点判定收紧：<250ms、位移 <30px（原 300ms/60px 会把小滑当轻点）
   - ACTION_CANCEL 按抬手处理（不判轻点）
7. **3→2 指尾部隔离**（2026-08）：三指 stroke 结束时 `cancelTouch()` 两个 TrackpadContext 槽 + 200ms 捏合守卫 —— 抬手尾巴不会误成双指滚动/捏合；`TrackpadContext.touchDownEvent` 增加 cancelled 守卫，杜绝"剩余手指转主指"路径的幽灵右键（此前 3 指轻点 = Alt+Tab + 右键双触发）
8. **任务视图导航缓冲**（2026-08）：Win+Tab 展开动画 ~350ms 内到达的方向键/回车先缓冲再注入（否则被 Windows 丢弃）；模式切换/超时取消挂起的导航键；超时 4s→8s（避免用户停下来读窗口时被踢出导航）
9. **三指跨 stroke 状态隔离**（2026-08-11）：全局同向冷却与当前 stroke 方向分开保存；冷却吞掉的 stroke 也更新自己的触发坐标，不再拿上一次落点判断反向；已经滑动后只反向 50px 又抬手不会误判成三指轻点。
10. **指针身份/指头数量加固**（2026-08-11）：捏合的两根手指用稳定 pointer ID 跟踪，index 重排不会制造巨大假位移；第 4/5 指落下会取消三指手势并隔离到全部抬起，不再任取“前 3 指”继续触发。
11. **采样率无关的移动与惯性**（2026-08-11）：光标加速改按 px/ms 而非单帧 px，速度 EMA 也改按 elapsed time/30ms 时常计算；60/120/240Hz 下同速手指得到一致响应。二指内容滚动不再套用光标加速，斜滚允许超过主轴 35% 的副轴，惯性按真实 elapsed time 指数衰减。新一轮触摸会停止两个 context 中任一方持有的惯性，但保留双击窗口。
12. **失焦安全释放**（2026-08-11）：窗口失焦、Activity pause、取消输入抓取时立即终止手势，释放合成 Alt/Ctrl、物理左键与 TrackpadContext 状态，避免等到 `onDestroy()` 才清理导致远端卡键。
13. **任务视图导航保序**（2026-08-11）：350ms 动画窗内从“只保留最后一键”改为最多 4 键 FIFO；快速左右反向或连续滑动不会丢掉前面的用户意图。所有 UI 定时改用 monotonic uptime。
14. **Mac / Windows PTP 架构对标**（2026-08-11）：确认 Apple 的手势 phase / momentum phase、AOSP 的 ambiguous gesture 抑制规则，以及开源 `mac-precision-touchpad` 的原始 contact frame → Windows PTP 路径；本轮代码采用相同的“采集、仲裁、输出”分层，但受串流协议限制仍在客户端合成鼠标/键盘。
15. **OPPO Pad 4 Pro 首轮真机数据**（2026-08-11）：arm64 debug 包已在 Android 16 / API 36 安装并进入真实串流。ADB 确认 `event10 pogo_touchpad` 支持 5 contacts、`INPUT_PROP_BUTTONPAD`、23 units/mm（约 120.2×70.9mm），另有 `event13 OPPO Pad 4 Pro Keyboard Touchpad` 10-contact 框架节点。首轮日志中滚动在 2–9 个缓冲事件、8–68ms 内确认；捏合在 2–3 个事件、8–16ms 内确认，未见滚动被误判为捏合；三指四向与轻点均有成功 `fire`/`tap` 记录。
16. **切出串流后的输入恢复加固**（2026-08-11）：首轮测试中用户报告远端光标停止，但原始 `pogo_touchpad` 坐标仍以约 120Hz 到达，系统也仍显示 pointer capture。焦点/Activity/input-grab 清理现在记录明确 reason，释放所有已知物理鼠标键；若 Android 恢复捕获时直接从 MOVE 续流而没有新 DOWN，会用首帧重新 seed 1/2 指 context。另修复待释放 tap 按钮身份：双指右键或多指按钮不再因 pointer count 变化而被错误地按左键释放。
17. **ADB 测试启动陷阱已定位**（2026-08-11）：连续出现的“事件正常但远端光标不动”并非触控板或输入协议故障，而是调试包曾由 `adb monkey` 启动。`NvConnection` 会在构造时读取 `ActivityManager.isUserAMonkey()`，为 true 时有意静默丢弃鼠标、键盘和滚轮输入；ColorOS 在 monkey 进程结束后还残留了 `mControllerIsAMonkey=true`。通过 Activity monitor 覆盖该状态、确认其为 false，再以 `am start` 冷启动并重进串流后，用户确认单指远端光标恢复移动。
18. **滚动惯性与抬指顺序解耦**（2026-08-11，最终曲线待真机确认）：用户实测发现长距离上下滚动抬手后会突然停止。对应日志显示该轮以 `ACTION_POINTER_UP(0)` 结束，而二指滚动速度保存在 context index 1；旧路由只向 action index 0 发送 `touchUpEvent()`，导致惯性拥有者永远收不到结束信号。现在主指先抬时会显式调用 `TrackpadContext.finishScrollGesture()`，只启动 context 1 的滚动惯性，不重复运行二指 tap/右键逻辑；实测已多次出现 `scroll momentum handoff`。滚动启动阈值与光标甩动拆分为 0.25 / 0.8px/ms，慢滚从 0.965/10ms 的较强阻尼起步并随速度平滑过渡到 0.98/10ms，达到 1px/ms 后保持原快速曲线。抬手前停顿 50ms 仍会衰减到零，不产生漂移。

## 未完成 ❌
- **真机回归仍需完成**：
  - 单指基础移动已通过；仍需单独执行“移动 → 切出/返回 → 立即移动”，确认 lifecycle 恢复逻辑；若 Android 从 MOVE 恢复，应看到 `recovered contexts from MOVE`
  - 按钮：双指轻点后立即切出，远端右键不得保持按下；日志中的 `interaction state cancelled reason=... buttons=...` 应与实际路径一致
  - 主观手感：缓冲回放是否有可感知停顿/突跳，缩放增益是否过大，斜滚副轴与惯性是否自然；首轮目前只有日志证据，不能代替用户手感结论
  - 惯性：用末尾仍在运动的长滚动分别覆盖 `ACTION_POINTER_UP(0/1)`；两种抬指顺序都应平滑减速，新包的 index 0 路径应记录 `scroll momentum handoff after pointer 0 lift`
  - 四/五指：首轮日志未捕获到 `stroke suppressed: pointer count=4`，仍需确认 ColorOS 是否在该轮截获，以及退回三指时是否无动作
  - 三指连续反向、任务视图 350ms FIFO、8s 超时和 3→2 尾部隔离仍需按完整脚本重复 10 次
- **TrackpadContext 的历史 index 架构**：捏合判别器已改用 pointer ID，但通用 1/2 指 `handleTouchInput()` 仍按 context index 取坐标。OPPO 驱动若实测会在同一 gesture 内重排 index，需再做 context↔pointer ID 绑定；当前不要在无复现时扩大触屏输入改动面。
- **原生 PTP 串流通道**：新版 Windows 已提供面向远程桌面等场景的触控板输入/注入 API，但当前 moonlight-common-c 只有鼠标、高精度滚轮、触屏/笔和控制器触控板事件，没有“系统 Precision Touchpad contact frame”包。若要真正让 Windows 接管全部手势，需要 Apollo/Sunshine + moonlight-common-c + Artemis 协议协同扩展，本轮未实现。
- 若快甩 60/45px 过灵敏 → 调 `TP3_SWIPE_X_FAST/TP3_SWIPE_Y_FAST`；若二指滚动起步迟钝或捏合仍误判 → 优先根据 `scroll/pinch confirm` 的 `buffered/t` 日志调整 `TrackpadGestureClassifier`，不要只改缩放增益
- 经验参数（动量/加速曲线/轻点窗口）仍未实测调优；不要再称为 Apple/macOS 官方参数

## 关键事实（重要，勿再走弯路）
- **三指事件能到达 app**（TP-PROBE 证实 566 个 pc=3 事件；早期"ColorOS 拦截"结论是错的）
- **硬件没有在“远端光标不动”时掉线**：`event10` 仍持续产生完整 tracking/down/move/up；本轮冻结的直接原因是 monkey 自动化保护，而不是 OPPO 驱动、pointer capture 或远端输入协议
- **真机串流测试禁止用 `adb monkey` 启动 Artemis**：它会让 `NvConnection` 丢弃全部远端输入；安装后用显式 Activity 启动，并先确认 `mControllerIsAMonkey=false`
- **框架 `getClassification()` 在本机恒返回 NONE**（分类不可用，需自研判别）
- 串流场景 OS 级手势**无原生通道**，只能发快捷键；手感靠参数（动量/时序/曲线）
- 触控板手势已从 Shizuku 移到 Game 框架路径；Shizuku 仅保留键盘抓取（稳定）
- `ShizukuInputService.startTrackpadReader` / `nativeOpenTrackpadObserver` 是死代码（evdev_grab.c 过滤 EV_ABS 设备），未启用勿复活
- 只在 exactly 3 指时计算质心；4/5 指未映射，额外指头会取消并隔离当前 stroke
- 时间戳统一用 `event.getEventTime()`（uptime）——Game 手势与捏合守卫互相比对，勿混用 `System.currentTimeMillis()`

## 调研校正（2026-08-11）

### 已由上游资料确认

- Android 的 pointer **index 顺序未定义且可跨事件变化**；跨事件跟踪必须保存 pointer ID，再用 `findPointerIndex()` 查当次 index。`ACTION_CANCEL` 取消整个 gesture，View 还必须容忍前一轮没收到 UP 就来了新 DOWN。来源：[MotionEvent reference](https://developer.android.com/reference/android/view/MotionEvent)、[Track multiple pointers](https://developer.android.com/develop/ui/views/touch-and-input/gestures/multi)。
- libinput 的一个 gesture 不会在中途改变指头数量；数量变化会取消旧 gesture。Pinch 输出相对初始位置的绝对 scale，三指 swipe 使用 logical center 位移。来源：[libinput gestures](https://wayland.freedesktop.org/libinput/doc/latest/gestures.html)。
- libinput 的 pointer acceleration 先用多事件与时间戳计算 velocity，再由 velocity 决定倍率；这支持本轮把“单帧位移曲线”改成 px/ms。来源：[libinput pointer acceleration](https://wayland.freedesktop.org/libinput/doc/latest/pointer-acceleration.html)。
- 二指滚动通常先过启动距离阈值，身份确认后微小移动也应继续滚；finger-source kinetic scroll 由调用方计算速度并在新交互时停止。来源：[libinput scrolling](https://wayland.freedesktop.org/libinput/doc/latest/scrolling.html)。
- AOSP 对 `CLASSIFICATION_AMBIGUOUS_GESTURE` 的要求是：意图未决时应抑制 scrolling；触控板还可携带逐 sample 的 scroll distance、pinch scale 与 gesture offset，且 historical samples 不能漏处理。来源：[MotionEvent source](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/MotionEvent.java)。目标 OPPO 目前始终报告 `NONE`，所以 Artemis 自行实现同样的未决阶段。
- Windows 官方默认触控板语义就是：三指上=显示所有窗口、下=显示桌面、左右=切换打开的应用；当前 Artemis 映射不是臆造。来源：[Microsoft touch gestures](https://support.microsoft.com/en-us/windows/touch-gestures-for-windows-a9d28305-4818-a5df-4e2b-e5590f850741)。Alt+Tab、Win+Tab、Win+D 的注入语义也与[官方快捷键表](https://support.microsoft.com/en-us/windows/keyboard-shortcuts-in-windows-dcc61a57-8ff0-cffe-9796-cb9706c75eec)一致。
- Apple 官方支持页确认二指滚动/捏合以及多指 Mission Control/桌面切换的交互习惯；该页没有提供 tap、加速或惯性识别常数。来源：[Mac trackpad gestures](https://support.apple.com/en-us/102482)。
- Apple 的开发者文档进一步表明：驱动先识别 gesture sequence，应用收到 begin/change/end/cancel phase；滚轮事件把手指滚动 phase 与 momentum phase 分开，缩放以连续相对 magnification 发送。Scroll/swipe 一旦真正开始即锁定，但 magnify/rotate 在一个多点触摸序列中仍可能切换。来源：[Handling Trackpad Events](https://developer.apple.com/library/archive/documentation/Cocoa/Conceptual/EventOverview/HandlingTouchEvents/HandlingTouchEvents.html)。
- 开源 Windows 驱动 [`imbushuo/mac-precision-touchpad`](https://github.com/imbushuo/mac-precision-touchpad) 没有复制一套 macOS 手势阈值；它把 Apple 原始触点转换成含 Contact ID、X/Y、Tip、Confidence、Scan Time、Contact Count 与按钮状态的 HID PTP report，再让 Windows 决定 mousing/action/gesture。对应实现见 [`InputInterrupt.c`](https://github.com/imbushuo/mac-precision-touchpad/blob/master/src/AmtPtpDeviceUsbUm/InputInterrupt.c)，字段语义与 [Windows Precision Touchpad Collection](https://learn.microsoft.com/en-us/windows-hardware/design/component-guidelines/touchpad-windows-precision-touchpad-collection) 一致。
- Windows 官方说明 Precision Touchpad 与旧式鼠标模拟的关键区别正是“上报手指位置/状态，由 Windows 决定动作”；其新触控板 API明确面向 Remote Desktop 客户端，并提供 synthetic touchpad/action injection。来源：[Precision Touchpad Input](https://learn.microsoft.com/en-us/windows/win32/input-precisiontouchpad/precision-touchpad-portal)。

### 工程推断（待真机证伪）

- OPPO 本机 `getClassification()==NONE`，所以应用层 confirm-and-lock 是合理降级，但 45/70px 等阈值只适用于当前坐标尺度，不是跨设备物理常数。
- Win+Tab 动画约 350ms、任务视图 8s 状态窗、Alt+Tab 500ms release tail 都是远程交互经验值，不是 Windows API 保证。
- `FLICK_FRICTION=0.98/10ms`、0.8→4.0px/ms 加速区间、10px/180ms tap、20–36px 捏合与 14px 滚动早确认都只是首轮调参起点。
- Mac 和 Windows PTP 的最佳路径都依赖 OS 级 recognizer；当前 Artemis 的缓冲判别是现有 Moonlight 输入协议下的近似，不应宣称与 macOS 曲线完全一致。

## 下一轮真机验证脚本

安装后的启动方式（Windows ADB 路径按本机环境替换）：

```sh
adb shell dumpsys activity | grep 'mController='
adb shell am force-stop com.limelight.noirdebug
adb shell am start -W -n com.limelight.noirdebug/com.limelight.PcView
```

第一条必须显示 `mControllerIsAMonkey=false`。不要使用 `adb monkey`；若残留为 true，先用
`adb shell am monitor` 建立非 monkey controller 后退出，再冷启动应用。

调试包日志统一使用：

```sh
adb logcat -c
adb logcat -s ArtemisTP
```

每组动作至少重复 10 次，并同时观察远端结果与日志：

1. 慢速直线二指滚动、交替“走路”滚动、斜向滚动：不得出现 `pinch confirm`；记录 `scroll confirm` 的 `buffered/t`，起步应无明显延迟或突跳。
2. 轻/慢/快捏合后反向：`pinch confirm` 前远端不得滚动；确认后静止不得漂移，反向应回缩；抬一指必须出现 `pinch end` 且 Ctrl 不粘住。
3. 三指同向快速连续 stroke、不同落点连续 stroke、右→左不抬手、滑后反向不足阈值直接抬手：分别核对 `fire`/`swallow`/`reversal re-arm`，不得额外出现 `tap`。
4. 三指过程中补第 4 指，再退回 3 指：应只见 `stroke suppressed`，直到全部抬起都不触发宿主动作。
5. 三指上后 350ms 内连续左/右/上/下与轻点：FIFO 顺序应与手势一致，无动画期丢键或选择后残留方向键。
6. 惯性滚动过程中单指落下、切到系统界面、取消抓取：惯性立即停止，远端 Alt/Ctrl/左键均不保持按下。

## 代码位置
- `Game.java`：三指检测 `handleTrackpadGestures`、二指事件缓冲/回放 `handleTrackpadPinch`、映射 `handleTrackpadGesture`、任务视图缓冲 `postTaskViewKey`、尾部隔离 `resetTrackpadContexts`
- `TrackpadGestureClassifier.java`：纯二指 scroll/pinch 仲裁与未决窗口；阈值单测入口
- `TrackpadContext.java`：1/2 指滚动/点击/拖拽/动量；时间型 EMA、线性双指滚动与 `touchDownEvent` cancelled 守卫
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
| PINCH_ZOOM_GAIN | 1.5 | 分离 px → 滚轮单位倍率 |
| PINCH_NOTCH_UNITS | 30 | 单次注入滚轮单位（1/4 格） |
| PINCH_PHASE_RESET_MS | 150 | 未决手势停顿后丢弃旧缓冲并开始新 phase |
| TASK_VIEW_TIMEOUT_MS | 8000 | 任务视图导航超时 |
| TASK_VIEW_NAV_ARM_MS | 350 | Win+Tab 展开动画缓冲窗 |
| TASK_VIEW_PENDING_NAV_LIMIT | 4 | 动画窗内最多保留的导航键数 |

`TrackpadGestureClassifier.java`：捏合确认距离=`clamp(初始间距×6%, 20, 36px)`，分离/质心比 `1.25`，
双指各自最小移动 `10px`；滚动早确认质心 `14px`、分离≤质心 `55%`、点积≥0；长路径回退
`70px/35%`；未决上限 `300ms/48 MotionEvent`。

`TrackpadContext.java` 手感初值：加速起点/封顶速度 `0.8/4.0 px/ms`，最大倍率 `2.5`；
速度 EMA 时常 `30ms`；惯性 `0.98/10ms`（按真实 elapsed time 换算），启动速度 `0.8 px/ms`；
滚动倍率 X/Y=`1.5/2.0`，副轴进入比例 `35%`。
