package com.tingjian.assistant;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Device TTS only. No microphone, audio recording, or audio files. */
public final class Narrator {
    public interface Listener { void onError(String message); }
    public interface Completion { void onFinished(boolean completed); }

    private static final String VOICE_ERROR = "中文朗读暂不可用，请在系统文字转语音设置中安装中文语音，并检查媒体音量。";
    private static final String FOCUS_ERROR = "暂时无法朗读，声音通道当前不可用。描述仍可通过屏幕阅读器读取，请稍后重试。";
    private static final String INTERRUPTED_ERROR = "朗读被其他声音打断。描述仍保留在面板中，可以稍后重新朗读。";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final AudioManager audio;
    private final AudioAttributes attributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
    private final Object stateLock = new Object();
    private TextToSpeech tts;
    private boolean ready;
    private boolean failed;
    private volatile boolean closed;
    private volatile boolean speaking;
    private volatile float speechRate = 1.35f;
    private long generation;
    private Playback playback;
    private long activeGeneration;
    private String finalUtteranceId;
    private AudioFocusRequest focusRequest;

    /** All fields except the immutable request data are accessed on the main thread. */
    private static final class Playback {
        final long generation;
        final String text;
        final float rate;
        final Completion completion;
        boolean notified;

        Playback(long generation, String text, float rate, Completion completion) {
            this.generation = generation;
            this.text = text;
            this.rate = rate;
            this.completion = completion;
        }
    }

    public Narrator(Context context, Listener listener) {
        this.listener = listener;
        audio = (AudioManager) context.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        tts = new TextToSpeech(context.getApplicationContext(), status -> main.post(() -> initialize(status)));
    }

