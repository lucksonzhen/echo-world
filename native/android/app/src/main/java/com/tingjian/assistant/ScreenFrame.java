package com.tingjian.assistant;

public final class ScreenFrame {
    public final String dataUrl;
    public final int timestampMs;
    public ScreenFrame(String dataUrl, int timestampMs) {
        this.dataUrl = dataUrl;
        this.timestampMs = timestampMs;
    }
}
