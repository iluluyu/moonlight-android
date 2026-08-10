# Artemis 触控板完整手势体系设计（调研 + 方案）

> **实现状态（2026-08）**：P0（捏合）+ P1（三指滑）已落地，见 `trackpad-handoff-note.md`。
> 与本文的差异：
> - 捏合判别器从"累计点积阈值"升级为 **confirm-and-lock 状态机**（三信号联合 + 滚动锁定），
>   因为纯点积在慢速滚动 + 手指"走路"时会漂成负值偶发误触；
> - 三指状态机改为"每次 DOWN 武装、触发时按方向门控"：同向冷却吞掉、反向立即触发、
>   中途反向重武装、快甩小阈值（60/45px）、一次 stroke 至多一次同向触发；
> - 三指 stroke 结束时 cancel 两个 TrackpadContext 槽 + 200ms 捏合守卫（尾部隔离），
>   TrackpadContext.touchDownEvent 增加 cancelled 守卫（修幽灵右键）；
> - 任务视图导航键有 350ms 展开动画缓冲（postTaskViewKey），超时 8s。

目标设备：OPPO Pad 4 Pro 官方磁吸键盘触控板（event13 pogo_touchpad / event11 Keyboard Touchpad）。
事件链：`Game.onTouch`(:3486) → `handleMotionEvent`(:2838 起) → 触控板 finger 分支(:3139) → `handleTouchInput(event, trackpadContextMap, false)`(:3185)。指针捕获开启时走相对轴分支(:2857-2916)，含 `CLASSIFICATION_TWO_FINGER_SWIPE` 合成(:2949)。

## 1. 现有手势能力清单

`trackpadContextMap` = `TouchContext[2]`（:152，仅 2 槽），初始化(:820)。所有 1/2 指行为在 `TrackpadContext`：

| 指数/动作 | 当前宿主机行为 | 代码位置 |
|---|---|---|
| 1 指移动 | 相对移动光标 `sendMouseMove`（含加速/惯性 flick） | TrackpadContext.touchMoveEvent pointerCount==1 |
| 1 指轻点(<230ms,<30px) | 左键点击（按下延时 230ms 抬起，支持双击判定 isDblClickPending） | TrackpadContext.isTap/touchUpEvent |
| 1 指拖拽 | "轻点+随后移动"进入 drag（确认后 hold 左键） | touchDownEvent isClickPending→confirmedDrag |
| 双击+按住拖放 | 第二指落下转 drag；或 synthClickPending+超时 `trackpadDragDropThreshold`(:2969) | Game.java:2968-2984 |
| 2 指移动 | 垂直/水平滚动 `sendMouseHighResScroll/HScroll`（系数 X=2/Y=3，自然方向） | touchMoveEvent actionIndex==1&&pointerCount==2 |
| 2 指轻点(无移动) | 右键（第 2 指抬起时） | touchDownEvent pointerCount==1&&!clickedMiddle |
| 2 指按下(静止) | 中键点一下（仅当未移动且 isNewFinger=false 触发? 实为 pointerCount==2 分支） | touchDownEvent pointerCount==2 |
| 3 指(理论) | getMouseButtonIndex 返回 MIDDLE，但第 3 指 actionIndex=2 → getTouchContext 返回 null，实际丢失 | TrackpadContext.getMouseButtonIndex / handleTouchInput getTouchContext(:2465) |
| 3/4/5 指轻点 | **仅触屏**模式有效（弹键盘/全键盘/菜单）；触控板分支(:3139)未接 `handleMultiTouchGesture` | handleMultiTouchGesture(:3362)，调用点仅(:3170) |

`handleMultiTouchGesture`(:3362) 只在**触屏** finger 分支(:3162-3172)被调用，触控板路径完全旁路。`sendKeys(short[])`(:2210) 自动累积 modifier 并延时逆序释放，是注入组合键的最佳入口。VK：`VK_LWIN=91 / VK_LCONTROL=162 / VK_LSHIFT=160 / VK_LMENU=164 / VK_TAB=9 / VK_LEFT=37 / VK_RIGHT=39 / VK_D=68 / VK_HOME=36`（KeyboardTranslator.java:56-88,95-105）。

## 2. 建议完整手势映射表

| 手势 | 检测条件 | 宿主机动作 |
|---|---|---|
| 单指移动 | pointerCount==1 | `sendMouseMove`（已有） |
| 单指轻点 | 抬起 <230ms & <30px | 左键（已有） |
| 单指双击 | 两次轻点 | 双击左键（已有 isDblClickPending） |
| 拖拽 | 轻点后未抬起继续移动 | hold 左键+移动（已有） |
| 双指滚动 | 2 指 MOVE，自然方向 | `sendMouseHighResScroll/HScroll`（已有，可加精度档） |
| 双指轻点 | 2 指<230ms 无 scroll | 右键（已有） |
| 双指捏合缩放 | 2 指距离变化率>|阈值| | Ctrl+滚轮（`VK_LCONTROL`+`sendMouseHighResScroll`，新建） |
| **三指左/右滑** | 3 指 MOVE，centroid ΔX>|120px| 且 |ΔX|>|ΔY| | `sendKeys({VK_LWIN,VK_CONTROL,VK_LEFT/RIGHT})` 切虚拟桌面 |
| **三指上滑** | 3 指 centroid ΔY<−100px | `sendKeys({VK_LWIN,VK_TAB})` 任务视图 |
| **三指下滑** | 3 指 centroid ΔY>+100px | `sendKeys({VK_LWIN,VK_D})` 显示桌面 |
| **三指轻点** | 3 指<250ms & <40px | `sendKeys({VK_LMENU,VK_TAB})` 切上个应用（单次） |
| 四指左/右滑 | 同三指 | 桌面切换备选 / 可配为 Alt+Tab |
| 四指上滑 | 同上 | 任务视图备选（Win+Tab） |
| 边缘左滑(右键区) | 起点在最右 8% 宽 | 退格/浏览器后退 `sendKeys({VK_LMENU,VK_LEFT})`（可选） |

