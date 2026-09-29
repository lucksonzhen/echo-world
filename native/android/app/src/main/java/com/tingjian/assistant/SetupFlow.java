package com.tingjian.assistant;

/** Progress is driven by confirmation and observed results, never by elapsed speech time. */
public final class SetupFlow {
    public enum Step { PROVIDER, ADDRESS, MODEL, KEY, TEST, CONSENT, ACCESSIBILITY, VOICE, PAUSE, IMAGES, BATTERY, DONE }
    public static Step restore(String saved, boolean configured, boolean tested, boolean consent, boolean accessibility) {
        Step step;
        try { step = Step.valueOf(saved); } catch (Exception ignored) { step = Step.PROVIDER; }
        if (!configured) return step.ordinal() <= Step.KEY.ordinal() ? step : Step.PROVIDER;
        if (!tested) return Step.TEST;
        if (!consent) return Step.CONSENT;
        if (!accessibility) return Step.ACCESSIBILITY;
        return step.ordinal() >= Step.VOICE.ordinal() ? step : Step.VOICE;
    }
    public static Step after(Step current, boolean completed) {
        if (!completed || current == Step.DONE) return current;
        return Step.values()[current.ordinal() + 1];
    }
    public static boolean needsGuide(boolean configured, boolean consent, boolean accessibility, boolean unfinished) {
        return !configured || !consent || !accessibility || unfinished;
    }
}
