package com.tingjian.pausefixture;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Separate local demo: real video playback and a real system MediaSession. */
public final class MainActivity extends Activity {
    private static final int INK = Color.rgb(25, 42, 59);
    private static final int MUTED = Color.rgb(84, 105, 119);
    private static final int ACCENT = Color.rgb(20, 98, 132);
    private static final int PAPER = Color.rgb(242, 246, 248);
    private static final long ACTIONS = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
            | PlaybackState.ACTION_STOP | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SEEK_TO;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService previewWorker = Executors.newSingleThreadExecutor();
    private MediaSession session;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private VideoView video;
    private ImageView poster;
    private MediaPlayer player;
    private TextView stateLabel, timeLabel;
    private Button playButton, replayButton;
    private SeekBar progress;
    private boolean prepared, playRequested, forcedBuffering, ended, dragging, focusHeld, destroyed;
    private boolean videoRendered, explicitSeekBeforeRender;
    private Bitmap seekPreview;
    private int currentState = PlaybackState.STATE_NONE;
    private int durationMs, lastPositionMs, restoredPositionMs, revision;
    private int publishedState = Integer.MIN_VALUE;
    private long commandGeneration;
    private long previewGeneration;
    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            updateProgress();
            // Do not re-publish PAUSED on timer ticks: only real transport changes
            // and completed seeks belong in the system session event stream.
            main.postDelayed(this, 250L);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        restoredPositionMs = savedInstanceState == null ? 0 : savedInstanceState.getInt("position", 0);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        getWindow().setStatusBarColor(PAPER);
        getWindow().setNavigationBarColor(PAPER);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build();
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(this::onAudioFocusChanged, main).build();
        buildUi();
        createSession();
        video.setAudioAttributes(attributes);
        // Manage focus here so a paused clip relinquishes it and never auto-resumes.
        video.setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE);
        video.setOnPreparedListener(this::onPrepared);
        video.setOnCompletionListener(ignored -> {
            playRequested = false; forcedBuffering = false; ended = true;
            lastPositionMs = durationMs;
            abandonFocus();
            setState(PlaybackState.STATE_STOPPED, true);
        });
        video.setOnInfoListener((ignored, what, extra) -> {
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                videoRendered = true;
                previewGeneration++;
                poster.setVisibility(View.GONE);
            } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START && playRequested) {
                setState(PlaybackState.STATE_BUFFERING, false);
            } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END && playRequested && !forcedBuffering) {
                setState(video.isPlaying() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_BUFFERING, false);
            }
            return false;
        });
        video.setOnErrorListener((ignored, what, extra) -> {
            prepared = false; playRequested = false;
            abandonFocus();
            setState(PlaybackState.STATE_ERROR, true);
            stateLabel.setText("视频加载失败，请重新打开演示");
            return true;
        });
        setState(PlaybackState.STATE_BUFFERING, true);
        video.setVideoURI(Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.demo_video));
        applyIntent(getIntent());
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(PAPER);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(22), dp(22), dp(22));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));
        TextView brand = text("听见世界  /  视频体验", 13, ACCENT);
        brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(brand);
        TextView heading = text("暂停，听见这一刻", 27, INK);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setAccessibilityHeading(true);
        add(root, heading, 10, 0);
        add(root, text("动态视频演示 · 暂停后听取画面描述", 12, MUTED), 8, 22);

        RatioFrame movie = new RatioFrame();
        movie.setBackground(round(Color.rgb(11, 22, 30), 16, 0));
        movie.setClipToOutline(true);
        video = new VideoView(this);
        video.setContentDescription("大雄兔动画视频。请使用下方播放、暂停和进度控件。");
        movie.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        // Some codecs do not display a seeked frame before their first start.
        // Keep the actual opening-frame poster until decoded video is visible.
        poster = new ImageView(this);
        poster.setImageResource(R.drawable.demo_poster);
        poster.setScaleType(ImageView.ScaleType.FIT_CENTER);
        poster.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        movie.addView(poster, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        root.addView(movie, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout info = new LinearLayout(this);
        info.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("大雄兔 · 动画片段", 17, INK);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        info.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        stateLabel = text("正在加载", 12, ACCENT);
        stateLabel.setGravity(Gravity.CENTER);
        stateLabel.setPadding(dp(12), dp(7), dp(12), dp(7));
        stateLabel.setBackground(round(Color.rgb(221, 236, 242), 20, 0));
        info.addView(stateLabel, new LinearLayout.LayoutParams(-2, -2));
        add(root, info, 16, 0);
        LinearLayout times = new LinearLayout(this);
        times.setGravity(Gravity.CENTER_VERTICAL);
        times.addView(text("片段进度", 12, MUTED), new LinearLayout.LayoutParams(0, -2, 1));
        timeLabel = text("00:00 / --:--", 13, MUTED);
        timeLabel.setTypeface(Typeface.MONOSPACE);
        timeLabel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        times.addView(timeLabel);
        add(root, times, 18, 0);
        progress = new SeekBar(this);
        progress.setContentDescription("视频进度");
        progress.setProgressTintList(ColorStateList.valueOf(ACCENT));
        progress.setThumbTintList(ColorStateList.valueOf(ACCENT));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(Color.rgb(205, 219, 227)));
        progress.setMinHeight(dp(48));
        progress.setPadding(dp(2), 0, dp(2), 0);
        progress.setEnabled(false);
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar seekBar) { dragging = true; }
            @Override public void onProgressChanged(SeekBar seekBar, int position, boolean fromUser) {
                if (!fromUser || !prepared) return;
                timeLabel.setText(formatTime(position) + " / " + formatTime(durationMs));
                if (!dragging) { commandGeneration++; seek(position); } // TalkBack or keyboard.
            }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                dragging = false;
                if (prepared) { commandGeneration++; seek(seekBar.getProgress()); }
            }
        });
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(48)));
        LinearLayout controls = new LinearLayout(this);
        playButton = button("播放视频", true);
        playButton.setOnClickListener(view -> { commandGeneration++; if (playRequested) pause(); else play(); });
        controls.addView(playButton, new LinearLayout.LayoutParams(0, dp(56), 1));
        replayButton = button("重播", false);
        replayButton.setOnClickListener(view -> { commandGeneration++; seek(0); play(); });
        LinearLayout.LayoutParams replayParams = new LinearLayout.LayoutParams(dp(88), dp(56));
        replayParams.leftMargin = dp(12);
        controls.addView(replayButton, replayParams);
        add(root, controls, 4, 22);
        LinearLayout explanation = new LinearLayout(this);
        explanation.setOrientation(LinearLayout.VERTICAL);
        explanation.setPadding(dp(18), dp(16), dp(18), dp(16));
        explanation.setBackground(round(Color.WHITE, 16, Color.rgb(225, 233, 237)));
        TextView helpTitle = text("让视频停一停，让画面说出来", 16, INK);
        helpTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        helpTitle.setAccessibilityHeading(true);
        explanation.addView(helpTitle);
        TextView help = text("播放后暂停，听取这一帧的描述。\n继续播放会停止讲解。", 14, MUTED);
        help.setLineSpacing(dp(5), 1f);
        add(explanation, help, 10, 0);
        TextView voiceHint = text("开启语音待命后，也可以说：\n“小助手，描述屏幕”。", 13, ACCENT);
        voiceHint.setLineSpacing(dp(4), 1f);
        add(explanation, voiceHint, 12, 0);
        root.addView(explanation, new LinearLayout.LayoutParams(-1, -2));
        TextView credit = text("Big Buck Bunny · © 2008 Blender Foundation\nCC BY 3.0 · 原片节选与转码", 10, MUTED);
        credit.setLineSpacing(dp(3), 1f);
        add(root, credit, 18, 0);
        TextView creditLink = text("素材与许可 ›", 12, ACCENT);
        creditLink.setMinHeight(dp(48));
        creditLink.setGravity(Gravity.CENTER_VERTICAL);
        creditLink.setOnClickListener(view -> showCredits());
        root.addView(creditLink, new LinearLayout.LayoutParams(-1, -2));
        setContentView(scroll);
    }

    private void showCredits() {
        TextView credits = text("Big Buck Bunny\n© 2008 Blender Foundation\n\n"
                + "原片项目：https://peach.blender.org/\n\n"
                + "许可：Creative Commons Attribution 3.0\nhttps://creativecommons.org/licenses/by/3.0/\n\n"
                + "演示截取原片 00:45–01:25，转码为 H.264/AAC。", 14, INK);
        credits.setPadding(dp(24), dp(16), dp(24), dp(8));
        credits.setAutoLinkMask(Linkify.WEB_URLS);
        Linkify.addLinks(credits, Linkify.WEB_URLS);
        credits.setMovementMethod(LinkMovementMethod.getInstance());
        new AlertDialog.Builder(this).setTitle("视频素材与许可").setView(credits)
                .setPositiveButton("关闭", null).show();
    }

    private void onPrepared(MediaPlayer readyPlayer) {
        player = readyPlayer; prepared = true;
        durationMs = Math.max(0, video.getDuration());
        player.setLooping(false);
        player.setOnSeekCompleteListener(ignored -> {
            if (destroyed) return;
            lastPositionMs = Math.max(0, video.getCurrentPosition());
            if (!videoRendered && explicitSeekBeforeRender) showSeekPreview(lastPositionMs);
            publishState(true); updateProgress();
        });
        progress.setMax(Math.max(1, durationMs));
        progress.setEnabled(true);
        publishMetadata();
        // Seek without starting playback; the opening-frame poster covers codecs
        // that defer displaying decoded frames until their first explicit start.
        video.seekTo(Math.min(Math.max(1, restoredPositionMs), Math.max(1, durationMs - 1)));
        restoredPositionMs = 0;
        if (playRequested) play();
        else if (forcedBuffering) setState(PlaybackState.STATE_BUFFERING, true);
        else setState(PlaybackState.STATE_PAUSED, true);
    }

    private void createSession() {
        if (session != null) session.release();
        session = new MediaSession(this, "MotionDemo-" + (++revision));
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { commandGeneration++; play(); }
            @Override public void onPause() { commandGeneration++; pause(); }
            @Override public void onStop() { commandGeneration++; stop(); }
            @Override public void onSeekTo(long position) { commandGeneration++; seek((int) Math.min(Integer.MAX_VALUE, position)); }
        }, main);
        publishMetadata(); publishedState = Integer.MIN_VALUE;
        session.setActive(true); publishState(true);
    }

    private void publishMetadata() {
        if (session == null) return;
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "big-buck-bunny-demo")
                .putString(MediaMetadata.METADATA_KEY_TITLE, "大雄兔 · 动画片段")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Blender Foundation")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs).build());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent); applyIntent(intent);
    }

    private void applyIntent(Intent intent) {
        if (intent == null) return;
        long generation = ++commandGeneration;
        if (intent.getBooleanExtra("replaceSession", false)) createSession();
        if (intent.hasExtra("state")) execute(intent.getStringExtra("state"), generation);
        if (intent.hasExtra("seekToMs")) seek(intent.getIntExtra("seekToMs", 0));
        int pauseAfter = intent.getIntExtra("pauseAfterMs", -1);
        int resumeAfter = intent.getIntExtra("resumeAfterMs", -1);
        if (pauseAfter >= 0) main.postDelayed(() -> { if (commandGeneration == generation) pause(); }, pauseAfter);
        if (resumeAfter >= 0) main.postDelayed(() -> { if (commandGeneration == generation) play(); }, resumeAfter);
    }

    private void execute(String command, long generation) {
        if (command == null) return;
        switch (command) {
            case "PLAYING": play(); break;
            case "PAUSED": pause(); break;
            case "BUFFERING":
                playRequested = false; forcedBuffering = true;
                pausePlayer(); abandonFocus(); setState(PlaybackState.STATE_BUFFERING, true); break;
            case "STOPPED": stop(); break;
            case "PAUSE_AND_RESUME_QUICK":
                pause();
                main.postDelayed(() -> { if (commandGeneration == generation) play(); }, 100L); break;
            default: stateLabel.setText("未知测试指令");
        }
    }

    private void play() {
        if (destroyed) return;
        forcedBuffering = false; playRequested = true;
        if (!prepared) { setState(PlaybackState.STATE_BUFFERING, false); return; }
        if (!focusHeld && audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            playRequested = false; setState(PlaybackState.STATE_PAUSED, false);
            stateLabel.setText("等待声音通道，请再点播放"); return;
        }
        focusHeld = true;
        if (ended || video.getCurrentPosition() >= durationMs - 100) { ended = false; video.seekTo(0); }
        video.start();
        setState(video.isPlaying() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_BUFFERING, true);
    }

    private void pause() {
        playRequested = false; forcedBuffering = false;
        pausePlayer(); abandonFocus(); setState(PlaybackState.STATE_PAUSED, true);
    }

    private void stop() {
        playRequested = false; forcedBuffering = false; ended = false;
        pausePlayer();
        if (prepared) video.seekTo(0);
        lastPositionMs = 0;
        abandonFocus(); setState(PlaybackState.STATE_STOPPED, true);
    }

    private void pausePlayer() {
        if (!prepared) return;
        // Reset VideoView's target state even if buffering currently returns false.
        video.pause();
        lastPositionMs = Math.max(0, video.getCurrentPosition());
    }

    private void seek(int position) {
        if (!videoRendered) explicitSeekBeforeRender = true;
        if (!prepared) { restoredPositionMs = Math.max(0, position); return; }
        ended = false;
        int target = Math.max(0, Math.min(position, Math.max(0, durationMs - 1)));
        lastPositionMs = target; video.seekTo(target);
        // The completion callback publishes the actual new position.
        timeLabel.setText(formatTime(target) + " / " + formatTime(durationMs));
    }

    private void showSeekPreview(int positionMs) {
        long generation = ++previewGeneration;
        // Some decoders leave their surface black until the first start. An
        // extracted frame keeps paused scrubbing accurate without starting audio.
        previewWorker.execute(() -> {
            Bitmap decoded = null;
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try (AssetFileDescriptor source = getResources().openRawResourceFd(R.raw.demo_video)) {
                retriever.setDataSource(source.getFileDescriptor(), source.getStartOffset(), source.getLength());
                decoded = retriever.getScaledFrameAtTime(positionMs * 1000L,
                        MediaMetadataRetriever.OPTION_PREVIOUS_SYNC, 1280, 720);
            } catch (Exception ignored) {
                // Keep the existing preview if this optional decode fails.
            } finally {
                try { retriever.release(); } catch (Exception ignored) { }
            }
            Bitmap result = decoded;
            main.post(() -> {
                if (destroyed || videoRendered || generation != previewGeneration) {
                    if (result != null) result.recycle();
                    return;
                }
                if (result == null) return;
                Bitmap previous = seekPreview;
                seekPreview = result;
                poster.setImageBitmap(result);
                if (previous != null) previous.recycle();
            });
        });
    }

    private void onAudioFocusChanged(int change) {
        if (destroyed) return;
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            commandGeneration++; pause();
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            setVolume(.2f);
        } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
            setVolume(1f);
            // Only an explicit play command may resume video.
        }
    }

    private void abandonFocus() {
        if (focusHeld) audioManager.abandonAudioFocusRequest(focusRequest);
        focusHeld = false;
        setVolume(1f);
    }

    private void setVolume(float volume) {
        if (player == null) return;
        try { player.setVolume(volume, volume); }
        catch (IllegalStateException ignored) {
            // VideoView can release its Surface/MediaPlayer before Activity teardown.
        }
    }

    private void setState(int state, boolean force) {
        currentState = state; publishState(force);
        stateLabel.setText(stateName(state)); stateLabel.setContentDescription(stateName(state));
        playButton.setText(playRequested ? "暂停，听讲解" : ended ? "重新播放" : "播放视频");
        playButton.setContentDescription(playRequested ? "暂停视频，听取画面描述" : "播放视频");
        playButton.setEnabled(prepared || state == PlaybackState.STATE_BUFFERING);
        replayButton.setEnabled(prepared);
        if (state == PlaybackState.STATE_PLAYING) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        updateProgress();
    }

    private void publishState(boolean force) {
        if (session == null || (!force && publishedState == currentState)) return;
        session.setPlaybackState(new PlaybackState.Builder().setActions(ACTIONS)
                .setState(currentState, position(), currentState == PlaybackState.STATE_PLAYING ? 1f : 0f,
                        SystemClock.elapsedRealtime()).build());
        publishedState = currentState;
    }

    private int position() {
        if (ended) return durationMs;
        if (prepared) lastPositionMs = Math.max(0, video.getCurrentPosition());
        return lastPositionMs;
    }

    private void updateProgress() {
        if (dragging || timeLabel == null) return;
        int position = position();
        timeLabel.setText(formatTime(position) + " / " + (durationMs > 0 ? formatTime(durationMs) : "--:--"));
        if (progress != null) {
            progress.setProgress(position);
            progress.setStateDescription(formatTime(position) + "，共 " + formatTime(durationMs));
        }
    }

    private String stateName(int state) {
        switch (state) {
            case PlaybackState.STATE_PLAYING: return "播放中";
            case PlaybackState.STATE_PAUSED: return "已暂停";
            case PlaybackState.STATE_BUFFERING: return "正在加载";
            case PlaybackState.STATE_STOPPED: return ended ? "播放完毕" : "已停止";
            case PlaybackState.STATE_ERROR: return "加载失败";
            default: return "准备中";
        }
    }

    @Override protected void onResume() {
        super.onResume(); main.removeCallbacks(progressTick); main.post(progressTick);
    }

    @Override protected void onPause() {
        commandGeneration++;
        if (playRequested) pause();
        main.removeCallbacks(progressTick); super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("position", position()); super.onSaveInstanceState(state);
    }

    @Override public void onDestroy() {
        destroyed = true; commandGeneration++; previewGeneration++;
        main.removeCallbacksAndMessages(null);
        previewWorker.shutdownNow();
        poster.setImageDrawable(null);
        if (seekPreview != null) { seekPreview.recycle(); seekPreview = null; }
        abandonFocus();
        if (video != null) video.stopPlayback();
        player = null; prepared = false;
        if (session != null) { session.release(); session = null; }
        super.onDestroy();
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setIncludeFontPadding(false);
        return view;
    }

    private Button button(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label); button.setTextSize(16); button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(primary ? Color.WHITE : INK);
        button.setBackground(round(primary ? INK : Color.WHITE, 14, primary ? 0 : Color.rgb(204, 219, 227)));
        button.setPadding(dp(10), 0, dp(10), 0); button.setMinHeight(dp(56)); button.setStateListAnimator(null);
        return button;
    }

    private GradientDrawable round(int color, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color); drawable.setCornerRadius(dp(radius));
        if (stroke != 0) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private void add(LinearLayout parent, View view, int marginTop, int marginBottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(marginTop); params.bottomMargin = dp(marginBottom); parent.addView(view, params);
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static String formatTime(int millis) {
        int seconds = Math.max(0, millis / 1000);
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    private final class RatioFrame extends FrameLayout {
        RatioFrame() { super(MainActivity.this); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(Math.round(width * 9f / 16f), MeasureSpec.EXACTLY));
        }
    }
}
