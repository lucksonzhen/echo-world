package com.tingjian.assistant;

import android.content.Context;
import android.view.accessibility.AccessibilityManager;
import android.accessibilityservice.AccessibilityServiceInfo;

final class AccessibilitySupport {
    static boolean hasScreenReader(Context context) {
        AccessibilityManager manager=context.getSystemService(AccessibilityManager.class);
        if (manager==null) return false;
        for (AccessibilityServiceInfo service:manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN)) {
            if (service.getResolveInfo()!=null && service.getResolveInfo().serviceInfo!=null
                    && !context.getPackageName().equals(service.getResolveInfo().serviceInfo.packageName)) return true;
        }
        return false;
    }
}
