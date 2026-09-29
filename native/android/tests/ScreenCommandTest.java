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
        check("再说一遍", ScreenCommand.Kind.REPEAT);
        check("请再读一遍。", ScreenCommand.Kind.REPEAT);
        check("请帮我重读一遍", ScreenCommand.Kind.REPEAT);
        check("帮我重复朗读", ScreenCommand.Kind.REPEAT);
        check("重复描述", ScreenCommand.Kind.REPEAT);
        check("重播描述", ScreenCommand.Kind.REPEAT);
        check("再说一遍的按钮在哪里？", ScreenCommand.Kind.QUESTION);
        check("不要再说一遍", ScreenCommand.Kind.QUESTION);
        check("停止按钮在哪里？", ScreenCommand.Kind.QUESTION);
        check("停止监控屏幕", ScreenCommand.Kind.MONITOR_STOP);
        check("请停止监控。", ScreenCommand.Kind.MONITOR_STOP);
        check("关闭屏幕监控", ScreenCommand.Kind.MONITOR_STOP);
        check("不要再监视画面", ScreenCommand.Kind.MONITOR_STOP);
        check("关闭语音监听", ScreenCommand.Kind.QUESTION);
        check("开启图片自动描述", ScreenCommand.Kind.AUTO_IMAGE_START);
        check("自动描述图片", ScreenCommand.Kind.AUTO_IMAGE_START);
        check("开启自动讲解", ScreenCommand.Kind.AUTO_IMAGE_START);
        check("监控屏幕", ScreenCommand.Kind.AUTO_IMAGE_START);
        check("开启屏幕监控", ScreenCommand.Kind.AUTO_IMAGE_START);
        check("关闭图片自动描述", ScreenCommand.Kind.AUTO_IMAGE_STOP);
        check("停止自动描述", ScreenCommand.Kind.AUTO_IMAGE_STOP);
        check("自动描述是什么？", ScreenCommand.Kind.QUESTION);
        check("监控屏幕的按钮在哪？", ScreenCommand.Kind.QUESTION);
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
