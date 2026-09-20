package com.tingjian.assistant;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.util.Base64;

import java.io.ByteArrayOutputStream;

/** Converts one system screenshot to an in-memory JPEG and always releases its hardware buffer. */
final class ScreenshotEncoder {
    private ScreenshotEncoder() {}

    static String encode(AccessibilityService.ScreenshotResult result) {
        HardwareBuffer buffer = result.getHardwareBuffer();
        Bitmap hardware = null;
        Bitmap pixels = null;
        Bitmap scaled = null;
        try {
            hardware = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
            if (hardware == null) throw new IllegalStateException("无法读取当前屏幕画面。");
            pixels = hardware.copy(Bitmap.Config.ARGB_8888, false);
            if (pixels == null) throw new IllegalStateException("无法读取当前屏幕画面。");
            float scale = Math.min(1f, 1280f / Math.max(pixels.getWidth(), pixels.getHeight()));
            int width = Math.max(1, Math.round(pixels.getWidth() * scale));
            int height = Math.max(1, Math.round(pixels.getHeight() * scale));
            scaled = Bitmap.createScaledBitmap(pixels, width, height, true);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, 75, bytes)) {
                throw new IllegalStateException("画面压缩失败，请稍后重试。");
            }
            return "data:image/jpeg;base64," + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP);
        } finally {
            if (scaled != null && scaled != pixels && scaled != hardware) scaled.recycle();
            if (pixels != null && pixels != hardware) pixels.recycle();
            if (hardware != null) hardware.recycle();
            buffer.close();
        }
    }

    static void discard(AccessibilityService.ScreenshotResult result) {
        result.getHardwareBuffer().close();
    }
}
