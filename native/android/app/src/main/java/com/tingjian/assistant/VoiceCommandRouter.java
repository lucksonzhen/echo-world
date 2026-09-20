package com.tingjian.assistant;

import java.util.Locale;

/** Pure command matching. A wake word in the middle of unrelated speech is not an invocation. */
public final class VoiceCommandRouter {
    public static final String WAKE_WORD = "小助手";

    private VoiceCommandRouter() {}

    public static String normalize(String text) {
        if (text == null) return "";
        StringBuilder normalized = new StringBuilder();
        for (int offset = 0; offset < text.length();) {
            int point = text.codePointAt(offset);
            int type = Character.getType(point);
            if (!Character.isWhitespace(point) && !Character.isSpaceChar(point)
                    && type != Character.CONNECTOR_PUNCTUATION && type != Character.DASH_PUNCTUATION
                    && type != Character.START_PUNCTUATION && type != Character.END_PUNCTUATION
                    && type != Character.INITIAL_QUOTE_PUNCTUATION && type != Character.FINAL_QUOTE_PUNCTUATION
                    && type != Character.OTHER_PUNCTUATION && type != Character.FORMAT) {
                normalized.appendCodePoint(point);
            }
            offset += Character.charCount(point);
        }
        return normalized.toString().toLowerCase(Locale.ROOT);
    }

    public static boolean startsWithWakeWord(String text) {
        return normalize(text).startsWith(WAKE_WORD);
    }

    /** The controller applies the wake/session requirement before calling this matcher. */
    public static boolean isDisableListeningCommand(String text) {
        String normalized = normalize(text);
        return normalized.equals("关闭语音监听") || normalized.equals("退出助手") || normalized.equals("关闭助手");
    }

    /** Returns null for ordinary statements and unsupported commands, not a fabricated screen question. */
    public static ScreenCommand route(String text) {
        String normalized = normalize(text);
        if (normalized.isEmpty()) return null;
        // These natural variants stay constrained to the three supported visual operations.
        String action = normalized.replaceFirst("^(请帮我|麻烦帮我|麻烦你|帮我|请)", "");
        if (action.matches("(描述|看看|看一下|讲讲)(当前|现在的|这个)?(屏幕|画面)")) {
            return ScreenCommand.parse("描述屏幕");
        }
        if (action.matches("(读|读出|读一下|朗读|念)(当前|现在的|屏幕上的|画面里的|这些)?文字")) {
            return ScreenCommand.parse("读文字");
        }
        if (action.matches("(观察|描述|看看|讲讲|看一下)(当前|现在的|这个)?视频")) {
            return ScreenCommand.parse("观察视频");
        }
        ScreenCommand parsed = ScreenCommand.parse(normalized);
        if (parsed.kind != ScreenCommand.Kind.QUESTION) return parsed;
        if (isQuestion(normalized)) return ScreenCommand.parse(text.trim());
        return null;
    }

    private static boolean isQuestion(String text) {
        String[] markers = { "什么", "哪里", "哪儿", "哪边", "哪个", "哪些", "哪种", "多少", "谁", "怎么", "怎样",
                "如何", "是否", "有没有", "是不是", "能否", "可不可以", "啥" };
        for (String marker : markers) if (text.contains(marker)) return true;
        return text.matches(".*(有几|是几|第几|几(个|位|只|辆|条|张|件|层|行|列|点|分钟|秒)).*")
                || text.endsWith("吗") || text.endsWith("呢")
                || (text.startsWith("问一下") && text.length() > 3)
                || (text.startsWith("我想知道") && text.length() > 4);
    }
}
