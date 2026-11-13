# 本地语音识别服务（给 SmartTube 用）

在电视上注册一个标准的 `android.speech.RecognitionService`，让 SmartTube 的「麦克风」按钮
有服务可绑，从而实现**完全离线**的中文语音搜索。

## 为什么需要它

SmartTube 的语音搜索不是自己开麦克风录音，而是问系统"谁来把声音转成文字"。
这台电视（MIUI TV / Android 9）里没有任何应用实现这个接口，所以日志里是：

```
E/SpeechRecognizer: no selected voice recognition service
```

授予 SmartTube `RECORD_AUDIO` 并不能解决——它根本没走到录音那一步。

## 数据流

```
SmartTube 点麦克风
   └─> 系统 SpeechRecognizer 发出 bind 请求 (action = android.speech.RecognitionService)
        └─> 本服务 LocalRecognitionService
             ├─ AudioRecord 16kHz / 单声道 / 16bit 抓 PCM
             ├─ Vosk 中文模型离线识别（边说边出 partial 结果）
             └─ 回调 results("周杰伦") ─> SmartTube 拿到文字直接搜索
```

## 构建

推送代码到 GitHub 仓库（main 分支）后，Actions 自动执行 `.github/workflows/build.yml`：

1. 装 JDK 17
2. 下载 `vosk-model-small-cn-0.22`（约 42MB）解压进 `app/src/main/assets/model/`
3. `gradle assembleDebug`
4. 产物上传为 artifact：**tv-voice-input-apk**（含 `app-debug.apk`）

也可以在 Actions 页面点 `Run workflow` 手动触发。

## 安装与验证

```bash
adb install -r app-debug.apk
adb shell pm grant com.local.voiceinput android.permission.RECORD_AUDIO
```

打开「本地语音识别」App，界面上会显示四项状态：

| 项目 | 期望 |
|---|---|
| 麦克风权限 | 已授予 |
| 识别模型 | 就绪（首次点「解压识别模型」） |
| 系统可发现本服务 | 是 |
| 服务类 | com.local.voiceinput.LocalRecognitionService |

如果「系统可发现本服务」是「否」，手动指定默认识别服务：

```bash
adb shell settings put secure voice_recognition_service \
  com.local.voiceinput/com.local.voiceinput.LocalRecognitionService
```

然后点「3. 自检：录 5 秒并识别」，对着电视说一句话，看能否出文字。

## 已知风险

电视上 `com.xiaomi.wakeupservice` 常驻占着麦克风节点（`pcmC0D7c`，唤醒词监听）。
如果 `AudioRecord` 拿不到有效电平，自检会返回 `ERROR_SPEECH_TIMEOUT`，
日志里 `peakRms` 接近 0。备选：改用 `AudioSource.MIC`（代码里已自动回退）、
或临时停掉唤醒服务。

## 项目结构

```
app/src/main/java/com/local/voiceinput/
  LocalRecognitionService.java   RecognitionService 实现 + 录音 + Vosk
  ModelInstaller.java            assets 里的模型解压到私有目录
  MainActivity.java              诊断界面（权限/模型/可发现性/自检）
```

## 参数

- 采样率 16000Hz，单声道，16bit
- 静音 2.2 秒判定说完；单次最长 20 秒
- 只打 `armeabi-v7a`（电视是 32 位系统），APK 约 55MB
