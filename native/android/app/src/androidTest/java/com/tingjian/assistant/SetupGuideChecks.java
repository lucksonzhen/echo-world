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
        prefs.edit().clear().putBoolean("paused",true).putBoolean("voice_enabled",true).commit();
        SettingsStore settings=new SettingsStore(runner.getTargetContext());
        try {
            open();
            ui(()->check(field("step")==SetupFlow.Step.PROVIDER,"guide starts at provider for an unconfigured install"));
            check(!prefs.contains("voice_enabled") && !WakeWordService.isRunning(),"legacy setup voice preference is removed without starting microphone");
            ui(()-> {
                BottomDial dial=(BottomDial)field("dial");
                check(dial.items().stream().noneMatch(item->item.getText().contains("语音操作") || item.getText().contains("语音说一项")),"setup has no microphone command entry");
                check(!((String)field("spoken")).contains("小助手") && ((String)field("spoken")).contains("双击") && ((String)field("spoken")).contains("屏幕底部") && ((String)field("spoken")).contains("当前选项"),"setup narration explains dial actions instead of spoken commands");
                check(field("next")==null && dial.items().stream().noneMatch(item->item.getText().contains("完成本项")),"provider has no duplicate continue action");
                dial.performClick(); dial.move(1);
                check(((DialPicker)field("choices")).getSelectedItemPosition()==0 && field("step")==SetupFlow.Step.PROVIDER,"browsing provider menu preserves confirmed value without advancing");
                dial.closeMenu(true);
                check(field("step")==SetupFlow.Step.PROVIDER && "gemini".equals(field("provider")),"cancelling provider choice does not advance or change draft");
                dial.performClick(); dial.move(1); dial.performClick();
                check(field("step")==SetupFlow.Step.ADDRESS && "deepseek".equals(field("provider"))
                        && "deepseek".equals(prefs.getString("provider","")),"confirming provider commits draft and immediately advances one step");
                check(!dial.performClick() && field("step")==SetupFlow.Step.ADDRESS,"old dial cannot trigger another step after confirmation");
                check(((BottomDial)field("dial")).current().getText().equals("确认地址"),"address starts on its single confirmation action");
            });
            click("返回上一步");
            ui(()-> {
                BottomDial dial=(BottomDial)field("dial");
                check(((DialPicker)field("choices")).getSelectedItemPosition()==1,"returning preserves confirmed provider selection");
                dial.performClick(); dial.performClick();
                check(field("step")==SetupFlow.Step.ADDRESS,"confirming unchanged provider still advances exactly once");
            });
            ui(activity::finish); open();
            ui(()->check(field("step")==SetupFlow.Step.ADDRESS && "deepseek".equals(field("provider")),"reopening resumes after the confirmed provider"));
            click("返回上一步");
            ui(()-> {
                BottomDial dial=(BottomDial)field("dial"); dial.performClick(); dial.move(-1); dial.performClick();
                check(field("step")==SetupFlow.Step.ADDRESS && "gemini".equals(field("provider")),"changing provider on return updates presets and advances");
            });
            click("重听当前步骤");
            ui(()-> {
                String text=(String)field("announcementText");
                check(text.contains("当前API 地址是") && text.contains("generativelanguage，点，googleapis，点，com，斜杠，v1beta"),"address replay reads the actual host and path with punctuation");
                check(((BottomDial)field("dial")).getContentDescription().toString().contains("generativelanguage"),"address confirmation accessibility label includes its value");
                set("paused",false);
            });
            ui(()-> {
                ((EditText)field("field")).setText("https://user:private-fixture@example.com/v1?key=private-fixture");
            });
            click("重听当前步骤");
            ui(()-> {
                check(((String)field("announcementText")).contains("格式不正确") && !((String)field("announcementText")).contains("private-fixture"),"malformed URL with credentials is not echoed by narration");
                ((EditText)field("field")).setText(DirectApiConfig.defaultUrl("gemini"));
            });
            next();
            ui(()-> {
                check(field("step")==SetupFlow.Step.MODEL && ((String)field("announcementText")).contains("gemini，短横线，3，点，5，短横线，flash"),"model entry reads the actual preset before confirmation");
                ((EditText)field("field")).setText("gemini-3.5-pro");
                check(((BottomDial)field("dial")).getContentDescription().toString().contains("短横线，pro"),"model confirmation label immediately follows edited value");
            });
            SystemClock.sleep(1350);
            ui(()->check(((String)field("announcementText")).contains("短横线，pro") && !((String)field("announcementText")).contains("短横线，flash"),"typing pause announces the latest model without stale preset"));
            click("重听当前步骤");
            ui(()->check(((String)field("announcementText")).contains("短横线，pro"),"replay uses edited model rather than saved draft"));
            ui(()->check(((BottomDial)field("dial")).current()==field("next"),"replay leaves the dial ready to confirm the value just heard"));
            ui(()->runner.getTargetContext().getSystemService(android.content.ClipboardManager.class)
                    .setPrimaryClip(android.content.ClipData.newPlainText("fixture","gemini-3.5-flash")));
            click("从剪贴板粘贴本项");
            ui(()-> {
                check(((String)field("announcementText")).contains("gemini，短横线，3，点，5，短横线，flash")
                        && field("step")==SetupFlow.Step.MODEL,"paste reads model without confirming or advancing");
                check(((BottomDial)field("dial")).current()==field("next"),"paste returns to confirmation carrying the current value");
                ((EditText)field("field")).setText("");
            });
            click("重听当前步骤");
            ui(()->check(((String)field("announcementText")).contains("模型名称尚未填写"),"empty model is explicitly announced"));
            ui(()-> { ((EditText)field("field")).setText("gemini-3.5-flash"); set("paused",true); });
            ui(()->runner.getTargetContext().getSystemService(android.content.ClipboardManager.class)
                    .setPrimaryClip(android.content.ClipData.newPlainText("fixture","guide-fixture-secret")));
            next();
            ui(()-> {
                check(field("step")==SetupFlow.Step.KEY,"confirmed presets lead to credential entry");
                check(field("field")==null && field("keyPage")!=null && !((BottomDial)field("dial")).isShown(),"credential page has no input field or dial");
                check(((String)field("pendingCredential")).isEmpty() && !((BottomDial.Item)field("next")).isEnabled(),"entering credential step neither reads clipboard nor enables empty save");
                runner.getTargetContext().getSystemService(android.content.ClipboardManager.class).clearPrimaryClip();
            });
            ui(()->((KeyPastePage)field("keyPage")).performClick());
            ui(()->check(((String)field("pendingCredential")).isEmpty() && ((TextView)field("feedback")).getText().toString().contains("剪贴板")
                    && ((KeyPastePage)field("keyPage")).getContentDescription().toString().contains("粘贴"),"empty clipboard explains how to retry and stays at paste"));
            ui(()->runner.getTargetContext().getSystemService(android.content.ClipboardManager.class)
                    .setPrimaryClip(android.content.ClipData.newPlainText("fixture","   ")));
            ui(()->((KeyPastePage)field("keyPage")).performClick());
            ui(()->check(!((BottomDial.Item)field("next")).isEnabled(),"whitespace clipboard cannot enable credential save"));
            ui(()->runner.getTargetContext().getSystemService(android.content.ClipboardManager.class)
                    .setPrimaryClip(android.content.ClipData.newPlainText("fixture","  guide-fixture-secret  ")));
            ui(()->((KeyPastePage)field("keyPage")).performClick());
            ui(()-> {
                check("guide-fixture-secret".equals(field("pendingCredential")),"explicit paste trims clipboard credential");
                check(((KeyPastePage)field("keyPage")).getContentDescription().toString().contains("保存") && field("step")==SetupFlow.Step.KEY && !settings.isConfigured(),"paste changes whole-page action to save without saving or advancing");
                check(!((TextView)field("credentialStatus")).getText().toString().contains("guide-fixture-secret")
                    && !((TextView)field("feedback")).getText().toString().contains("guide-fixture-secret")
                    && !((String)field("announcementText")).contains("guide-fixture-secret"),"credential remains outside visible and spoken feedback");
                check(!((TextView)field("credentialStatus")).isSaveEnabled(),"credential status does not enter saved view state");
            });
            ui(()-> {
                activity.onBackPressed();
                check(((String)field("pendingCredential")).isEmpty() && field("step")==SetupFlow.Step.KEY,"back clears unsaved key and stays in paste stage");
                KeyPastePage page=(KeyPastePage)field("keyPage");
                long scrollTime=SystemClock.uptimeMillis();
                touch(page,scrollTime,android.view.MotionEvent.ACTION_DOWN,100,400);
                touch(page,scrollTime+20,android.view.MotionEvent.ACTION_MOVE,100,200);
                touch(page,scrollTime+40,android.view.MotionEvent.ACTION_UP,100,200);
                check(((String)field("pendingCredential")).isEmpty(),"scrolling the key page cannot paste a credential");
                page.setActive(false);
                check(!page.performClick(),"background key page rejects activation"); page.setActive(true);
                long now=scrollTime+1000;
                touch(page,now,android.view.MotionEvent.ACTION_DOWN,100,200);
                touch(page,now+30,android.view.MotionEvent.ACTION_UP,100,200);
                check(((String)field("pendingCredential")).isEmpty(),"single tap cannot paste or save key");
                touch(page,now+100,android.view.MotionEvent.ACTION_DOWN,100,200);
                touch(page,now+130,android.view.MotionEvent.ACTION_UP,100,200);
                check(!((String)field("pendingCredential")).isEmpty() && !settings.isConfigured() && field("step")==SetupFlow.Step.KEY,"physical double tap pastes once without saving in the same gesture");
            });
            ui(()->activity.finish()); open();
            ui(()->check(field("step")==SetupFlow.Step.KEY && ((String)field("pendingCredential")).isEmpty()
                    && ((KeyPastePage)field("keyPage")).getContentDescription().toString().contains("粘贴"),"reopened guide keeps progress and waits for explicit paste without restoring a secret"));
            ui(()->((KeyPastePage)field("keyPage")).performClick());
            ui(()->((KeyPastePage)field("keyPage")).performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK,null));
            check(settings.isConfigured() && !settings.isConsentGranted(),"guide saves connection without granting screenshot consent");
            check(!runner.getTargetContext().getSharedPreferences("screen_assistant",0).getAll().toString().contains("guide-fixture-secret"),"guide credential stored encrypted");
            AtomicInteger calls=new AtomicInteger();
            AssistantApi fake=new AssistantApi(runner.getTargetContext(),url->new Fixture(url,calls.incrementAndGet()==1 ? 401 : 200));
            ui(()-> { ((AssistantApi)field("api")).close(); set("api",fake); });
            check(calls.get()==0,"entering test step does not send a request automatically");
            next(); waitForIdleRequest();
            ui(()->check(field("step")==SetupFlow.Step.TEST && ((TextView)field("feedback")).getText().toString().contains("HTTP 401"),"failed test remains on test step with safe error"));
            check(!settings.isConnectionVerified(),"failed test cannot mark configuration verified");
            next(); waitForIdleRequest();
            ui(()->check(field("step")==SetupFlow.Step.CONSENT,"successful test advances exactly one step"));
            check(calls.get()==2 && settings.isConnectionVerified() && !settings.isConsentGranted(),"only explicit tests send requests and consent remains separate");
            click("重听当前步骤");
            check(!settings.isConsentGranted(),"reading upload explanation does not grant consent");
            click("我了解并同意，继续");
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
    private void touch(KeyPastePage page,long time,int action,float x,float y) {
        android.view.MotionEvent event=android.view.MotionEvent.obtain(time,time,action,x,y,0);
        try { page.dispatchTouchEvent(event); } finally { event.recycle(); }
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
