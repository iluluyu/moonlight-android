# Artemis 触控板完整手势体系设计（调研 + 方案）

> **实现状态（2026-08-11）**：捏合、三/四指滑和第二轮触控板架构对标加固已落地；
> 最新 arm64 debug 包已在 OPPO Pad 4 Pro（Android 16 / API 36）完成真实串流回归。相对模式 80%
> 已通过画圆、精准定位、双屏跨越、快速反向、滚动惯性、落指刹停与缩放测试；四指虚拟桌面和三指同一
> stroke 连续应用定位、Alt+Tab 二维行列导航、横向弧线抑制与两排边界保护也已通过。当前采用唯一的
> Windows 语义。
> 执行清单、实测记录与官方资料链接见 `trackpad-handoff-note.md`。
> 当前实现要点：
> - 捏合判别器升级为 **ambiguous → confirm-and-lock 状态机**：未决事件（含 historical samples）先缓冲，
>   确认滚动后按原时间戳回放，确认捏合后丢弃，避免远端出现不可撤销的“先滚后缩”；
> - 三指状态机改为"每次 DOWN 武装、触发时按方向门控"：同向冷却吞掉、反向立即触发、
>   中途反向重武装、快甩小阈值（60/45px）、一次 stroke 至多一次同向触发；
> - 三指 stroke 结束时 cancel 两个 TrackpadContext 槽 + 200ms 捏合守卫（尾部隔离），
>   TrackpadContext.touchDownEvent 增加 cancelled 守卫（修幽灵右键）；
> - 当前 stroke 方向与跨 stroke 冷却分离；四指使用独立 centroid detector，5 指取消并隔离；
>   捏合按 pointer ID 跟踪；
> - 三指上滑无状态地发送 Win+Tab；Task View 不接管后续三指手势；
> - 光标加速、速度 EMA 与惯性按 elapsed time 计算；二指滚动保持线性并支持有意的斜向双轴；
>   单指抬起立即停止，只有二指内容滚动进入 momentum phase；
> - 失焦/取消抓取立即释放合成修饰键和鼠标键，捕获恢复若缺失 DOWN 可由首个 MOVE 重新 seed。

目标设备：OPPO Pad 4 Pro 官方磁吸键盘触控板。ADB 实测可见 `event10 pogo_touchpad`
（5 contacts、`INPUT_PROP_BUTTONPAD`、约 120.2×70.9mm）以及 `event13 OPPO Pad 4 Pro Keyboard Touchpad`
（10 contacts；其 resolution 元数据明显不代表真实毫米尺寸）。
主事件链：`Game.onTouch` / `onGenericMotionEvent` → `handleMotionEvent` → `SOURCE_TOUCHPAD` finger 分支 → `handleTrackpadGestures` → `handleTrackpadPinch` → `handleTouchInput(event, trackpadContextMap, false)`。

## 1. 现有手势能力清单

`trackpadContextMap` = `TouchContext[2]`（仅 2 槽）。1/2 指基础行为在 `TrackpadContext`，
捏合和 3 指以上的路由在 `Game`：

