import type { AnalysisResult } from '../shared/contracts';

export const demoResult: AnalysisResult = {
  title: '窗边的一杯热茶',
  summary: '这是一幅示例插画。窗边的桌上放着一只绿色茶杯，旁边有一盆小植物，窗外是明亮的天空。',
  details: ['画面中间偏左是一只带把手的绿色茶杯，杯口上方有三缕代表热气的线条。', '右侧是一盆绿叶植物，种在浅棕色花盆里。杯子与花盆都放在同一张桌上。', '后方是一扇圆角窗户。透过窗户，可以看到浅蓝色天空和一轮黄色太阳。'],
  visibleText: [], timeline: [],
  uncertainties: ['这是预先编写的演示描述，用于体验操作和朗读，并非实时识别结果。'],
  answer: null,
};

export function formatTimestamp(ms: number): string {
  const seconds = Math.floor(ms / 1000);
  return `${Math.floor(seconds / 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}`;
}

export function narration(result: AnalysisResult, mode: 'brief' | 'detailed' | 'text'): string {
  const sections = mode === 'text'
    ? [result.title, '画面中的文字。', result.visibleText.length ? result.visibleText.join('。') : '没有识别到清晰可读的文字。']
    : [result.title, result.summary, ...(mode === 'detailed' ? [...result.details, ...(result.visibleText.length ? ['画面中的文字。', ...result.visibleText] : [])] : []), ...result.timeline.map(item => `${Math.floor(item.timestampMs / 1000)}秒：${item.description}`)];
  return [...sections, ...result.uncertainties.map(text => `识别说明：${text}`)].join('。');
}
