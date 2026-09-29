package com.tingjian.assistant;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Runs bundled synthetic audio through the actual native Vosk model; no microphone or network. */
public final class VoiceModelInstrumentation extends Instrumentation {
    private static final String[][] CASES = {
        {"describe-screen.wav", "DESCRIBE"},
        {"read-text.wav", "READ_TEXT"},
        {"observe-video.wav", "VIDEO"},
        {"stop.wav", "STOP"},
        {"disable-listening.wav", "DISABLE_LISTENING"},
        {"pause-start.wav", "PAUSE_START"},
        {"pause-stop.wav", "PAUSE_STOP"},
        {"monitor-stop.wav", "MONITOR_STOP"},
        {"auto-image-start.wav", "AUTO_IMAGE_START"},
        {"auto-image-stop.wav", "AUTO_IMAGE_STOP"},
        {"faster.wav", "FASTER"},
        {"slower.wav", "SLOWER"},
        {"normal-rate.wav", "NORMAL_RATE"}
    };

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        // Keep model extraction and native decoding off the application's main thread.
        new Thread(this::runFixtures, "voice-model-fixtures").start();
    }

    private void runFixtures() {
        Bundle summary = new Bundle();
        int passed = 0;
        int failed = 0;
        Model model = null;
        long started = SystemClock.elapsedRealtime();
        StringBuilder report = new StringBuilder("\nSynthetic Vosk model fixtures (not microphone accuracy):\n");
        try {
            model = new Model(OfflineModelStore.prepare(getTargetContext()).getAbsolutePath());
            for (String[] fixture : CASES) {
                Bundle status = new Bundle();
                status.putString("fixture", fixture[0]);
                status.putString("expected", fixture[1]);
                try {
                    Recognition result = recognize(model, fixture[0]);
                    status.putString("recognized", result.text);
                    status.putString("endpoints", result.endpoints.toString());
                    status.putString("streaming_events", result.streamingEvents.toString());
                    status.putString("endpoint_only_events", result.endpointEvents.toString());
                    status.putString("event_trace", result.eventTrace.toString());
                    status.putString("partial_commands", result.partialEvents.toString());
                    Probe combined = new Probe();
                    combined.controller.onFinal(result.text);
                    // The concatenated transcript checks the command itself. Streaming endpoints
                    // and repeated partial observations check WakeWordService's delivery boundaries.
                    requireEvent(combined.events, fixture[1], "combined transcript");
                    requireEvent(result.endpointEvents, fixture[1], "streaming endpoints only");
                    requireEvent(result.streamingEvents, fixture[1], "streaming partials and endpoints");
                    if (("STOP".equals(fixture[1]) || "DISABLE_LISTENING".equals(fixture[1]))
                            && !result.partialEvents.contains(fixture[1])) {
                        throw new IOException("Expected stable partial interruption before the final endpoint; trace=" + result.eventTrace);
                    }
                    passed++;
                    status.putBoolean("passed", true);
                    report.append("PASS ").append(fixture[0]).append(": ").append(result.text)
                            .append("; ").append(result.eventTrace).append('\n');
                } catch (Exception | LinkageError error) {
                    failed++;
                    status.putBoolean("passed", false);
                    status.putString("failure", describe(error));
                    report.append("FAIL ").append(fixture[0]).append(": ").append(describe(error))
                            .append("; recognized=").append(status.getString("recognized", "unavailable")).append('\n');
                }
                sendStatus(0, status);
            }
        } catch (Exception | LinkageError error) {
            failed = CASES.length;
            summary.putString("setup_failure", describe(error));
            report.append("SETUP FAIL: ").append(describe(error)).append('\n');
        } finally {
            if (model != null) model.close();
        }
        summary.putInt("passed", passed);
        summary.putInt("failed", failed);
        summary.putLong("elapsed_ms", SystemClock.elapsedRealtime() - started);
        summary.putString("stream", report.append("Passed ").append(passed).append('/').append(CASES.length).append('\n').toString());
        finish(failed == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, summary);
    }

    private Recognition recognize(Model model, String filename) throws Exception {
        short[] pcm;
        try (InputStream source = getContext().getAssets().open("voice-commands/" + filename)) {
            pcm = readPcmWave(source);
        }
        Recognizer recognizer = new Recognizer(model, 16000f);
        Probe streaming = new Probe();
        Probe endpointsOnly = new Probe();
        List<String> endpoints = new ArrayList<>();
        try {
            short[] frame = new short[1600]; // Same 100 ms blocks as the microphone service.
            for (int offset = 0; offset < pcm.length; offset += frame.length) {
                int count = Math.min(frame.length, pcm.length - offset);
                System.arraycopy(pcm, offset, frame, 0, count);
                // Native decoding is faster/slower than real time depending on the host. Wake
                // windows and the 200 ms stable-partial rule must use the audio's own timeline.
                long observedAt = (offset + (long) count) * 1000L / 16000L;
                streaming.observeTime(observedAt);
                endpointsOnly.observeTime(observedAt);
                if (recognizer.acceptWaveForm(frame, count)) {
                    String text = new JSONObject(recognizer.getResult()).optString("text", "").trim();
                    if (!text.isEmpty()) endpoints.add(text);
                    streaming.stage = "final";
                    streaming.controller.onFinal(text);
                    endpointsOnly.stage = "final";
                    endpointsOnly.controller.onFinal(text);
                } else {
                    String partial = new JSONObject(recognizer.getPartialResult()).optString("partial", "").trim();
                    streaming.stage = "partial";
                    // Deliver unchanged hypotheses too: their elapsed stability allows early STOP.
                    streaming.controller.onPartial(partial);
                }
            }
            // Preserve any final decoder tail for diagnostics and the combined-text assertion.
            // Do not deliver a forced EOF result to the streaming probe: production waits for an endpoint.
            String tail = new JSONObject(recognizer.getFinalResult()).optString("text", "").trim();
            List<String> allText = new ArrayList<>(endpoints);
            if (!tail.isEmpty()) allText.add(tail);
            String text = String.join(" ", allText);
            if (text.isEmpty()) throw new IOException("Model returned no words for the synthetic fixture.");
            return new Recognition(text, endpoints, streaming.events, endpointsOnly.events,
                    streaming.eventTrace, streaming.partialEvents);
        } finally { recognizer.close(); }
    }

    private static void requireEvent(List<String> events, String expected, String stage) throws IOException {
        if (events.size() != 1 || !expected.equals(events.get(0))) {
            throw new IOException(stage + " expected [" + expected + "] but received " + events);
        }
    }

    private static final class Probe implements VoiceWakeController.Listener {
        final List<String> events = new ArrayList<>();
        final List<String> eventTrace = new ArrayList<>();
        final List<String> partialEvents = new ArrayList<>();
        long audioTimeMs;
        String stage = "combined";
        final VoiceWakeController controller = new VoiceWakeController(this, () -> audioTimeMs);
        Probe() { controller.start(); }
        void observeTime(long elapsedMs) { audioTimeMs = elapsedMs; controller.tick(); }
        @Override public void onWake() {}
        @Override public void onCommand(ScreenCommand command) { record(command.kind.name()); }
        @Override public void onWakeTimeout() { record("WAKE_TIMEOUT"); }
        @Override public void onDisableListening() { record("DISABLE_LISTENING"); }
        private void record(String event) {
            events.add(event);
            eventTrace.add(stage + "@" + audioTimeMs + "ms=" + event);
            if ("partial".equals(stage)) partialEvents.add(event);
        }
    }

    private static final class Recognition {
        final String text;
        final List<String> endpoints;
        final List<String> streamingEvents;
        final List<String> endpointEvents;
        final List<String> eventTrace;
        final List<String> partialEvents;
        Recognition(String text, List<String> endpoints, List<String> streamingEvents,
                List<String> endpointEvents, List<String> eventTrace, List<String> partialEvents) {
            this.text = text; this.endpoints = endpoints; this.streamingEvents = streamingEvents;
            this.endpointEvents = endpointEvents; this.eventTrace = eventTrace; this.partialEvents = partialEvents;
        }
    }

    static short[] readPcmWave(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > 1024 * 1024) throw new IOException("Fixture exceeds 1 MiB.");
            output.write(buffer, 0, count);
        }
        byte[] wave = output.toByteArray();
        if (wave.length < 12 || !tag(wave, 0, "RIFF") || !tag(wave, 8, "WAVE")) throw new IOException("Invalid RIFF/WAVE fixture.");
        long declaredLength = u32(wave, 4) + 8L;
        if (declaredLength != wave.length) throw new IOException("Incorrect RIFF length.");
        boolean formatFound = false;
        int dataOffset = -1;
        int dataLength = 0;
        for (int offset = 12; offset + 8 <= wave.length;) {
            long chunkLength = u32(wave, offset + 4);
            long next = offset + 8L + chunkLength + (chunkLength & 1L);
            if (next > wave.length) throw new IOException("Truncated WAV chunk.");
            if (tag(wave, offset, "fmt ")) {
                if (chunkLength < 16 || u16(wave, offset + 8) != 1 || u16(wave, offset + 10) != 1
                        || u32(wave, offset + 12) != 16000 || u32(wave, offset + 16) != 32000
                        || u16(wave, offset + 20) != 2 || u16(wave, offset + 22) != 16) {
                    throw new IOException("Fixture must be 16 kHz mono PCM16.");
                }
                formatFound = true;
            } else if (tag(wave, offset, "data")) {
                if (dataOffset >= 0) throw new IOException("Multiple WAV data chunks are unsupported.");
                dataOffset = offset + 8;
                dataLength = (int) chunkLength;
            }
            offset = (int) next;
        }
        if (!formatFound || dataOffset < 0 || dataLength == 0 || (dataLength & 1) != 0) throw new IOException("Missing PCM16 data.");
        short[] pcm = new short[dataLength / 2];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (short) u16(wave, dataOffset + i * 2);
        return pcm;
    }

    private static boolean tag(byte[] bytes, int offset, String value) {
        return value.equals(new String(bytes, offset, 4, StandardCharsets.US_ASCII));
    }

    private static int u16(byte[] bytes, int offset) {
        return (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8);
    }

    private static long u32(byte[] bytes, int offset) {
        return ((long) u16(bytes, offset)) | ((long) u16(bytes, offset + 2) << 16);
    }

    private static String describe(Throwable error) {
        return error.getClass().getSimpleName() + ": " + (error.getMessage() == null ? "no detail" : error.getMessage());
    }
}
