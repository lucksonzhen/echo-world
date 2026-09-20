import type { MediaFrame } from '../../shared/contracts';
import { checkCancelled, guardedOperation, MediaError, resizedDimensions, sampleVideoTimestamps, validateVideoDuration } from './helpers';
import type { PrepareOptions } from './types';

/** Decode locally; neither the video nor its audio is sent anywhere by this module. */
export async function prepareWebVideo(uri: string, options: PrepareOptions): Promise<{ frames: MediaFrame[]; durationMs: number }> {
  if (typeof document === 'undefined') throw new MediaError('当前环境无法读取视频。请在手机 App 或支持视频的浏览器中打开。');
  const video = document.createElement('video');
  video.muted = true;
  video.playsInline = true;
  video.preload = 'auto';
  const canvas = document.createElement('canvas');
  const context = canvas.getContext('2d');
  if (!context) throw new MediaError('当前浏览器无法处理视频画面。请尝试手机 App 或更新浏览器。');

  const waitFor = (event: 'loadeddata' | 'seeked', begin: () => void, message: string) => {
    let removeListeners = () => {};
    const ready = new Promise<void>((resolve, reject) => {
      const success = () => { removeListeners(); resolve(); };
      const failure = () => {
        removeListeners();
        reject(new MediaError('浏览器无法解码这个视频。请导出为 MP4（H.264）后再试。'));
      };
      removeListeners = () => {
        video.removeEventListener(event, success);
        video.removeEventListener('error', failure);
      };
      video.addEventListener(event, success, { once: true });
      video.addEventListener('error', failure, { once: true });
      begin();
    });
    return guardedOperation(ready, { signal: options.signal, timeoutMs: 20_000, timeoutMessage: message }).finally(removeListeners);
  };

  try {
    checkCancelled(options.signal);
    options.onProgress?.('正在读取视频时长…');
    await waitFor('loadeddata', () => { video.src = uri; video.load(); }, '读取视频超时。请检查文件是否完整，或尝试更短的片段。');
    const durationMs = validateVideoDuration(Math.round(video.duration * 1000));
    const dimensions = resizedDimensions(video.videoWidth, video.videoHeight);
    canvas.width = dimensions.width;
    canvas.height = dimensions.height;
    const timestamps = sampleVideoTimestamps(durationMs);
    const frames: MediaFrame[] = [];

    for (const [index, timestampMs] of timestamps.entries()) {
      checkCancelled(options.signal);
      options.onProgress?.(`正在提取视频画面 ${index + 1}/${timestamps.length}…`);
      const seconds = timestampMs / 1000;
      // Seeking to the already loaded first frame does not reliably emit "seeked".
      if (Math.abs(video.currentTime - seconds) > 0.001) {
        await waitFor('seeked', () => { video.currentTime = seconds; }, '提取视频画面超时。请重新导出这个片段后再试。');
      }
      checkCancelled(options.signal);
      context.drawImage(video, 0, 0, dimensions.width, dimensions.height);
      const dataUrl = canvas.toDataURL('image/jpeg', 0.75);
      if (!dataUrl.startsWith('data:image/jpeg;base64,')) throw new MediaError('无法生成视频画面，请尝试其他视频。');
      frames.push({ dataUrl, timestampMs });
    }
    return { frames, durationMs };
  } finally {
    video.pause();
    video.removeAttribute('src');
    video.load();
    canvas.width = 0;
    canvas.height = 0;
  }
}
