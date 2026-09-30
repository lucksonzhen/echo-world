# Android 手机直连模型 API（1.6.0）

手机完成截图、帧压缩、API 请求、结果检查与朗读，不需要电脑开机、局域网或自建描述服务。Vosk 语音识别在本机完成；**画面识别仍联网发送到模型服务商**，手机必须能访问该 API。

设置未完成时，打开应用会进入 [逐项设置引导](android-setup-guide.md)。每次只配置一项，通过拨轮确认、检查成功后继续，说明和选项仍会朗读；主界面也提供“逐项设置引导”入口。

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

## DeepSeek 官方 API

1. 接口类型选择“DeepSeek 官方 API”；切换服务商会清空密钥输入框并撤销屏幕上传同意，需重新填写并保存。
2. 基础地址自动填写 `https://api.deepseek.com`，应用追加 `/chat/completions`。
3. 默认视觉模型 `deepseek-flash`，输入 [DeepSeek 平台](https://platform.deepseek.com/) 创建的 API Key。
4. 点击“保存并测试识图连接”，测试成功后重新勾选屏幕识别说明并保存，再使用屏幕描述。

按照当前 [官方图像文档](https://api-docs.deepseek.com/guides/vision/)，`deepseek-flash` 支持图片输入。不要使用仅支持文字的模型；模型能力及账户权限由服务商决定。截图直接发送到 DeepSeek，不经电脑或 Gemini。图片与视频采样帧采用内联图片，不调用文件上传接口。

采用 [非思考模式](https://api-docs.deepseek.com/guides/thinking_mode/) 与 [JSON 输出](https://api-docs.deepseek.com/guides/json_mode/)，只朗读最终描述。密钥沿用本机加密存储、HTTPS 和重定向保护；测试与识图可能产生 DeepSeek API 费用。

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

1.6.0 起，本文提及的保存、测试、模型选择和设置入口均通过 [底部拨轮](android-dial.md) 选择并双击执行，应用内不再显示按钮或下拉菜单。

## Android 1.6.8 返回兼容与诊断

此前“模型返回格式不完整或不兼容，请选择支持图片输入的模型后重试”是本地解析器的通用错误，不能单凭这句话认定模型不支持图片。已核对 DeepSeek 官方图片请求形状；1.6.8 兼容 DeepSeek 有效概要之外的空辅助字段（省略、null、空字符串），以及 JSON 代码块的大小写、换行和 BOM。不把空回复、错误类型、拒绝或截断转换为成功，不自动重试。

| 诊断码 | 含义 |
| --- | --- |
| R00 | API 返回外层结构不符合接口格式。 |
| R01 | 服务返回空内容；官方 JSON 输出文档也列出过这一情况。 |
| R02 | 回复无法解析为单个 JSON 对象。 |
| R03 | 缺少可用概要或概要超出限制。 |
| R04 | 描述字段类型、长度或时间轴校验失败。 |
| R06 | 服务明确报告输出达到长度限制。 |
| R07 | 服务未正常完成回复。 |
| I01 | 本机准备的图片数据无效或过大。 |

诊断只使用固定提示，不记录或回显截图、模型回复或密钥。遇到问题可提供接口类型、模型名称、诊断码，以及小图连接测试是否成功，不需要提交密钥或私人截图。未取得用户手机的真实返回，本次不能确认上述哪种情况是该次故障的唯一原因。

参考：[DeepSeek 图像输入](https://api-docs.deepseek.com/guides/vision/)、[JSON 输出与空内容说明](https://api-docs.deepseek.com/guides/json_mode/)。

## Android 1.6.9 小图成功、屏幕 R02

用户确认 DeepSeek `deepseek-flash` 小图测试成功，只有屏幕描述报 R02，因此继续针对正文解析处理。未取得手机失败回复原文，不能断定具体包装类型。

静态画面的 brief／detailed 模式、无提问、正常 stop 且无接口 refusal 时，可以接收长度不超过 1500、以汉字开头且至少含六个汉字、没有结构／代码标记的普通中文描述，原样作为概要朗读，不编造文字识别结果或时间轴。已知的模型无法查看图片的文字拒绝仍不作为成功描述。此兼容不用于视频、读字、提问或其他服务商。

结构化回复可无损解开一层 JSON 字符串或只含一个对象的数组；支持紧凑代码围栏。不会从夹带说明文字的正文中任意截取第一个对象，不修补损坏内容。DeepSeek 原 R02 进一步区分为：R22 JSON 语法损坏／不完整，R23 对象外还有内容，R24 不是单个描述对象。固定诊断不回显模型原文或密钥。
