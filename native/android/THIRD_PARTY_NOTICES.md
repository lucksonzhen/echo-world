# 离线语音模型和依赖

本 App 的离线语音识别基于 Alpha Cephei 的 Vosk。麦克风音频在手机本地处理；图片理解仍使用用户配置的服务，不能把整个产品称为全离线。

## 中文语音模型

默认构建使用大模型，对真人口音和噪声的识别明显更好；低存储设备可用 `--small` 回退到小模型。两者同样是 Vosk 官方模型、Apache License 2.0。

### 大模型（默认）

- 名称：`vosk-model-cn-0.22`
- 官方下载：[固定 ZIP 文件](https://alphacephei.com/vosk/models/vosk-model-cn-0.22.zip)
- 来源与许可：[Vosk 官方模型列表](https://alphacephei.com/vosk/models)，该模型标为 **Apache License 2.0**。
- ZIP 文件大小：1,358,736,686 字节（约 1.27 GiB）。官方列表标注为 1.3G。
- SHA-256：`7f5580bf7ca3d9e8ce8be68337378950ed78ef974df7a7d4ead8dbe32bec2fa1`
- 解压后约 2.0 GiB；识别时内存需求显著高于小模型，低配手机请实测或改用小模型。

### 小模型（`--small`）

- 名称：`vosk-model-small-cn-0.22`
- 官方下载：[固定 ZIP 文件](https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip)
- ZIP 文件大小：43,898,754 字节（约 41.87 MiB）。官方列表四舍五入为 42M。
- SHA-256：`3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba`

校验值来自本次从上述官方 HTTPS 地址取得的完整文件，用于固定构建输入；不是对官方签名或官方公布摘要的声明。两个模型的常量必须与 `OfflineModelStore.java` 保持一致。

开发者在构建前运行（默认大模型，加 `--small` 使用小模型）：

```sh
node native/android/prepare-voice-model.mjs
```

脚本把模型流式下载到 `app/src/main/assets/voice-model-cn.zip`，检查固定大小与 SHA-256 后才替换最终文件，并生成记录模型来源的 `voice-model-notice.txt`。APK 内置此模型，用户安装应用后不用另找文件或手动下载。模型 ZIP、notice 和下载临时文件不加入 Git；重新构建前运行脚本即可复现。

首次启用语音时，调用 `OfflineModelStore.prepare(context)`，**必须在工作线程调用**。它按校验值识别 APK 里是大模型还是小模型，再次验证压缩包，安全解压到 `context.getNoBackupFilesDir()` 下对应目录（大模型为 `vosk-cn-0.22-large`，小模型为 `vosk-cn-0.22`），验证必要文件后写入完成标记并移动到最终目录；切换模型版本后会清理另一份模型的存储。方法返回可直接传给 `new org.vosk.Model(path)` 的模型目录。失败或中断后可以再次调用重试；尚未完成的目录不会被当作有效模型。模型不放入共享存储或设备备份。

## Vosk Android

- 官方项目：[alphacep/vosk-api](https://github.com/alphacep/vosk-api)
- Android 官方示例：[alphacep/vosk-android-demo](https://github.com/alphacep/vosk-android-demo)
- 构建依赖：`com.alphacephei:vosk-android:0.3.75@aar`
- 项目许可证：Apache License 2.0；原始声明和许可见官方仓库 [COPYING](https://github.com/alphacep/vosk-api/blob/master/COPYING)。本目录 `licenses/VOSK-APACHE-2.0.txt` 保留该许可全文。
- 官方示例同时使用 `net.java.dev.jna:jna:5.18.1@aar`。JNA 官方采用 Apache-2.0 或 LGPL-2.1-or-later 双重许可，允许使用者选择；本项目选择 Apache-2.0。原始许可声明保存在 `licenses/JNA-LICENSE.txt`，同时保留依赖 AAR/JAR 自带的许可声明。来源：[JNA 5.18.1 LICENSE](https://github.com/java-native-access/jna/blob/5.18.1/LICENSE)。

模型由官方独立发布，模型列表的许可条目是该模型的许可依据；不要把其他语言或其他 Vosk 模型的许可自动套用于此模型。
