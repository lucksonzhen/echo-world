import { z } from 'zod';
import type { AnalysisRequest, AnalysisResult } from '../shared/contracts';

export const MAX_FRAME_BYTES = 2 * 1024 * 1024;
export const MAX_TOTAL_IMAGE_BYTES = 8 * 1024 * 1024;
export const MAX_VIDEO_DURATION_MS = 120_000;
export const MAX_FRAMES = 12;

/** Only inline images are accepted, so a client cannot make the server fetch arbitrary URLs. */
function imageBytes(dataUrl: string): number | null {
  const match = /^data:image\/(jpeg|png|webp);base64,([A-Za-z0-9+/]+={0,2})$/.exec(dataUrl);
  if (!match || match[2].length % 4 !== 0) return null;
  const bytes = Buffer.from(match[2], 'base64');
  if (bytes.toString('base64') !== match[2]) return null;
  const format = match[1];
  const valid = format === 'jpeg'
    ? bytes.length >= 4 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff
    : format === 'png'
      ? bytes.length >= 8 && bytes.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]))
      : bytes.length >= 12 && bytes.toString('ascii', 0, 4) === 'RIFF' && bytes.toString('ascii', 8, 12) === 'WEBP';
  return valid ? bytes.length : null;
}

export const analysisRequestSchema = z.object({
  mediaType: z.enum(['image', 'video']),
  mode: z.enum(['brief', 'detailed', 'text']),
  frames: z.array(z.object({
    dataUrl: z.string().min(1).max(Math.ceil(MAX_FRAME_BYTES / 3) * 4 + 64),
    timestampMs: z.number().int().min(0).max(MAX_VIDEO_DURATION_MS),
  }).strict()).min(1).max(MAX_FRAMES),
  durationMs: z.number().int().positive().max(MAX_VIDEO_DURATION_MS).optional(),
  question: z.string().trim().min(1).max(500).optional(),
}).strict().superRefine((request, ctx) => {
  let total = 0;
  for (const [index, frame] of request.frames.entries()) {
    const size = imageBytes(frame.dataUrl);
    if (size === null || size > MAX_FRAME_BYTES) {
      ctx.addIssue({ code: 'custom', path: ['frames', index, 'dataUrl'], message: '请提供不超过 2 MB 的有效 JPEG、PNG 或 WebP 图片。' });
    } else {
      total += size;
    }
  }
  if (total > MAX_TOTAL_IMAGE_BYTES) {
    ctx.addIssue({ code: 'custom', path: ['frames'], message: '上传图片总大小不能超过 8 MB。' });
  }
  if (request.mediaType === 'image') {
    if (request.frames.length !== 1 || request.frames[0]?.timestampMs !== 0 || request.durationMs !== undefined) {
      ctx.addIssue({ code: 'custom', path: ['frames'], message: '图片应只有一帧，时间为零，且不包含视频时长。' });
    }
  } else {
    if (request.durationMs === undefined) {
      ctx.addIssue({ code: 'custom', path: ['durationMs'], message: '请提供视频时长。' });
    }
    request.frames.forEach((frame, index) => {
      if ((request.durationMs !== undefined && frame.timestampMs >= request.durationMs) ||
          (index > 0 && frame.timestampMs <= request.frames[index - 1].timestampMs)) {
        ctx.addIssue({ code: 'custom', path: ['frames', index, 'timestampMs'], message: '视频帧时间须依次递增，并小于视频时长。' });
      }
    });
  }
});

const shortText = z.string().trim().min(1).max(1500);
export const analysisResultSchema = z.object({
  title: z.string().trim().min(1).max(150),
  summary: shortText,
  details: z.array(shortText).max(20),
  visibleText: z.array(z.string().trim().min(1).max(3000)).max(30),
  timeline: z.array(z.object({
    timestampMs: z.number().int().min(0).max(MAX_VIDEO_DURATION_MS),
    description: shortText,
  }).strict()).max(MAX_FRAMES),
  uncertainties: z.array(shortText).max(20),
  answer: z.string().trim().min(1).max(3000).nullable(),
}).strict();

// These assignments also ensure runtime validators stay compatible with the public contract.
const _requestType: z.ZodType<AnalysisRequest> = analysisRequestSchema;
const _resultType: z.ZodType<AnalysisResult> = analysisResultSchema;
void _requestType;
void _resultType;

export const resultJsonSchema = {
  type: 'object',
  additionalProperties: false,
  required: ['title', 'summary', 'details', 'visibleText', 'timeline', 'uncertainties', 'answer'],
  properties: {
    title: { type: 'string' },
    summary: { type: 'string' },
    details: { type: 'array', items: { type: 'string' } },
    visibleText: { type: 'array', items: { type: 'string' } },
    timeline: {
      type: 'array',
      items: {
        type: 'object', additionalProperties: false,
        required: ['timestampMs', 'description'],
        properties: { timestampMs: { type: 'integer' }, description: { type: 'string' } },
      },
    },
    uncertainties: { type: 'array', items: { type: 'string' } },
    answer: { type: ['string', 'null'] },
  },
} as const;
