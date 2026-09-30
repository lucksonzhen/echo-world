package com.tingjian.assistant;

import android.content.Context;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ScrollView;

/** A single accessible action across the key page. Single taps and scrolling never activate it. */
final class KeyPastePage extends ScrollView {
    private final Runnable action;
    private boolean active=true, moved;
    private float downX,downY,lastTapX,lastTapY;
    private long downTime,lastTap,generation,lastTapGeneration;
    KeyPastePage(Context context,Runnable action) {
        super(context); this.action=action;
        setFillViewport(true); setFocusable(true); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }
    void setPrompt(String text) { generation++; lastTap=0; setContentDescription(text); }
    void setActive(boolean value) { active=value; lastTap=0; }
    @Override public boolean performClick() {
        if (!active) return false;
        lastTap=0; action.run(); return true;
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!active) return false;
        switch(event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX=event.getX(); downY=event.getY(); downTime=event.getEventTime(); moved=false; break;
            case MotionEvent.ACTION_MOVE:
                int slop=ViewConfiguration.get(getContext()).getScaledTouchSlop();
                if (Math.abs(event.getX()-downX)>slop || Math.abs(event.getY()-downY)>slop) { moved=true; lastTap=0; } break;
            case MotionEvent.ACTION_POINTER_DOWN: moved=true; lastTap=0; break;
            case MotionEvent.ACTION_CANCEL: moved=true; lastTap=0; break;
            case MotionEvent.ACTION_UP:
                long now=event.getEventTime();
                if (!moved && now-downTime<500) {
                    int distance=ViewConfiguration.get(getContext()).getScaledDoubleTapSlop();
                    if(lastTap!=0 && now-lastTap<=ViewConfiguration.getDoubleTapTimeout() && lastTapGeneration==generation
                            && Math.abs(downX-lastTapX)<=distance && Math.abs(downY-lastTapY)<=distance) performClick();
                    else { lastTap=now; lastTapGeneration=generation; lastTapX=downX; lastTapY=downY; }
                } else lastTap=0;
                break;
            default: break;
        }
        super.onTouchEvent(event); return true;
    }
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.Button"); info.setClickable(true);
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,"执行当前步骤"));
    }
    @Override public boolean performAccessibilityAction(int action,Bundle arguments) {
        if(action==AccessibilityNodeInfo.ACTION_CLICK) return performClick();
        return super.performAccessibilityAction(action,arguments);
    }
    @Override public boolean onKeyDown(int code,KeyEvent event) {
        if(code==KeyEvent.KEYCODE_ENTER || code==KeyEvent.KEYCODE_DPAD_CENTER) {
            if(event.getRepeatCount()==0) performClick(); return true;
        }
        return super.onKeyDown(code,event);
    }
}
