import assert from 'node:assert/strict';
import test from 'node:test';
import type { AnalysisResult } from '../shared/contracts';
import { narration } from '../src/demo';

const result: AnalysisResult = {
  title: '窗边的告示牌',
  summary: '窗边放着一块告示牌，旁边有人经过',
  details: ['告示牌位于画面左侧', '右侧的人穿着蓝色外套'],
  visibleText: ['今日开放', '请从右侧进入'],
  timeline: [{ timestampMs: 2500, description: '一个人从告示牌旁经过' }],
  uncertainties: ['底部小字较模糊，无法确认内容'],
  answer: null,
};

test('detailed narration includes the overview, spatial details, all readable text and uncertainty', () => {
  const text = narration(result, 'detailed');
  for (const section of [result.title, result.summary, ...result.details, ...result.visibleText]) {
    assert.ok(text.includes(section), `Missing spoken section: ${section}`);
  }
  assert.ok(text.includes('画面中的文字'));
  assert.ok(text.includes(`识别说明：${result.uncertainties[0]}`));
  assert.ok(text.indexOf(result.summary) < text.indexOf(result.details[0]));
  assert.ok(text.indexOf(result.details[1]) < text.indexOf(result.visibleText[0]));
});

test('brief narration omits detailed observations and OCR while retaining uncertainty', () => {
  const text = narration(result, 'brief');
  assert.ok(text.includes(result.title));
  assert.ok(text.includes(result.summary));
  for (const omitted of [...result.details, ...result.visibleText]) {
    assert.ok(!text.includes(omitted), `Unexpected detailed section in brief mode: ${omitted}`);
  }
  assert.ok(text.includes(`识别说明：${result.uncertainties[0]}`));
});

test('text mode explicitly says when no readable text was recognized', () => {
  const text = narration({ ...result, visibleText: [] }, 'text');
  assert.ok(text.includes('没有识别到清晰可读的文字'));
  assert.ok(text.includes(`识别说明：${result.uncertainties[0]}`));
  assert.ok(!text.includes(result.summary));
  assert.ok(!text.includes(result.details[0]));
});

test('text mode reads every OCR line without adding the scene narrative or video timeline', () => {
  const text = narration(result, 'text');
  for (const line of result.visibleText) assert.ok(text.includes(line));
  assert.ok(!text.includes('没有识别到清晰可读的文字'));
  assert.ok(!text.includes(result.summary));
  assert.ok(!text.includes(result.timeline[0].description));
});
