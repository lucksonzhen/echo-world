package com.tingjian.assistant;

import java.util.ArrayList;
import java.util.List;

public final class VoiceWakeControllerTest {
    private static int assertions;

    private static final class Fixture implements VoiceWakeController.Listener {
        long now;
        int wakes;
        int timeouts;
        int disabled;
        final List<ScreenCommand> commands = new ArrayList<>();
        final VoiceWakeController controller = new VoiceWakeController(this, () -> now);
        @Override public void onWake() { wakes++; }
        @Override public void onCommand(ScreenCommand command) { commands.add(command); }
        @Override public void onWakeTimeout() { timeouts++; }
        @Override public void onDisableListening() { disabled++; }
    }

    public static void main(String[] args) {
        finalTextRunsOneCommand();
        wakeOnlyHasEightSecondWindow();
        ordinarySpeechIsIgnored();
        cancellationAndOldCallbacksAreIgnored();
        punctuationAndQuestionsAreHandled();
        timeoutCallbackCanDisableController();
        disablingRequiresWakeAndInvalidatesCallbacks();
        narrationAcceptsOnlyExplicitStopOrDisable();
        narrationTransitionsCancelWakeWithoutRestartingSession();
        stablePartialStopRunsOnceBeforeFinal();
        changingOrStalePartialCannotStop();
        partialCannotCaptureOrChangeRate();
        partialDisableInvalidatesCallbacks();
        partialStopRemainderCannotLaunchCapture();
        narrationAllowsExplicitRateAndPauseStop();
        finalStartsPauseNarration();
        System.out.println("Voice wake controller: " + assertions + " assertions passed.");
    }

    private static void finalTextRunsOneCommand() {
        Fixture f = new Fixture();
        long id = f.controller.start();
        check(f.controller.onPartial(id, "小 助 手"), "accept first partial");
        check(!f.controller.onPartial(id, "小助手"), "normalize and suppress repeated partial");
        check(f.controller.onPartial(id, "小助手描述屏幕"), "accept changed partial");
        check(f.wakes == 0 && f.commands.isEmpty(), "partial never wakes or captures");
        f.controller.onFinal(id, "小助手，描述屏幕。");
        check(f.commands.size() == 1 && f.commands.get(0).kind == ScreenCommand.Kind.DESCRIBE, "final invokes describe");
        f.controller.onPartial(id, "小助手描述屏幕");
        f.controller.onFinal(id, "小 助 手 描 述 屏 幕");
        check(f.commands.size() == 1, "duplicate result cannot capture twice");
        f.now = 1600;
        f.controller.onFinal(id, "小助手描述屏幕");
        check(f.commands.size() == 2, "a later deliberate repeat works");
    }

    private static void wakeOnlyHasEightSecondWindow() {
        Fixture f = new Fixture();
        f.controller.start();
        f.controller.onFinal("小助手。");
        check(f.wakes == 1 && f.controller.isWaitingForCommand(), "wake-only arms next sentence");
        f.now = 7999;
        f.controller.onFinal("读文字");
        check(f.commands.size() == 1 && f.commands.get(0).kind == ScreenCommand.Kind.READ_TEXT, "follow-up just before deadline");
        check(!f.controller.isWaitingForCommand(), "executed command consumes wake window");
        f.controller.onFinal("观察视频");
        check(f.commands.size() == 1, "subsequent unprefixed commands do not run");
        f.now = 10_000;
        f.controller.onFinal("小助手");
        f.now = 18_000;
        f.controller.onFinal("观察视频");
        check(f.commands.size() == 1 && f.timeouts == 1, "deadline is exclusive");
        f.controller.tick();
        check(f.timeouts == 1, "timeout announced only once");
    }

    private static void ordinarySpeechIsIgnored() {
        Fixture f = new Fixture();
        f.controller.start();
        for (String text : new String[] { "描述屏幕", "大家可以对小助手说描述屏幕", "电视剧里小助手正在工作", "小助手今天表现很好", "小助手播放音乐", "小助手问一下", "小助手最近几天真忙" }) {
            f.controller.onFinal(text);
            f.now += 2000;
        }
        check(f.commands.isEmpty() && f.wakes == 0, "ordinary speech, embedded wake and unsupported commands ignored");
        f.controller.onFinal("小助手");
        f.controller.onFinal("今天阳光很好");
        check(f.commands.isEmpty() && f.controller.isWaitingForCommand(), "ordinary next sentence is not a screen question");
        f.controller.onFinal("左边的人在做什么");
        check(f.commands.size() == 1 && f.commands.get(0).kind == ScreenCommand.Kind.QUESTION, "visual follow-up question");
    }

