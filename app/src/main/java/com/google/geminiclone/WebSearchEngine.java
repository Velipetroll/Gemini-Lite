package com.google.geminiclone;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Ultra-optimized on-demand headless WebView search engine:
 * 1. In-memory TTL cache (5 min, 15 entries) -> 0 ms, 0 Chromium wakeups on repeat/follow-up queries.
 * 2. Temporary WebView reuse with immediate onPause()/pauseTimers() (0% idle CPU) & 60s auto-dispose.
 * 3. Timeout-safe cancellation (prevents leaked background loads).
 * 4. Targeted DOM TreeWalker extraction on #rso/#main instead of document.body.innerText (avoids full layout reflow).
 * 5. Aggressive blocking of CSS, fonts, images, media, and Google telemetry/XHRs.
 * 6. Waived renderer priority (API 26+), disabled SafeBrowsing & hardware layer.
 */
public class WebSearchEngine {

    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final long IDLE_DISPOSE_MS = 60 * 1000L;
    private static final byte[] EMPTY_BYTES = new byte[0];

    private static Context appContext;
    private static WebView cachedWebView;
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static final class CacheEntry {
        final String result;
        final long timestamp;

        CacheEntry(String result, long timestamp) {
            this.result = result;
            this.timestamp = timestamp;
        }
    }

    private static final LruCache<String, CacheEntry> searchCache = new LruCache<>(15);

    private static final Runnable idleDisposeRunnable = () -> {
        if (cachedWebView != null) {
            destroyWebView(cachedWebView);
            cachedWebView = null;
        }
    };

    // Fast targeted DOM extraction without triggering a full-page Blink layout reflow (no innerText)
    private static final String EXTRACT_DOM_TEXT_JS =
            "(function(){" +
            "var root=document.getElementById('rso')||document.getElementById('main')||document.body;" +
            "if(!root)return '';" +
            "var w=document.createTreeWalker(root,NodeFilter.SHOW_TEXT,{" +
            "acceptNode:function(n){" +
            "var p=n.parentNode;" +
            "if(!p)return NodeFilter.FILTER_REJECT;" +
            "var t=p.nodeName;" +
            "if(t==='SCRIPT'||t==='STYLE'||t==='NOSCRIPT'||t==='SVG'||t==='TEXTAREA')return NodeFilter.FILTER_REJECT;" +
            "return n.nodeValue&&n.nodeValue.trim()?NodeFilter.FILTER_ACCEPT:NodeFilter.FILTER_REJECT;" +
            "}" +
            "},false);" +
            "var out=[],node,len=0;" +
            "while((node=w.nextNode())&&len<3200){" +
            "var s=node.nodeValue.trim();" +
            "if(s){out.push(s);len+=s.length+1;}" +
            "}" +
            "return out.join(' ');" +
            "})();";

    public static void init(Context context) {
        if (context != null && appContext == null) {
            appContext = context.getApplicationContext();
        }
    }

