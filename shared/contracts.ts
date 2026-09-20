export type DescriptionMode = 'brief' | 'detailed' | 'text';
export type MediaType = 'image' | 'video';
export interface MediaFrame {
  dataUrl: string;
  timestampMs: number;
}
export interface AnalysisRequest {
  mediaType: MediaType;
  mode: DescriptionMode;
  frames: MediaFrame[];
  durationMs?: number;
  question?: string;
}
export interface AnalysisResult {
  title: string;
  summary: string;
  details: string[];
  visibleText: string[];
  timeline: { timestampMs: number; description: string }[];
  uncertainties: string[];
  answer: string | null;
}
