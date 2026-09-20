package com.tingjian.assistant;

/** Pure Java policy checks; no Android SDK or runtime is needed. */
public final class MediaPausePolicyTest {
    private static final PlaybackPausePolicy.State PLAYING = PlaybackPausePolicy.State.PLAYING;
    private static final PlaybackPausePolicy.State PAUSED = PlaybackPausePolicy.State.PAUSED;
    private static final PlaybackPausePolicy.State BUFFERING = PlaybackPausePolicy.State.BUFFERING;
    private static final PlaybackPausePolicy.State STOPPED = PlaybackPausePolicy.State.STOPPED;
    private static final PlaybackPausePolicy.State NONE = PlaybackPausePolicy.State.OTHER;
    private static int checks;
    public static void main(String[] args) {
        PlaybackPausePolicy policy = new PlaybackPausePolicy();
        policy.onState(PAUSED, 0);
        check(policy.poll(1000) == 0, "Initial paused state must not narrate");

        check(policy.onState(PLAYING, 1100) == 1, "Playing immediately notifies resumed");
        policy.onState(PAUSED, 1200);
        check(policy.poll(1699) == 0, "Pause debounce excludes short pauses");
        check(policy.poll(1700) == 2, "Stable playing-to-paused transition narrates after 500 ms");
        check(policy.poll(5000) == 0, "A single pause is delivered only once");

        policy.reset();
        policy.onState(PLAYING, 0);
        policy.onState(PAUSED, 50);
        policy.onState(PAUSED, 500);
        check(policy.poll(550) == 2, "Repeated paused callbacks do not restart debounce");

        policy.reset();
        policy.onState(PLAYING, 0);
        policy.onState(PAUSED, 50);
        check(policy.onState(PLAYING, 200) == 1, "Resume interrupts immediately while debounce pending");
        check(policy.poll(1000) == 0, "Quick resume cancels queued narration");

        policy.reset();
        policy.onState(PLAYING, 0);
        policy.onState(BUFFERING, 50);
        policy.onState(PAUSED, 100);
        check(policy.poll(1000) == 0, "Buffering-to-paused is not a user pause inference");

        policy.reset();
        policy.onState(PLAYING, 0);
        policy.onState(STOPPED, 50);
        policy.onState(PAUSED, 100);
        check(policy.poll(1000) == 0, "Stopped/end-to-paused is ignored");

        policy.reset();
        policy.onState(PLAYING, 0);
        policy.onState(PAUSED, 50);
        policy.reset();
        check(policy.poll(1000) == 0, "Changing app, session or permission cancels pending pause");

        policy.onState(PLAYING, 1100);
        policy.onState(PAUSED, 1200);
        check(policy.poll(1700) == 2, "A new valid playback cycle may narrate again");
        policy.onState(PLAYING, 1800);
        policy.onState(PAUSED, 1900);
        policy.onState(NONE, 2000);
        check(policy.poll(3000) == 0, "Unknown media state invalidates pending narration");
        check(PlaybackPausePolicy.isAtEnd(10000, 10000), "A paused end position is recognized as completed");
        check(PlaybackPausePolicy.isAtEnd(10000, 9750), "Small end-position rounding is treated conservatively");
        check(!PlaybackPausePolicy.isAtEnd(10000, 9000), "A pause away from the end remains eligible");
        check(!PlaybackPausePolicy.isAtEnd(0, 10000), "Unknown duration is not assumed to mean completion");
        check(!PlaybackPausePolicy.isAtEnd(10000, -1), "Unknown position is not assumed to mean completion");
        System.out.println("MediaPausePolicyTest: " + checks + " checks passed");
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