    public static String search(String rawPrompt) {
        if (appContext == null || rawPrompt == null || rawPrompt.trim().isEmpty()) {
            return "";
        }

        final String query = extractQuery(rawPrompt);
        if (query.isEmpty()) {
            return "";
        }

        final String cacheKey = query.toLowerCase(Locale.ROOT);
        synchronized (searchCache) {
            CacheEntry cached = searchCache.get(cacheKey);
            if (cached != null && (System.currentTimeMillis() - cached.timestamp) < CACHE_TTL_MS) {
                return cached.result;
            }
        }

        final String searchUrl;
        try {
            searchUrl = "https://www.google.com/search?q=" + URLEncoder.encode(query, "UTF-8") + "&hl=es";
        } catch (Exception e) {
            return "";
        }

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> resultRef = new AtomicReference<>("");
        final AtomicBoolean finished = new AtomicBoolean(false);

        mainHandler.post(() -> {
            mainHandler.removeCallbacks(idleDisposeRunnable);
            try {
                WebView webView = getOrCreateWebView();
                webView.onResume();
                webView.resumeTimers();

                webView.setWebViewClient(new WebViewClient() {
                    @Override
                    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                        if (request != null && request.getUrl() != null) {
                            String u = request.getUrl().toString().toLowerCase(Locale.ROOT);
                            if (isBlockedResource(u)) {
                                return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(EMPTY_BYTES));
                            }
                        }
                        return super.shouldInterceptRequest(view, request);
                    }

                    @Override
                    public void onPageFinished(WebView view, String url) {
                        if (finished.get() || "about:blank".equals(url)) return;
                        extractWithRetry(view, 150, true, finished, resultRef, latch);
                    }
                });

                webView.loadUrl(searchUrl);
            } catch (Exception e) {
                if (finished.compareAndSet(false, true)) {
                    latch.countDown();
                }
            }
        });

        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                // Timeout cleanup so WebView never keeps running in background
                if (finished.compareAndSet(false, true)) {
                    mainHandler.post(() -> {
                        if (cachedWebView != null) {
                            cachedWebView.stopLoading();
                            pauseAndScheduleDispose(cachedWebView);
                        }
                    });
                }
            }
        } catch (InterruptedException ignored) {
        }

        String finalResult = resultRef.get();
        if (!finalResult.isEmpty()) {
            synchronized (searchCache) {
                searchCache.put(cacheKey, new CacheEntry(finalResult, System.currentTimeMillis()));
            }
        }
        return finalResult;
    }

    private static void extractWithRetry(
            WebView view,
            long delayMs,
            boolean canRetry,
            AtomicBoolean finished,
            AtomicReference<String> resultRef,
            CountDownLatch latch) {
        mainHandler.postDelayed(() -> {
            if (finished.get()) return;
            try {
                view.evaluateJavascript(EXTRACT_DOM_TEXT_JS, value -> {
                    if (finished.get()) return;
                    String cleaned = unescapeJsString(value);
                    if (canRetry && cleaned.length() < 120) {
                        extractWithRetry(view, 350, false, finished, resultRef, latch);
                        return;
                    }
                    if (finished.compareAndSet(false, true)) {
                        if (cleaned.length() > 3000) {
                            cleaned = cleaned.substring(0, 3000);
                        }
                        resultRef.set(cleaned);
                        pauseAndScheduleDispose(view);
                        latch.countDown();
                    }
                });
            } catch (Exception e) {
                if (finished.compareAndSet(false, true)) {
                    pauseAndScheduleDispose(view);
                    latch.countDown();
                }
            }
        }, delayMs);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private static WebView getOrCreateWebView() {
        if (cachedWebView != null) {
            return cachedWebView;
        }
        WebView webView = new WebView(appContext);
        webView.setWillNotDraw(true);
        webView.setLayerType(View.LAYER_TYPE_NONE, null);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_WAIVED, true);
        }

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setLoadsImagesAutomatically(false);
        settings.setBlockNetworkImage(true);
        settings.setDomStorageEnabled(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setGeolocationEnabled(false);
        settings.setSaveFormData(false);
        settings.setSupportZoom(false);
        settings.setDisplayZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(false);
        }

        cachedWebView = webView;
        return webView;
    }

    private static void pauseAndScheduleDispose(WebView webView) {
        if (webView != null) {
            try {
                webView.stopLoading();
                webView.onPause();
                webView.pauseTimers();
            } catch (Exception ignored) {
            }
        }
        mainHandler.removeCallbacks(idleDisposeRunnable);
        mainHandler.postDelayed(idleDisposeRunnable, IDLE_DISPOSE_MS);
    }

    private static void destroyWebView(WebView webView) {
        if (webView != null) {
            try {
                webView.stopLoading();
                webView.loadUrl("about:blank");
                webView.clearHistory();
                webView.removeAllViews();
                webView.destroy();
            } catch (Exception ignored) {
            }
        }
    }

    private static boolean isBlockedResource(String url) {
        return url.endsWith(".css") || url.contains(".css?")
                || url.endsWith(".woff") || url.endsWith(".woff2") || url.endsWith(".ttf") || url.endsWith(".otf")
                || url.endsWith(".png") || url.endsWith(".jpg") || url.endsWith(".jpeg")
                || url.endsWith(".gif") || url.endsWith(".webp") || url.endsWith(".svg") || url.endsWith(".ico")
                || url.endsWith(".mp4") || url.endsWith(".mp3")
                || url.contains("/gen_204")
                || url.contains("/client_204")
                || url.contains("/complete/search")
                || url.contains("/async/")
                || url.contains("ogs.google.com")
                || url.contains("play.google.com/log")
                || url.contains("accounts.google.com")
                || url.contains("fonts.gstatic.com")
                || url.contains("encrypted-tbn")
                || url.contains("google-analytics.com")
                || url.contains("googletagmanager.com")
                || url.contains("doubleclick.net")
                || url.contains("gstatic.com/og/");
    }

    private static String extractQuery(String rawPrompt) {
        String query = rawPrompt;
        String marker = "Mensaje actual del usuario:";
        int idx = query.lastIndexOf(marker);
        if (idx >= 0) {
            query = query.substring(idx + marker.length());
        }
        query = query.trim();
        if (query.length() > 160) {
            query = query.substring(0, 160);
        }
        return query;
    }

    private static String unescapeJsString(String jsResult) {
        if (jsResult == null || "null".equals(jsResult)) return "";
        String s = jsResult;
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            s = s.substring(1, s.length() - 1);
        }
        return s.replace("\\n", "\n")
                .replace("\\t", " ")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
