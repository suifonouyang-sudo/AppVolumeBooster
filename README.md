# 应用音量放大器（App Volume Booster）

按应用单独放大音量的 Android 工具。已在 Rockchip R10D（Android 11）上实测通过。

## 为什么这样实现

Android **没有 per-app 音量 API**，系统音量是按 stream（媒体/铃声/通知）控制的。
本应用通过「识别当前正在放音的应用 → 把 LoudnessEnhancer 增益值切换成该应用的设定值」来实现按应用放大。

识别应用用三条路径兜底：

| 路径 | 准确度 | 所需权限 |
|---|---|---|
| MediaSessionManager（正在播放的媒体会话） | 最高 | 通知使用权 |
| AudioPlaybackConfiguration + 反射 getClientUid | 高（覆盖游戏等无 MediaSession 的应用） | 无需额外权限 |
| UsageStats 前台应用 | 兜底 | 使用情况访问 |

增益施加：能拿到 audio session id 就挂到该 session（真正的独立通道）；否则挂全局输出混音。

## 下载 / 安装

从 [Releases](../../releases) 下载最新 `app-debug.apk`，然后：

```bash
adb install -r app-debug.apk
```

或直接把 APK 传到手机点击安装（需允许「安装未知应用」）。

## 首次使用必做两步授权

1. **通知使用权**（主识别路径）：
   设置 → 通知使用权 → 勾选「应用音量放大器」
   或 adb：`adb shell settings put secure enabled_notification_listeners com.rk.appvolume/com.rk.appvolume.NotiListener`
2. **使用情况访问**（兜底识别）：
   设置 → 使用情况访问 → 勾选「应用音量放大器」
   或 adb：`adb shell appops set com.rk.appvolume GET_USAGE_STATS allow`

> App 首页有两个按钮可直接跳转到对应设置页。

## 使用方法

1. 打开 App，打开顶部「启用放大服务」开关
2. 在列表里找到目标应用，拖动滑块设定增益（0 ~ +15 dB）
3. 播放该应用的音频，增益自动生效
4. 首页底部实时显示「正在播放：XXX」和当前生效的增益

## 列表排序规则

默认排序：**正在播放的应用自动置顶**，未播放的排在下面；同组内按应用名称字母序。

- 应用停止播放后，它会自动滑回字母序原位
- 正在播放的应用名字后面带一个「正在播放」标签
- 拖动滑块期间列表会暂时冻结排序（避免手指底下的条目乱跳），松手后才重排
- 置顶只认「真正检测到音频输出」的应用；仅前台在前但未放音的应用不会置顶

> 实现细节：服务广播里除了 `extra_active`（含前台兜底）还带了一个 `extra_active_audio`
> （只含 MediaSession / AudioPlaybackConfiguration 真正检出的音频应用），UI 排序用后者。

## adb / 自动化接口

```bash
# 设置某个应用的增益（单位 mB，1000 = +10 dB，上限 1500）
adb shell am broadcast -n com.rk.appvolume/.GainReceiver \
    -a com.rk.appvolume.SET_GAIN --es pkg com.example.app --ei gain 1000

# 启动 / 停止服务
adb shell am start-foreground-service -n com.rk.appvolume/.BoostService
adb shell am stopservice -n com.rk.appvolume/.BoostService
```

## 查看运行状态

```bash
adb shell logcat -s AppVolume:I
# 输出示例：playing=[com.android.launcher] gain=1000mB mode=global
```

- `mode=session`：已挂到该应用独立音频通道（最优）
- `mode=global`：使用全局输出混音（按当前播放应用切换增益值）

## 已知限制

1. **多个应用同时放音时**，只能应用一个增益值（取其中最大的）。这是 Android 音频架构的限制，非本应用缺陷。
2. `mode=global` 下，增益对所有输出生效（包括通知音），但因为只在目标应用播放时才开启，实际影响很小。
3. 部分 ROM 限制了隐藏 API 反射，会导致识别退化为「使用情况访问」路径（前台应用）。此时若后台放音、前台是别的应用，可能识别不准——授权通知使用权可解决。
4. 增益过大可能削波破音，建议逐步上调，一般 +6 ~ +10 dB 足够。

## 自行构建

```bash
git clone https://github.com/suifonouyang-sudo/AppVolumeBooster.git
cd AppVolumeBooster
./gradlew assembleDebug          # Windows 用 gradlew.bat assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

零外部依赖（不使用 AndroidX），只需 JDK 17+ 与 Android SDK（compileSdk 34）。
首次构建前请确认 `local.properties` 中的 SDK 路径，或设置环境变量 `ANDROID_HOME`。

## 适配说明

在 Rockchip R10D（Android 11，RK356x）上开发并实测。
理论上适用于 Android 8.0（API 26）及以上任意机型，但**识别路径 2 依赖隐藏 API 反射**，
不同 ROM 表现可能不同，详见下方「已知限制」。
