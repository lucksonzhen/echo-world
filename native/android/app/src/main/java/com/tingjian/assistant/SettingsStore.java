package com.tingjian.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.net.URI;
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
        private ConnectionSnapshot(String serverUrl, String accessToken, boolean consentGranted) {
            this.serverUrl = serverUrl; this.accessToken = accessToken; this.consentGranted = consentGranted;
        }
    }
    public ConnectionSnapshot snapshot() {
        synchronized (SETTINGS_LOCK) {
            return new ConnectionSnapshot(prefs.getString("server_url", ""), readAccessToken(), prefs.getBoolean("screen_consent", false));
        }
    }
    public boolean isConsentGranted() { synchronized (SETTINGS_LOCK) { return prefs.getBoolean("screen_consent", false); } }
    public float getSpeechRate() { return prefs.getFloat("speech_rate", 1.35f); }
    public void setSpeechRate(float rate) {
        prefs.edit().putFloat("speech_rate", Math.max(1f, Math.min(1.8f, rate))).apply();
    }
    public boolean isPauseDescriptionEnabled() { return prefs.getBoolean("pause_description", false); }
    public void setPauseDescriptionEnabled(boolean enabled) { prefs.edit().putBoolean("pause_description", enabled).apply(); }
    public String getServerUrl() { synchronized (SETTINGS_LOCK) { return prefs.getString("server_url", ""); } }
    public String getAccessToken() {
        synchronized (SETTINGS_LOCK) { return readAccessToken(); }
    }
    private String readAccessToken() {
        String stored = prefs.getString("token_encrypted", "");
        if (stored.isEmpty()) return "";
        try {
            String[] pieces = stored.split(":", 2);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(pieces[0], Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(pieces[1], Base64.NO_WRAP)), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception error) { throw new IllegalStateException("无法读取访问口令，请打开听见屏幕重新保存连接设置。"); }
    }
    public void save(String serverUrl, String token, boolean consent) throws Exception {
        synchronized (SETTINGS_LOCK) {
            String url = validateUrl(serverUrl, (context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0);
            String encrypted = "";
            if (!token.trim().isEmpty()) {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, key());
                encrypted = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(token.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8)), Base64.NO_WRAP);
            }
            prefs.edit().putString("server_url", url).putString("token_encrypted", encrypted).putBoolean("screen_consent", consent).apply();
        }
    }
    public void revokeConsent() { synchronized (SETTINGS_LOCK) { prefs.edit().putBoolean("screen_consent", false).putBoolean("pause_description", false).apply(); } }
    public static String validateUrl(String input, boolean debug) {
        try {
            URI uri = new URI(input.trim());
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) throw new Exception();
            if (!("https".equals(uri.getScheme()) || (debug && "http".equals(uri.getScheme())))) throw new Exception();
            return input.trim().replaceAll("/+$", "");
        } catch (Exception error) { throw new IllegalArgumentException(debug ? "请输入完整服务地址，例如 http://192.168.1.10:8787。" : "请输入有效的 HTTPS 服务地址。"); }
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
