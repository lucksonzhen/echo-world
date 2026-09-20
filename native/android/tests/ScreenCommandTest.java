package com.tingjian.assistant;

public final class ScreenCommandTest {
    private static int checked;
    public static void main(String[] args) {
        check("描述屏幕", ScreenCommand.Kind.DESCRIBE);
        check("请描述屏幕。", ScreenCommand.Kind.DESCRIBE);
        check("读文字", ScreenCommand.Kind.READ_TEXT);
        check("帮我读出文字", ScreenCommand.Kind.READ_TEXT);
        check("观察视频", ScreenCommand.Kind.VIDEO);
        check("暂停讲解", ScreenCommand.Kind.PAUSE_START);
        check("开启暂停后讲解", ScreenCommand.Kind.PAUSE_START);
        check("关闭暂停讲解", ScreenCommand.Kind.PAUSE_STOP);
        check("快一点", ScreenCommand.Kind.FASTER);
        check("语速慢一点", ScreenCommand.Kind.SLOWER);
        check("正常语速", ScreenCommand.Kind.NORMAL_RATE);
        check("暂停讲解是什么？", ScreenCommand.Kind.QUESTION);
        check("描述视频！", ScreenCommand.Kind.VIDEO);
        check("停止", ScreenCommand.Kind.STOP);
        check("取消", ScreenCommand.Kind.STOP);
        check("停止按钮在哪里？", ScreenCommand.Kind.QUESTION);
        check("左边那张图片是什么？", ScreenCommand.Kind.QUESTION);
        check("视频上的文字写着什么？", ScreenCommand.Kind.QUESTION);
        StringBuilder longQuestion = new StringBuilder();
        for (int i=0;i<600;i++) longQuestion.append('字');
        if (ScreenCommand.parse(longQuestion.toString()).text.length() != 500) throw new AssertionError("Question limit");
        checked++;
        System.out.println("Screen commands: " + checked + " checks passed.");
    }
    private static void check(String input, ScreenCommand.Kind expected) {
        ScreenCommand parsed = ScreenCommand.parse(input);
        if (parsed.kind != expected) throw new AssertionError(input + ": expected " + expected + ", got " + parsed.kind);
        checked++;
    }
}
