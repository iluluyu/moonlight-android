# 触控板工作交接 Note

> 状态：**稳定版核心 1/2/3/4 指与多指尾部抑制均已完成 OPPO 真实串流回归；唯一 Windows 语义下的三指 Alt+Tab 二维定位、弧线抑制与两排边界保护均已通过**（2026-08-11）。

## 目标
OPPO Pad 4 Pro 官方磁吸键盘触控板 → 远程 Windows 主机，接近 Mac 触控板手感。

## 已完成 ✅
1. **轻点卡键修复**：tap 后延迟抬起与双击拖拽竞态导致左键卡住 → 已修复（显式 leftButtonDown 跟踪 + 放弃路径补发 UP）
2. **拖拽修复**：手指1按住 + 手指2移动可拖拽（primaryButtonDown 全局标志）
3. **三指手势**（Game 框架路径直接检测）：
   - 左右滑 = Alt+Tab 切应用（手势驱动切换器，3s 安全兜底防卡）
   - 上滑 = 打开任务视图（仅发送 Win+Tab，不接管后续三指手势）
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
8. **历史方案：任务视图导航缓冲（已撤销）**（2026-08）：曾在 Win+Tab 展开后维护本地导航模式并缓冲方向键；由于串流协议不回传远端 Task View 是否仍打开，该方案会产生状态泄漏，已由第 25 项的无状态语义取代。
9. **三指跨 stroke 状态隔离**（2026-08-11）：全局同向冷却与当前 stroke 方向分开保存；冷却吞掉的 stroke 也更新自己的触发坐标，不再拿上一次落点判断反向；已经滑动后只反向 50px 又抬手不会误判成三指轻点。
10. **指针身份/指头数量加固**（2026-08-11）：捏合的两根手指用稳定 pointer ID 跟踪，index 重排不会制造巨大假位移；第 4/5 指落下会取消三指手势并隔离到全部抬起，不再任取“前 3 指”继续触发。
11. **采样率无关的移动与惯性**（2026-08-11）：光标加速改按 px/ms 而非单帧 px，速度 EMA 也改按 elapsed time/30ms 时常计算；60/120/240Hz 下同速手指得到一致响应。二指内容滚动不再套用光标加速，斜滚允许超过主轴 35% 的副轴，惯性按真实 elapsed time 指数衰减。新一轮触摸会停止两个 context 中任一方持有的惯性，但保留双击窗口。
12. **失焦安全释放**（2026-08-11）：窗口失焦、Activity pause、取消输入抓取时立即终止手势，释放合成 Alt/Ctrl、物理左键与 TrackpadContext 状态，避免等到 `onDestroy()` 才清理导致远端卡键。
13. **历史方案：任务视图导航保序（已撤销）**（2026-08-11）：曾用最多 4 键 FIFO 保留动画窗内的方向键；用户确认三指上滑只需打开页面，因此该队列及其本地模式已整体删除。
14. **Mac / Windows PTP 架构对标**（2026-08-11）：确认 Apple 的手势 phase / momentum phase、AOSP 的 ambiguous gesture 抑制规则，以及开源 `mac-precision-touchpad` 的原始 contact frame → Windows PTP 路径；本轮代码采用相同的“采集、仲裁、输出”分层，但受串流协议限制仍在客户端合成鼠标/键盘。
15. **OPPO Pad 4 Pro 首轮真机数据**（2026-08-11）：arm64 debug 包已在 Android 16 / API 36 安装并进入真实串流。ADB 确认 `event10 pogo_touchpad` 支持 5 contacts、`INPUT_PROP_BUTTONPAD`、23 units/mm（约 120.2×70.9mm），另有 `event13 OPPO Pad 4 Pro Keyboard Touchpad` 10-contact 框架节点。首轮日志中滚动在 2–9 个缓冲事件、8–68ms 内确认；捏合在 2–3 个事件、8–16ms 内确认，未见滚动被误判为捏合；三指四向与轻点均有成功 `fire`/`tap` 记录。
16. **切出串流后的输入恢复加固**（2026-08-11）：首轮测试中用户报告远端光标停止，但原始 `pogo_touchpad` 坐标仍以约 120Hz 到达，系统也仍显示 pointer capture。焦点/Activity/input-grab 清理现在记录明确 reason，释放所有已知物理鼠标键；若 Android 恢复捕获时直接从 MOVE 续流而没有新 DOWN，会用首帧重新 seed 1/2 指 context。另修复待释放 tap 按钮身份：双指右键或多指按钮不再因 pointer count 变化而被错误地按左键释放。
17. **ADB 测试启动陷阱已定位**（2026-08-11）：连续出现的“事件正常但远端光标不动”并非触控板或输入协议故障，而是调试包曾由 `adb monkey` 启动。`NvConnection` 会在构造时读取 `ActivityManager.isUserAMonkey()`，为 true 时有意静默丢弃鼠标、键盘和滚轮输入；ColorOS 在 monkey 进程结束后还残留了 `mControllerIsAMonkey=true`。通过 Activity monitor 覆盖该状态、确认其为 false，再以 `am start` 冷启动并重进串流后，用户确认单指远端光标恢复移动。
18. **滚动惯性与抬指顺序解耦**（2026-08-11，真机通过）：用户实测发现长距离上下滚动抬手后会突然停止。对应日志显示该轮以 `ACTION_POINTER_UP(0)` 结束，而二指滚动速度保存在 context index 1；旧路由只向 action index 0 发送 `touchUpEvent()`，导致惯性拥有者永远收不到结束信号。现在主指先抬时会显式调用 `TrackpadContext.finishScrollGesture()`，只启动 context 1 的滚动惯性，不重复运行二指 tap/右键逻辑；实测已多次出现 `scroll momentum handoff`。滚动启动阈值与光标甩动拆分为 0.25 / 0.8px/ms，慢滚从 0.965/10ms 的较强阻尼起步并随速度平滑过渡到 0.98/10ms，达到 1px/ms 后保持原快速曲线。抬手前停顿 50ms 仍会衰减到零，不产生漂移；慢滚短尾、快滚长惯性及新落指立即刹停均已通过。
19. **指针速度 A/B 与完整手感回归**（2026-08-11，真机通过）：ColorOS 已为物理触控板提供默认加速曲线，Artemis 相对移动后远端 Windows 还可能再次加速；100% 时用户反馈画圆在四个方向切换处略卡、跨双屏略快。保持相对模式和全部算法不变，只把 X/Y 调为 80% 后，慢速大/小圆、连续“8”字、小目标微调与自然跨屏均通过；快速反向抬手立即停止，慢/快滚动、速度相关惯性、落指刹停和捏合也无回归。差异不影响默认可用性，因此默认仍保持 100%，80% 只作为 OPPO 双屏环境的可选舒适值；绝对模式不进入默认路径。
20. **四指默认映射实现**（2026-08-11，真机通过）：四指使用独立、可单测的 centroid swipe/tap detector，支持一次 stroke 一次触发与中途反向；反向以首次动作后的最远点为转折点，并保留用于确认反向的首段位移。唯一默认映射采用直接方向：右滑下一桌面、左滑上一桌面；上/下映射 Task View/显示桌面，若刚由四指下滑发送 `Win+D`，随后上滑会再次发送 `Win+D` 恢复窗口，重复下滑不会反复切换。<250ms 且全程位移<30px 的轻点映射 Windows 通知中心。4→3→2 尾部整段隔离，第 5 指取消整轮。连续 stroke、同一 stroke 反向、任务视图、显示/恢复桌面和多指尾部均已在真实串流中验证。桌面边界由 Windows 快捷键决定且不循环；当前协议无法读取远端桌面数量/索引，不能可靠补循环。
21. **三指应用连续定位**（2026-08-11，真机通过）：Alt+Tab 本身可首尾循环，不需要宿主应用计数。首次水平快/慢阈值进入选择器并移动一格；保持三指接触时，独立 `TrackpadSelectionAccumulator` 将水平位移每 90px 转成一个 Tab/Shift+Tab 步进，可在同一 stroke 内左右反向定位。抬起任一指立即释放 Alt 确认；15s 只作为 Android 漏失 UP/CANCEL 时的防卡死后备。完整新 stroke 会重置跨 stroke 冷却，因此明确的下一次滑动不会被旧动作吞掉。OPPO 真机反馈连续定位、反向与抬指确认的结果和手感均符合预期。
22. **Alt+Tab 多显示器位置差异**（2026-08-11，宿主限制已确认）：真实串流中远端光标位于 B 屏时，Windows Alt+Tab 选择器仍出现在主屏 A。主机为 Windows 11 24H2 build 26100.2161；只读 Win32 探针确认光标和前台窗口都位于非主屏 `DISPLAY2`，随后直接注入 Alt+Tab 并截图，浮层依然只画在主屏，排除了 Artemis 焦点丢失。相同条件下 Win+Tab 会在所有显示器呈现 Task View，但其网格导航和 Alt+Tab 的线性循环语义不同，不能未经手感验证直接替换。现有 Moonlight 输入协议只向宿主发送相对/绝对鼠标、键盘、滚轮等输入事件，不回传 Windows 的真实光标坐标、前台窗口或显示器拓扑，也没有为 Alt+Tab 指定显示器的字段。客户端不通过合成左键点击抢焦点；若 Task View 不能满足需求，真正修复需可选宿主端 companion/协议扩展来实现按光标显示器枚举、呈现和激活窗口。
23. **三指 Alt+Tab 二维网格定位**（2026-08-11，首轮方向通过）：保留已经通过的水平 MRU 循环和 90px 步距；水平首次进入 Alt+Tab 后，再启用一套独立的纵向 `TrackpadSelectionAccumulator`。保持三指接触时，X 轴继续发送 Tab/Shift+Tab，Y 轴发送 Up/Down 跨 Windows Alt+Tab 网格的上一行/下一行；斜向移动可在同一采样同时产生行列步进。初始纯三指上/下仍分别是 Task View/显示桌面，不改变原映射；抬指仍立即释放 Alt 确认。纵向首格阈值后来由第 27 项调整为 135px，后续原始累计步距仍为 90px，并由第 28 项约束两排边界。
24. **三指快速重触修复**（2026-08-11，待真机）：失败日志显示旧 stroke 在 `18:18:47.065` 抬一指确认，2ms 后又由 `ACTION_POINTER_DOWN` 凑成三指并记录 `stroke start`，但直到抬起都没有 `fire`。这不是远端输入或 Alt 卡键；新 identity 落在旧 200ms 尾部窗内，且顺序落指/稳定接触会耗掉原本从第三指 DOWN 开始计算的快滑窗口。现在任何新三指 identity 都重置跨 stroke 方向冷却；若它在尾部窗内由两根保留手指快速补回第三指形成，则清除旧尾部 guard，并让该轮在首次识别前持续使用 60/45px 快滑阈值。新增 `stroke ended without fire dx/dy/t` 日志，用于区分阈值未过与动作注入失败。
25. **三指上滑后的状态泄漏修复**（2026-08-11，待真机）：日志确认后续三指上/左手势都已正常识别并出现 `fire`，但在先前 Win+Tab 设置的 8s `taskViewMode` 内被改发成 Up/Left；每次误路由还会续期，用户因而感知为“一段时间所有三指手势失效”。按 Windows 默认语义简化为无状态动作：三指上只发送一次 Win+Tab；三指左右始终进入已有 Alt+Tab 连续选择；三指下始终发送 Win+D。删除任务视图四向/轻点导航、350ms FIFO、8s 超时及全部本地 Task View 状态，远端页面是否关闭不再影响后续手势。
26. **键盘捕获设置合并**（2026-08-11，待真机）：删除独立的“当前使用的权限”只读项，把所选方式和实际生效/回退状态合并到“键盘捕获方式”的摘要。默认改为 Shizuku，并把旧 `auto` 值迁移为 Shizuku；由于两者原先运行路径完全相同，界面只保留 Shizuku（推荐）、无障碍服务、关闭三项。选择 Shizuku 时若尚未授权会发起权限请求，返回设置页后刷新实际状态。
27. **Alt+Tab 纵向首格防误触**（2026-08-11，真机通过）：日志确认水平连续定位期间确有多次 `rows=1` 注入，并非主观错觉。旧逻辑从水平动作触发点开始无条件累计 Y，首格与连续换行都只需 90px，长时间左右移动的缓慢斜向漂移也会越过阈值。现在仅把纵向第一格提高到 135px；一旦确认明确的上下意图，后续及反向仍保持 90px。水平步距、二维斜向能力和不抬手选择均不变。
28. **Alt+Tab 两排纵向防循环**（2026-08-11，真机通过）：真实日志出现同一次长上滑连续多个 `rows=-1`、长下滑连续多个 `rows=1`；Windows 的方向键会在 Alt+Tab 网格上下边界首尾回绕，因此两排时“上、上”又落回下排。新增独立 `TrackpadRowBoundaryGuard`：一次同方向纵向意图最多注入一格，再次同向步进被抑制；反向可立即返回，水平 Tab/Shift+Tab 发生后因选择可能跨行而重新允许一次纵向步进。单个批量 MOVE 也不会一次穿过两排，日志用 `rowBoundarySuppressed=N` 标明被拦截的步数。Moonlight 协议不回传远端窗口数量、网格行数或当前行，所以这是针对当前常见两排布局的输入语义保护，而不是对远端网格状态的伪同步。
29. **Alt+Tab 横向弧线不再误换行**（2026-08-11，真机通过）：用户反馈自然左右扫动是弧形轨迹，却会切到上/下排；真机日志确认典型横扫约 700–800px 时伴随约 200px 的 Y 弧度，旧实现因 X/Y 完全独立累计而反复注入 `rows=±1`。新增 `TrackpadVerticalIntentGate`：按每段纵向位移与同期累计横向轨迹判断意图，只有 `|Y| >= 0.8 × horizontalTravel` 才允许换行。横向步进发生且 X 占优时，通过 `TrackpadSelectionAccumulator.rebase()` 丢弃未确认的 Y 弧度但保留首格/后续格迟滞状态；即使中间没有水平步进，Y 达阈值时仍会再检查方向比例。明确的纵向和接近对角线移动继续支持行列组合。调试日志以 `rowIntentRebased` 标识弧度重置，以 `rowIntentSuppressed=N` 标识阈值处被拒绝的纵向步进。用户最终确认自然弧线、纵向/斜向选择均“十分跟手”。

