package com.tingjian.assistant;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Observes a PLAYING -> PAUSED transition from the exact foreground package's published
 * media session. It cannot determine who caused a pause; many video apps publish no session.
 * No audio capture, media controls, notification contents, or audio-level detection are used.
 */
public final class MediaPauseMonitor {
    public interface Listener {
        void onPaused(String packageName);
        void onResumed(String packageName);
        void onUnavailable(String reason);
    }

    private final Context context;
    private final Handler main;
    private final Listener listener;
    private final MediaSessionManager sessions;
    private final ComponentName component;
    private final List<Binding> bindings = new ArrayList<>();
    private final PlaybackPausePolicy policy = new PlaybackPausePolicy();
    private MediaSessionManager.OnActiveSessionsChangedListener sessionsListener;
    private String target = "";
    private String lastUnavailable = "";
    private boolean started;
    private boolean aggregatePlaying;
    private long generation;
    private long bindingGeneration;
    private Runnable pendingPause;

    public MediaPauseMonitor(Context context, Handler main, Listener listener) {
        if (main.getLooper() != Looper.getMainLooper()) throw new IllegalArgumentException("Use main Handler");
        if (listener == null) throw new IllegalArgumentException("Listener required");
        this.context = context.getApplicationContext();
        this.main = main;
        this.listener = listener;
        this.sessions = this.context.getSystemService(MediaSessionManager.class);
        this.component = component(this.context);
    }

    public static boolean hasAccess(Context context) {
        try {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            return manager != null && manager.isNotificationListenerAccessGranted(component(context));
        } catch (RuntimeException ignored) { return false; }
    }

