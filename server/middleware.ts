import { createHash, timingSafeEqual } from 'node:crypto';
import type { ErrorRequestHandler, RequestHandler } from 'express';

export function corsAndSecurityHeaders(allowedOrigins?: string[]): RequestHandler {
  const origins = new Set(allowedOrigins ?? ['http://localhost:8081', 'http://127.0.0.1:8081']);
  return (req, res, next) => {
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.vary('Origin');
    const origin = req.headers.origin;
    if (origin) {
      if (!origins.has(origin)) {
        res.status(403).json({ error: '这个网页地址尚未获准连接描述服务。', code: 'ORIGIN_NOT_ALLOWED' });
        return;
      }
      res.setHeader('Access-Control-Allow-Origin', origin);
      res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
      res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization');
      res.setHeader('Access-Control-Max-Age', '600');
    }
    if (req.method === 'OPTIONS') { res.sendStatus(204); return; }
    next();
  };
}

export function authenticate(accessToken?: string): RequestHandler {
  return (req, res, next) => {
    if (accessToken && !tokenMatches(req.headers.authorization, accessToken)) {
      res.status(401).json({ error: '连接口令不正确，请在设置中检查。', code: 'UNAUTHORIZED' });
      return;
    }
    next();
  };
}

export function createRateLimiter(limit = 12, windowMs = 60_000, clock = Date.now): RequestHandler {
  const clients = new Map<string, { count: number; until: number }>();
  return (req, res, next) => {
    const now = clock();
    for (const [key, record] of clients) if (record.until <= now) clients.delete(key);
    const ip = req.ip ?? req.socket.remoteAddress ?? 'unknown';
    let record = clients.get(ip);
    if (!record) {
      if (clients.size >= 1000) {
        res.status(503).json({ error: '服务暂时繁忙，请稍后再试。', code: 'SERVER_BUSY' });
        return;
      }
      record = { count: 0, until: now + windowMs };
      clients.set(ip, record);
    }
    if (record.count >= limit) {
      res.setHeader('Retry-After', String(Math.max(1, Math.ceil((record.until - now) / 1000))));
      res.status(429).json({ error: '操作有些频繁，请稍等一分钟再试。', code: 'RATE_LIMITED' });
      return;
    }
    record.count++;
    next();
  };
}

export const handleHttpError: ErrorRequestHandler = (error, _req, res, _next) => {
  if (res.headersSent) return;
  if (error?.type === 'entity.too.large') {
    res.status(413).json({ error: '上传内容过大，请选择较小的图片或较短的视频。', code: 'PAYLOAD_TOO_LARGE' });
  } else if (error?.type === 'entity.parse.failed') {
    res.status(400).json({ error: '请求内容格式不正确，请重试。', code: 'INVALID_JSON' });
  } else if (error?.status === 415) {
    res.status(415).json({ error: '不支持这个请求编码。', code: 'UNSUPPORTED_ENCODING' });
  } else {
    res.status(500).json({ error: '服务出现问题，请稍后重试。', code: 'INTERNAL_ERROR' });
  }
};

function tokenMatches(header: string | undefined, expected: string): boolean {
  if (!header?.startsWith('Bearer ')) return false;
  // Fixed-length digests avoid timing-sensitive comparisons and token-length leakage.
  const digest = (value: string) => createHash('sha256').update(value).digest();
  return timingSafeEqual(digest(header.slice(7)), digest(expected));
}
