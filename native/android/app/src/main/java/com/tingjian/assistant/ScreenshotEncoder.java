package com.tingjian.assistant;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.util.Base64;

import java.io.ByteArrayOutputStream;

/** Converts one system screenshot to an in-memory JPEG and always releases its hardware buffer. */
final class ScreenshotEncoder {
    /** Below this many pixels on a side, a crop rectangle is treated as invalid and the full screen is used. */
    private static final int MIN_CROP_SIDE = 32;

    /** One encoded frame plus a perceptual fingerprint used to skip already described pictures. */
    static final class Encoded {
        final String dataUrl;
        final long hash;
        private Encoded(String dataUrl, long hash) { this.dataUrl = dataUrl; this.hash = hash; }
    }

    private ScreenshotEncoder() {}

    static String encode(AccessibilityService.ScreenshotResult result) {
        return encode(result, null).dataUrl;
    }

    /** @param crop screen rectangle to keep, or null for the whole screen */
    static Encoded encode(AccessibilityService.ScreenshotResult result, Rect crop) {
        HardwareBuffer buffer = result.getHardwareBuffer();
        Bitmap hardware = null;
        Bitmap pixels = null;
        Bitmap region = null;
        Bitmap scaled = null;
        Bitmap tiny = null;
        try {
            hardware = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
            if (hardware == null) throw new IllegalStateException("无法读取当前屏幕画面。");
            pixels = hardware.copy(Bitmap.Config.ARGB_8888, false);
            if (pixels == null) throw new IllegalStateException("无法读取当前屏幕画面。");
            region = pixels;
            Rect clipped = clip(crop, pixels.getWidth(), pixels.getHeight());
            if (clipped != null) region = Bitmap.createBitmap(pixels, clipped.left, clipped.top, clipped.width(), clipped.height());
            float scale = Math.min(1f, 1280f / Math.max(region.getWidth(), region.getHeight()));
            int width = Math.max(1, Math.round(region.getWidth() * scale));
            int height = Math.max(1, Math.round(region.getHeight() * scale));
            scaled = Bitmap.createScaledBitmap(region, width, height, true);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, 75, bytes)) {
                throw new IllegalStateException("画面压缩失败，请稍后重试。");
            }
            tiny = Bitmap.createScaledBitmap(region, ImageWatchPolicy.HASH_WIDTH, ImageWatchPolicy.HASH_HEIGHT, true);
            return new Encoded("data:image/jpeg;base64," + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP), fingerprint(tiny));
        } finally {
            if (tiny != null && tiny != region && tiny != pixels && tiny != hardware) tiny.recycle();
            if (scaled != null && scaled != region && scaled != pixels && scaled != hardware) scaled.recycle();
            if (region != null && region != pixels && region != hardware) region.recycle();
            if (pixels != null && pixels != hardware) pixels.recycle();
            if (hardware != null) hardware.recycle();
            buffer.close();
        }
    }

    static void discard(AccessibilityService.ScreenshotResult result) {
        result.getHardwareBuffer().close();
    }

    private static Rect clip(Rect crop, int width, int height) {
        if (crop == null) return null;
        Rect clipped = new Rect(crop);
        if (!clipped.intersect(0, 0, width, height)) return null;
        if (clipped.width() < MIN_CROP_SIDE || clipped.height() < MIN_CROP_SIDE) return null;
        if (clipped.width() == width && clipped.height() == height) return null;
        return clipped;
    }

    private static long fingerprint(Bitmap tiny) {
        int[] argb = new int[ImageWatchPolicy.HASH_WIDTH * ImageWatchPolicy.HASH_HEIGHT];
        tiny.getPixels(argb, 0, ImageWatchPolicy.HASH_WIDTH, 0, 0, ImageWatchPolicy.HASH_WIDTH, ImageWatchPolicy.HASH_HEIGHT);
        int[] luma = new int[argb.length];
        for (int index = 0; index < argb.length; index++) {
            int color = argb[index];
            luma[index] = (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000;
        }
        return ImageWatchPolicy.differenceHash(luma);
    }
}
