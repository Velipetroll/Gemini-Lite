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
    private static final long IDLE_DISPOSE_MS = 20 * 1000L;
    private static final byte[] EMPTY_BYTES = new byte[0];

    private static Context appContext;
    private static WebView cachedWebView;
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static final class SearchResult {
        public final String text;
        public final String sourcesJson;

        public SearchResult(String text, String sourcesJson) {
            this.text = text != null ? text : "";
            this.sourcesJson = sourcesJson != null ? sourcesJson : "[]";
        }
    }

    private static final class CacheEntry {
        final SearchResult result;
        final long timestamp;

        CacheEntry(SearchResult result, long timestamp) {
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

    // Fast targeted DOM extraction (text + up to 6 result links) without full-page reflow
    private static final String EXTRACT_DOM_TEXT_JS =
            "(function(){" +
            "var root=document.getElementById('rso')||document.getElementById('main')||document.body;" +
            "if(!root)return JSON.stringify({text:'',sources:[]});" +
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
            "var srcs=[],seen={},links=document.querySelectorAll('a[href],a[ping],[data-href],[data-url],[data-surl],[data-lpage],[data-curl]');" +
            "var dbg=[];" +
            "for(var i=0;i<links.length;i++){" +
            "var a=links[i],rawHref=a.getAttribute('href')||a.getAttribute('data-surl')||a.getAttribute('data-lpage')||a.getAttribute('data-curl')||a.getAttribute('data-href')||a.getAttribute('data-url')||a.getAttribute('ping')||a.href||'';" +
            "if(dbg.length<24&&rawHref)dbg.push(rawHref.slice(0,80));" +
            "if(srcs.length>=6)continue;" +
            "var href=rawHref;" +
            "var isGoogleGoto=href.indexOf('/goto?url=')!==-1;" +
            "if(isGoogleGoto&&href.startsWith('/')){href='https://www.google.com'+href;}" +
            "if(!isGoogleGoto&&href.indexOf('/url?')!==-1){try{var u=new URL(href,'https://www.google.com');href=u.searchParams.get('q')||u.searchParams.get('url')||href;}catch(e){}}" +
            "if(href.indexOf('/amp/s/')!==-1){href='https://'+href.split('/amp/s/')[1];isGoogleGoto=false;}" +
            "else if(href.indexOf('/amp/')!==-1){href='http://'+href.split('/amp/')[1];isGoogleGoto=false;}" +
            "if(!href.startsWith('http')||seen[href])continue;" +
            "if(!isGoogleGoto){try{var host=new URL(href).hostname.toLowerCase();if(host==='www.google.com'||host==='google.com'||host.endsWith('.google.com')||host.endsWith('.gstatic.com')||host.endsWith('.googleusercontent.com'))continue;}catch(e){continue;}}" +
            "var h3=a.querySelector('h3,[role=\"heading\"]');var title=(h3?h3.textContent:a.textContent)||'';title=title.replace(/\\s+/g,' ').trim();" +
            "if(isGoogleGoto&&title.length<8)continue;" +
            "if(title.length<2){try{title=new URL(href).hostname;}catch(e){continue;}}" +
            "seen[href]=1;srcs.push({title:title.slice(0,90),url:href});" +
            "}" +
            "var dbgCites=[];" +
            "if(srcs.length===0){" +
            "var cites=document.querySelectorAll('cite');" +
            "for(var j=0;j<cites.length;j++){" +
            "var rawC=(cites[j].textContent||'').trim();if(dbgCites.length<8)dbgCites.push(rawC);" +
            "if(srcs.length>=6)continue;" +
            "var ct=rawC.split(/[\\s›>]/)[0].trim();" +
            "if(!ct)continue;" +
            "var cu=ct.startsWith('http')?ct:'https://'+ct;" +
            "try{var ch=new URL(cu).hostname.toLowerCase();if(ch.indexOf('.')===-1||ch.endsWith('.google.com'))continue;if(!seen[cu]){seen[cu]=1;srcs.push({title:ch,url:cu});}}catch(e){}" +
            "}" +
            "}" +
            "return JSON.stringify({text:out.join(' '),sources:srcs,dbg:{url:location.href.slice(0,80),nLinks:links.length,links:dbg,cites:dbgCites}});" +
            "})();";

    public static void init(Context context) {
        if (context != null && appContext == null) {
            appContext = context.getApplicationContext();
        }
    }

    public static String search(String rawPrompt) {
        return searchWithSources(rawPrompt, "").text;
    }

    public static SearchResult searchWithSources(String rawPrompt) {
        return searchWithSources(rawPrompt, "");
    }

    public static SearchResult searchWithSources(String rawPrompt, String explicitRecency) {
        if (appContext == null || rawPrompt == null || rawPrompt.trim().isEmpty()) {
            return new SearchResult("", "[]");
        }

        final String query = extractQuery(rawPrompt);
        if (query.isEmpty()) {
            return new SearchResult("", "[]");
        }

        final String recency = detectRecency(query, explicitRecency);
        final long cacheTtlMs = getCacheTtlMs(recency);
        final String cacheKey = query.toLowerCase(Locale.ROOT) + "|" + recency;
        synchronized (searchCache) {
            CacheEntry cached = searchCache.get(cacheKey);
            if (cached != null && (System.currentTimeMillis() - cached.timestamp) < cacheTtlMs) {
                return cached.result;
            }
        }

        SearchResult result = executeWebSearch(query, recency);
        // Si un filtro muy estricto (1h o 24h) devuelve casi nada, relajar 1 nivel para no fallar
        if (result.text.length() < 120 && !recency.isEmpty()) {
            String relaxed = "h".equals(recency) ? "d" : ("d".equals(recency) ? "w" : "");
            SearchResult fallback = executeWebSearch(query, relaxed);
            if (fallback.text.length() > result.text.length()) {
                result = fallback;
            }
        }

        if (!result.text.isEmpty()) {
            synchronized (searchCache) {
                searchCache.put(cacheKey, new CacheEntry(result, System.currentTimeMillis()));
            }
        }
        return result;
    }

    private static SearchResult executeWebSearch(String query, String recency) {
        final String searchUrl;
        try {
            String base = "https://www.google.com/search?q=" + URLEncoder.encode(query, "UTF-8") + "&hl=es&udm=14";
            searchUrl = (recency != null && !recency.isEmpty()) ? base + "&tbs=qdr:" + recency : base;
        } catch (Exception e) {
            return new SearchResult("", "[]");
        }

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<SearchResult> resultRef = new AtomicReference<>(new SearchResult("", "[]"));
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

        return resultRef.get();
    }

    public static String detectRecency(String text, String explicitRecency) {
        if (explicitRecency != null) {
            String r = explicitRecency.trim().toLowerCase(Locale.ROOT);
            if ("h".equals(r) || "d".equals(r) || "w".equals(r) || "m".equals(r)) {
                return r;
            }
        }
        if (text == null) return "";
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("ultima hora") || lower.contains("última hora")
                || lower.contains("last hour") || lower.contains("hace minutos")) {
            return "h";
        }
        if (lower.contains("hoy") || lower.contains("ahora") || lower.contains("en vivo")
                || lower.contains("ultimo minuto") || lower.contains("último minuto")
                || lower.contains("ultimas 24") || lower.contains("últimas 24")
                || lower.contains("today") || lower.contains("breaking")) {
            return "d";
        }
        if (lower.contains("reciente") || lower.contains("esta semana")
                || lower.contains("ultimos dias") || lower.contains("últimos días")
                || lower.contains("recent") || lower.contains("this week")
                || lower.contains("latest")) {
            return "w";
        }
        if (lower.contains("este mes") || lower.contains("this month")) {
            return "m";
        }
        return "";
    }

    private static long getCacheTtlMs(String recency) {
        if ("h".equals(recency)) return 30 * 1000L;
        if ("d".equals(recency)) return 2 * 60 * 1000L;
        return CACHE_TTL_MS;
    }

    private static void extractWithRetry(
            WebView view,
            long delayMs,
            boolean canRetry,
            AtomicBoolean finished,
            AtomicReference<SearchResult> resultRef,
            CountDownLatch latch) {
        mainHandler.postDelayed(() -> {
            if (finished.get()) return;
            try {
                view.evaluateJavascript(EXTRACT_DOM_TEXT_JS, value -> {
                    if (finished.get()) return;
                    SearchResult parsed = parseJsPayload(value);
                    android.util.Log.d("WebSearchEngine", "extract canRetry=" + canRetry + " textLen=" + parsed.text.length() + " sources=" + parsed.sourcesJson);
                    if (canRetry && (parsed.text.length() < 120 || "[]".equals(parsed.sourcesJson))) {
                        extractWithRetry(view, 350, false, finished, resultRef, latch);
                        return;
                    }
                    if (finished.compareAndSet(false, true)) {
                        String txt = parsed.text.length() > 3000 ? parsed.text.substring(0, 3000) : parsed.text;
                        resultRef.set(new SearchResult(txt, parsed.sourcesJson));
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
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setSupportMultipleWindows(false);
        settings.setUseWideViewPort(false);
        settings.setLoadWithOverviewMode(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setBuiltInZoomControls(false);
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

    /** Libera el WebView inmediatamente (llamar desde onTrimMemory / onDestroy). */
    public static void trim() {
        mainHandler.post(() -> {
            mainHandler.removeCallbacks(idleDisposeRunnable);
            if (cachedWebView != null) {
                destroyWebView(cachedWebView);
                cachedWebView = null;
            }
        });
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

    private static SearchResult parseJsPayload(String jsResult) {
        if (jsResult == null || "null".equals(jsResult)) return new SearchResult("", "[]");
        try {
            Object tok = new org.json.JSONTokener(jsResult).nextValue();
            String raw = (tok instanceof String) ? (String) tok : jsResult;
            org.json.JSONObject obj = new org.json.JSONObject(raw);
            String text = obj.optString("text", "").replaceAll("\\s+", " ").trim();
            org.json.JSONArray arr = obj.optJSONArray("sources");
            if (arr == null || arr.length() == 0) {
                android.util.Log.d("WebSearchEngine", "empty sources dbg=" + obj.opt("dbg"));
            }
            return new SearchResult(text, arr != null ? arr.toString() : "[]");
        } catch (Exception e) {
            android.util.Log.d("WebSearchEngine", "parseJsPayload err=" + e + " raw=" + jsResult.substring(0, Math.min(200, jsResult.length())));
            String fallback = unescapeJsString(jsResult);
            return new SearchResult(fallback, "[]");
        }
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
