import { useCallback, useEffect, useRef, useState } from 'react';
import * as Speech from 'expo-speech';
import { SpeechQueue, type SpeechState } from '../media/speechQueue';

export function useSpeech(): {
  speaking: boolean;
  speak: (text: string, rate?: number) => Promise<void>;
  stop: () => Promise<void>;
  error: string | null;
} {
  const [state, setState] = useState<SpeechState>({ speaking: false, error: null });
  const mounted = useRef(true);
  const queue = useRef<SpeechQueue | null>(null);
  const getQueue = useCallback(() => {
    if (!queue.current) {
      queue.current = new SpeechQueue(Speech, (next) => {
        if (mounted.current) setState(next);
      });
    }
    return queue.current;
  }, []);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      queue.current?.dispose();
      queue.current = null;
    };
  }, []);

  const speak = useCallback(async (text: string, rate = 1) => {
    if (mounted.current) await getQueue().speak(text, rate);
  }, [getQueue]);
  const stop = useCallback(async () => {
    if (mounted.current) await getQueue().stop();
  }, [getQueue]);
  return { ...state, speak, stop };
}
