package com.google.geminiclone;

import android.content.Context;
import android.content.SharedPreferences;

public class ApiKeyManager {

    private static final String PREFS_NAME = "gemini_clone_prefs";
    private static final String KEY_API_KEY = "gemini_api_key";
    private static final String KEY_MODEL = "gemini_model";
    private static final String KEY_THINKING_LEVEL = "gemini_thinking_level";
    private static final String KEY_DEFAULT_MODEL = "gemini_default_model";
    private static final String KEY_DEFAULT_THINKING = "gemini_default_thinking_level";
    private static final String KEY_WEB_SEARCH = "gemini_web_search";
    private static final String KEY_SYSTEM_PROMPT = "gemini_system_prompt";

    public static final String MODEL_38_FLASH = "gemini-3.8-flash";
    public static final String MODEL_35_FLASH_LITE = "gemini-3.5-flash-lite";

    public static final String THINKING_HIGH = "high";
    public static final String THINKING_MEDIUM = "medium";
    public static final String THINKING_LOW = "low";
    public static final String THINKING_MINIMAL = "minimal";

    public static final String DEFAULT_MODEL = MODEL_35_FLASH_LITE;
    public static final String DEFAULT_THINKING = THINKING_MEDIUM;

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

    public boolean isWebSearchEnabled() {
        return prefs.getBoolean(KEY_WEB_SEARCH, true);
    }

    public void setWebSearchEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_WEB_SEARCH, enabled).apply();
    }

    public String getSystemPrompt() {
        return prefs.getString(KEY_SYSTEM_PROMPT, "");
    }

    public void setSystemPrompt(String systemPrompt) {
        prefs.edit().putString(KEY_SYSTEM_PROMPT, systemPrompt != null ? systemPrompt.trim() : "").apply();
    }

    private String normalizeModel(String model) {
        if (!MODEL_38_FLASH.equals(model) && !MODEL_35_FLASH_LITE.equals(model)) {
            return DEFAULT_MODEL;
        }
        return model;
    }

    private String normalizeThinkingLevel(String level, String model) {
        if (level == null || level.isEmpty()
                || (!THINKING_HIGH.equals(level) && !THINKING_MEDIUM.equals(level)
                && !THINKING_LOW.equals(level) && !THINKING_MINIMAL.equals(level))) {
            level = DEFAULT_THINKING;
        }
        // Minimal thinking is only supported on 3.5 Flash Lite
        if (THINKING_MINIMAL.equals(level) && !MODEL_35_FLASH_LITE.equals(model)) {
            return DEFAULT_THINKING;
        }
        return level;
    }

    public String getDefaultModel() {
        return normalizeModel(prefs.getString(KEY_DEFAULT_MODEL, DEFAULT_MODEL));
    }

    public String getDefaultThinkingLevel() {
        String defaultModel = getDefaultModel();
        return normalizeThinkingLevel(prefs.getString(KEY_DEFAULT_THINKING, DEFAULT_THINKING), defaultModel);
    }

    public void setDefaultModelAndThinking(String model, String thinkingLevel) {
        String validModel = normalizeModel(model);
        String validThinking = normalizeThinkingLevel(thinkingLevel, validModel);
        prefs.edit()
                .putString(KEY_DEFAULT_MODEL, validModel)
                .putString(KEY_DEFAULT_THINKING, validThinking)
                .putString(KEY_MODEL, validModel)
                .putString(KEY_THINKING_LEVEL, validThinking)
                .apply();
    }

    public void applyDefaultModel() {
        String defaultModel = getDefaultModel();
        String defaultThinking = getDefaultThinkingLevel();
        prefs.edit()
                .putString(KEY_MODEL, defaultModel)
                .putString(KEY_THINKING_LEVEL, defaultThinking)
                .apply();
    }

    public String getModel() {
        return normalizeModel(prefs.getString(KEY_MODEL, DEFAULT_MODEL));
    }

    public void setModel(String model) {
        String validModel = normalizeModel(model);
        SharedPreferences.Editor editor = prefs.edit().putString(KEY_MODEL, validModel);
        if (MODEL_38_FLASH.equals(validModel) && THINKING_MINIMAL.equals(getThinkingLevel())) {
            editor.putString(KEY_THINKING_LEVEL, DEFAULT_THINKING);
        }
        editor.apply();
    }

    public String getThinkingLevel() {
        return normalizeThinkingLevel(prefs.getString(KEY_THINKING_LEVEL, DEFAULT_THINKING), getModel());
    }

    public void setThinkingLevel(String level) {
        String validLevel = normalizeThinkingLevel(level, getModel());
        prefs.edit().putString(KEY_THINKING_LEVEL, validLevel).apply();
    }

    public static String getModelDisplayName(String model) {
        return MODEL_35_FLASH_LITE.equals(model) ? "3.5 Flash Lite" : "3.8 Flash";
    }

    public static String formatModelLabel(String model, String level) {
        String modelName = getModelDisplayName(model);
        String safeLevel = (level != null && !level.isEmpty()) ? level : DEFAULT_THINKING;
        String capitalizedLevel = safeLevel.substring(0, 1).toUpperCase() + safeLevel.substring(1);
        return modelName + " (" + capitalizedLevel + ")";
    }

    public String getFormattedModelLabel() {
        return formatModelLabel(getModel(), getThinkingLevel());
    }
}
