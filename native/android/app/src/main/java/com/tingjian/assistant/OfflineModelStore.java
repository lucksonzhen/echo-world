package com.tingjian.assistant;

import android.content.Context;
import android.os.Looper;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Installs the bundled, checksum-pinned model without network access or shared storage. */
public final class OfflineModelStore {
    private static final String ASSET = "voice-model-cn.zip";
    private static final String ARCHIVE_ROOT = "vosk-model-small-cn-0.22/";
    private static final String MODEL_DIRECTORY = "vosk-cn-0.22";
    private static final String COMPLETE_FILE = ".complete.sha256";
    private static final String SHA256 = "3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba";
    private static final long ARCHIVE_BYTES = 43_898_754L;
    private static final long MAX_UNPACKED_BYTES = 256L * 1024 * 1024;
    private static final int MAX_ENTRIES = 256;
    private static final String[] REQUIRED_FILES = {
        "am/final.mdl", "conf/mfcc.conf", "conf/model.conf", "graph/HCLr.fst", "graph/Gr.fst"
    };

    private OfflineModelStore() {}

    /** Call from a worker thread before constructing org.vosk.Model. Safe to retry after failure. */
    public static synchronized File prepare(Context context) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IOException("离线语音模型必须在后台线程准备。");
        }
        Context app = context.getApplicationContext();
        File parent = app.getNoBackupFilesDir().getCanonicalFile();
        File destination = new File(parent, MODEL_DIRECTORY);
        File staging = new File(parent, MODEL_DIRECTORY + ".partial");
        requireInside(destination, parent);
        requireInside(staging, parent);
        if (isComplete(destination)) return destination;

        // Verify the APK asset before using its entries; do not trust a partial or
        // accidentally substituted developer download as a usable speech model.
        verifyArchive(app);
        removeOwnedTree(staging, parent);
        if (!staging.mkdirs() && !staging.isDirectory()) throw new IOException("无法创建离线语音模型目录。");
        try {
            extract(app, staging);
            if (!hasRequiredFiles(staging)) throw new IOException("离线语音模型文件不完整，请重新安装应用。");
            try (FileOutputStream marker = new FileOutputStream(new File(staging, COMPLETE_FILE))) {
                marker.write(SHA256.getBytes(StandardCharsets.US_ASCII));
                marker.getFD().sync();
            }
            removeOwnedTree(destination, parent);
            if (!staging.renameTo(destination)) throw new IOException("无法完成离线语音模型准备，请重试。");
            return destination;
        } catch (IOException error) {
            try { removeOwnedTree(staging, parent); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    private static void verifyArchive(Context context) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new IOException("系统不支持模型完整性检查。", error); }
        long bytes = 0;
        try (InputStream input = new BufferedInputStream(context.getAssets().open(ASSET))) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("语音模型准备已取消。");
                bytes += count;
                if (bytes > ARCHIVE_BYTES) throw new IOException("离线语音模型大小不正确，请重新安装应用。");
                digest.update(buffer, 0, count);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        if (bytes != ARCHIVE_BYTES || !SHA256.equals(hex.toString())) {
            throw new IOException("离线语音模型校验失败，请重新安装完整的应用。");
        }
    }

    private static void extract(Context context, File staging) throws IOException {
        long unpacked = 0;
        int count = 0;
        Set<String> names = new HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(context.getAssets().open(ASSET)))) {
            ZipEntry entry;
            byte[] buffer = new byte[32 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("语音模型准备已取消。");
                if (++count > MAX_ENTRIES) throw new IOException("语音模型文件数量超过限制。");
                String name = entry.getName();
                if (!name.startsWith(ARCHIVE_ROOT) || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0 || !names.add(name)) {
                    throw new IOException("语音模型压缩包包含无效路径。");
                }
                String relative = name.substring(ARCHIVE_ROOT.length());
                if (relative.isEmpty()) { zip.closeEntry(); continue; }
                if (relative.equals(COMPLETE_FILE)) throw new IOException("语音模型压缩包包含保留文件。");
                File target = new File(staging, relative);
                requireInside(target, staging);
                if (entry.isDirectory()) {
                    if (!target.mkdirs() && !target.isDirectory()) throw new IOException("无法创建模型子目录。");
                } else {
                    if (entry.getSize() > MAX_UNPACKED_BYTES) throw new IOException("语音模型解压大小超过限制。");
                    File directory = target.getParentFile();
                    if (directory == null || (!directory.mkdirs() && !directory.isDirectory())) throw new IOException("无法创建模型目录。");
                    try (BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(target))) {
                        int length;
                        while ((length = zip.read(buffer)) != -1) {
                            if (Thread.currentThread().isInterrupted()) throw new IOException("语音模型准备已取消。");
                            unpacked += length;
                            if (unpacked > MAX_UNPACKED_BYTES) throw new IOException("语音模型解压大小超过限制。");
                            output.write(buffer, 0, length);
                        }
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private static boolean hasRequiredFiles(File directory) {
        for (String relative : REQUIRED_FILES) {
            File file = new File(directory, relative);
            if (!file.isFile() || file.length() == 0) return false;
        }
        return true;
    }

    private static boolean isComplete(File directory) throws IOException {
        File marker = new File(directory, COMPLETE_FILE);
        if (!hasRequiredFiles(directory) || !marker.isFile() || marker.length() != SHA256.length()) return false;
        try (InputStream input = new FileInputStream(marker); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[128];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > 128) return false;
                bytes.write(buffer, 0, count);
            }
            return SHA256.equals(new String(bytes.toByteArray(), StandardCharsets.US_ASCII));
        }
    }

    private static void requireInside(File target, File parent) throws IOException {
        String root = parent.getCanonicalPath() + File.separator;
        if (!target.getCanonicalPath().startsWith(root)) throw new IOException("模型路径超出应用私有目录。");
    }

    private static void removeOwnedTree(File target, File parent) throws IOException {
        requireInside(target, parent);
        if (!target.exists()) return;
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children == null) throw new IOException("无法读取旧模型目录。");
            for (File child : children) removeOwnedTree(child, parent);
        }
        if (!target.delete()) throw new IOException("无法清理未完成的语音模型，请重试。");
    }
}
