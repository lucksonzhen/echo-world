package com.tingjian.assistant;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Provider wire formats and bounded, validated descriptions. No network or credential logging. */
public final class DirectModelProtocol {
    private static final String FORMAT = "\n只输出一个 JSON 对象，不要 Markdown。所有字段必须存在："
            + "{\"title\":\"简短标题\",\"summary\":\"概要\",\"details\":[],\"visibleText\":[],"
            + "\"timeline\":[],\"uncertainties\":[],\"answer\":null}。"
            + "数组 details、visibleText、uncertainties 只包含字符串。timeline 项为 {\"timestampMs\":整数,\"description\":\"描述\"}。";

    public static JSONObject request(String provider, String model, String instructions, List<ScreenFrame> frames,
            boolean video, String mode, String question, int durationMs) throws Exception {
        if (frames.isEmpty() || frames.size() > 12) throw invalid();
        JSONObject context = new JSONObject().put("mediaType", video ? "video" : "image").put("mode", mode)
                .put("question", question == null ? JSONObject.NULL : question);
        if (video) context.put("durationMs", durationMs);
        boolean gemini = "gemini".equals(provider);
        JSONArray content = new JSONArray().put(gemini ? new JSONObject().put("text", context.toString())
                : new JSONObject().put("type", "text").put("text", context.toString()));
        long total = 0;
        for (ScreenFrame frame : frames) {
            if (!frame.dataUrl.matches("data:image/(jpeg|png|webp);base64,[A-Za-z0-9+/]+={0,2}")) throw invalid();
            int comma = frame.dataUrl.indexOf(',');
            int size = frame.dataUrl.length() - comma - 1;
            total += size;
            if (size > 2 * 1024 * 1024 * 4 / 3 + 4 || total > 8 * 1024 * 1024 * 4 / 3 + 16) throw invalid();
            String stamp = "画面时间 timestampMs=" + frame.timestampMs;
            if (gemini) {
                content.put(new JSONObject().put("text", stamp));
                content.put(new JSONObject().put("inlineData", new JSONObject()
                        .put("mimeType", frame.dataUrl.substring(5, frame.dataUrl.indexOf(';')))
                        .put("data", frame.dataUrl.substring(comma + 1))));
            } else {
                content.put(new JSONObject().put("type", "text").put("text", stamp));
                content.put(new JSONObject().put("type", "image_url").put("image_url", new JSONObject().put("url", frame.dataUrl)));
            }
        }
        String prompt = instructions + FORMAT;
        if (gemini) return new JSONObject()
                .put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", prompt))))
                .put("contents", new JSONArray().put(new JSONObject().put("role", "user").put("parts", content)))
                .put("generationConfig", new JSONObject().put("responseMimeType", "application/json").put("maxOutputTokens", 4096));
        JSONObject body = new JSONObject().put("model", model).put("stream", false)
                .put("messages", new JSONArray().put(new JSONObject().put("role", "system").put("content", prompt))
                        .put(new JSONObject().put("role", "user").put("content", content)));
        if ("openai".equals(provider)) body.put("store", false).put("max_completion_tokens", 4096)
                .put("response_format", new JSONObject().put("type", "json_object"));
        // Compatible services vary in their structured-output options. The prompt and validator
        // enforce the result contract without retrying a potentially billable request.
        else body.put("max_tokens", 4096);
        return body;
    }

