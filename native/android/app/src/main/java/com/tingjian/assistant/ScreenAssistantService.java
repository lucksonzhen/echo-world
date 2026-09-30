package com.tingjian.assistant;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Captures after an explicit command, an enabled and confirmed foreground playback pause, or, when
 * automatic image description is on, a prominent unlabeled picture found in the foreground window.
 * Node inspection happens only in that last mode, stays on this device, and is limited to class
 * names, labels and bounds; screenshots for it are cropped to the picture.
 */
public final class ScreenAssistantService extends AccessibilityService {
    private static final int VIDEO_FRAMES = 6;
    private static final long FRAME_INTERVAL_MS = 1500L;
    private static final long OVERLAY_HIDE_MS = 150L;
    private static final String MONITOR_STOPPED = "已停止监控屏幕。图片自动描述和暂停讲解已关闭，语音待命保留，随时可说小助手描述屏幕。";
    private static WeakReference<ScreenAssistantService> instance = new WeakReference<>(null);

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService encoder = Executors.newSingleThreadExecutor();
    private volatile boolean connected;
    private volatile long generation;
    private boolean destroyed;
    private boolean receiverRegistered;
    private String foregroundPackage = "";
    private String statusMessage = "先在语音待命设置中完成一次配置。开启后，可在其他应用中说出唤醒词和指令。";
    private String latestDescription = "";
    private AssistantOverlay overlay;
    private AssistantApi api;
    private SettingsStore settings;
    private Narrator narrator;
    private Session active;
    private MediaPauseMonitor pauseMonitor;
    private ImageWatchMonitor imageWatch;
    private boolean pauseEnabled;
    private boolean imageWatchEnabled;
    /** A pause-triggered narration is playing; playback or app changes cancel it. */
    private boolean automaticNarration;
    /** An image-triggered narration is playing; scrolling or app changes cancel it. */
    private boolean automaticImageNarration;
    private long ignorePauseUntil;

    private static final class Session {
        final long id;
        final boolean video;
        final String mode;
        final String question;
        final String packageName;
        /** Triggered by a confirmed playback pause. */
        final boolean automatic;
        /** Triggered by the image watch; {@link #crop} then bounds the picture. */
        final boolean autoImage;
        final Rect crop;
        final long startedAt = SystemClock.uptimeMillis();
        final List<ScreenFrame> frames = new ArrayList<>();
        long firstScreenshotAt = -1L;
        long imageHash;
        boolean submitted;

        Session(long id, boolean video, String mode, String question, String packageName, boolean automatic) {
            this(id, video, mode, question, packageName, automatic, null);
        }

        Session(long id, boolean video, String mode, String question, String packageName, boolean automatic, Rect crop) {
            this.id = id; this.video = video; this.mode = mode;
            this.question = question; this.packageName = packageName; this.automatic = automatic;
            this.autoImage = crop != null; this.crop = crop;
        }
    }

    private static volatile boolean appControlsVisible;
    public static boolean areAppControlsVisible() { return appControlsVisible; }
    public static void setAppControlsVisible(boolean visible) {
        appControlsVisible=visible;
        ScreenAssistantService service=instance.get();
        if(service!=null) service.main.post(()->{ if(service.overlay!=null) service.overlay.setAppVisible(appControlsVisible); });
    }
    public static boolean isConnected() {
        ScreenAssistantService service = instance.get();
        return service != null && service.connected;
    }

    public static boolean isNarrating() {
        ScreenAssistantService service = instance.get();
        return service != null && service.connected && service.narrator != null && service.narrator.isSpeaking();
    }

    /** Commands from accessible controls or the explicitly enabled offline wake-word service. */
    public static boolean dispatchCommand(String command) {
        ScreenAssistantService service = instance.get();
        if (service == null || !service.connected) return false;
        service.main.post(() -> { if (service.connected) service.handleCommand(command); });
        return true;
    }

    public static boolean speakStatus(String message) {
        ScreenAssistantService service = instance.get();
        if (service == null || !service.connected) return false;
        service.main.post(() -> { if (service.connected) service.report(message, true); });
        return true;
    }

