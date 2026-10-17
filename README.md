<p align="right"><b>中文</b> | <a href="README_EN.md">English</a></p>

# Artemis Android · OPPO 触控板优化分支

一个面向 **Android 平板 / 二合一设备**的 Moonlight 分支：在 [Artemis Android](https://github.com/ClassicOldSong/moonlight-android) 的基础上，针对 **OPPO Pad 官方磁吸键盘触控板**做了深度适配，用 **Shizuku 取代无障碍服务**做键鼠捕获，并优化了**有线网络**串流。串流主机侧搭配 [Apollo](https://github.com/ClassicOldSong/Apollo) / [Sunshine](https://github.com/LizardByte/Sunshine)。

> 目标很朴素：**让平板 + 磁吸键盘触控板，用起来像一台带触控板的笔记本**——单指像鼠标、两指像滚轮、三指四指像 Windows 触控板。

---

## 特色

### 1. 为 OPPO 触控板深度适配（含 ColorOS 17 / Android 17）

从零复刻了"Mac 触控板手感"的一整套手势识别：时间型的指针加速、与采样率无关的速度估计、惯性滚动、捏合缩放判别、三/四指滑动状态机，全部在客户端合成，因此**不依赖主机端支持**。

Android 17（ColorOS 17）修改了触控板事件投递：pointer capture 仍然生效，但系统只给应用 **单指** `SOURCE_MOUSE_RELATIVE`，把滚动合成为滚轮、轻点合成为点按，二/三/四指的原始触点**不再下发**——这会让所有多指手势一夜失效。本项目改为通过 Shizuku 以 shell 身份**直接读取触控板 evdev 节点**，再把原始触点合成为 `SOURCE_TOUCHPAD` 事件喂回同一套手势管线，因此手势逻辑与手感保持一致。

![触控板事件管线对比](docs/images/touchpad-pipeline.svg)

### 2. 用 Shizuku 取代无障碍服务

键盘捕获默认走 **Shizuku UserService**（shell/root 身份常驻进程），对 `/dev/input/event*` 执行 `EVIOCGRAB` 独占抓取：

- 事件**不再进入系统输入栈**，OPPO 的 `Win → AI` 之类的 OEM 快捷键在串流中不会误触发；
- 物理 Esc、Fn 组合、功能键可原样送到串流主机；
- fd 关闭或 UserService 进程退出时内核**自动释放**抓取，系统输入立即恢复（崩溃也不会把键盘锁死）。

![Shizuku 与无障碍服务的差别](docs/images/shizuku-vs-accessibility.svg)

### 3. 有线优先串流

接上 USB-C 网卡/扩展坞后，可只把**串流流量**绑到有线网卡，其余系统流量继续保持 Wi-Fi：

- 基于 `ConnectivityManager.bindProcessToNetwork()`，bionic 会给此后创建的 socket 应用进程默认网络，因此 `moonlight-common-c` 的原生 socket 也走有线；
- 多路会话（如外接屏会话）通过引用计数共享绑定，最后一个持有者释放后才解绑。

![有线优先网络绑定](docs/images/ethernet-priority.svg)

### 4. 其他本分支增强

- 触控板**触点过滤**：直读 evdev 会绕过系统自带的噪声/掌压过滤，因此内置了轻量过滤器——噪声小触点、搭在板上的拇指不再把两指滚动变成三指手势；
- **点击防误触**：滚动过程中的触点抖动、双指轻点，不再产生多余的左键点击；
- 键盘捕获方式可选（Shizuku / 无障碍服务），并显示实时生效状态；
- 首次使用引导授权 Shizuku，串流开始时会提示捕获是否生效；
- 其余上游 Artemis 的全部特性（虚拟按键、自定义分辨率与码率、外接屏模式、性能浮层、剪贴板同步、外接屏 SBS 3D 等）均保留。

---

## 触控板手势

默认映射对齐 Windows 触控板语义，全部可在设置里调整灵敏度/轴方向：

| 手势 | 动作 |
|---|---|
| 单指滑动 | 移动光标（带指针加速） |
| 单指轻点 | 左键 |
| 单指双击后按住拖动 | 拖拽 / 选中 |
| 两指上下、左右滑动 | 滚轮（含惯性、支持斜向） |
| 两指轻点 | 右键 |
| 两指捏合 / 张开 | `Ctrl` + 滚轮（缩放） |
| 三指左右滑动 | `Alt+Tab` 应用选择器（保持三指可连续定位，抬手确认） |
| 三指上滑 / 下滑 | `Win+Tab` 任务视图 / `Win+D` 显示桌面 |
| 三指轻点 | `Alt+Tab` |
| 四指左右滑动 | 切换虚拟桌面（上一个 / 下一个） |
| 四指上滑 / 下滑 | 任务视图 / 显示桌面（再下滑恢复窗口） |
| 四指轻点 | 通知中心 |
| 触控板机械按下 | 左键 |

相关设置项：触控板灵敏度 X/Y、交换 X/Y 轴、拖拽触发时长与振动、**直接读取触控板**（Android 17 / ColorOS 17 上默认开启）。

---

## 安装

* [直接下载 APK](https://github.com/iluluyu/moonlight-android/releases)
* [用 Obtainium 自动更新（推荐）](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.limelight.noir%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Filuluyu%2Fmoonlight-android%22%2C%22author%22%3A%22iluluyu%22%2C%22name%22%3A%22Artemis%22%2C%22additionalSettings%22%3A%22%7B%5C%22apkFilterRegEx%5C%22%3A%5C%22arm64%5C%22%2C%5C%22matchGroutToUse%5C%22%3A%5C%22%241%5C%22%2C%5C%22versionExtractionRegEx%5C%22%3A%5C%22v(.%2B)%5C%22%7D%22%7D)

## 首次配置：Shizuku

键盘捕获与触控板直读都依赖 Shizuku：

1. 安装 [Shizuku](https://github.com/RikkaApps/Shizuku)（Play 商店或 GitHub Releases）；
2. 通过**无线调试**或 ADB 启动 Shizuku 服务；
3. Artemis → 设置 → 键盘捕获 → 选择 **Shizuku**；
4. 按提示授权，状态显示"已生效"即可。串流开始时也会有 Toast 提示。

> 不想用 Shizuku 时可以回退到无障碍服务模式：键盘仍可用，但**触控板直读**与 OEM 快捷键拦截不可用。

## 构建

```sh
git submodule update --init --recursive
# 在 app/local.properties 写入 sdk.dir 与 ndk.dir（需要 JDK 17）
./gradlew assembleNonRoot_gameDebug   # 日常调试包
./gradlew assembleNonRoot_gameRelease # 发布包
```

发版（版本号、签名、Release、Obtainium 链接的约束）见 [`docs/release-guide.md`](docs/release-guide.md)。

## 已知限制

- Alt+Tab / 任务视图的悬浮层位置由 Windows 决定（多显示器时固定在主屏），Moonlight 输入协议无法指定显示器，也不回传主机端窗口状态；
- 手势阈值目前基于 OPPO Pad 4 Pro 实测标定，其他触控板可能需要调灵敏度或提 issue 反馈；
- 串流协议不提供 Windows Precision Touchpad 触点通道，手势在客户端合成为键鼠事件。

技术细节与真机排查记录见 [`docs/trackpad-handoff-note.md`](docs/trackpad-handoff-note.md) 与 [`docs/trackpad-gestures-design.md`](docs/trackpad-gestures-design.md)。

---

## 致谢

本分支能成立，完全建立在下列优秀开源项目之上：

- **[Moonlight Android](https://github.com/moonlight-stream/moonlight-android)** — Android 串流客户端的源头，协议实现、解码与渲染的基础；感谢 [Cameron Gutman](https://github.com/cgutman)、[Diego Waxemberg](https://github.com/dwaxemberg)、[Aaron Neyer](https://github.com/Aaronneyer)、[Andrew Hennessy](https://github.com/yetanothername) 以及 [Case Western](http://case.edu) / [MHacks](http://mhacks.org) 的同学们。
- **[Artemis Android（Moonlight Noir）](https://github.com/ClassicOldSong/moonlight-android)** — 本分支的直接上游，虚拟按键、自定义分辨率/码率、外接屏模式、性能浮层、剪贴板同步、SBS 3D 等大量特性来自这里；感谢 [ClassicOldSong](https://github.com/ClassicOldSong)。
- **[Apollo](https://github.com/ClassicOldSong/Apollo)** / **[Sunshine](https://github.com/LizardByte/Sunshine)** — 主机端串流服务，协议与虚拟显示集成。
- **[Shizuku](https://github.com/RikkaApps/Shizuku)** — 提供了无需 root 的 shell 级权限通道，本项目基于它实现键盘独占捕获与触控板 evdev 直读；感谢 [Rikka](https://github.com/RikkaW)。
- **[moonlight-common-c](https://github.com/moonlight-stream/moonlight-common-c)** — 串流协议核心库（子模块）。
- 以及所有在 issue / PR 里反馈真实设备行为的用户——OPPO 触控板的手感参数基本都是在真机日志上一点点磨出来的。

本分支的增量改动由 [iluluyu](https://github.com/iluluyu) 维护，遵循与原项目相同的开源许可。
