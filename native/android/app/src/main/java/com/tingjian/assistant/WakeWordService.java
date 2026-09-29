package com.tingjian.assistant;

import android.Manifest;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.media.ToneGenerator;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.NoiseSuppressor;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Explicitly enabled foreground microphone service. PCM and recognition stay on this device. */
public final class WakeWordService extends Service {
    private static final String CHANNEL = "voice_standby";
    private static final String ACTION_STOP = "com.tingjian.assistant.STOP_LISTENING";
    private static final int NOTIFICATION_ID = 42;
    private static volatile boolean running;
    private static volatile String status = "语音待命尚未开启。";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean live;
    private volatile boolean silenced;
    private volatile AudioRecord recorder;
    private VoiceWakeController wake;
    private ToneGenerator tone;
    private long recognitionSession;

    public static boolean isRunning() { return running; }
    public static String getStatus() { return status; }
    public static void stopListening(Context context) { context.stopService(new Intent(context, WakeWordService.class)); }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "免触摸语音待命", NotificationManager.IMPORTANCE_LOW));
        try { tone = new ToneGenerator(AudioManager.STREAM_ACCESSIBILITY, 65); } catch (RuntimeException ignored) {}
        wake = new VoiceWakeController(new VoiceWakeController.Listener() {
            @Override public void onWake() { if (tone != null) tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120); }
            @Override public void onCommand(ScreenCommand command) {
                if (!live || !canHearCommands()) return;
                if (!ScreenAssistantService.dispatchCommand(command.text)) {
                    setStatus("屏幕读取服务已关闭，请打开应用重新启用。", false);
                    stopSelf();
                }
            }
            @Override public void onWakeTimeout() { ScreenAssistantService.speakStatus("没有收到指令，已回到语音待命。"); }
            @Override public void onDisableListening() {
                ScreenAssistantService.dispatchCommand("停止");
                ScreenAssistantService.speakStatus("语音待命已关闭。再次使用时请在应用里开启。");
                stopSelf();
            }
        }, SystemClock::elapsedRealtime);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            ScreenAssistantService.dispatchCommand("停止"); stopSelf(); return START_NOT_STICKY;
        }
        if (live) return START_NOT_STICKY;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            setStatus("请先允许麦克风权限，再开启语音待命。", true); stopSelf(); return START_NOT_STICKY;
        }
        try {
            // Must happen BEFORE model unpacking; this service is started only by a visible Activity.
            startForeground(NOTIFICATION_ID, notification("正在准备本地语音识别…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } catch (RuntimeException error) {
            setStatus("系统未允许启动麦克风，请回到应用重新开启语音待命。", true);
            stopSelf(); return START_NOT_STICKY;
        }
        if (!ScreenAssistantService.isConnected()) {
            setStatus("请先在系统无障碍设置中开启屏幕读取服务。", true); stopSelf(); return START_NOT_STICKY;
        }
        live = true; running = true; recognitionSession = wake.start();
        setStatus("正在准备本地语音识别，首次开启需要解压语音模型，可能要等一到几分钟。", true);
        worker.execute(this::listen);
        main.post(tick);
        return START_NOT_STICKY;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!live) return;
            wake.tick();
            main.postDelayed(this, 250);
        }
    };

    private void listen() {
        Model model = null;
        Recognizer recognizer = null;
        AudioRecord audio = null;
        AcousticEchoCanceler echoCanceler = null;
        NoiseSuppressor noiseSuppressor = null;
        try {
            model = new Model(OfflineModelStore.prepare(this).getAbsolutePath());
            if (!live) return;
            recognizer = new Recognizer(model, 16000f);
            int minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IllegalStateException();
            audio = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(16000)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                    .setBufferSizeInBytes(Math.max(minimum, 12800)).build();
            if (audio.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException();
            // Device implementations may omit these effects or reject enablement.
            // They reduce echo/noise where supported; they do not identify a speaker.
            try {
                if (AcousticEchoCanceler.isAvailable()) {
                    echoCanceler = AcousticEchoCanceler.create(audio.getAudioSessionId());
                    if (echoCanceler != null && echoCanceler.setEnabled(true) != AudioEffect.SUCCESS) {
                        releaseEffect(echoCanceler); echoCanceler = null;
                    }
                }
            } catch (RuntimeException unavailable) { releaseEffect(echoCanceler); echoCanceler = null; }
            try {
                if (NoiseSuppressor.isAvailable()) {
                    noiseSuppressor = NoiseSuppressor.create(audio.getAudioSessionId());
                    if (noiseSuppressor != null && noiseSuppressor.setEnabled(true) != AudioEffect.SUCCESS) {
                        releaseEffect(noiseSuppressor); noiseSuppressor = null;
                    }
                }
            } catch (RuntimeException unavailable) { releaseEffect(noiseSuppressor); noiseSuppressor = null; }
            recorder = audio;
            audio.registerAudioRecordingCallback(getMainExecutor(), recordingCallback);
            if (!live) return;
            audio.startRecording();
            if (audio.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IllegalStateException();
            final long session = recognitionSession;
            main.post(() -> { if (live) setStatus("语音待命已开启，可以回到正在浏览的应用。", true); });
            short[] buffer = new short[1600];
            boolean wasAvailable = true;
            boolean utteranceHadNarration = false;
            while (live && !Thread.currentThread().isInterrupted()) {
                int count = audio.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                if (!live) break;
                if (count < 0) throw new IllegalStateException();
                if (count == 0) continue;
                boolean available = canHearCommands();
                if (available != wasAvailable) {
                    recognizer.reset(); wasAvailable = available; utteranceHadNarration = false;
                    main.post(() -> {
                        if (!live) return;
                        wake.reset();
                        setStatus(available ? "语音待命已恢复。" : "麦克风不可用或屏幕已锁定，已暂停接收指令。", false);
                    });
                }
                if (!available) continue;
                utteranceHadNarration |= ScreenAssistantService.isNarrating();
                if (recognizer.acceptWaveForm(buffer, count)) {
                    String words = new JSONObject(recognizer.getResult()).optString("text", "");
                    final boolean overlappedNarration = utteranceHadNarration;
                    utteranceHadNarration = false;
                    main.post(() -> {
                        if (!live || !canHearCommands()) return;
                        // Only explicit stop/disable/rate controls remain active during our narration.
                        wake.setNarrating(overlappedNarration || ScreenAssistantService.isNarrating());
                        wake.onFinal(session, words);
                    });
                } else {
                    String partial = new JSONObject(recognizer.getPartialResult()).optString("partial", "");
                    final boolean overlappedNarration = utteranceHadNarration;
                    main.post(() -> {
                        if (!live || !canHearCommands()) return;
                        wake.setNarrating(overlappedNarration || ScreenAssistantService.isNarrating());
                        // Repeated observations establish a stable explicit stop.
                        // Partial text can never trigger a screenshot or AI request.
                        wake.onPartial(session, partial);
                    });
                }
            }
        } catch (Exception | LinkageError error) {
            main.post(() -> {
                if (!live) return;
                setStatus("本地语音识别未能启动或已中断，请回到应用重新开启，并检查麦克风权限。", true);
                stopSelf();
            });
        } finally {
            recorder = null;
            releaseEffect(echoCanceler);
            releaseEffect(noiseSuppressor);
            if (audio != null) {
                try { audio.unregisterAudioRecordingCallback(recordingCallback); audio.stop(); } catch (RuntimeException ignored) {}
                audio.release();
            }
            if (recognizer != null) recognizer.close();
            if (model != null) model.close();
        }
    }

    private static void releaseEffect(AudioEffect effect) {
        if (effect != null) try { effect.release(); } catch (RuntimeException ignored) {}
    }

    private final AudioManager.AudioRecordingCallback recordingCallback = new AudioManager.AudioRecordingCallback() {
        @Override public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configurations) {
            AudioRecord audio = recorder;
            if (!live || audio == null) return;
            AudioRecordingConfiguration current = audio.getActiveRecordingConfiguration();
            silenced = current != null && current.isClientSilenced();
        }
    };

    private boolean canHearCommands() {
        PowerManager power = getSystemService(PowerManager.class);
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        return !silenced && !SetupGuideActivity.isVisible() && power.isInteractive() && !keyguard.isKeyguardLocked();
    }

    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, WakeWordService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_assistant)
                .setContentTitle("听见世界 · 语音待命").setContentText(text)
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "关闭语音待命", stop).build()).build();
    }

    private void setStatus(String text, boolean speak) {
        status = text;
        if (running) getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(text));
        if (speak) ScreenAssistantService.speakStatus(text);
    }

    @Override public void onDestroy() {
        live = false; running = false;
        wake.stop(); main.removeCallbacksAndMessages(null);
        AudioRecord audio = recorder;
        if (audio != null) try { audio.stop(); } catch (RuntimeException ignored) {}
        worker.shutdown();
        if (tone != null) { tone.release(); tone = null; }
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (!status.contains("未能") && !status.contains("请先") && !status.contains("未允许")) status = "语音待命已关闭。";
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
