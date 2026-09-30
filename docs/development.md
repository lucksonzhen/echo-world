# 开发说明

后续开发的优先级、具体任务与验收标准见 [开发路线图](roadmap.md)。当前用户操作见 [README](../README.md)，版本历史见 [更新记录](changelog.md)。

## 代码结构

仓库名称为 `echo-world`，应用显示名为“听见世界”，构建项目名为 `EchoWorld`。Android 的 `com.tingjian.*` 包名、iOS bundle ID、钥匙串及配置存储键继续沿用，避免把品牌更新变成全新安装或丢失连接配置。iOS 的 Xcode 工程、target 和 scheme 已更名，需重新运行 `xcodegen generate`。

| 位置 | 职责 |
| --- | --- |
| `native/android/` | 手机本地语音、截图、暂停检测、图片自动描述和朗读 |
| `native/ios/` | Siri 快捷指令的截图描述入口 |
| `server/app.ts`、`middleware.ts` | HTTP 路由、鉴权、限流与错误处理 |
| `server/describe.ts` | 组织识别请求并验证描述结果 |
| `server/providers/` | OpenAI、Gemini 适配器及默认模型配置 |
| `server/description-prompt.ts` | 中文描述规则 |
| `shared/contracts.ts` | 客户端与服务端共用数据类型 |
| `App.tsx`、`src/` | 备用 Expo 媒体导入原型 |
| `tests/` | TypeScript 单元与 HTTP 集成测试 |
| `demo/android/` | 带真实动态视频的独立演示播放器 |

Android 中，`ScreenAssistantService` 协调命令与截图，`AssistantOverlay` 管理悬浮面板；`MediaPauseMonitor` 监听播放，`ImageWatchMonitor` 在图片自动描述开启时扫描前台控件树寻找无文字说明的大图，包括明确标注 Live／实况照片的绘图控件（解读规则、去抖、冷却和图像指纹在纯 Java 的 `ImageWatchPolicy`），`ScreenshotEncoder` 负责裁剪、压缩与指纹，`AssistantApi` 调用服务，`Narrator` 朗读。语音唤醒由 `WakeWordService` 和 `VoiceWakeController` 处理，`ScreenCommand` 把口令映射到有限的动作。

主流程：**语音命令／已开启的暂停检测／已开启的图片自动描述 → 截图（自动图片裁剪到图片区域）→ HTTP 服务 → 模型适配器 → 结果校验 → 朗读**。恢复播放、滚动、切换应用或停止命令会取消当前自动讲解；“停止监控屏幕”与“停止”关闭全部自动模式但保留语音待命。

无障碍服务声明（`res/xml/screen_assistant.xml`）需要 `canRetrieveWindowContent="true"` 并订阅 `typeWindowContentChanged|typeViewScrolled`，仅供图片自动描述使用；服务在该模式关闭时不调用 `getRootInActiveWindow()`。修改这一点时同步更新 `strings.xml` 里的服务描述。

## 构建 Android

需要 Node.js、JDK 17+、Android SDK 35。在项目根目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File native/android/build-debug.ps1 -JavaHome 'JDK目录' -SdkRoot 'Android SDK目录'
```

首次克隆后先运行 `npm ci`。Android 1.2.0 可直接在手机填写模型 API Key，无需 `.env` 或后端；仅 Web / iOS / 中转模式按[服务端说明](../server/README.md)配置 `.env`。上述构建脚本下载并校验默认 Vosk 中文大模型（约 1.27 GiB 下载），再调用 Gradle。小模型与关闭调试的手机试用构建见[手机直连说明](android-direct-api.md#构建与验证)，不要用会重新准备大模型的调试脚本替代该流程。调试输出为 `native/android/app/build/outputs/apk/debug/app-debug.apk`；模型许可见[第三方声明](../native/android/THIRD_PARTY_NOTICES.md)。

已配置 Java 和 SDK 的其他平台，可先运行 `node native/android/prepare-voice-model.mjs`（可加 `--small`），再进入 `native/android` 运行 `./gradlew :app:assembleDebug`。iPhone 构建见 [iOS 说明](../native/ios/README.md)。

Vosk 大语音模型会增大 APK 体积，首次解压需要约 2 GiB 存储；真人口音、噪声和远场场景的收益与实际内存占用仍需和小模型做真机对照。当前 1.6.11 试用 APK 使用小语音模型，Vosk 两种规格都不负责图像理解。手机上切换模型版本后，首次开启语音待命会重新解压并清理旧模型。

安装：`adb install -r native/android/app/build/outputs/apk/debug/app-debug.apk`。测试暂停讲解可使用[动态视频演示](../demo/android/README.md)。

## 验证

```powershell
npm run typecheck
npm test
powershell -ExecutionPolicy Bypass -File native/android/test-commands.ps1 -JavaHome 'JDK目录'
```

TypeScript 测试覆盖模型请求、格式校验、鉴权、限流、超时和取消；Java 检查覆盖命令、唤醒、暂停策略和图片自动描述策略。模型响应在自动化测试中使用替身，真实识别另行联调。合成语音回录（13 段）通过 `VoiceModelInstrumentation` 跑真实 Vosk 模型；更换模型版本后必须重跑。

备用 Web 原型使用 `npm run build:web` 构建、`npm run test:e2e` 检查；Android / iOS 资源导出使用 `npm run build:native`。

修改界面后重新构建 Android，并在模拟器检查展开／收起、截图时隐藏面板、暂停讲解及恢复播放取消。真机验收见[验收清单](acceptance.md)。

`.env`、密钥、构建产物和本地 `artifacts/` 不提交。服务配置见[服务端说明](../server/README.md)；备用原型的使用方式见 [Expo 说明](media-prototype.md)。
