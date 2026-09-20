package com.tingjian.assistant;

/** Pure playback transition/debounce policy, independent of Android and wall-clock timers. */
public final class PlaybackPausePolicy {
    public enum State { PLAYING, PAUSED, BUFFERING, STOPPED, OTHER }
    static final int NONE = 0, RESUMED = 1, PAUSED = 2;
    static final long DEBOUNCE_MS = 500;
    private State previous = State.OTHER;
    private long pausedAt = -1;
    private boolean sawPlaying;
    private boolean delivered;

    int onState(State state, long now) {
        int action = NONE;
        if (state == State.PLAYING) {
            if (previous != state) action = RESUMED;
            sawPlaying = true; pausedAt = -1; delivered = false;
        } else if (state == State.PAUSED) {
            if (previous == State.PLAYING && sawPlaying) pausedAt = now;
        } else {
            sawPlaying = false; pausedAt = -1; delivered = false;
        }
        previous = state;
        return action;
    }
    int poll(long now) {
        if (isPending() && now - pausedAt >= DEBOUNCE_MS) { delivered = true; return PAUSED; }
        return NONE;
    }
    boolean isPending() { return previous == State.PAUSED && sawPlaying && pausedAt >= 0 && !delivered; }
    long remaining(long now) { return Math.max(0, DEBOUNCE_MS - (now - pausedAt)); }
    void reset() { previous = State.OTHER; pausedAt = -1; sawPlaying = false; delivered = false; }

    static boolean isAtEnd(long durationMs, long positionMs) {
        return durationMs > 0 && positionMs >= 0 && positionMs >= Math.max(0, durationMs - 250);
    }
}
