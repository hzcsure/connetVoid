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

## 数据流（本机实际方案：root 中继）

这台电视的系统 HAL 被换成了白名单版（`/vendor/lib/hw/audio.primary.mulan.so`
只有 58KB，原版 `audio.primaryorig.mulan.so` 有 570KB），只放行 HDMI-in / ATV / Line-in，
**任何 app 走 AudioRecord 都只能拿到全零**。真实麦克风是 PDM 数字麦，
只有 root 打开 `pcm3c` 才拿得到，而且必须由 DSP（小爱）运行才供电。

```
root 守护进程 (voice-daemon.sh)
   └─> tinycap -D 0 -d 3 -c 2 -r 16000   （PDM 麦克风，声道 1 有效）
        └─> /sdcard/voice/seg_<n>.wav    （30 秒一段，只保留最近 3 段）

SmartTube 点麦克风
   └─> 系统 SpeechRecognizer bind (action = android.speech.RecognitionService)
        └─> LocalRecognitionService
             ├─ RootRelaySource 从最新分段尾部读 PCM（声道 1 → 单声道 16k）
             ├─ Vosk 中文模型离线识别（边说边出 partial 结果）
             └─ 回调 results("周杰伦") ─> SmartTube 拿到文字直接搜索
```

若中继不存在（`/sdcard/voice` 没有分段），自动退回 `AudioRecord`。

## 启动中继

```bash
adb shell service call TvService 4400 s16 s s16 /sdcard/voice_start.sh
```

停止：`adb shell touch /sdcard/voice/stop`。重启电视后需要重新执行启动命令。

前提：`com.xiaomi.wakeupservice` 必须是启用状态（它负责点亮 PDM 麦克风），
`pcm7c` 被它独占是正常的，我们用的是 `pcm3c`，两者不冲突。

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

- 中继是**持续采集**的，约 5.5GB/天写入 `/sdcard`（分段轮换，磁盘只占几 MB）。
  如果长期使用，建议改成「按需采集」：app 写一个请求文件，守护进程看到才开录。
- 中继取的是 PDM 原始数据，**没有 AEC**，电视扬声器的声音会被一起录进去。
  按麦克风时最好让 SmartTube 暂停播放，否则可能把台词识别成搜索词。
- `pip`/`gradle` 首次构建要下 42MB 模型，Actions 约 5–8 分钟。

## 项目结构

```
app/src/main/java/com/local/voiceinput/
  LocalRecognitionService.java   RecognitionService 实现 + 识别循环
  RootRelaySource.java           从 /sdcard/voice 分段尾部读麦克风声道
  PcmSource.java                 录音源抽象（中继 / AudioRecord）
  ModelInstaller.java            assets 里的模型解压到私有目录
  MainActivity.java              诊断界面（权限/模型/可发现性/中继状态/自检）
```

## 参数

- 采样率 16000Hz，单声道，16bit（中继侧 16k 立体声，取声道 1）
- 送入 Vosk 前 4 倍增益（PDM 电平偏低，实测人声 RMS 100–370）
- 自适应静音判定：底噪 ×2.5 与 60 取大者；静音 1.8 秒判定说完；单次最长 20 秒
- 只打 `armeabi-v7a`（电视是 32 位系统），APK 约 55MB
