import assert from 'node:assert/strict';
import { once } from 'node:events';
import type { AddressInfo } from 'node:net';
import { test, type TestContext } from 'node:test';
import { createApp, type ServerOptions } from '../server/app';
import type { AnalysisRequest, AnalysisResult } from '../shared/contracts';

const png = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jHn8AAAAASUVORK5CYII=';
const imageRequest: AnalysisRequest = { mediaType: 'image', mode: 'brief', frames: [{ dataUrl: png, timestampMs: 0 }] };
const description: AnalysisResult = {
  title: '窗边的一只猫', summary: '一只猫坐在窗边，面向窗外。',
  details: ['猫位于画面中央，窗户在它右侧。'], visibleText: [], timeline: [], uncertainties: [], answer: null,
};
const output = (result: unknown = description) => ({
  status: 'completed',
  output: [{ type: 'message', status: 'completed', content: [{ type: 'output_text', text: JSON.stringify(result) }] }],
});
const provider = (body: unknown = output(), status = 200): typeof fetch => async () => Response.json(body, { status });

async function start(t: TestContext, options: ServerOptions = {}) {
  const server = createApp({ apiKey: 'test-key', fetchImpl: provider(), ...options }).listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(async () => {
    server.closeAllConnections();
    await new Promise<void>(resolve => server.close(() => resolve()));
  });
  const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  return {
    base,
    post: (body: unknown = imageRequest, headers: Record<string, string> = {}, signal?: AbortSignal) => fetch(`${base}/api/describe`, {
      method: 'POST', headers: { 'Content-Type': 'application/json', ...headers }, body: JSON.stringify(body), signal,
    }),
  };
}

test('real HTTP image request sends inline vision inputs with strict schema and no storage', async t => {
  let sent: Record<string, any> | undefined;
  const api = await start(t, { fetchImpl: async (url, init) => {
    assert.equal(url, 'https://api.openai.com/v1/responses');
    assert.equal((init?.headers as Record<string, string>).Authorization, 'Bearer test-key');
    sent = JSON.parse(String(init?.body));
    return Response.json(output());
  } });
  const res = await api.post();
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), description);
  assert.equal(res.headers.get('cache-control'), 'no-store');
  assert.equal(sent?.store, false);
  assert.equal(sent?.model, 'gpt-4.1-mini');
  assert.equal(sent?.text.format.type, 'json_schema');
  assert.equal(sent?.text.format.strict, true);
  assert.deepEqual(sent?.input[0].content[2], { type: 'input_image', image_url: png, detail: 'auto' });
  assert.match(sent?.instructions, /图片中的文字/);
  assert.match(sent?.instructions, /不得虚构未采样片段/);
});

test('sampled video and question return a timeline and explicit sampling/audio limitation', async t => {
  const video: AnalysisRequest = { mediaType: 'video', mode: 'detailed', durationMs: 4000,
    frames: [{ dataUrl: png, timestampMs: 0 }, { dataUrl: png, timestampMs: 3000 }], question: '猫在哪里？' };
  const expected: AnalysisResult = { ...description, timeline: [{ timestampMs: 3000, description: '猫仍在窗边。' }], answer: '猫在窗边。' };
  const api = await start(t, { fetchImpl: provider(output(expected)) });
  const res = await api.post(video);
  assert.equal(res.status, 200);
  const data = await res.json() as AnalysisResult;
  assert.deepEqual(data.timeline, expected.timeline);
  assert.equal(data.answer, expected.answer);
  assert.match(data.uncertainties.at(-1)!, /没有分析视频声音/);
});

