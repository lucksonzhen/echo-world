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
    private DialPicker choices;
    private BottomDial dial;
    private static final String[] GUIDE_PROVIDERS={"gemini","deepseek","openai","compatible","backend"};
    private BottomDial.Item next, pause;
    private String provider, address, model, spoken = "";
    private boolean resumed, busy, paused, pendingVoice, returningSystem;
    private long request;
    private boolean standbyAfterGuide;
    private boolean screenReader;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        store = new SettingsStore(this); api = new AssistantApi(this);
        standbyAfterGuide=WakeWordService.isRunning();
        progress = getSharedPreferences("setup_guide", MODE_PRIVATE);
        if (standbyAfterGuide) progress.edit().putBoolean("standby_after_guide",true).apply();
        paused = progress.getBoolean("paused", false);
        progress.edit().remove("voice_enabled").apply();
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
        screenReader=AccessibilitySupport.hasScreenReader(this);
        if(dial!=null) dial.setActive(false);
        dial=new BottomDial(this,new BottomDial.Feedback() {
            public void stop() { narrator.stop(); main.removeCallbacks(announcement); }
            public void speak(String text) { SetupGuideActivity.this.speak(text,true); }
        });
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(28),dp(24),dp(28),dp(24));
        body.setBackgroundColor(Color.rgb(248,249,246)); scroll.addView(body);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom); return insets;
        });
        LinearLayout masthead=new LinearLayout(this); masthead.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView brand=new TextView(this); brand.setText("听见世界  /  设置"); brand.setTextSize(13); brand.setTextColor(Color.rgb(87,106,98)); brand.setLetterSpacing(.05f);
        masthead.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        TextView count=new TextView(this); count.setText(String.format(java.util.Locale.ROOT,"%02d / 12",step.ordinal()+1)); count.setTextSize(13); count.setTextColor(Color.rgb(87,106,98)); masthead.addView(count); body.addView(masthead);
        LinearLayout progressLine=new LinearLayout(this); LinearLayout.LayoutParams progressParams=new LinearLayout.LayoutParams(-1,dp(3)); progressParams.topMargin=dp(20); progressParams.bottomMargin=dp(44); body.addView(progressLine,progressParams);
        View filled=new View(this); filled.setBackgroundColor(Color.rgb(48,89,73)); progressLine.addView(filled,new LinearLayout.LayoutParams(0,-1,step.ordinal()+1));
        View remaining=new View(this); remaining.setBackgroundColor(Color.rgb(226,232,225)); progressLine.addView(remaining,new LinearLayout.LayoutParams(0,-1,12-step.ordinal()-1));
        TextView heading=label("",30); heading.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0)); heading.setAccessibilityHeading(true);
        instructions=label("",16); instructions.setTextColor(Color.rgb(85,105,95)); instructions.setLineSpacing(dp(5),1f);
        LinearLayout.LayoutParams noteParams=(LinearLayout.LayoutParams)instructions.getLayoutParams(); noteParams.bottomMargin=dp(28); instructions.setLayoutParams(noteParams);
        String title, detail, action = "完成本项，继续";
        switch (step) {
            case PROVIDER:
                title = "模型接口配置"; detail = "选项一，Gemini；选项二，DeepSeek；选项三，OpenAI；选项四，自定义兼容接口；选项五，原有中转服务。双击模型接口配置，左右滑动选择服务商，再双击确认。选好后选择完成本项，继续。默认选择 Gemini。";
                choices = new DialPicker(dial,selectionCard(),title,
                        new String[]{"Gemini 官方 API", "DeepSeek 官方 API", "OpenAI 官方 API", "自定义 OpenAI 兼容接口", "原有中转服务"});
                for (int i=0;i<GUIDE_PROVIDERS.length;i++) if (provider.equals(GUIDE_PROVIDERS[i])) choices.setSelection(i);
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
                detail = "先在服务商页面复制密钥，回到这里，在拨轮选择粘贴密钥并双击。只在你确认粘贴时读取剪贴板，不会读出密钥。"
                        + ("backend".equals(provider) ? "中转没有设置口令时可以留空。" : "此项不能为空。") + "也可用读屏器定位密码输入框并粘贴，完成后在拨轮选择完成本项，继续。";
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
                detail = "在底部拨轮选择前往开启屏幕读取服务，双击进入系统无障碍设置，找到已下载或已安装的应用，开启听见世界助手。一加 ColorOS 可先看通用分类。手机自带读屏无需关闭。若提示受限设置，可到听见世界应用信息查看允许受限制的设置。完成后返回这里，检测到服务已连接才继续。";
                button("打开本应用信息", () -> openSystem(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:"+getPackageName())))); break;
            case VOICE:
                title = "免触摸语音待命，可选"; action = "允许麦克风并开启语音待命";
                detail = "开启后，结束引导时会启动日常语音待命，可在其他应用说小助手，描述屏幕。声音只在本机处理，系统会显示麦克风标记。在拨轮选择允许麦克风并开启语音待命，或选择暂不开启，继续。首次麦克风授权需在系统弹窗中确认。"; break;
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
                detail = "在拨轮选择完成引导，返回应用。若已选择日常语音待命，准备完成后，在其他应用说小助手，描述屏幕。底部备用拨轮默认隐藏，可在主界面或通过语音显示。其他设置随时可调整。";
        }
        spoken = title + "。" + detail + " 所有操作都在屏幕底部拨轮，左右滑动切换，停稳听取，双击执行。";
        if (step==SetupFlow.Step.PROVIDER) spoken += " 如果使用 TalkBack，单指左右滑动听取选项，双击屏幕执行当前选项，双指滑动滚动。系统开关仍需通过系统界面操作。开启读屏的快捷方式因手机设置而异。";
        heading.setText(displayTitle());
        instructions.setText(displaySummary());
        feedback=label("",14); feedback.setTextColor(Color.rgb(100,114,105)); feedback.setMaxLines(4); feedback.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams feedbackParams=(LinearLayout.LayoutParams)feedback.getLayoutParams(); feedbackParams.topMargin=dp(20); feedback.setLayoutParams(feedbackParams);
        if (field!=null && field.isEnabled()) button(step==SetupFlow.Step.KEY ? "粘贴密钥，不朗读内容" : "从剪贴板粘贴本项",this::pasteField);
        next = button(action,this::completeCurrent); next.setEnabled(!busy);
        if (step == SetupFlow.Step.VOICE || step == SetupFlow.Step.PAUSE || step == SetupFlow.Step.IMAGES || step == SetupFlow.Step.BATTERY)
            button(step == SetupFlow.Step.BATTERY ? "暂不调整，继续" : "暂不开启，继续",this::skipCurrent);
        if (step!=SetupFlow.Step.PROVIDER) button("返回上一步",this::previousStep);
        button("重听当前步骤", () -> speak(spoken, true));
        pause = button(paused ? "继续语音引导" : "暂停语音引导", () -> {
            paused = !paused; progress.edit().putBoolean("paused",paused).apply(); narrator.stop(); main.removeCallbacks(announcement);
            pause.setText(paused ? "继续语音引导" : "暂停语音引导");
            if (!paused) speak(spoken, false);
        });
        button("中文语音设置", () -> openSystem(new Intent("com.android.settings.TTS_SETTINGS")));
        if (step != SetupFlow.Step.DONE) button("稍后配置，返回主界面",this::finish);
        setContentView(BottomDial.page(this,scroll,dial));
        if (resumed) speak(spoken,false);
    }

    private void completeCurrent() {
        if (busy) return;
        narrator.stop();
        try {
            switch (step) {
                case PROVIDER:
                    String chosen = GUIDE_PROVIDERS[choices.getSelectedItemPosition()];
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
        standbyAfterGuide=true;
        progress.edit().putBoolean("standby_after_guide",true).apply(); advance();
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
        request++; api.cancel(); busy=false;
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
        screenReader=AccessibilitySupport.hasScreenReader(this);
        if (screenReader) {
            narrator.stop(); instructions.announceForAccessibility(announcementText);
        } else {
            narrator.speak(announcementText);
        }
    };
    private void sayResult(String text) { feedback.setText(text); speak(text,true); }
    private void openSystem(Intent intent) {
        narrator.stop(); returningSystem=true;
        try { startActivity(intent); } catch (RuntimeException error) { returningSystem=false; sayResult("系统未提供此入口，请从手机设置中查找对应项目，完成后返回。"); }
    }
    @Override protected void onResume() {
        super.onResume(); ScreenAssistantService.setAppControlsVisible(true); resumed=true; visible=true; if(dial!=null) dial.setActive(true);
        screenReader=AccessibilitySupport.hasScreenReader(this);
        standbyAfterGuide=standbyAfterGuide || progress.getBoolean("standby_after_guide",false);
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
        resumed=false; visible=false; if(dial!=null) dial.setActive(false);
        main.removeCallbacksAndMessages(null); narrator.stop();
        // A response while backgrounded must not silently advance the wizard.
        if (busy && step==SetupFlow.Step.TEST) { request++; api.cancel(); busy=false; next.setEnabled(true); feedback.setText("测试已暂停，返回后可重新点击测试。"); }
        ScreenAssistantService.setAppControlsVisible(false); super.onPause();
    }
    @Override protected void onDestroy() { main.removeCallbacksAndMessages(null); api.close(); narrator.shutdown(); super.onDestroy(); }
    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if (!focus && narrator!=null) { narrator.stop(); main.removeCallbacks(announcement); } }
    private long busySince;
    private final Runnable observe = new Runnable() {
        public void run() {
            if (!resumed) return;
            if (step==SetupFlow.Step.ACCESSIBILITY && ScreenAssistantService.isConnected()) advance();
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
        TextView view=new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(Color.rgb(27,53,45)); view.setPadding(0,dp(8),0,dp(8)); body.addView(view); return view;
    }
    private BottomDial.Item button(String text,Runnable action) { return dial.add(text,action); }
    @Override public void onBackPressed() { if(!dial.closeMenu(true)) super.onBackPressed(); }
    private void input(String title,String value,boolean secret) {
        field=new EditText(this); field.setContentDescription(title); field.setHint(title); field.setTextSize(18); field.setSingleLine(true);
        field.setPadding(dp(20),dp(18),dp(20),dp(18)); field.setMinHeight(dp(76));
        android.graphics.drawable.GradientDrawable surface=new android.graphics.drawable.GradientDrawable(); surface.setColor(Color.WHITE); surface.setCornerRadius(dp(18)); surface.setStroke(dp(1),Color.rgb(220,227,219)); field.setBackground(surface);
        field.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_URI));
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO); field.setSaveEnabled(false); field.setText(value);
        field.setOnFocusChangeListener((view,focused)-> { if (focused) { narrator.stop(); main.removeCallbacks(announcement); } });
        body.addView(field,new LinearLayout.LayoutParams(-1,-2));
        EditText input=field;
        dial.add("编辑"+title,()->{ if(!input.isEnabled()) { sayResult("本项已预填，无需编辑。"); return; } input.requestFocus(); getSystemService(android.view.inputmethod.InputMethodManager.class).showSoftInput(input,0); });
        body.setFocusableInTouchMode(true); body.requestFocus();
    }

    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private TextView selectionCard() {
        LinearLayout card=new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(22),dp(22),dp(22),dp(22));
        android.graphics.drawable.GradientDrawable surface=new android.graphics.drawable.GradientDrawable(); surface.setColor(Color.WHITE); surface.setCornerRadius(dp(20)); surface.setStroke(dp(1),Color.rgb(226,232,225)); card.setBackground(surface);
        TextView caption=new TextView(this); caption.setText("当前选择"); caption.setTextSize(12); caption.setTextColor(Color.rgb(100,117,106)); card.addView(caption);
        TextView value=new TextView(this); value.setTextSize(23); value.setTextColor(Color.rgb(30,62,47)); value.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0)); value.setPadding(0,dp(14),0,dp(6)); card.addView(value);
        TextView hint=new TextView(this); hint.setText("在底部拨轮中选择与确认"); hint.setTextSize(13); hint.setTextColor(Color.rgb(105,119,110)); card.addView(hint);
        body.addView(card,new LinearLayout.LayoutParams(-1,-2)); return value;
    }
    private String displayTitle() {
        String[] titles={"选择模型服务","确认连接地址","选择视觉模型","添加你的密钥","测试连接","允许屏幕描述","连接屏幕服务","开启语音待命","暂停时讲解","自动描述图片","允许后台运行","准备好了"};
        return titles[step.ordinal()];
    }
    private String displaySummary() {
        switch(step) {
            case PROVIDER: return "选择你使用的 AI 服务。\n选好后，继续下一步。";
            case ADDRESS: return "compatible".equals(provider)||"backend".equals(provider)?"填写服务商提供的 HTTPS 地址。":"官方地址已为你填好，确认即可。";
            case MODEL: return "backend".equals(provider)?"模型由中转服务选择，无需填写。":"使用支持图片输入的模型。可以保留预填名称。";
            case KEY: return "从服务商复制密钥，再选择粘贴。\n密钥仅加密保存在本机，不会读出。";
            case TEST: return "发送一张生成的小图验证连接，不读取屏幕。可能产生一次 API 费用。";
            case CONSENT: return "描述时，屏幕画面会上传到你选择的模型服务商。视频取样不包含声音。只有同意后才启用；自动描述默认关闭。";
            case ACCESSIBILITY: return "在系统无障碍设置中开启“听见世界助手”，完成后返回。手机原有读屏可以保留。";
            case VOICE: return "说“小助手”，即可描述屏幕。\n语音只在本机识别，也可以稍后开启。";
            case PAUSE: return "视频暂停时，自动上传当前画面并讲解。\n这是可选功能，可以跳过。";
            case IMAGES: return "浏览时自动上传并描述较大的图片，可能增加 API 用量。也可以跳过。";
            case BATTERY: return "允许后台运行，让语音待命更稳定。\n这是可选设置，可能增加耗电。";
            default: return "回到正在浏览的应用，\n说“小助手，描述屏幕”。";
        }
    }

    private void pasteField() {
        if (field==null || !field.isEnabled()) { sayResult("当前项没有可粘贴的输入框。"); return; }
        android.content.ClipboardManager clipboard=getSystemService(android.content.ClipboardManager.class);
        android.content.ClipData clip=clipboard==null ? null : clipboard.getPrimaryClip();
        CharSequence text=clip==null || clip.getItemCount()==0 ? null : clip.getItemAt(0).getText();
        if (text==null || text.length()==0 || text.length()>4096) { sayResult("剪贴板没有合适的纯文本，请先复制本项内容。"); return; }
        field.setText(text.toString().trim());
        sayResult(step==SetupFlow.Step.KEY ? "已粘贴密钥，不朗读内容。确认来源正确后，在拨轮选择完成本项，继续。" : "已粘贴本项。可使用系统读屏核对，再选择完成本项，继续进行校验。");
    }
    private void skipCurrent() {
        if (step==SetupFlow.Step.VOICE) { standbyAfterGuide=false; progress.edit().putBoolean("standby_after_guide",false).apply(); }
        advance();
    }
    private void previousStep() {
        if (step==SetupFlow.Step.PROVIDER) { sayResult("已经是第一项。"); return; }
        go(SetupFlow.Step.values()[step.ordinal()-1]);
    }
    @Override public void finish() {
        if (standbyAfterGuide && store!=null && store.isConsentGranted() && ScreenAssistantService.isConnected()
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) {
            try { startForegroundService(new Intent(this,WakeWordService.class)); } catch (RuntimeException ignored) { }
        }
        if (progress!=null) progress.edit().putBoolean("standby_after_guide",false).apply();
        super.finish();
    }
}
