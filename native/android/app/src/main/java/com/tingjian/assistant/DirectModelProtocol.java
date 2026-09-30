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
            + "title、summary 必须为非空字符串。没有内容的数组必须是 []，不要用 null 或空字符串。没有用户问题时 answer 必须为 null。"
            + "数组 details、visibleText、uncertainties 只包含非空字符串。timeline 项为 {\"timestampMs\":整数,\"description\":\"描述\"}。";

    public static JSONObject request(String provider, String model, String instructions, List<ScreenFrame> frames,
            boolean video, String mode, String question, int durationMs) throws Exception {
        if (frames.isEmpty() || frames.size() > 12) throw new IllegalStateException("准备的图片数据无效或过大，请重新描述屏幕。诊断码 I01。");
        JSONObject context = new JSONObject().put("mediaType", video ? "video" : "image").put("mode", mode)
                .put("question", question == null ? JSONObject.NULL : question);
        if (video) context.put("durationMs", durationMs);
        boolean gemini = "gemini".equals(provider);
        JSONArray content = new JSONArray().put(gemini ? new JSONObject().put("text", context.toString())
                : new JSONObject().put("type", "text").put("text", context.toString()));
        long total = 0;
        for (ScreenFrame frame : frames) {
            if (!frame.dataUrl.matches("data:image/(jpeg|png|webp);base64,[A-Za-z0-9+/]+={0,2}")) throw new IllegalStateException("准备的图片数据无效或过大，请重新描述屏幕。诊断码 I01。");
            int comma = frame.dataUrl.indexOf(',');
            int size = frame.dataUrl.length() - comma - 1;
            total += size;
            if (size > 2 * 1024 * 1024 * 4 / 3 + 4 || total > 8 * 1024 * 1024 * 4 / 3 + 16) throw new IllegalStateException("准备的图片数据无效或过大，请重新描述屏幕。诊断码 I01。");
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
        else if ("deepseek".equals(provider)) body.put("max_tokens", 4096)
                .put("response_format", new JSONObject().put("type", "json_object"))
                .put("thinking", new JSONObject().put("type", "disabled"));
        // Compatible services vary in their structured-output options. The prompt and validator
        // enforce the result contract without retrying a potentially billable request.
        else body.put("max_tokens", 4096);
        return body;
    }

    public static JSONObject result(String provider, JSONObject envelope, List<ScreenFrame> frames, boolean video, String question) throws Exception {
        return result(provider,envelope,frames,video,question,"brief");
    }
    public static JSONObject result(String provider, JSONObject envelope, List<ScreenFrame> frames, boolean video, String question,String mode) throws Exception {
        try { return parseResult(provider,envelope,frames,video,question,mode); }
        catch(org.json.JSONException malformed) { throw envelopeError(); }
    }
    private static JSONObject parseResult(String provider, JSONObject envelope, List<ScreenFrame> frames, boolean video, String question,String mode) throws Exception {
        if (envelope.has("error")) throw envelopeError();
        String text;
        if ("gemini".equals(provider)) {
            JSONObject feedback = envelope.optJSONObject("promptFeedback");
            if (feedback != null && feedback.has("blockReason") && !"BLOCK_REASON_UNSPECIFIED".equals(feedback.optString("blockReason"))) throw refused();
            JSONArray candidates = envelope.optJSONArray("candidates");
            if (candidates == null || candidates.length() != 1) throw envelopeError();
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
            if (choices == null || choices.length() != 1) throw envelopeError();
            JSONObject choice = choices.getJSONObject(0);
            JSONObject message = choice.getJSONObject("message");
            if (!message.isNull("refusal") && !message.optString("refusal").isEmpty()) throw refused();
            String finish=choice.optString("finish_reason");
            if ("length".equals(finish)) throw new IllegalStateException("模型回复达到输出长度限制，描述被截断。请使用简短描述重试。诊断码 R06。");
            if ("content_filter".equals(finish)) throw refused();
            if (!"stop".equals(finish)) throw new IllegalStateException("模型未正常完成描述，请重试。诊断码 R07。");
            if (message.isNull("content")) throw emptyResponse();
            if (!(message.opt("content") instanceof String)) throw envelopeError();
            text = message.getString("content");
        }
        text=unwrapFence(text);
        if(text.isEmpty()) throw emptyResponse();
        JSONObject result=parseDescription(text,"deepseek".equals(provider),!video && (question==null || question.trim().isEmpty())
                && ("brief".equals(mode) || "detailed".equals(mode)));
        if("deepseek".equals(provider)) normalizeDeepSeek(result,question);
        validateResult(result, frames, video, question);
        return result;
    }

    private static String unwrapFence(String text) {
        text=text.trim();
        if(text.startsWith("\uFEFF")) text=text.substring(1).trim();
        // Accept a single complete fence, including compact one-line fences; never extract an arbitrary object from prose.
        if(text.startsWith("```") && text.endsWith("```") && text.length()>=6) {
            String inner=text.substring(3,text.length()-3).trim();
            if(inner.regionMatches(true,0,"json",0,4)) inner=inner.substring(4).trim();
            if(!inner.contains("```")) text=inner;
        }
        return text;
    }
    private static JSONObject parseDescription(String text,boolean deepseek,boolean allowProse) throws Exception {
        if(deepseek && allowProse && isPlainNarration(text)) return plainDescription(text);
        Object parsed=parseSingleValue(text,deepseek);
        // One extra JSON string layer and a single-object array are unambiguous containers, not content repair.
        if(deepseek && parsed instanceof String) parsed=parseSingleValue(unwrapFence((String)parsed),true);
        if(deepseek && parsed instanceof JSONArray && ((JSONArray)parsed).length()==1) parsed=((JSONArray)parsed).opt(0);
        if(!(parsed instanceof JSONObject)) throw formatError(deepseek ? "R24" : "R02","回复不是单个描述对象");
        return (JSONObject)parsed;
    }
    private static Object parseSingleValue(String text,boolean deepseek) {
        org.json.JSONTokener parser=new org.json.JSONTokener(text);
        Object parsed;
        try { parsed=parser.nextValue(); }
        catch(org.json.JSONException invalid) { throw formatError(deepseek ? "R22" : "R02","回复中的 JSON 语法不完整或损坏"); }
        try { if(parser.nextClean()!=0) throw formatError(deepseek ? "R23" : "R02","描述对象外还有其他内容，无法可靠合并"); }
        catch(org.json.JSONException invalid) { throw formatError(deepseek ? "R22" : "R02","回复中的 JSON 语法不完整或损坏"); }
        return parsed;
    }
    private static boolean isPlainNarration(String text) {
        if(text.isEmpty() || text.length()>1500 || Character.UnicodeScript.of(text.codePointAt(0))!=Character.UnicodeScript.HAN) return false;
        int chinese=0;
        for(int i=0;i<text.length();) {
            int code=text.codePointAt(i); i+=Character.charCount(code);
            if("{}[]<>`".indexOf(code)>=0 || (Character.isISOControl(code) && code!='\n' && code!='\r' && code!='\t')) return false;
            if(Character.UnicodeScript.of(code)==Character.UnicodeScript.HAN) chinese++;
        }
        return chinese>=6;
    }
    private static JSONObject plainDescription(String text) throws Exception {
        // A textual refusal is not a successful connection test or a new screen description.
        if(text.matches("(?s)^(抱歉[，,。\\s]*)?(我(无法|不能|看不到).{0,16}(图片|图像|截图|屏幕)|作为.{0,12}语言模型|未(收到|提供).{0,6}(图片|图像|截图)|请(先)?(上传|提供).{0,6}(图片|图像|截图)).*")) throw refused();
        return new JSONObject().put("title","画面描述").put("summary",text).put("details",new JSONArray())
                .put("visibleText",new JSONArray()).put("timeline",new JSONArray()).put("uncertainties",new JSONArray()).put("answer",JSONObject.NULL);
    }
    private static IllegalStateException formatError(String code,String reason) {
        return new IllegalStateException(reason+"。请重试。诊断码 "+code+"。");
    }
    /** Only normalize absent/empty optional data; never invent a summary or accept mistyped content. */
    private static void normalizeDeepSeek(JSONObject result,String question) throws Exception {
        if(emptyOptional(result.opt("title"))) result.put("title","画面描述");
        for(String name:new String[]{"details","visibleText","uncertainties","timeline"})
            if(emptyOptional(result.opt(name))) result.put(name,new JSONArray());
        if(question==null || question.trim().isEmpty() || emptyOptional(result.opt("answer"))) result.put("answer",JSONObject.NULL);
    }
    private static boolean emptyOptional(Object value) {
        return value==null || value==JSONObject.NULL || (value instanceof String && ((String)value).trim().isEmpty());
    }
    public static void validateResult(JSONObject result, List<ScreenFrame> frames, boolean video, String question) throws Exception {
        checkedText(result.opt("title"), 150);
        Object summary=result.opt("summary");
        if(!(summary instanceof String) || ((String)summary).trim().isEmpty() || ((String)summary).length()>1500)
            throw new IllegalStateException("模型没有返回可用的画面概要，请重试。诊断码 R03。");
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
    private static IllegalStateException invalid() { return new IllegalStateException("模型描述的字段格式不符合要求，请重试。诊断码 R04。"); }
    private static IllegalStateException envelopeError() { return new IllegalStateException("模型服务返回的响应结构不符合接口格式，请检查接口类型或重试。诊断码 R00。"); }
    private static IllegalStateException emptyResponse() { return new IllegalStateException("模型服务本次返回空内容，请重新描述屏幕。诊断码 R01。"); }
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