## 未完成 ❌
- **多显示器选择器定位（低优先级）**：Alt+Tab 与 Task View 的选择焦点都仍由 Windows 放在主屏，用户确认当前并不重要。保留宿主行为；若未来重新提高优先级，再设计可选宿主端 companion，不能在 Artemis 中用隐式点击抢焦点。
- **自定义动作产品化**：当前保持单一默认语义；未来若设计动作编辑器，三指拖移、旋转、页面轻扫等不得直接硬编码进 `Game`。
- **跨设备标定**：80% 与现有 px 阈值只在 OPPO Pad 4 Pro 上完成主观验证。推广前应至少增加一种不同 resolution/采样率的 Android 触控板，并考虑按物理距离归一化。
- **指针与滚动设置语义**：现有 X/Y 灵敏度同时缩放单指移动与二指滚动。此次 80% 组合已真机通过，因此暂不拆开；设置重构时应评估独立的“指针速度”和“滚动速度”，并提供迁移策略。
- **TrackpadContext 的历史 index 架构**：捏合判别器已改用 pointer ID，但通用 1/2 指 `handleTouchInput()` 仍按 context index 取坐标。OPPO 驱动若实测会在同一 gesture 内重排 index，需再做 context↔pointer ID 绑定；当前不要在无复现时扩大触屏输入改动面。
- **原生 PTP 串流通道**：新版 Windows 已提供面向远程桌面等场景的触控板输入/注入 API，但当前 moonlight-common-c 只有鼠标、高精度滚轮、触屏/笔和控制器触控板事件，没有“系统 Precision Touchpad contact frame”包。若要真正让 Windows 接管全部手势，需要 Apollo/Sunshine + moonlight-common-c + Artemis 协议协同扩展，本轮未实现。
- 若后续出现快甩过灵敏、滚动确认迟钝或捏合误判，优先用 `ArtemisTP` 日志和真机复现调整，不要把经验参数称为 Apple/macOS 官方参数。

