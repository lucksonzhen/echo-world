import type { MediaFrame, MediaType } from '../../shared/contracts';

export interface SelectedMedia {
  uri: string;
  type: MediaType;
  name: string;
  width: number;
  height: number;
  durationMs?: number;
  fileSize?: number;
}

export interface PreparedMedia {
  frames: MediaFrame[];
  previewUri: string;
  mediaType: MediaType;
  durationMs?: number;
  cleanup: () => Promise<void>;
}

export interface PrepareOptions {
  onProgress?: (message: string) => void;
  signal?: AbortSignal;
}
