# Artemis 触控板完整手势体系设计（调研 + 方案）

> **实现状态（2026-08-11）**：捏合、三指滑、任务视图导航和第二轮 Mac/PTP 对标加固已落地；
> 最新 arm64 debug 包已在 OPPO Pad 4 Pro（Android 16 / API 36）安装并进入真实串流；单指远端光标移动已验证通过，正在继续验证组合手势手感。
> 执行清单、实测记录与官方资料链接见 `trackpad-handoff-note.md`。
> 当前实现要点：
> - 捏合判别器升级为 **ambiguous → confirm-and-lock 状态机**：未决事件（含 historical samples）先缓冲，
>   确认滚动后按原时间戳回放，确认捏合后丢弃，避免远端出现不可撤销的“先滚后缩”；
> - 三指状态机改为"每次 DOWN 武装、触发时按方向门控"：同向冷却吞掉、反向立即触发、
>   中途反向重武装、快甩小阈值（60/45px）、一次 stroke 至多一次同向触发；
> - 三指 stroke 结束时 cancel 两个 TrackpadContext 槽 + 200ms 捏合守卫（尾部隔离），
>   TrackpadContext.touchDownEvent 增加 cancelled 守卫（修幽灵右键）；
> - 当前 stroke 方向与跨 stroke 冷却分离；4+ 指取消并隔离；捏合按 pointer ID 跟踪；
> - 任务视图导航键有 350ms 展开动画缓冲（最多 4 键 FIFO），超时 8s；
> - 光标加速、速度 EMA 与惯性按 elapsed time 计算；二指滚动保持线性并支持有意的斜向双轴；
>   失焦/取消抓取立即释放合成修饰键和鼠标键，捕获恢复若缺失 DOWN 可由首个 MOVE 重新 seed。

目标设备：OPPO Pad 4 Pro 官方磁吸键盘触控板。ADB 实测可见 `event10 pogo_touchpad`
（5 contacts、`INPUT_PROP_BUTTONPAD`、约 120.2×70.9mm）以及 `event13 OPPO Pad 4 Pro Keyboard Touchpad`
（10 contacts；其 resolution 元数据明显不代表真实毫米尺寸）。
主事件链：`Game.onTouch` / `onGenericMotionEvent` → `handleMotionEvent` → `SOURCE_TOUCHPAD` finger 分支 → `handleTrackpadGestures` → `handleTrackpadPinch` → `handleTouchInput(event, trackpadContextMap, false)`。

## 1. 现有手势能力清单

`trackpadContextMap` = `TouchContext[2]`（仅 2 槽）。1/2 指基础行为在 `TrackpadContext`，
捏合和 3 指以上的路由在 `Game`：

| 指数/动作 | 当前宿主机行为 | 代码位置 |
|---|---|---|
| 1 指移动 | 相对移动光标 `sendMouseMove`（含加速/惯性 flick） | TrackpadContext.touchMoveEvent pointerCount==1 |
| 1 指轻点(≤180ms,≤10px/轴) | 左键点击（按下延时 180ms 抬起，支持双击判定 `isDblClickPending`） | TrackpadContext.isTap/touchUpEvent |
| 1 指拖拽 | "轻点+随后移动"进入 drag（确认后 hold 左键） | touchDownEvent isClickPending→confirmedDrag |
| 双击+按住拖放 | 第二次落指至少停留 120ms 后移动才进入 drag；过早移动按普通光标移动处理 | TrackpadContext.touchMoveEvent |
| 2 指移动 | 垂直/水平自然滚动 `sendMouseHighResScroll/HScroll`（系数 X=1.5/Y=2）+ 惯性 | TrackpadContext.touchMoveEvent actionIndex==1&&pointerCount==2 |
| 2 指轻点(无移动) | 右键 | TrackpadContext.touchUpEvent / getMouseButtonIndex |
| 2 指捏合 | confirm-and-lock 后 Ctrl+高精度滚轮；位置式反向可回缩 | Game.handleTrackpadPinch |
| 3 指左右滑 | Alt 保持 + Tab / Shift+Tab，抬手尾部释放 Alt | Game.handleTrackpadGestures / handleTrackpadGesture |
| 3 指上/下滑 | 任务视图 / 显示桌面；任务视图内四向导航 | Game.handleTrackpadGesture |
| 3 指轻点 | 快速 Alt+Tab；任务视图内 Enter 选择 | Game.handleTrackpadGesture |
| 4/5 指 | 取消当前三指身份并吞掉剩余 stroke，不映射动作 | gesture3SuppressedUntilUp |

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
| **三指左/右滑** | 快甩 60px / 慢滑 150px，主轴占优 | 保持 Alt 的 Shift+Tab / Tab，切换打开的应用 |
| **三指上滑** | 快甩 45px / 慢滑 120px，主轴占优 | Win+Tab；已在任务视图时发送 Up |
| **三指下滑** | 同上 | Win+D；已在任务视图时发送 Down |
| **三指轻点** | <250ms 且曼哈顿位移<30px，且本 stroke 从未识别过 swipe | Alt+Tab；任务视图内 Enter |
| 任务视图内三指左右滑 | 同三指水平判定 | Left/Right，350ms 动画窗 FIFO 缓冲 |
| 4/5 指 | 任一额外 contact | 取消并吞掉本轮，不触发动作 |

