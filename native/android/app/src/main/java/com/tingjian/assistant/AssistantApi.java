package com.tingjian.assistant;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
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

/** Direct provider requests use the owner's encrypted local key; screenshots remain memory-only. */
public final class AssistantApi {
    public interface Callback { void onSuccess(String narration); void onFailure(String error); }
    interface ConnectionFactory { HttpURLConnection open(URL url) throws Exception; }
    private final Context context;
    private final SettingsStore settings;
    private final ConnectionFactory connections;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicInteger generation = new AtomicInteger();
    private volatile HttpURLConnection active;
    public AssistantApi(Context context) { this(context, url -> (HttpURLConnection) url.openConnection()); }
    AssistantApi(Context context, ConnectionFactory connections) {
        this.context = context.getApplicationContext(); settings = new SettingsStore(context); this.connections = connections;
    }
    public void cancel() { generation.incrementAndGet(); HttpURLConnection connection = active; if (connection != null) connection.disconnect(); }
    public void close() { cancel(); main.removeCallbacksAndMessages(null); io.shutdownNow(); }

    public void describe(List<ScreenFrame> source, boolean video, String mode, String question, int durationMs, Callback callback) {
        request(new ArrayList<>(source), video, mode, question, durationMs, false, callback);
    }
    /** Sends a generated geometric test image, never the user's screen. This may incur an API charge. */
    public void check(Callback callback) { request(new ArrayList<>(), false, "brief", null, 0, true, callback); }
    private void request(List<ScreenFrame> frames, boolean video, String mode, String question, int durationMs, boolean test, Callback callback) {
        cancel();
        int id = generation.get();
        io.execute(() -> {
            HttpURLConnection connection = null;
            Runnable deadline = () -> {
                if (id == generation.get()) { cancel(); callback.onFailure("请求超时，请检查手机网络和 API 地址后重试。"); }
            };
            main.postDelayed(deadline, 90000);
            try {
                if (id != generation.get()) return;
                SettingsStore.ConnectionSnapshot snapshot = settings.snapshot();
                if (!test && !snapshot.consentGranted) throw new IllegalStateException("请先在听见世界中确认屏幕识别说明。");
                boolean debug = (context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
                String base = DirectApiConfig.validateBaseUrl(snapshot.provider, snapshot.serverUrl, debug);
                String model = DirectApiConfig.validateModel(snapshot.provider, snapshot.model);
                DirectApiConfig.validateKey(snapshot.provider, snapshot.accessToken);
                if (test && !snapshot.isDirect()) {
                    connection = open(base + "/api/health", snapshot); active = connection;
                    if (id != generation.get()) return;
                    int status = connection.getResponseCode();
                    if (status != 200) throw new IllegalStateException(DirectModelProtocol.httpError(status));
                    JSONObject result = read(connection);
                    if (!"ok".equals(result.optString("status"))) throw new IllegalStateException("此地址不是听见世界描述服务。");
                    String message = result.optBoolean("configured") ? "中转服务已连接；实际图片识别仍需测试。" : "中转服务未配置 AI 密钥。";
                    main.post(() -> { if (id == generation.get()) callback.onSuccess(message); });
                    return;
                }
                if (test) frames.add(testFrame());
                JSONObject body;
                if (snapshot.isDirect()) {
                    String instructions;
                    try (InputStream input = context.getAssets().open("description-instructions.txt")) {
                        instructions = new String(readBytes(input, 32768), StandardCharsets.UTF_8);
                    }
                    body = DirectModelProtocol.request(snapshot.provider, model, instructions, frames, video, mode, question, durationMs);
                } else {
                    body = new JSONObject().put("mediaType", video ? "video" : "image").put("mode", mode);
                    JSONArray images = new JSONArray();
                    for (ScreenFrame frame : frames) images.put(new JSONObject().put("dataUrl", frame.dataUrl).put("timestampMs", frame.timestampMs));
                    body.put("frames", images);
                    if (video) body.put("durationMs", durationMs);
                    if (question != null && !question.trim().isEmpty()) body.put("question", question);
                }
                connection = open(DirectApiConfig.requestUrl(snapshot.provider, base, model), snapshot); active = connection;
                if (id != generation.get() || (!test && !settings.isConsentGranted())) return;
                connection.setRequestMethod("POST"); connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] encoded = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(encoded.length);
                try (java.io.OutputStream out = connection.getOutputStream()) {
                    for (int offset = 0; offset < encoded.length; offset += 65536) {
                        if (id != generation.get() || (!test && !settings.isConsentGranted())) return;
                        out.write(encoded, offset, Math.min(65536, encoded.length - offset));
                    }
                }
                int status = connection.getResponseCode();
                // Never relay upstream error bodies: gateways may echo credentials or image data.
                if (status < 200 || status >= 300) throw new IllegalStateException(DirectModelProtocol.httpError(status));
                JSONObject result = read(connection);
                if (snapshot.isDirect()) result = DirectModelProtocol.result(snapshot.provider, result, frames, video, question);
                else DirectModelProtocol.validateResult(result, frames, video, question);
                String speech = buildNarration(result, video, mode, question);
                String message = test ? "手机直连识图测试成功。测试图描述：" + speech : speech;
                main.post(() -> { if (id == generation.get() && (test || settings.isConsentGranted())) callback.onSuccess(message); });
            } catch (Exception error) {
                String message = error instanceof IllegalStateException || error instanceof IllegalArgumentException
                        ? error.getMessage() : "无法完成请求，请检查手机能否访问所选 API、网络和模型设置。";
                main.post(() -> { if (id == generation.get()) callback.onFailure(message); });
            } finally {
                main.removeCallbacks(deadline); frames.clear();
                if (connection != null) connection.disconnect();
                if (active == connection) active = null;
            }
        });
    }
    private HttpURLConnection open(String endpoint, SettingsStore.ConnectionSnapshot snapshot) throws Exception {
        HttpURLConnection connection = connections.open(new URL(endpoint));
        connection.setConnectTimeout(15000); connection.setReadTimeout(70000);
        connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
        if (!snapshot.accessToken.isEmpty()) {
            if ("gemini".equals(snapshot.provider)) connection.setRequestProperty("x-goog-api-key", snapshot.accessToken);
            else connection.setRequestProperty("Authorization", "Bearer " + snapshot.accessToken);
        }
        return connection;
    }
    private static JSONObject read(HttpURLConnection connection) throws Exception {
        try (InputStream input = connection.getInputStream()) {
            if (input == null) throw new IllegalStateException("模型服务没有返回内容，请重试。");
            return new JSONObject(new String(readBytes(input, 256 * 1024), StandardCharsets.UTF_8));
        }
    }
    private static byte[] readBytes(InputStream input, int limit) throws Exception {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int length;
            while ((length = input.read(buffer)) != -1) {
                if (bytes.size() + length > limit) throw new IllegalStateException("模型服务响应过长，请重试。");
                bytes.write(buffer, 0, length);
            }
            return bytes.toByteArray();
        }
    }
    private static ScreenFrame testFrame() {
        Bitmap bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            Canvas canvas = new Canvas(bitmap); canvas.drawColor(Color.WHITE);
            Paint paint = new Paint(); paint.setColor(Color.BLUE); canvas.drawRect(12, 12, 60, 60, paint);
            paint.setColor(Color.RED); canvas.drawCircle(92, 92, 24, paint);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes);
            return new ScreenFrame("data:image/png;base64," + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP), 0);
        } catch (java.io.IOException error) { throw new IllegalStateException("无法创建测试图片，请重试。"); }
        finally { bitmap.recycle(); }
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
