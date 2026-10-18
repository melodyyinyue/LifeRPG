package com.lifereplay.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class AppSettings {
    private static final Object LOCK = new Object();
    private static final String PREFERENCES = "life_replay_settings";
    private static final String ALIAS = "com.lifereplay.app.settings.aes.v1";
    private static final String KEY = "key.encrypted";
    private static final String AUDIO_KEY = "audioKey.encrypted";
    private static final String TEXT_MIGRATED = "text.step35.migrated";
    private static final int IV_LENGTH = 12;
    private final SharedPreferences preferences;

    public AppSettings(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    public ModelClient.Config load() throws Exception {
        synchronized (LOCK) {
            try {
                ModelClient.Config config = new ModelClient.Config();
                String textUrl = value(preferences.getString("textUrl", ""));
                if (!textUrl.trim().isEmpty()) {
                    config.textUrl = ModelClient.normalizeEndpoint(textUrl);
                }
                String audioUrl = value(preferences.getString("audioUrl", ""));
                String asrUrl = value(preferences.getString("asrUrl", ""));
                if (!audioUrl.trim().isEmpty()) {
                    config.audioUrl = audioUrl;
                }
                if (!asrUrl.trim().isEmpty()) {
                    config.asrUrl = asrUrl;
                }
                config.textModel = preferences.getString("textModel", config.textModel);
                config.audioModel = preferences.getString("audioModel", config.audioModel);
                config.asrModel = preferences.getString("asrModel", config.asrModel);
                config.key = decrypt(KEY);
                config.audioKey = decrypt(AUDIO_KEY);
                migrateTextConfig(config);
                return config;
            } catch (Exception ignored) {
                // 配置损坏时不返回默认配置，错误消息中不包含服务商或偏好设置的值。
                throw new Exception("读取配置失败，配置或加密密钥不可用；请明确重设或清除配置");
            }
        }
    }

    private void migrateTextConfig(ModelClient.Config config) throws Exception {
        if (preferences.getBoolean(TEXT_MIGRATED, false)) return;
        ModelClient.Config defaults = new ModelClient.Config();
        String savedUrl = value(preferences.getString("textUrl", "")).trim();
        boolean legacyRoute = false;
        try {
            java.net.URI uri = new java.net.URI(config.textUrl);
            legacyRoute = "https".equalsIgnoreCase(uri.getScheme())
                    && "models-proxy.stepfun-inc.com".equalsIgnoreCase(uri.getHost())
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && uri.getRawUserInfo() == null && uri.getRawQuery() == null
                    && uri.getRawFragment() == null
                    && "/v1/chat/completions".equals(uri.getRawPath());
        } catch (Exception ignored) {
            // 自定义或无效路由保留，不能将凭据迁往未确认的服务。
        }
        boolean defaultRoute = savedUrl.isEmpty() || legacyRoute;
        boolean oldOfficialModel = ModelClient.isOfficialTextEndpoint(config.textUrl)
                && (value(config.textModel).trim().isEmpty()
                || "step-5-preview:stepfun".equals(config.textModel)
                || "step-5-preview".equals(config.textModel));
        SharedPreferences.Editor editor = preferences.edit();
        if (defaultRoute) {
            config.textUrl = defaults.textUrl;
            editor.putString("textUrl", config.textUrl);
        }
        if (defaultRoute || oldOfficialModel) {
            config.textModel = defaults.textModel;
            editor.putString("textModel", config.textModel);
        }
        // 只更新文本配置与标记，不重写已有密文或其他能力配置。
        if (!editor.putBoolean(TEXT_MIGRATED, true).commit()) {
            throw new IllegalStateException();
        }
    }

    public void save(ModelClient.Config config) throws Exception {
        synchronized (LOCK) {
            try {
                if (config == null) {
                    throw new IllegalArgumentException();
                }
                String encryptedKey = encrypt(value(config.key), KEY);
                String encryptedAudioKey = encrypt(value(config.audioKey), AUDIO_KEY);
                // 先完成全部加密，再一次性提交完整的偏好设置快照。
                SharedPreferences.Editor editor = preferences.edit().clear()
                        .putBoolean(TEXT_MIGRATED, true)
                        .putString("textUrl", ModelClient.normalizeEndpoint(config.textUrl))
                        .putString("audioUrl", value(config.audioUrl))
                        .putString("asrUrl", value(config.asrUrl))
                        .putString("textModel", value(config.textModel))
                        .putString("audioModel", value(config.audioModel))
                        .putString("asrModel", value(config.asrModel));
                if (!encryptedKey.isEmpty()) {
                    editor.putString(KEY, encryptedKey);
                }
                if (!encryptedAudioKey.isEmpty()) {
                    editor.putString(AUDIO_KEY, encryptedAudioKey);
                }
                if (!editor.commit()) {
                    throw new IllegalStateException();
                }
            } catch (Exception ignored) {
                throw new Exception("保存配置失败，请检查设备存储及加密密钥状态");
            }
        }
    }

    public void clear() {
        synchronized (LOCK) {
            boolean failed = false;
            try {
                failed = !preferences.edit().clear().commit();
            } catch (RuntimeException ignored) {
                failed = true;
            }
            try {
                KeyStore store = keyStore();
                if (store.containsAlias(ALIAS)) {
                    store.deleteEntry(ALIAS);
                }
            } catch (Exception ignored) {
                failed = true;
            }
            if (failed) {
                throw new IllegalStateException("配置或本应用加密密钥未能完全清除，请重试");
            }
        }
    }

    private String encrypt(String secret, String field) throws Exception {
        if (secret.isEmpty()) {
            return "";
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        // AndroidKeyStore 每次生成新的随机初始化向量，绝不将 API Key 用作 AES 密钥。
        cipher.init(Cipher.ENCRYPT_MODE, secretKey(true));
        cipher.updateAAD((PREFERENCES + ":" + field).getBytes(StandardCharsets.UTF_8));
        byte[] plaintext = secret.getBytes(StandardCharsets.UTF_8);
        try {
            byte[] encrypted = cipher.doFinal(plaintext);
            byte[] iv = cipher.getIV();
            if (iv.length != IV_LENGTH) {
                throw new IllegalStateException();
            }
            byte[] envelope = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, envelope, 0, iv.length);
            System.arraycopy(encrypted, 0, envelope, iv.length, encrypted.length);
            return "v1:" + Base64.encodeToString(envelope, Base64.NO_WRAP);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private String decrypt(String field) throws Exception {
        String encoded = preferences.getString(field, "");
        if (encoded.isEmpty()) {
            return "";
        }
        if (!encoded.startsWith("v1:")) {
            throw new IllegalStateException();
        }
        byte[] envelope = Base64.decode(encoded.substring(3), Base64.NO_WRAP);
        if (envelope.length <= IV_LENGTH + 16) {
            throw new IllegalStateException();
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, secretKey(false),
                new GCMParameterSpec(128, Arrays.copyOf(envelope, IV_LENGTH)));
        cipher.updateAAD((PREFERENCES + ":" + field).getBytes(StandardCharsets.UTF_8));
        byte[] plaintext = cipher.doFinal(envelope, IV_LENGTH, envelope.length - IV_LENGTH);
        try {
            return new String(plaintext, StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private SecretKey secretKey(boolean create) throws Exception {
        KeyStore store = keyStore();
        if (store.containsAlias(ALIAS)) {
            Key key = store.getKey(ALIAS, null);
            if (!(key instanceof SecretKey)) {
                throw new IllegalStateException();
            }
            return (SecretKey) key;
        }
        if (!create) {
            throw new IllegalStateException();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private static KeyStore keyStore() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        return store;
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
