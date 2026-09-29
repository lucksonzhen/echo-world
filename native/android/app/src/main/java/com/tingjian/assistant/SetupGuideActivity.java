package com.tingjian.assistant;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.widget.*;

/** A single-field, user-paced setup surface. No credential enters saved UI state or speech. */
public final class SetupGuideActivity extends Activity {
    private static volatile boolean visible;
    public static boolean isVisible() { return visible; }
    private final Handler main = new Handler();
    private SettingsStore store;
    private SharedPreferences progress;
    private AssistantApi api;
    private Narrator narrator;
    private SetupFlow.Step step;
    private LinearLayout body;
    private TextView instructions, feedback;
    private EditText field;
    private Spinner choices;
    private Button next, pause;
    private String provider, address, model, spoken = "";
    private boolean resumed, busy, paused, pendingVoice, startingVoice, returningSystem;
    private long request;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        store = new SettingsStore(this); api = new AssistantApi(this);
        progress = getSharedPreferences("setup_guide", MODE_PRIVATE);
        paused = progress.getBoolean("paused", false);
        step = SetupFlow.restore(progress.getString("step", ""), store.isConfigured(), store.isConnectionVerified(),
                store.isConsentGranted(), ScreenAssistantService.isConnected());
        provider = progress.getString("provider", store.getProvider());
        address = progress.getString("address", DirectApiConfig.defaultUrl(provider));
        model = progress.getString("model", DirectApiConfig.defaultModel(provider));
        if (store.isConfigured()) { provider = store.getProvider(); address = store.getServerUrl(); model = store.getModel(); }
        progress.edit().putBoolean("unfinished", step != SetupFlow.Step.DONE).apply();
        narrator = new Narrator(this, message -> { if (feedback != null) feedback.setText(message + " 可使用系统读屏继续，或打开中文语音设置。"); });
        narrator.setSpeechRate(1.0f);
        render();
    }

    private void render() {
        narrator.stop(); field = null; choices = null;
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(32,24,32,32);
        body.setBackgroundColor(Color.rgb(246,247,242)); scroll.addView(body);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom); return insets;
        });
        label("语音配置引导", 26).setAccessibilityHeading(true);
        label("一次只配置一项，完成后继续。",16);
        instructions = label("",20);
        String title, detail, action = "完成本项，继续";
        switch (step) {
            case PROVIDER:
                title = "模型接口类型"; detail = "请选择你持有密钥的服务商。选好后点击完成本项，继续。默认是 Gemini，你也可以选择 DeepSeek。";
                choices = new Spinner(this); choices.setContentDescription(title);
                ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                        new String[]{"自定义 OpenAI 兼容接口", "OpenAI 官方 API", "Gemini 官方 API", "DeepSeek 官方 API", "原有中转服务"});
                choices.setAdapter(adapter); body.addView(choices);
                for (int i=0;i<DirectApiConfig.PROVIDERS.length;i++) if (provider.equals(DirectApiConfig.PROVIDERS[i])) choices.setSelection(i);
                break;
            case ADDRESS:
                title = "API 基础地址";
                boolean official = !"compatible".equals(provider) && !"backend".equals(provider);
                detail = official ? "官方地址已经填好，无需修改。确认后点击完成本项，继续。" : "请填写你信任的服务商提供的完整 HTTPS 基础地址。不要填聊天网页地址。填完后再继续。";
                input(title,address,false); field.setEnabled(!official); break;
            case MODEL:
                title = "视觉模型名称";
                detail = "backend".equals(provider) ? "中转模式由描述服务选择模型，这里无需填写。确认后继续。"
                        : "请确认支持图片输入的模型名称。官方接口已经预填。填完或确认后再继续。";
                input(title,model,false); field.setEnabled(!"backend".equals(provider)); break;
            case KEY:
                title = "backend".equals(provider) ? "中转访问口令" : "API Key 密钥";
                detail = "请粘贴所选服务商的密钥。密钥不会被本引导读出来，也不会明文保存。"
                        + ("backend".equals(provider) ? "中转没有设置口令时可以留空。" : "此项不能为空。") + "输入完成后点击完成本项，继续。";
                input(title,"",true); break;
            case TEST:
                title = "测试连接"; action = "保存并测试识图连接";
                detail = "backend".equals(provider) ? "点击测试中转连接。此步骤只检查服务状态，实际识图仍需使用时验证。"
                        : "点击保存并测试识图连接。只发送应用生成的几何小图，不读取屏幕；可能产生一次 API 费用。测试成功才进入下一项，失败会留在这里。";
                break;
            case CONSENT:
                title = "允许屏幕描述"; action = "我了解并同意，继续";
                detail = "发出描述命令后，当前压缩画面会发送到已选择的模型服务商；中转模式会经你的描述服务。视频观察约八秒取六帧，不含声音。截图不保存到相册，最近描述暂存在内存。可选的暂停讲解和图片自动描述会在触发时上传画面，默认关闭。只有你同意后才启用屏幕描述。不同意可点稍后配置。";
                break;
            case ACCESSIBILITY:
                title = "无障碍屏幕读取服务"; action = "前往开启屏幕读取服务";
                detail = "点击下方按钮进入系统无障碍设置，找到已下载或已安装的应用，开启听见世界助手。一加 ColorOS 可先看通用分类。手机自带读屏无需关闭。若提示受限设置，可到听见世界应用信息查看允许受限制的设置。完成后返回这里，检测到服务已连接才继续。";
                button("打开本应用信息", () -> openSystem(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:"+getPackageName())))); break;
            case VOICE:
                title = "免触摸语音待命，可选"; action = "允许麦克风并开启语音待命";
                detail = "开启后，麦克风在本机持续识别唤醒词和指令，声音不上传或保存；系统会显示麦克风标记。点击开启并在系统弹窗中允许麦克风。也可选择暂不开启，之后用悬浮按钮。首次开启可能需要等待语音模型解压。"; break;
            case PAUSE:
                title = "暂停后讲解，可选"; action = "设置并开启暂停讲解";
                detail = "开启后，视频从播放变为暂停时会自动上传一张画面并朗读。需要通知使用权，只处理媒体播放状态，不读取通知正文。不需要可点暂不开启。授权后返回，再点击开启。"; break;
            case IMAGES:
                title = "图片自动描述，可选"; action = "开启图片自动描述";
                detail = "开启后，在其他应用遇到没有文字说明的大图时会自动上传图片区域并描述。为发现大图，会在本机读取前台控件类型、标签和位置，这些控件信息不上传。可能增加 API 用量。不需要可点暂不开启。"; break;
            case BATTERY:
                title = "后台电池设置，可选"; action = "打开电池优化设置";
                detail = "如果希望语音长期待命，可在系统电池优化列表找到听见世界，选择不优化。可能增加耗电，且不能保证后台一直运行。不同手机可能还需允许后台活动。可以选择暂不调整。"; break;
            default:
                title = "基础配置已完成"; action = "完成引导，返回应用";
                detail = "可以回到图片或视频应用使用悬浮按钮描述屏幕。若已开启语音待命，请等本地语音模型准备完成后再使用唤醒词。其他设置随时可在主界面调整。";
        }
        spoken = title + "。" + detail;
        instructions.setText(spoken);
        feedback = label("",16);
        next = button(action,this::completeCurrent); next.setEnabled(!busy);
        if (step == SetupFlow.Step.VOICE || step == SetupFlow.Step.PAUSE || step == SetupFlow.Step.IMAGES || step == SetupFlow.Step.BATTERY)
            button(step == SetupFlow.Step.BATTERY ? "暂不调整，继续" : "暂不开启，继续",this::advance);
        button("重听当前步骤", () -> speak(spoken, true));
        pause = button(paused ? "继续语音引导" : "暂停语音引导", () -> {
            paused = !paused; progress.edit().putBoolean("paused",paused).apply(); narrator.stop(); main.removeCallbacks(announcement);
            pause.setText(paused ? "继续语音引导" : "暂停语音引导");
            if (!paused) speak(spoken, false);
        });
        button("中文语音设置", () -> openSystem(new Intent("com.android.settings.TTS_SETTINGS")));
        if (step != SetupFlow.Step.DONE) button("稍后配置，返回主界面",this::finish);
        setContentView(scroll);
        if (resumed) speak(spoken,false);
    }

    private void completeCurrent() {
        if (busy) return;
        narrator.stop();
        try {
            switch (step) {
                case PROVIDER:
                    String chosen = DirectApiConfig.PROVIDERS[choices.getSelectedItemPosition()];
                    if (!provider.equals(chosen)) { provider = chosen; address = DirectApiConfig.defaultUrl(provider); model = DirectApiConfig.defaultModel(provider); }
                    persistDraft(); advance(); break;
                case ADDRESS:
                    address = DirectApiConfig.validateBaseUrl(provider,field.getText().toString(),false); persistDraft(); advance(); break;
                case MODEL:
                    model = DirectApiConfig.validateModel(provider,field.getText().toString()); persistDraft(); advance(); break;
                case KEY:
                    store.saveConnection(provider,address,model,field.getText().toString(),false);
                    field.setText(""); advance(); break;
                case TEST:
                    busy=true; next.setEnabled(false); final long id=++request;
                    sayResult("正在测试连接，请等待。成功后会继续，失败不会跳过本项。");
                    api.check(new AssistantApi.Callback() {
                        public void onSuccess(String message) {
                            if (id!=request) return; busy=false; next.setEnabled(true);
                            if (message.contains("未配置")) { sayResult(message); return; }
                            store.markConnectionVerified(); advance();
                        }
                        public void onFailure(String message) {
                            if (id!=request) return; busy=false; next.setEnabled(true); sayResult(message + " 请检查后重试；也可返回主界面修改连接设置。");
                        }
                    }); break;
                case CONSENT:
                    store.saveConnection(store.getProvider(),store.getServerUrl(),store.getModel(),store.getAccessToken(),true); advance(); break;
                case ACCESSIBILITY:
                    if (ScreenAssistantService.isConnected()) advance(); else openSystem(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); break;
                case VOICE:
                    if (!ScreenAssistantService.isConnected() || !store.isConsentGranted()) { go(SetupFlow.Step.ACCESSIBILITY); return; }
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(Build.VERSION.SDK_INT >= 33 ? new String[]{Manifest.permission.RECORD_AUDIO,Manifest.permission.POST_NOTIFICATIONS}
                                : new String[]{Manifest.permission.RECORD_AUDIO}, 81);
                    } else startVoice(); break;
                case PAUSE:
                    if (!MediaPauseMonitor.hasAccess(this)) { openSystem(MediaPauseMonitor.permissionIntent(this)); return; }
                    if (!ScreenAssistantService.dispatchCommand("开启暂停讲解")) { sayResult("屏幕读取服务未连接，请返回检查。"); return; }
                    // Dispatch is asynchronous. Polling observes actual state; it never assumes success.
                    busy=true; busySince=android.os.SystemClock.uptimeMillis(); next.setEnabled(false); break;
                case IMAGES:
                    if (!ScreenAssistantService.dispatchCommand("开启图片自动描述")) { sayResult("屏幕读取服务未连接，请返回检查。"); return; }
                    busy=true; busySince=android.os.SystemClock.uptimeMillis(); next.setEnabled(false); break;
                case BATTERY: openSystem(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); break;
                case DONE:
                    progress.edit().putBoolean("unfinished",false).remove("step").apply(); finish(); break;
            }
        } catch (Exception error) { sayResult(error instanceof IllegalArgumentException || error instanceof IllegalStateException ? error.getMessage() : "无法完成本项，请检查设置后重试。"); }
    }
    private void startVoice() {
        if (!resumed) { pendingVoice=true; return; }
        try { startForegroundService(new Intent(this,WakeWordService.class)); startingVoice=true; sayResult("已请求开启语音待命，正在准备。准备失败时可稍后重试。"); }
        catch (RuntimeException error) { sayResult("系统未允许启动麦克风，请检查权限和后台运行设置。"); }
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(code,permissions,results);
        if (code!=81) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) startVoice();
        else sayResult("麦克风尚未允许。本项不会自动完成，可以重试、在系统应用信息中授权，或选择暂不开启。");
    }
    private void persistDraft() { progress.edit().putString("provider",provider).putString("address",address).putString("model",model).apply(); }
    private void advance() { go(SetupFlow.after(step,true)); }
    private void go(SetupFlow.Step value) {
        request++; api.cancel(); busy=false; startingVoice=false;
        step=value; progress.edit().putString("step",step.name()).putBoolean("unfinished",step!=SetupFlow.Step.DONE).apply(); render();
    }
    private void speak(String text, boolean explicit) {
        main.removeCallbacks(announcement);
        if (!resumed || (paused && !explicit)) return;
        announcementText=text; main.postDelayed(announcement,450);
    }
    private String announcementText="";
    private final Runnable announcement = () -> {
        if (!resumed || !hasWindowFocus()) return;
        AccessibilityManager manager = getSystemService(AccessibilityManager.class);
        if (manager!=null && !manager.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_SPOKEN).isEmpty()) {
            narrator.stop(); instructions.announceForAccessibility(announcementText);
        } else narrator.speak(announcementText);
    };
    private void sayResult(String text) { feedback.setText(text); speak(text,false); }
    private void openSystem(Intent intent) {
        narrator.stop(); returningSystem=true;
        try { startActivity(intent); } catch (RuntimeException error) { returningSystem=false; sayResult("系统未提供此入口，请从手机设置中查找对应项目，完成后返回。"); }
    }
    @Override protected void onResume() {
        super.onResume(); resumed=true; visible=true;
        SetupFlow.Step restored=SetupFlow.restore(step.name(),store.isConfigured(),store.isConnectionVerified(),
                store.isConsentGranted(),ScreenAssistantService.isConnected());
        if (restored!=step) go(restored);
        if (pendingVoice) { pendingVoice=false; if (step==SetupFlow.Step.VOICE) startVoice(); }
        boolean returned=returningSystem; returningSystem=false;
        if (step==SetupFlow.Step.ACCESSIBILITY && ScreenAssistantService.isConnected()) advance();
        else if (returned && step==SetupFlow.Step.BATTERY && getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName())) advance();
        else speak(spoken,false);
        main.post(observe);
    }
    @Override protected void onPause() {
        resumed=false; visible=false; main.removeCallbacksAndMessages(null); narrator.stop();
        // A response while backgrounded must not silently advance the wizard.
        if (busy && step==SetupFlow.Step.TEST) { request++; api.cancel(); busy=false; next.setEnabled(true); feedback.setText("测试已暂停，返回后可重新点击测试。"); }
        super.onPause();
    }
    @Override protected void onDestroy() { main.removeCallbacksAndMessages(null); api.close(); narrator.shutdown(); super.onDestroy(); }
    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if (!focus && narrator!=null) { narrator.stop(); main.removeCallbacks(announcement); } }
    private long busySince;
    private final Runnable observe = new Runnable() {
        public void run() {
            if (!resumed) return;
            if (step==SetupFlow.Step.ACCESSIBILITY && ScreenAssistantService.isConnected()) advance();
            else if (step==SetupFlow.Step.VOICE && startingVoice && WakeWordService.isRunning()) advance();
            else if (step==SetupFlow.Step.PAUSE && busy && store.isPauseDescriptionEnabled()) advance();
            else if (step==SetupFlow.Step.IMAGES && busy && store.isImageWatchEnabled()) advance();
            else if (busy && (step==SetupFlow.Step.PAUSE || step==SetupFlow.Step.IMAGES)
                    && android.os.SystemClock.uptimeMillis()-busySince>5000) {
                busy=false; next.setEnabled(true); sayResult("尚未检测到开启成功，请检查屏幕读取服务和权限后重试，也可暂不开启。");
            }
            main.postDelayed(this,700);
        }
    };
    private TextView label(String text,int size) {
        TextView view=new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(Color.rgb(27,53,45)); view.setPadding(8,16,8,16); body.addView(view); return view;
    }
    private Button button(String text,Runnable action) {
        Button view=new Button(this); view.setText(text); view.setTextSize(18); view.setMinHeight((int)(56*getResources().getDisplayMetrics().density));
        body.addView(view,new LinearLayout.LayoutParams(-1,-2)); view.setOnClickListener(v->action.run()); return view;
    }
    private void input(String title,String value,boolean secret) {
        field=new EditText(this); field.setContentDescription(title); field.setHint(title); field.setTextSize(20); field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_URI));
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO); field.setSaveEnabled(false); field.setText(value);
        field.setOnFocusChangeListener((view,focused)-> { if (focused) { narrator.stop(); main.removeCallbacks(announcement); } });
        body.addView(field,new LinearLayout.LayoutParams(-1,-2));
        body.setFocusableInTouchMode(true); body.requestFocus();
    }
}
