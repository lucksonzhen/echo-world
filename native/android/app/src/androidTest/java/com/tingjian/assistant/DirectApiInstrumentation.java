package com.tingjian.assistant;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.SharedPreferences;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the real Android JSON/Keystore/client code; transport is deterministic and offline. */
public final class DirectApiInstrumentation extends Instrumentation {
    private int passed;
    private final StringBuilder report = new StringBuilder();
    private static final String KEY = "fixture-key-never-a-real-secret";
    private static final String PNG = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aXioAAAAASUVORK5CYII=";
    private final List<ScreenFrame> frames = Collections.singletonList(new ScreenFrame(PNG, 0));
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() { new Thread(this::run, "direct-api-tests").start(); }
    private interface Checked { void run() throws Exception; }
    private void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        passed++; report.append("PASS ").append(name).append('\n');
    }
    private void rejects(Checked action, String name) throws Exception {
        try { action.run(); } catch (IllegalStateException expected) { check(true, name); return; }
        throw new AssertionError(name);
    }
    private JSONObject description() throws Exception {
        return new JSONObject().put("title", "测试几何图形").put("summary", "白色背景上有蓝色方块和红色圆形")
                .put("details", new JSONArray()).put("visibleText", new JSONArray()).put("timeline", new JSONArray())
                .put("uncertainties", new JSONArray()).put("answer", JSONObject.NULL);
    }
    private JSONObject envelope(String provider, JSONObject description) throws Exception {
        if ("gemini".equals(provider)) return new JSONObject().put("candidates", new JSONArray().put(new JSONObject()
                .put("finishReason", "STOP").put("content", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", description.toString()))))));
        return new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("finish_reason", "stop")
                .put("message", new JSONObject().put("content", description.toString()))));
    }
    private static final class FakeConnection extends HttpURLConnection {
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        final byte[] response;
        final int status;
        final CountDownLatch reached = new CountDownLatch(1), gate = new CountDownLatch(1);
        boolean block;
        FakeConnection(URL url, int status, String response) { super(url); this.status = status; this.response = response.getBytes(StandardCharsets.UTF_8); }
        @Override public void connect() { }
        @Override public void disconnect() { gate.countDown(); }
        @Override public boolean usingProxy() { return false; }
        @Override public OutputStream getOutputStream() { return body; }
        @Override public int getResponseCode() {
            reached.countDown();
            if (block) try { gate.await(5, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            return status;
        }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(response); }
    }
    private static final class Reply implements AssistantApi.Callback {
        final CountDownLatch done = new CountDownLatch(1);
        volatile String success, error;
        public void onSuccess(String text) { success = text; done.countDown(); }
        public void onFailure(String text) { error = text; done.countDown(); }
        void await() throws Exception { if (!done.await(8, TimeUnit.SECONDS)) throw new AssertionError("Callback timeout"); }
    }
    private void save(SettingsStore settings, String provider, boolean consent) throws Exception {
        settings.saveConnection(provider, "compatible".equals(provider) ? "https://vision.example.com/v1" : DirectApiConfig.defaultUrl(provider),
                "compatible".equals(provider) ? "vision-model" : DirectApiConfig.defaultModel(provider), KEY, consent);
    }
    private void run() {
        Bundle summary = new Bundle();
        try {
            SharedPreferences prefs = getTargetContext().getSharedPreferences("screen_assistant", 0);
            prefs.edit().clear().putString("server_url", "http://old-computer:8787").putBoolean("screen_consent", true)
                    .putString("token_encrypted", "legacy-placeholder").commit();
            SettingsStore settings = new SettingsStore(getTargetContext());
            check("gemini".equals(settings.getProvider()), "upgrade defaults to Gemini");
            check(settings.getServerUrl().equals(DirectApiConfig.defaultUrl("gemini")), "legacy computer URL not used as API URL");
            check(settings.getAccessToken().isEmpty(), "legacy access token not reused as API key");
            check(!settings.isConsentGranted(), "upgrade requires fresh direct-upload consent");
            save(settings, "gemini", true);
            check(KEY.equals(new SettingsStore(getTargetContext()).getAccessToken()), "Keystore key survives settings reload");
            check(!prefs.getAll().toString().contains(KEY), "preferences contain no plaintext API key");

            for (String provider : new String[]{"gemini", "openai", "compatible"}) {
                JSONObject request = DirectModelProtocol.request(provider, "vision-model", "仅描述画面", frames, false, "brief", null, 0);
                check(!request.toString().contains(KEY), provider + " key excluded from JSON body");
                if ("gemini".equals(provider)) {
                    JSONObject image = request.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(2).getJSONObject("inlineData");
                    check("image/png".equals(image.getString("mimeType")) && !image.getString("data").startsWith("data:"), "Gemini inline image format");
                    check(!request.has("store") && "application/json".equals(request.getJSONObject("generationConfig").getString("responseMimeType")), "Gemini supported generation fields");
                } else check(request.getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(2).getJSONObject("image_url").getString("url").equals(PNG), provider + " image URL format");
                JSONObject result = DirectModelProtocol.result(provider, envelope(provider, description()), frames, false, null);
                check(result.getString("summary").contains("方块"), provider + " valid response parsed");
                save(settings, provider, true);
                AtomicReference<FakeConnection> observed = new AtomicReference<>();
                AssistantApi client = new AssistantApi(getTargetContext(), url -> {
                    FakeConnection fake = new FakeConnection(url, 200, envelope(provider, description()).toString()); observed.set(fake); return fake;
                });
                try {
                    Reply reply = new Reply(); client.describe(frames, false, "brief", null, 0, reply); reply.await();
                    FakeConnection fake = observed.get();
                    check(reply.error == null && reply.success.contains("方块"), provider + " client narration completes");
                    check("POST".equals(fake.getRequestMethod()) && !fake.getInstanceFollowRedirects(), provider + " POST with redirects disabled");
                    check(!fake.getURL().toString().contains(KEY) && "https".equals(fake.getURL().getProtocol()), provider + " HTTPS URL without key");
                    check(("gemini".equals(provider) ? KEY : "Bearer " + KEY).equals(fake.getRequestProperty("gemini".equals(provider) ? "x-goog-api-key" : "Authorization")), provider + " correct authentication header");
                    check(!fake.body.toString("UTF-8").contains(KEY), provider + " transport body excludes key");
                } finally { client.close(); }
            }
            JSONObject truncated = envelope("gemini", description()); truncated.getJSONArray("candidates").getJSONObject(0).put("finishReason", "MAX_TOKENS");
            rejects(() -> DirectModelProtocol.result("gemini", truncated, frames, false, null), "truncated Gemini answer rejected");
            JSONObject refused = envelope("openai", description()); refused.getJSONArray("choices").getJSONObject(0).getJSONObject("message").put("refusal", "not allowed");
            rejects(() -> DirectModelProtocol.result("openai", refused, frames, false, null), "refused answer not narrated");
            JSONObject bad = description().put("details", new JSONArray().put(123));
            rejects(() -> DirectModelProtocol.validateResult(bad, frames, false, null), "invalid field types rejected");
            JSONObject hallucinated = description().put("timeline", new JSONArray().put(new JSONObject().put("timestampMs", 999).put("description", "不存在的时间")));
            rejects(() -> DirectModelProtocol.validateResult(hallucinated, Arrays.asList(new ScreenFrame(PNG, 0), new ScreenFrame(PNG, 1000)), true, null), "invented video timestamp rejected");
            rejects(() -> DirectModelProtocol.validateResult(hallucinated, frames, false, null), "image timeline rejected");
            JSONObject answer = description().put("answer", "无关回答"); DirectModelProtocol.validateResult(answer, frames, false, null);
            check(answer.isNull("answer"), "unsolicited answer discarded");

            save(settings, "gemini", true);
            for (int status : new int[]{401, 429, 302, 500}) {
                AtomicInteger opened = new AtomicInteger();
                AssistantApi client = new AssistantApi(getTargetContext(), url -> { opened.incrementAndGet(); return new FakeConnection(url, status, "secret-from-upstream-" + KEY); });
                try {
                    Reply reply = new Reply(); client.describe(frames, false, "brief", null, 0, reply); reply.await();
                    check(reply.success == null && reply.error != null && !reply.error.contains(KEY) && !reply.error.contains("upstream"), "HTTP " + status + " error body redacted");
                    check(opened.get() == 1, "HTTP " + status + " no automatic retry or redirect");
                } finally { client.close(); }
            }
            settings.revokeConsent();
            AtomicInteger connections = new AtomicInteger();
            AssistantApi noConsent = new AssistantApi(getTargetContext(), url -> { connections.incrementAndGet(); return new FakeConnection(url, 200, envelope("gemini", description()).toString()); });
            try {
                Reply reply = new Reply(); noConsent.describe(frames, false, "brief", null, 0, reply); reply.await();
                check(reply.error != null && connections.get() == 0, "no consent means no screenshot request");
                Reply test = new Reply(); noConsent.check(test); test.await();
                check(test.error == null && test.success.contains("测试成功"), "generated-image connection test needs no screenshot permission");
            } finally { noConsent.close(); }
            save(settings, "gemini", true);
            AtomicReference<FakeConnection> pending = new AtomicReference<>();
            CountDownLatch created = new CountDownLatch(1);
            AssistantApi cancellable = new AssistantApi(getTargetContext(), url -> {
                FakeConnection fake = new FakeConnection(url, 200, envelope("gemini", description()).toString()); fake.block = true; pending.set(fake); created.countDown(); return fake;
            });
            try {
                Reply reply = new Reply(); cancellable.describe(frames, false, "brief", null, 0, reply);
                check(created.await(5, TimeUnit.SECONDS) && pending.get().reached.await(5, TimeUnit.SECONDS), "cancellation fixture entered transport");
                cancellable.cancel();
                check(!reply.done.await(700, TimeUnit.MILLISECONDS), "cancelled response cannot reach narration callback");
            } finally { cancellable.close(); }
            settings.clearCredential();
            check(settings.getAccessToken().isEmpty() && !settings.isConfigured() && !settings.isConsentGranted(), "clear key disables direct requests and consent");
            prefs.edit().clear().commit();
            summary.putInt("passed", passed); summary.putInt("failed", 0); summary.putString("stream", report.toString());
            finish(Activity.RESULT_OK, summary);
        } catch (Throwable error) {
            summary.putInt("passed", passed); summary.putInt("failed", 1); summary.putString("stream", report + "FAIL " + error.getClass().getSimpleName() + ": " + error.getMessage());
            finish(Activity.RESULT_CANCELED, summary);
        }
    }
}
