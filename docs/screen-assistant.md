# 屏幕助手使用说明

在其他 App 浏览时，用语音描述当前画面，无需下载或导入媒体。Android 提供本地语音待命和辅助悬浮控件；iPhone 使用 Siri 快捷指令。

## 平台能力

| 能力 | Android 11+ | iPhone / iOS 18+ |
| --- | --- | --- |
| 当前画面与文字 | 无障碍服务截图，语音触发 | 系统截屏 → App Intent → 朗读 |
| 视频观察 | 命令后约 8 秒采样 6 帧 | 仅描述当前截图 |
| 暂停后自动讲解 | 可选，依赖目标 App 的媒体会话 | 未实现 |
| 跨 App 悬浮控件 | 已实现，日常操作可用语音 | 本项目未提供 |
| 验证状态 | API 35 模拟器已接通真实 AI 与朗读 | 源码已实现，未编译或真机验证 |

Android 使用系统 [截图 API](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshot(int,java.util.concurrent.Executor,android.accessibilityservice.AccessibilityService.TakeScreenshotCallback)) 与 [无障碍悬浮层](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_ACCESSIBILITY_OVERLAY)。iPhone 设置见 [iOS 说明](../native/ios/README.md)；本表描述本项目能力，不代表所有 iOS 版本的系统能力。

## Android 首次设置

1. 按 [服务端说明](../server/README.md) 启动 AI 服务，在应用中填写服务地址和访问口令。模型密钥只放服务端。
2. 确认截图上传说明，通过系统设置启用“听见屏幕助手”无障碍服务；可使用 TalkBack 完成。
3. 回到应用，选择“允许麦克风并开启语音待命”，接受麦克风授权，等待开启提示。
4. 切回目标 App，说“小助手，描述屏幕”。悬浮控件只是辅助入口。

模拟器访问电脑使用 `http://10.0.2.2:服务端口`；真机使用电脑局域网地址或 HTTPS 服务，不能使用电脑的 `localhost`。

若安装后提示“受限设置”，按系统要求为可信安装包 [允许受限设置](https://support.google.com/android/answer/12623953?hl=en)，再开启无障碍服务。

语音待命显示麦克风标记和持续通知。重启或被系统终止后，需要回到应用重新开启；应用遵守 [麦克风前台服务限制](https://developer.android.com/develop/background-work/services/fgs/service-types#microphone)。

## 常用口令

| 说出“小助手”后接 | 效果 |
| --- | --- |
| 描述屏幕 / 看看画面 | 描述当前一张截图 |
| 读文字 | 优先读取清晰可见的文字 |
| 看看视频 | 观察随后约 8 秒的画面变化 |
| 左边的人在做什么？ | 对当前画面提问 |
| 开启暂停讲解 / 关闭暂停讲解 | 开关暂停后的自动描述 |
| 快一点 / 慢一点 / 正常语速 | 调整朗读速度 |
| 停止 / 取消 / 别读了 | 取消请求与朗读，关闭暂停讲解，保留语音待命 |
| 关闭语音监听 / 退出助手 | 取消当前任务并关闭麦克风待命 |

唤醒词须在句首。也可先说“小助手”，听到提示音后在 **8 秒内**说命令。

朗读时只接受带唤醒词的停止、关闭暂停讲解、关闭监听和调速口令；要换问题，先说“小助手，停止”。停止类口令可在稳定部分识别后提前执行，普通命令等待最终识别结果。

初始语速为 **1.35 倍**，每次调整 0.15，范围 1.0–1.8；“正常语速”为 1.0。设置保存在本机。

## 暂停后自动讲解

默认关闭；不在视频的静音间隙插话。

1. 说“小助手，开启暂停讲解”。按提示授予系统“通知使用权”，回到目标 App 后重新开启。
2. 等开启提示结束并稍候约 1 秒，播放视频，再通过原 App 的可访问控件或耳机播放键暂停。
3. 同一前台 App 的唯一媒体会话从播放变为暂停，并保持约 **500 毫秒**后，助手描述停住的当前一帧。
4. 继续播放会取消本次自动描述及朗读；助手不会替用户恢复视频。

每次暂停只触发一次。开启时已暂停、短暂停、缓冲、停止、可识别的视频结束或存在多个会话时，不自动讲解；可随时主动说“小助手，描述屏幕”。切换 App、锁屏或撤销权限会取消相关任务。

通知使用权仅用于读取活动媒体会话的播放状态、位置和时长，不处理通知正文或音轨。[系统授权依据](https://developer.android.com/reference/android/media/session/MediaSessionManager#getActiveSessions(android.content.ComponentName))

目标 App 必须公开播放状态。系统状态不能普遍区分用户暂停与通话、音频焦点导致的暂停；助手会忽略自身朗读附近的状态变化，但仍需按 App 验证。

## 声音与数据

- 语音识别使用内置 Vosk 中文模型，在本机处理，不保存或上传录音；模型许可见 [第三方声明](../native/android/THIRD_PARTY_NOTICES.md)。
- 截图和提问文字会发送到所配置的服务端，再由模型服务处理。首次需同意；仅在有效命令或已开启的暂停讲解触发时截图，不记录后台画面历史。
- 截图可能包含整屏聊天或通知。服务端处理范围见 [服务端说明](../server/README.md)；不能将本地语音识别等同于全程离线。
- 锁屏期间丢弃录音片段、不执行命令；关闭语音待命才会关闭麦克风。通话、系统录音权限和省电策略可能中断待命。
- 支持时启用系统回声消除和降噪，但没有声纹鉴别。视频或旁人说出同一口令仍可能误唤醒；外放干扰、停止延迟和耗电需真机测量。

## 内容边界与排查

- 视频采样不含音轨，也不能回看命令之前的画面；暂停描述仅基于当前一帧，不能据此还原之前动作。
- 系统保护的页面或视频可能无法截图或显示为空白，不能绕过。[Android 截图错误定义](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#ERROR_TAKE_SCREENSHOT_SECURE_WINDOW)
- 无响应：检查语音待命、无障碍服务、截图上传同意和服务连接；暂停不触发时再检查通知使用权及目标 App 的媒体会话支持。
- 构建与开发命令见 [开发说明](development.md)；验证结果与待测项目见 [验收表](acceptance.md)。
