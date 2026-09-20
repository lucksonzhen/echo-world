import { normalizedSpeechRate, splitSpeechText } from './speechText';

export interface SpeechState {
  speaking: boolean;
  error: string | null;
}

export interface SpeechEngine {
  stop: () => Promise<void>;
  speak: (text: string, options: {
    language: string;
    rate: number;
    useApplicationAudioSession: boolean;
    onStart: () => void;
    onDone: () => void;
    onStopped: () => void;
    onError: (error: Error) => void;
  }) => void;
  maxSpeechInputLength: number;
}

const SPEECH_FAILURE = '语音播放失败。请检查手机的中文语音、音量和静音设置，再点一次朗读。';

/** Serializes stop/start commands and rejects callbacks from older utterances. */
export class SpeechQueue {
  private generation = 0;
  private disposed = false;
  private stopChain: Promise<void> = Promise.resolve();
  private clearWatchdog: (() => void) | undefined;

  constructor(private engine: SpeechEngine, private onState: (state: SpeechState) => void) {}

  private isCurrent(generation: number): boolean {
    return !this.disposed && generation === this.generation;
  }

  private scheduleStop(): Promise<void> {
    this.stopChain = this.stopChain.catch(() => undefined).then(() => this.engine.stop());
    return this.stopChain;
  }

  async stop(): Promise<void> {
    const generation = ++this.generation;
    this.clearWatchdog?.();
    if (!this.disposed) this.onState({ speaking: false, error: null });
    try {
      await this.scheduleStop();
    } catch {
      if (this.isCurrent(generation)) this.onState({ speaking: false, error: SPEECH_FAILURE });
    }
  }

  async speak(text: string, requestedRate = 1): Promise<void> {
    if (this.disposed) return;
    const generation = ++this.generation;
    this.clearWatchdog?.();
    this.onState({ speaking: false, error: null });
    try {
      await this.scheduleStop();
      if (!this.isCurrent(generation)) return;
      const platformLimit = this.engine.maxSpeechInputLength;
      const maximumLength = Number.isFinite(platformLimit) && platformLimit >= 2 ? Math.min(180, platformLimit) : 180;
      const chunks = splitSpeechText(text, maximumLength);
      if (!chunks.length) return;
      const rate = normalizedSpeechRate(requestedRate);
      this.onState({ speaking: true, error: null });

      const play = (index: number): void => {
        if (!this.isCurrent(generation)) return;
        if (index === chunks.length) {
          this.onState({ speaking: false, error: null });
          return;
        }
        let finished = false;
        let watchdog: ReturnType<typeof setTimeout> | undefined;
        const clear = () => {
          if (watchdog !== undefined) clearTimeout(watchdog);
        };
        this.clearWatchdog = clear;
        const finish = (reason: 'done' | 'stopped' | 'error') => {
          if (finished || !this.isCurrent(generation)) return;
          finished = true;
          clear();
          if (reason === 'done') play(index + 1);
          else this.onState({ speaking: false, error: reason === 'error' ? SPEECH_FAILURE : null });
        };
        const timeOut = () => {
          if (finished || !this.isCurrent(generation)) return;
          finish('error');
          // A broken engine must not leave speech running after the UI reports failure.
          void this.scheduleStop().catch(() => undefined);
        };
        watchdog = setTimeout(timeOut, 15_000);
        try {
          this.engine.speak(chunks[index], {
            language: 'zh-CN', rate, useApplicationAudioSession: false,
            onStart: () => {
              if (finished || !this.isCurrent(generation)) return;
              clear();
              watchdog = setTimeout(timeOut, Math.max(30_000, (chunks[index].length / rate) * 1000));
            },
            onDone: () => finish('done'),
            onStopped: () => finish('stopped'),
            onError: () => finish('error'),
          });
        } catch {
          finish('error');
        }
      };
      play(0);
    } catch {
      if (this.isCurrent(generation)) this.onState({ speaking: false, error: SPEECH_FAILURE });
    }
  }

  dispose(): void {
    this.disposed = true;
    this.generation += 1;
    this.clearWatchdog?.();
    void this.scheduleStop().catch(() => undefined);
  }
}
