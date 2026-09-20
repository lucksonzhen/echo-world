# 开发说明

## 代码结构

| 位置 | 职责 |
| --- | --- |
| `native/android/` | 手机本地语音、截图、暂停检测和朗读 |
| `native/ios/` | Siri 快捷指令的截图描述入口 |
| `server/app.ts`、`middleware.ts` | HTTP 路由、鉴权、限流与错误处理 |
| `server/describe.ts` | 组织识别请求并验证描述结果 |
| `server/providers/` | OpenAI、Gemini 适配器及默认模型配置 |
| `server/description-prompt.ts` | 中文描述规则 |
| `shared/contracts.ts` | 客户端与服务端共用数据类型 |
| `App.tsx`、`src/` | 备用 Expo 媒体导入原型 |
| `tests/` | TypeScript 单元与 HTTP 集成测试 |
| `demo/android/` | 带真实动态视频的独立演示播放器 |

Android 中，`ScreenAssistantService` 协调命令与截图，`AssistantOverlay` 管理悬浮面板；`MediaPauseMonitor` 监听播放，`AssistantApi` 调用服务，`Narrator` 朗读。语音唤醒由 `WakeWordService` 和 `VoiceWakeController` 处理。

主流程：**语音命令／已开启的暂停检测 → 截图 → HTTP 服务 → 模型适配器 → 结果校验 → 朗读**。恢复播放或停止命令会取消当前自动讲解。

## 构建 Android

需要 Node.js、JDK 17+、Android SDK 35。在项目根目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File native/android/build-debug.ps1 -JavaHome 'JDK目录' -SdkRoot 'Android SDK目录'
```

首次克隆后先运行 `npm ci`，并按[服务端说明](../server/README.md)从 `.env.example` 创建 `.env`；默认端口为 `8787`。上述构建脚本下载并校验内置 Vosk 中文模型，再调用 Gradle，首次构建需要联网。安装包输出到 `native/android/app/build/outputs/apk/debug/app-debug.apk`；模型许可见[第三方声明](../native/android/THIRD_PARTY_NOTICES.md)。

已配置 Java 和 SDK 的其他平台，可先运行 `node native/android/prepare-voice-model.mjs`，再进入 `native/android` 运行 `./gradlew :app:assembleDebug`。iPhone 构建见 [iOS 说明](../native/ios/README.md)。

安装：`adb install -r native/android/app/build/outputs/apk/debug/app-debug.apk`。测试暂停讲解可使用[动态视频演示](../demo/android/README.md)。

## 验证

```powershell
npm run typecheck
npm test
powershell -ExecutionPolicy Bypass -File native/android/test-commands.ps1 -JavaHome 'JDK目录'
```

TypeScript 测试覆盖模型请求、格式校验、鉴权、限流、超时和取消；Java 检查覆盖命令、唤醒和暂停策略。模型响应在自动化测试中使用替身，真实识别另行联调。

备用 Web 原型使用 `npm run build:web` 构建、`npm run test:e2e` 检查；Android / iOS 资源导出使用 `npm run build:native`。

修改界面后重新构建 Android，并在模拟器检查展开／收起、截图时隐藏面板、暂停讲解及恢复播放取消。真机验收见[验收清单](acceptance.md)。

`.env`、密钥、构建产物和本地 `artifacts/` 不提交。服务配置见[服务端说明](../server/README.md)；备用原型的使用方式见 [Expo 说明](media-prototype.md)。
