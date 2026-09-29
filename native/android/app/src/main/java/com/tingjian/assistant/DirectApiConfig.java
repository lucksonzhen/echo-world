package com.tingjian.assistant;

import java.net.URI;

/** Validates the destination before any credential or image can leave the device. */
public final class DirectApiConfig {
    public static final String[] PROVIDERS = {"compatible", "openai", "gemini", "deepseek", "backend"};
    public static String defaultUrl(String provider) {
        if ("openai".equals(provider)) return "https://api.openai.com/v1";
        if ("gemini".equals(provider)) return "https://generativelanguage.googleapis.com/v1beta";
        if ("deepseek".equals(provider)) return "https://api.deepseek.com";
        return "";
    }
    public static String defaultModel(String provider) {
        if ("openai".equals(provider)) return "gpt-4.1-mini";
        if ("gemini".equals(provider)) return "gemini-3.5-flash";
        if ("deepseek".equals(provider)) return "deepseek-flash";
        return "";
    }
    public static String validateProvider(String provider) {
        for (String allowed : PROVIDERS) if (allowed.equals(provider)) return provider;
        throw new IllegalArgumentException("请选择模型接口类型。");
    }
    public static String validateBaseUrl(String provider, String value, boolean debug) {
        validateProvider(provider);
        try {
            String clean = value.trim().replaceAll("/+$", "");
            URI uri = new URI(clean);
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535 || !uri.normalize().equals(uri)) throw new Exception();
            boolean localBackend = "backend".equals(provider) && debug && "http".equals(uri.getScheme());
            if (!"https".equals(uri.getScheme()) && !localBackend) throw new Exception();
            if ("openai".equals(provider) || "gemini".equals(provider) || "deepseek".equals(provider)) {
                if (!defaultUrl(provider).equals(clean)) throw new Exception();
            }
            if ("compatible".equals(provider) && (clean.endsWith("/chat/completions") || clean.endsWith("/responses")))
                throw new IllegalArgumentException("请填写 API 基础地址，例如 https://服务商域名/v1，不要包含 /chat/completions 或 /responses。");
            return clean;
        } catch (IllegalArgumentException error) { throw error; }
        catch (Exception error) {
            throw new IllegalArgumentException("backend".equals(provider) && debug
                    ? "请输入完整描述服务地址。" : "请输入有效的 HTTPS API 基础地址；官方接口请使用预填地址。");
        }
    }
    public static String validateModel(String provider, String value) {
        if ("backend".equals(provider)) return "";
        String model = value.trim();
        if ("gemini".equals(provider) && model.startsWith("models/")) model = model.substring(7);
        if (model.isEmpty() || model.length() > 200 || !model.matches("[A-Za-z0-9][A-Za-z0-9._:/-]*")
                || ("gemini".equals(provider) && !model.matches("[A-Za-z0-9._-]+")))
            throw new IllegalArgumentException("请填写服务商提供的视觉模型名称。");
        return model;
    }
    public static String validateKey(String provider, String key) {
        String clean = key.trim();
        if ((!"backend".equals(provider) && clean.isEmpty()) || clean.length() > 4096 || !clean.matches("[\\x21-\\x7E]*"))
            throw new IllegalArgumentException("请填写有效的 API Key；不要包含空格或换行。");
        return clean;
    }
    public static String requestUrl(String provider, String base, String model) {
        if ("backend".equals(provider)) return base + "/api/describe";
        if ("gemini".equals(provider)) return base + "/models/" + validateModel(provider, model) + ":generateContent";
        return base + "/chat/completions";
    }
}
