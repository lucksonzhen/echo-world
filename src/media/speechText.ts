/** Prefer sentence boundaries and keep every chunk below the platform's UTF-16 input limit. */
export function splitSpeechText(text: string, maximumLength = 180): string[] {
  if (!Number.isFinite(maximumLength) || maximumLength < 2) {
    throw new Error('Speech chunk size must be at least two UTF-16 code units.');
  }
  const limit = Math.floor(maximumLength);
  const chunks: string[] = [];
  let current = '';
  const flush = () => {
    if (current.trim()) chunks.push(current.trim());
    current = '';
  };
  // Iteration by code point avoids cutting an emoji's surrogate pair in half.
  for (const character of text.replace(/\r\n?/g, '\n')) {
    if (current.length + character.length > limit) flush();
    current += character;
    if (/[。！？!?；;\n]/u.test(character)) flush();
  }
  flush();
  return chunks;
}

export function normalizedSpeechRate(rate: number): number {
  return Number.isFinite(rate) ? Math.min(1.5, Math.max(0.5, rate)) : 1;
}
