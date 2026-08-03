package com.chasmet.modeliseur3d.cloud;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Conserve la clé personnelle Tripo sans jamais l'écrire en clair dans l'APK,
 * les journaux ou le dépôt. Android 6+ utilise une clé AES du Keystore.
 */
public final class CloudCredentialStore {
    private static final String PREFERENCES = "cloud_credentials_v43";
    private static final String ENCRYPTED_KEY = "tripo_api_key_ciphertext";
    private static final String INITIALIZATION_VECTOR = "tripo_api_key_iv";
    private static final String KEY_ALIAS = "modeliseur3d_v43_tripo_key";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private static volatile String sessionOnlyKey;

    private final Context context;

    public CloudCredentialStore(Context context) {
        this.context = context.getApplicationContext();
    }

    public void save(String value) throws Exception {
        String apiKey = normalize(value);
        if (apiKey.isEmpty()) {
            throw new IllegalArgumentException("La clé API est vide");
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // Le Keystore AES/GCM n'est pas disponible de façon uniforme sur
            // Android 5. La clé reste uniquement en mémoire pour cette session.
            sessionOnlyKey = apiKey;
            return;
        }

        SecretKey secretKey = getOrCreateSecretKey();
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);
        byte[] encrypted = cipher.doFinal(
                apiKey.getBytes(StandardCharsets.UTF_8)
        );

        preferences().edit()
                .putString(
                        ENCRYPTED_KEY,
                        Base64.encodeToString(encrypted, Base64.NO_WRAP)
                )
                .putString(
                        INITIALIZATION_VECTOR,
                        Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
                )
                .apply();
        sessionOnlyKey = apiKey;
    }

    public String load() {
        String memoryValue = normalize(sessionOnlyKey);
        if (!memoryValue.isEmpty()) {
            return memoryValue;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return "";
        }

        SharedPreferences preferences = preferences();
        String encryptedText = preferences.getString(ENCRYPTED_KEY, null);
        String ivText = preferences.getString(INITIALIZATION_VECTOR, null);
        if (encryptedText == null || ivText == null) {
            return "";
        }

        try {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
            keyStore.load(null);
            SecretKey key = (SecretKey) keyStore.getKey(KEY_ALIAS, null);
            if (key == null) {
                clear();
                return "";
            }

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    key,
                    new GCMParameterSpec(
                            128,
                            Base64.decode(ivText, Base64.NO_WRAP)
                    )
            );
            String result = normalize(new String(
                    cipher.doFinal(Base64.decode(
                            encryptedText,
                            Base64.NO_WRAP
                    )),
                    StandardCharsets.UTF_8
            ));
            sessionOnlyKey = result;
            return result;
        } catch (Exception invalidatedOrCorruptKey) {
            clear();
            return "";
        }
    }

    public boolean hasKey() {
        return !load().isEmpty();
    }

    public void clear() {
        sessionOnlyKey = null;
        preferences().edit()
                .remove(ENCRYPTED_KEY)
                .remove(INITIALIZATION_VECTOR)
                .apply();
    }

    private SecretKey getOrCreateSecretKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        SecretKey existing = (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        if (existing != null) {
            return existing;
        }

        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                KEYSTORE
        );
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT
                        | KeyProperties.PURPOSE_DECRYPT
        )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(
                        KeyProperties.ENCRYPTION_PADDING_NONE
                )
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private SharedPreferences preferences() {
        return context.getSharedPreferences(
                PREFERENCES,
                Context.MODE_PRIVATE
        );
    }

    private static String normalize(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return normalized.substring(7).trim();
        }
        return normalized;
    }
}
