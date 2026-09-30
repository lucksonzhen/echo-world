package com.tingjian.assistant;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.net.Uri;
import android.provider.Settings;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Setup only. Day-to-day use remains over the app the user is browsing. */
public final class MainActivity extends Activity {
    private static final int GREEN = Color.rgb(29,89,71), INK = Color.rgb(27,53,45);
    private SettingsStore store;
    private AssistantApi api;
    private EditText url, token, model;
    private TextView urlLabel, tokenLabel, connectionHelp, connectionStatus;
    private DialPicker provider;
    private BottomDial dial;
    private Narrator dialNarrator;
    private String selectedProvider;
    private DialConsent consent;
    private TextView status;
    private BottomDial.Item checkConnectionButton, enableAssistantButton, enableVoiceButton, modelsButton;
    private boolean checkingConnection;
    private boolean guideOffered;
    private boolean resumed, pendingVoiceStart, pendingDialHelp;
    private final Handler main = new Handler();
    private TextView voiceStatus;
    private TextView playbackStatus;
    private TextView imageWatchStatus;
    private TextView accessibilityPermission, microphonePermission, playbackPermission, notificationPermission, batteryPermission;
    private static final int VOICE_PERMISSIONS = 32;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        guideOffered = state != null && state.getBoolean("guide_offered");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        store = new SettingsStore(this); api = new AssistantApi(this);
        dialNarrator=new Narrator(this,message->{ if(status!=null) status.setText(message); }); dialNarrator.setSpeechRate(1f);
        dial=new BottomDial(this,new BottomDial.Feedback() {
            public void stop() { dialNarrator.stop(); }
            public void speak(String text) { if(!resumed) return; if(AccessibilitySupport.hasScreenReader(MainActivity.this)) dial.announceForAccessibility(text); else dialNarrator.speak(text); }
        });
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(28),dp(24),dp(28),dp(32)); body.setBackgroundColor(Color.rgb(248,249,246)); scroll.addView(body);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom); return insets;
        });
        heading(body,"听见世界",30);
        text(body,"留在正在浏览的应用里，\n一句话，听懂眼前的画面。",20);
        text(body,"完成一次设置后，直接说“小助手，描述屏幕”。日常浏览无需找按钮，也不用下载图片或视频。",16);
        status = text(body,"",16); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        button(body,"逐项设置引导",this::openSetupGuide);
        text(body,"操作集中在底部拨轮：左右滑动切换，咔哒声后停稳听取，双击执行。开启读屏时可用双指横滑，或拨轮的上一项、下一项无障碍操作。底部备用拨轮默认隐藏，语音操作不依赖悬浮窗。",16);
        button(body,"显示底部备用拨轮",()->setOverlayVisible(true));
        button(body,"隐藏悬浮按钮，保留语音服务",()->setOverlayVisible(false));
        heading(body,"1. 手机直连模型 API",20);
        text(body,"手机自行截图、请求模型并朗读，无需电脑或同一 Wi-Fi。仍需联网，图片会发送到你选择的 API 服务商。",16);
        selectedProvider = store.getProvider();
        text(body,"模型接口配置",16);
        provider = new DialPicker(dial,text(body,"",16),"模型接口配置",
                new String[]{"自定义 OpenAI 兼容接口", "OpenAI 官方 API", "Gemini 官方 API", "DeepSeek 官方 API", "原有中转服务（可选）"});
        for (int i=0;i<DirectApiConfig.PROVIDERS.length;i++) if(DirectApiConfig.PROVIDERS[i].equals(selectedProvider)) provider.setSelection(i);
        urlLabel = text(body,"API 基础地址",16); url = input(body,"例如 https://服务商域名/v1",false);
        url.setContentDescription("API 基础地址"); url.setText(store.getServerUrl());
        text(body,"视觉模型名称",16); model = input(body,"填写支持图片输入的模型名称",false);
        model.setContentDescription("视觉模型名称"); model.setText(store.getModel());
        tokenLabel = text(body,"API Key",16); token = input(body,"填写你自己的 API Key",true); token.setContentDescription("API Key");
        token.setSaveEnabled(false);
        try { token.setText(store.getAccessToken()); } catch (Exception error) { report(error.getMessage()); }
        connectionHelp = text(body,"",14);
        modelsButton = button(body,"获取 Gemini 模型列表",this::fetchModels);
        updateConnectionFields();
        text(body,"密钥使用 Android Keystore 加密保存在本机，不写入安装包或日志。更换接口类型或地址会清空输入框，需重新输入密钥。API 调用可能产生服务商费用。",14);
        button(body,"清除本机密钥",() -> {
            cancelForConnectionChange(); store.clearCredential(); token.setText("");
            report("已清除本机 API 密钥和中转口令，并停止识别与语音待命。");
        });
        heading(body,"2. 允许按命令识别屏幕",20);
        text(body,"底部备用拨轮默认隐藏，可按需显示。发出描述命令时，会将当前压缩画面直接发送到上方选择的模型 API；自定义接口运营方也会收到画面和密钥。仅选择原有中转模式时才经中转服务。若另外开启暂停讲解，播放器暂停后也会发送一张当前画面。图片自动描述会在本机读取前台应用的控件类型、标签和位置以发现大图，并上传该图片区域。视频观察约 8 秒取 6 帧，不包含声音。截图和控件信息只在内存中处理，不保存到相册。最近描述暂存在内存供重听，锁屏、撤销同意或关闭服务后清除。",16);
        consent = new DialConsent(dial,text(body,"",16)); consent.setChecked(store.isConsentGranted());
        consent.onChanged(checked -> { if(!checked) { api.cancel(); setCheckingConnection(false); store.revokeConsent(); WakeWordService.stopListening(this); ScreenAssistantService.dispatchCommand("停止"); report("已关闭语音待命并暂停屏幕识别。"); } });
        button(body,"保存设置",this::save);
        text(body,"识图测试仅发送应用生成的红圆和蓝方块图片，不截取手机屏幕；会产生一次模型调用。",14);
        checkConnectionButton = button(body,"保存并测试识图连接",() -> {
            if (checkingConnection || !save()) return;
            setCheckingConnection(true); report("正在测试连接，直连模式将发送生成的小图…");
            api.check(new AssistantApi.Callback() {
                public void onSuccess(String message) { if (!message.contains("未配置")) store.markConnectionVerified(); setCheckingConnection(false); report(message); }
                public void onFailure(String message) { setCheckingConnection(false); report(message); }
            });
        });
        connectionStatus = text(body,"连接测试结果会显示在这里。",16);
        enableAssistantButton = button(body,"开启屏幕读取服务",() -> {
            if (checkingConnection) return;
            if (!consent.isChecked()) { report("请先阅读说明并在拨轮确认屏幕上传授权，再开启助手。"); return; }
            if (save()) openAccessibility();
        });
        heading(body,"权限与后台运行",20);
        text(body,"这里集中显示系统状态。从设置返回后会自动更新。通知使用权只用于可选的暂停讲解，电池设置也可稍后调整。",14);
        accessibilityPermission = text(body,"",16);
        button(body,"检查无障碍服务设置",this::openAccessibility);
        microphonePermission = text(body,"",16);
        button(body,"打开麦克风权限设置",this::openApplicationSettings);
        playbackPermission = text(body,"",16);
        button(body,"检查通知使用权",() -> openSystemSettings(MediaPauseMonitor.permissionIntent(this)));
        notificationPermission = text(body,"",16);
        button(body,"打开待命通知设置",() -> openSystemSettings(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())));
        batteryPermission = text(body,"",16);
        text(body,"若语音待命经常被系统关闭，可在电池优化列表中找到听见世界，选择不优化；部分手机还需在应用电池设置中允许后台运行。这可能增加耗电，不保证系统始终保留进程。",14);
        button(body,"打开电池优化设置",() -> openSystemSettings(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)));
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
            catch (RuntimeException error) { report("请到系统设置的通知使用权中开启听见世界播放状态。"); }
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
        text(body,"“小助手，描述屏幕”\n“小助手，读文字”\n“小助手，再说一遍”——重听最近一次描述，不重新上传\n“小助手，看看视频”\n“小助手，开启暂停讲解”\n“小助手，关闭暂停讲解”\n“小助手，开启图片自动描述”\n“小助手，关闭图片自动描述”\n“小助手，停止监控屏幕”——关闭图片自动描述和暂停讲解，保留语音待命\n“小助手，快一点”或“慢一点”\n“小助手，正常语速”\n“小助手，左边那张图片有什么？”\n“小助手，停止”——取消任务、朗读及所有自动描述\n“小助手，关闭语音监听”——关闭麦克风",16);
        text(body,"也可先说“小助手”，听到提示音后在八秒内说要求。默认语速为 1.35 倍，可用语音调节。朗读过程中先说“小助手，停止”，再提出新的描述要求；再说一遍、语速、关闭暂停讲解、关闭图片自动描述和停止监控屏幕指令可直接说。底部拨轮仅作备用入口。",16);
        text(body,"首次授权可通过 TalkBack 的读屏导航完成。若系统关闭服务或手机重启，需要重新打开本应用开启语音待命。外放视频中出现相同唤醒口令可能误触发，耳机通常能减少干扰，仍需在实际设备验证。",14);
        text(body,"仅能理解当前可见内容，不会自动滚动；受保护页面或锁屏无法截图。视频需要在观察期间继续播放。",14);
        button(body,"打开系统无障碍设置",this::openAccessibility);
        button(body,"朗读最近操作结果",()->speakFeedback(status.getText().toString()));
        setContentView(BottomDial.page(this,scroll,dial));
        provider.onChanged(position -> {
            String next=DirectApiConfig.PROVIDERS[position];
            if(next.equals(selectedProvider)) return;
            cancelForConnectionChange(); selectedProvider=next;
            token.setText(""); url.setText(DirectApiConfig.defaultUrl(next)); model.setText(DirectApiConfig.defaultModel(next));
            updateConnectionFields(); report("已切换接口，请输入该服务的密钥并重新确认屏幕识别说明。");
        });
        url.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            public void onTextChanged(CharSequence value, int start, int before, int count) {
                token.setText(""); cancelForConnectionChange();
            }
            public void afterTextChanged(Editable value) { }
        });
    }
    @Override protected void onResume() {
        super.onResume(); ScreenAssistantService.setAppControlsVisible(true);
        resumed = true; pendingDialHelp=true; dial.setActive(true);
        if (!guideOffered && SetupFlow.needsGuide(store.isConfigured(),store.isConsentGranted(),ScreenAssistantService.isConnected(),
                getSharedPreferences("setup_guide",MODE_PRIVATE).getBoolean("unfinished",false))) {
            openSetupGuide();
        }
        if (pendingVoiceStart) { pendingVoiceStart = false; startVoice(); }
        if (status != null && !checkingConnection) report(ScreenAssistantService.isConnected() ? "屏幕读取已开启。开启下方语音待命后，回到其他应用直接说指令。" : "请先在系统无障碍设置中启用听见世界助手。");
        main.post(refreshVoiceStatus);
    }
    @Override protected void onPause() { resumed = false; dial.setActive(false); main.removeCallbacks(refreshVoiceStatus); ScreenAssistantService.setAppControlsVisible(false); super.onPause(); }
    @Override protected void onDestroy() { main.removeCallbacksAndMessages(null); if (api != null) api.close(); if(dialNarrator!=null) dialNarrator.shutdown(); super.onDestroy(); }
    private void openSetupGuide() {
        guideOffered = true;
        api.cancel(); setCheckingConnection(false);
        startActivityForResult(new Intent(this,SetupGuideActivity.class),83);
    }
    private void setOverlayVisible(boolean visible) {
        store.setOverlayVisible(visible);
        ScreenAssistantService.dispatchCommand(visible ? "显示悬浮按钮" : "隐藏悬浮按钮");
        report(visible ? "底部备用拨轮已设为显示。无障碍服务连接后生效。" : "底部备用拨轮已隐藏，不影响已开启的语音服务。");
    }
    @Override protected void onSaveInstanceState(Bundle state) { state.putBoolean("guide_offered",guideOffered); super.onSaveInstanceState(state); }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if (request==83) { guideOffered=true; recreate(); }
    }
    private final Runnable refreshVoiceStatus = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            refreshPermissionStatus();
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
        try { store.saveConnection(selectedProvider,url.getText().toString(),model.getText().toString(),token.getText().toString(),consent.isChecked()); report("连接设置已保存。"); return true; }
        catch (Exception error) { report(error.getMessage() == null ? "无法保存设置。" : error.getMessage()); return false; }
    }
    private void setCheckingConnection(boolean checking) {
        checkingConnection = checking;
        provider.setEnabled(!checking); token.setEnabled(!checking); model.setEnabled(!checking && !"backend".equals(selectedProvider));
        url.setEnabled(!checking && ("compatible".equals(selectedProvider) || "backend".equals(selectedProvider)));
        if (checkConnectionButton != null) checkConnectionButton.setEnabled(!checking);
        if (modelsButton != null) modelsButton.setEnabled(!checking);
        if (enableAssistantButton != null) enableAssistantButton.setEnabled(!checking);
        if (enableVoiceButton != null) enableVoiceButton.setEnabled(!checking);
        // Consent remains editable so revocation can immediately stop collection.
    }
    private void refreshPermissionStatus() {
        setPermissionText(accessibilityPermission, "无障碍服务：" + (ScreenAssistantService.isConnected() ? "已连接，可以读取屏幕。" : "未连接，请在系统设置中检查。"));
        setPermissionText(microphonePermission, "麦克风权限：" + (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                ? "已允许；语音待命需单独开启，系统麦克风总开关也需打开。" : "未允许，语音待命不可用；可用读屏操作应用，或手动显示底部备用拨轮。"));
        setPermissionText(playbackPermission, "通知使用权：" + (MediaPauseMonitor.hasAccess(this)
                ? "已允许读取播放状态。" : "未允许；仅暂停讲解需要。"));
        NotificationManager notifications = getSystemService(NotificationManager.class);
        setPermissionText(notificationPermission, "待命通知：" + (notifications != null && notifications.areNotificationsEnabled()
                ? "应用通知已允许；若看不到待命通知，请检查通知类别设置。" : "应用通知已关闭，可在系统通知设置中开启。"));
        PowerManager power = getSystemService(PowerManager.class);
        setPermissionText(batteryPermission, "电池优化：" + (power == null ? "无法读取，请检查系统设置。"
                : power.isIgnoringBatteryOptimizations(getPackageName()) ? "已设为不优化。" : "正在优化，后台待命可能被系统暂停。"));
    }
    private void setPermissionText(TextView view, String value) {
        if (view != null && !value.contentEquals(view.getText())) view.setText(value);
    }
    private void updateConnectionFields() {
        boolean backend = "backend".equals(selectedProvider);
        if (modelsButton != null) modelsButton.setVisibility("gemini".equals(selectedProvider) ? View.VISIBLE : View.GONE);
        urlLabel.setText(backend ? "中转服务地址" : "API 基础地址");
        tokenLabel.setText(backend ? "访问口令（可选）" : "API Key");
        token.setContentDescription(backend ? "中转服务访问口令" : "API Key");
        token.setHint(backend ? "中转服务设置的口令" : "填写你自己的 API Key");
        connectionHelp.setText(backend ? "兼容旧版部署。此模式需要另行运行描述服务。"
                : "gemini".equals(selectedProvider) ? "Gemini 官方地址已填好。输入 Google AI Studio 的 API Key，可获取模型列表后选择。列表不保证图片识别可用，仍需测试小图。获取列表不上传图片、不调用生成接口。手机网络需能访问 Gemini API。"
                : "openai".equals(selectedProvider) ? "OpenAI 官方地址已填好，模型需支持图片输入。"
                : "deepseek".equals(selectedProvider) ? "DeepSeek 官方地址已填好。请输入 DeepSeek 平台的 API Key，默认视觉模型 deepseek-flash。采用非思考模式描述画面；不要填写仅支持文字的模型。请先保存并测试识图连接。"
                : "填写服务商的 HTTPS 基础地址（通常以 /v1 结尾），不要加 /chat/completions。接口需支持图片输入及 Chat Completions。密钥只发给这个地址。" );
        setCheckingConnection(checkingConnection);
    }
    private void cancelForConnectionChange() {
        api.cancel(); setCheckingConnection(false); store.revokeConsent();
        if (consent != null) consent.setChecked(false);
        WakeWordService.stopListening(this); ScreenAssistantService.dispatchCommand("停止");
    }
    private void fetchModels() {
        if (checkingConnection || !"gemini".equals(selectedProvider)) return;
        try {
            setCheckingConnection(true); report("正在获取 Gemini 模型列表，不会上传屏幕…");
            api.listGeminiModels(url.getText().toString().trim(), token.getText().toString().trim(), new AssistantApi.ModelsCallback() {
                public void onSuccess(java.util.List<String> models) {
                    setCheckingConnection(false);
                    report("已获取模型列表。选择后请保存并测试识图连接。");
                    dial.choose("视觉模型",models.toArray(new String[0]),0,which -> {
                        model.setText(models.get(which));
                        report("已选择 "+models.get(which)+"，请在拨轮选择保存并测试识图连接。");
                    });
                }
                public void onFailure(String message) { setCheckingConnection(false); report(message); }
            });
        } catch (IllegalArgumentException error) { setCheckingConnection(false); report(error.getMessage()); }
    }
    private void openAccessibility() {
        String help="进入系统无障碍设置后，找到已下载的应用或已安装的服务，开启听见世界助手。ColorOS 可先查看通用分类。手机自带读屏无需关闭。若提示受限设置，确认安装包来源后，到应用信息查看允许受限制的设置。请用底部拨轮选择前往设置、应用信息或返回。";
        report(help);
        dial.showMenu(new String[]{"前往无障碍设置","打开本应用信息"},new Runnable[]{()->openSystemSettings(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)),this::openApplicationSettings});
    }
    @Override public void onBackPressed() { if(!dial.closeMenu(true)) super.onBackPressed(); }

    private void openApplicationSettings() {
        openSystemSettings(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
    }
    private void openSystemSettings(Intent intent) {
        try { startActivity(intent); }
        catch (RuntimeException unavailable) {
            try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); }
            catch (RuntimeException fallbackUnavailable) { report("无法打开系统设置，请从手机设置中找到听见世界，检查对应权限或电池设置。"); }
        }
    }
    private void speakFeedback(String text) {
        if(!resumed) return;
        if(AccessibilitySupport.hasScreenReader(this)) dial.announceForAccessibility(text); else dialNarrator.speak(text);
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if(dial==null) return;
        if(!focused) dial.cancelSpeech();
        else if(resumed && pendingDialHelp) { pendingDialHelp=false; dial.announceUsage("听见世界设置"); }
    }
    private void report(String message) {
        if (status != null) status.setText(message);
        if (connectionStatus != null) connectionStatus.setText(message);
        if(resumed && dialNarrator!=null && !AccessibilitySupport.hasScreenReader(this)) dialNarrator.speak(message);
    }
    private TextView text(LinearLayout parent,String value,int sp) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(sp); view.setTextColor(INK); view.setLineSpacing(dp(3),1); view.setPadding(0,dp(8),0,dp(8)); parent.addView(view); return view;
    }
    private void heading(LinearLayout parent,String value,int size) {
        TextView view = text(parent,value,size); view.setTypeface(Typeface.create("sans-serif-medium",0)); view.setAccessibilityHeading(true); view.setPadding(0,dp(30),0,dp(10));
    }
    private EditText input(LinearLayout parent,String hint,boolean secret) {
        EditText field = new EditText(this); field.setTextSize(16); field.setSingleLine(true); field.setHint(hint); field.setMinHeight(dp(56)); field.setPadding(dp(12),dp(12),dp(12),dp(12));
        field.setInputType(secret ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        GradientDrawable background = new GradientDrawable(); background.setColor(Color.WHITE); background.setCornerRadius(dp(16)); background.setStroke(dp(1),Color.rgb(220,227,219)); field.setBackground(background);
        parent.addView(field,new LinearLayout.LayoutParams(-1,-2));
        dial.add("编辑："+hint,()->{ if(!field.isEnabled()) { report("本项无需编辑。"); return; } field.requestFocus(); field.requestRectangleOnScreen(new android.graphics.Rect(0,0,field.getWidth(),field.getHeight())); getSystemService(android.view.inputmethod.InputMethodManager.class).showSoftInput(field,0); });
        dial.add("粘贴："+hint,()->{
            if(!field.isEnabled()) { report("本项无需编辑。"); return; }
            android.content.ClipData clip=getSystemService(android.content.ClipboardManager.class).getPrimaryClip();
            CharSequence value=clip==null||clip.getItemCount()==0?null:clip.getItemAt(0).getText();
            if(value==null||value.length()>4096) { report("剪贴板没有合适的文本。"); return; }
            field.setText(value.toString().trim()); report(secret?"已粘贴密钥，不朗读内容。":"已粘贴本项，请核对后保存。");
        }); return field;
    }
    private BottomDial.Item button(LinearLayout parent,String label,Runnable action) { return dial.add(label,action); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
