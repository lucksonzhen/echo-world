# 动态视频演示

独立 Android 11+ 播放器，内置 40 秒、720p 的《Big Buck Bunny》片段及原声，无需联网播放。支持播放／暂停、拖动进度和重播；暂停定格，继续播放恢复运动。助手和 AI 服务需另外启动，播放器本身不生成描述。

## 构建与体验

配置 JDK 17+、Android SDK 35（`JAVA_HOME`、`ANDROID_HOME`），在仓库根目录运行；首次构建需要联网：

```powershell
native/android/gradlew.bat -p demo/android --no-daemon :app:assembleDebug
adb install -r demo/android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.tingjian.pausefixture/.MainActivity
```

其他系统构建时使用 `./native/android/gradlew -p demo/android --no-daemon :app:assembleDebug`。先按[使用说明](../../docs/screen-assistant.md)开启助手的暂停讲解，再在演示中播放并暂停：助手应描述当前帧；恢复播放应停止自动讲解。首次打开停在封面，不自动播放或触发讲解。

## 自动化联调

```sh
adb shell am start -n com.tingjian.pausefixture/.MainActivity --es state PLAYING --ei pauseAfterMs 2000
adb shell am start -n com.tingjian.pausefixture/.MainActivity --es state PAUSED --ei seekToMs 20000
```

- `state`：`PLAYING`、`PAUSED`、`STOPPED`、`BUFFERING`、`PAUSE_AND_RESUME_QUICK`。后两项分别模拟缓冲和 100 毫秒短暂停，用于检查误触发。
- `pauseAfterMs`、`resumeAfterMs`：延迟切换状态；新命令取消旧计划。
- `seekToMs`：跳转位置，首次播放前也更新真实帧预览；`replaceSession=true`：替换媒体会话，检查旧请求取消。

播放器通过真实 `MediaSession` 提供时长、位置与播放状态。播放结束为 `STOPPED`，不循环；暂停释放音频焦点，焦点恢复不自动播放。

素材 © 2008 Blender Foundation，按 [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/) 使用；来源、剪辑范围和校验值见[素材许可](ASSET_LICENSE.md)。
