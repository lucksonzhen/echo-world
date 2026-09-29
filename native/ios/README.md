# iPhone 屏幕描述

iOS 18+ 原生客户端，通过 **系统截屏 → 听见世界描述 → 系统朗读** 快捷指令读取当前画面，无需下载或导入媒体。

**当前为 Swift 源码、XcodeGen 配置和 XCTest 用例；Windows 环境尚未完成 iOS 编译、签名、安装或真机验证。** 本项目未提供跨 App 悬浮窗、连续视频观察或暂停自动讲解。

## 在 Mac 上构建

需要 Xcode 16+、对应 iOS SDK 和 [XcodeGen](https://github.com/yonaskolb/XcodeGen)：

```sh
brew install xcodegen
cd native/ios
xcodegen generate
open EchoWorld.xcodeproj
```

在 `EchoWorld` 的 Signing & Capabilities 中选择开发团队，按需修改 bundle ID，再选择 iPhone 运行。发布前需补齐应用图标、签名、隐私申报和真机验收。

模拟器测试（设备名以 `xcrun simctl list devices available` 为准）：

```sh
xcodebuild -project EchoWorld.xcodeproj -scheme EchoWorld \
  -destination 'platform=iOS Simulator,name=iPhone 16' test
```

`Tests/EchoWorldTests.swift` 覆盖地址约束、请求契约、图片处理与上传开关；本环境未执行。

## 一次设置

1. 按 [服务端说明](../../server/README.md) 启动服务；模型密钥只放服务端。
2. 打开“听见世界”，填写服务地址与访问口令，允许截图上传，检查连接并保存。正式构建仅接受 HTTPS；Debug 可使用电脑私网 HTTP 地址，不能用手机的 `localhost`。
3. 在系统“快捷指令”中新建 **听见当前屏幕**，依次添加下表动作。

| 顺序 | 动作 | 输入 |
| --- | --- | --- |
| 1 | 系统“截取屏幕截图 / Take Screenshot” | 当前画面；此前不要打开助手 App |
| 2 | 本 App“听见世界描述” | 上一步的截图变量，选择描述方式，可留空问题 |
| 3 | 系统“朗读文本 / Speak Text” | 描述动作的返回文本，在此调整语速 |

停留在目标 App，说“嘿 Siri，听见当前屏幕”。也可绑定轻点背面或操作按钮；无需添加保存到相册动作。仓库不提供预制快捷指令分享链接。

依据：[Siri 运行快捷指令](https://support.apple.com/guide/shortcuts/run-shortcuts-with-siri-apd07c25bb38/ios)、[轻点背面](https://support.apple.com/111772)、[IntentFile](https://developer.apple.com/documentation/appintents/intentfile)。

## 数据与限制

- 截图在内存中缩放到最长边 1600 像素、转为 JPEG 并去除元数据，再通过后端送往模型；只描述当前一帧，不分析音轨。
- 设置和口令保存在本机 Keychain，需解锁读取；不建立截图或描述历史，不跟随网络重定向。系统快捷指令仍可能使用临时文件。
- 关闭上传开关立即阻止后续上传；已发送的请求无法撤回。锁屏需解锁，受保护画面可能为空白，慢请求可能被系统中止。
- Siri 面板可能遮挡取景，需在具体系统验证；若 Siri 与“朗读文本”重复朗读，可省略后者，实体按钮触发则建议保留。

按 [验收表](../../docs/acceptance.md) 检查 VoiceOver、真实截图、错误恢复和中文朗读；Android 的验证结果不代表 iPhone 已通过。
