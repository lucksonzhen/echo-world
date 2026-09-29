package com.tingjian.assistant;

import android.content.Context;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

/** Optional bottom wheel over other apps; hidden by default and while our own controls are visible. */
final class AssistantOverlay {
    interface Listener {
        void onCommand(String command);
        void onVoiceSettings();
        void onClose();
        void onSpeak(String text);
        void onStopSpeaking();
    }
    private final Context context;
    private final Listener listener;
    private final WindowManager windows;
    private final SettingsStore settings;
    private BottomDial dial;
    private BottomDial.Item repeat,pause,images;
    private boolean captureHidden,appVisible;
    private String statusMessage="",latestDescription="";
    private boolean pauseEnabled,imageWatchEnabled;
    AssistantOverlay(Context context,Listener listener) {
        this.context=context; this.listener=listener;
        settings=new SettingsStore(context); windows=context.getSystemService(WindowManager.class);
    }
    void show() {
        if(!settings.isOverlayVisible() || appVisible || dial!=null) return;
        dial=new BottomDial(context,new BottomDial.Feedback() {
            public void stop() { listener.onStopSpeaking(); }
            public void speak(String text) {
                if(AccessibilitySupport.hasScreenReader(context)) { if(dial!=null) dial.announceForAccessibility(text); }
                else listener.onSpeak(text);
            }
        });
        dial.add("描述屏幕",()->listener.onCommand("描述屏幕"));
        dial.add("读文字",()->listener.onCommand("读文字"));
        repeat=dial.add("再说一遍",()->listener.onCommand("再说一遍"));
        dial.add("观察视频，约八秒采集六帧",()->listener.onCommand("观察视频"));
        pause=dial.add("暂停讲解",()->listener.onCommand(pauseEnabled?"关闭暂停讲解":"开启暂停讲解"));
        images=dial.add("图片自动描述",()->listener.onCommand(imageWatchEnabled?"关闭图片自动描述":"开启图片自动描述"));
        dial.add("语音待命设置",listener::onVoiceSettings);
        dial.add("隐藏悬浮窗，保留语音",()->setEnabled(false));
        dial.add("停止",()->listener.onCommand("停止"));
        dial.add("当前状态",()->listener.onSpeak(statusMessage));
        dial.add("关闭服务",listener::onClose);
        update();
        WindowManager.LayoutParams params=new WindowManager.LayoutParams(-1,Math.round(184*context.getResources().getDisplayMetrics().density),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT);
        params.gravity=Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL;
        try { windows.addView(dial,params); setHidden(captureHidden); }
        catch(RuntimeException failure) { dial=null; throw failure; }
    }
    void setAppVisible(boolean value) { appVisible=value; if(value) close(); else show(); }
    void setEnabled(boolean enabled) { settings.setOverlayVisible(enabled); if(enabled) show(); else close(); }
    void render(boolean active,boolean pauseEnabled,boolean imageWatchEnabled,String status,String description) {
        this.pauseEnabled=pauseEnabled; this.imageWatchEnabled=imageWatchEnabled; statusMessage=status; latestDescription=description; update();
    }
    private void update() {
        if(dial==null) return;
        repeat.setEnabled(!latestDescription.isEmpty());
        pause.setText(pauseEnabled?"关闭暂停讲解":"开启暂停讲解");
        images.setText(imageWatchEnabled?"关闭图片自动描述":"开启图片自动描述");
    }
    void setStatus(String message) { statusMessage=message; }
    void announce(String message) { if(dial!=null) dial.announceForAccessibility(message); }
    void announceClosed() { announce("听见世界服务已关闭。"); Toast.makeText(context,"听见世界服务已关闭",Toast.LENGTH_SHORT).show(); }
    void setHidden(boolean hidden) {
        captureHidden=hidden; if(dial==null) return;
        dial.setVisibility(hidden?View.INVISIBLE:View.VISIBLE); dial.setActive(!hidden);
        WindowManager.LayoutParams params=(WindowManager.LayoutParams)dial.getLayoutParams();
        params.flags=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|(hidden?WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE:0);
        try { windows.updateViewLayout(dial,params); } catch(IllegalArgumentException ignored) { }
    }
    void close() {
        if(dial==null) return;
        dial.setActive(false);
        try { windows.removeViewImmediate(dial); } catch(IllegalArgumentException ignored) { }
        dial=null;
    }
}
