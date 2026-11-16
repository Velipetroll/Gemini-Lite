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
        default void onStatusUpdate(String statusText) {}
        default void onSourcesReady(String sourcesJson) {}
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
                String rawSysInstr = (systemInstruction != null) ? systemInstruction : "";
                String sysInstrNoSearch = stripInternetRule(rawSysInstr);
                String firstSysInstr = enableWebSearch ? rawSysInstr : sysInstrNoSearch;
                boolean hasFile = (base64Data != null && !base64Data.isEmpty() && mimeType != null);

                String payload = hasFile
                        ? GeminiNative.buildRequestPayloadWithFile(prompt, mimeType, base64Data, thinkingLevel, firstSysInstr, false)
                        : GeminiNative.buildRequestPayload(prompt, thinkingLevel, firstSysInstr, false);

                HttpResult result = executePost(endpointUrl, payload, callback);

                if (result.statusCode >= 200 && result.statusCode < 300) {
                    String parsedText = GeminiNative.parseTextResponse(result.body);

                    if (enableWebSearch && parsedText.contains("[[SEARCH:")) {
                        String rawSearchTag = extractSearchQuery(parsedText);
                        if (!rawSearchTag.isEmpty()) {
                            String searchQuery = rawSearchTag;
                            String explicitRecency = "";
                            int qdrIdx = rawSearchTag.toLowerCase(java.util.Locale.ROOT).indexOf("|qdr:");
                            if (qdrIdx >= 0) {
                                searchQuery = rawSearchTag.substring(0, qdrIdx).trim();
                                explicitRecency = rawSearchTag.substring(qdrIdx + 5).trim();
                            }
                            String userText = extractCurrentUserText(prompt);
                            String effectiveRecency = WebSearchEngine.detectRecency(
                                    searchQuery + " " + userText, explicitRecency);
                            final String cleanSearchQuery = searchQuery;

                            mainHandler.post(() -> callback.onStatusUpdate("Buscando en la web..."));
                            WebSearchEngine.SearchResult searchResult =
                                    WebSearchEngine.searchWithSources(cleanSearchQuery, effectiveRecency);
                            String webContext = searchResult.text;
                            if (searchResult.sourcesJson != null && searchResult.sourcesJson.length() > 2) {
                                final String srcs = searchResult.sourcesJson;
                                mainHandler.post(() -> callback.onSourcesReady(srcs));
                            }
                            String groundedPrompt = !webContext.isEmpty()
                                    ? prompt + "\n\n[Resultados web actuales para \"" + cleanSearchQuery + "\" — prioriza estos datos sobre tu memoria interna y responde sin emitir etiquetas [[SEARCH:...]]]\n" + webContext
                                    : prompt + "\n\n[No se pudieron obtener resultados web en este momento; aclara si algún dato actual requiere verificación y no emitas etiquetas [[SEARCH:...]]].";
                            String secondPayload = hasFile
                                    ? GeminiNative.buildRequestPayloadWithFile(groundedPrompt, mimeType, base64Data, thinkingLevel, sysInstrNoSearch, false)
                                    : GeminiNative.buildRequestPayload(groundedPrompt, thinkingLevel, sysInstrNoSearch, false);
                            mainHandler.post(() -> callback.onStatusUpdate("Pensando..."));
                            HttpResult secondResult = executePost(endpointUrl, secondPayload, callback);
                            if (secondResult.statusCode >= 200 && secondResult.statusCode < 300) {
                                parsedText = GeminiNative.parseTextResponse(secondResult.body)
                                        .replaceAll("\\[\\[SEARCH:[^\\]]*\\]\\]", "")
                                        .trim();
                            } else {
                                final int errStatus = secondResult.statusCode;
                                final String errMsg = GeminiNative.parseTextResponse(secondResult.body);
                                mainHandler.post(() -> callback.onError("Error (" + errStatus + "): " + errMsg));
                                return;
                            }
                        }
                    }

                    final String finalText = parsedText;
                    mainHandler.post(() -> callback.onSuccess(finalText));
                } else {
                    final int finalStatus = result.statusCode;
                    String parsedError = GeminiNative.parseTextResponse(result.body);
                    mainHandler.post(() -> callback.onError("Error (" + finalStatus + "): " + parsedError));
                }

            } catch (Exception e) {
                mainHandler.post(() -> callback.onError("Error de red: " + e.getMessage()));
            }
        });
    }

    private static String extractSearchQuery(String text) {
        int start = text.indexOf("[[SEARCH:");
        if (start < 0) return "";
        start += 9;
        int end = text.indexOf("]]", start);
        if (end < 0) return "";
        return text.substring(start, end).trim();
    }

    private static String extractCurrentUserText(String prompt) {
        if (prompt == null) return "";
        String marker = "Mensaje actual del usuario:";
        int idx = prompt.lastIndexOf(marker);
        return (idx >= 0) ? prompt.substring(idx + marker.length()).trim() : prompt;
    }

    private static String stripInternetRule(String sysInstr) {
        if (sysInstr == null || sysInstr.isEmpty()) return "";
        return sysInstr.replaceAll("Herramienta de búsqueda web[^\\n]*:[\\s\\S]*?(?=Acciones del dispositivo:)", "");
    }

    private static class HttpResult {
        final int statusCode;
        final String body;
        HttpResult(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }
    }

    private static final int MAX_HTTP_RETRIES = 2;

    private HttpResult executePost(String endpointUrl, String payload, ApiCallback callback) throws Exception {
        HttpResult lastResult = null;
        int ioRetries = 0;

        for (int attempt = 0; attempt <= MAX_HTTP_RETRIES; attempt++) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(endpointUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(55000);
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
                lastResult = new HttpResult(statusCode, responseBuffer.toString());

                boolean retryableHttp = (statusCode == 503 || statusCode == 429
                        || statusCode == 500 || statusCode == 502 || statusCode == 504);
                if (!retryableHttp || attempt == MAX_HTTP_RETRIES) {
                    return lastResult;
                }

                if (callback != null) {
                    mainHandler.post(() -> callback.onStatusUpdate("Pensando..."));
                }
                Thread.sleep(attempt == 0 ? 1200L : 2500L);
            } catch (java.io.IOException e) {
                if (ioRetries >= 1 || attempt == MAX_HTTP_RETRIES) {
                    throw e;
                }
                ioRetries++;
                if (callback != null) {
                    mainHandler.post(() -> callback.onStatusUpdate("Pensando..."));
                }
                Thread.sleep(1000L);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        return lastResult != null ? lastResult : new HttpResult(503, "");
    }
}
