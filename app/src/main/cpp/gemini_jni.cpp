#include <jni.h>
#include <string>
#include <memory>
#include "gemini_renderer.h"
#include "gemini_api.h"

static std::unique_ptr<GeminiRenderer> g_renderer = nullptr;

static std::string jstring2string(JNIEnv* env, jstring jStr) {
    if (!jStr) return "";
    const char* chars = env->GetStringUTFChars(jStr, nullptr);
    std::string str = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(jStr, chars);
    return str;
}

static float g_starScale = 1.0f;
static float g_glowIntensity = 1.0f;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_google_geminiclone_GeminiNative_init(JNIEnv* env, jclass clazz, jint width, jint height) {
    if (g_renderer) {
        g_renderer->destroy();
        g_renderer.reset();
    }
    g_renderer = std::make_unique<GeminiRenderer>();
    bool ok = g_renderer->init(width, height);
    if (ok) {
        g_renderer->setStarScale(g_starScale);
        g_renderer->setGlowIntensity(g_glowIntensity);
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_google_geminiclone_GeminiNative_resize(JNIEnv* env, jclass clazz, jint width, jint height) {
    if (g_renderer) {
        g_renderer->resize(width, height);
    }
}

JNIEXPORT void JNICALL
Java_com_google_geminiclone_GeminiNative_render(JNIEnv* env, jclass clazz,
                                                jfloat timeSec, jfloat touchX,
                                                jfloat touchY, jfloat touchPressure) {
    if (g_renderer) {
        g_renderer->render(timeSec, touchX, touchY, touchPressure);
    }
}

JNIEXPORT void JNICALL
Java_com_google_geminiclone_GeminiNative_destroy(JNIEnv* env, jclass clazz) {
    if (g_renderer) {
        g_renderer->destroy();
        g_renderer.reset();
    }
}

JNIEXPORT void JNICALL
Java_com_google_geminiclone_GeminiNative_setStarScale(JNIEnv* env, jclass clazz, jfloat scale) {
    g_starScale = scale;
    if (g_renderer) {
        g_renderer->setStarScale(scale);
    }
}

JNIEXPORT void JNICALL
Java_com_google_geminiclone_GeminiNative_setGlowIntensity(JNIEnv* env, jclass clazz, jfloat intensity) {
    g_glowIntensity = intensity;
    if (g_renderer) {
        g_renderer->setGlowIntensity(intensity);
    }
}

// API Utilities
JNIEXPORT jboolean JNICALL
Java_com_google_geminiclone_GeminiNative_validateApiKey(JNIEnv* env, jclass clazz, jstring jApiKey) {
    std::string key = jstring2string(env, jApiKey);
    return GeminiApiClient::validateApiKeyFormat(key) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_google_geminiclone_GeminiNative_buildEndpoint(JNIEnv* env, jclass clazz, jstring jModel, jstring jApiKey) {
    std::string model = jstring2string(env, jModel);
    std::string key = jstring2string(env, jApiKey);
    std::string url = GeminiApiClient::buildEndpointUrl(model, key);
    return env->NewStringUTF(url.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_google_geminiclone_GeminiNative_buildRequestPayload(JNIEnv* env, jclass clazz, jstring jPrompt, jstring jThinkingLevel) {
    std::string prompt = jstring2string(env, jPrompt);
    std::string thinkingLevel = jstring2string(env, jThinkingLevel);
    std::string payload = GeminiApiClient::buildRequestPayload(prompt, thinkingLevel);
    return env->NewStringUTF(payload.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_google_geminiclone_GeminiNative_buildRequestPayloadWithFile(JNIEnv* env, jclass clazz,
                                                                    jstring jPrompt, jstring jMimeType,
                                                                    jstring jBase64Data, jstring jThinkingLevel) {
    std::string prompt = jstring2string(env, jPrompt);
    std::string mimeType = jstring2string(env, jMimeType);
    std::string base64Data = jstring2string(env, jBase64Data);
    std::string thinkingLevel = jstring2string(env, jThinkingLevel);
    std::string payload = GeminiApiClient::buildRequestPayloadWithFile(prompt, mimeType, base64Data, thinkingLevel);
    return env->NewStringUTF(payload.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_google_geminiclone_GeminiNative_parseTextResponse(JNIEnv* env, jclass clazz, jstring jJson) {
    std::string json = jstring2string(env, jJson);
    std::string text = GeminiApiClient::parseTextResponse(json);
    return env->NewStringUTF(text.c_str());
}

} // extern "C"
