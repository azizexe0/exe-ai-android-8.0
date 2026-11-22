package com.exeai.assistant;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

/**
 * Stores the Gemini API key in EncryptedSharedPreferences (AES-256 GCM values, AES-256 SIV keys).
 * The key is never written to source code or to plain preferences. If the secure store cannot be
 * opened, saving is refused instead of falling back to plain text.
 */
public final class ApiKeyStore {
    private static final String FILE = "exe_ai_secure";
    private static final String KEY_GEMINI = "gemini_api_key";
    private static final String KEY_MODEL_OVERRIDE = "gemini_model_override";

    private ApiKeyStore() {}

    private static SharedPreferences open(Context context) throws Exception {
        MasterKey masterKey = new MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build();
        return EncryptedSharedPreferences.create(
                context,
                FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
    }

    /** Returns the saved key, or an empty string when none is saved or the store cannot be opened. */
    public static String getGeminiKey(Context context) {
        try {
            return open(context).getString(KEY_GEMINI, "");
        } catch (Exception e) {
            return "";
        }
    }

    /** Returns false when the secure store is unavailable. */
    public static boolean saveGeminiKey(Context context, String apiKey) {
        try {
            open(context).edit().putString(KEY_GEMINI, apiKey == null ? "" : apiKey.trim()).apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void clearGeminiKey(Context context) {
        try {
            open(context).edit().remove(KEY_GEMINI).apply();
        } catch (Exception ignored) { }
    }

    public static String getModelOverride(Context context) {
        try {
            return open(context).getString(KEY_MODEL_OVERRIDE, "");
        } catch (Exception e) {
            return "";
        }
    }

    public static void saveModelOverride(Context context, String model) {
        try {
            open(context).edit().putString(KEY_MODEL_OVERRIDE, model == null ? "" : model.trim()).apply();
        } catch (Exception ignored) { }
    }
}
