# 内置语音模型的设备测试

`VoiceModelInstrumentation` 将固定的合成语音交给应用实际内置的 Vosk 中文模型，再把识别文本交给 `VoiceWakeController`。它不使用 mock 模型，不打开麦克风，不调用描述服务，也不执行真实截图。

10 个测试样本由 Windows `System.Speech` 与 **Microsoft Huihui Desktop** 中文语音离线生成，语速 `0`、音量 `100`。所有 WAV 为 **16 kHz、单声道、PCM16**，额外添加 350 毫秒开头静音及 1250 毫秒结尾静音，总计 **1,321,560 字节**。

| 文件 | 合成内容 | 预期路由 |
| --- | --- | --- |
| `assets/voice-commands/describe-screen.wav` | 小助手描述屏幕 | `DESCRIBE` |
| `assets/voice-commands/read-text.wav` | 小助手读文字 | `READ_TEXT` |
| `assets/voice-commands/observe-video.wav` | 小助手看看视频 | `VIDEO` |
| `assets/voice-commands/stop.wav` | 小助手停止 | `STOP` |
| `assets/voice-commands/disable-listening.wav` | 小助手关闭语音监听 | `DISABLE_LISTENING` |
| `assets/voice-commands/pause-start.wav` | 小助手开启暂停讲解 | `PAUSE_START` |
| `assets/voice-commands/pause-stop.wav` | 小助手关闭暂停讲解 | `PAUSE_STOP` |
| `assets/voice-commands/faster.wav` | 小助手快一点 | `FASTER` |
| `assets/voice-commands/slower.wav` | 小助手慢一点 | `SLOWER` |
| `assets/voice-commands/normal-rate.wav` | 小助手正常语速 | `NORMAL_RATE` |

设备测试通过 `OfflineModelStore.prepare(getTargetContext())` 准备模型，在工作线程创建实际 `Model` 和 `Recognizer`。每次传入 1600 个采样点，与麦克风服务的 100 毫秒输入块一致。每个样本分别检查：

1. 识别终结文本合并后，命令恰好触发一次且类型正确。
2. 模型实际语音终点逐次交给状态机后，也恰好触发相同命令。强制文件结束产生的残余结果不计入这项，避免掩盖服务等待不到语音终点的问题。
3. 逐次交付部分结果和终点结果时，命令仍只触发一次；停止及关闭监听必须在最终终点前由稳定的部分结果触发。

Instrumentation Bundle 输出固定样本的文件名、识别结果、终点结果、成功数和失败数。测试代码不会记录用户语音。

## 运行

在 Android 工程 `defaultConfig` 中设置：

```groovy
testInstrumentationRunner 'com.tingjian.assistant.VoiceModelInstrumentation'
```

从仓库根目录构建：

```powershell
node native/android/prepare-voice-model.mjs
native/android/gradlew.bat -p native/android :app:assembleDebug :app:assembleDebugAndroidTest
```

安装应用 APK 及测试 APK 后运行；有多个设备时，在每条 `adb` 命令中用 `-s 设备序列号` 选定同一台设备：

```powershell
adb install -r native/android/app/build/outputs/apk/debug/app-debug.apk
adb install -r native/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.tingjian.assistant.screen.test/com.tingjian.assistant.VoiceModelInstrumentation
```

成功应包含 `passed=10`、`failed=0` 和 `INSTRUMENTATION_CODE: -1`；任一样本或模型初始化失败时，测试结束码为 `0`。首次运行需要解包模型。此测试自行报告结果，不依赖 JUnit runner。

## 重新生成样本

在安装了上述 Windows 中文语音的电脑上，从仓库根目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File native/android/app/src/androidTest/generate-fixtures.ps1
```

脚本只合成固定语句，不采集麦克风。语音来源与编码方法见 [Microsoft System.Speech 文档](https://learn.microsoft.com/en-us/dotnet/api/system.speech.synthesis.speechsynthesizer.setoutputtowavefile?view=netframework-4.8.1)。

## 验证边界

样本生成和 WAV 格式检查不代表设备模型测试已经通过，设备结果需另行记录。即使十项均通过，也只验证这一组合成音频的实际模型解码与命令路由，不能代表真人口音、远距离、噪音、视频外放、TTS 回声或真实麦克风的唤醒准确率；权限、前台服务、截图和朗读的整条免触摸流程仍需单独验收。


## 配置语音样本（1.5.0）

另有 `assets/setup-commands/` 下 10 段固定合成语音，由 `generate-setup-fixtures.ps1` 使用相同声音与编码参数生成（共 1,282,520 字节）。覆盖下一步、上一步、选择第二项、再说一遍、粘贴密钥、测试连接、同意上传、确认操作、取消操作、跳过。

使用 `-PtestRunner=com.tingjian.assistant.SetupVoiceModelInstrumentation` 构建测试 APK，再运行 `adb shell am instrument -w com.tingjian.assistant.screen.test/com.tingjian.assistant.SetupVoiceModelInstrumentation`。每段只将实际语音终点交给配置命令解析器，部分结果和强制 EOF 不触发操作。本轮使用 APK 内置的 Vosk small 中文模型；“上一步”识别为“上一部”的同音结果只作为返回动作兼容，不放宽同意或确认命令。

配置 Activity、模拟 API、授权和密钥保护另由 `-PtestRunner=com.tingjian.assistant.DirectApiInstrumentation` 检查。音频样本不包含真人录音，不能替代实际麦克风和读屏全流程验收。

## 配置语音短时输入（1.6.3）

增加 4 段不带唤醒词的固定合成语音：下一步、选择第二项、确认操作、取消操作。由相同脚本生成，与此前 10 段合计 14 段、1,719,656 字节。新增样本仅使用主动输入的解析模式；普通待命仍要求唤醒词。测试只在 Vosk 的实际语音终点交付命令，部分结果和 EOF 不执行。
