import { test, expect } from '@playwright/test';
import path from 'node:path';

const sample = {
  title: '测试画面', summary: '桌面上有一只绿色杯子。', details: ['杯子在画面中央。'],
  visibleText: [], timeline: [], uncertainties: ['无法判断杯中是什么。'], answer: null,
};
const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j5ioAAAAASUVORK5CYII=', 'base64');

test('mobile home, accessible modes, demo, clearing and settings', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto('/');
  await expect(page.getByRole('heading', { name: '把画面，讲给你听。' })).toBeVisible();
  await expect(page.getByRole('button', { name: '开始描述', exact: true })).toBeDisabled();
  await page.getByRole('radio', { name: '画中文字' }).click();
  await expect(page.getByRole('radio', { name: '画中文字' })).toBeChecked();
  await page.getByRole('button', { name: '体验示例描述' }).click();
  await expect(page.getByRole('heading', { name: '窗边的一杯热茶' })).toBeVisible();
  await expect(page.getByText('本地演示', { exact: true })).toBeVisible();
  await expect(page.getByText('没有识别到清晰可读的文字。')).toBeVisible();
  await page.getByRole('button', { name: '清空本次内容' }).click();
  await expect(page.getByRole('heading', { name: '窗边的一杯热茶' })).toHaveCount(0);
  await page.getByRole('tab', { name: '设置' }).click();
  await page.getByRole('radio', { name: '1.2倍速' }).click();
  await expect(page.getByRole('radio', { name: '1.2倍速' })).toBeChecked();
  await page.getByRole('textbox', { name: '描述服务地址' }).fill('not-a-url');
  await page.getByRole('button', { name: '检查连接' }).click();
  await expect(page.getByRole('status')).toContainText('请输入完整的服务地址');
  expect(errors).toEqual([]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});

test('selected image is compressed, only uploaded on demand, and grounds follow-up', async ({ page }) => {
  const requests: any[] = [];
  await page.route('**/api/describe', async route => {
    const body = route.request().postDataJSON(); requests.push(body);
    await route.fulfill({ json: { ...sample, answer: body.question ? '杯子位于画面中央。' : null } });
  });
  await page.goto('/');
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', { name: '选择照片', exact: true }).click();
  await (await chooser).setFiles({ name: 'test-photo.png', mimeType: 'image/png', buffer: png });
  await expect(page.getByText('已选择照片', { exact: true })).toBeVisible();
  expect(requests).toHaveLength(0);
  await page.getByRole('button', { name: '开始描述', exact: true }).click();
  await expect(page.getByRole('heading', { name: sample.title })).toBeVisible();
  expect(requests).toHaveLength(1);
  expect(requests[0].frames[0].dataUrl).toMatch(/^data:image\/jpeg;base64,/);
  expect(requests[0].mediaType).toBe('image');
  await page.getByRole('textbox', { name: '关于当前画面的问题' }).fill('杯子在哪里？');
  await page.getByRole('button', { name: '发送问题', exact: true }).click();
  await expect(page.getByText('杯子位于画面中央。', { exact: true })).toBeVisible();
  expect(requests[1].frames).toEqual(requests[0].frames);
  expect(requests[1].question).toBe('杯子在哪里？');
});

test('cancellation prevents a late response replacing cleared content', async ({ page }) => {
  let release!: () => void;
  const pending = new Promise<void>(resolve => { release = resolve; });
  await page.route('**/api/describe', async route => {
    await pending;
    await route.fulfill({ json: sample }).catch(() => {});
  });
  await page.goto('/');
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', { name: '选择照片', exact: true }).click();
  await (await chooser).setFiles({ name: 'test.png', mimeType: 'image/png', buffer: png });
  await page.getByRole('button', { name: '开始描述', exact: true }).click();
  await expect(page.getByText('正在理解画面，整理适合聆听的描述…')).toBeVisible();
  await page.getByRole('button', { name: '移除当前内容', exact: true }).click();
  release();
  await expect(page.getByRole('button', { name: '开始描述', exact: true })).toBeDisabled();
  await expect(page.getByRole('heading', { name: sample.title })).toHaveCount(0);
});

test('desktop layout is usable without horizontal overflow', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 950 });
  await page.goto('/');
  await expect(page.getByRole('button', { name: '选择照片', exact: true })).toBeVisible();
  await page.screenshot({ path: 'artifacts/desktop.png', fullPage: true });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({ path: 'artifacts/mobile.png', fullPage: true });
});

test('local video is decoded into ordered JPEG frames without uploading the original', async ({ page }) => {
  let request: any;
  await page.route('**/api/describe', async route => {
    request = route.request().postDataJSON();
    await route.fulfill({ json: { ...sample, title: '视频画面测试', timeline: request.frames.map((frame: any) => ({ timestampMs: frame.timestampMs, description: '彩色测试画面。' })) } });
  });
  await page.goto('/');
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', { name: '选择视频', exact: true }).click();
  await (await chooser).setFiles(path.resolve('tests/fixtures/motion.mp4'));
  await expect(page.getByText('已选择视频', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '开始描述', exact: true }).click();
  await expect(page.getByRole('heading', { name: '视频画面测试' })).toBeVisible();
  expect(request.mediaType).toBe('video');
  expect(request.durationMs).toBe(3000);
  expect(request.frames.length).toBeGreaterThanOrEqual(2);
  expect(request.frames.length).toBeLessThanOrEqual(12);
  expect(request.frames.every((frame: any, index: number) => frame.dataUrl.startsWith('data:image/jpeg;base64,') && (index === 0 || frame.timestampMs > request.frames[index - 1].timestampMs))).toBe(true);
  expect(request.frames[0].dataUrl).not.toBe(request.frames.at(-1).dataUrl);
  await expect(page.getByText(/不包含声音，可能遗漏短暂动作/)).toBeVisible();
});

test('speech can be started and immediately stopped from the persistent control', async ({ page }) => {
  await page.addInitScript(() => {
    Object.defineProperty(window.speechSynthesis, 'cancel', { value: () => {} });
    Object.defineProperty(window.speechSynthesis, 'speak', { value: (utterance: SpeechSynthesisUtterance) => {
      utterance.dispatchEvent(new Event('start'));
    } });
  });
  await page.goto('/');
  await page.getByRole('button', { name: '体验示例描述' }).click();
  await page.getByRole('button', { name: '朗读这段描述', exact: true }).click();
  await expect(page.getByText('正在朗读', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '立即停止朗读', exact: true }).click();
  await expect(page.getByText('正在朗读', { exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '朗读这段描述', exact: true })).toBeVisible();
});
