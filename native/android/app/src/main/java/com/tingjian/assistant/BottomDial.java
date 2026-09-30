package com.tingjian.assistant;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import java.util.ArrayList;
import java.util.List;

/** One stable, bottom-anchored control. Browsing never runs an action or changes a saved value. */
public final class BottomDial extends View {
    public interface Feedback { void stop(); void speak(String text); }
    public final class Item {
        private String label, spokenDetail="";
        private final Runnable action;
        private boolean enabled=true, visible=true;
        Item(String label,Runnable action) { this.label=label; this.action=action; }
        public void setEnabled(boolean value) { if (enabled!=value) { enabled=value; refresh(); } }
        public boolean isEnabled() { return enabled; }
        public void setText(String value) { if (!label.equals(value)) { label=value; refresh(); } }
        public void setSpokenDetail(String value) { if(!spokenDetail.equals(value)) { spokenDetail=value; refresh(); } }
        public String getText() { return label; }
        public void setVisibility(int value) { boolean next=value==VISIBLE; if (visible!=next) { visible=next; refresh(); } }
        public boolean performClick() { if (!enabled || !visible) return false; action.run(); return true; }
    }
    private final List<Item> root=new ArrayList<>();
    private List<Item> menu=root;
    private int selected, rootSelection;
    private long version, lastTap, lastTapVersion;
    private final Handler handler=new Handler();
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Feedback feedback;
    private SoundPool sounds;
    private int clickSound;
    private boolean soundReady, active=true, dragging;
    private float anchorX, downX, downY;
    private long downTime;
    private String usagePrefix;
    private final Runnable announce=this::announceSelection;
    private void announceSelection() {
        if(!active || !isShown() || current()==null) return;
        android.view.accessibility.AccessibilityEvent event=android.view.accessibility.AccessibilityEvent.obtain(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SCROLLED);
        event.setFromIndex(selected); event.setToIndex(selected); event.setItemCount(visibleItems().size());
        sendAccessibilityEventUnchecked(event);
        String prefix=usagePrefix; usagePrefix=null;
        feedback.speak(prefix==null ? description() : prefix+"。"+usageDescription());
    }

