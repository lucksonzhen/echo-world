package com.tingjian.assistant;

public final class DirectApiConfigTest {
    private static int passed;
    private static void equal(String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError(actual);
        passed++;
    }
    private static void rejects(Runnable run) {
        try { run.run(); } catch (IllegalArgumentException expected) { passed++; return; }
        throw new AssertionError("Unsafe configuration accepted");
    }
    public static void main(String[] args) {
        equal("deepseek", DirectApiConfig.validateProvider("deepseek"));
        equal("https://api.deepseek.com", DirectApiConfig.validateBaseUrl("deepseek", "https://api.deepseek.com/", false));
        equal("deepseek-flash", DirectApiConfig.defaultModel("deepseek"));
        equal("https://api.deepseek.com/chat/completions", DirectApiConfig.requestUrl("deepseek", DirectApiConfig.defaultUrl("deepseek"), "deepseek-flash"));
        rejects(() -> DirectApiConfig.validateBaseUrl("deepseek", "https://api.deepseek.com.evil.example", false));
        rejects(() -> DirectApiConfig.validateBaseUrl("deepseek", "http://api.deepseek.com", true));
        rejects(() -> DirectApiConfig.validateBaseUrl("deepseek", "https://api.deepseek.com/chat/completions", false));
        rejects(() -> DirectApiConfig.validateKey("deepseek", ""));
        equal("https://api.openai.com/v1", DirectApiConfig.validateBaseUrl("openai", "https://api.openai.com/v1/", false));
        equal("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent",
                DirectApiConfig.requestUrl("gemini", DirectApiConfig.defaultUrl("gemini"), "gemini-3.5-flash"));
        equal("https://api.example.com/compatible-mode/v1/chat/completions",
                DirectApiConfig.requestUrl("compatible", "https://api.example.com/compatible-mode/v1", "vision"));
        equal("https://api.example.com/v1", DirectApiConfig.validateBaseUrl("compatible", " https://api.example.com/v1/// ", false));
        equal("gemini-3.5-flash", DirectApiConfig.validateModel("gemini", "models/gemini-3.5-flash"));
        equal("vendor/vision-model", DirectApiConfig.validateModel("compatible", "vendor/vision-model"));
        equal("", DirectApiConfig.validateKey("backend", ""));
        equal("test-key", DirectApiConfig.validateKey("gemini", " test-key "));
        equal("http://127.0.0.1:8787", DirectApiConfig.validateBaseUrl("backend", "http://127.0.0.1:8787", true));
        rejects(() -> DirectApiConfig.validateBaseUrl("backend", "http://127.0.0.1:8787", false));
        for (String value : new String[]{"http://api.example.com/v1", "https://user:secret@api.example.com/v1", "https://api.example.com/v1?key=secret",
                "https://api.example.com/v1#secret", "https://api.example.com/a/../v1", "https://api.example.com:0/v1", "https://api.example.com:99999/v1",
                "https://api.example.com/v1/chat/completions", "https://api.example.com/v1/responses", "file:///tmp/model", ""}) {
            rejects(() -> DirectApiConfig.validateBaseUrl("compatible", value, true));
        }
        rejects(() -> DirectApiConfig.validateBaseUrl("gemini", "https://proxy.example.com/v1beta", false));
        rejects(() -> DirectApiConfig.validateBaseUrl("openai", "https://api.openai.com.evil.example/v1", false));
        rejects(() -> DirectApiConfig.validateKey("gemini", ""));
        rejects(() -> DirectApiConfig.validateKey("gemini", "key\r\nHeader: value"));
        rejects(() -> DirectApiConfig.validateModel("gemini", "../model"));
        rejects(() -> DirectApiConfig.validateModel("gemini", "model?key=value"));
        rejects(() -> DirectApiConfig.validateModel("compatible", ""));
        rejects(() -> DirectApiConfig.validateProvider("unknown"));
        System.out.println("Direct API configuration: " + passed + " checks passed");
    }
}