| 指数/动作 | 当前宿主机行为 | 代码位置 |
|---|---|---|
| 1 指移动 | 相对移动光标 `sendMouseMove`（速度加速；抬手立即停止，无光标惯性） | TrackpadContext.touchMoveEvent pointerCount==1 |
| 1 指轻点(≤180ms,≤10px/轴) | 左键点击（按下延时 180ms 抬起，支持双击判定 `isDblClickPending`） | TrackpadContext.isTap/touchUpEvent |
| 1 指拖拽 | "轻点+随后移动"进入 drag（确认后 hold 左键） | touchDownEvent isClickPending→confirmedDrag |
| 双击+按住拖放 | 第二次落指至少停留 120ms 后移动才进入 drag；过早移动按普通光标移动处理 | TrackpadContext.touchMoveEvent |
| 2 指移动 | 垂直/水平自然滚动 `sendMouseHighResScroll/HScroll`（系数 X=1.5/Y=2）+ 惯性 | TrackpadContext.touchMoveEvent actionIndex==1&&pointerCount==2 |
| 2 指轻点(无移动) | 右键 | TrackpadContext.touchUpEvent / getMouseButtonIndex |
| 2 指捏合 | confirm-and-lock 后 Ctrl+高精度滚轮；位置式反向可回缩 | Game.handleTrackpadPinch |
| 3 指左右滑 | Alt 保持 + Tab / Shift+Tab；进入后同一 stroke 内可用 X/Y 选择列/行，横向弧线不换行、两排纵向边界不回绕，抬指确认 | Game.handleTrackpadGestures / TrackpadSelectionAccumulator / TrackpadVerticalIntentGate / TrackpadRowBoundaryGuard |
| 3 指上/下滑 | 无状态打开任务视图 / 显示桌面 | Game.handleTrackpadGesture |
| 3 指轻点 | 快速 Alt+Tab | Game.handleTrackpadGesture |
| 4 指左右滑 | 左滑上一桌面、右滑下一桌面 | TrackpadSwipeGestureDetector / handleFourFingerGesture |
| 4 指上/下滑 | 任务视图 / 显示桌面；下滑后上滑恢复刚隐藏的窗口 | TrackpadGestureConfiguration |
| 4 指轻点 | Windows 通知中心（`Win+N`） | TrackpadSwipeGestureDetector / performTrackpadGestureAction |
| 5 指 | 取消当前多指身份并吞掉剩余 stroke | multiFingerGestureSuppressedUntilUp |

触屏的 `handleMultiTouchGesture` 与物理触控板手势是两条独立路径，不能互相复用状态。
短组合键走 `sendKeys(short[])`；需要跨手势保持的 Alt 和捏合期间保持的 Ctrl 必须显式配对 DOWN/UP，
并在 cancel、失焦、pause、取消输入抓取时兜底释放。

## 2. 当前手势映射表

| 手势 | 检测条件 | 宿主机动作 |
|---|---|---|
| 单指移动 | pointerCount==1 | `sendMouseMove`（已有） |
| 单指轻点 | 抬起 ≤180ms & 每轴≤10px | 左键 |
| 单指双击 | 两次轻点 | 双击左键（已有 isDblClickPending） |
| 拖拽 | 双击后第二次落指停留≥120ms再移动，或按住物理左键移动 | hold 左键+移动 |
| 双指滚动 | 2 指 MOVE，自然方向，确认 scroll 后身份锁定 | `sendMouseHighResScroll/HScroll` |
| 双指轻点 | 2 指≤180ms 且无 scroll | 右键 |
| 双指捏合缩放 | 分离变化达到 `clamp(初始间距×6%, 20, 36px)`、分离主导质心、净点积<0、双指各移动≥10px | Ctrl+高精度滚轮 |
| **三指左/右滑** | 快甩 60px / 慢滑 150px 首次进入；锁定后 X 每 90px 一格，Y 首格 135px、原始累计后续 90px，换行要求 `|Y| >= 0.8 × horizontalTravel` | X 轴保持 Alt 的 Shift+Tab / Tab 并可首尾循环；自然横向弧线不换行，明确纵向/对角线可跨行；两排 Y 轴同方向最多移动一行，反向可返回，水平选择后重新允许换行；抬起任一指确认 |
| **三指上滑** | 快甩 45px / 慢滑 120px，主轴占优 | 无状态发送 Win+Tab；不改变后续三指路由 |
| **三指下滑** | 同上 | 无状态发送 Win+D |
| **三指轻点** | <250ms 且曼哈顿位移<30px，且本 stroke 从未识别过 swipe | Alt+Tab |
| **四指左/右滑** | 与三指相同的速度阈值，独立 centroid detector | 上一/下一桌面 |
| **四指上/下滑** | 与水平滑动共用 detector | Task View / 显示桌面；四指下滑后，上滑用第二次 `Win+D` 恢复窗口 |
| **四指轻点** | <250ms、全程 centroid 曼哈顿位移<30px，且未识别过 swipe | 通知中心（`Win+N`） |
| 5 指 | 任一额外 contact | 取消并吞掉本轮，不触发动作 |

阈值仅为 OPPO 目标机的初值。若以后推广到其它触控板，应优先用硬件 resolution/物理尺寸换算，
而不是增加一套新的固定 px 常数。