阈值仅为 OPPO 目标机的初值。若以后推广到其它触控板，应优先用硬件 resolution/物理尺寸换算，
而不是增加一套新的固定 px 常数。

## 3. 当前实现结构

1. **分层路由**：只有 `SOURCE_TOUCHPAD + TOOL_TYPE_FINGER` 进入物理触控板路径；先处理三指，
   再处理捏合，最后才落到原有 `TrackpadContext[2]`。三指无需扩容 context，因为其事件被完整消费。
2. **三指 stroke**：exactly 3 指才计算 centroid。当前 stroke 的方向/触发坐标与跨 stroke 的
   冷却方向/时间分开；识别 swipe 后禁用 tap，反向越过 50px 可重新武装。第 4 指取消身份并隔离到全部抬起。
3. **二指身份与延迟路由**：以 pointer ID 绑定两根手指；累计 separation、centroid、dot product 与两指 travel。
   未决事件最多保留 300ms/48 个 `MotionEvent`，并处理每个 historical sample。Pinch 三信号满足后锁定为 zoom
   并丢弃缓冲；同向移动确认 scroll 后按原时间戳回放且永不再转 pinch。小幅 tap 抖动仍保留双指右键，
   已形成反向短路径但未过阈值的手势则在抬起时吞掉，避免误变成右键。
4. **尾部/生命周期隔离**：三指结束 cancel 两个 context，并设置 200ms guard；`ACTION_CANCEL`、
   新的异常 DOWN、窗口失焦、pause、ungrab 都走配对清理，不能留下鼠标键或 Alt/Ctrl。待释放 tap
   显式保存实际按钮值，pointer count 改变后仍能正确释放双指右键；焦点恢复若直接收到 MOVE，则首帧只用于
   重建 1/2 指 context，下一帧恢复正常相对移动。
5. **移动手感**：`TrackpadContext` 按 px/ms 计算光标分段加速；30ms 时间型 EMA 速度驱动 flick；
   0.98/10ms 摩擦按实际 elapsed time 指数换算。二指内容滚动不使用光标加速，副轴达到主轴 35% 时
   保留双轴斜滚。新接触调用两个 context 的 `stopFling()`，但不清 pending tap。
6. **宿主注入**：瞬时 Win 组合键走 `sendKeys()`；Alt+Tab 与 pinch Ctrl 因需跨事件保持而显式发送
   DOWN/UP。Task View 动画窗内用有界 FIFO 保序，切换模式时清空。

当前有意不做的扩大项：通用 `handleTouchInput()` 仍有历史 index→context 假设；只有拿到 OPPO
index 重排复现后，才对物理触控板增加 context↔pointer ID 绑定，避免无证据地改动共用触屏路径。

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

## 5. 设置项改动清单

当前版本没有增加设置项，目标机先用一套固定行为完成验证。确认默认手感后再决定是否把以下能力公开，
避免在算法尚未稳定时永久背负参数兼容：