    private void initialize(int status) {
        if (closed || tts == null) return;
        if (status != TextToSpeech.SUCCESS) { fail(); return; }
        int language = tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
        if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
            language = tts.setLanguage(Locale.CHINESE);
        }
        if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
            fail(); return;
        }
        if (tts.setAudioAttributes(attributes) == TextToSpeech.ERROR) { fail(); return; }
        if (tts.setSpeechRate(speechRate) == TextToSpeech.ERROR) { fail(); return; }
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) {}
            @Override public void onDone(String utteranceId) {
                main.post(() -> {
                    if (!isActiveUtterance(utteranceId) || !utteranceId.equals(finalUtteranceId)) return;
                    Playback finished = playback;
                    long current = activeGeneration;
                    playback = null;
                    activeGeneration = 0L;
                    finalUtteranceId = null;
                    setSpeaking(current, false);
                    abandonFocus();
                    finish(finished, true);
                });
            }
            @Override public void onError(String utteranceId) { reportUtteranceError(utteranceId); }
            @Override public void onError(String utteranceId, int errorCode) { reportUtteranceError(utteranceId); }
            @Override public void onStop(String utteranceId, boolean interrupted) {
                main.post(() -> {
                    if (isActiveUtterance(utteranceId)) failPlayback(activeGeneration, INTERRUPTED_ERROR);
                });
            }
        });
        ready = true;
        Playback requested = playback;
        if (requested != null && isCurrent(requested.generation)) startPlayback(requested);
    }

    private void reportUtteranceError(String utteranceId) {
        main.post(() -> {
            if (isActiveUtterance(utteranceId)) failPlayback(activeGeneration, VOICE_ERROR);
        });
    }

    private void fail() {
        failed = true;
        ready = false;
        long current;
        synchronized (stateLock) { current = generation; }
        stopOutput();
        setSpeaking(current, false);
        listener.onError(VOICE_ERROR);
    }

    /** Includes a queued request and TTS initialization; safe to read from the microphone thread. */
    public boolean isSpeaking() { return speaking; }

    /** Sets the speed of subsequent descriptions. */
    public void setSpeechRate(float rate) { speechRate = clampRate(rate); }

    public void speak(String text) {
        if (text == null || text.trim().isEmpty()) return;
        enqueue(text, speechRate, null);
    }

    /**
     * Speaks with the normal audio focus policy and an optional completion notification.
     * Completion runs once on the main thread: true only after the entire request finishes;
     * false for cancellation, replacement, shutdown, rejected input, or playback failure.
     */
    public void speak(String text, Completion callback) {
        enqueue(text, speechRate, callback);
    }

    private static float clampRate(float rate) {
        return Float.isNaN(rate) ? 1.35f : Math.max(1.0f, Math.min(1.8f, rate));
    }

    private void enqueue(String text, float rate, Completion completion) {
        if (text == null || text.trim().isEmpty()) {
            Playback rejected = new Playback(0L, "", rate, completion);
            runOnMain(() -> finish(rejected, false));
            return;
        }
        final long current;
        final boolean rejected;
        synchronized (stateLock) {
            rejected = closed;
            current = rejected ? generation : ++generation;
            if (!rejected) speaking = true;
        }
        Playback requested = new Playback(current, text, rate, completion);
        runOnMain(() -> {
            if (rejected || !isCurrent(current)) { finish(requested, false); return; }
            stopOutput();
            playback = requested;
            if (failed) { failPlayback(current, VOICE_ERROR); return; }
            if (!ready) return;
            startPlayback(requested);
        });
    }

    private void startPlayback(Playback requested) {
        long current = requested.generation;
        if (!isCurrent(current) || playback != requested) return;
        List<String> chunks = splitText(requested.text, Math.min(300, TextToSpeech.getMaxSpeechInputLength()));
        if (chunks.isEmpty()) {
            playback = null;
            setSpeaking(current, false);
            finish(requested, false);
            return;
        }
        activeGeneration = current;
        finalUtteranceId = current + ":" + (chunks.size() - 1);
        if (!requestFocus(current)) { failPlayback(current, FOCUS_ERROR); return; }
        if (!isCurrent(current)) { stopOutput(); return; }
        try {
            if (tts.setSpeechRate(requested.rate) == TextToSpeech.ERROR) {
                failPlayback(current, VOICE_ERROR);
                return;
            }
            for (int i = 0; i < chunks.size(); i++) {
                int result = tts.speak(chunks.get(i), i == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD,
                        new Bundle(), current + ":" + i);
                if (result == TextToSpeech.ERROR) {
                    failPlayback(current, VOICE_ERROR);
                    break;
                }
            }
        } catch (RuntimeException error) { failPlayback(current, VOICE_ERROR); }
    }

    private boolean requestFocus(long current) {
        if (audio == null) return false;
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .setAcceptsDelayedFocusGain(false)
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(change -> {
                    if (activeGeneration != current || !isCurrent(current)) return;
                    if (change == AudioManager.AUDIOFOCUS_LOSS
                            || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                            || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                        failPlayback(current, INTERRUPTED_ERROR);
                    }
                }, main).build();
        try { return audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED; }
        catch (RuntimeException error) { return false; }
    }

    private void abandonFocus() {
        AudioFocusRequest previous = focusRequest;
        focusRequest = null;
        if (audio != null && previous != null) {
            try { audio.abandonAudioFocusRequest(previous); }
            catch (RuntimeException ignored) { /* Audio service may already be gone during shutdown. */ }
        }
    }

    private void failPlayback(long current, String message) {
        if (!isCurrent(current)) return;
        stopOutput();
        setSpeaking(current, false);
        listener.onError(message);
    }

    private boolean isActiveUtterance(String utteranceId) {
        return activeGeneration != 0L && isCurrent(activeGeneration)
                && utteranceId != null && utteranceId.startsWith(activeGeneration + ":");
    }

    private boolean isCurrent(long current) {
        synchronized (stateLock) { return !closed && current == generation; }
    }

    private void setSpeaking(long current, boolean value) {
        synchronized (stateLock) { if (current == generation) speaking = value; }
    }

    private void runOnMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else main.post(action);
    }

    /** Main-thread cleanup. Do not clear a newer request's speaking flag. */
    private void stopOutput() {
        Playback stopped = playback;
        playback = null;
        activeGeneration = 0L;
        finalUtteranceId = null;
        if (tts != null) {
            try { tts.stop(); }
            catch (RuntimeException ignored) { /* Still release focus if the TTS engine disconnected. */ }
        }
        abandonFocus();
        finish(stopped, false);
    }

    private void finish(Playback requested, boolean completed) {
        if (requested == null || requested.notified) return;
        requested.notified = true;
        // Post after cleanup so callback re-entry cannot overwrite the next playback request.
        if (requested.completion != null) main.post(() -> requested.completion.onFinished(completed));
    }

    /** Uses UTF-16 limits without splitting a surrogate pair. */
    static List<String> splitText(String text, int maximumLength) {
        int limit = Math.max(2, maximumLength);
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int offset = 0; offset < text.length();) {
            int point = text.codePointAt(offset);
            int count = Character.charCount(point);
            if (current.length() + count > limit) addChunk(chunks, current);
            current.appendCodePoint(point);
            if (point == '。' || point == '！' || point == '？' || point == '!' || point == '?' || point == '\n' || point == '；') {
                addChunk(chunks, current);
            }
            offset += count;
        }
        addChunk(chunks, current);
        return chunks;
    }

    private static void addChunk(List<String> chunks, StringBuilder current) {
        String text = current.toString().trim();
        if (!text.isEmpty()) chunks.add(text);
        current.setLength(0);
    }

    /** Stops without an added timer; actual audible stop latency depends on the TTS engine. */
    public void stop() {
        final long current;
        synchronized (stateLock) {
            if (closed) return;
            current = ++generation;
        }
        runOnMain(() -> {
            if (!isCurrent(current)) return;
            stopOutput();
            setSpeaking(current, false);
        });
    }

    public void shutdown() {
        synchronized (stateLock) {
            if (closed) return;
            closed = true;
            generation++;
            speaking = false;
        }
        runOnMain(() -> {
            stopOutput();
            // Keep queued request/completion tasks: each accepted request must resolve exactly once.
            // Late engine events are ignored by the closed/generation checks.
            if (tts != null) { tts.shutdown(); tts = null; }
        });
    }
}