    private static void cancellationAndOldCallbacksAreIgnored() {
        Fixture f = new Fixture();
        f.controller.onFinal("小助手描述屏幕");
        check(f.commands.isEmpty(), "initially disabled");
        long oldId = f.controller.start();
        f.controller.onFinal(oldId, "小助手");
        f.controller.stop();
        f.controller.onFinal(oldId, "描述屏幕");
        check(f.commands.isEmpty() && !f.controller.isWaitingForCommand(), "stop invalidates armed command");
        long newId = f.controller.start();
        f.controller.onFinal(oldId, "小助手观察视频");
        check(!f.controller.onPartial(oldId, "小助手"), "old partial rejected after restart");
        check(f.commands.isEmpty(), "old final rejected after restart");
        f.controller.onFinal(newId, "小助手停止");
        check(f.commands.size() == 1 && f.commands.get(0).kind == ScreenCommand.Kind.STOP, "current explicit stop command accepted");
        f.controller.onFinal(newId, "小助手");
        f.controller.reset();
        f.controller.onFinal(newId, "读文字");
        check(f.commands.size() == 1 && f.controller.isEnabled(), "reset cancels wake window without disabling microphone session");
    }

    private static void punctuationAndQuestionsAreHandled() {
        Fixture f = new Fixture();
        f.controller.start();
        f.controller.onFinal(" 小，助。手！请帮我读出屏幕上的文字？ ");
        check(f.commands.size() == 1 && f.commands.get(0).kind == ScreenCommand.Kind.READ_TEXT, "Unicode punctuation and spaces removed");
        f.controller.onFinal("小助手，观察这个视频");
        check(f.commands.size() == 2 && f.commands.get(1).kind == ScreenCommand.Kind.VIDEO, "video command");
        f.controller.onFinal("小助手，停止按钮在哪里？");
        check(f.commands.size() == 3 && f.commands.get(2).kind == ScreenCommand.Kind.QUESTION, "question containing stop is not a stop command");
        check(VoiceCommandRouter.route(null) == null && VoiceCommandRouter.route("， 。") == null, "empty input ignored");
    }

    private static void timeoutCallbackCanDisableController() {
        final long[] now = { 0L };
        final VoiceWakeController[] holder = new VoiceWakeController[1];
        final int[] count = { 0 };
        holder[0] = new VoiceWakeController(new VoiceWakeController.Listener() {
            @Override public void onWake() {}
            @Override public void onCommand(ScreenCommand command) { count[0]++; }
            @Override public void onWakeTimeout() { holder[0].stop(); }
        }, () -> now[0]);
        holder[0].start(); holder[0].onFinal("小助手");
        now[0] = 9000L;
        holder[0].onFinal("小助手描述屏幕");
        check(count[0] == 0 && !holder[0].isEnabled(), "reentrant timeout stop prevents a late command");
    }

    private static void disablingRequiresWakeAndInvalidatesCallbacks() {
        for (String command : new String[] { "关闭语音监听", "退出助手", "关闭助手" }) {
            Fixture f = new Fixture();
            long id = f.controller.start();
            f.controller.onFinal(id, command);
            check(f.disabled == 0 && f.controller.isEnabled(), "bare disable phrase ignored: " + command);
            f.controller.onFinal(id, "小助手，" + command);
            check(f.disabled == 1 && !f.controller.isEnabled(), "explicit disable stops listening state: " + command);
            f.controller.onFinal(id, "小助手描述屏幕");
            f.controller.onFinal(id, "小助手" + command);
            check(f.disabled == 1 && f.commands.isEmpty(), "late callbacks cannot act after disable");
        }
        Fixture f = new Fixture();
        f.controller.start();
        f.controller.onFinal("小助手");
        f.now = 7000;
        f.controller.onFinal("关闭语音监听");
        check(f.disabled == 1 && !f.controller.isEnabled(), "armed follow-up may disable listening");
        check(!VoiceCommandRouter.isDisableListeningCommand("如果要关闭助手就说这句话"), "disable matcher is anchored");
    }

