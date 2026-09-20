# Demo video attribution

`app/src/main/res/raw/demo_video.mp4` is a 40-second excerpt of **Big Buck Bunny** (2008).

**(c) copyright 2008, Blender Foundation / www.bigbuckbunny.org**

The film is licensed under [Creative Commons Attribution 3.0 Unported (CC BY 3.0)](https://creativecommons.org/licenses/by/3.0/). The project’s [official licensing and attribution information](https://peach.blender.org/about/) explicitly permits reuse of parts of the movie with the attribution above. This demo does not imply endorsement by Blender Foundation.

## Source and changes

- Official film download page: https://peach.blender.org/download/
- Source archive: https://download.blender.org/peach/bigbuckbunny_movies/big_buck_bunny_720p_stereo.ogg.zip
- Source movie: `big_buck_bunny_720p_stereo.ogg` (1280 × 720, 24 fps, original stereo soundtrack).
- Selected excerpt: **00:45.000–01:25.000** of the source movie.
- Changes: cut to 40 seconds; re-encoded Theora video to H.264 and Vorbis soundtrack to AAC; added MP4 title and attribution metadata. Original picture dimensions, frame rate, and stereo soundtrack are retained.
- Output: 1280 × 720, 24 fps, H.264/yuv420p, AAC stereo 48 kHz, 8,719,850 bytes.
- Output SHA-256: `689c246613a479b09bee123eee24b7a475fce24ab4aade294c9406162226f86f`.
- `app/src/main/res/drawable-nodpi/demo_poster.jpg` is a JPEG extracted from the first frame of this excerpt for the initial paused preview. It carries the same attribution and license.

The excerpt shows the rabbit emerging into the meadow, stretching, walking, smelling flowers, and watching butterflies. It contains genuine animated motion and the original mixed soundtrack, rather than simulated player-state graphics. The separately released isolated musical score is not used.

## Reproduction

The official ZIP stores the OGG entry without compression. To avoid downloading the whole film, the build preparation downloaded bytes 0–49,999,999 of the ZIP, extracted the OGG payload after its 88-byte local header, and encoded a complete interval well before the partial source ends (approximately 160 seconds). The resulting 40-second MP4 was fully decoded by FFmpeg without errors.

```powershell
ffmpeg -ss 45 -i bbb-720-prefix.ogg -t 40 -map 0:v:0 -map 0:a:0 `
  -c:v libx264 -preset medium -crf 25 -pix_fmt yuv420p -profile:v high `
  -level:v 3.1 -r 24 -g 48 -c:a aac -b:a 128k -ac 2 -ar 48000 `
  -movflags +faststart demo_video.mp4
```

Retain this attribution when redistributing the asset and display a compact credit in the demo player.
