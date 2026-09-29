package com.tingjian.assistant;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Pure, single-threaded wake-word state machine. Call it on the service's serial callback thread.
 * Captures require final microphone transcripts. Stable, exactly prefixed partial STOP/disable
 * commands may interrupt speech early. During narration, only explicit stop/disable/rate/repeat commands run
 * (including the monitor-stop and automatic-image-stop variants). This reduces
 * the app's own echo risk but cannot distinguish a human invocation from identical audio nearby.
 */
public final class VoiceWakeController {
    public static final long COMMAND_WINDOW_MS = 8000L;
    public static final long REPEAT_SUPPRESSION_MS = 1500L;
    public static final long PARTIAL_STOP_STABILITY_MS = 200L;
    private static final long PARTIAL_MAX_OBSERVATION_GAP_MS = 450L;

    public interface Listener {
        void onWake();
        void onCommand(ScreenCommand command);
        void onWakeTimeout();
        default void onDisableListening() {}
    }

    private final Listener listener;
    private final LongSupplier elapsedMillis;
    private boolean enabled;
    private boolean waiting;
    private boolean narrating;
    private long sessionId;
    private long deadline;
    private String lastPartial = "";
    private String lastFinal = "";
    private long lastFinalAt;
    private String partialActionCandidate = "";
    private long partialCandidateSince;
    private long partialCandidateLastSeen;
    private boolean partialActionIssued;

    public VoiceWakeController(Listener listener, LongSupplier elapsedMillis) {
        this.listener = Objects.requireNonNull(listener, "listener");
        this.elapsedMillis = Objects.requireNonNull(elapsedMillis, "elapsedMillis");
    }

    /** Capture this ID when starting ASR, then pass it to the transcript methods. */
    public long start() {
        sessionId++;
        enabled = true;
        clearConversation();
        return sessionId;
    }

    /** Disables wake recognition and invalidates callbacks from the previous ASR run. */
    public void stop() {
        sessionId++;
        enabled = false;
        clearConversation();
    }

    /** Cancels the current wake window while keeping the microphone session enabled. */
    public void reset() { clearConversation(); }

    /** Keep ASR active for hands-free stop. Changing narration state cancels only the wake window. */
    public void setNarrating(boolean value) {
        if (narrating == value) return;
        narrating = value;
        waiting = false;
        deadline = 0L;
        lastPartial = "";
        clearPartialCandidate();
    }

    public boolean isEnabled() { return enabled; }
    public long getSessionId() { return sessionId; }
    public boolean isWaitingForCommand() {
        tick();
        return enabled && waiting;
    }

    /** New/changed hypotheses return true. Only stable explicit STOP/disable may invoke a listener. */
    public boolean onPartial(String text) { return onPartial(sessionId, text); }
    public boolean onPartial(long sourceSessionId, String text) {
        if (!accepts(sourceSessionId)) return false;
        tick();
        if (!accepts(sourceSessionId)) return false;
        String normalized = VoiceCommandRouter.normalize(text);
        boolean changed = !normalized.isEmpty() && !normalized.equals(lastPartial);
        lastPartial = normalized;
        if (partialActionIssued) return changed;
        String instruction = normalized.startsWith(VoiceCommandRouter.WAKE_WORD)
                ? normalized.substring(VoiceCommandRouter.WAKE_WORD.length()) : "";
        boolean exactStop = normalized.equals(VoiceCommandRouter.WAKE_WORD + "停止");
        boolean disable = normalized.startsWith(VoiceCommandRouter.WAKE_WORD)
                && VoiceCommandRouter.isDisableListeningCommand(instruction);
        if (!exactStop && !disable) {
            clearPartialCandidate();
            return changed;
        }
        long now = elapsedMillis.getAsLong();
        if (normalized.equals(lastFinal) && now >= lastFinalAt && now - lastFinalAt < REPEAT_SUPPRESSION_MS) {
            clearPartialCandidate();
            return changed;
        }
        if (!normalized.equals(partialActionCandidate) || now < partialCandidateLastSeen
                || now - partialCandidateLastSeen > PARTIAL_MAX_OBSERVATION_GAP_MS) {
            partialActionCandidate = normalized;
            partialCandidateSince = now;
        }
        partialCandidateLastSeen = now;
        if (now - partialCandidateSince < PARTIAL_STOP_STABILITY_MS) return changed;
        partialActionIssued = true;
        lastFinal = normalized;
        lastFinalAt = now;
        clearPartialCandidate();
        if (disable) disableListening();
        else emit(ScreenCommand.parse("停止"));
        return changed;
    }

