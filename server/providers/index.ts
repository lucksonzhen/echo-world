import type { AnalysisRequest } from '../../shared/contracts';
import { geminiProvider } from './gemini';
import { openAIProvider } from './openai';

export type AIProvider = 'openai' | 'gemini';

export const DEFAULT_MODELS: Record<AIProvider, string> = {
  openai: 'gpt-4.1-mini',
  gemini: 'gemini-2.5-flash',
};

export interface ProviderOptions {
  apiKey: string;
  model: string;
  provider?: AIProvider;
  fetchImpl?: typeof fetch;
}

export interface DescriptionProvider {
  request(request: AnalysisRequest, options: ProviderOptions, signal: AbortSignal): Promise<Response>;
  outputText(raw: unknown): string;
}

export function getProvider(provider: AIProvider = 'openai'): DescriptionProvider {
  return provider === 'gemini' ? geminiProvider : openAIProvider;
}