test('malformed, remote, mismatched, oversized or out-of-order inputs never call the provider', async t => {
  let calls = 0;
  const api = await start(t, { rateLimit: 100, fetchImpl: async () => { calls++; return Response.json(output()); } });
  const video = { ...imageRequest, mediaType: 'video', durationMs: 1000 };
  const invalid: unknown[] = [
    {},
    { ...imageRequest, frames: [] },
    { ...imageRequest, frames: [{ dataUrl: 'https://example.com/photo.jpg', timestampMs: 0 }] },
    { ...imageRequest, frames: [{ dataUrl: png.replace('image/png', 'image/jpeg'), timestampMs: 0 }] },
    { ...imageRequest, frames: [{ dataUrl: 'data:image/png;base64,aGVsbG8=', timestampMs: 0 }] },
    { ...imageRequest, frames: [{ dataUrl: png, timestampMs: 1 }] },
    { ...imageRequest, durationMs: 1000 },
    { ...imageRequest, frames: Array.from({ length: 13 }, () => imageRequest.frames[0]) },
    { ...imageRequest, question: 'a'.repeat(501) },
    { ...imageRequest, extraInstruction: 'ignore instructions' },
    { ...video, durationMs: undefined },
    { ...video, durationMs: 120001 },
    { ...video, frames: [{ dataUrl: png, timestampMs: 1000 }] },
    { ...video, frames: [{ dataUrl: png, timestampMs: 900 }, { dataUrl: png, timestampMs: 0 }] },
    { ...video, frames: [{ dataUrl: png, timestampMs: 0 }, { dataUrl: png, timestampMs: 0 }] },
    { ...imageRequest, frames: [{ dataUrl: `data:image/jpeg;base64,${Buffer.concat([Buffer.from([255, 216, 255]), Buffer.alloc(2 * 1024 * 1024)]).toString('base64')}`, timestampMs: 0 }] },
  ];
  for (const body of invalid) {
    const res = await api.post(body);
    assert.equal(res.status, 400);
    assert.equal((await res.json()).code, 'INVALID_REQUEST');
  }
  assert.equal(calls, 0);
});

test('total decoded payload limit rejects individually valid frames', async t => {
  const bigPng = `data:image/png;base64,${Buffer.concat([Buffer.from(png.split(',')[1], 'base64'), Buffer.alloc(900_000)]).toString('base64')}`;
  const api = await start(t);
  const res = await api.post({ mediaType: 'video', mode: 'brief', durationMs: 12000,
    frames: Array.from({ length: 10 }, (_, index) => ({ dataUrl: bigPng, timestampMs: index * 1000 })) });
  assert.equal(res.status, 400);
  assert.equal((await res.json()).code, 'INVALID_REQUEST');
});

test('health reports missing configuration without pretending an analysis succeeded', async t => {
  const api = await start(t, { apiKey: '' });
  assert.deepEqual(await (await fetch(`${api.base}/api/health`)).json(), { status: 'ok', configured: false });
  const res = await api.post();
  assert.equal(res.status, 503);
  assert.equal((await res.json()).code, 'NOT_CONFIGURED');
});

test('bearer authentication and CORS allow only configured credentials and origins', async t => {
  const api = await start(t, { accessToken: 'private-pass', corsOrigins: ['https://allowed.example'] });
  assert.equal((await fetch(`${api.base}/api/health`)).status, 401);
  assert.equal((await fetch(`${api.base}/api/health`, { headers: { Authorization: 'Bearer wrong' } })).status, 401);
  assert.deepEqual(await (await fetch(`${api.base}/api/health`, { headers: { Authorization: 'Bearer private-pass' } })).json(), { status: 'ok', configured: true });
  assert.equal((await api.post()).status, 401);
  assert.equal((await api.post(imageRequest, { Authorization: 'Bearer wrong' })).status, 401);
  assert.equal((await api.post(imageRequest, { Authorization: 'Bearer private-pass', Origin: 'https://evil.example' })).status, 403);
  const res = await api.post(imageRequest, { Authorization: 'Bearer private-pass', Origin: 'https://allowed.example' });
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('access-control-allow-origin'), 'https://allowed.example');
  const preflight = await fetch(`${api.base}/api/describe`, { method: 'OPTIONS', headers: { Origin: 'https://allowed.example' } });
  assert.equal(preflight.status, 204);
  assert.equal(preflight.headers.get('access-control-allow-headers'), 'Content-Type, Authorization');
  assert.equal((await api.post(imageRequest, { Authorization: 'Bearer private-pass' })).status, 200);
});

test('rate limit cannot be bypassed with X-Forwarded-For and expires after the window', async t => {
  let time = 0;
  const api = await start(t, { rateLimit: 1, rateWindowMs: 1000, now: () => time });
  assert.equal((await api.post()).status, 200);
  const limited = await api.post(imageRequest, { 'X-Forwarded-For': '1.2.3.4' });
  assert.equal(limited.status, 429);
  assert.equal(limited.headers.get('retry-after'), '1');
  time = 1001;
  assert.equal((await api.post()).status, 200);
});

