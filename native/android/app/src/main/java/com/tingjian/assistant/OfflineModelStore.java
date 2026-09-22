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
    private static final String COMPLETE_FILE = ".complete.sha256";
    private static final int MAX_ENTRIES = 256;

    /**
     * One speech model the build may bundle into ASSET. prepare-voice-model.mjs packages the
     * large model by default (better accuracy, about 2.0 GiB unpacked and roughly 2 GiB of RAM
     * while recognizing); `--small` packages the small model for low-storage devices. The first
     * candidate whose checksum matches the bundled asset is used.
     */
    private static final class ModelDef {
        final String archiveRoot;
        final String directory;
        final String sha256;
        final long archiveBytes;
        final long maxUnpackedBytes;
        final String[] requiredFiles;
        ModelDef(String archiveRoot, String directory, String sha256,
                long archiveBytes, long maxUnpackedBytes, String[] requiredFiles) {
            this.archiveRoot = archiveRoot; this.directory = directory; this.sha256 = sha256;
            this.archiveBytes = archiveBytes; this.maxUnpackedBytes = maxUnpackedBytes;
            this.requiredFiles = requiredFiles;
        }
    }

    private static final ModelDef LARGE = new ModelDef(
            "vosk-model-cn-0.22/", "vosk-cn-0.22-large",
            "7f5580bf7ca3d9e8ce8be68337378950ed78ef974df7a7d4ead8dbe32bec2fa1", 1_358_736_686L,
            3072L * 1024 * 1024,
            new String[] { "am/final.mdl", "conf/mfcc.conf", "conf/model.conf", "graph/HCLG.fst", "ivector/final.ie" });
    private static final ModelDef SMALL = new ModelDef(
            "vosk-model-small-cn-0.22/", "vosk-cn-0.22",
            "3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba", 43_898_754L,
            256L * 1024 * 1024,
            new String[] { "am/final.mdl", "conf/mfcc.conf", "conf/model.conf", "graph/HCLr.fst", "graph/Gr.fst" });
    private static final ModelDef[] CANDIDATES = { LARGE, SMALL };

    private OfflineModelStore() {}

    /** Call from a worker thread before constructing org.vosk.Model. Safe to retry after failure. */
    public static synchronized File prepare(Context context) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IOException("离线语音模型必须在后台线程准备。");
        }
        Context app = context.getApplicationContext();
        File parent = app.getNoBackupFilesDir().getCanonicalFile();
        // Verify the APK asset before using its entries; do not trust a partial or
        // accidentally substituted developer download as a usable speech model.
        ModelDef model = null;
        for (ModelDef candidate : CANDIDATES) {
            if (verifyArchive(app, candidate)) { model = candidate; break; }
        }
        if (model == null) throw new IOException("离线语音模型校验失败，请重新安装完整的应用。");
        File destination = new File(parent, model.directory);
        File staging = new File(parent, model.directory + ".partial");
        requireInside(destination, parent);
        requireInside(staging, parent);
        if (!isComplete(destination, model)) {
            removeOwnedTree(staging, parent);
            if (!staging.mkdirs() && !staging.isDirectory()) throw new IOException("无法创建离线语音模型目录。");
            try {
                extract(app, staging, model);
                if (!hasRequiredFiles(staging, model)) throw new IOException("离线语音模型文件不完整，请重新安装应用。");
                try (FileOutputStream marker = new FileOutputStream(new File(staging, COMPLETE_FILE))) {
                    marker.write(model.sha256.getBytes(StandardCharsets.US_ASCII));
                    marker.getFD().sync();
                }
                removeOwnedTree(destination, parent);
                if (!staging.renameTo(destination)) throw new IOException("无法完成离线语音模型准备，请重试。");
            } catch (IOException error) {
                try { removeOwnedTree(staging, parent); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
                throw error;
            }
        }
        // Free the storage of the model variant this build no longer bundles.
        for (ModelDef other : CANDIDATES) {
            if (other == model) continue;
            try {
                removeOwnedTree(new File(parent, other.directory), parent);
                removeOwnedTree(new File(parent, other.directory + ".partial"), parent);
            } catch (IOException ignored) { }
        }
        return destination;
    }

    private static boolean verifyArchive(Context context, ModelDef model) throws IOException {
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
                if (bytes > model.archiveBytes) return false;
                digest.update(buffer, 0, count);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return bytes == model.archiveBytes && model.sha256.equals(hex.toString());
    }

    private static void extract(Context context, File staging, ModelDef model) throws IOException {
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
                if (!name.startsWith(model.archiveRoot) || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0 || !names.add(name)) {
                    throw new IOException("语音模型压缩包包含无效路径。");
                }
                String relative = name.substring(model.archiveRoot.length());
                if (relative.isEmpty()) { zip.closeEntry(); continue; }
                if (relative.equals(COMPLETE_FILE)) throw new IOException("语音模型压缩包包含保留文件。");
                File target = new File(staging, relative);
                requireInside(target, staging);
                if (entry.isDirectory()) {
                    if (!target.mkdirs() && !target.isDirectory()) throw new IOException("无法创建模型子目录。");
                } else {
                    if (entry.getSize() > model.maxUnpackedBytes) throw new IOException("语音模型解压大小超过限制。");
                    File directory = target.getParentFile();
                    if (directory == null || (!directory.mkdirs() && !directory.isDirectory())) throw new IOException("无法创建模型目录。");
                    try (BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(target))) {
                        int length;
                        while ((length = zip.read(buffer)) != -1) {
                            if (Thread.currentThread().isInterrupted()) throw new IOException("语音模型准备已取消。");
                            unpacked += length;
                            if (unpacked > model.maxUnpackedBytes) throw new IOException("语音模型解压大小超过限制。");
                            output.write(buffer, 0, length);
                        }
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private static boolean hasRequiredFiles(File directory, ModelDef model) {
        for (String relative : model.requiredFiles) {
            File file = new File(directory, relative);
            if (!file.isFile() || file.length() == 0) return false;
        }
        return true;
    }

    private static boolean isComplete(File directory, ModelDef model) throws IOException {
        File marker = new File(directory, COMPLETE_FILE);
        if (!hasRequiredFiles(directory, model) || !marker.isFile() || marker.length() != model.sha256.length()) return false;
        try (InputStream input = new FileInputStream(marker); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[128];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > 128) return false;
                bytes.write(buffer, 0, count);
            }
            return model.sha256.equals(new String(bytes.toByteArray(), StandardCharsets.US_ASCII));
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
