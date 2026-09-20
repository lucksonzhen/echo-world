import type { AnalysisRequest, AnalysisResult } from '../shared/contracts';
import { ApiError, invalidResponse } from './errors';
import { getProvider, type ProviderOptions } from './providers';
import { analysisResultSchema } from './schema';

export { ApiError } from './errors';
export { DESCRIPTION_INSTRUCTIONS } from './description-prompt';
export type { ProviderOptions } from './providers';

export async function describeMedia(
  request: AnalysisRequest,
  options: ProviderOptions,
  signal: AbortSignal,
): Promise<AnalysisResult> {
  const provider = getProvider(options.provider);
  const response = await provider.request(request, options, signal);
  if (!response.ok) {
    // Do not forward upstream error bodies: they may include request data or configuration.
    await response.body?.cancel();
    if (response.status === 429) throw new ApiError(503, 'UPSTREAM_RATE_LIMIT', '描述服务暂时繁忙，请稍后再试。');
    throw new ApiError(502, 'UPSTREAM_ERROR', '描述服务暂时不可用，请稍后重试或检查服务端配置。');
  }

  let raw: unknown;
  try {
    raw = await response.json();
  } catch (error) {
    if (signal.aborted) throw error;
    throw invalidResponse();
  }
  return validateDescription(provider.outputText(raw), request);
}

function validateDescription(text: string, request: AnalysisRequest): AnalysisResult {
  let parsed: unknown;
  try { parsed = JSON.parse(text); } catch { throw invalidResponse(); }
  const validated = analysisResultSchema.safeParse(parsed);
  if (!validated.success) throw invalidResponse();
  const result = validated.data;
  const timestamps = new Set(request.frames.map(frame => frame.timestampMs));
  if ((request.mediaType === 'image' && result.timeline.length > 0) ||
      result.timeline.some((entry, index) => !timestamps.has(entry.timestampMs) ||
        (index > 0 && entry.timestampMs <= result.timeline[index - 1].timestampMs))) {
    throw invalidResponse();
  }
  if (!request.question) result.answer = null;
  if (request.mediaType === 'video') {
    const limitation = '视频描述基于抽样画面，可能遗漏中间变化；没有分析视频声音。';
    result.uncertainties = [...result.uncertainties.slice(0, 19), limitation];
  }
  return result;
}