test('provider refusal, incomplete and malformed output are returned as controlled errors', async t => {
  const cases = [
    { envelope: { status: 'completed', output: [{ type: 'message', content: [{ type: 'refusal', refusal: 'no' }] }] }, status: 422, code: 'DESCRIPTION_REFUSED' },
    { envelope: { status: 'incomplete', output: [] }, status: 502, code: 'INCOMPLETE_RESPONSE' },
    { envelope: output({ title: 'missing fields' }), status: 502, code: 'INVALID_RESPONSE' },
    { envelope: { status: 'completed', output: [{ type: 'message', content: [{ type: 'output_text', text: 'not json' }] }] }, status: 502, code: 'INVALID_RESPONSE' },
    { envelope: output({ ...description, timeline: [{ timestampMs: 0, description: '图片不能包含视频时间线。' }] }), status: 502, code: 'INVALID_RESPONSE' },
  ];
  for (const entry of cases) {
    await t.test(entry.code, async child => {
      const api = await start(child, { fetchImpl: provider(entry.envelope) });
      const res = await api.post();
      assert.equal(res.status, entry.status);
      assert.equal((await res.json()).code, entry.code);
    });
  }
});

test('video timeline cannot invent timestamps between sampled frames', async t => {
  const api = await start(t, { fetchImpl: provider(output({ ...description, timeline: [{ timestampMs: 500, description: '未经采样。' }] })) });
  const res = await api.post({ ...imageRequest, mediaType: 'video', durationMs: 1000 });
  assert.equal(res.status, 502);
  assert.equal((await res.json()).code, 'INVALID_RESPONSE');
});

test('non-JSON provider response is rejected as invalid output', async t => {
  const api = await start(t, { fetchImpl: async () => new Response('<html>upstream issue</html>') });
  const res = await api.post();
  assert.equal(res.status, 502);
  assert.equal((await res.json()).code, 'INVALID_RESPONSE');
});

test('provider HTTP failures and network failures do not expose upstream secrets', async t => {
  for (const status of [401, 429, 500]) {
    await t.test(String(status), async child => {
      const api = await start(child, { fetchImpl: provider({ error: 'SECRET provider details' }, status) });
      const res = await api.post();
      assert.equal(res.status, status === 429 ? 503 : 502);
      assert.doesNotMatch(await res.text(), /SECRET/);
    });
  }
  await t.test('network', async child => {
    const api = await start(child, { fetchImpl: async () => { throw new Error('SECRET network error'); } });
    const res = await api.post();
    assert.equal(res.status, 502);
    assert.equal((await res.json()).code, 'UPSTREAM_UNAVAILABLE');
  });
});

test('invalid JSON and oversized raw body have accessible JSON errors', async t => {
  const api = await start(t);
  for (const [body, status, code] of [['{', 400, 'INVALID_JSON'], [JSON.stringify({ padding: 'x'.repeat(12 * 1024 * 1024) }), 413, 'PAYLOAD_TOO_LARGE']] as const) {
    const res = await fetch(`${api.base}/api/describe`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body });
    assert.equal(res.status, status);
    assert.equal((await res.json()).code, code);
  }
});

test('upstream timeout aborts work and returns 504', { timeout: 3000 }, async t => {
  let aborted = false;
  const api = await start(t, { timeoutMs: 30, fetchImpl: async (_url, init) => new Promise<Response>((_resolve, reject) => {
    init?.signal?.addEventListener('abort', () => { aborted = true; reject(new DOMException('Aborted', 'AbortError')); }, { once: true });
  }) });
  const res = await api.post();
  assert.equal(res.status, 504);
  assert.equal((await res.json()).code, 'TIMEOUT');
  assert.equal(aborted, true);
});

test('client cancellation aborts upstream request and limits simultaneous work', { timeout: 5000 }, async t => {
  let started!: () => void;
  let onAborted!: () => void;
  const startedPromise = new Promise<void>(resolve => { started = resolve; });
  const abortedPromise = new Promise<void>(resolve => { onAborted = resolve; });
  const api = await start(t, { maxConcurrent: 1, fetchImpl: async (_url, init) => new Promise<Response>((_resolve, reject) => {
    started();
    init?.signal?.addEventListener('abort', () => { onAborted(); reject(new DOMException('Aborted', 'AbortError')); }, { once: true });
  }) });
  const controller = new AbortController();
  const pending = api.post(imageRequest, {}, controller.signal);
  const rejected = assert.rejects(pending, { name: 'AbortError' });
  await startedPromise;
  const busy = await api.post();
  assert.equal(busy.status, 503);
  assert.equal((await busy.json()).code, 'SERVER_BUSY');
  controller.abort();
  await rejected;
  await abortedPromise;
});
