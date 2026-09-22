package com.tingjian.assistant;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Setup only. Day-to-day use remains over the app the user is browsing. */
public final class MainActivity extends Activity {
    private static final int GREEN = Color.rgb(29,89,71), INK = Color.rgb(27,53,45);
    private SettingsStore store;
    private AssistantApi api;
    private EditText url, token;
    private CheckBox consent;
    private TextView status;
    private Button checkConnectionButton, enableAssistantButton, enableVoiceButton;
    private boolean checkingConnection;
    private boolean resumed, pendingVoiceStart;
    private final Handler main = new Handler();
    private TextView voiceStatus;
    private TextView playbackStatus;
    private TextView imageWatchStatus;
    private static final int VOICE_PERMISSIONS = 32;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        store = new SettingsStore(this); api = new AssistantApi(this);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24),dp(24),dp(24),dp(32)); body.setBackgroundColor(Color.rgb(246,247,242)); scroll.addView(body);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom); return insets;
        });
        heading(body,"听见屏幕",30);
        text(body,"留在正在浏览的应用里，\n一句话，听懂眼前的画面。",20);
        text(body,"完成一次设置后，直接说“小助手，描述屏幕”。日常浏览无需找按钮，也不用下载图片或视频。",16);
        status = text(body,"",16); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        heading(body,"1. 连接描述服务",20);
        text(body,"服务地址",16); url = input(body,"例如 https://your-server.example.com",false);
        url.setContentDescription("描述服务地址"); url.setText(store.getServerUrl());
        text(body,"访问口令（可选）",16); token = input(body,"服务提供方设置的口令",true); token.setContentDescription("描述服务访问口令");
        try { token.setText(store.getAccessToken()); } catch (Exception error) { report(error.getMessage()); }
        text(body,"正式使用请连接 HTTPS 服务。API 密钥只配置在服务器，手机无需填写。",14);
        heading(body,"2. 允许按命令识别屏幕",20);
        text(body,"开启后会出现悬浮按钮。发出描述命令时，会将当前压缩画面发送到上述服务及 AI 提供方。若另外开启暂停讲解，支持的播放器暂停后也会发送一张当前画面。若开启图片自动描述，会在本机读取前台应用的控件类型、标签和位置以发现大图，并只上传该图片区域。视频观察命令约 8 秒取 6 帧，不包含声音。截图和控件信息只在内存中处理，不保存到相册。",16);
        consent = new CheckBox(this); consent.setText("我了解并允许按上述方式发送当前屏幕进行描述"); consent.setTextSize(16); consent.setMinHeight(dp(56)); consent.setTextColor(INK);
        consent.setChecked(store.isConsentGranted()); body.addView(consent);
        consent.setOnCheckedChangeListener((button, checked) -> { if (!checked) { store.revokeConsent(); WakeWordService.stopListening(this); ScreenAssistantService.dispatchCommand("停止"); report("已关闭语音待命并暂停屏幕识别。"); } });
        checkConnectionButton = button(body,"保存并检查连接",() -> {
            if (checkingConnection || !save()) return;
            setCheckingConnection(true); report("正在检查服务连接…");
            api.check(new AssistantApi.Callback() {
                public void onSuccess(String message) { setCheckingConnection(false); report(message); }
                public void onFailure(String message) { setCheckingConnection(false); report(message); }
            });
        });
        enableAssistantButton = button(body,"开启屏幕读取服务",() -> {
            if (checkingConnection) return;
            if (!consent.isChecked()) { report("请先阅读并勾选屏幕识别说明，再开启助手。"); return; }
            if (save()) openAccessibility();
        });
        heading(body,"3. 开启免触摸语音待命",20);
        text(body,"开启后，麦克风持续在手机本地识别唤醒词和指令，声音不上传、不保存。系统会显示麦克风标记及持续通知。锁屏期间不处理指令；通话或其他录音可能使监听暂停。",16);
        voiceStatus = text(body,WakeWordService.getStatus(),16);
        voiceStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        enableVoiceButton = button(body,"允许麦克风并开启语音待命",this::requestVoiceStart);
        button(body,"关闭语音待命",() -> { pendingVoiceStart = false; WakeWordService.stopListening(this); ScreenAssistantService.dispatchCommand("停止"); });
        heading(body,"4. 暂停后讲解（可选）",20);
        text(body,"默认关闭。开启后，播放器明确从播放变为暂停时，简短描述当前停住的画面；继续播放就取消自动识别和朗读。不根据静音自动插话，也不录制视频声音。",16);
        text(body,"需要系统的“通知使用权”才能取得播放器状态。本应用仅处理媒体播放状态和时长，不读取或保存通知正文。部分应用不提供状态，只能用语音请求描述；系统也无法始终区分手动暂停和应用自行暂停。",14);
        playbackStatus = text(body,"",16);
        button(body,"允许读取播放状态",() -> {
            if (checkingConnection || !save() || !consent.isChecked()) return;
            try { startActivity(MediaPauseMonitor.permissionIntent(this)); }
            catch (RuntimeException error) { report("请到系统设置的通知使用权中开启听见屏幕播放状态。"); }
        });
        button(body,"开启暂停讲解",() -> {
            if (checkingConnection || !save() || !consent.isChecked()) return;
            if (!ScreenAssistantService.dispatchCommand("开启暂停讲解")) report("请先开启屏幕读取服务。");
        });
        button(body,"关闭暂停讲解",() -> {
            store.setPauseDescriptionEnabled(false);
            ScreenAssistantService.dispatchCommand("关闭暂停讲解");
        });
        heading(body,"5. 图片自动描述（可选）",20);
        text(body,"默认关闭。开启后，浏览其他应用时若页面上出现没有文字说明的较大图片，会在画面稳定约 1 秒后自动简短描述；同一张图不重复，两次描述至少间隔 4 秒，滚动或切换应用会取消未读完的内容。",16);
        text(body,"为了找到图片，应用会在本机读取前台页面的控件类型、标签和位置，这些信息不上传；上传的只有裁剪后的图片区域。已有完整文字说明的图片交由读屏器朗读，不再重复。部分应用不暴露图片控件，只能用语音请求描述。",14);
        imageWatchStatus = text(body,"",16);
        button(body,"开启图片自动描述",() -> {
            if (checkingConnection || !save() || !consent.isChecked()) return;
            if (!ScreenAssistantService.dispatchCommand("开启图片自动描述")) report("请先开启屏幕读取服务。");
        });
        button(body,"关闭图片自动描述",() -> {
            store.setImageWatchEnabled(false);
            ScreenAssistantService.dispatchCommand("关闭图片自动描述");
        });
        heading(body,"6. 回到正在浏览的应用，直接说",20);
        text(body,"“小助手，描述屏幕”\n“小助手，读文字”\n“小助手，看看视频”\n“小助手，开启暂停讲解”\n“小助手，关闭暂停讲解”\n“小助手，开启图片自动描述”\n“小助手，关闭图片自动描述”\n“小助手，停止监控屏幕”——关闭图片自动描述和暂停讲解，保留语音待命\n“小助手，快一点”或“慢一点”\n“小助手，正常语速”\n“小助手，左边那张图片有什么？”\n“小助手，停止”——取消任务、朗读及所有自动描述\n“小助手，关闭语音监听”——关闭麦克风",16);
        text(body,"也可先说“小助手”，听到提示音后在八秒内说要求。默认语速为 1.35 倍，可用语音调节。朗读过程中先说“小助手，停止”，再提出新的描述要求；语速、关闭暂停讲解、关闭图片自动描述和停止监控屏幕指令可直接说。悬浮按钮仅作备用入口。",16);
        text(body,"首次授权可通过 TalkBack 的读屏导航完成。若系统关闭服务或手机重启，需要重新打开本应用开启语音待命。外放视频中出现相同唤醒口令可能误触发，耳机通常能减少干扰，仍需在实际设备验证。",14);
        text(body,"仅能理解当前可见内容，不会自动滚动；受保护页面或锁屏无法截图。视频需要在观察期间继续播放。",14);
        button(body,"打开系统无障碍设置",this::openAccessibility);
        setContentView(scroll);
    }
    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (pendingVoiceStart) { pendingVoiceStart = false; startVoice(); }
        if (status != null && !checkingConnection) report(ScreenAssistantService.isConnected() ? "屏幕读取已开启。开启下方语音待命后，回到其他应用直接说指令。" : "请先在系统无障碍设置中启用听见屏幕助手。");
        main.post(refreshVoiceStatus);
    }
    @Override protected void onPause() { resumed = false; main.removeCallbacks(refreshVoiceStatus); super.onPause(); }
    @Override protected void onDestroy() { main.removeCallbacksAndMessages(null); if (api != null) api.close(); super.onDestroy(); }
    private final Runnable refreshVoiceStatus = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            String value = WakeWordService.getStatus();
            if (voiceStatus != null && !value.contentEquals(voiceStatus.getText())) voiceStatus.setText(value);
            String playback = MediaPauseMonitor.hasAccess(MainActivity.this)
                    ? (store.isPauseDescriptionEnabled() ? "暂停讲解已开启，等待视频先播放再暂停。" : "播放状态已授权。可说小助手开启暂停讲解。")
                    : "播放状态尚未授权；语音描述屏幕仍可使用。";
            if (playbackStatus != null && !playback.contentEquals(playbackStatus.getText())) playbackStatus.setText(playback);
            String imageWatch = store.isImageWatchEnabled() ? "图片自动描述已开启，浏览时遇到大图会自动简述。" : "图片自动描述已关闭；可说小助手开启图片自动描述。";
            if (imageWatchStatus != null && !imageWatch.contentEquals(imageWatchStatus.getText())) imageWatchStatus.setText(imageWatch);
            main.postDelayed(this, 700);
        }
    };
    private void requestVoiceStart() {
        if (checkingConnection || !save()) return;
        if (!consent.isChecked()) { report("请先同意按命令读取屏幕。"); return; }
        if (!ScreenAssistantService.isConnected()) { report("请先完成第二步，在系统设置中启用屏幕读取服务。"); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            String[] permissions = Build.VERSION.SDK_INT >= 33
                    ? new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}
                    : new String[]{Manifest.permission.RECORD_AUDIO};
            requestPermissions(permissions, VOICE_PERMISSIONS); return;
        }
        startVoice();
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != VOICE_PERMISSIONS) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            report("没有麦克风权限，语音待命未开启。可在系统应用权限中允许麦克风。"); return;
        }
        if (resumed) startVoice(); else pendingVoiceStart = true;
    }
    private void startVoice() {
        if (!resumed) { pendingVoiceStart = true; return; }
        if (!store.isConsentGranted() || !ScreenAssistantService.isConnected()) { report("请先开启屏幕读取服务并同意屏幕识别。"); return; }
        try { startForegroundService(new Intent(this, WakeWordService.class)); report("正在开启本地语音待命。"); }
        catch (RuntimeException error) { report("系统未允许启动麦克风，请检查权限，并在本页面重新开启。"); }
    }
    private boolean save() {
        try { store.save(url.getText().toString(),token.getText().toString(),consent.isChecked()); report("连接设置已保存。"); return true; }
        catch (Exception error) { report(error.getMessage() == null ? "无法保存设置。" : error.getMessage()); return false; }
    }
    private void setCheckingConnection(boolean checking) {
        checkingConnection = checking;
        url.setEnabled(!checking); token.setEnabled(!checking);
        if (checkConnectionButton != null) checkConnectionButton.setEnabled(!checking);
        if (enableAssistantButton != null) enableAssistantButton.setEnabled(!checking);
        if (enableVoiceButton != null) enableVoiceButton.setEnabled(!checking);
        // Consent remains editable so revocation can immediately stop collection.
    }
    private void openAccessibility() { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
    private void report(String message) { if (status != null) status.setText(message); }
    private TextView text(LinearLayout parent,String value,int sp) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(sp); view.setTextColor(INK); view.setLineSpacing(dp(5),1); view.setPadding(0,dp(8),0,dp(8)); parent.addView(view); return view;
    }
    private void heading(LinearLayout parent,String value,int size) {
        TextView view = text(parent,value,size); view.setTypeface(null,Typeface.BOLD); view.setAccessibilityHeading(true); view.setPadding(0,dp(22),0,dp(8));
    }
    private EditText input(LinearLayout parent,String hint,boolean secret) {
        EditText field = new EditText(this); field.setTextSize(16); field.setSingleLine(true); field.setHint(hint); field.setMinHeight(dp(56)); field.setPadding(dp(12),dp(12),dp(12),dp(12));
        field.setInputType(secret ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        GradientDrawable background = new GradientDrawable(); background.setColor(Color.WHITE); background.setCornerRadius(dp(10)); background.setStroke(dp(1),GREEN); field.setBackground(background);
        parent.addView(field,new LinearLayout.LayoutParams(-1,-2)); return field;
    }
    private Button button(LinearLayout parent,String label,Runnable action) {
        Button button = new Button(this); button.setText(label); button.setTextSize(17); button.setAllCaps(false); button.setTextColor(Color.WHITE); button.setMinHeight(dp(56)); button.setPadding(dp(12),dp(8),dp(12),dp(8));
        GradientDrawable background = new GradientDrawable(); background.setColor(GREEN); background.setCornerRadius(dp(12)); button.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1,-2); params.topMargin = dp(14); parent.addView(button,params); button.setOnClickListener(view -> action.run());
        return button;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
