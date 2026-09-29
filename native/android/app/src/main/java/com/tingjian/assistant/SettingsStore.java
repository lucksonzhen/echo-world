package com.tingjian.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class SettingsStore {
    private static final String KEY_ALIAS = "tingjian_connection_token";
    private static final Object SETTINGS_LOCK = new Object();
    private final SharedPreferences prefs;
    private final Context context;
    public SettingsStore(Context context) {
        this.context = context.getApplicationContext();
        prefs = context.getSharedPreferences("screen_assistant", Context.MODE_PRIVATE);
    }
    public static final class ConnectionSnapshot {
        public final String serverUrl;
        public final String accessToken;
        public final boolean consentGranted;
        public final String provider, model;
        private ConnectionSnapshot(String serverUrl, String accessToken, boolean consentGranted, String provider, String model) {
            this.serverUrl = serverUrl; this.accessToken = accessToken; this.consentGranted = consentGranted;
            this.provider = provider; this.model = model;
        }
        public boolean isDirect() { return !"backend".equals(provider); }
    }
    public ConnectionSnapshot snapshot() {
        synchronized (SETTINGS_LOCK) {
            return new ConnectionSnapshot(getServerUrl(), readAccessToken(), isConsentGranted(), getProvider(), getModel());
        }
    }
    // A legacy backend consent does not authorize sending screenshots to a newly selected API.
    public boolean isConsentGranted() { synchronized (SETTINGS_LOCK) { return prefs.contains("connection_provider") && prefs.getBoolean("screen_consent", false); } }
    public float getSpeechRate() { return prefs.getFloat("speech_rate", 1.35f); }
    public void setSpeechRate(float rate) {
        prefs.edit().putFloat("speech_rate", Math.max(1f, Math.min(1.8f, rate))).apply();
    }
    public boolean isPauseDescriptionEnabled() { return prefs.getBoolean("pause_description", false); }
    public void setPauseDescriptionEnabled(boolean enabled) { prefs.edit().putBoolean("pause_description", enabled).apply(); }
    public boolean isImageWatchEnabled() { return prefs.getBoolean("image_watch", false); }
    public void setImageWatchEnabled(boolean enabled) { prefs.edit().putBoolean("image_watch", enabled).apply(); }
    public String getProvider() { synchronized (SETTINGS_LOCK) { return prefs.getString("connection_provider", "gemini"); } }
    public String getModel() { synchronized (SETTINGS_LOCK) { return prefs.getString("direct_model", DirectApiConfig.defaultModel(getProvider())); } }
    public String getServerUrl() { synchronized (SETTINGS_LOCK) {
        return prefs.getString("backend".equals(getProvider()) ? "server_url" : "direct_url", DirectApiConfig.defaultUrl(getProvider()));
    } }
    public boolean isConfigured() { synchronized (SETTINGS_LOCK) {
        return !getServerUrl().isEmpty() && ("backend".equals(getProvider())
                || (!getModel().isEmpty() && !prefs.getString("api_key_encrypted", "").isEmpty()));
    } }
    public boolean isConnectionVerified() { synchronized (SETTINGS_LOCK) { return prefs.getBoolean("connection_verified", false); } }
    public void markConnectionVerified() { synchronized (SETTINGS_LOCK) { prefs.edit().putBoolean("connection_verified",true).apply(); } }
    public String getAccessToken() {
        synchronized (SETTINGS_LOCK) { return readAccessToken(); }
    }
    private String readAccessToken() {
        String stored = prefs.getString("backend".equals(getProvider()) ? "token_encrypted" : "api_key_encrypted", "");
        if (stored.isEmpty()) return "";
        try {
            String[] pieces = stored.split(":", 2);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(pieces[0], Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(pieces[1], Base64.NO_WRAP)), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception error) { throw new IllegalStateException("无法读取已保存的密钥，请打开听见世界重新输入并保存。"); }
    }
    public void save(String serverUrl, String token, boolean consent) throws Exception {
        saveConnection("backend", serverUrl, "", token, consent);
    }
    public void saveConnection(String provider, String serverUrl, String model, String token, boolean consent) throws Exception {
        synchronized (SETTINGS_LOCK) {
            DirectApiConfig.validateProvider(provider);
            String url = DirectApiConfig.validateBaseUrl(provider, serverUrl, (context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0);
            String cleanModel = DirectApiConfig.validateModel(provider, model);
            token = DirectApiConfig.validateKey(provider, token);
            boolean changed = !provider.equals(getProvider()) || !url.equals(getServerUrl())
                    || (!"backend".equals(provider) && !cleanModel.equals(getModel()));
            try { changed = changed || !token.equals(readAccessToken()); } catch (IllegalStateException unreadable) { changed = true; }
            String encrypted = "";
            if (!token.trim().isEmpty()) {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, key());
                encrypted = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(token.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8)), Base64.NO_WRAP);
            }
            boolean backend = "backend".equals(provider);
            SharedPreferences.Editor editor = prefs.edit().putString("connection_provider", provider)
                    .putString(backend ? "server_url" : "direct_url", url)
                    .putString(backend ? "token_encrypted" : "api_key_encrypted", encrypted)
                    .putBoolean("screen_consent", consent);
            if (!backend) editor.putString("direct_model", cleanModel);
            if (changed) editor.putBoolean("connection_verified",false);
            if (!editor.commit()) throw new IllegalStateException("无法保存连接设置，请重试。");
        }
    }
    public void clearCredential() { synchronized (SETTINGS_LOCK) {
        prefs.edit().remove("api_key_encrypted").remove("token_encrypted").putBoolean("connection_verified",false).apply();
        revokeConsent();
    } }
    public void revokeConsent() { synchronized (SETTINGS_LOCK) { prefs.edit().putBoolean("screen_consent", false).putBoolean("pause_description", false).putBoolean("image_watch", false).apply(); } }
    public static String validateUrl(String input, boolean debug) {
        return DirectApiConfig.validateBaseUrl("backend", input, debug);
    }
    private static synchronized SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias(KEY_ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(KEY_ALIAS, null);
    }
}
