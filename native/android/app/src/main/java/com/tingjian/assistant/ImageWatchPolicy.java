package com.tingjian.assistant;

import java.util.Locale;

/**
 * Pure rules for unattended image description, independent of Android and wall-clock timers:
 * which node counts as a prominent, unlabeled picture; when a changed screen has settled enough
 * to look at; and whether a captured picture differs from the one already described.
 */
public final class ImageWatchPolicy {
    /** Scrolling and image loading must be quiet for this long before the tree is inspected. */
    static final long SETTLE_MS = 1200;
    /** Apps that animate continuously never settle; inspect anyway after this long. */
    static final long MAX_WAIT_MS = 3000;
    /** Minimum gap between two automatic descriptions, so a fast feed cannot flood the user. */
    static final long COOLDOWN_MS = 4000;
    /** Traversal caps keep node inspection cheap on the main thread. */
    static final int MAX_NODES = 400;
    static final int MAX_DEPTH = 32;
    /** Difference hash samples a 9x8 luminance grid into 64 bits. */
    static final int HASH_WIDTH = 9;
    static final int HASH_HEIGHT = 8;
    static final int SAME_IMAGE_DISTANCE = 6;
    private static final double MIN_AREA_RATIO = 0.10;
    private static final double MIN_SIDE_RATIO = 0.20;
    private static final String[] GENERIC_LABELS = { "图片", "图像", "照片", "相片", "图", "封面", "缩略图", "头像",
            "image", "img", "photo", "picture", "pic", "cover", "thumbnail", "avatar" };

    private long changedAt = -1;
    private long firstChangedAt = -1;
    private long describedAt = -1;
    private long lastHash;
    private boolean hasHash;

    /** A content change, scroll, or window change happened in the watched app. */
    void onScreenChanged(long now) {
        if (changedAt < 0) firstChangedAt = now;
        changedAt = now;
    }

    boolean isPending() { return changedAt >= 0; }

    /** Milliseconds until the pending change may be inspected; zero when it is due. */
    long remaining(long now) {
        if (changedAt < 0) return 0;
        long settle = Math.min(Math.max(0, SETTLE_MS - (now - changedAt)), Math.max(0, MAX_WAIT_MS - (now - firstChangedAt)));
        long cooldown = describedAt < 0 ? 0 : Math.max(0, COOLDOWN_MS - (now - describedAt));
        return Math.max(settle, cooldown);
    }

    boolean shouldEvaluate(long now) { return isPending() && remaining(now) == 0; }

    /** The pending change was inspected (whether or not a picture was found). */
    void consume() { changedAt = -1; firstChangedAt = -1; }

    boolean isNewImage(long hash) { return !hasHash || hammingDistance(hash, lastHash) > SAME_IMAGE_DISTANCE; }

    void onDescribed(long now, long hash) { describedAt = now; lastHash = hash; hasHash = true; }

    /** Forget everything, e.g. when the foreground app changes or the mode is turned off. */
    void reset() { changedAt = -1; firstChangedAt = -1; describedAt = -1; lastHash = 0; hasHash = false; }

    /** Picture-like widgets. Icon buttons are excluded; Compose nodes without a class name cannot be detected. */
    static boolean isImageClass(CharSequence className) {
        if (className == null) return false;
        String name = className.toString().toLowerCase(Locale.ROOT);
        if (name.contains("button")) return false;
        return name.contains("image") || name.contains("draweeview") || name.contains("photoview");
    }

    /** Missing or placeholder alt text; a real description is left to the screen reader. */
    static boolean isGenericLabel(CharSequence label) {
        if (label == null) return true;
        String text = label.toString().trim().toLowerCase(Locale.ROOT).replaceAll("[\\s，。,.:：]", "");
        if (text.isEmpty()) return true;
        for (String generic : GENERIC_LABELS) if (text.equals(generic)) return true;
        return false;
    }

    /** Roughly a tenth of the screen with no thin side, so thumbnails and banners are ignored. */
    static boolean isProminent(int width, int height, int screenWidth, int screenHeight) {
        if (width <= 0 || height <= 0 || screenWidth <= 0 || screenHeight <= 0) return false;
        double area = (double) width * height / ((double) screenWidth * screenHeight);
        int shortSide = Math.min(width, height);
        return area >= MIN_AREA_RATIO && shortSide >= MIN_SIDE_RATIO * Math.min(screenWidth, screenHeight);
    }

    /** @param luma HASH_WIDTH * HASH_HEIGHT row-major luminance samples, each 0..255 */
    static long differenceHash(int[] luma) {
        if (luma == null || luma.length != HASH_WIDTH * HASH_HEIGHT) throw new IllegalArgumentException("luma grid");
        long hash = 0;
        for (int row = 0; row < HASH_HEIGHT; row++) {
            for (int column = 0; column < HASH_WIDTH - 1; column++) {
                hash <<= 1;
                if (luma[row * HASH_WIDTH + column] < luma[row * HASH_WIDTH + column + 1]) hash |= 1;
            }
        }
        return hash;
    }

    static int hammingDistance(long first, long second) { return Long.bitCount(first ^ second); }
}
