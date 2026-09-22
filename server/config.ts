import type { ServerOptions } from './app';
import { DEFAULT_MODELS } from './providers';

/** Select one provider explicitly; never send a failed request to another provider. */
export function readServerConfig(env: NodeJS.ProcessEnv = process.env) {
  rejectClientExposedSecrets(env);
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

/**
 * Expo inlines every EXPO_PUBLIC_* variable into the client bundle. A provider key or access
 * token under that prefix would ship to every phone, so refuse to start instead of silently
 * working with a leaked secret.
 */
export function rejectClientExposedSecrets(env: NodeJS.ProcessEnv): void {
  const exposed = Object.keys(env).filter(name =>
    name.startsWith('EXPO_PUBLIC_') && /(API_?KEY|SECRET|TOKEN|PASSWORD)/i.test(name) && env[name]?.trim());
  if (exposed.length > 0) {
    throw new Error(`以下变量会被打包进客户端，不得用于存放密钥：${exposed.join(', ')}。请改用 OPENAI_API_KEY / GEMINI_API_KEY / APP_ACCESS_TOKEN。`);
  }
}
