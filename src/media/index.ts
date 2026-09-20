import { Platform } from 'react-native';
import * as ImagePicker from 'expo-image-picker';
import { ImageManipulator, SaveFormat } from 'expo-image-manipulator';
import * as VideoThumbnails from 'expo-video-thumbnails';
import { File, Paths } from 'expo-file-system';
import type { MediaFrame } from '../../shared/contracts';
import { cancelledError, checkCancelled, guardedOperation, MediaError, resizedDimensions, sampleVideoTimestamps, validateVideoDuration } from './helpers';
import type { PreparedMedia, PrepareOptions, SelectedMedia } from './types';
import { prepareWebVideo } from './webVideo';

export type { PreparedMedia, PrepareOptions, SelectedMedia } from './types';
export { MAX_VIDEO_DURATION_MS, MAX_VIDEO_FRAMES } from './helpers';

const preparedCleanups = new WeakMap<SelectedMedia, Set<() => Promise<void>>>();
const selectedObjectUrls = new WeakMap<SelectedMedia, string>();

async function deleteGeneratedFile(uri: string): Promise<void> {
  try {
    if (Platform.OS === 'web') {
      if (uri.startsWith('blob:')) URL.revokeObjectURL(uri);
      return;
    }
    // This helper is only called for explicitly generated files; also enforce the app cache boundary.
    const target = new URL(uri);
    const cache = new URL(Paths.cache.uri);
    const cachePath = cache.pathname.replace(/\/?$/, '/');
    if (target.protocol !== 'file:' || target.host !== cache.host || !target.pathname.startsWith(cachePath)) return;
    const file = new File(uri);
    if (file.exists) file.delete();
  } catch {
    // OS cache eviction is harmless. Cleanup failure must not discard a successful description.
  }
}

function displayPickerError(error: unknown): Error {
  if (error instanceof MediaError) return error;
  return new MediaError('无法打开或读取所选媒体。请确认相机或相册权限，并将云端文件下载到手机后重试。');
}

export async function pickMedia(kind: 'image' | 'video' | 'camera'): Promise<SelectedMedia | null> {
  try {
    if (kind === 'camera' && Platform.OS !== 'web') {
      const permission = await ImagePicker.requestCameraPermissionsAsync();
      if (!permission.granted) throw new MediaError('相机权限未开启。请在系统设置中允许“听见画面”使用相机，或改为从相册选择图片。');
    }
    if (kind === 'video' && Platform.OS === 'ios') {
      const permission = await ImagePicker.requestMediaLibraryPermissionsAsync();
      if (!permission.granted) throw new MediaError('读取原始视频需要相册权限。请在系统设置中允许访问所选视频，然后再试。');
    }
    // Call the web picker in the original click gesture; do not await permission calls on web.
    const result = kind === 'camera'
      ? await ImagePicker.launchCameraAsync({ mediaTypes: ['images'], allowsEditing: false, quality: 1, exif: false })
      : await ImagePicker.launchImageLibraryAsync({
        mediaTypes: kind === 'video' ? ['videos'] : ['images'],
        allowsEditing: false,
        allowsMultipleSelection: false,
        quality: 1,
        exif: false,
        shouldDownloadFromNetwork: true,
      });
    if (result.canceled) return null;
    const asset = result.assets[0];
    if (!asset?.uri) throw new MediaError('没有读取到所选文件，请重新选择。');
    const type = asset.type === 'video' || kind === 'video' ? 'video' : 'image';
    const durationMs = typeof asset.duration === 'number' ? asset.duration : undefined;
    if (type === 'video' && durationMs !== undefined && durationMs > 120_000) validateVideoDuration(durationMs);
    const selected: SelectedMedia = {
      uri: asset.uri,
      type,
      name: asset.fileName || (type === 'video' ? '已选择的视频' : kind === 'camera' ? '刚拍摄的照片' : '已选择的图片'),
      width: asset.width,
      height: asset.height,
      durationMs,
      fileSize: asset.fileSize,
    };
    if (Platform.OS === 'web' && asset.file) {
      const objectUrl = URL.createObjectURL(asset.file);
      selected.uri = objectUrl;
      selectedObjectUrls.set(selected, objectUrl);
    }
    return selected;
  } catch (error) {
    throw displayPickerError(error);
  }
}

