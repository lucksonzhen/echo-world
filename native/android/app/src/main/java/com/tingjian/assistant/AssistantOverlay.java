package com.tingjian.assistant;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Owns the accessible floating panel; commands and capture remain in the service. */
final class AssistantOverlay {
    interface Listener {
        void onCommand(String command);
        void onVoiceSettings();
        void onClose();
    }

    private static final int GREEN = Color.rgb(29, 89, 71);
    private static final int INK = Color.rgb(27, 53, 45);
    private static final int BASE_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;

    private final Context context;
    private final Listener listener;
    private final WindowManager windows;
    private WindowManager.LayoutParams params;
    private LinearLayout panel;
    private TextView statusView;
    private boolean expanded;
    private boolean active;
    private boolean pauseEnabled;
    private boolean imageWatchEnabled;
    private String statusMessage = "";
    private String latestDescription = "";
    private final SettingsStore settings;
    private final android.content.SharedPreferences position;
    private boolean captureHidden;

    AssistantOverlay(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        settings=new SettingsStore(context);
        position=context.getSharedPreferences("overlay_position",Context.MODE_PRIVATE);
        windows = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    void show() {
        if (!settings.isOverlayVisible() || panel!=null) return;
        panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(0, 0, 0, 0);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(247, 249, 243));
        background.setCornerRadius(dp(18));
        background.setStroke(dp(1), Color.rgb(174, 193, 176));
        panel.setBackground(background);
        panel.setElevation(dp(8));
        panel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        params = new WindowManager.LayoutParams(dp(64), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, BASE_FLAGS, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        android.graphics.Rect area=windows.getCurrentWindowMetrics().getBounds();
        params.x = position.getInt("x",area.width()-dp(72));
        params.y = position.getInt("y",area.height()/2);
        clampPosition();
        rebuildPanel();
        windows.addView(panel, params);
        setHidden(captureHidden);
    }

    void setEnabled(boolean enabled) { settings.setOverlayVisible(enabled); if (enabled) show(); else close(); }

    void render(boolean active, boolean pauseEnabled, boolean imageWatchEnabled, String status, String description) {
        this.active = active;
        this.pauseEnabled = pauseEnabled;
        this.imageWatchEnabled = imageWatchEnabled;
        statusMessage = status;
        latestDescription = description;
        updatePanel();
    }

    void setStatus(String message) {
        statusMessage = message;
        if (statusView != null) {
            statusView.setText(message);
            statusView.setContentDescription(message);
        }
    }

    void announce(String message) {
        if (statusView != null) statusView.announceForAccessibility(message);
        else if (panel != null) panel.announceForAccessibility(message);
    }

    void announceClosed() {
        if (panel != null) panel.announceForAccessibility("听见世界服务已关闭。");
        Toast.makeText(context, "听见世界服务已关闭", Toast.LENGTH_SHORT).show();
    }

    void setHidden(boolean hidden) {
        captureHidden=hidden;
        if (panel == null) return;
        panel.setVisibility(hidden ? View.INVISIBLE : View.VISIBLE);
        params.flags = BASE_FLAGS | (hidden ? WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : 0);
        try { windows.updateViewLayout(panel, params); } catch (IllegalArgumentException ignored) {}
    }

    void close() {
        if (panel == null) return;
        try { windows.removeViewImmediate(panel); } catch (IllegalArgumentException ignored) {}
        panel = null;
        statusView = null;
    }

    private void updatePanel() {
        if (panel == null) return;
        rebuildPanel();
        params.width = expanded
                ? Math.min(dp(292), context.getResources().getDisplayMetrics().widthPixels - dp(16)) : dp(64);
        clampPosition();
        try { windows.updateViewLayout(panel, params); } catch (IllegalArgumentException ignored) {}
    }

    private void rebuildPanel() {
        panel.removeAllViews();
        statusView = null;
        if (!expanded) {
            Button expand = button("听", () -> { expanded = true; updatePanel(); });
            expand.setContentDescription("听见世界备用按钮，双击展开；可拖动，读屏操作菜单可移动或隐藏");
            expand.setTextSize(24);
            attachMovement(expand);
            panel.addView(expand);
            return;
        }
        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(context);
        title.setText("听见世界"); title.setTextColor(INK); title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setAccessibilityHeading(true);
        title.setContentDescription("听见世界控制窗，拖动可移动；读屏操作菜单可移动或隐藏");
        attachMovement(title);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(56), 1f));
        header.addView(button("收起", () -> { expanded = false; updatePanel(); }),
                new LinearLayout.LayoutParams(dp(76), dp(56)));
        panel.addView(header);

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(false);
        LinearLayout controls = new LinearLayout(context);
        controls.setOrientation(LinearLayout.VERTICAL);
        statusView = new TextView(context);
        statusView.setTextColor(INK); statusView.setTextSize(15);
        statusView.setPadding(dp(6), dp(8), dp(6), dp(8));
        statusView.setMaxLines(4);
        statusView.setText(statusMessage);
        statusView.setContentDescription(statusMessage);
        // TTS handles requested descriptions; avoid duplicate TalkBack speech.
        statusView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
        controls.addView(statusView);
        controls.addView(button("描述屏幕", () -> listener.onCommand("描述屏幕")));
        controls.addView(button("读文字", () -> listener.onCommand("读文字")));
        Button repeat = button("再说一遍", () -> listener.onCommand("再说一遍"));
        repeat.setContentDescription("再说一遍，重听最近一次成功的描述，不重新截图或上传");
        repeat.setEnabled(!latestDescription.isEmpty());
        controls.addView(repeat);
        Button video = button("观察视频", () -> listener.onCommand("观察视频"));
        video.setContentDescription("观察视频，采集接下来约八秒的六个画面，不录制声音");
        controls.addView(video);
        controls.addView(button(pauseEnabled ? "关闭暂停讲解" : "开启暂停讲解",
                () -> listener.onCommand(pauseEnabled ? "关闭暂停讲解" : "开启暂停讲解")));
        Button imageWatch = button(imageWatchEnabled ? "关闭图片自动描述" : "开启图片自动描述",
                () -> listener.onCommand(imageWatchEnabled ? "关闭图片自动描述" : "开启图片自动描述"));
        imageWatch.setContentDescription(imageWatchEnabled ? "关闭图片自动描述" : "开启图片自动描述，浏览时自动简述较大的图片");
        controls.addView(imageWatch);
        controls.addView(button("语音待命设置", listener::onVoiceSettings));
        controls.addView(button("隐藏悬浮窗，保留语音", () -> setEnabled(false)));
        controls.addView(button("停止", () -> listener.onCommand("停止")));
        controls.addView(button("关闭服务", listener::onClose));
        if (!latestDescription.isEmpty()) {
            TextView resultTitle = new TextView(context);
            resultTitle.setText("最近一次描述"); resultTitle.setTextColor(INK); resultTitle.setTextSize(17);
            resultTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            resultTitle.setPadding(dp(6), dp(16), dp(6), dp(8));
            resultTitle.setAccessibilityHeading(true);
            controls.addView(resultTitle);
            TextView resultText = new TextView(context);
            resultText.setText(latestDescription); resultText.setTextColor(INK); resultText.setTextSize(16);
            resultText.setPadding(dp(6), dp(4), dp(6), dp(12));
            resultText.setLineSpacing(dp(4), 1f);
            resultText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
            controls.addView(resultText);
        }
        scroll.addView(controls);
        int height = Math.max(dp(160), Math.min(dp(460), context.getResources().getDisplayMetrics().heightPixels - dp(180)));
        panel.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height));
    }

    private void clampPosition() {
        android.view.WindowMetrics metrics=windows.getCurrentWindowMetrics();
        android.graphics.Rect bounds=metrics.getBounds();
        android.graphics.Insets insets=metrics.getWindowInsets().getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars());
        int height=expanded ? Math.min(dp(560),bounds.height()-insets.top-insets.bottom) : dp(64);
        params.x=Math.max(0,Math.min(params.x,Math.max(0,bounds.width()-insets.left-insets.right-params.width)));
        params.y=Math.max(0,Math.min(params.y,Math.max(0,bounds.height()-insets.top-insets.bottom-height)));
    }
    private void move(int x,int y,boolean save) {
        params.x=x; params.y=y; clampPosition();
        try { windows.updateViewLayout(panel,params); } catch (IllegalArgumentException ignored) { }
        if (save) position.edit().putInt("x",params.x).putInt("y",params.y).apply();
    }
    private void attachMovement(View handle) {
        final int[] actions={R.id.overlay_move_up,R.id.overlay_move_down,R.id.overlay_move_left,R.id.overlay_move_right,R.id.overlay_hide};
        handle.setOnTouchListener(new View.OnTouchListener() {
            float startX,startY; int originX,originY; boolean dragged;
            public boolean onTouch(View view,MotionEvent event) {
                if (event.getActionMasked()==MotionEvent.ACTION_DOWN) {
                    startX=event.getRawX(); startY=event.getRawY(); originX=params.x; originY=params.y; dragged=false; return true;
                }
                if (event.getActionMasked()==MotionEvent.ACTION_MOVE) {
                    float dx=event.getRawX()-startX,dy=event.getRawY()-startY;
                    if (Math.hypot(dx,dy)>ViewConfiguration.get(context).getScaledTouchSlop()) dragged=true;
                    if (dragged) move(originX+(int)dx,originY+(int)dy,false); return true;
                }
                if (event.getActionMasked()==MotionEvent.ACTION_UP) {
                    if (dragged) move(params.x,params.y,true); else view.performClick(); return true;
                }
                return event.getActionMasked()==MotionEvent.ACTION_CANCEL;
            }
        });
        handle.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host,info);
                String[] labels={"向上移动","向下移动","向左移动","向右移动","隐藏悬浮窗"};
                for (int i=0;i<labels.length;i++) info.addAction(new AccessibilityNodeInfo.AccessibilityAction(actions[i],labels[i]));
            }
            public boolean performAccessibilityAction(View host,int action,android.os.Bundle args) {
                int which=-1;
                for (int i=0;i<actions.length;i++) if (actions[i]==action) which=i;
                if (which==4) { setEnabled(false); return true; }
                if (which>=0 && which<4) {
                    move(params.x+(which==2 ? -dp(80) : which==3 ? dp(80) : 0), params.y+(which==0 ? -dp(80) : which==1 ? dp(80) : 0),true);
                    host.announceForAccessibility("悬浮按钮已移动。"); return true;
                }
                return super.performAccessibilityAction(host,action,args);
            }
        });
    }

    private Button button(String label, Runnable action) {
        Button button = new Button(context);
        button.setText(label); button.setTextSize(16); button.setTextColor(GREEN);
        button.setAllCaps(false); button.setMinHeight(dp(52)); button.setMinimumHeight(dp(52));
        button.setMinWidth(dp(48)); button.setMinimumWidth(dp(48));
        button.setContentDescription(label);
        button.setOnClickListener(view -> action.run());
        button.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return button;
    }

    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
}
