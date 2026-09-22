package com.tingjian.assistant;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;

/**
 * Looks for one prominent picture without usable alt text in the foreground window after the
 * screen has settled. Only class names, labels, and bounds of nodes are inspected, on this device;
 * the tree is never uploaded. The listener receives just the picture's screen rectangle, and the
 * service decides whether to take a screenshot cropped to it.
 */
final class ImageWatchMonitor {
    interface Listener {
        void onImageFound(String packageName, Rect bounds);
    }

    private final AccessibilityService service;
    private final Handler main;
    private final Listener listener;
    private final ImageWatchPolicy policy = new ImageWatchPolicy();
    private String target = "";
    private boolean started;

    ImageWatchMonitor(AccessibilityService service, Handler main, Listener listener) {
        if (main.getLooper() != Looper.getMainLooper()) throw new IllegalArgumentException("Use main Handler");
        if (listener == null) throw new IllegalArgumentException("Listener required");
        this.service = service;
        this.main = main;
        this.listener = listener;
    }

    boolean isStarted() { return started; }

    void start() {
        started = true;
        policy.reset();
    }

    void stop() {
        started = false;
        main.removeCallbacks(evaluate);
        policy.reset();
    }

    /** A new foreground app forgets the previous picture so returning to it describes again. */
    void setTargetPackage(String packageName) {
        String next = packageName == null || packageName.equals(service.getPackageName()) ? "" : packageName;
        if (target.equals(next)) return;
        target = next;
        main.removeCallbacks(evaluate);
        policy.reset();
    }

    /** Called for content, scroll, and window events of the foreground app. */
    void onScreenChanged(String packageName) {
        if (!started || target.isEmpty() || !target.equals(packageName)) return;
        policy.onScreenChanged(SystemClock.uptimeMillis());
        schedule();
    }

    boolean isNewImage(long hash) { return policy.isNewImage(hash); }

    void onDescribed(long hash) { policy.onDescribed(SystemClock.uptimeMillis(), hash); }

    private void schedule() {
        main.removeCallbacks(evaluate);
        main.postDelayed(evaluate, Math.max(1L, policy.remaining(SystemClock.uptimeMillis())));
    }

    private final Runnable evaluate = new Runnable() {
        @Override public void run() {
            if (!started || target.isEmpty()) return;
            long now = SystemClock.uptimeMillis();
            if (!policy.shouldEvaluate(now)) { if (policy.isPending()) schedule(); return; }
            policy.consume();
            Rect found = findProminentImage();
            if (found != null) listener.onImageFound(target, found);
        }
    };

    private Rect findProminentImage() {
        AccessibilityNodeInfo root;
        try { root = service.getRootInActiveWindow(); } catch (RuntimeException error) { return null; }
        if (root == null || root.getPackageName() == null || !target.contentEquals(root.getPackageName())) return null;
        DisplayMetrics metrics = service.getResources().getDisplayMetrics();
        Rect window = new Rect();
        root.getBoundsInScreen(window);
        Rect best = null;
        int visited = 0;
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        ArrayDeque<Integer> depths = new ArrayDeque<>();
        queue.add(root);
        depths.add(0);
        Rect bounds = new Rect();
        try {
            while (!queue.isEmpty() && visited < ImageWatchPolicy.MAX_NODES) {
                AccessibilityNodeInfo node = queue.poll();
                int depth = depths.poll();
                visited++;
                if (!node.isVisibleToUser()) continue;
                if (ImageWatchPolicy.isImageClass(node.getClassName())
                        && ImageWatchPolicy.isGenericLabel(node.getContentDescription())
                        && ImageWatchPolicy.isGenericLabel(node.getText())) {
                    node.getBoundsInScreen(bounds);
                    if (bounds.intersect(window)
                            && ImageWatchPolicy.isProminent(bounds.width(), bounds.height(), metrics.widthPixels, metrics.heightPixels)
                            && (best == null || area(bounds) > area(best))) {
                        best = new Rect(bounds);
                    }
                }
                if (depth >= ImageWatchPolicy.MAX_DEPTH) continue;
                int children = node.getChildCount();
                for (int index = 0; index < children && queue.size() + visited < ImageWatchPolicy.MAX_NODES; index++) {
                    AccessibilityNodeInfo child;
                    try { child = node.getChild(index); } catch (RuntimeException error) { child = null; }
                    if (child != null) { queue.add(child); depths.add(depth + 1); }
                }
            }
        } catch (RuntimeException error) {
            // The window can disappear while it is being read; simply report nothing this time.
            return null;
        }
        return best;
    }

    private static long area(Rect rect) { return (long) rect.width() * rect.height(); }
}