    public static JSONObject result(String provider, JSONObject envelope, List<ScreenFrame> frames, boolean video, String question) throws Exception {
        if (envelope.has("error")) throw invalid();
        String text;
        if ("gemini".equals(provider)) {
            JSONObject feedback = envelope.optJSONObject("promptFeedback");
            if (feedback != null && feedback.has("blockReason") && !"BLOCK_REASON_UNSPECIFIED".equals(feedback.optString("blockReason"))) throw refused();
            JSONArray candidates = envelope.optJSONArray("candidates");
            if (candidates == null || candidates.length() != 1) throw invalid();
            JSONObject candidate = candidates.getJSONObject(0);
            String finish = candidate.optString("finishReason");
            if (!"STOP".equals(finish)) throw new IllegalStateException("模型未返回完整描述，可能达到输出限制或触发内容限制。");
            JSONArray ratings = candidate.optJSONArray("safetyRatings");
            if (ratings != null) for (int i = 0; i < ratings.length(); i++) if (ratings.getJSONObject(i).optBoolean("blocked")) throw refused();
            JSONArray parts = candidate.getJSONObject("content").getJSONArray("parts");
            StringBuilder all = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.getJSONObject(i);
                if (part.optBoolean("thought")) continue;
                if (!(part.opt("text") instanceof String)) throw invalid();
                all.append(part.getString("text"));
            }
            text = all.toString();
        } else {
            JSONArray choices = envelope.optJSONArray("choices");
            if (choices == null || choices.length() != 1) throw invalid();
            JSONObject choice = choices.getJSONObject(0);
            JSONObject message = choice.getJSONObject("message");
            if (!message.isNull("refusal") && !message.optString("refusal").isEmpty()) throw refused();
            if (!"stop".equals(choice.optString("finish_reason"))) throw new IllegalStateException("模型未返回完整描述，请检查视觉模型和输出限制。");
            if (!(message.opt("content") instanceof String)) throw invalid();
            text = message.getString("content");
        }
        text = text.trim();
        if (text.startsWith("```json\n") && text.endsWith("```")) text = text.substring(8, text.length() - 3).trim();
        else if (text.startsWith("```\n") && text.endsWith("```")) text = text.substring(4, text.length() - 3).trim();
        JSONObject result;
        try { result = new JSONObject(text); } catch (Exception error) { throw invalid(); }
        validateResult(result, frames, video, question);
        return result;
    }

    public static void validateResult(JSONObject result, List<ScreenFrame> frames, boolean video, String question) throws Exception {
        checkedText(result.opt("title"), 150);
        checkedText(result.opt("summary"), 1500);
        strings(result.optJSONArray("details"), 20, 1500);
        strings(result.optJSONArray("visibleText"), 30, 3000);
        strings(result.optJSONArray("uncertainties"), 20, 1500);
        if (!result.has("answer")) throw invalid();
        if (!result.isNull("answer")) checkedText(result.opt("answer"), 3000);
        if (question == null || question.trim().isEmpty()) result.put("answer", JSONObject.NULL);
        JSONArray timeline = result.optJSONArray("timeline");
        if (timeline == null || timeline.length() > 12 || (!video && timeline.length() > 0)) throw invalid();
        Set<Integer> stamps = new HashSet<>();
        for (ScreenFrame frame : frames) stamps.add(frame.timestampMs);
        int previous = -1;
        for (int i = 0; i < timeline.length(); i++) {
            JSONObject entry = timeline.getJSONObject(i);
            Object stamp = entry.opt("timestampMs");
            if (!(stamp instanceof Number) || ((Number) stamp).doubleValue() != ((Number) stamp).intValue()) throw invalid();
            int value = ((Number) stamp).intValue();
            if (value <= previous || !stamps.contains(value)) throw invalid();
            previous = value;
            checkedText(entry.opt("description"), 1500);
        }
    }
    private static void strings(JSONArray array, int count, int length) {
        if (array == null || array.length() > count) throw invalid();
        for (int i = 0; i < array.length(); i++) checkedText(array.opt(i), length);
    }
    private static void checkedText(Object value, int max) {
        if (!(value instanceof String) || ((String) value).trim().isEmpty() || ((String) value).length() > max) throw invalid();
    }
    private static IllegalStateException invalid() { return new IllegalStateException("模型返回格式不完整或不兼容，请选择支持图片输入的模型后重试。"); }
    private static IllegalStateException refused() { return new IllegalStateException("模型未能描述这张画面，请换一张普通图片再试。"); }
    public static String httpError(int status) {
        return httpError(status, null);
    }
    /** Only fixed local messages are displayed; upstream strings can contain secrets or images. */
    public static String httpError(int status, JSONObject envelope) {
        String prefix = "HTTP " + status + "：";
        JSONObject error = envelope == null ? null : envelope.optJSONObject("error");
        if (error != null) {
            JSONArray details = error.optJSONArray("details");
            if (details != null) for (int i = 0; i < details.length(); i++) {
                JSONObject detail = details.optJSONObject(i);
                String reason = detail == null ? "" : detail.optString("reason");
                if ("API_KEY_INVALID".equals(reason) || "API_KEY_EXPIRED".equals(reason))
                    return prefix + "API Key 无效或已过期，请在 Google AI Studio 检查密钥后重新填写。";
                if (reason.startsWith("API_KEY_") && reason.endsWith("_BLOCKED"))
                    return prefix + "密钥的应用或 API 限制阻止了请求，请检查该密钥的限制设置。";
                if ("SERVICE_DISABLED".equals(reason)) return prefix + "该项目未启用所需 API，请在 Google 项目中检查服务设置。";
            }
            String message = error.optString("message").toLowerCase(java.util.Locale.ROOT);
            if (message.contains("api key not valid") || message.contains("api key expired") || message.contains("api key was reported as leaked"))
                return prefix + "API Key 无效、过期或已被停用，请在 Google AI Studio 检查并更换密钥。";
            if (message.contains("user location is not supported") || message.contains("not available in your country"))
                return prefix + "服务商不支持当前请求所在地区，请检查官方可用地区说明。";
            if ("FAILED_PRECONDITION".equals(error.optString("status")))
                return prefix + "账户尚不满足调用条件，请检查项目计费、免费层和地区可用性。";
        }
        if (status == 401 || status == 403) return prefix + "认证或权限被拒绝，请检查密钥、模型授权及密钥限制。";
        if (status == 402) return prefix + "账户计费或余额不足，请检查服务商计费设置。";
        if (status == 429) return prefix + "API 配额不足或请求过于频繁，请检查配额后重试。";
        if (status == 404) return prefix + "模型或接口不存在，或当前密钥无法使用它。Gemini 用户请获取模型列表后重新选择并测试。";
        if (status == 400 || status == 422) return prefix + "请求参数被拒绝。请先测试小图；若也失败，请检查模型是否支持图片输入和 JSON 输出。";
        if (status >= 300 && status < 400) return prefix + "API 地址发生重定向；为保护密钥已停止，请填写服务商的最终 HTTPS 地址。";
        return prefix + "模型服务暂时不可用，请稍后重试。";
    }
}