    public BottomDial(Context context,Feedback feedback) {
        super(context); this.feedback=feedback;
        setFocusable(true); setClickable(true); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setMinimumHeight(dp(184)); setBackgroundColor(Color.rgb(248,249,246));
    }
    public Item add(String label,Runnable action) { Item item=new Item(label,action); root.add(item); refresh(); return item; }
    /** Reposition a guided action without running it or queuing a second announcement. */
    public void select(Item item) {
        if (!root.contains(item) || !item.visible) return;
        cancelSpeech(); menu=root; selected=visibleItems().indexOf(item); refresh();
    }
    public List<Item> items() { return new ArrayList<>(root); }
    public Item current() { List<Item> list=visibleItems(); return list.isEmpty()?null:list.get(Math.min(selected,list.size()-1)); }
    private List<Item> visibleItems() { List<Item> list=new ArrayList<>(); for(Item item:menu) if(item.visible) list.add(item); return list; }
    private String description() {
        Item item=current();
        return item==null ? "底部拨轮，暂无选项" : "第"+(selected+1)+"项，共"+visibleItems().size()+"项，"+item.label+(item.spokenDetail.isEmpty()?"":"。"+item.spokenDetail)+(item.enabled?"，双击执行":"，暂不可用");
    }
    public static String operationHint(Context context) {
        return AccessibilitySupport.hasScreenReader(context)
                ? "操作区在屏幕底部。请先用读屏聚焦拨轮，再用双指左右滑动选择，也可使用下一项、上一项无障碍操作。停下来听选项，双击屏幕确认。"
                : "操作区在屏幕底部。用一根手指在底部左右滑动选择，咔哒声表示换了一项。停下来听选项，在底部双击确认。";
    }
    public String usageDescription() { return operationHint(getContext())+"当前是"+description()+"。"; }
    public void announceUsage(String prefix) { cancelSpeech(); usagePrefix=prefix; scheduleSpeech(); }
    private void refresh() {
        selected=Math.max(0,Math.min(selected,visibleItems().size()-1));
        version++; lastTap=0; setContentDescription(description()); invalidate();
    }
    public void choose(String title,String[] options,int initial,java.util.function.IntConsumer onConfirm) {
        cancelSpeech(); if (menu==root) rootSelection=selected;
        List<Item> choices=new ArrayList<>();
        for(int i=0;i<options.length;i++) {
            final int index=i;
            choices.add(new Item(title+"："+options[i],()-> { closeMenu(false); onConfirm.accept(index); if(active && isShown()) announceUsage("已选择："+options[index]+"。已返回主拨轮"); }));
        }
        choices.add(new Item("取消选择，返回",()->closeMenu(true)));
        menu=choices; selected=Math.max(0,Math.min(initial,options.length-1)); refresh(); announceUsage("请选择"+title);
    }
    public void showMenu(String[] labels,Runnable[] actions) {
        cancelSpeech(); if(menu==root) rootSelection=selected;
        menu=new ArrayList<>();
        for(int i=0;i<labels.length;i++) { final Runnable action=actions[i]; menu.add(new Item(labels[i],()->{ closeMenu(false); action.run(); })); }
        menu.add(new Item("返回主拨轮",()->closeMenu(true))); selected=0; refresh(); announceUsage("已打开选项拨轮");
    }
    public boolean closeMenu(boolean speak) {
        if(menu==root) return false;
        cancelSpeech(); menu=root; selected=rootSelection; refresh(); if(speak) announceUsage("已返回主拨轮"); return true;
    }
    public void move(int direction) {
        if(!active) return;
        cancelSpeech(); int next=Math.max(0,Math.min(visibleItems().size()-1,selected+direction));
        if(next!=selected) {
            selected=next; refresh();
            if(soundReady && sounds!=null) sounds.play(clickSound,.4f,.4f,1,0,1f);
            else playSoundEffect(android.view.SoundEffectConstants.CLICK);
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
        scheduleSpeech();
    }
    private void scheduleSpeech() { handler.removeCallbacks(announce); if(active) handler.postDelayed(announce,550); }
    public void cancelSpeech() { handler.removeCallbacks(announce); usagePrefix=null; feedback.stop(); }
    public void setActive(boolean value) { active=value; if(!value) { cancelSpeech(); lastTap=0; } }
    @Override public boolean performClick() {
        if(!active) return false;
        super.performClick(); cancelSpeech(); lastTap=0;
        Item item=current(); if(item==null) return false;
        if(!item.enabled) { feedback.speak(description()); return false; }
        return item.performClick();
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if(!active) return false;
        float x=0; for(int i=0;i<event.getPointerCount();i++) x+=event.getX(i); x/=event.getPointerCount();
        switch(event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                cancelSpeech(); anchorX=downX=x; downY=event.getY(); downTime=SystemClock.uptimeMillis(); dragging=false; return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                anchorX=x; dragging=true; lastTap=0; return true;
            case MotionEvent.ACTION_POINTER_UP:
                float remaining=0; for(int i=0;i<event.getPointerCount();i++) if(i!=event.getActionIndex()) remaining+=event.getX(i);
                anchorX=remaining/Math.max(1,event.getPointerCount()-1); dragging=true; lastTap=0; return true;
            case MotionEvent.ACTION_MOVE:
                if(Math.abs(x-downX)>ViewConfiguration.get(getContext()).getScaledTouchSlop() || Math.abs(event.getY()-downY)>dp(16)) { dragging=true; lastTap=0; }
                while(Math.abs(x-anchorX)>=dp(56)) { int direction=x<anchorX?1:-1; anchorX-=direction*dp(56); move(direction); }
                return true;
            case MotionEvent.ACTION_UP:
                if(!dragging && SystemClock.uptimeMillis()-downTime<500) {
                    long now=SystemClock.uptimeMillis();
                    if(lastTap!=0 && now-lastTap<=ViewConfiguration.getDoubleTapTimeout() && lastTapVersion==version) performClick();
                    else { lastTap=now; lastTapVersion=version; scheduleSpeech(); }
                } else { lastTap=0; scheduleSpeech(); }
                return true;
            case MotionEvent.ACTION_CANCEL: lastTap=0; handler.removeCallbacks(announce); return true;
            default: return true;
        }
    }
    @Override public boolean onKeyDown(int code,KeyEvent event) {
        if(code==KeyEvent.KEYCODE_DPAD_LEFT) { move(-1); return true; }
        if(code==KeyEvent.KEYCODE_DPAD_RIGHT) { move(1); return true; }
        if(code==KeyEvent.KEYCODE_DPAD_CENTER || code==KeyEvent.KEYCODE_ENTER) { if(event.getRepeatCount()==0) performClick(); return true; }
        return super.onKeyDown(code,event);
    }
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.SeekBar"); info.setScrollable(true);
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,0,Math.max(0,visibleItems().size()-1),selected));
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,"下一项"));
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,"上一项"));
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,"执行当前项"));
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT);
    }
    @Override public boolean performAccessibilityAction(int action,Bundle args) {
        if(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action==AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId()) { move(1); return true; }
        if(action==AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD || action==AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.getId()) { move(-1); return true; }
        if(action==AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId() && args!=null) {
            float value=args.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,selected);
            if(Float.isNaN(value) || Float.isInfinite(value)) return false;
            move(Math.round(value)-selected); return true;
        }
        if(action==AccessibilityNodeInfo.ACTION_CLICK) return performClick();
        return super.performAccessibilityAction(action,args);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); float w=getWidth(),cx=w/2f;
        paint.setTypeface(android.graphics.Typeface.create("sans-serif",0)); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1)); paint.setColor(Color.rgb(217,226,216));
        canvas.drawArc(-w*.18f,dp(20),w*1.18f,dp(180),198,144,false,paint); paint.setStyle(Paint.Style.FILL);
        // A quiet central marker and two fading detents suggest a rotary surface.
        paint.setColor(Color.rgb(201,215,202)); canvas.drawCircle(cx-dp(54),dp(24),dp(2),paint); canvas.drawCircle(cx+dp(54),dp(24),dp(2),paint);
        paint.setColor(Color.rgb(43,89,66)); canvas.drawCircle(cx,dp(20),dp(3),paint);
        Item item=current(); String label=item==null?"暂无选项":item.label;
        paint.setTextAlign(Paint.Align.CENTER); paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0)); paint.setColor(item!=null&&!item.enabled?Color.rgb(113,123,115):Color.rgb(29,62,45));
        float size=20*getResources().getDisplayMetrics().scaledDensity; paint.setTextSize(size);
        while(paint.measureText(label)>w-dp(80) && size>16*getResources().getDisplayMetrics().scaledDensity) paint.setTextSize(--size);
        List<String> lines=new ArrayList<>(); StringBuilder line=new StringBuilder();
        for(int offset=0;offset<label.length();) { int cp=label.codePointAt(offset); String c=new String(Character.toChars(cp));
            if(paint.measureText(line.toString()+c)>w-dp(80) && line.length()>0) { lines.add(line.toString()); line.setLength(0); }
            line.append(c); offset+=Character.charCount(cp);
        }
        if(line.length()>0) lines.add(line.toString());
        for(int i=0;i<Math.min(2,lines.size());i++) canvas.drawText(lines.get(i),cx,dp(lines.size()>1?68:82)+i*dp(26),paint);
        paint.setTypeface(android.graphics.Typeface.create("sans-serif",0)); paint.setTextSize(12*getResources().getDisplayMetrics().scaledDensity); paint.setColor(Color.rgb(94,113,99));
        canvas.drawText((selected+1)+"  /  "+visibleItems().size(),cx,dp(122),paint);
        paint.setColor(Color.rgb(76,99,83)); paint.setTextSize(13*getResources().getDisplayMetrics().scaledDensity);
        canvas.drawText("左右滑动选择   ·   双击确认",cx,dp(160),paint);
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        try { sounds=new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        sounds.setOnLoadCompleteListener((pool,id,status)->soundReady=status==0);
        clickSound=sounds.load(getContext(),R.raw.dial_click,1);
        } catch(RuntimeException unavailable) { if(sounds!=null) sounds.release(); sounds=null; soundReady=false; }
    }
    @Override protected void onDetachedFromWindow() {
        cancelSpeech(); if(sounds!=null) { sounds.release(); sounds=null; } soundReady=false; super.onDetachedFromWindow();
    }
    public static LinearLayout page(Context context,ScrollView content,BottomDial dial) {
        content.setOnApplyWindowInsetsListener(null); content.setPadding(0,0,0,0);
        LinearLayout page=new LinearLayout(context); page.setOrientation(LinearLayout.VERTICAL); page.setBackgroundColor(Color.rgb(248,249,246));
        page.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        page.addView(dial,new LinearLayout.LayoutParams(-1,dial.dp(184)));
        page.setOnApplyWindowInsetsListener((view,insets)-> {
            android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom); return insets;
        });
        return page;
    }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
}
