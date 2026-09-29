package com.tingjian.assistant;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual wheel events and Activity layout; no screenshots, live model requests or microphone. */
final class DialChecks {
    private final Instrumentation runner;
    private final StringBuilder report;
    private int passed;
    DialChecks(Instrumentation runner,StringBuilder report) { this.runner=runner; this.report=report; }
    private void ui(Runnable task) { runner.runOnMainSync(task); runner.waitForIdleSync(); }
    private void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); passed++; report.append("PASS ").append(message).append('\n'); }
    private BottomDial find(View view) {
        if(view instanceof BottomDial) return (BottomDial)view;
        if(view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++) { BottomDial result=find(((ViewGroup)view).getChildAt(i)); if(result!=null) return result; }
        return null;
    }
    private boolean legacyControl(View view) {
        if(view instanceof android.widget.Button || view instanceof android.widget.Spinner || view instanceof android.widget.CheckBox) return true;
        if(view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++) if(legacyControl(((ViewGroup)view).getChildAt(i))) return true;
        return false;
    }
    int run() {
        SetupGuideActivity activity=(SetupGuideActivity)runner.startActivitySync(new Intent(runner.getTargetContext(),SetupGuideActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            ui(()-> {
                BottomDial dial=find(activity.getWindow().getDecorView());
                check(dial!=null && !legacyControl(activity.getWindow().getDecorView()),"guide has one wheel and no buttons or dropdown menus");
                android.widget.LinearLayout page=(android.widget.LinearLayout)dial.getParent();
                check(page.getChildAt(page.getChildCount()-1)==dial && page.getChildAt(0) instanceof android.widget.ScrollView,"wheel is a fixed sibling below scrollable content");
                BottomDial.Item provider=dial.current();
                check(provider.getText().equals("模型接口配置"),"wheel presents a stable model configuration entry");
                snapshot(activity,"dial-guide.png");
                dial.performClick(); dial.move(1);
                check(dial.current().getText().contains("DeepSeek"),"provider submenu scrolls to DeepSeek");
                check(selectedProvider(activity).contains("Gemini"),"scrolling provider choices does not commit selection");
                dial.performClick();
                check(selectedProvider(activity).contains("DeepSeek"),"explicit wheel activation commits the provider");
                dial.performClick(); dial.move(-1); dial.closeMenu(false);
                check(selectedProvider(activity).contains("DeepSeek"),"cancelling a menu preserves the confirmed provider");
            });
            for(SetupFlow.Step preview:new SetupFlow.Step[]{SetupFlow.Step.KEY,SetupFlow.Step.CONSENT}) {
                ui(()-> {
                    try { java.lang.reflect.Method go=SetupGuideActivity.class.getDeclaredMethod("go",SetupFlow.Step.class); go.setAccessible(true); go.invoke(activity,preview); }
                    catch(Exception error) { throw new AssertionError(error); }
                });
                ui(()->snapshot(activity,"dial-"+preview.name().toLowerCase(java.util.Locale.ROOT)+".png"));
            }
            List<String> speech=new ArrayList<>(); AtomicInteger actions=new AtomicInteger(); BottomDial[] control={null};
            ui(()-> {
                BottomDial dial=new BottomDial(activity,new BottomDial.Feedback() { public void stop(){} public void speak(String text){speech.add(text);} }); control[0]=dial;
                dial.add("第一项",actions::incrementAndGet); dial.add("第二项",actions::incrementAndGet); dial.add("第三项",actions::incrementAndGet);
                activity.setContentView(dial); dial.layout(0,0,1000,500);
                long now=SystemClock.uptimeMillis();
                event(dial,now,MotionEvent.ACTION_DOWN,850); event(dial,now+30,MotionEvent.ACTION_MOVE,600); event(dial,now+50,MotionEvent.ACTION_UP,600);
                check(actions.get()==0 && !dial.current().getText().equals("第一项"),"horizontal drag selects without firing an action");
                dial.move(1); dial.move(-1); dial.move(1);
            });
            SystemClock.sleep(750);
            ui(()-> {
                BottomDial dial=control[0];
                check(speech.size()==1 && speech.get(0).contains(dial.current().getText()),"rapid movement speaks only the final settled item");
                long now=SystemClock.uptimeMillis();
                event(dial,now,MotionEvent.ACTION_DOWN,500); event(dial,now+30,MotionEvent.ACTION_UP,500);
                check(actions.get()==0,"single tap does not execute");
                event(dial,now+100,MotionEvent.ACTION_DOWN,505); event(dial,now+130,MotionEvent.ACTION_UP,505);
                check(actions.get()==1,"double tap executes exactly once");
                dial.current().setEnabled(false); dial.performClick(); check(actions.get()==1,"disabled item cannot execute");
                dial.move(-99); dial.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,null);
                check(dial.current().getText().equals("第二项"),"screen reader scroll action changes wheel selection");
                dial.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,null); check(actions.get()==2,"screen reader activation invokes the selected action");
                dial.move(-1); dial.setActive(false);
                check(!dial.performClick(),"paused wheel rejects activation");
                speech.clear();
            });
            SystemClock.sleep(700);
            ui(()->check(speech.isEmpty(),"leaving the wheel cancels delayed announcements"));
        } finally { ui(activity::finish); runner.getTargetContext().getSharedPreferences("setup_guide",0).edit().clear().commit(); }
        return passed;
    }
    private String selectedProvider(SetupGuideActivity activity) {
        try {
            java.lang.reflect.Field field=SetupGuideActivity.class.getDeclaredField("choices"); field.setAccessible(true);
            DialPicker picker=(DialPicker)field.get(activity);
            return picker.label(picker.getSelectedItemPosition());
        } catch(Exception error) { throw new AssertionError(error); }
    }
    private static void snapshot(SetupGuideActivity activity,String name) {
        try {
            View decor=activity.getWindow().getDecorView();
            android.graphics.Bitmap image=android.graphics.Bitmap.createBitmap(decor.getWidth(),decor.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);
            decor.draw(new android.graphics.Canvas(image));
            try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(activity.getCacheDir(),name))) { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out); }
            image.recycle();
        } catch(Exception failure) { throw new AssertionError(failure); }
    }
    private static void event(BottomDial dial,long time,int type,float x) {
        MotionEvent event=MotionEvent.obtain(time,time,type,x,60,0); try { dial.onTouchEvent(event); } finally { event.recycle(); }
    }
}
