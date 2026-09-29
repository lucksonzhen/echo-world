package com.tingjian.assistant;

import android.widget.TextView;

/** Selection commits only on wheel activation, never while browsing choices. */
final class DialPicker {
    private final String title;
    private final String[] labels;
    private final TextView value;
    private final BottomDial.Item entry;
    private int selected;
    private java.util.function.IntConsumer changed=index->{};
    DialPicker(BottomDial dial,TextView value,String title,String[] labels) {
        this.value=value; this.title=title; this.labels=labels;
        entry=dial.add(title,()->dial.choose(title,labels,selected,this::setSelection)); update();
    }
    int getSelectedItemPosition() { return selected; }
    String label(int index) { return labels[index]; }
    void setSelection(int index) { boolean different=selected!=index; selected=index; update(); if(different) changed.accept(index); }
    void onChanged(java.util.function.IntConsumer listener) { changed=listener; }
    void setEnabled(boolean enabled) { entry.setEnabled(enabled); }
    private void update() { value.setText(labels[selected]); value.setContentDescription(title+"："+labels[selected]); entry.setText(title); }
}
