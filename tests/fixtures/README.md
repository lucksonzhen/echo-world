`motion.mp4` 是本项目生成的 3 秒彩色测试信号，不含个人内容或音轨。浏览器测试用它验证实际解码、不同时间点的 JPEG 抽帧和仅上传画面。

生成命令（只需在重建测试素材时安装 FFmpeg）：

```sh
ffmpeg -f lavfi -i testsrc2=size=320x240:rate=10 -t 3 -c:v libx264 -pix_fmt yuv420p -an motion.mp4
```
