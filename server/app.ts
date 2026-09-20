import express from 'express';
import { analysisRequestSchema } from './schema';
import { ApiError, describeMedia } from './describe';
import { DEFAULT_MODELS, type AIProvider } from './providers';
import { authenticate, corsAndSecurityHeaders, createRateLimiter, handleHttpError } from './middleware';

export interface ServerOptions {
  provider?: AIProvider;
  apiKey?: string;
  model?: string;
  accessToken?: string;
  corsOrigins?: string[];
  timeoutMs?: number;
  rateLimit?: number;
  rateWindowMs?: number;
  maxConcurrent?: number;
  fetchImpl?: typeof fetch;
  now?: () => number;
}

export function createApp(options: ServerOptions = {}) {
  const app = express();
  const provider = options.provider ?? 'openai';
  let activeRequests = 0;
  app.disable('x-powered-by');
  // Do not trust X-Forwarded-For without a known reverse-proxy topology.
  app.set('trust proxy', false);
  app.use(corsAndSecurityHeaders(options.corsOrigins));
  app.use('/api', authenticate(options.accessToken));
  app.get('/api/health', (_req, res) => res.json({ status: 'ok', configured: Boolean(options.apiKey?.trim()) }));
  app.use('/api/describe', createRateLimiter(options.rateLimit, options.rateWindowMs, options.now));

  app.post('/api/describe', express.json({ limit: '12mb', inflate: false }), async (req, res) => {
    const parsed = analysisRequestSchema.safeParse(req.body);
    if (!parsed.success) {
      res.status(400).json({ error: '请上传有效图片，或不超过 2 分钟、最多 12 个采样画面的视频。每帧最多 2 MB，总计最多 8 MB。', code: 'INVALID_REQUEST' });
      return;
    }
    if (!options.apiKey?.trim()) {
      res.status(503).json({ error: '描述服务尚未配置，请在服务端设置所选 AI 提供方的 API 密钥。', code: 'NOT_CONFIGURED' });
      return;
    }
    if (activeRequests >= (options.maxConcurrent ?? 4)) {
      res.status(503).json({ error: '服务正在处理其他描述，请稍后再试。', code: 'SERVER_BUSY' });
      return;
    }

    activeRequests++;
    const controller = new AbortController();
    let timedOut = false;
    const timeout = setTimeout(() => { timedOut = true; controller.abort(); }, options.timeoutMs ?? 60_000);
    const onClose = () => { if (!res.writableEnded) controller.abort(); };
    res.on('close', onClose);
    try {
      const result = await describeMedia(parsed.data, {
        apiKey: options.apiKey,
        provider,
        model: options.model ?? DEFAULT_MODELS[provider],
        fetchImpl: options.fetchImpl,
      }, controller.signal);
      if (!res.destroyed) res.json(result);
    } catch (error) {
      if (res.destroyed) return;
      if (timedOut) {
        res.status(504).json({ error: '描述用时较长，请重试或选择较短的视频。', code: 'TIMEOUT' });
      } else if (error instanceof ApiError) {
        res.status(error.status).json({ error: error.message, code: error.code });
      } else {
        res.status(502).json({ error: '暂时无法连接描述服务，请稍后再试。', code: 'UPSTREAM_UNAVAILABLE' });
      }
    } finally {
      clearTimeout(timeout);
      res.off('close', onClose);
      activeRequests--;
    }
  });

  app.use((_req, res) => res.status(404).json({ error: '没有找到这个接口。', code: 'NOT_FOUND' }));
  app.use(handleHttpError);
  return app;
}
