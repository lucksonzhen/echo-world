# Android 手机直连模型 API（1.2.1）

手机完成截图、帧压缩、API 请求、结果检查与朗读，不需要电脑开机、局域网或自建描述服务。Vosk 语音识别在本机完成；**画面识别仍联网发送到模型服务商**，手机必须能访问该 API。

## Gemini 官方 API

| 项目 | 填写 |
| --- | --- |
| 接口类型 | Gemini 官方 API（默认） |
| API 基础地址 | `https://generativelanguage.googleapis.com/v1beta`，自动填写 |
| 视觉模型 | 默认 `gemini-3.5-flash`；可按你的账号可用模型修改 |
| API Key | 在 Google AI Studio 创建的个人密钥，直接在手机输入 |

输入密钥后可点“获取 Gemini 模型列表”，选择该密钥当前返回的模型。此操作只查询官方 `models` 接口，不上传图片、不调用生成接口、不保存新设置。列表筛选支持 `generateContent` 的 Gemini 模型，**不保证每个候选支持图片输入、JSON 输出或有剩余额度**。

选择后点“保存并测试识图连接”。测试发送程序生成的白底、蓝方块、红圆小图，不读取手机屏幕，但会产生一次模型请求及可能的费用。只有收到完整、符合格式的图像描述才显示成功。结果同时显示在测试按钮下方。

1.2.0 将 HTTP 400、404、422 合并为“API 地址、模型名称或图片请求格式不受支持”，不能据此判断地址错误。1.2.1 显示 HTTP 状态码，并根据服务商的有限错误字段区分密钥无效、地区不支持、调用条件不满足等；不会回显原始错误正文。404 时先获取模型列表；400 时先测试小图，并记录错误全文。小图成功但屏幕描述失败，应同时记录两者结果。不要发送密钥或私人画面。

官方基础地址仍为上表地址；应用自动追加 `/models/模型名:generateContent`，无需填写 `/openai/`。接口依据：[模型列表](https://ai.google.dev/api/models)、[错误排查](https://ai.google.dev/gemini-api/docs/troubleshooting)。

真实使用前阅读并勾选屏幕识别说明，点“开启屏幕读取服务”。系统设置里找到“听见世界助手”；ColorOS 若有分类，先看“通用”下的已下载应用。手机自带读屏可以保留。安装包受到“受限设置”保护时，按 [Android 官方说明](https://support.google.com/android/answer/12623953?hl=zh-Hans) 查看本应用信息页是否提供允许入口，不需要关闭手机全局安全防护。

## 其他接口

- OpenAI：选择官方接口，默认 `https://api.openai.com/v1` 与 `gpt-4.1-mini`；输入自己的 OpenAI API Key。
- OpenAI 兼容：填写服务商明确提供的 HTTPS 基础地址（如 `https://example.com/v1` 或其要求的其他前缀）、视觉模型名与密钥。应用追加 `/chat/completions`；不支持只实现 `/responses` 的网关或纯文字模型。兼容接口的 JSON 结果必须满足描述结构，非所有网关都已实测。
- 原有中转：保留旧描述服务 `/api/health` 与 `/api/describe` 协议。输入中转服务地址和访问口令，模型名不使用。手机试用构建只接受 HTTPS；只有 debug 构建允许 HTTP 中转。

## 密钥与数据

- 使用 Android Keystore 管理 AES-GCM 密钥，加密保存 API Key，禁用应用备份和配置页面截屏。API Key 不写入代码、APK、日志或请求 URL。
- 官方 Gemini 通过 `x-goog-api-key` 请求头认证；OpenAI / 兼容接口通过 Bearer 请求头认证。不跟随重定向，不关闭证书校验。
- 自定义接口的运营方会收到密钥和画面，必须填写你信任的服务商地址。切换类型或修改地址会清空密钥输入框、停止待命并撤销画面上传同意；保存新设置前不会自动发送到新地址。
- 从旧版升级，不把中转口令当作模型密钥，也不继承旧版的画面上传同意。需重新输入 API Key 并确认用途。
- “清除本机密钥”同时清除模型密钥和中转口令，停止待命并撤销同意。删除本机密钥不等于在服务商处吊销密钥。
- 原始图片仅在内存中处理；服务商的数据处理规则依其条款。连接测试与真实描述均可能计费，不做失败自动重试。取消会停止本机处理，但已经抵达服务商的请求可能仍计费。
- 个人试用包 `phonePreview` 关闭调试与明文 HTTP，沿用上一版的本机测试签名以便覆盖安装。它仍是个人侧载试用包，非应用商店正式发行版。

## 构建与验证

```powershell
node native/android/prepare-voice-model.mjs --small
native/android/gradlew.bat -p native/android :app:assemblePhonePreview
```

输出：`native/android/app/build/outputs/apk/phonePreview/app-phonePreview.apk`。不要运行会重新准备默认大模型的 `build-debug.ps1` 来构建小模型包。

纯 Java 检查：`native/android/test-commands.ps1 -JavaHome <JDK17路径>`。
Android 协议、加密与取消测试使用 `DirectApiInstrumentation`：以 `-PtestRunner=com.tingjian.assistant.DirectApiInstrumentation` 构建 `assembleDebug assembleDebugAndroidTest`，在专用模拟器安装两份 APK 后运行：

```text
adb shell am instrument -w com.tingjian.assistant.screen.test/com.tingjian.assistant.DirectApiInstrumentation
```

该测试会清空模拟器上的应用配置，仅使用假密钥和离线传输替身，覆盖 Gemini/OpenAI/兼容协议、AES-GCM 保存、升级迁移、错误脱敏、禁用重定向、取消及撤销同意。不能替代真实 API Key、手机网络、一加系统权限或麦克风测试。具体结果见 [验收记录](acceptance.md)。

协议依据：[Gemini API](https://ai.google.dev/api)、[OpenAI Chat Completions](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)。直连口述规则位于 `native/android/app/src/main/assets/description-instructions.txt`，应与 `server/description-prompt.ts` 的 `DESCRIPTION_INSTRUCTIONS` 同步维护。
