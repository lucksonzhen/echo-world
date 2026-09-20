import type { AnalysisRequest } from '../../shared/contracts';
import { DESCRIPTION_INSTRUCTIONS, requestContext } from '../description-prompt';
import { ApiError, incompleteResponse, invalidResponse, refusedResponse } from '../errors';
import { resultJsonSchema } from '../schema';
import type { DescriptionProvider, ProviderOptions } from './index';

export const geminiProvider: DescriptionProvider = {
  request: requestGemini,
  outputText: geminiOutputText,
};

function requestGemini(request: AnalysisRequest, options: ProviderOptions, signal: AbortSignal): Promise<Response> {
  const parts: Array<Record<string, unknown>> = [{ text: requestContext(request) }];
  for (const frame of request.frames) {
    const image = /^data:(image\/(?:jpeg|png|webp));base64,([A-Za-z0-9+/]+={0,2})$/.exec(frame.dataUrl);
    if (!image) throw new ApiError(400, 'INVALID_REQUEST', '请提供有效的图片数据。');
    parts.push({ text: `画面时间 timestampMs=${frame.timestampMs}` });
    parts.push({ inlineData: { mimeType: image[1], data: image[2] } });
  }
  const model = encodeURIComponent(options.model.replace(/^models\//, ''));
  return (options.fetchImpl ?? fetch)(`https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`, {
    method: 'POST',
    headers: { 'x-goog-api-key': options.apiKey, 'Content-Type': 'application/json' },
    signal,
    body: JSON.stringify({
      store: false,
      systemInstruction: { parts: [{ text: DESCRIPTION_INSTRUCTIONS }] },
      contents: [{ role: 'user', parts }],
      generationConfig: { responseMimeType: 'application/json', responseJsonSchema: resultJsonSchema, maxOutputTokens: 4000 },
    }),
  });
}

function isObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function geminiOutputText(raw: unknown): string {
  if (!isObject(raw) || raw.error) throw invalidResponse();
  const feedback = raw.promptFeedback;
  if (isObject(feedback) && typeof feedback.blockReason === 'string' && feedback.blockReason !== 'BLOCK_REASON_UNSPECIFIED') {
    throw refusedResponse();
  }
  if (!Array.isArray(raw.candidates) || raw.candidates.length !== 1 || !isObject(raw.candidates[0])) throw invalidResponse();
  const candidate = raw.candidates[0];
  const blockedReasons = ['SAFETY', 'RECITATION', 'BLOCKLIST', 'PROHIBITED_CONTENT', 'SPII', 'IMAGE_SAFETY', 'IMAGE_PROHIBITED_CONTENT', 'IMAGE_RECITATION'];
  if (typeof candidate.finishReason === 'string' && blockedReasons.includes(candidate.finishReason)) throw refusedResponse();
  if (Array.isArray(candidate.safetyRatings) && candidate.safetyRatings.some(rating => isObject(rating) && rating.blocked === true)) throw refusedResponse();
  if (candidate.finishReason !== 'STOP') {
    throw incompleteResponse();
  }
  if (!isObject(candidate.content) || !Array.isArray(candidate.content.parts)) throw invalidResponse();
  const parts: string[] = [];
  for (const part of candidate.content.parts) {
    if (!isObject(part)) throw invalidResponse();
    if (part.thought === true) continue;
    if (typeof part.text !== 'string') throw invalidResponse();
    parts.push(part.text);
  }
  return parts.join('');
}