    private static void narrationAcceptsOnlyExplicitStopOrDisable() {
        Fixture f = new Fixture();
        f.controller.start();
        f.controller.setNarrating(true);
        for (String phrase : new String[] { "小助手", "小助手描述屏幕", "小助手读文字", "小助手观察视频",
                "小助手左边的人是谁", "停止", "退出助手", "关闭语音监听" }) {
            f.controller.onFinal(phrase);
            f.now += 2000;
        }
        check(f.wakes == 0 && f.commands.isEmpty() && f.disabled == 0, "narration rejects wake-only, ordinary commands and unprefixed stops");
        f.controller.onFinal("小助手停止");
        check(f.commands.size() == 1 && f.commands.get(0).kind == ScreenCommand.Kind.STOP, "hands-free explicit stop remains usable during narration");
        check(f.controller.isEnabled(), "stop command leaves microphone wake recognition enabled");
        f.controller.onFinal("小助手退出助手");
        check(f.disabled == 1 && !f.controller.isEnabled(), "explicit shutdown remains usable during narration");
    }

    private static void narrationTransitionsCancelWakeWithoutRestartingSession() {
        Fixture f = new Fixture();
        long id = f.controller.start();
        f.controller.onFinal(id, "小助手");
        f.controller.setNarrating(true);
        check(!f.controller.isWaitingForCommand() && f.controller.getSessionId() == id, "narration start clears wake window without invalidating ASR run");
        f.controller.onFinal(id, "停止");
        check(f.commands.isEmpty(), "previous wake cannot turn narration into unprefixed-stop mode");
        f.controller.setNarrating(false);
        check(f.controller.getSessionId() == id, "narration end keeps microphone session");
        f.controller.onFinal(id, "描述屏幕");
        check(f.commands.isEmpty(), "narration end does not restore old armed window");
        f.controller.onFinal(id, "小助手描述屏幕");
        check(f.commands.size() == 1, "normal explicit command works again after narration");
        f.controller.setNarrating(true);
        f.controller.setNarrating(false);
        f.controller.onFinal(id, "小助手描述屏幕");
        check(f.commands.size() == 1, "narration state changes preserve duplicate-final suppression");
    }

    private static void stablePartialStopRunsOnceBeforeFinal() {
        Fixture f = new Fixture();
        long id = f.controller.start();
        f.controller.setNarrating(true);
        f.controller.onPartial(id, "小助手停止");
        f.now = 199;
        f.controller.onPartial(id, "小 助 手 停 止");
        check(f.commands.isEmpty(), "partial stop needs 200ms stability");
        f.now = 200;
        f.controller.onPartial(id, "小助手停止");
        check(f.commands.size() == 1 && f.commands.get(0).kind == ScreenCommand.Kind.STOP, "stable explicit partial stops before endpoint");
        f.controller.setNarrating(false);
        for (f.now = 300; f.now <= 3000; f.now += 100) f.controller.onPartial(id, "小助手停止");
        check(f.commands.size() == 1, "same utterance does not repeat during a long endpoint delay");
        f.controller.onFinal(id, "小助手停止");
        check(f.commands.size() == 1, "final of partial-executed stop is suppressed");
        f.now += 2000;
        f.controller.onFinal(id, "小助手停止");
        check(f.commands.size() == 2, "next deliberate stop works");
    }

    private static void changingOrStalePartialCannotStop() {
        Fixture f = new Fixture();
        f.controller.start();
        f.controller.onPartial("小助手停止");
        f.now = 150;
        f.controller.onPartial("小助手停止按钮在哪里");
        f.now = 500;
        f.controller.onPartial("小助手停止按钮在哪里");
        check(f.commands.isEmpty(), "extended question cancels partial stop candidate");
        f.controller.onPartial("小助手停止");
        f.now = 1200;
        f.controller.onPartial("小助手停止");
        check(f.commands.isEmpty(), "stale observations do not establish stability");
        f.now = 1399;
        f.controller.onPartial("小助手停止");
        check(f.commands.isEmpty(), "stale candidate restarted its timer");
        f.now = 1400;
        f.controller.onPartial("小助手停止");
        check(f.commands.size() == 1, "fresh stable observations can stop");
    }

