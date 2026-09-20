package com.tingjian.assistant;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Requests contain memory-only JPEG frames. No media URLs, files, or provider keys. */
public final class AssistantApi {
    public interface Callback { void onSuccess(String narration); void onFailure(String error); }
    private final SettingsStore settings;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicInteger generation = new AtomicInteger();
    private volatile HttpURLConnection active;
    public AssistantApi(Context context) { settings = new SettingsStore(context); }
    public void cancel() { generation.incrementAndGet(); HttpURLConnection connection = active; if (connection != null) connection.disconnect(); }
    public void close() { cancel(); io.shutdownNow(); }

    public void describe(List<ScreenFrame> source, boolean video, String mode, String question, int durationMs, Callback callback) {
        cancel();
        int id = generation.get();
        List<ScreenFrame> frames = new ArrayList<>(source);
        io.execute(() -> {
            HttpURLConnection connection = null;
            try {
                if (id != generation.get()) return;
                SettingsStore.ConnectionSnapshot snapshot = settings.snapshot();
                if (!snapshot.consentGranted) throw new IllegalStateException("请先在听见屏幕中确认屏幕识别说明。");
                if (snapshot.serverUrl.isEmpty()) throw new IllegalStateException("请先打开听见屏幕，设置描述服务地址。");
                JSONObject body = new JSONObject().put("mediaType", video ? "video" : "image").put("mode", mode);
                JSONArray images = new JSONArray();
                for (ScreenFrame frame : frames) images.put(new JSONObject().put("dataUrl", frame.dataUrl).put("timestampMs", frame.timestampMs));
                body.put("frames", images);
                if (video) body.put("durationMs", durationMs);
                if (question != null && !question.trim().isEmpty()) body.put("question", question);
                connection = open("/api/describe", snapshot); active = connection;
                if (id != generation.get()) return;
                connection.setRequestMethod("POST"); connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                byte[] encoded = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(encoded.length);
                try (java.io.OutputStream out = connection.getOutputStream()) { out.write(encoded); }
                int status = connection.getResponseCode();
                JSONObject result = read(connection, status);
                if (status < 200 || status >= 300) throw new IllegalStateException(result.optString("error", "描述服务暂时不可用，请稍后重试。"));
                String speech = buildNarration(result, video, mode, question);
                main.post(() -> { if (id == generation.get()) callback.onSuccess(speech); });
            } catch (Exception error) {
                String message = error instanceof IllegalStateException ? error.getMessage() : "无法完成描述，请检查网络和服务地址后重试。";
                main.post(() -> { if (id == generation.get()) callback.onFailure(message); });
            } finally {
                frames.clear();
                if (connection != null) connection.disconnect();
                if (active == connection) active = null;
            }
        });
    }

    public void check(Callback callback) {
        cancel(); int id = generation.get();
        io.execute(() -> {
            HttpURLConnection connection = null;
            try {
                if (id != generation.get()) return;
                SettingsStore.ConnectionSnapshot snapshot = settings.snapshot();
                connection = open("/api/health", snapshot); active = connection;
                if (id != generation.get()) return;
                int status = connection.getResponseCode(); JSONObject response = read(connection, status);
                if (status != 200) throw new IllegalStateException(response.optString("error", "无法连接服务。"));
                if (!"ok".equals(response.optString("status"))) throw new IllegalStateException("此地址不是听见屏幕的描述服务。");
                String message = response.optBoolean("configured") ? "连接成功，描述服务已配置。" : "服务已连接，识别功能尚未开通，请联系服务提供方完成配置。";
                main.post(() -> { if (id == generation.get()) callback.onSuccess(message); });
            } catch (Exception error) {
                String message = error instanceof IllegalStateException ? error.getMessage() : "连接失败，请检查服务地址、访问口令和网络。";
                main.post(() -> { if (id == generation.get()) callback.onFailure(message); });
            } finally { if (connection != null) connection.disconnect(); if (active == connection) active = null; }
        });
    }

    private HttpURLConnection open(String path, SettingsStore.ConnectionSnapshot snapshot) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(snapshot.serverUrl + path).openConnection();
        connection.setConnectTimeout(10000); connection.setReadTimeout(70000);
        connection.setInstanceFollowRedirects(false);
        String token = snapshot.accessToken;
        if (!token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
        return connection;
    }
    private static JSONObject read(HttpURLConnection connection, int status) throws Exception {
        try (InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            if (input == null) throw new IllegalStateException("服务没有返回内容，请重试。");
            byte[] buffer = new byte[4096]; int length;
            while ((length = input.read(buffer)) != -1) {
                if (bytes.size() + length > 256 * 1024) throw new IllegalStateException("服务响应过长，请重试。");
                bytes.write(buffer, 0, length);
            }
            return new JSONObject(bytes.toString("UTF-8"));
        }
    }
    private static String buildNarration(JSONObject result, boolean video, String mode, String question) throws Exception {
        String summary = result.optString("summary", "").trim();
        if (summary.isEmpty()) throw new IllegalStateException("收到的描述不完整，请重试。");
        StringBuilder text = new StringBuilder();
        if (video) text.append("这是刚才约八秒内的抽样画面，不包含视频声音，可能遗漏短暂动作。");
        if (question != null && !question.isEmpty() && !result.isNull("answer")) {
            text.append(result.optString("answer", summary));
        } else if ("text".equals(mode)) {
            JSONArray words = result.optJSONArray("visibleText");
            if (words == null || words.length() == 0) text.append("没有识别到清晰可读的文字。");
            else { text.append("屏幕中的文字。"); appendArray(text, words); }
        } else {
            text.append(result.optString("title", "")).append("。").append(summary).append("。");
            if ("detailed".equals(mode)) { appendArray(text, result.optJSONArray("details")); appendArray(text, result.optJSONArray("visibleText")); }
            JSONArray timeline = result.optJSONArray("timeline");
            if (timeline != null) for (int i = 0; i < timeline.length(); i++) {
                JSONObject item = timeline.getJSONObject(i);
                text.append(item.optInt("timestampMs") / 1000).append("秒，").append(item.optString("description")).append("。");
            }
        }
        appendArray(text, result.optJSONArray("uncertainties"));
        return text.toString();
    }
    private static void appendArray(StringBuilder output, JSONArray values) {
        if (values == null) return;
        for (int i = 0; i < values.length(); i++) output.append(values.optString(i)).append("。");
    }
}
