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
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(response); }
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
    private JSONObject contentEnvelope(Object content,String finish) throws Exception {
        return new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("finish_reason",finish)
                .put("message",new JSONObject().put("content",content))));
    }
    private void rejectsWithCode(JSONObject envelope,String code,String name) throws Exception {
        try { DirectModelProtocol.result("deepseek",envelope,frames,false,null); }
        catch(IllegalStateException expected) {
            check(expected.getMessage().contains(code) && !expected.getMessage().contains(KEY)
                    && !expected.getMessage().contains("请选择支持图片"),name); return;
        }
        throw new AssertionError(name);
    }
    private void deepseekCompatibility(SettingsStore settings) throws Exception {
        for(Object blank:new Object[]{JSONObject.NULL,"", "  "}) {
            JSONObject minimal=new JSONObject().put("summary","桌上有一个蓝色杯子");
            for(String key:new String[]{"title","details","visibleText","uncertainties","timeline","answer"}) minimal.put(key,blank);
            JSONObject parsed=DirectModelProtocol.result("deepseek",envelope("deepseek",minimal),frames,false,null);
            check(parsed.getString("summary").equals("桌上有一个蓝色杯子") && parsed.getJSONArray("details").length()==0
                    && parsed.getJSONArray("timeline").length()==0 && parsed.isNull("answer"),"DeepSeek accepts empty optional fields variant "+blank.toString().length());
        }
        JSONObject minimal=new JSONObject().put("summary","桌上有一个蓝色杯子");
        JSONObject parsed=DirectModelProtocol.result("deepseek",envelope("deepseek",minimal),frames,false,null);
        check(parsed.getString("title").equals("画面描述") && parsed.getJSONArray("uncertainties").length()==0,"DeepSeek preserves summary when optional fields are omitted");
        JSONObject emptyAnswer=description().put("answer","");
        check(DirectModelProtocol.result("deepseek",envelope("deepseek",emptyAnswer),frames,false,null).isNull("answer"),"DeepSeek empty unused answer does not reject a valid description");
        JSONObject wrapped=contentEnvelope("\uFEFF```JSON\r\n"+description()+"\r\n```","stop");
        check(DirectModelProtocol.result("deepseek",wrapped,frames,false,null).getString("summary").contains("方块"),"DeepSeek BOM and CRLF code fence accepted without taking surrounding prose");
        rejectsWithCode(contentEnvelope("", "stop"),"R01","empty DeepSeek output has a distinct safe diagnosis");
        rejectsWithCode(contentEnvelope(JSONObject.NULL,"stop"),"R01","null DeepSeek output has empty response diagnosis");
        rejectsWithCode(contentEnvelope("not json "+KEY,"stop"),"R23","non JSON response diagnosis never echoes upstream text");
        rejectsWithCode(contentEnvelope(description()+" extra "+KEY,"stop"),"R23","trailing prose cannot silently pass JSON parsing");
        rejectsWithCode(contentEnvelope(description().toString(),"length"),"R06","truncated DeepSeek output is rejected even when JSON is complete");
        rejectsWithCode(envelope("deepseek",new JSONObject()),"R03","normalization never invents a missing summary");
        rejectsWithCode(envelope("deepseek",description().put("summary","")),"R03","empty summary remains an error");
        rejectsWithCode(envelope("deepseek",description().put("details",new JSONArray().put(42))),"R04","DeepSeek non text detail entries remain rejected");
        rejectsWithCode(envelope("deepseek",description().put("visibleText","not an array")),"R04","DeepSeek nonempty mistyped optional field remains rejected");
        rejectsWithCode(envelope("deepseek",description().put("timeline",new JSONArray().put(new JSONObject().put("timestampMs",0).put("description","帧")))),"R04","DeepSeek image response cannot carry a video timeline");
        rejectsWithCode(new JSONObject().put("choices",new JSONArray().put(new JSONObject())),"R00","malformed envelope is distinguished from network failure");
        JSONObject refusal=contentEnvelope(description().toString(),"stop"); refusal.getJSONArray("choices").getJSONObject(0).getJSONObject("message").put("refusal","refused");
        rejects(()->DirectModelProtocol.result("deepseek",refusal,frames,false,null),"DeepSeek refusal cannot be normalized into a description");
        JSONObject video=description().put("timeline",new JSONArray().put(new JSONObject().put("timestampMs",99).put("description","帧")));
        rejects(()->DirectModelProtocol.result("deepseek",envelope("deepseek",video),frames,true,null),"DeepSeek fabricated timestamps remain rejected");
        save(settings,"deepseek",true);
        AtomicInteger calls=new AtomicInteger();
        AssistantApi client=new AssistantApi(getTargetContext(),url->{ calls.incrementAndGet(); return new FakeConnection(url,200,envelope("deepseek",minimal).toString()); });
        try {
            Reply reply=new Reply(); client.describe(frames,false,"brief",null,0,reply); reply.await();
            check(reply.error==null && reply.success.contains("蓝色杯子") && calls.get()==1,"DeepSeek minimal valid description reaches narration in one request");
        } finally { client.close(); }
    }
    private void deepseekTextResponses(SettingsStore settings) throws Exception {
        String prose="画面中央是一只蓝色杯子，放在浅色桌面上。背景是一面白墙。";
        JSONObject reply=contentEnvelope(prose,"stop");
        JSONObject parsed=DirectModelProtocol.result("deepseek",reply,frames,false,null,"brief");
        check(parsed.getString("summary").equals(prose) && parsed.getJSONArray("timeline").length()==0,"DeepSeek complete Chinese screen prose is preserved verbatim without fabricated timeline");
        check(DirectModelProtocol.result("deepseek",reply,frames,false,null,"detailed").getString("summary").equals(prose),"DeepSeek detailed static description accepts prose");
        rejects(()->DirectModelProtocol.result("deepseek",reply,frames,true,null,"brief"),"video cannot fall back to unstructured prose");
        rejects(()->DirectModelProtocol.result("deepseek",reply,frames,false,null,"text"),"OCR cannot label unstructured prose as recognized text");
        rejects(()->DirectModelProtocol.result("deepseek",reply,frames,false,"杯子什么颜色","brief"),"question answer keeps its structured contract");
        rejects(()->DirectModelProtocol.result("gemini",envelope("gemini",description()).put("error",true),frames,false,null),"other providers retain refusal and envelope checks");
        rejects(()->DirectModelProtocol.result("openai",reply,frames,false,null),"OpenAI does not use DeepSeek prose compatibility");
        rejectsWithCode(contentEnvelope("{\"summary\":\"坏掉的 JSON", "stop"),"R22","broken JSON cannot be read as a natural language description");
        rejectsWithCode(contentEnvelope("<think>画面看起来可能是蓝色杯子</think>"+prose,"stop"),"R23","reasoning tags cannot enter spoken prose");
        rejectsWithCode(contentEnvelope("```text\n"+prose+"\n```","stop"),"R23","unknown code block language cannot be narrated as prose");
        rejectsWithCode(contentEnvelope(description()+"\n"+description(),"stop"),"R23","multiple descriptions are rejected rather than taking the first");
        rejectsWithCode(contentEnvelope("["+description()+","+description()+"]","stop"),"R24","multiple object arrays are not silently merged");
        check(DirectModelProtocol.result("deepseek",contentEnvelope("```json"+description()+"```","stop"),frames,false,null).getString("summary").contains("方块"),"compact single line JSON fence accepted");
        check(DirectModelProtocol.result("deepseek",contentEnvelope(JSONObject.quote(description().toString()),"stop"),frames,false,null).getString("summary").contains("方块"),"one extra JSON string layer accepted without string replacement");
        check(DirectModelProtocol.result("deepseek",contentEnvelope("["+description()+"]","stop"),frames,false,null).getString("summary").contains("方块"),"single description object array unwrapped");
        rejects(()->DirectModelProtocol.result("deepseek",contentEnvelope("抱歉，我无法查看您提供的截图，请上传图片。","stop"),frames,false,null),"plain model refusal does not become a successful description");
        String textScreen="页面没有图片，只有一行文字和一个确认按钮。";
        check(DirectModelProtocol.result("deepseek",contentEnvelope(textScreen,"stop"),frames,false,null).getString("summary").equals(textScreen),"describing a text only screen is not mistaken for refusal");
        rejects(()->DirectModelProtocol.result("deepseek",contentEnvelope(prose,"length"),frames,false,null),"truncated plain response is never spoken");
        save(settings,"deepseek",true);
        AtomicInteger calls=new AtomicInteger();
        AssistantApi client=new AssistantApi(getTargetContext(),url->{ int n=calls.incrementAndGet(); return new FakeConnection(url,200,n==1 ? envelope("deepseek",description()).toString() : contentEnvelope(prose,"stop").toString()); });
        try {
            Reply test=new Reply(); client.check(test); test.await();
            Reply screen=new Reply(); client.describe(frames,false,"brief",null,0,screen); screen.await();
            check(test.error==null && screen.error==null && screen.success.contains(prose) && calls.get()==2,"small test JSON followed by screen prose succeeds without extra requests");
        } finally { client.close(); }
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

            for (String provider : new String[]{"gemini", "openai", "compatible", "deepseek"}) {
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
                if ("deepseek".equals(provider)) {
                    check("disabled".equals(request.getJSONObject("thinking").getString("type"))
                            && "json_object".equals(request.getJSONObject("response_format").getString("type"))
                            && request.getInt("max_tokens") == 4096 && !request.has("store") && !request.has("max_completion_tokens"), "DeepSeek native options and JSON output");
                    SettingsStore restored = new SettingsStore(getTargetContext());
                    check("deepseek".equals(restored.getProvider()) && "deepseek-flash".equals(restored.getModel())
                            && KEY.equals(restored.getAccessToken()), "DeepSeek provider model and encrypted key survive reload");
                }
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
                    if ("deepseek".equals(provider)) {
                        JSONObject sent = new JSONObject(fake.body.toString("UTF-8"));
                        check("https://api.deepseek.com/chat/completions".equals(fake.getURL().toString())
                                && "deepseek-flash".equals(sent.getString("model")), "DeepSeek official endpoint and vision model used");
                        Reply test = new Reply(); client.check(test); test.await();
                        check(test.error == null && test.success.contains("测试成功"), "DeepSeek generated-image connection test succeeds");
                    }
                } finally { client.close(); }
            }
            deepseekCompatibility(settings);
            deepseekTextResponses(settings);
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
            String[] errors = {
                "{\"error\":{\"message\":\"" + KEY + "\",\"details\":[{\"reason\":\"API_KEY_INVALID\"}]}}",
                "{\"error\":{\"status\":\"FAILED_PRECONDITION\",\"message\":\"" + KEY + "\"}}",
                "{\"error\":{\"message\":\"User location is not supported for the API use.\"}}",
                "{\"error\":{\"message\":\"" + KEY + "\"}}", "<html>" + KEY,
                String.join("", Collections.nCopies(40000, "x"))
            };
            String[] expected = {"API Key 无效", "调用条件", "所在地区", "请求参数", "请求参数", "请求参数"};
            for (int i = 0; i < errors.length; i++) {
                final String body = errors[i];
                AssistantApi client = new AssistantApi(getTargetContext(), url -> new FakeConnection(url, 400, body));
                try {
                    Reply reply = new Reply(); client.check(reply); reply.await();
                    check(reply.error != null && reply.error.contains("HTTP 400") && reply.error.contains(expected[i]) && !reply.error.contains(KEY), "400 diagnosis safely classified " + i);
                } finally { client.close(); }
            }
            check(DirectModelProtocol.httpError(404).contains("模型或接口不存在"), "404 separated from invalid arguments");
            AtomicInteger pages = new AtomicInteger();
            java.util.List<FakeConnection> listed = new java.util.ArrayList<>();
            AssistantApi discovery = new AssistantApi(getTargetContext(), url -> {
                int page = pages.incrementAndGet();
                String response = page == 1
                    ? "{\"models\":[{\"name\":\"models/gemini-test\",\"supportedGenerationMethods\":[\"generateContent\"]},{\"name\":\"models/gemini-embedding\",\"supportedGenerationMethods\":[\"embedContent\"]}],\"nextPageToken\":\"a+/=&b\"}"
                    : "{\"models\":[{\"name\":\"models/gemini-test\",\"supportedGenerationMethods\":[\"generateContent\"]},{\"name\":\"models/gemini-other\",\"supportedGenerationMethods\":[\"generateContent\"]}]}";
                FakeConnection fake = new FakeConnection(url, 200, response); listed.add(fake); return fake;
            });
            try {
                CountDownLatch done = new CountDownLatch(1);
                AtomicReference<List<String>> result = new AtomicReference<>();
                discovery.listGeminiModels(DirectApiConfig.defaultUrl("gemini"), KEY, new AssistantApi.ModelsCallback() {
                    public void onSuccess(List<String> models) { result.set(models); done.countDown(); }
                    public void onFailure(String message) { done.countDown(); }
                });
                check(done.await(8, TimeUnit.SECONDS) && result.get() != null, "model list works without screen consent");
                check(result.get().equals(Arrays.asList("gemini-other", "gemini-test")), "model candidates filtered sorted and deduplicated");
                check(pages.get() == 2 && listed.get(1).getURL().toString().endsWith("pageToken=a%2B%2F%3D%26b"), "model pagination token safely encoded");
                check(listed.stream().allMatch(c -> "GET".equals(c.getRequestMethod()) && c.body.size() == 0 && KEY.equals(c.getRequestProperty("x-goog-api-key")) && !c.getURL().toString().contains(KEY) && !c.getInstanceFollowRedirects()), "model discovery has no images generation or URL credentials");
                check(!settings.isConsentGranted() && settings.getModel().equals(DirectApiConfig.defaultModel("gemini")), "discovery does not save model or grant consent");
            } finally { discovery.close(); }
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
            passed += new SetupGuideChecks(this, report).run();
            passed += new DialChecks(this, report).run();
            summary.putInt("passed", passed); summary.putInt("failed", 0); summary.putString("stream", report.toString());
            finish(Activity.RESULT_OK, summary);
        } catch (Throwable error) {
            summary.putInt("passed", passed); summary.putInt("failed", 1); summary.putString("stream", report + "FAIL " + error.getClass().getSimpleName() + ": " + error.getMessage());
            finish(Activity.RESULT_CANCELED, summary);
        }
    }
}
