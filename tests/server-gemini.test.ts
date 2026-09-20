import assert from 'node:assert/strict';
import { test } from 'node:test';
import { ApiError, describeMedia, DESCRIPTION_INSTRUCTIONS } from '../server/describe';
import { resultJsonSchema } from '../server/schema';
import type { AnalysisRequest, AnalysisResult } from '../shared/contracts';

const png = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jHn8AAAAASUVORK5CYII=';
const imageRequest: AnalysisRequest = { mediaType: 'image', mode: 'brief', frames: [{ dataUrl: png, timestampMs: 0 }] };
const description: AnalysisResult = {
  title: '窗边的一只猫', summary: '一只猫坐在窗边，面向窗外。',
  details: ['猫位于画面中央，窗户在它右侧。'], visibleText: [], timeline: [], uncertainties: [], answer: null,
};
const output = (result: unknown = description) => ({
  candidates: [{ finishReason: 'STOP', content: { role: 'model', parts: [{ text: JSON.stringify(result) }] } }],
});
const provider = (body: unknown = output(), status = 200): typeof fetch => async () => Response.json(body, { status });
const describe = (fetchImpl: typeof fetch, request = imageRequest, signal = new AbortController().signal) => describeMedia(request, {
  apiKey: 'test-gemini-key', model: 'gemini-2.5-flash', provider: 'gemini', fetchImpl,
}, signal);
const errorIs = (code: string, status: number) => (error: unknown) => {
  assert.ok(error instanceof ApiError);
  assert.equal(error.code, code);
  assert.equal(error.status, status);
  assert.doesNotMatch(error.message, /SECRET/);
  return true;
};

test('Gemini maps an image to inline vision input, authenticates in a header and requests structured output', async () => {
  const controller = new AbortController();
  const result = await describe(async (url, init) => {
    assert.equal(url, 'https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent');
    assert.equal(init?.method, 'POST');
    assert.deepEqual(init.headers, { 'x-goog-api-key': 'test-gemini-key', 'Content-Type': 'application/json' });
    assert.equal(init.signal, controller.signal);
    const sent = JSON.parse(String(init.body));
    assert.equal(sent.store, false);
    assert.deepEqual(sent.systemInstruction, { parts: [{ text: DESCRIPTION_INSTRUCTIONS }] });
    assert.deepEqual(sent.generationConfig, {
      responseMimeType: 'application/json', responseJsonSchema: resultJsonSchema, maxOutputTokens: 4000,
    });
    assert.equal(sent.contents[0].role, 'user');
    assert.equal(sent.contents[0].parts.length, 3);
    assert.equal(JSON.parse(sent.contents[0].parts[0].text).question, null);
    assert.deepEqual(sent.contents[0].parts.slice(1), [
      { text: '画面时间 timestampMs=0' },
      { inlineData: { mimeType: 'image/png', data: png.split(',')[1] } },
    ]);
    assert.equal(sent.tools, undefined);
    return Response.json(output());
  }, imageRequest, controller.signal);
  assert.deepEqual(result, description);
});

test('Gemini preserves video frame order, timestamps, user question and sampling limitations', async () => {
  const jpeg = 'data:image/jpeg;base64,/9j/2Q==';
  const video: AnalysisRequest = { mediaType: 'video', mode: 'detailed', durationMs: 4000, question: '猫在哪里？',
    frames: [{ dataUrl: png, timestampMs: 0 }, { dataUrl: jpeg, timestampMs: 3000 }] };
  const timeline = [{ timestampMs: 0, description: '猫坐在窗边。' }, { timestampMs: 3000, description: '猫向右转头。' }];
  const result = await describe(async (_url, init) => {
    const sent = JSON.parse(String(init?.body));
    const parts = sent.contents[0].parts;
    assert.deepEqual(JSON.parse(parts[0].text), {
      mediaType: 'video', mode: 'detailed', durationMs: 4000, question: '猫在哪里？',
      instruction: '请按系统规则描述以下画面，输出指定的 JSON。',
    });
    assert.deepEqual(parts.slice(1), [
      { text: '画面时间 timestampMs=0' }, { inlineData: { mimeType: 'image/png', data: png.split(',')[1] } },
      { text: '画面时间 timestampMs=3000' }, { inlineData: { mimeType: 'image/jpeg', data: '/9j/2Q==' } },
    ]);
    return Response.json(output({ ...description, timeline, answer: '猫在窗边。' }));
  }, video);
  assert.deepEqual(result.timeline, timeline);
  assert.equal(result.answer, '猫在窗边。');
  assert.match(result.uncertainties.at(-1)!, /没有分析视频声音/);
});

test('Gemini omits thought parts and combines only final text; unsolicited answers are removed', async () => {
  const text = JSON.stringify({ ...description, answer: '未提问的回答。' });
  const result = await describe(provider({ candidates: [{ finishReason: 'STOP', content: { parts: [
    { thought: true, text: 'SECRET thought must never be included' },
    { text: text.slice(0, 20) }, { text: text.slice(20) },
  ] } }] }));
  assert.deepEqual(result, description);
});

