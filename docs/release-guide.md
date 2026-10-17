# 发布流程（本分支）

面向本仓库维护者的发版清单。所有命令都在仓库根目录、WSL/Linux 下执行。

## 0. 环境前提

- **JDK 17**：Gradle 8.13 在 JDK 27 上会直接失败（`Could not create task ':app:outgoingVariants' ... Type T not present`）。所有 gradle 命令都要带上：
  ```sh
  export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
  ```
- **签名**：`app/keystore.properties`（本地、已 gitignore）必须存在，否则 release 包不会被签名。覆盖安装要求签名与已安装版本一致，不要换 keystore。
- **NDK**：`app/local.properties` 里的 `sdk.dir` / `ndk.dir` 已配好即可。

## 1. 改版本号

`app/build.gradle`：

```groovy
versionName "20.2.6-oppo.4"   // 展示给用户的版本
versionCode = 60              // 必须递增，否则无法覆盖安装
```

> 如果本次改动了 Shizuku UserService 的 **AIDL 或行为**，还要把
> `ShizukuInputBackend.SERVICE_PROTOCOL_VERSION` 加 1。
> Shizuku 只在 `UserServiceArgs.version`（= `versionCode * 100 + 协议版本`）变化时才重建
> UserService 进程；不改的话，同版本号下的开发包会**继续复用旧进程**（旧代码 + 旧 AIDL，
> 并且继续握着键盘/触控板 grab），表现是"新方法调不通、改了没反应"。

## 2. 构建 release APK（只出 arm64）

平板/手机都是 arm64，只构建一个 ABI 又快又省事：

```sh
./gradlew assembleNonRoot_gameRelease \
    -Pandroid.injected.build.abi=arm64-v8a \
    -Pandroid.injected.testOnly=false --offline
```

两个参数都是必须的：

| 参数 | 作用 |
|---|---|
| `-Pandroid.injected.build.abi=arm64-v8a` | 只出 arm64，省掉另外 3 个 ABI 的编译与打包时间 |
| `-Pandroid.injected.testOnly=false` | **不加会产出 `testOnly` APK**，安装时报 `INSTALL_FAILED_TEST_ONLY`；之前的 release 包也不该是 test-only |

带 ABI 过滤时产物落在 **intermediates**，不是 `outputs/`：

```
app/build/intermediates/apk/nonRoot_game/release/app-nonRoot_game-arm64-v8a-release.apk
```

（不加 ABI 过滤则走 `app/build/outputs/apk/nonRoot_game/release/`，会出 4 个 APK。）

## 3. 检查产物

```sh
APK=app/build/intermediates/apk/nonRoot_game/release/app-nonRoot_game-arm64-v8a-release.apk

# 版本号、包名、ABI 对不对
/opt/android-sdk/build-tools/37.0.0/aapt2 dump badging "$APK" | grep -E '^package|native-code'
# 期望：package: name='com.limelight.noir' versionCode='60' versionName='20.2.6-oppo.4'
#       native-code: 'arm64-v8a'

# 确认没有被打上 testOnly
unzip -p "$APK" AndroidManifest.xml | grep -c testOnly   # 期望 0
```

## 4. 装到真机自测

```sh
adb install -r "$APK"
adb shell am start -W -n com.limelight.noir/com.limelight.PcView
```

> **不要用 `adb monkey` 启动**：`NvConnection` 会检测 `isUserAMonkey()` 并静默丢弃全部鼠标/键盘/滚轮输入，
> 表现为"串流正常但什么操作都进不去主机"。一律用 `am start`。

进串流后至少确认：

```sh
# Shizuku 用户服务是否起来、是否抓到触控板节点（release 包也有这条日志）
adb logcat -d -s ShizukuInputService | tail -5
# 期望形如：
#   started uid=2000 grabNative=true grabbed=9 readable=9
#   touchpad grabbed /dev/input/event13 xMax=2764 yMax=1630 majorMax=100
#   touchpad grabbed /dev/input/event10 xMax=2764 yMax=1630 majorMax=100
# 触摸屏节点（event4，INPUT_PROP_DIRECT）**不应**出现在 grab 列表里

# 指针捕获状态
adb shell dumpsys input | grep 'Pointer capture mode'
```

