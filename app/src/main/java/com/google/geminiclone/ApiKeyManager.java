package com.google.geminiclone;

import android.content.Context;
import android.content.SharedPreferences;

public class ApiKeyManager {

    private static final String PREFS_NAME = "gemini_clone_prefs";
    private static final String KEY_API_KEY = "gemini_api_key";
    private static final String KEY_MODEL = "gemini_model";
    private static final String KEY_THINKING_LEVEL = "gemini_thinking_level";

    public static final String MODEL_38_FLASH = "gemini-3.8-flash";
    public static final String MODEL_35_FLASH_LITE = "gemini-3.5-flash-lite";

    public static final String THINKING_HIGH = "high";
    public static final String THINKING_MEDIUM = "medium";
    public static final String THINKING_LOW = "low";
    public static final String THINKING_MINIMAL = "minimal";

    public static final String DEFAULT_MODEL = MODEL_38_FLASH;
    public static final String DEFAULT_THINKING = THINKING_HIGH;

    private final SharedPreferences prefs;

    public ApiKeyManager(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public String getApiKey() {
        return prefs.getString(KEY_API_KEY, "");
    }

    public void setApiKey(String apiKey) {
        prefs.edit().putString(KEY_API_KEY, apiKey != null ? apiKey.trim() : "").apply();
    }

    public boolean hasApiKey() {
        String key = getApiKey();
        return key != null && !key.trim().isEmpty();
    }

    public String getModel() {
        String model = prefs.getString(KEY_MODEL, DEFAULT_MODEL);
        // Ensure only the two requested models are valid
        if (!MODEL_38_FLASH.equals(model) && !MODEL_35_FLASH_LITE.equals(model)) {
            return DEFAULT_MODEL;
        }
        return model;
    }

    public void setModel(String model) {
        if (!MODEL_38_FLASH.equals(model) && !MODEL_35_FLASH_LITE.equals(model)) {
            model = DEFAULT_MODEL;
        }
        // Minimal is only allowed for Flash Lite: if switching to 3.8 Flash, adjust thinking
        if (MODEL_38_FLASH.equals(model) && THINKING_MINIMAL.equals(getThinkingLevel())) {
            setThinkingLevel(THINKING_HIGH);
        }
        prefs.edit().putString(KEY_MODEL, model).apply();
    }

    public String getThinkingLevel() {
        String level = prefs.getString(KEY_THINKING_LEVEL, DEFAULT_THINKING);
        if (MODEL_38_FLASH.equals(getModel()) && THINKING_MINIMAL.equals(level)) {
            return THINKING_HIGH;
        }
        return level;
    }

    public void setThinkingLevel(String level) {
        if (level == null || level.isEmpty()) {
            level = DEFAULT_THINKING;
        }
        // "minimal solo para flash lite"
        if (THINKING_MINIMAL.equals(level) && !MODEL_35_FLASH_LITE.equals(getModel())) {
            level = THINKING_HIGH;
        }
        prefs.edit().putString(KEY_THINKING_LEVEL, level).apply();
    }

    public String getFormattedModelLabel() {
        String modelName = MODEL_35_FLASH_LITE.equals(getModel()) ? "Gemini 3.5 Flash Lite" : "Gemini 3.8 Flash";
        String level = getThinkingLevel();
        String capitalizedLevel = level.substring(0, 1).toUpperCase() + level.substring(1);
        return modelName + " (" + capitalizedLevel + ")";
    }
}