## 独立系统级项目边界

- 公共仓库 [`iluluyu/oppo-pad4-trackpad`](https://github.com/iluluyu/oppo-pad4-trackpad) 目前仅预留，保持空仓库；系统级过滤器不是 Artemis 当前关键路径。
- 已验证 Shizuku UserService 的 shell 身份具备目标机 `/dev/input/event10` 和 `/dev/uinput` 读写条件，未来可探索 `EVIOCGRAB → 手势识别 → uinput`，以替换 ColorOS 三指截图等系统行为；实现必须先有 watchdog/紧急释放，避免抓取后输入失联。
- 该项目未来可以复用 Artemis 已验证的纯识别状态机、阈值调研与测试脚本，但不应成为 Artemis 的 Gradle 依赖，也不应把系统权限/设备专用代码放回本仓库。

## 关键事实（重要，勿再走弯路）
- **三指事件能到达 app**（TP-PROBE 证实 566 个 pc=3 事件；早期"ColorOS 拦截"结论是错的）
- **硬件没有在“远端光标不动”时掉线**：`event10` 仍持续产生完整 tracking/down/move/up；本轮冻结的直接原因是 monkey 自动化保护，而不是 OPPO 驱动、pointer capture 或远端输入协议
- **真机串流测试禁止用 `adb monkey` 启动 Artemis**：它会让 `NvConnection` 丢弃全部远端输入；安装后用显式 Activity 启动，并先确认 `mControllerIsAMonkey=false`
- **框架 `getClassification()` 在本机恒返回 NONE**（分类不可用，需自研判别）
- 串流场景 OS 级手势**无原生通道**，只能发快捷键；手感靠参数（动量/时序/曲线）
- 触控板手势已从 Shizuku 移到 Game 框架路径；Shizuku 仅保留键盘抓取（稳定）
- `ShizukuInputService.startTrackpadReader` / `nativeOpenTrackpadObserver` 是死代码（evdev_grab.c 过滤 EV_ABS 设备），未启用勿复活
- 三指与四指只对各自 exactly-N contacts 计算质心；四指有独立 detector，第 5 指会取消并隔离当前 stroke
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
- Apple 的 Mission Control 用户指南进一步明确：三指上滑进入 Mission Control、下滑离开；从第二显示器进入时，只展示该显示器正在使用的窗口与空间。显示桌面则是拇指加三指展开，不与 Mission Control 的下滑退出混用。来源：[View open windows and spaces in Mission Control](https://support.apple.com/guide/mac-help/view-open-windows-spaces-mission-control-mh35798/mac)。
- Apple 的开发者文档进一步表明：驱动先识别 gesture sequence，应用收到 begin/change/end/cancel phase；滚轮事件把手指滚动 phase 与 momentum phase 分开，缩放以连续相对 magnification 发送。Scroll/swipe 一旦真正开始即锁定，但 magnify/rotate 在一个多点触摸序列中仍可能切换。来源：[Handling Trackpad Events](https://developer.apple.com/library/archive/documentation/Cocoa/Conceptual/EventOverview/HandlingTouchEvents/HandlingTouchEvents.html)。
- 开源 Windows 驱动 [`imbushuo/mac-precision-touchpad`](https://github.com/imbushuo/mac-precision-touchpad) 没有复制一套 macOS 手势阈值；它把 Apple 原始触点转换成含 Contact ID、X/Y、Tip、Confidence、Scan Time、Contact Count 与按钮状态的 HID PTP report，再让 Windows 决定 mousing/action/gesture。对应实现见 [`InputInterrupt.c`](https://github.com/imbushuo/mac-precision-touchpad/blob/master/src/AmtPtpDeviceUsbUm/InputInterrupt.c)，字段语义与 [Windows Precision Touchpad Collection](https://learn.microsoft.com/en-us/windows-hardware/design/component-guidelines/touchpad-windows-precision-touchpad-collection) 一致。
- Windows 官方说明 Precision Touchpad 与旧式鼠标模拟的关键区别正是“上报手指位置/状态，由 Windows 决定动作”；其新触控板 API明确面向 Remote Desktop 客户端，并提供 synthetic touchpad/action injection。来源：[Precision Touchpad Input](https://learn.microsoft.com/en-us/windows/win32/input-precisiontouchpad/precision-touchpad-portal)。

### 工程推断（待真机证伪）

- OPPO 本机 `getClassification()==NONE`，所以应用层 confirm-and-lock 是合理降级，但 45/70px 等阈值只适用于当前坐标尺度，不是跨设备物理常数。
- Alt+Tab 水平/纵向累计步距 90px、纵向首格 135px、纵横意图比例 0.8、两排同向边界保护与 15s 安全释放都是远程交互经验值，不是 Windows API 保证。0.8 依据本轮 OPPO 弧形轨迹与保留对角线定位的折中，仍需真机手感证伪。输入协议无法得知远端 Alt+Tab 的行数；若宿主出现三排以上布局，需要宿主端状态通道后才能做真正的边界钳制。曾使用的 Win+Tab 350ms 动画缓冲和 8s 本地状态窗已因状态泄漏删除。
- `FLICK_FRICTION=0.98/10ms`、0.8→4.0px/ms 加速区间、10px/180ms tap、20–36px 捏合与 14px 滚动早确认仍是经验参数；其中 OPPO 上的 80% 灵敏度、指针立即停、速度相关滚动惯性已通过主观回归，但不能外推为跨设备常数。
- Mac 和 Windows PTP 的最佳路径都依赖 OS 级 recognizer；当前 Artemis 的缓冲判别是现有 Moonlight 输入协议下的近似，不应宣称与 macOS 曲线完全一致。

## 后续版本真机回归脚本

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

涉及输入算法的新版本每组动作至少重复 10 次，并同时观察远端结果与日志：

1. 相对模式 80%：慢速大/小圆、连续“8”字、小目标微调、快速反向与双屏跨越；抬手不得继续移动。
2. 慢速直线二指滚动、交替“走路”滚动、斜向滚动：不得出现 `pinch confirm`；记录 `scroll confirm` 的 `buffered/t`，起步应无明显延迟或突跳。
3. 轻/慢/快捏合后反向：`pinch confirm` 前远端不得滚动；确认后静止不得漂移，反向应回缩；抬一指必须出现 `pinch end` 且 Ctrl 不粘住。
4. 三指水平滑后保持接触：先重复自然左右弧形横扫，X 轴应每约 90px 移动一格并可首尾循环，选择不得跳到另一排；日志中的水平段应出现 `rowIntentRebased` 而不是非零 `rows`。再做明确纵向和约 45° 对角线，约 135px 应触发第一行，且对角线仍可在同一次 MOVE 产生行列步进。两排布局分别执行“上、上”和“下、下”，第二次同方向不得回绕；“上、下、上”应逐次返回，水平移动一格后应重新允许一次纵向移动。被拒绝的弧线/回绕分别显示 `rowIntentSuppressed=N` / `rowBoundarySuppressed=N`。抬起任一指应立即确认，且不得额外出现 `tap`。确认后保留两指并在 200ms 内补回第三指，立即做短上/左/右滑也必须触发；若失败，检查 `stroke start (fast recontact)` 与 `stroke ended without fire`。
5. 四指右/左应切到下一/上一虚拟桌面；连续 stroke 与同一 stroke 反向均只按意图触发。桌面边界不循环；四指上/下/轻点分别打开任务视图、显示桌面、通知中心。
6. 四指过程中补第 5 指、四指抬成三指：直到全部抬起都不得再触发三指、滚动或捏合。
7. 三指上滑打开任务视图后，无需等待，立即分别测试三指左、右、上、下：左右仍应进入 Alt+Tab 应用选择，上应再次发送 Win+Tab，下应发送 Win+D；日志不得再出现 `taskview key`。
8. 惯性滚动过程中单指落下、切到系统界面、取消抓取：惯性立即停止，远端 Alt/Ctrl/左键均不保持按下。

## 代码位置
- `Game.java`：三/四指路由 `handleTrackpadGestures`、二指事件缓冲/回放 `handleTrackpadPinch`、无状态动作注入 `performTrackpadGestureAction`、尾部隔离 `resetTrackpadContexts`
- `TrackpadGestureConfiguration.java`：唯一默认的 3/4 指方向→宿主动作纯映射
- `TrackpadSwipeGestureDetector.java`：独立四指 centroid swipe/reversal detector
- `TrackpadSelectionAccumulator.java` / `TrackpadVerticalIntentGate.java` / `TrackpadRowBoundaryGuard.java`：Alt+Tab 连续行列步进、横向弧线抑制与两排纵向防循环
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
| TP3_APP_SWITCH_ROW_FIRST_STEP_PX | 135 | Alt+Tab 纵向首次换行迟滞 px |
| TP3_APP_SWITCH_VERTICAL_INTENT_RATIO | 0.8 | 换行所需最小纵向/横向轨迹比 |
| MULTI_FINGER_TAIL_GUARD_MS | 200 | 3/4 指结束后的尾部守卫 ms |
| PINCH_ZOOM_GAIN | 1.5 | 分离 px → 滚轮单位倍率 |
| PINCH_NOTCH_UNITS | 30 | 单次注入滚轮单位（1/4 格） |
| PINCH_PHASE_RESET_MS | 150 | 未决手势停顿后丢弃旧缓冲并开始新 phase |

`TrackpadGestureClassifier.java`：捏合确认距离=`clamp(初始间距×6%, 20, 36px)`，分离/质心比 `1.25`，
双指各自最小移动 `10px`；滚动早确认质心 `14px`、分离≤质心 `55%`、点积≥0；长路径回退
`70px/35%`；未决上限 `300ms/48 MotionEvent`。

`TrackpadContext.java` 手感基线：默认 X/Y 灵敏度 `100%`（OPPO 双屏环境可选 `80%`）；加速起点/封顶速度 `0.8/4.0 px/ms`，
最大倍率 `2.5`；速度 EMA 时常 `30ms`；惯性 `0.98/10ms`（按真实 elapsed time 换算），
滚动惯性启动速度 `0.25 px/ms`；滚动倍率 X/Y=`1.5/2.0`，副轴进入比例 `35%`。
