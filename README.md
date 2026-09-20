# 听见屏幕

An AI screen assistant for blind users.

帮助盲人理解其他 App 中的图片、文字和视频画面，无需下载或导入媒体。

- **Android 11+**：说“小助手，描述屏幕”；也可开启暂停讲解，视频暂停后自动描述当前帧，继续播放即停止。
- **iPhone**：通过 Siri 快捷指令“截屏 → 描述 → 朗读”。目前提供源码，需在 Mac 上构建。
- **AI**：支持 Gemini 和 OpenAI；语音唤醒在手机本地完成，画面识别由服务端调用模型。

## 快速开始

本机演示使用 Node.js 24.5+，在项目根目录运行：

```powershell
npm install
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
# 编辑 .env，填入所选 AI 提供方的密钥后启动
npm run server:local
```

首次运行在启动服务前，按[服务端说明](server/README.md)填写 `.env` 中的 API 密钥，默认端口 `8787`。已有 `.env` 时跳过复制，保留现有配置。

1. 按[开发说明](docs/development.md)构建并安装 Android 调试 APK，仓库不包含构建产物。
2. 打开“听见屏幕”，设置服务地址。默认配置下，模拟器用 `http://10.0.2.2:8787`，真机用电脑的局域网地址；更改端口时同步修改手机设置。
3. 使用 TalkBack 完成屏幕读取授权，允许麦克风并开启语音待命。
4. 回到视频或图片所在 App，说“小助手，描述屏幕”。需要暂停后自动讲解时，再开启“暂停讲解”。

常用命令：**描述屏幕、读文字、看看视频、开启／关闭暂停讲解、快一点／慢一点、停止**，前面加“小助手”。

## 当前状态

已在 Android 模拟器跑通 **暂停 → 截图 → Gemini 真实识别 → 中文朗读**。真人麦克风、真机和盲人用户体验仍待验收。

暂停讲解只适用于提供播放状态的 App；截图受保护的页面无法识别。视频理解使用抽样画面，不分析原声。截图会发送给所配置的 AI 服务，密钥只保存在服务端。

## 文档

| 需要做什么 | 文档 |
| --- | --- |
| 安装、授权和语音操作 | [使用说明](docs/screen-assistant.md) |
| 配置模型、地址与接口 | [服务端](server/README.md) |
| 了解代码结构、构建和测试 | [开发说明](docs/development.md) |
| 检查已验证与待验证项目 | [验收清单](docs/acceptance.md) |
| 运行动态视频演示 | [演示播放器](demo/android/README.md) |
| 构建 iPhone 版本 | [iOS](native/ios/README.md) |
| 使用备用的相册导入原型 | [Expo 原型](docs/media-prototype.md) |