## 3. 当前实现结构

1. **分层路由**：只有 `SOURCE_TOUCHPAD + TOOL_TYPE_FINGER` 进入物理触控板路径；先处理三/四指，
   再处理捏合，最后才落到原有 `TrackpadContext[2]`。多指手势无需扩容 context，因为事件被完整消费。
2. **三指 stroke**：exactly 3 指才计算 centroid。当前 stroke 的方向/触发坐标与跨 stroke 的
   冷却方向/时间分开；识别 swipe 后禁用 tap，反向越过 50px 可重新武装。第 4 指会结束三指 stroke 并建立
   独立四指身份；第 5 指才取消并隔离到全部抬起。四指抬起后的 4→3→2 尾部同样整段吞掉。
3. **二指身份与延迟路由**：以 pointer ID 绑定两根手指；累计 separation、centroid、dot product 与两指 travel。
   未决事件最多保留 300ms/48 个 `MotionEvent`，并处理每个 historical sample。Pinch 三信号满足后锁定为 zoom
   并丢弃缓冲；同向移动确认 scroll 后按原时间戳回放且永不再转 pinch。小幅 tap 抖动仍保留双指右键，
   已形成反向短路径但未过阈值的手势则在抬起时吞掉，避免误变成右键。
4. **尾部/生命周期隔离**：三/四指结束 cancel 两个 context，并设置 200ms guard；`ACTION_CANCEL`、
   新的异常 DOWN、窗口失焦、pause、ungrab 都走配对清理，不能留下鼠标键或 Alt/Ctrl。待释放 tap
   显式保存实际按钮值，pointer count 改变后仍能正确释放双指右键；焦点恢复若直接收到 MOVE，则首帧只用于
   重建 1/2 指 context，下一帧恢复正常相对移动。
5. **移动手感**：`TrackpadContext` 按 px/ms 计算光标分段加速；单指抬起立即停止，不再进入 flick。
   二指内容滚动使用 30ms 时间型 EMA，并以 0.98/10ms 摩擦按实际 elapsed time 指数换算；它不使用
   光标加速，副轴达到主轴 35% 时保留双轴斜滚。新接触调用两个 context 的 `stopFling()`，但不清
   pending tap。默认 X/Y 保持 100%；OPPO 双屏环境实测 80% 更舒适。现有实现也会用该灵敏度缩放二指滚动。
6. **宿主注入**：瞬时 Win 组合键走 `sendKeys()`；Alt+Tab 与 pinch Ctrl 因需跨事件保持而显式发送
   DOWN/UP。三指首次水平滑动后，两套独立 `TrackpadSelectionAccumulator` 量化后续位移：X 每 90px
   发送 Tab/Shift+Tab 保留 MRU 循环；Y 首格需 135px，确认纵向意图后继续按 90px 累计。
   `TrackpadVerticalIntentGate` 同时累计纵向净位移与横向轨迹，要求 `|Y| >= 0.8 × horizontalTravel`：X 占优的
   弧形横扫会 rebase 尚未确认的 Y，明确纵向和接近对角线仍可换行。同一 MOVE 因而仍能产生行列步进，但不会
   仅因长横扫的自然弧度误换行。由于 Windows Alt+Tab 的 Up/Down 会在边界回绕，`TrackpadRowBoundaryGuard`
   再将同方向纵向序列限制为一格，反向立即放行；水平步进可能跨到另一行，因此会重置该保护。抬起任一指
   立即释放 Alt 确认，15s 仅作漏失 UP/CANCEL 的后备释放。
   Task View 仅由 Win+Tab 打开，不在客户端维护远端页面状态。输入协议不回传远端光标、前台窗口或显示器拓扑，
   Alt+Tab 包也不能指定呈现屏幕。Windows 11 24H2 实测即使前台窗口和光标都在非主屏，Alt+Tab 浮层仍固定
   显示在主屏；Win+Tab 虽会铺到所有屏，但网格导航语义不同。因此客户端不得用隐式点击冒充聚焦，也不能
   在未验证手感前静默替换选择器。协议同样不回传 Alt+Tab 的窗口数、网格行数或当前行，所以当前纵向
   保护明确针对 OPPO 实测的常见两排布局；三排以上若要精确钳制，需要宿主端状态通道。

