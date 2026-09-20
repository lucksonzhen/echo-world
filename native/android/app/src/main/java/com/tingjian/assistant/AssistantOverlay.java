package com.tingjian.assistant;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
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
    private String statusMessage = "";
    private String latestDescription = "";

    AssistantOverlay(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        windows = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    void show() {
        panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(10), dp(10), dp(10), dp(10));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(247, 249, 243));
        background.setCornerRadius(dp(18));
        background.setStroke(dp(1), Color.rgb(174, 193, 176));
        panel.setBackground(background);
        panel.setElevation(dp(8));
        panel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        params = new WindowManager.LayoutParams(dp(148), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, BASE_FLAGS, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        params.x = dp(8);
        rebuildPanel();
        windows.addView(panel, params);
    }

    void render(boolean active, boolean pauseEnabled, String status, String description) {
        this.active = active;
        this.pauseEnabled = pauseEnabled;
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
        if (panel != null) panel.announceForAccessibility("听见屏幕服务已关闭。");
        Toast.makeText(context, "听见屏幕服务已关闭", Toast.LENGTH_SHORT).show();
    }

    void setHidden(boolean hidden) {
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
                ? Math.min(dp(292), context.getResources().getDisplayMetrics().widthPixels - dp(16)) : dp(148);
        try { windows.updateViewLayout(panel, params); } catch (IllegalArgumentException ignored) {}
    }

    private void rebuildPanel() {
        panel.removeAllViews();
        statusView = null;
        if (!expanded) {
            Button expand = button("听见屏幕", () -> { expanded = true; updatePanel(); });
            expand.setContentDescription("听见屏幕，展开控制");
            panel.addView(expand);
            if (active || pauseEnabled) panel.addView(button("停止", () -> listener.onCommand("停止")));
            return;
        }
        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(context);
        title.setText("听见屏幕"); title.setTextColor(INK); title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setAccessibilityHeading(true);
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
        Button video = button("观察视频", () -> listener.onCommand("观察视频"));
        video.setContentDescription("观察视频，采集接下来约八秒的六个画面，不录制声音");
        controls.addView(video);
        controls.addView(button(pauseEnabled ? "关闭暂停讲解" : "开启暂停讲解",
                () -> listener.onCommand(pauseEnabled ? "关闭暂停讲解" : "开启暂停讲解")));
        controls.addView(button("语音待命设置", listener::onVoiceSettings));
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