阈值仅为初值，需在 centroid 计算基础上按触控板物理尺寸定标。

## 3. 实现落点（最小侵入）

1. **扩容 + 新分发**：把 `trackpadContextMap`(:152) 扩到 4 槽以容纳 3/4 指 MOVE；在触控板分支(:3139)于 `handleTouchInput` 调用前插入：`if (pointerCount>=3 && handleTrackpadGesture(event,action,pointerCount)) return true;`，仿触屏(:3170)写法。
2. **新增 `handleTrackpadGesture`**（仿 :3362）：ACTION_POINTER_DOWN(count==3/4) 记录 downTime、centroid、各指坐标；ACTION_MOVE 更新 centroid 并当超过阈值时**一次性**注入 `sendKeys` 并 `cancelTouch`（防误判，复用 :3413 `cancelStaleTouchState` 思路调 `trackpadContextMap` 各 `cancelTouch()`）；ACTION_UP/POINTER_UP 判定轻点(<250ms,<40px)→`sendKeys`。
3. **方向判定**用质心位移主导轴；速度可借用 TrackpadContext 的 EMA 思路。`CLASSIFICATION` 仅 `TWO_FINGER_SWIPE`/`PINCH` 对 2 指有用(:2949)，3/4 指 API 无分类，须自行判定。
4. **抑制默认**：注入手势后对 `trackpadContextMap` 全部 `cancelTouch()+setPointerCount(0)`，避免残留滚轮/移动；触控板事件本身 return true 吞掉。
5. 组合键一律走 `sendKeys(short[])`(:2210)，避免手写 down/up 与 modifier。捏合缩放走 `sendMouseHighResScroll` 叠加 `VK_LCONTROL` 单独 down/up。

## 4. 设置项改动清单

放 `category_input_settings`(preferences.xml:362)：
- `checkbox_trackpad_gestures_enable`(总开关) + 子项
- `checkbox_trackpad_3f_swipe`(默认 true)、`checkbox_trackpad_3f_tap_alttab`(默认 true)
- `list_trackpad_3f_up`(任务视图/显示桌面/关闭)、`list_trackpad_3f_down`
- `checkbox_trackpad_pinch_zoom`(默认 true)
- `seekbar_trackpad_gesture_sensitivity`(阈值倍率)
对应 PreferenceConfiguration.java 新增字段(仿:354-358)+读取(仿:1022-1026)；strings.xml 补 title/summary（仿 title_trackpad_swap_axis）。

## 5. ColorOS 抢占风险

触控板未被 Shizuku 抓取（evdev_grab.c 过滤 `EV_ABS`，触控板有 EV_ABS→返回 -1 跳过），仍走框架，故**会**被系统手势层拦截。判断：OPPO 的截屏/分屏手势一般绑**触屏**(SOURCE_TOUCHSCREEN)，对 SOURCE_TOUCHPAD 不一定生效——需实机验证三指 MOVE 是否到达 `onTouch`（若只见 DOWN 后紧跟 ACTION_CANCEL 即被抢）。引导措辞：「设置→便捷工具/手势→关闭三指截屏、三指分屏；设置→桌面与锁屏→关闭多指手势」。**降级方案**：若 3 指收不到，把"切桌面/任务视图"改绑**双指长按+方向**或键盘已有快捷键；保留 2 指滚/右键、单指全套（这些稳定）。

## 6. 优先级建议

- **P0（已完成 ✅）**：双指捏合缩放(Ctrl+滚轮)——confirm-and-lock 判别器；统一并清理 2 指右键/滚动体验。
- **P1（已完成 ✅）**：三指上/下/左/右滑（Win+Tab / Win+D / Ctrl+Win+←→）——centroid + 阈值版，
  覆盖远程桌面最常用导航；含任务视图展开动画缓冲、反向即时响应、尾部隔离。
- **P2（待定）**：三指轻点 Alt+Tab（已实现但参数未实测）；四指备选手势。
- **P3（待定）**：边缘手势、可配置 list 映射。

实测优先级：捏合慢速滚动误触是否根除、快甩阈值是否过灵敏（TP3_SWIPE_*_FAST）、
三指快速连滑跟手度（TP3_COOLDOWN_MS=250 同向门控）、任务视图 350ms 缓冲是否足够。