    private static void partialCannotCaptureOrChangeRate() {
        for (String phrase : new String[] { "停止", "请小助手停止", "小助手停止按钮在哪里", "小助手描述屏幕",
                "小助手观察视频", "小助手读文字", "小助手开启暂停讲解", "小助手说快一点", "小助手关闭暂停讲解", "小助手今天很好" }) {
            Fixture f = new Fixture();
            f.controller.start();
            for (f.now = 0; f.now <= 1000; f.now += 100) f.controller.onPartial(phrase);
            check(f.commands.isEmpty() && f.disabled == 0 && f.wakes == 0, "partial never initiates unsupported operation: " + phrase);
        }
    }

    private static void partialDisableInvalidatesCallbacks() {
        Fixture f = new Fixture();
        long old = f.controller.start();
        f.controller.setNarrating(true);
        f.controller.onPartial(old, "小助手关闭语音监听");
        f.now = 200;
        f.controller.onPartial(old, "小助手关闭语音监听");
        check(f.disabled == 1 && !f.controller.isEnabled(), "stable explicit partial can disable microphone service");
        f.controller.onFinal(old, "小助手关闭语音监听");
        f.controller.onPartial(old, "小助手停止");
        check(f.disabled == 1 && f.commands.isEmpty(), "disable rejects later partial and final callbacks");
    }

    private static void partialStopRemainderCannotLaunchCapture() {
        Fixture f = new Fixture();
        f.controller.start();
        f.controller.onPartial("小助手停止");
        f.now = 200;
        f.controller.onPartial("小助手停止");
        f.now = 400;
        f.controller.onPartial("小助手停止按钮在哪里");
        f.controller.onFinal("小助手停止按钮在哪里");
        check(f.commands.size() == 1 && f.commands.get(0).kind == ScreenCommand.Kind.STOP, "extended final cannot launch question capture after partial stop");
    }

    private static void narrationAllowsExplicitRateAndPauseStop() {
        Fixture f = new Fixture();
        f.controller.start();
        f.controller.setNarrating(true);
        String[] commands = { "说快一点", "说慢一点", "正常语速", "关闭暂停讲解", "停止监控屏幕", "关闭图片自动描述" };
        ScreenCommand.Kind[] kinds = { ScreenCommand.Kind.FASTER, ScreenCommand.Kind.SLOWER,
                ScreenCommand.Kind.NORMAL_RATE, ScreenCommand.Kind.PAUSE_STOP,
                ScreenCommand.Kind.MONITOR_STOP, ScreenCommand.Kind.AUTO_IMAGE_STOP };
        for (int index = 0; index < commands.length; index++) {
            f.controller.onFinal(commands[index]);
            check(f.commands.size() == index, "unprefixed control rejected during narration");
            f.controller.onFinal("小助手" + commands[index]);
            check(f.commands.size() == index + 1 && f.commands.get(index).kind == kinds[index], "prefixed narration control accepted: " + commands[index]);
            f.now += 2000;
        }
        f.controller.onFinal("小助手开启暂停讲解");
        check(f.commands.size() == 6, "narration cannot start a new automatic capture mode");
        f.now += 2000;
        f.controller.onFinal("小助手开启图片自动描述");
        check(f.commands.size() == 6, "narration cannot start automatic image description");
    }

    private static void finalStartsPauseNarration() {
        Fixture f = new Fixture();
        f.controller.start();
        f.controller.onFinal("开启暂停讲解");
        check(f.commands.isEmpty(), "pause mode requires wake word");
        f.controller.onFinal("小助手开启暂停讲解");
        check(f.commands.size() == 1 && f.commands.get(0).kind == ScreenCommand.Kind.PAUSE_START,
                "explicit final routes pause narration mode start");
        check(VoiceCommandRouter.route("关闭暂停讲解").kind == ScreenCommand.Kind.PAUSE_STOP,
                "voice router recognizes pause narration shutdown");
        f.controller.onFinal("小助手开启暂停讲解");
        check(f.commands.size() == 1, "duplicate pause mode activation is suppressed");
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
