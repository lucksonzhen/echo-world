import { z } from 'zod';
import type { AnalysisRequest, AnalysisResult } from '../shared/contracts';

export const defaultApiUrl = process.env.EXPO_PUBLIC_API_URL || 'http://localhost:8787';

const resultSchema = z.object({
  title: z.string(), summary: z.string(), details: z.array(z.string()),
  visibleText: z.array(z.string()), uncertainties: z.array(z.string()),
  timeline: z.array(z.object({ timestampMs: z.number().nonnegative(), description: z.string() })),
  answer: z.string().nullable(),
});

export function normalizeApiUrl(value: string): string {
  try {
    const url = new URL(value.trim());
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash) throw new Error();
    return url.toString().replace(/\/$/, '');
  } catch { throw new Error('请输入完整的服务地址，例如 https://your-server.example.com。'); }
}

async function request(baseUrl: string, path: string, token: string, signal?: AbortSignal, body?: unknown): Promise<unknown> {
  const controller = new AbortController();
  let timedOut = false;
  const abort = () => controller.abort();
  if (signal?.aborted) controller.abort();
  signal?.addEventListener('abort', abort, { once: true });
  const timer = setTimeout(() => { timedOut = true; controller.abort(); }, body ? 100_000 : 10_000);
  try {
    const response = await fetch(`${normalizeApiUrl(baseUrl)}${path}`, {
      method: body ? 'POST' : 'GET', signal: controller.signal,
      headers: { ...(body ? { 'Content-Type': 'application/json' } : {}), ...(token.trim() ? { Authorization: `Bearer ${token.trim()}` } : {}) },
      ...(body ? { body: JSON.stringify(body) } : {}),
    });
    const data = await response.json().catch(() => null);
    if (!response.ok) throw new Error(data?.error || `服务暂时无法响应（${response.status}），请稍后重试。`);
    return data;
  } catch (error) {
    if (timedOut) throw new Error('服务响应超时，请检查网络后重试。');
    if (signal?.aborted) throw new Error('已取消本次操作。');
    if (error instanceof TypeError) throw new Error('连接不上描述服务，请检查网络和设置中的服务地址。');
    throw error;
  } finally { clearTimeout(timer); signal?.removeEventListener('abort', abort); }
}

export async function describeMedia(baseUrl: string, token: string, body: AnalysisRequest, signal: AbortSignal): Promise<AnalysisResult> {
  const result = resultSchema.safeParse(await request(baseUrl, '/api/describe', token, signal, body));
  if (!result.success) throw new Error('收到的描述格式不完整，请重试。');
  return result.data;
}

export async function checkConnection(baseUrl: string, token: string): Promise<boolean> {
  const value = await request(baseUrl, '/api/health', token);
  const parsed = z.object({ status: z.literal('ok'), configured: z.boolean() }).safeParse(value);
  if (!parsed.success) throw new Error('服务地址不正确，请连接听见世界的后端服务。');
  return parsed.data.configured;
}