    public void onFinal(String text) { onFinal(sessionId, text); }
    public void onFinal(long sourceSessionId, String text) {
        if (!accepts(sourceSessionId)) return;
        long now = elapsedMillis.getAsLong();
        expire(now);
        // Expiration listeners may stop recognition. Never act after such a synchronous stop.
        if (!accepts(sourceSessionId)) return;
        lastPartial = "";
        clearPartialCandidate();
        String normalized = VoiceCommandRouter.normalize(text);
        if (partialActionIssued) {
            // The remainder/final of an already executed utterance cannot launch
            // a new capture, even if the recognizer later extends the sentence.
            partialActionIssued = false;
            if (!normalized.isEmpty()) { lastFinal = normalized; lastFinalAt = now; }
            return;
        }
        if (normalized.isEmpty()) return;
        if (normalized.equals(lastFinal) && now >= lastFinalAt && now - lastFinalAt < REPEAT_SUPPRESSION_MS) return;
        lastFinal = normalized;
        lastFinalAt = now;

        if (normalized.startsWith(VoiceCommandRouter.WAKE_WORD)) {
            String instruction = normalized.substring(VoiceCommandRouter.WAKE_WORD.length());
            if (VoiceCommandRouter.isDisableListeningCommand(instruction)) {
                disableListening();
                return;
            }
            if (narrating) {
                ScreenCommand command = VoiceCommandRouter.route(instruction);
                if (command != null && permittedDuringNarration(command.kind)) emit(command);
                return;
            }
            if (instruction.isEmpty()) {
                waiting = true;
                deadline = now + COMMAND_WINDOW_MS;
                listener.onWake();
                return;
            }
            ScreenCommand command = VoiceCommandRouter.route(instruction);
            if (command != null) emit(command);
            return;
        }
        if (waiting && !narrating) {
            if (VoiceCommandRouter.isDisableListeningCommand(text)) {
                disableListening();
                return;
            }
            ScreenCommand command = VoiceCommandRouter.route(text);
            if (command != null) emit(command);
        }
    }

    /** The host can call this on its normal timer to announce an expired eight-second window. */
    public void tick() {
        if (enabled) expire(elapsedMillis.getAsLong());
    }

    private boolean accepts(long sourceSessionId) { return enabled && sourceSessionId == sessionId; }

    private static boolean permittedDuringNarration(ScreenCommand.Kind kind) {
        return kind == ScreenCommand.Kind.STOP || kind == ScreenCommand.Kind.PAUSE_STOP
                || kind == ScreenCommand.Kind.MONITOR_STOP || kind == ScreenCommand.Kind.AUTO_IMAGE_STOP
                || kind == ScreenCommand.Kind.FASTER || kind == ScreenCommand.Kind.SLOWER
                || kind == ScreenCommand.Kind.NORMAL_RATE || kind == ScreenCommand.Kind.REPEAT
                || kind == ScreenCommand.Kind.OVERLAY_HIDE || kind == ScreenCommand.Kind.OVERLAY_SHOW;
    }

    private void expire(long now) {
        if (waiting && now >= deadline) {
            waiting = false;
            deadline = 0L;
            listener.onWakeTimeout();
        }
    }

    private void emit(ScreenCommand command) {
        waiting = false;
        deadline = 0L;
        listener.onCommand(command);
    }

    private void disableListening() {
        stop();
        listener.onDisableListening();
    }

    private void clearConversation() {
        waiting = false;
        deadline = 0L;
        lastPartial = "";
        lastFinal = "";
        lastFinalAt = 0L;
        partialActionIssued = false;
        clearPartialCandidate();
    }

    private void clearPartialCandidate() {
        partialActionCandidate = "";
        partialCandidateSince = 0L;
        partialCandidateLastSeen = 0L;
    }
}