当前有意不做的扩大项：通用 `handleTouchInput()` 仍有历史 index→context 假设；只有拿到 OPPO
index 重排复现后，才对物理触控板增加 context↔pointer ID 绑定，避免无证据地改动共用触屏路径。

三指上滑遵循 Windows 默认语义，只发送 Win+Tab 打开 overview；不保存“任务视图已打开”状态，也不把后续
三指左右/上下改发成方向键。三指左右始终属于 Alt+Tab，只有先由水平动作进入 Alt+Tab 的同一次接触中，
后续纵向位移才用于跨网格行。这两套语义由手势起始方向区分，不依赖远端 UI 状态。

## 4. Mac / Windows Precision Touchpad 对标结论

开源 Windows 驱动 [`imbushuo/mac-precision-touchpad`](https://github.com/imbushuo/mac-precision-touchpad)
值得学习的不是一套“Mac 阈值”：它把 Apple 触点转换成包含 Contact ID、X/Y、Tip、Confidence、
Scan Time、Contact Count 和按钮状态的 Windows PTP HID report，手势识别由 Windows 完成。
Apple 的应用层契约同样强调 gesture phase，以及彼此分离的 finger-scroll phase / momentum phase，
而没有公开指针加速、轻点或惯性常数。

| 路径 | 触点仲裁者 | 结论 |
|---|---|---|
| Mac 硬件 → `mac-precision-touchpad` → Windows PTP | Windows | 驱动忠实上报触点帧，不复刻 macOS 手势算法 |
| Android `MotionEvent` → Artemis → Moonlight 鼠标/键盘包 | Artemis | 当前可落地路径；必须在发送不可撤销滚轮前完成意图仲裁 |
| Android 原始触点帧 → 新串流协议 → Windows PTP 注入 | Windows | 长期最接近原生体验，但需 Apollo/Sunshine、moonlight-common-c 与客户端共同扩展 |

因此本轮只借鉴其架构原则：稳定 contact identity、完整采样、明确 phase、先仲裁再输出，以及新接触终止
momentum；没有把经验阈值包装成 Apple 官方参数。Windows 新 Precision Touchpad Input API 明确包含远程桌面与
synthetic touchpad/action 场景，后续协议验证应优先研究该入口。

## 5. 设置与单一默认语义

当前已有物理触控板 X/Y 灵敏度、绝对鼠标模式、交换轴、拖放振动/阈值等设置。默认 X/Y 继续保持
100%；OPPO 双屏环境可按需调整为已经真机通过的 80%，不为单一设备改变全局默认。绝对模式仍是兼容选项，
不是默认方案，因为它虽然可避免远端再次加速，却可能影响游戏的相对鼠标输入。

不再提供 Windows/Mac 模式选择。唯一默认映射为：三指左右切应用、上滑任务视图、下滑显示桌面；四指
左右切虚拟桌面、上滑任务视图、下滑显示桌面、轻点通知中心。兼容且经过真机验证的触控板机制（phase、
速度相关惯性、落指刹停、反向重武装、二维选窗）直接合入这一默认实现，不为它们增加模式开关。

未来若增加动作编辑器，也必须复用统一 recognizer，不能为每个动作复制状态机。三指拖移、旋转、页面轻扫、
拇指加三指等动作若与现有映射冲突，保持不实现；只有不改变现有语义、能独立验证的改进才直接合入。

Windows“虚拟触控板”只是屏幕上的触控板界面，不是 Artemis 可调用的远端输入协议；系统触控板设置也只有
在宿主收到 PTP contact frame 时才会接管。因此当前设置页必须诚实描述为 Artemis 映射，不能声称由 Windows
原生触控板设置驱动。

当前协议只能注入 `Win+Ctrl+Left/Right`，Artemis 不知道远端虚拟桌面总数与当前索引。Windows 在首尾边界
会忽略越界快捷键，因此不能可靠实现循环；若未来 Apollo/PTP 通道能返回桌面状态，再单独设计循环选项。

## 6. ColorOS 抢占风险

TP-PROBE 已在目标机收到 566 个 pointerCount=3 事件，因此“ColorOS 必然拦截三指触控板”已经被证伪。
当前唯一可信结论是：框架路径可用，但任何一轮仍可能以 `ACTION_CANCEL` 结束，状态机必须正确清理。

Shizuku 只负责键盘 EVIOCGRAB；触控板观察器是未启用死代码，不应为了手势重新启用。
若未来系统更新后日志只见 DOWN/CANCEL，再提供关闭 ColorOS 三指截屏/分屏的排障提示；不要预先要求用户改系统设置。

## 7. 优先级建议

- **P0（完成并真机通过）**：二指延迟仲裁、滚动/捏合锁定、Ctrl/Alt/左键配对释放、三指尾部隔离、失焦恢复。
- **P1（完成并真机通过）**：Windows 默认三指语义、反向重武装、采样率无关加速/惯性、相对模式 80% 对照。
- **P2（代码完成，真机验证中）**：四指虚拟桌面、三指 Alt+Tab 二维定位，以及 Task View 无状态路由。
- **P3（有复现/更多硬件再做）**：context↔pointer ID 绑定、按物理距离归一化、独立指针/滚动速度和跨设备曲线。
- **P4（跨项目）**：Apollo/Sunshine + moonlight-common-c + Artemis 的 PTP contact-frame 通道；预留的系统级 OPPO 过滤器不阻塞本项目。

## 8. 2026-08-11 OPPO 真机结果

- arm64 debug 包在 Android 16 / API 36 正常安装、启动并进入真实串流；系统实际输入能力见文首。
- 直线/斜向滚动样本在 2–9 个缓冲事件、8–68ms 内确认；捕获日志中没有出现 scroll → pinch 误判。
- 张开/收拢捏合在 2–3 个事件、8–16ms 内确认，随后按位置连续输出并在抬手时记录 `pinch end`。
- 三指左/右/上/下、连续反向与 tap 已通过；Task View 无状态路由待本轮回归，4+ 指 suppression、退回三指不误触已通过。
- 调试过程中出现过“事件与 pointer capture 均正常、远端光标仍不动”。根因是使用 `adb monkey` 启动后
  `NvConnection` 检测到 `ActivityManager.isUserAMonkey()==true`，按既有安全逻辑丢弃全部输入；ColorOS 还在
  monkey 退出后残留了 controller 标记。清除标记、确认 `mControllerIsAMonkey=false` 并用显式 Activity
  冷启动后，用户确认单指光标恢复。因此后续真实串流测试不得用 monkey 启动。
- 切出/返回后的光标恢复、按住物理键失焦后的释放、滚动/捏合恢复及 Ctrl 不粘住均已独立回归通过；
  lifecycle reason 日志、全部物理鼠标键释放、缺失 DOWN 的 MOVE 恢复，以及待释放 tap 的真实按钮身份继续作为防御性加固保留。
- 长滚动实测暴露出抬指顺序相关的惯性缺失：`ACTION_POINTER_UP(0)` 没有通知持有滚动速度的 context 1。
  新版在不重复触发二指右键的前提下显式结束 context 1 的滚动并启动 momentum，真机日志已确认该 handoff
  多次执行。二指滚动采用独立的 0.25px/ms 启动阈值；慢速惯性以 0.965/10ms 较快衰减，随速度过渡到
  既有 0.98/10ms 快速曲线。慢滚短尾、快甩继续远滑、新接触立即刹停均已主观通过。
- 原始相对模式 100% 在双屏环境中过快，连续画圆在方向切换处显得卡；X/Y 80% 后大/小圆、“8”字、
  小目标微调、快速反向与跨屏速度均通过，滚动和缩放无回归。默认仍为 100%，80% 作为目标设备可选值。
- 目标设备的 `MotionEvent` 本轮 `historySize=0`；历史采样支持保留用于其它驱动，不能据此假定所有设备都不批处理。

以上同时包含日志证据和用户在远端 Windows 的主观结果。后续改动仍须重复 handoff note 中的真机脚本，
不能只以单元测试或事件日志作为完成依据。