async function compressImage(uri: string, width: number, height: number) {
  const context = ImageManipulator.manipulate(uri);
  let original: Awaited<ReturnType<typeof context.renderAsync>> | undefined;
  let rendered: Awaited<ReturnType<typeof context.renderAsync>> | undefined;
  try {
    if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) {
      original = await context.renderAsync();
      width = original.width;
      height = original.height;
    }
    const size = resizedDimensions(width, height);
    context.resize(size);
    rendered = await context.renderAsync();
    return await rendered.saveAsync({ format: SaveFormat.JPEG, compress: 0.75, base64: true });
  } finally {
    // The web implementation creates an intermediate blob URL for every rendered ImageRef.
    // SharedRef.release() releases the native handle but does not revoke those browser URLs.
    if (Platform.OS === 'web') {
      for (const reference of [rendered, original]) {
        if (reference && 'uri' in reference && typeof reference.uri === 'string') {
          await deleteGeneratedFile(reference.uri);
        }
      }
    }
    rendered?.release();
    if (original && original !== rendered) original.release();
    context.release();
  }
}

export async function prepareMedia(media: SelectedMedia, options: PrepareOptions = {}): Promise<PreparedMedia> {
  checkCancelled(options.signal);
  const generated = new Set<string>();
  let disposed = false;
  const checkActive = () => {
    checkCancelled(options.signal);
    if (disposed) throw cancelledError();
  };
  let cleanups = preparedCleanups.get(media);
  if (!cleanups) {
    cleanups = new Set();
    preparedCleanups.set(media, cleanups);
  }
  const cleanup = async () => {
    disposed = true;
    const files = [...generated];
    generated.clear();
    await Promise.all(files.map(deleteGeneratedFile));
    cleanups.delete(cleanup);
  };
  cleanups.add(cleanup);

  const compress = async (uri: string, width: number, height: number) => {
    checkActive();
    const result = await guardedOperation(compressImage(uri, width, height), {
      signal: options.signal,
      timeoutMs: 30_000,
      timeoutMessage: '处理画面超时。请尝试较小的图片或更短的视频片段。',
      onLateResult: (late) => deleteGeneratedFile(late.uri),
    });
    generated.add(result.uri);
    checkActive();
    if (!result.base64) throw new MediaError('无法读取画面内容，请重新选择文件。');
    return { uri: result.uri, dataUrl: `data:image/jpeg;base64,${result.base64}` };
  };

  try {
    if (media.type === 'image') {
      options.onProgress?.('正在压缩图片并移除拍摄信息…');
      const image = await compress(media.uri, media.width, media.height);
      return { frames: [{ dataUrl: image.dataUrl, timestampMs: 0 }], previewUri: image.uri, mediaType: 'image', cleanup };
    }
    if (Platform.OS === 'web') {
      const video = await prepareWebVideo(media.uri, options);
      checkActive();
      return { ...video, previewUri: video.frames[0].dataUrl, mediaType: 'video', cleanup };
    }

    const durationMs = validateVideoDuration(media.durationMs);
    const timestamps = sampleVideoTimestamps(durationMs);
    const frames: MediaFrame[] = [];
    let previewUri = '';
    for (const [index, timestampMs] of timestamps.entries()) {
      checkActive();
      options.onProgress?.(`正在提取视频画面 ${index + 1}/${timestamps.length}…`);
      const thumbnail = await guardedOperation(VideoThumbnails.getThumbnailAsync(media.uri, { time: timestampMs, quality: 0.9 }), {
        signal: options.signal,
        timeoutMs: 20_000,
        timeoutMessage: '提取视频画面超时。请确认视频可在相册播放，或截取更短的片段后重试。',
        onLateResult: (late) => deleteGeneratedFile(late.uri),
      });
      generated.add(thumbnail.uri);
      const frame = await compress(thumbnail.uri, thumbnail.width, thumbnail.height);
      await deleteGeneratedFile(thumbnail.uri);
      generated.delete(thumbnail.uri);
      frames.push({ dataUrl: frame.dataUrl, timestampMs });
      if (!previewUri) previewUri = frame.uri;
      else {
        await deleteGeneratedFile(frame.uri);
        generated.delete(frame.uri);
      }
    }
    checkActive();
    return { frames, previewUri, mediaType: 'video', durationMs, cleanup };
  } catch (error) {
    await cleanup();
    if (error instanceof MediaError || (error instanceof Error && error.name === 'AbortError')) throw error;
    throw new MediaError(media.type === 'video'
      ? '无法解码这个视频。请确认它能在相册中播放，或重新导出为 MP4 后再试。'
      : '无法读取这张图片。请重新选择，或在相册中另存为 JPEG 后再试。');
  }
}

/** Deletes only files generated while preparing this item and object URLs created by this module. */
export async function releaseMedia(media: SelectedMedia): Promise<void> {
  const cleanups = preparedCleanups.get(media);
  if (cleanups) await Promise.all([...cleanups].map((cleanup) => cleanup()));
  preparedCleanups.delete(media);
  const objectUrl = selectedObjectUrls.get(media);
  if (objectUrl) {
    URL.revokeObjectURL(objectUrl);
    selectedObjectUrls.delete(media);
  }
}
