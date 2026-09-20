import assert from 'node:assert/strict';
import test from 'node:test';
import { guardedOperation, MAX_VIDEO_DURATION_MS, resizedDimensions, sampleVideoTimestamps, validateVideoDuration } from '../src/media/helpers';
import { normalizedSpeechRate, splitSpeechText } from '../src/media/speechText';
import { SpeechQueue, type SpeechEngine, type SpeechState } from '../src/media/speechQueue';

test('sampling covers both ends with strictly increasing timestamps, including very short videos', () => {
  for (const durationMs of [0.5, 1, 10, 150, 500, 1000, 5000, 5001, 30_000, MAX_VIDEO_DURATION_MS]) {
    const timestamps = sampleVideoTimestamps(durationMs);
    assert.ok(timestamps.length >= 1 && timestamps.length <= 12);
    assert.equal(timestamps[0], 0);
    assert.ok(timestamps.every((time) => time >= 0 && time < durationMs));
    assert.ok(durationMs - timestamps.at(-1)! <= Math.max(150, durationMs * 0.1));
    assert.ok(timestamps.every((time, index) => index === 0 || time > timestamps[index - 1]));
  }
  assert.equal(sampleVideoTimestamps(MAX_VIDEO_DURATION_MS).length, 12);
});

test('unknown, corrupt and over-limit video durations are rejected, rather than truncating silently', () => {
  for (const invalid of [undefined, NaN, Infinity, -1, 0]) {
    assert.throws(() => validateVideoDuration(invalid), /无法读取视频时长/);
  }
  assert.throws(() => sampleVideoTimestamps(MAX_VIDEO_DURATION_MS + 1), /2 分钟/);
  assert.equal(validateVideoDuration(MAX_VIDEO_DURATION_MS), MAX_VIDEO_DURATION_MS);
});

test('resize preserves orientation, does not enlarge, and handles an extreme aspect ratio', () => {
  assert.deepEqual(resizedDimensions(4000, 3000), { width: 1280, height: 960 });
  assert.deepEqual(resizedDimensions(3000, 4000), { width: 960, height: 1280 });
  assert.deepEqual(resizedDimensions(320, 200), { width: 320, height: 200 });
  assert.deepEqual(resizedDimensions(50_000, 1), { width: 1280, height: 1 });
  assert.throws(() => resizedDimensions(0, 500), /无法读取画面尺寸/);
});

test('cancelled native operation rejects promptly and cleans its eventual result', async () => {
  const abortController = new AbortController();
  let complete!: (value: string) => void;
  let cleaned = '';
  const pending = guardedOperation(new Promise<string>((resolve) => { complete = resolve; }), {
    signal: abortController.signal, timeoutMs: 1000, timeoutMessage: '超时', onLateResult: (uri) => { cleaned = uri; },
  });
  abortController.abort();
  await assert.rejects(pending, { name: 'AbortError' });
  complete('generated-cache-file');
  await Promise.resolve();
  await Promise.resolve();
  assert.equal(cleaned, 'generated-cache-file');
});

test('decode timeout is bounded and still disposes a late native result', async () => {
  let complete!: (value: number) => void;
  let cleaned = false;
  const pending = guardedOperation(new Promise<number>((resolve) => { complete = resolve; }), {
    timeoutMs: 5, timeoutMessage: '提取视频画面超时', onLateResult: () => { cleaned = true; },
  });
  await assert.rejects(pending, /提取视频画面超时/);
  complete(1);
  await Promise.resolve();
  await Promise.resolve();
  assert.equal(cleaned, true);
});

test('speech segments prefer Chinese sentence boundaries and preserve long unpunctuated text and emoji', () => {
  assert.deepEqual(splitSpeechText('一只猫。它坐在窗边！\n窗外有树。'), ['一只猫。', '它坐在窗边！', '窗外有树。']);
  const original = `${'这是很长的文字'.repeat(100)}😀😀结尾。`;
  const chunks = splitSpeechText(original, 31);
  assert.equal(chunks.join(''), original);
  assert.ok(chunks.every((chunk) => chunk.length <= 31));
  assert.ok(chunks.every((chunk) => !/[\uD800-\uDBFF]$/.test(chunk) && !/^[\uDC00-\uDFFF]/.test(chunk)));
  assert.deepEqual(splitSpeechText(' \n  '), []);
  assert.equal(normalizedSpeechRate(NaN), 1);
  assert.equal(normalizedSpeechRate(10), 1.5);
});

function speechFixture() {
  const utterances: { text: string; options: Parameters<SpeechEngine['speak']>[1] }[] = [];
  const states: SpeechState[] = [];
  let stops = 0;
  const engine: SpeechEngine = {
    stop: async () => { stops += 1; },
    speak: (text, options) => { utterances.push({ text, options }); },
    maxSpeechInputLength: 100,
  };
  const queue = new SpeechQueue(engine, (state) => states.push(state));
  return { queue, utterances, states, stops: () => stops };
}

test('replay ignores delayed stop/done callbacks from the previous queue', async () => {
  const fixture = speechFixture();
  try {
    await fixture.queue.speak('旧的第一句。旧的第二句。');
    const old = fixture.utterances[0];
    await fixture.queue.speak('新的描述。');
    old.options.onStopped();
    old.options.onDone();
    assert.equal(fixture.states.at(-1)?.speaking, true);
    assert.deepEqual(fixture.utterances.map(({ text }) => text), ['旧的第一句。', '新的描述。']);
    fixture.utterances[1].options.onDone();
    assert.deepEqual(fixture.states.at(-1), { speaking: false, error: null });
  } finally {
    fixture.queue.dispose();
  }
});

test('stop prevents the next sentence, and disposal prevents later UI state updates', async () => {
  const fixture = speechFixture();
  await fixture.queue.speak('第一句。第二句。');
  await fixture.queue.stop();
  fixture.utterances[0].options.onDone();
  assert.equal(fixture.utterances.length, 1);
  assert.equal(fixture.states.at(-1)?.speaking, false);
  fixture.queue.dispose();
  const stateCount = fixture.states.length;
  fixture.utterances[0].options.onError(new Error('late error'));
  assert.equal(fixture.states.length, stateCount);
});

test('concurrent replay serializes native stops and only speaks the latest request', async () => {
  const fixture = speechFixture();
  try {
    await Promise.all([fixture.queue.speak('甲。'), fixture.queue.speak('乙。'), fixture.queue.speak('丙。')]);
    assert.deepEqual(fixture.utterances.map(({ text }) => text), ['丙。']);
    assert.equal(fixture.stops(), 3);
    fixture.utterances[0].options.onDone();
  } finally {
    fixture.queue.dispose();
  }
});
