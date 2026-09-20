export const MAX_VIDEO_DURATION_MS = 120_000;
export const MAX_VIDEO_FRAMES = 12;
export const MAX_IMAGE_EDGE = 1280;

export class MediaError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'MediaError';
  }
}

export function cancelledError(): Error {
  const error = new Error('已取消处理。');
  error.name = 'AbortError';
  return error;
}

export function checkCancelled(signal?: AbortSignal): void {
  if (signal?.aborted) throw cancelledError();
}

export function validateVideoDuration(durationMs: number | undefined): number {
  if (durationMs === undefined || !Number.isFinite(durationMs) || durationMs <= 0) {
    throw new MediaError('无法读取视频时长。请使用手机相册中的完整视频，或重新导出为 MP4 后再试。');
  }
  if (durationMs > MAX_VIDEO_DURATION_MS) {
    throw new MediaError('目前支持 2 分钟以内的视频。请先在相册中截取想了解的片段。');
  }
  return durationMs;
}

/** Include the opening and a decodable position close to the end, never the EOF itself. */
export function sampleVideoTimestamps(durationMs: number): number[] {
  const duration = validateVideoDuration(durationMs);
  const count = Math.min(MAX_VIDEO_FRAMES, Math.max(2, Math.ceil(duration / 5000) + 1));
  const end = Math.max(0, Math.floor(duration - Math.min(150, duration * 0.1)));
  return [...new Set(Array.from({ length: count }, (_, index) => Math.round((end * index) / (count - 1))))];
}

export function resizedDimensions(width: number, height: number): { width: number; height: number } {
  if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) {
    throw new MediaError('无法读取画面尺寸。文件可能已损坏，请换一张图片或视频。');
  }
  const scale = Math.min(1, MAX_IMAGE_EDGE / Math.max(width, height));
  return { width: Math.max(1, Math.round(width * scale)), height: Math.max(1, Math.round(height * scale)) };
}

/** Native decoding cannot be interrupted; discard and clean up a result that arrives after cancellation. */
export function guardedOperation<T>(
  operation: Promise<T>,
  options: { signal?: AbortSignal; timeoutMs: number; timeoutMessage: string; onLateResult?: (result: T) => void | Promise<void> },
): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    let settled = false;
    const finish = (error?: Error) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      options.signal?.removeEventListener('abort', abort);
      if (error) reject(error);
    };
    const abort = () => finish(cancelledError());
    const timer = setTimeout(() => finish(new MediaError(options.timeoutMessage)), options.timeoutMs);
    options.signal?.addEventListener('abort', abort, { once: true });
    if (options.signal?.aborted) abort();
    operation.then((result) => {
      if (settled) {
        Promise.resolve().then(() => options.onLateResult?.(result)).catch(() => undefined);
        return;
      }
      finish();
      resolve(result);
    }, (error: unknown) => {
      if (settled) return;
      finish();
      reject(error);
    });
  });
}