手势细节需要装 debug 包（`./gradlew assembleNonRoot_gameDebug -Pandroid.injected.build.abi=arm64-v8a -Pandroid.injected.testOnly=false`），
debug 包会在画面左上角叠加 `ArtemisTP` 事件路由信息，也可以用：

```sh
adb logcat -c && adb logcat -s ArtemisTP
```

## 5. 发布 GitHub Release

资产命名**必须是 `Artemis-arm64-v8a.apk`**，tag **必须以 `v` 开头**，否则 README 里的 Obtainium 链接匹配不到（见下一节）。

```sh
cp "$APK" /tmp/Artemis-arm64-v8a.apk

gh release create v20.2.6-oppo.4 /tmp/Artemis-arm64-v8a.apk \
    --title "Artemis for OPPO Pad 4 Pro（一句话说明）" \
    --notes-file /tmp/relnotes.md \
    --target moonlight-noir
```

release notes 用中文、面向用户写：这版**修了什么体感问题**、需要什么前提（如 Shizuku）、怎么安装、已知限制。
技术细节留在 `docs/trackpad-handoff-note.md`，不往 release notes 里堆。

发布后核对：

```sh
gh release view v20.2.6-oppo.4
```

## 6. Obtainium 链接的约束

README 里的一键安装链接（`obtainium://app/...`）编码了三条规则，改版时必须仍然成立：

| 字段 | 值 | 含义 |
|---|---|---|
| `id` | `com.limelight.noir` | 必须是 release 包的 package |
| `apkFilterRegEx` | `arm64` | 资产名必须含 `arm64` |
| `versionExtractionRegEx` | `v(.+)` | **tag** 必须以 `v` 开头，取 `v` 之后的部分作版本号 |

历史坑：这两条规则以前写的是 `nonRoot`，而实际资产叫 `Artemis-arm64-v8a.apk`，导致 Obtainium 报"找不到安装包"。
所以**不要随意改资产名或 tag 格式**；要改就同时改 README 里的链接。

## 7. 提交与推送

```sh
git add -A && git commit -m "bump version to 20.2.6-oppo.4"
git push origin moonlight-noir
```

发版提交建议单独一个 commit，方便回溯"哪个 commit 对应哪个 release"。

## 8. 常见故障速查

| 现象 | 原因 / 处理 |
|---|---|
| `Type T not present` / 构建直接失败 | JDK 太新，`export JAVA_HOME=/usr/lib/jvm/java-17-openjdk` |
| `INSTALL_FAILED_TEST_ONLY` | 构建时漏了 `-Pandroid.injected.testOnly=false` |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 签名不一致或 `versionCode` 没递增 |
| 找不到 release APK | 用了 ABI 过滤，产物在 `app/build/intermediates/apk/nonRoot_game/release/` |
| 改了 AIDL 但真机行为没变 | 忘了加 `SERVICE_PROTOCOL_VERSION`，Shizuku 复用了旧 UserService 进程；临时可 `adb shell kill <:evdev 进程 pid>` 后重进串流 |
| 串流中键鼠全部无反应 | 八成是用 `adb monkey` 启动的；强停后用 `am start` 冷启动 |
| 触控板手势全失效 | release 包看 `ShizukuInputService` 是否有 `touchpad grabbed`；若系统已不再把该设备报告为 TOUCHPAD，需在设置里确认 Shizuku 模式并检查系统"键盘触控板"设置 |

## 9. 全 ABI 构建（需要时）

要一次性出全部 ABI（例如给模拟器或群里其他人用），去掉 ABI 过滤即可，产物在 `app/build/outputs/apk/nonRoot_game/release/`：

```sh
./gradlew assembleNonRoot_gameRelease -Pandroid.injected.testOnly=false
```
