package com.google.geminiclone;

public class GeminiNative {
    static {
        System.loadLibrary("gemini_native");
    }

    // OpenGL ES 3.0 Rendering
    public static native boolean init(int width, int height);
    public static native void resize(int width, int height);
    public static native void render(float timeSec, float touchX, float touchY, float touchPressure);
    public static native void destroy();
    public static native void setStarScale(float scale);
    public static native void setGlowIntensity(float intensity);

    // C++ Gemini API Protocol & Utilities
    public static native boolean validateApiKey(String apiKey);
    public static native String buildEndpoint(String model, String apiKey);
    public static native String buildRequestPayload(String prompt, String thinkingLevel);
    public static native String buildRequestPayloadWithFile(String prompt, String mimeType, String base64Data, String thinkingLevel);
    public static native String parseTextResponse(String jsonResponse);
}
