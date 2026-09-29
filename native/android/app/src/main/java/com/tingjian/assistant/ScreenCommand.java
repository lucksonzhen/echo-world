package com.tingjian.assistant;

/** Maps speech to a bounded action; unrecognized speech is a question about one screen. */
public final class ScreenCommand {
    public enum Kind {
        DESCRIBE, READ_TEXT, VIDEO, REPEAT, STOP, PAUSE_START, PAUSE_STOP, FASTER, SLOWER, NORMAL_RATE, QUESTION,
        /** Turns on unattended description of prominent images while browsing. */
        AUTO_IMAGE_START,
        /** Turns off only the automatic image description. */
        AUTO_IMAGE_STOP,
        /** Stops every unattended screen capture (image watch and pause narration); voice standby stays on. */
        MONITOR_STOP
    }
    public final Kind kind;
    public final String text;
    private ScreenCommand(Kind kind, String text) { this.kind = kind; this.text = text; }
    public static ScreenCommand parse(String raw) {
        String text = raw == null ? "" : raw.trim();
        String normalized = text.replaceAll("[\\s，。！？,.!?]", "");
        if (normalized.matches("(请帮我|请|帮我)?(再说一遍|再读一遍|重读一遍|重复朗读|重复描述|重播描述)")) return new ScreenCommand(Kind.REPEAT, text);
        if (normalized.matches("(请)?(开启|开始)?(暂停讲解|暂停后讲解)")) return new ScreenCommand(Kind.PAUSE_START, text);
        if (normalized.matches("(请)?(关闭|停止|结束)(暂停讲解|暂停后讲解)")) return new ScreenCommand(Kind.PAUSE_STOP, text);
        // "监听" is deliberately absent: closing the microphone belongs to VoiceCommandRouter.
        if (normalized.matches("(请)?(停止|关闭|结束|取消|不要|别)(再)?(监控|监视)(屏幕|画面)?")
                || normalized.matches("(请)?(停止|关闭|结束|取消)(屏幕|画面)(监控|监视)")) return new ScreenCommand(Kind.MONITOR_STOP, text);
        if (normalized.matches("(请)?(关闭|停止|结束|取消)(图片|图像)?自动(描述|讲解)(图片|图像)?")) return new ScreenCommand(Kind.AUTO_IMAGE_STOP, text);
        if (normalized.matches("(请|帮我)?(开启|开始|打开|启用)?(图片|图像)?自动(描述|讲解)(图片|图像)?")
                || normalized.matches("(请)?(开启|开始|打开|启用)?(监控|监视)(屏幕|画面)")
                || normalized.matches("(请)?(开启|开始|打开|启用)(屏幕|画面)(监控|监视)")) return new ScreenCommand(Kind.AUTO_IMAGE_START, text);
        if (normalized.matches("(请)?(说快一点|讲快一点|快一点|语速快一点|加快语速)")) return new ScreenCommand(Kind.FASTER, text);
        if (normalized.matches("(请)?(说慢一点|讲慢一点|慢一点|语速慢一点|放慢语速)")) return new ScreenCommand(Kind.SLOWER, text);
        if (normalized.matches("(请)?(正常语速|恢复正常语速)")) return new ScreenCommand(Kind.NORMAL_RATE, text);
        if (normalized.matches("(请)?(停止|停下|停止朗读|停止描述|取消|不用了|别读了)")) return new ScreenCommand(Kind.STOP, text);
        if (normalized.matches("(请|帮我)?(读文字|读一下文字|读屏幕文字|念文字|朗读文字|读出文字)")) return new ScreenCommand(Kind.READ_TEXT, text);
        if (normalized.matches("(请|帮我)?(描述视频|看看视频|观察视频|讲一下视频|视频描述)")) return new ScreenCommand(Kind.VIDEO, text);
        if (normalized.isEmpty() || normalized.matches("(请|帮我)?(描述屏幕|看看屏幕|看屏幕|描述画面|看看画面|看一下屏幕|屏幕上有什么)")) return new ScreenCommand(Kind.DESCRIBE, text);
        return new ScreenCommand(Kind.QUESTION, text.length() > 500 ? text.substring(0, 500) : text);
    }
}
