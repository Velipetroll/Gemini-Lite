package com.google.geminiclone;

import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GeminiApiClient {

    public interface ApiCallback {
        void onSuccess(String responseText);
        void onError(String errorMessage);
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public void testConnection(String apiKey, String model, String thinkingLevel, ApiCallback callback) {
        sendPromptInternal(apiKey, model, thinkingLevel, "Hola", null, null, "", false, callback);
    }

    public void sendPrompt(String apiKey, String model, String thinkingLevel, String prompt, ApiCallback callback) {
        sendPromptInternal(apiKey, model, thinkingLevel, prompt, null, null, "", false, callback);
    }

    public void sendPrompt(String apiKey, String model, String thinkingLevel, String prompt, String systemInstruction, boolean enableWebSearch, ApiCallback callback) {
        sendPromptInternal(apiKey, model, thinkingLevel, prompt, null, null, systemInstruction, enableWebSearch, callback);
    }

    public void sendPromptWithFile(String apiKey, String model, String thinkingLevel, String prompt, String mimeType, String base64Data, ApiCallback callback) {
        sendPromptInternal(apiKey, model, thinkingLevel, prompt, mimeType, base64Data, "", false, callback);
    }

    public void sendPromptWithFile(String apiKey, String model, String thinkingLevel, String prompt, String mimeType, String base64Data, String systemInstruction, boolean enableWebSearch, ApiCallback callback) {
        sendPromptInternal(apiKey, model, thinkingLevel, prompt, mimeType, base64Data, systemInstruction, enableWebSearch, callback);
    }

    private void sendPromptInternal(String apiKey, String model, String thinkingLevel, String prompt, String mimeType, String base64Data, String systemInstruction, boolean enableWebSearch, ApiCallback callback) {
        executor.execute(() -> {
            try {
                String endpointUrl = GeminiNative.buildEndpoint(model, apiKey);
                String sysInstr = (systemInstruction != null) ? systemInstruction : "";
                boolean hasFile = (base64Data != null && !base64Data.isEmpty() && mimeType != null);

                android.util.Log.d("GeminiApiClient", "Sending request to " + model);
                String payload = hasFile
                        ? GeminiNative.buildRequestPayloadWithFile(prompt, mimeType, base64Data, thinkingLevel, sysInstr, false)
                        : GeminiNative.buildRequestPayload(prompt, thinkingLevel, sysInstr, false);

                HttpResult result = executePost(endpointUrl, payload);

                final int finalStatus = result.statusCode;
                final String rawResponse = result.body;

                if (finalStatus >= 200 && finalStatus < 300) {
                    String parsedText = GeminiNative.parseTextResponse(rawResponse);
                    mainHandler.post(() -> callback.onSuccess(parsedText));
                } else {
                    String parsedError = GeminiNative.parseTextResponse(rawResponse);
                    mainHandler.post(() -> callback.onError("Error (" + finalStatus + "): " + parsedError));
                }

            } catch (Exception e) {
                mainHandler.post(() -> callback.onError("Error de red: " + e.getMessage()));
            }
        });
    }

    private static class HttpResult {
        final int statusCode;
        final String body;
        HttpResult(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }
    }

    private static HttpResult executePost(String endpointUrl, String payload) throws Exception {
        URL url = new URL(endpointUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = payload.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int statusCode = conn.getResponseCode();
        InputStream is = (statusCode >= 200 && statusCode < 300)
                ? conn.getInputStream()
                : conn.getErrorStream();

        StringBuilder responseBuffer = new StringBuilder();
        if (is != null) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    responseBuffer.append(line.trim());
                }
            }
        }
        return new HttpResult(statusCode, responseBuffer.toString());
    }
}
