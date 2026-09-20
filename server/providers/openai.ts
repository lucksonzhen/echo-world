import type { AnalysisRequest } from '../../shared/contracts';
import { DESCRIPTION_INSTRUCTIONS, requestContext } from '../description-prompt';
import { incompleteResponse, invalidResponse, refusedResponse } from '../errors';
import { resultJsonSchema } from '../schema';
import type { DescriptionProvider, ProviderOptions } from './index';

export const openAIProvider: DescriptionProvider = {
  request: requestOpenAI,
  outputText: openAIOutputText,
};

function requestOpenAI(request: AnalysisRequest, options: ProviderOptions, signal: AbortSignal): Promise<Response> {
  const content: Array<Record<string, unknown>> = [{
    type: 'input_text',
    text: requestContext(request),
  }];
  for (const frame of request.frames) {
    content.push({ type: 'input_text', text: `画面时间 timestampMs=${frame.timestampMs}` });
    content.push({ type: 'input_image', image_url: frame.dataUrl, detail: 'auto' });
  }
  return (options.fetchImpl ?? fetch)('https://api.openai.com/v1/responses', {
    method: 'POST',
    headers: { Authorization: `Bearer ${options.apiKey}`, 'Content-Type': 'application/json' },
    signal,
    body: JSON.stringify({
      model: options.model,
      store: false,
      instructions: DESCRIPTION_INSTRUCTIONS,
      input: [{ role: 'user', content }],
      max_output_tokens: 4000,
      text: { format: { type: 'json_schema', name: 'accessible_media_description', strict: true, schema: resultJsonSchema } },
    }),
  });
}

function openAIOutputText(raw: unknown): string {
  if (!raw || typeof raw !== 'object') throw invalidResponse();
  const envelope = raw as { status?: unknown; output?: unknown; error?: unknown };
  if (envelope.status !== 'completed' || envelope.error) {
    throw incompleteResponse();
  }
  if (!Array.isArray(envelope.output)) throw invalidResponse();
  const parts: string[] = [];
  for (const item of envelope.output) {
    if (!item || item.type !== 'message') continue;
    if (item.status !== undefined && item.status !== 'completed') {
      throw incompleteResponse();
    }
    if (!Array.isArray(item.content)) continue;
    for (const part of item.content) {
      if (part?.type === 'refusal') throw refusedResponse();
      if (part?.type === 'output_text' && typeof part.text === 'string') parts.push(part.text);
    }
  }
  return parts.join('');
}
