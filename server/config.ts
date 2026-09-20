import type { ServerOptions } from './app';
import { DEFAULT_MODELS } from './providers';

/** Select one provider explicitly; never send a failed request to another provider. */
export function readServerConfig(env: NodeJS.ProcessEnv = process.env) {
  const provider = env.AI_PROVIDER?.trim() || 'openai';
  if (provider !== 'openai' && provider !== 'gemini') {
    throw new Error('AI_PROVIDER must be openai or gemini.');
  }
  const port = Number(env.PORT ?? '8787');
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error('PORT must be an integer between 1 and 65535.');
  }
  const keyVariable = provider === 'gemini' ? 'GEMINI_API_KEY' : 'OPENAI_API_KEY';
  const model = provider === 'gemini'
    ? env.GEMINI_MODEL?.trim() || DEFAULT_MODELS.gemini
    : env.OPENAI_MODEL?.trim() || DEFAULT_MODELS.openai;
  const options: ServerOptions = {
    provider,
    apiKey: env[keyVariable]?.trim() || undefined,
    model,
    accessToken: env.APP_ACCESS_TOKEN?.trim() || undefined,
    corsOrigins: env.CORS_ORIGINS?.split(',').map(origin => origin.trim()).filter(Boolean),
  };
  return { port, host: env.HOST?.trim() || '0.0.0.0', keyVariable, options };
}