- `checkbox_trackpad_gestures_enable`(总开关) + 子项
- `checkbox_trackpad_3f_swipe`(默认 true)、`checkbox_trackpad_3f_tap_alttab`(默认 true)
- `list_trackpad_3f_up`(任务视图/显示桌面/关闭)、`list_trackpad_3f_down`
- `checkbox_trackpad_pinch_zoom`(默认 true)
- `seekbar_trackpad_gesture_sensitivity`(阈值倍率)

若落地，对应 `PreferenceConfiguration.java` 新增字段/读取，`strings.xml` 至少补英文默认值。

## 6. ColorOS 抢占风险

TP-PROBE 已在目标机收到 566 个 pointerCount=3 事件，因此“ColorOS 必然拦截三指触控板”已经被证伪。
当前唯一可信结论是：框架路径可用，但任何一轮仍可能以 `ACTION_CANCEL` 结束，状态机必须正确清理。

Shizuku 只负责键盘 EVIOCGRAB；触控板观察器是未启用死代码，不应为了手势重新启用。
若未来系统更新后日志只见 DOWN/CANCEL，再提供关闭 ColorOS 三指截屏/分屏的排障提示；不要预先要求用户改系统设置。

## 7. 优先级建议

- **P0（代码完成，真机验证中）**：二指延迟仲裁、滚动/捏合锁定、Ctrl/Alt/左键配对释放、三指尾部隔离。
- **P1（代码完成，真机验证中）**：Windows 默认三指语义、反向重武装、任务视图 FIFO、采样率无关加速/惯性。
- **P2（有复现再做）**：若通用 1/2 指路径出现 pointer index 重排，增加 context↔pointer ID 绑定；按日志调动态阈值与确认窗。
- **P3（跨项目设计）**：原生 PTP contact-frame 串流通道；四指虚拟桌面、边缘手势、映射/灵敏度设置项。

实测优先级：捏合慢速滚动误触是否根除、快甩阈值是否过灵敏（TP3_SWIPE_*_FAST）、
三指快速连滑跟手度（TP3_COOLDOWN_MS=250 同向门控）、任务视图 350ms 缓冲是否足够，
以及加速曲线在慢速精准定位和快速跨屏之间是否自然。详细动作顺序见 handoff note。

## 8. 2026-08-11 首轮 OPPO 真机结果

- arm64 debug 包在 Android 16 / API 36 正常安装、启动并进入真实串流；系统实际输入能力见文首。
- 直线/斜向滚动样本在 2–9 个缓冲事件、8–68ms 内确认；捕获日志中没有出现 scroll → pinch 误判。
- 张开/收拢捏合在 2–3 个事件、8–16ms 内确认，随后按位置连续输出并在抬手时记录 `pinch end`。
- 三指左/右/上/下与 tap 均有成功注入日志；四指 suppression 尚未取得有效日志证据，不能标为通过。
- 调试过程中出现过“事件与 pointer capture 均正常、远端光标仍不动”。根因是使用 `adb monkey` 启动后
  `NvConnection` 检测到 `ActivityManager.isUserAMonkey()==true`，按既有安全逻辑丢弃全部输入；ColorOS 还在
  monkey 退出后残留了 controller 标记。清除标记、确认 `mControllerIsAMonkey=false` 并用显式 Activity
  冷启动后，用户确认单指光标恢复。因此后续真实串流测试不得用 monkey 启动。
- lifecycle reason 日志、全部物理鼠标键释放、缺失 DOWN 的 MOVE 恢复，以及待释放 tap 的真实按钮身份
  仍作为防御性加固保留；“切出/返回”场景需与 monkey 问题分开做独立回归。
- 长滚动实测暴露出抬指顺序相关的惯性缺失：`ACTION_POINTER_UP(0)` 没有通知持有滚动速度的 context 1。
  新版在不重复触发二指右键的前提下显式结束 context 1 的滚动并启动 momentum，真机日志已确认该 handoff
  多次执行。二指滚动采用独立的 0.25px/ms 启动阈值；慢速惯性以 0.965/10ms 较快衰减，随速度过渡到
  既有 0.98/10ms 快速曲线。这样慢滚只有短尾、快甩继续远滑，最终主观手感仍待确认。

这些是事件链与状态机证据，不等于主观手感通过。滚动突跳、缩放增益、惯性和任务视图动画仍以用户在
远端 Windows 看到的行为为准。
