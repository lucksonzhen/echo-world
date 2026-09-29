package com.tingjian.assistant;

import android.widget.TextView;

/** Explicit allow/revoke choices replace a visually located checkbox. */
final class DialConsent {
    private boolean checked;
    private final TextView status;
    private final BottomDial.Item entry;
    private java.util.function.Consumer<Boolean> changed=value->{};
    DialConsent(BottomDial dial,TextView status) {
        this.status=status;
        entry=dial.add("屏幕上传授权",()->dial.choose("屏幕上传授权",new String[]{"不允许上传屏幕","我了解并允许发送当前屏幕到所选模型服务"},checked?1:0,index->setChecked(index==1)));
        update();
    }
    boolean isChecked() { return checked; }
    void setChecked(boolean value) { boolean different=checked!=value; checked=value; update(); if(different) changed.accept(value); }
    void onChanged(java.util.function.Consumer<Boolean> listener) { changed=listener; }
    private void update() { String label="屏幕上传授权："+(checked?"允许":"不允许"); status.setText(label); entry.setText(label); }
}