    public static Intent permissionIntent(Context context) {
        Intent detail = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component(context).flattenToString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return detail.resolveActivity(context.getPackageManager()) != null ? detail
                : new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private static ComponentName component(Context context) {
        return new ComponentName(context, PlaybackAccessService.class);
    }

    public void setTargetPackage(String packageName) {
        String requested = packageName == null ? "" : packageName;
        runMain(() -> {
            String next = requested.equals(context.getPackageName()) ? "" : requested;
            if (target.equals(next)) return;
            target = next;
            detach();
            lastUnavailable = "";
            if (started) attach();
        });
    }

    public void start() {
        runMain(() -> {
            if (!started) { started = true; lastUnavailable = ""; }
            if (sessionsListener == null) attach();
            else refreshSessions();
            main.removeCallbacks(accessCheck);
            main.postDelayed(accessCheck, 1000);
        });
    }

    public void stop() {
        runMain(() -> {
            started = false;
            main.removeCallbacks(accessCheck);
            detach();
            lastUnavailable = "";
        });
    }

    /** Fresh, conservative check for callbacks/request results; must be called on the main thread. */
    public boolean isPaused() {
        return isStillPaused(target);
    }

    public boolean isStillPaused(String packageName) {
        if (Looper.myLooper() != main.getLooper() || !started || !hasAccess(context)
                || target.isEmpty() || !target.equals(packageName) || bindings.size() != 1) return false;
        Binding binding = bindings.get(0);
        if (!target.equals(binding.controller.getPackageName())) return false;
        try {
            // Session-list callbacks can still be queued when an API result arrives. Check the
            // system's current list too, so an old paused controller cannot validate a new video.
            Map<MediaSession.Token, MediaController> matching = new LinkedHashMap<>();
            List<MediaController> active = sessions.getActiveSessions(component);
            if (active == null) return false;
            for (MediaController controller : active) {
                if (controller != null && target.equals(controller.getPackageName()))
                    matching.put(controller.getSessionToken(), controller);
            }
            if (matching.size() != 1 || !matching.containsKey(binding.controller.getSessionToken())) return false;
            binding.state = binding.controller.getPlaybackState();
            binding.durationMs = duration(binding.controller.getMetadata());
            return effectiveState(binding) == PlaybackState.STATE_PAUSED;
        } catch (RuntimeException ignored) { return false; }
    }

    private void runMain(Runnable action) {
        if (Looper.myLooper() == main.getLooper()) action.run(); else main.post(action);
    }

    private void attach() {
        if (!started || sessionsListener != null) return;
        if (!hasAccess(context)) {
            unavailable("尚未授权播放状态访问；可在系统设置中开启，或直接说描述屏幕。");
            return;
        }
        if (target.isEmpty()) {
            unavailable("请返回正在浏览的视频应用，等待播放状态。");
            return;
        }
        final long epoch = generation;
        sessionsListener = controllers -> {
            if (started && epoch == generation) updateControllers(controllers);
        };
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsListener, component, main);
            updateControllers(sessions.getActiveSessions(component));
        } catch (RuntimeException error) {
            detach();
            unavailable("无法读取当前应用的播放状态；请检查系统授权，或直接说描述屏幕。");
        }
    }

    private void refreshSessions() {
        if (!started) return;
        if (!hasAccess(context)) {
            detach();
            unavailable("播放状态访问已关闭，自动暂停讲解已停止；仍可直接描述屏幕。");
            return;
        }
        if (sessionsListener == null) { attach(); return; }
        try { updateControllers(sessions.getActiveSessions(component)); }
        catch (RuntimeException error) {
            detach();
            unavailable("播放状态暂时不可用；仍可直接描述屏幕。");
        }
    }

    private final Runnable accessCheck = new Runnable() {
        @Override public void run() {
            if (!started) return;
            if (!hasAccess(context)) {
                if (sessionsListener != null || !bindings.isEmpty()) detach();
                unavailable("播放状态访问已关闭，自动暂停讲解已停止；仍可直接描述屏幕。");
            } else if (sessionsListener == null) attach();
            main.postDelayed(this, 1000);
        }
    };

    private void updateControllers(List<MediaController> controllers) {
        if (!started) return;
        if (!hasAccess(context)) { refreshSessions(); return; }
        Map<MediaSession.Token, MediaController> matching = new LinkedHashMap<>();
        if (controllers != null) for (MediaController controller : controllers) {
            if (controller != null && target.equals(controller.getPackageName()))
                matching.put(controller.getSessionToken(), controller);
        }
        boolean same = matching.size() == bindings.size();
        if (same) for (Binding binding : bindings) {
            if (!matching.containsKey(binding.controller.getSessionToken())) { same = false; break; }
        }
        if (!same) {
            boolean replacing = !bindings.isEmpty();
            detachBindings();
            if (replacing) unavailable("视频播放会话已切换，已取消之前的自动讲解。");
            long epoch = generation;
            long bindingEpoch = bindingGeneration;
            try {
                for (MediaController controller : matching.values()) {
                    Binding binding = new Binding(controller, epoch, bindingEpoch);
                    bindings.add(binding);
                    controller.registerCallback(binding.callback, main);
                    binding.state = controller.getPlaybackState();
                    binding.durationMs = duration(controller.getMetadata());
                }
            } catch (RuntimeException error) {
                detachBindings();
                unavailable("当前视频未提供可用的播放状态；请直接说描述屏幕。");
                return;
            }
        }
        evaluate();
    }

    private final class Binding {
        final MediaController controller;
        final MediaController.Callback callback;
        PlaybackState state;
        long durationMs = -1;
        Binding(MediaController controller, long epoch, long bindingEpoch) {
            this.controller = controller;
            callback = new MediaController.Callback() {
                private boolean current() {
                    return started && epoch == generation && bindingEpoch == bindingGeneration && bindings.contains(Binding.this);
                }
                @Override public void onPlaybackStateChanged(PlaybackState playbackState) {
                    if (!current()) return;
                    state = playbackState;
                    evaluate();
                }
                @Override public void onMetadataChanged(MediaMetadata metadata) {
                    if (!current()) return;
                    durationMs = duration(metadata);
                    // A new item may reuse a controller inside the same app. Invalidate work
                    // conservatively on metadata updates without inspecting its title/content.
                    cancelPending();
                    policy.reset();
                    unavailable("视频内容信息已更新，已取消之前的自动讲解；仍可直接描述屏幕。");
                    evaluate();
                }
                @Override public void onSessionDestroyed() {
                    if (!current()) return;
                    detachBindings();
                    unavailable("当前视频的播放会话已结束；仍可直接描述屏幕。");
                    main.post(() -> { if (started && epoch == generation) refreshSessions(); });
                }
            };
        }
    }

    private static long duration(MediaMetadata metadata) {
        // Read only duration to avoid treating end-of-video as a pause; no title/artwork/content.
        return metadata == null ? -1 : metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
    }

    private void evaluate() {
        if (!started) return;
        if (!hasAccess(context)) { refreshSessions(); return; }
        if (bindings.size() != 1) {
            cancelPending();
            policy.reset();
            boolean playing = false;
            for (Binding binding : bindings)
                if (binding.state != null && binding.state.getState() == PlaybackState.STATE_PLAYING) playing = true;
            if (playing && !aggregatePlaying) listener.onResumed(target);
            aggregatePlaying = playing;
            unavailable(bindings.isEmpty()
                    ? "当前应用未提供视频播放状态，无法自动识别暂停；请直接说描述屏幕。"
                    : "当前应用有多个播放会话，无法确定正在看的视频；请直接说描述屏幕。");
            return;
        }
        Binding binding = bindings.get(0);
        int state = effectiveState(binding);
        aggregatePlaying = state == PlaybackState.STATE_PLAYING;
        int action = policy.onState(policyState(state), SystemClock.uptimeMillis());
        if (action == PlaybackPausePolicy.RESUMED) {
            cancelPending();
            lastUnavailable = "";
            listener.onResumed(target);
        } else if (policy.isPending()) {
            lastUnavailable = "";
            if (pendingPause == null) schedulePause(binding);
        } else {
            cancelPending();
            if (state != PlaybackState.STATE_PLAYING && state != PlaybackState.STATE_PAUSED)
                unavailable("视频处于缓冲、停止或结束状态，暂不触发暂停讲解。");
        }
    }

    private static int effectiveState(Binding binding) {
        if (binding.state == null) return PlaybackState.STATE_NONE;
        int state = binding.state.getState();
        if (state == PlaybackState.STATE_PAUSED
                && PlaybackPausePolicy.isAtEnd(binding.durationMs, binding.state.getPosition()))
            return PlaybackState.STATE_STOPPED;
        return state;
    }

    private static PlaybackPausePolicy.State policyState(int state) {
        if (state == PlaybackState.STATE_PLAYING) return PlaybackPausePolicy.State.PLAYING;
        if (state == PlaybackState.STATE_PAUSED) return PlaybackPausePolicy.State.PAUSED;
        if (state == PlaybackState.STATE_BUFFERING || state == PlaybackState.STATE_CONNECTING)
            return PlaybackPausePolicy.State.BUFFERING;
        if (state == PlaybackState.STATE_STOPPED) return PlaybackPausePolicy.State.STOPPED;
        return PlaybackPausePolicy.State.OTHER;
    }

    private void schedulePause(Binding binding) {
        final long epoch = generation;
        final long bindingEpoch = bindingGeneration;
        pendingPause = () -> {
            pendingPause = null;
            if (!started || epoch != generation || bindingEpoch != bindingGeneration
                    || bindings.size() != 1 || bindings.get(0) != binding) return;
            if (!hasAccess(context)) { refreshSessions(); return; }
            try { binding.state = binding.controller.getPlaybackState(); }
            catch (RuntimeException error) { policy.reset(); unavailable("播放状态暂时不可用，请直接说描述屏幕。"); return; }
            int state = effectiveState(binding);
            int action = policy.onState(policyState(state), SystemClock.uptimeMillis());
            if (action == PlaybackPausePolicy.RESUMED) { listener.onResumed(target); return; }
            if (policy.poll(SystemClock.uptimeMillis()) == PlaybackPausePolicy.PAUSED) listener.onPaused(target);
            else if (policy.isPending()) schedulePause(binding);
        };
        main.postDelayed(pendingPause, Math.max(1, policy.remaining(SystemClock.uptimeMillis())));
    }

    private void unavailable(String reason) {
        if (reason.equals(lastUnavailable)) return;
        lastUnavailable = reason;
        listener.onUnavailable(reason);
    }
    private void cancelPending() {
        if (pendingPause != null) main.removeCallbacks(pendingPause);
        pendingPause = null;
    }
    private void detachBindings() {
        ++bindingGeneration;
        cancelPending();
        policy.reset();
        aggregatePlaying = false;
        for (Binding binding : bindings) {
            try { binding.controller.unregisterCallback(binding.callback); } catch (RuntimeException ignored) { }
        }
        bindings.clear();
    }
    private void detach() {
        ++generation;
        detachBindings();
        if (sessionsListener != null) {
            try { sessions.removeOnActiveSessionsChangedListener(sessionsListener); } catch (RuntimeException ignored) { }
            sessionsListener = null;
        }
    }

}
