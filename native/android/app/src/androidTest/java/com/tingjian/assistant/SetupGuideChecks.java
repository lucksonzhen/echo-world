package com.tingjian.assistant;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicInteger;

/** Real Activity actions with a deterministic API transport. No live key, camera or microphone. */
final class SetupGuideChecks {
    private final Instrumentation runner;
    private final StringBuilder report;
    private SetupGuideActivity activity;
    private int passed;
    SetupGuideChecks(Instrumentation runner,StringBuilder report) { this.runner=runner; this.report=report; }
    private void check(boolean ok,String message) {
        if (!ok) throw new AssertionError(message);
        passed++; report.append("PASS ").append(message).append('\n');
    }
    private Object field(String name) {
        try { java.lang.reflect.Field field=SetupGuideActivity.class.getDeclaredField(name); field.setAccessible(true); return field.get(activity); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private void set(String name,Object value) {
        try { java.lang.reflect.Field field=SetupGuideActivity.class.getDeclaredField(name); field.setAccessible(true); field.set(activity,value); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private void ui(Runnable action) { runner.runOnMainSync(action); runner.waitForIdleSync(); }
    private void click(String text) { ui(()-> {
        BottomDial.Item found=((BottomDial)field("dial")).items().stream().filter(item->text.equals(item.getText())).findFirst().orElseThrow(()->new AssertionError("Missing dial action "+text)); found.performClick();
    }); }
    private void next() { ui(()->((BottomDial.Item)field("next")).performClick()); }
    private void open() {
        activity=(SetupGuideActivity)runner.startActivitySync(new Intent(runner.getTargetContext(),SetupGuideActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        runner.waitForIdleSync();
    }
    int run() throws Exception {
        android.content.SharedPreferences prefs=runner.getTargetContext().getSharedPreferences("setup_guide",0);
        prefs.edit().clear().putBoolean("paused",true).commit();
        SettingsStore settings=new SettingsStore(runner.getTargetContext());
        try {
            open();
            ui(()->check(field("step")==SetupFlow.Step.PROVIDER,"guide starts at provider for an unconfigured install"));
            ui(()->activity.handleVoice("小助手选择第二项"));
            ui(()->check(((DialPicker)field("choices")).getSelectedItemPosition()==1,"spoken option two selects DeepSeek without locating a button"));
            ui(()->activity.handleVoice("小助手选择第一项"));
            ui(()->activity.handleVoice("下一步"));
            ui(()->check(field("step")==SetupFlow.Step.PROVIDER,"unprefixed or unrelated speech cannot advance setup"));
            next();
            ui(()->check(field("step")==SetupFlow.Step.ADDRESS,"provider requires explicit confirmation"));
            next(); next();
            ui(()->check(field("step")==SetupFlow.Step.KEY,"confirmed presets lead to credential entry"));
            next();
            ui(()->check(field("step")==SetupFlow.Step.KEY && ((TextView)field("feedback")).getText().length()>0,"empty credential cannot advance"));
            ui(()->((EditText)field("field")).setText("guide-fixture-secret"));
            SystemClock.sleep(900);
            ui(()->check(field("step")==SetupFlow.Step.KEY && !((String)field("spoken")).contains("guide-fixture-secret"),"typing neither advances nor enters spoken guidance"));
            ui(()->check(!((EditText)field("field")).isSaveEnabled(),"credential excluded from saved view state"));
            ui(()->activity.finish()); open();
            ui(()->check(field("step")==SetupFlow.Step.KEY && ((EditText)field("field")).getText().length()==0,"resume keeps confirmed progress but discards unsaved key"));
            ui(()->runner.getTargetContext().getSystemService(android.content.ClipboardManager.class)
                    .setPrimaryClip(android.content.ClipData.newPlainText("fixture","guide-fixture-secret")));
            ui(()->activity.handleVoice("小助手粘贴密钥"));
            ui(()->check("guide-fixture-secret".contentEquals(((EditText)field("field")).getText())
                    && !((TextView)field("feedback")).getText().toString().contains("guide-fixture-secret"),"explicit voice paste fills key without speaking its value"));
            next();
            check(settings.isConfigured() && !settings.isConsentGranted(),"guide saves connection without granting screenshot consent");
            check(!runner.getTargetContext().getSharedPreferences("screen_assistant",0).getAll().toString().contains("guide-fixture-secret"),"guide credential stored encrypted");
            AtomicInteger calls=new AtomicInteger();
            AssistantApi fake=new AssistantApi(runner.getTargetContext(),url->new Fixture(url,calls.incrementAndGet()==1 ? 401 : 200));
            ui(()-> { ((AssistantApi)field("api")).close(); set("api",fake); });
            ui(()->activity.handleVoice("小助手测试连接"));
            check(calls.get()==0,"voice test request waits for a separate confirmation");
            ui(()->activity.handleVoice("小助手取消操作"));
            ui(()->activity.handleVoice("小助手确认操作"));
            check(calls.get()==0,"cancelled voice confirmation cannot send a request");
            next(); waitForIdleRequest();
            ui(()->check(field("step")==SetupFlow.Step.TEST && ((TextView)field("feedback")).getText().toString().contains("HTTP 401"),"failed test remains on test step with safe error"));
            check(!settings.isConnectionVerified(),"failed test cannot mark configuration verified");
            ui(()->activity.handleVoice("小助手测试连接"));
            ui(()->activity.handleVoice("小助手确认操作")); waitForIdleRequest();
            ui(()->check(field("step")==SetupFlow.Step.CONSENT,"successful test advances exactly one step"));
            check(calls.get()==2 && settings.isConnectionVerified() && !settings.isConsentGranted(),"only explicit tests send requests and consent remains separate");
            ui(()->activity.handleVoice("小助手下一步"));
            check(!settings.isConsentGranted(),"generic next command cannot grant screen upload consent");
            ui(()->activity.handleVoice("小助手同意上传"));
            check(!settings.isConsentGranted(),"spoken upload consent is explained before confirmation");
            ui(()->activity.handleVoice("小助手确认操作"));
            check(settings.isConsentGranted() && settings.isConnectionVerified(),"explicit consent saves without invalidating successful connection test");
            click("重听当前步骤");
            ui(()->check(field("step")==SetupFlow.Step.ACCESSIBILITY || field("step")==SetupFlow.Step.VOICE,"replay does not skip required permissions"));
            ui(()->activity.finish());
            settings.saveConnection(settings.getProvider(),settings.getServerUrl(),"another-model",settings.getAccessToken(),true);
            check(!settings.isConnectionVerified(),"changing model invalidates saved test result");
            settings.markConnectionVerified(); settings.clearCredential();
            check(!settings.isConnectionVerified(),"clearing credential invalidates saved test result");
            check(!settings.isOverlayVisible(),"overlay hidden by default");
            settings.setOverlayVisible(true); check(new SettingsStore(runner.getTargetContext()).isOverlayVisible(),"overlay opt-in survives settings reload");
            settings.setOverlayVisible(false); check(!settings.isOverlayVisible(),"overlay can be hidden independently of connection settings");
        } finally {
            if (activity!=null) ui(()->activity.finish());
            runner.getTargetContext().getSystemService(android.content.ClipboardManager.class).clearPrimaryClip();
            prefs.edit().clear().commit(); runner.getTargetContext().getSharedPreferences("screen_assistant",0).edit().clear().commit();
        }
        return passed;
    }
    private void waitForIdleRequest() {
        long end=SystemClock.uptimeMillis()+8000;
        while (SystemClock.uptimeMillis()<end) {
            final boolean[] busy={true}; ui(()->busy[0]=(Boolean)field("busy"));
            if (!busy[0]) return; SystemClock.sleep(50);
        }
        throw new AssertionError("guide test timeout");
    }
    private static final class Fixture extends HttpURLConnection {
        private final int code;
        Fixture(URL url,int code) { super(url); this.code=code; }
        public void connect(){} public void disconnect(){} public boolean usingProxy(){return false;}
        public int getResponseCode(){return code;}
        public java.io.OutputStream getOutputStream(){return new ByteArrayOutputStream();}
        public java.io.InputStream getErrorStream(){return new ByteArrayInputStream("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        public java.io.InputStream getInputStream(){
            String json="{\"title\":\"测试\",\"summary\":\"有几何图形\",\"details\":[],\"visibleText\":[],\"timeline\":[],\"uncertainties\":[],\"answer\":null}";
            try { return new ByteArrayInputStream(new org.json.JSONObject().put("candidates",new org.json.JSONArray().put(new org.json.JSONObject().put("finishReason","STOP")
                .put("content",new org.json.JSONObject().put("parts",new org.json.JSONArray().put(new org.json.JSONObject().put("text",json)))))).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
            catch (Exception e){throw new AssertionError(e);}
        }
    }
}