    public static void onPlaybackAccessChanged() {
        ScreenAssistantService service = instance.get();
        if (service == null) return;
        service.main.post(() -> {
            if (!service.connected || !service.pauseEnabled) return;
            service.pauseMonitor.stop();
            if (MediaPauseMonitor.hasAccess(service)) {
                service.pauseMonitor.setTargetPackage(service.foregroundPackage);
                service.pauseMonitor.start();
            } else {
                service.setPauseMode(false);
                if (service.automaticNarration || (service.active != null && service.active.automatic)) service.cancelCurrent();
                service.setStatus("播放状态权限已关闭，仍可说小助手描述屏幕。");
                service.updatePanel();
            }
        });
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        if (connected || destroyed) return;
        settings = new SettingsStore(this);
        api = new AssistantApi(this);
        narrator = new Narrator(this, message -> {
            if (!connected) return;
            setStatus(message);
            if (overlay != null) overlay.announce(message);
        });
        narrator.setSpeechRate(settings.getSpeechRate());
        pauseMonitor = new MediaPauseMonitor(this, main, new MediaPauseMonitor.Listener() {
            @Override public void onPaused(String packageName) { describePausedFrame(packageName); }
            @Override public void onResumed(String packageName) {
                if (!foregroundPackage.equals(packageName)) return;
                if (automaticNarration || (active != null && active.automatic)) {
                    cancelCurrent();
                    setStatus("视频已恢复播放，自动讲解已停止。");
                }
            }
            @Override public void onUnavailable(String reason) {
                if (automaticNarration || (active != null && active.automatic)) cancelCurrent();
                if (pauseEnabled) setStatus(reason + " 仍可说小助手描述屏幕。");
            }
        });
        imageWatch = new ImageWatchMonitor(this, main, this::describeFoundImage);
        connected = true;
        instance = new WeakReference<>(this);
        if (settings.isPauseDescriptionEnabled() && settings.isConsentGranted() && MediaPauseMonitor.hasAccess(this)) setPauseMode(true);
        if (settings.isImageWatchEnabled() && settings.isConsentGranted()) setImageWatch(true);
        registerReceiver(screenReceiver, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        receiverRegistered = true;
        try {
            overlay = new AssistantOverlay(this, new AssistantOverlay.Listener() {
                @Override public void onCommand(String command) { handleCommand(command); }
                @Override public void onVoiceSettings() { openVoiceSettings(); }
                @Override public void onSpeak(String text) { report(text,true); }
                @Override public void onStopSpeaking() { if(narrator!=null) narrator.stop(); }
                @Override public void onClose() {
                    cancelCurrent();
                    overlay.announceClosed();
                    disableSelf();
                }
            });
            updatePanel();
            overlay.setAppVisible(appControlsVisible);
            overlay.show();
            report("屏幕描述服务已开启。请先打开语音待命设置，完成一次配置并开启语音待命，之后就能在其他应用中免触摸发出指令。", true);
        } catch (RuntimeException error) {
            report("底部备用拨轮暂不可用，仍可通过语音使用助手。", true);
        }
    }

    private void updatePanel() {
        if (connected && overlay != null) {
            overlay.render(active != null || (narrator != null && narrator.isSpeaking()), pauseEnabled, imageWatchEnabled, statusMessage, latestDescription);
        }
    }

    private void setStatus(String message) {
        statusMessage = message;
        if (overlay != null) overlay.setStatus(message);
    }

    private void report(String message, boolean speak) {
        setStatus(message);
        if (speak && narrator != null && !SetupGuideActivity.isVisible()) {
            ignorePauseUntil = SystemClock.uptimeMillis() + 2000L;
            narrator.speak(message, completed -> ignorePauseUntil = SystemClock.uptimeMillis() + 1000L);
        }
    }

    private void openVoiceSettings() {
        if (!connected) return;
        cancelCurrent();
        try {
            startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        } catch (RuntimeException error) {
            report("无法打开语音待命设置，请从桌面打开听见世界应用。", true);
        }
    }

    private void handleCommand(String text) {
        if (!connected) return;
        ScreenCommand command = ScreenCommand.parse(text == null ? "" : text);
        if (command.kind == ScreenCommand.Kind.OVERLAY_HIDE || command.kind == ScreenCommand.Kind.OVERLAY_SHOW) {
            boolean show=command.kind==ScreenCommand.Kind.OVERLAY_SHOW;
            settings.setOverlayVisible(show);
            try { if (overlay!=null) overlay.setEnabled(show); }
            catch (RuntimeException unavailable) { report("暂时无法显示底部备用拨轮，语音服务仍可使用。",true); return; }
            report(show ? "已开启底部备用拨轮，回到其他应用后显示。"+BottomDial.operationHint(this) : "已隐藏悬浮按钮，语音与屏幕读取服务继续运行。",true); return;
        }
        if (!settings.isConsentGranted()) latestDescription = "";
        if (command.kind == ScreenCommand.Kind.STOP) { stopFromUser("已停止。"); return; }
        if (command.kind == ScreenCommand.Kind.MONITOR_STOP) { stopFromUser(MONITOR_STOPPED); return; }
        if (command.kind == ScreenCommand.Kind.REPEAT) { repeatDescription(); return; }
        if (command.kind == ScreenCommand.Kind.PAUSE_STOP) {
            cancelCurrent();
            setPauseMode(false);
            updatePanel();
            report("暂停讲解已关闭。", true);
            return;
        }
        if (command.kind == ScreenCommand.Kind.AUTO_IMAGE_STOP) {
            cancelCurrent();
            setImageWatch(false);
            updatePanel();
            report("图片自动描述已关闭。", true);
            return;
        }
        if (command.kind == ScreenCommand.Kind.FASTER || command.kind == ScreenCommand.Kind.SLOWER
                || command.kind == ScreenCommand.Kind.NORMAL_RATE) { changeSpeechRate(command.kind); return; }
        if (command.kind == ScreenCommand.Kind.PAUSE_START && pauseEnabled) { report("暂停讲解已经开启。", true); return; }
        if (command.kind == ScreenCommand.Kind.AUTO_IMAGE_START && imageWatchEnabled) { report("图片自动描述已经开启。", true); return; }
        cancelCurrent();
        if (!settings.isConsentGranted()) {
            report("请先打开听见世界应用，阅读并同意按指令采集画面及上传识别的说明。", true);
            return;
        }
        if (!settings.isConfigured()) {
            report("请先在听见世界应用中配置模型 API 和密钥。", true);
            return;
        }
        if (screenUnavailable()) { report("屏幕已锁定或关闭，请解锁后再描述。", true); return; }
        if (command.kind == ScreenCommand.Kind.PAUSE_START) { startPauseMode(); return; }
        if (command.kind == ScreenCommand.Kind.AUTO_IMAGE_START) { startImageWatch(); return; }
        boolean video = command.kind == ScreenCommand.Kind.VIDEO;
        String mode = command.kind == ScreenCommand.Kind.READ_TEXT ? "text" : "detailed";
        String question = command.kind == ScreenCommand.Kind.QUESTION ? command.text : null;
        Session session = new Session(generation, video, mode, question, foregroundPackage, false);
        active = session;
        updatePanel();
        report(video ? "开始观察。请让视频继续播放，将采集约八秒内的六个画面，不录制声音。" : "正在读取当前屏幕。", true);
        capture(session);
    }

    /** Replays only the last successful result, without a screenshot or a network request. */
    private void repeatDescription() {
        cancelCurrent();
        if (screenUnavailable()) { report("屏幕已锁定或关闭，请解锁后再重听。", true); return; }
        if (latestDescription.isEmpty()) {
            report("还没有可重听的描述，请先描述一次屏幕。", true);
            return;
        }
        long replayGeneration = generation;
        setStatus("正在重听最近一次描述，不会重新识别当前画面。");
        ignorePauseUntil = SystemClock.uptimeMillis() + 2000L;
        narrator.speak("重播最近一次描述。" + latestDescription, completed -> {
            if (!connected || generation != replayGeneration) return;
            ignorePauseUntil = SystemClock.uptimeMillis() + 1000L;
            if (completed) setStatus("重听已完成。");
            updatePanel();
        });
        updatePanel();
    }

    private void changeSpeechRate(ScreenCommand.Kind command) {
        float delta = command == ScreenCommand.Kind.FASTER ? 0.15f : -0.15f;
        float normal = command == ScreenCommand.Kind.NORMAL_RATE ? 1f : settings.getSpeechRate() + delta;
        settings.setSpeechRate(normal);
        narrator.setSpeechRate(settings.getSpeechRate());
        report("讲解语速已调整。", true);
    }

    private void startPauseMode() {
        if (!MediaPauseMonitor.hasAccess(this)) {
            report("暂停讲解需要允许访问播放状态。系统把它放在通知使用权设置中；授权后，请再说开启暂停讲解。", true);
            try {
                startActivity(MediaPauseMonitor.permissionIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException error) { report("请打开听见世界设置，允许读取播放状态。", true); }
            return;
        }
        setPauseMode(true);
        report("暂停讲解已开启。等提示结束后，播放并暂停视频即可听取画面描述。", true);
        updatePanel();
    }

    private void setPauseMode(boolean enabled) {
        pauseEnabled = enabled;
        settings.setPauseDescriptionEnabled(enabled);
        pauseMonitor.stop();
        if (enabled) { pauseMonitor.setTargetPackage(foregroundPackage); pauseMonitor.start(); }
    }

    private void startImageWatch() {
        setImageWatch(true);
        report("图片自动描述已开启。浏览时遇到较大的图片会自动简短描述；说小助手停止监控屏幕即可关闭。", true);
        updatePanel();
        // Look at the page that is already open once the confirmation has been spoken.
        imageWatch.onScreenChanged(foregroundPackage);
    }

    private void setImageWatch(boolean enabled) {
        imageWatchEnabled = enabled;
        settings.setImageWatchEnabled(enabled);
        imageWatch.stop();
        if (enabled) { imageWatch.setTargetPackage(foregroundPackage); imageWatch.start(); }
    }

    private void describePausedFrame(String packageName) {
        if (!connected || !pauseEnabled || !foregroundPackage.equals(packageName)
                || !settings.isConsentGranted() || screenUnavailable() || active != null
                || narrator.isSpeaking() || SystemClock.uptimeMillis() < ignorePauseUntil
                || !pauseMonitor.isStillPaused(packageName)) return;
        if (!settings.isConfigured()) return;
        cancelCurrent();
        Session session = new Session(generation, false, "brief", null, packageName, true);
        active = session;
        setStatus("视频已暂停，正在理解停住的画面。");
        updatePanel();
        capture(session);
    }

    private void describeFoundImage(String packageName, Rect bounds, boolean motion) {
        if (!connected || !imageWatchEnabled || !foregroundPackage.equals(packageName)
                || !settings.isConsentGranted() || screenUnavailable()
                || !settings.isConfigured()) return;
        if (active != null || narrator.isSpeaking()) {
            // Busy with a request or speech; look again once the screen has been quiet.
            imageWatch.onScreenChanged(packageName);
            return;
        }
        cancelCurrent();
        Session session = new Session(generation, false, "brief", null, packageName, false, new Rect(bounds));
        active = session;
        imageWatch.onCaptureStarted(bounds, motion);
        setStatus(motion ? "发现实况图片，正在描述当前画面。" : "发现图片，正在自动描述。");
        updatePanel();
        capture(session);
    }

    private boolean current(Session session) {
        return connected && active == session && generation == session.id;
    }

    private boolean ready(Session session) {
        if (!current(session)) return false;
        if (session.automatic && (!pauseEnabled || !foregroundPackage.equals(session.packageName)
                || !pauseMonitor.isStillPaused(session.packageName))) {
            cancelCurrent();
            setStatus("视频状态已变化，本次自动讲解已取消。");
            return false;
        }
        if (session.autoImage && (!imageWatchEnabled || !foregroundPackage.equals(session.packageName))) {
            cancelCurrent();
            setStatus("页面已变化，本次图片自动描述已取消。");
            return false;
        }
        return true;
    }

    private boolean screenUnavailable() {
        KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        return keyguard == null || power == null || keyguard.isKeyguardLocked() || !power.isInteractive();
    }

    private void capture(Session session) {
        if (!ready(session)) return;
        if (!settings.isConsentGranted()) { fail(session, "授权已关闭，已停止采集画面。"); return; }
        if (screenUnavailable()) { fail(session, "屏幕已锁定或关闭，已停止采集。请解锁后重试。"); return; }
        setOverlayHidden(true);
        main.postDelayed(() -> {
            if (!ready(session)) return;
            if (!settings.isConsentGranted()) { fail(session, "授权已关闭，已停止采集画面。"); return; }
            if (screenUnavailable()) { fail(session, "屏幕已锁定或关闭，请解锁后重试。"); return; }
            Runnable timeout = () -> fail(session, "读取屏幕超时，请稍后再试。");
            main.postDelayed(timeout, session, 8000L);
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                    @Override public void onSuccess(ScreenshotResult screenshot) {
                        main.removeCallbacks(timeout);
                        if (!ready(session)) { ScreenshotEncoder.discard(screenshot); return; }
                        setOverlayHidden(false);
                        if (session.firstScreenshotAt < 0) session.firstScreenshotAt = screenshot.getTimestamp();
                        int timestamp = session.video ? (int) Math.max(0L, screenshot.getTimestamp() - session.firstScreenshotAt) : 0;
                        try {
                            encoder.execute(() -> encode(session, screenshot, timestamp));
                        } catch (RejectedExecutionException error) {
                            ScreenshotEncoder.discard(screenshot);
                            fail(session, "画面处理已停止，请重新开启服务。");
                        }
                    }
                    @Override public void onFailure(int errorCode) {
                        main.removeCallbacks(timeout);
                        if (current(session)) fail(session, screenshotFailure(errorCode));
                    }
                });
            } catch (RuntimeException error) {
                main.removeCallbacks(timeout);
                fail(session, "系统没有允许读取当前画面，请检查无障碍服务是否仍然开启。");
            }
        }, session, OVERLAY_HIDE_MS);
    }

    private void encode(Session session, ScreenshotResult screenshot, int timestamp) {
        if (!connected || generation != session.id) { ScreenshotEncoder.discard(screenshot); return; }
        try {
            ScreenshotEncoder.Encoded encoded = ScreenshotEncoder.encode(screenshot, session.crop);
            main.post(() -> {
                if (!current(session)) return;
                if (session.autoImage && !imageWatch.isNewImage(encoded.hash)) {
                    // The same picture is still on screen; do not upload or speak again.
                    cancelCurrent();
                    setStatus("图片没有变化，未重复描述。");
                    return;
                }
                session.imageHash = encoded.hash;
                session.frames.add(new ScreenFrame(encoded.dataUrl, timestamp));
                if (!session.video || session.frames.size() >= VIDEO_FRAMES) { submit(session); return; }
                setStatus("正在观察视频：已采集 " + session.frames.size() + "/" + VIDEO_FRAMES + " 个画面。语音待命开启时，可说小助手停止。");
                long delay = Math.max(0L, session.startedAt + session.frames.size() * FRAME_INTERVAL_MS - SystemClock.uptimeMillis());
                main.postDelayed(() -> capture(session), session, delay);
            });
        } catch (RuntimeException | OutOfMemoryError error) {
            main.post(() -> fail(session, "无法处理当前画面，请关闭不需要的应用后重试。"));
        }
    }

    private void submit(Session session) {
        if (!ready(session)) return;
        if (!settings.isConsentGranted()) { fail(session, "授权已关闭，画面没有上传。"); return; }
        session.submitted = true;
        setStatus(session.autoImage ? "正在理解图片。语音待命开启时，可说小助手停止。" : "正在理解画面。语音待命开启时，可说小助手停止。");
        int duration = session.video ? session.frames.get(session.frames.size() - 1).timestampMs + 1 : 0;
        api.describe(new ArrayList<>(session.frames), session.video, session.mode, session.question, duration,
                new AssistantApi.Callback() {
                    @Override public void onSuccess(String narration) {
                        if (!ready(session)) return;
                        if (!settings.isConsentGranted()) { cancelCurrent(); return; }
                        active = null;
                        session.frames.clear();
                        String speech = session.autoImage ? "屏幕上有一张图片。" + narration : narration;
                        latestDescription = speech;
                        automaticNarration = session.automatic;
                        automaticImageNarration = session.autoImage;
                        if (session.autoImage) imageWatch.onDescribed(session.imageHash);
                        setStatus("描述已完成。可说小助手再说一遍，或展开控制查看完整文字。");
                        narrator.speak(speech, completed -> {
                            if (connected && generation == session.id) {
                                automaticNarration = false; automaticImageNarration = false;
                                ignorePauseUntil = SystemClock.uptimeMillis() + 1000L;
                                updatePanel();
                            }
                        });
                        updatePanel();
                    }
                    @Override public void onFailure(String message) {
                        // Unattended image lookups fail quietly so a flaky network does not talk over the user.
                        if (session.autoImage && current(session)) { cancelCurrent(); setStatus(message); return; }
                        fail(session, message);
                    }
                });
    }

    private void setOverlayHidden(boolean hidden) {
        if (connected && overlay != null) overlay.setHidden(hidden);
    }

    private static String screenshotFailure(int errorCode) {
        // Secure-window has value 6 from API 34. Older Android versions may return internal-error instead.
        if (errorCode == 6) return "当前页面受应用保护，系统不允许截图，无法描述这个页面。";
        if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) return "操作太快，系统暂时限制截图。请稍等片刻后再试。";
        if (errorCode == ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS) return "无障碍截图权限已关闭，请重新启用听见世界服务。";
        if (errorCode == ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY) return "无法读取当前显示屏，请回到手机主屏幕后重试。";
        return "系统无法读取当前画面。页面可能受截图保护，请切换到允许截图的页面后重试。";
    }

    private void fail(Session session, String message) {
        if (!current(session)) return;
        boolean quiet = session.autoImage;
        cancelCurrent();
        // Screenshot problems on a page the user did not ask about are shown, not spoken.
        report(message, !quiet);
    }

    /** Stops every unattended capture mode and the current task; voice standby is untouched. */
    private void stopFromUser(String message) {
        cancelCurrent();
        setPauseMode(false);
        setImageWatch(false);
        updatePanel();
        report(message, true);
    }

    private void cancelCurrent() {
        automaticNarration = false;
        automaticImageNarration = false;
        generation++;
        Session previous = active;
        active = null;
        if (previous != null) {
            main.removeCallbacksAndMessages(previous);
            previous.frames.clear();
        }
        if (api != null) api.cancel();
        if (narrator != null) narrator.stop();
        setOverlayHidden(false);
        updatePanel();
    }

    private boolean imageNarrationOrCapture() {
        return automaticImageNarration || (active != null && active.autoImage);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!connected || event == null || event.getPackageName() == null) return;
        int type = event.getEventType();
        String packageName = event.getPackageName().toString();
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            // Only the watched foreground app matters; our own overlay and system windows are ignored.
            if (!imageWatchEnabled || foregroundPackage.isEmpty() || !foregroundPackage.equals(packageName)) return;
            if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED && imageNarrationOrCapture()) {
                cancelCurrent();
                setStatus("页面已滚动，自动描述已取消。");
            }
            if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) imageWatch.onNavigation(packageName);
            else imageWatch.onScreenChanged(packageName);
            return;
        }
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (getPackageName().equals(packageName)) {
            if (MainActivity.class.getName().contentEquals(event.getClassName() == null ? "" : event.getClassName())
                    || SetupGuideActivity.class.getName().contentEquals(event.getClassName() == null ? "" : event.getClassName())) {
                foregroundPackage = "";
                cancelCurrent();
                pauseMonitor.setTargetPackage("");
                imageWatch.setTargetPackage("");
            }
            return;
        }
        // Never mistake notification shade / permission-dialog pixels for the paused video or a picture.
        if ("com.android.systemui".equals(packageName) || "android".equals(packageName)) {
            foregroundPackage = "";
            if (automaticNarration || (active != null && active.automatic) || imageNarrationOrCapture()) cancelCurrent();
            pauseMonitor.setTargetPackage("");
            imageWatch.setTargetPackage("");
            return;
        }
        if (!foregroundPackage.equals(packageName) && (automaticNarration || automaticImageNarration)) {
            cancelCurrent();
        }
        foregroundPackage = packageName;
        pauseMonitor.setTargetPackage(packageName);
        imageWatch.setTargetPackage(packageName);
        Session session = active;
        if (session != null && !session.packageName.isEmpty()
                && !session.packageName.equals(packageName)) {
            fail(session, "前台应用已切换，已停止采集。请在想了解的页面重新发出指令。");
        }
        if (imageWatchEnabled) imageWatch.onNavigation(packageName);
    }

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                boolean wasActive = active != null;
                latestDescription = "";
                cancelCurrent();
                foregroundPackage = "";
                pauseMonitor.setTargetPackage("");
                imageWatch.setTargetPackage("");
                if (wasActive) report("屏幕已关闭，已停止处理。", true);
            }
        }
    };

    @Override public void onInterrupt() {
        cancelCurrent();
        setStatus("朗读已停止。");
    }

    @Override public boolean onUnbind(Intent intent) { tearDown(); return super.onUnbind(intent); }
    @Override public void onDestroy() { tearDown(); super.onDestroy(); }

    private void tearDown() {
        if (destroyed) return;
        destroyed = true;
        latestDescription = "";
        cancelCurrent();
        connected = false;
        if (instance.get() == this) instance.clear();
        WakeWordService.stopListening(this);
        if (pauseMonitor != null) pauseMonitor.stop();
        if (imageWatch != null) imageWatch.stop();
        main.removeCallbacksAndMessages(null);
        if (receiverRegistered) { unregisterReceiver(screenReceiver); receiverRegistered = false; }
        if (overlay != null) { overlay.close(); overlay = null; }
        if (narrator != null) narrator.shutdown();
        if (api != null) api.close();
        // Let already accepted encoding work run its finally block and close every hardware buffer.
        encoder.shutdown();
    }
}