test('Gemini model path accepts a models prefix without allowing query/path injection', async () => {
  await describeMedia(imageRequest, {
    apiKey: 'test-key', provider: 'gemini', model: 'models/model/name?key=unexpected',
    fetchImpl: async url => {
      assert.equal(url, 'https://generativelanguage.googleapis.com/v1beta/models/model%2Fname%3Fkey%3Dunexpected:generateContent');
      return Response.json(output());
    },
  }, new AbortController().signal);
});

test('Gemini blocks prompt/candidate refusals and rejects incomplete responses', async t => {
  const cases = [
    { body: { promptFeedback: { blockReason: 'SAFETY' } }, code: 'DESCRIPTION_REFUSED', status: 422 },
    { body: { candidates: [{ finishReason: 'SAFETY' }] }, code: 'DESCRIPTION_REFUSED', status: 422 },
    { body: { candidates: [{ finishReason: 'RECITATION' }] }, code: 'DESCRIPTION_REFUSED', status: 422 },
    { body: { candidates: [{ ...output().candidates[0], safetyRatings: [{ blocked: true }] }] }, code: 'DESCRIPTION_REFUSED', status: 422 },
    { body: { candidates: [{ ...output().candidates[0], finishReason: 'MAX_TOKENS' }] }, code: 'INCOMPLETE_RESPONSE', status: 502 },
    { body: { candidates: [{ content: output().candidates[0].content }] }, code: 'INCOMPLETE_RESPONSE', status: 502 },
  ];
  for (const [index, entry] of cases.entries()) {
    await t.test(`${entry.code} ${index}`, async () => {
      await assert.rejects(describe(provider(entry.body)), errorIs(entry.code, entry.status));
    });
  }
});

test('Gemini rejects malformed envelopes, JSON, result schemas, tool output and image timelines', async t => {
  const cases: unknown[] = [
    null, {}, { candidates: [] }, { candidates: [null] }, { candidates: [output().candidates[0], output().candidates[0]] },
    { error: { message: 'SECRET upstream details' } },
    { candidates: [{ finishReason: 'STOP', content: { parts: [{ text: 'not JSON' }] } }] },
    { candidates: [{ finishReason: 'STOP', content: { parts: [{ functionCall: { name: 'unexpected' } }] } }] },
    { candidates: [{ finishReason: 'STOP', content: { parts: [{ thought: true, text: JSON.stringify(description) }] } }] },
    output({ title: 'missing fields' }),
    output({ ...description, timeline: [{ timestampMs: 0, description: '图片不可包含时间线。' }] }),
  ];
  for (const [index, body] of cases.entries()) {
    await t.test(String(index), async () => {
      await assert.rejects(describe(provider(body)), errorIs('INVALID_RESPONSE', 502));
    });
  }
  await assert.rejects(describe(async () => new Response('<html>SECRET error</html>')), errorIs('INVALID_RESPONSE', 502));
});

test('Gemini cannot invent or reorder video timestamps', async () => {
  const request: AnalysisRequest = { mediaType: 'video', mode: 'brief', durationMs: 4000,
    frames: [{ dataUrl: png, timestampMs: 0 }, { dataUrl: png, timestampMs: 3000 }] };
  for (const timestamps of [[500], [3000, 0], [0, 0]]) {
    const timeline = timestamps.map(timestampMs => ({ timestampMs, description: '猫在窗边。' }));
    await assert.rejects(describe(provider(output({ ...description, timeline })), request), errorIs('INVALID_RESPONSE', 502));
  }
});

test('Gemini upstream HTTP errors discard the body and remain sanitized', async () => {
  for (const status of [400, 401, 403, 429, 500]) {
    let cancelled = false;
    const fetchImpl: typeof fetch = async () => new Response(new ReadableStream({
      start(controller) { controller.enqueue(new TextEncoder().encode('SECRET upstream body')); },
      cancel() { cancelled = true; },
    }), { status });
    await assert.rejects(describe(fetchImpl), errorIs(status === 429 ? 'UPSTREAM_RATE_LIMIT' : 'UPSTREAM_ERROR', status === 429 ? 503 : 502));
    assert.equal(cancelled, true);
  }
});

test('Gemini propagates cancellation to the provider fetch without generating output', async () => {
  const controller = new AbortController();
  let started!: () => void;
  const ready = new Promise<void>(resolve => { started = resolve; });
  let upstreamAborted = false;
  const pending = describe(async (_url, init) => new Promise<Response>((_resolve, reject) => {
    assert.equal(init?.signal, controller.signal);
    init.signal.addEventListener('abort', () => {
      upstreamAborted = true;
      reject(new DOMException('Aborted', 'AbortError'));
    }, { once: true });
    started();
  }), imageRequest, controller.signal);
  const rejected = assert.rejects(pending, { name: 'AbortError' });
  await ready;
  controller.abort();
  await rejected;
  assert.equal(upstreamAborted, true);
});
