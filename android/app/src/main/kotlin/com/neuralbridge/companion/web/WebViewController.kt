package com.neuralbridge.companion.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * WebViewController — an app-owned, off-screen [WebView] driven entirely in-process.
 *
 * This is the "Option A" web driver for NeuralBridge: instead of attaching to an
 * external Chrome instance over the Chrome DevTools Protocol socket, we host our own
 * WebView inside the companion app and drive it via [WebView.evaluateJavascript].
 *
 * Properties:
 *  - In-process, no ADB / no external browser — matches NeuralBridge's low-latency model.
 *  - `setWebContentsDebuggingEnabled(true)` additionally exposes the WebView over CDP
 *    (`localabstract:webview_devtools_remote_<pid>`), so external CDP clients can also
 *    attach for debugging if desired.
 *
 * Threading: [WebView] is strictly single-threaded and must be created and called on the
 * main (UI) thread. Every public suspend function hops to [Dispatchers.Main] internally,
 * so callers (e.g. MCP tool handlers running on background dispatchers) can call freely.
 *
 * Rendering: a detached WebView does not lay out or run rAF-driven JS reliably. We attach
 * a 1x1, fully transparent view to the window via WindowManager using
 * `TYPE_ACCESSIBILITY_OVERLAY`, which does NOT require the SYSTEM_ALERT_WINDOW permission
 * (it is granted implicitly to an active AccessibilityService).
 */
class WebViewController(private val context: Context) {

    companion object {
        private const val TAG = "WebViewController"
        private const val DEFAULT_LOAD_TIMEOUT_MS = 30_000L
        private const val DEFAULT_USER_AGENT_SUFFIX = " NeuralBridgeWebView"

        // ------------------------------------------------------------------
        // Pure JS/DOM string helpers.
        //
        // These are the machinery behind web_eval_js / web_query / web_get_text /
        // web_wait_for: embedding user-supplied CSS selectors into JS and decoding
        // the JSON-encoded values the WebView returns. They are stateless and kept
        // in the companion (no Context) so the JVM unit suite can pin them down.
        //
        // The exact failure modes they guard against are the ones that LOOK like
        // "web_query found nothing": a selector containing quotes/backslashes that
        // yields malformed JS, or a decode step that corrupts a result. A selector
        // that matches no elements returning `[]`/null is *correct* behaviour, not
        // a bug -- see WebJavascriptBridgeTest.
        // ------------------------------------------------------------------

        /** Build the JS executed by web_query for the given CSS [selector]. Pure for testability. */
        internal fun buildQueryScript(selector: String, all: Boolean): String {
            val nodesExpr = if (all) {
                "Array.from(document.querySelectorAll(sel))"
            } else {
                "(function(){var e=document.querySelector(sel);return e?[e]:[];})()"
            }
            return """
                (function(){
                  try {
                    var sel = ${jsString(selector)};
                    var nodes = $nodesExpr;
                    return JSON.stringify(nodes.map(function(e){
                      var r = e.getBoundingClientRect();
                      return {
                        tag: e.tagName ? e.tagName.toLowerCase() : null,
                        id: e.id || null,
                        text: (e.innerText || e.textContent || '').trim().slice(0, 200),
                        name: e.getAttribute ? e.getAttribute('name') : null,
                        type: e.getAttribute ? e.getAttribute('type') : null,
                        href: e.getAttribute ? e.getAttribute('href') : null,
                        rect: { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) },
                        visible: !!(r.width && r.height)
                      };
                    }));
                  } catch (err) { return JSON.stringify({error: String(err)}); }
                })()
            """.trimIndent()
        }

        /** Safely embed an arbitrary string as a JS string literal. */
        internal fun jsString(s: String): String {
            val sb = StringBuilder("\"")
            for (c in s) {
                when (c) {
                    '\\' -> sb.append("\\\\")
                    '"' -> sb.append("\\\"")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    '\u2028' -> sb.append("\\u2028")
                    '\u2029' -> sb.append("\\u2029")
                    else -> sb.append(c)
                }
            }
            sb.append("\"")
            return sb.toString()
        }

        /**
         * Decode the JSON string the WebView returns from evaluateJavascript.
         * Returns null for the literal "null". Non-string JSON (numbers, objects) is
         * returned verbatim so callers can still inspect it.
         */
        internal fun decodeJsString(raw: String): String? {
            if (raw == "null") return null
            if (raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
                val inner = raw.substring(1, raw.length - 1)
                val sb = StringBuilder(inner.length)
                var i = 0
                while (i < inner.length) {
                    val c = inner[i]
                    if (c == '\\' && i + 1 < inner.length) {
                        val n = inner[i + 1]
                        when (n) {
                            'n' -> { sb.append('\n'); i += 2 }
                            'r' -> { sb.append('\r'); i += 2 }
                            't' -> { sb.append('\t'); i += 2 }
                            '"' -> { sb.append('"'); i += 2 }
                            '\\' -> { sb.append('\\'); i += 2 }
                            '/' -> { sb.append('/'); i += 2 }
                            'u' -> {
                                if (i + 5 < inner.length + 1 && i + 6 <= inner.length) {
                                    val hex = inner.substring(i + 2, i + 6)
                                    val code = hex.toIntOrNull(16)
                                    if (code != null) { sb.append(code.toChar()); i += 6 }
                                    else { sb.append(c); i += 1 }
                                } else { sb.append(c); i += 1 }
                            }
                            else -> { sb.append(n); i += 2 }
                        }
                    } else {
                        sb.append(c); i += 1
                    }
                }
                return sb.toString()
            }
            // Not a quoted string (number/boolean/object) — return as-is.
            return raw
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var windowManager: WindowManager? = null

    @Volatile
    private var attached = false

    /** Tracks the most recent page-load completion so navigation can await it. */
    @Volatile
    private var pageLoadDeferred: CompletableDeferred<LoadResult>? = null

    data class LoadResult(val success: Boolean, val url: String?, val errorMessage: String?)

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * Ensure the WebView exists and is attached. Safe to call repeatedly; creation
     * happens exactly once. Must resolve on the main thread.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun ensureInitialized() = withContext(Dispatchers.Main) {
        if (webView != null) return@withContext

        WebView.setWebContentsDebuggingEnabled(true)

        val wv = WebView(context)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            mediaPlaybackRequiresUserGesture = true
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = userAgentString + DEFAULT_USER_AGENT_SUFFIX
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }
        }
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                pageLoadDeferred?.let { if (!it.isCompleted) it.complete(LoadResult(true, url, null)) }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                // Only treat main-frame failures as navigation errors.
                if (request?.isForMainFrame == true) {
                    val msg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        "${error?.errorCode}: ${error?.description}"
                    } else "load error"
                    pageLoadDeferred?.let { if (!it.isCompleted) it.complete(LoadResult(false, request.url?.toString(), msg)) }
                }
            }
        }

        attachToWindow(wv)
        webView = wv
        Log.i(TAG, "WebView initialized (debugging enabled)")
    }

    private fun attachToWindow(wv: WebView) {
        if (attached) return
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // Determine the real display size so the WebView lays out and PAINTS at full
        // resolution (a 1x1 overlay renders nothing capturable). The view stays invisible:
        // fully transparent pixel format, alpha 0, non-focusable, non-touchable, and the
        // overlay sits behind everything. The user never sees or interacts with it, but
        // web_screenshot can now capture a full-size bitmap.
        val (w, h) = displaySize(wm)
        val params = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSPARENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            alpha = 0f            // fully invisible to the user
            width = w
            height = h
        }
        // Explicitly measure+layout at full size so content renders before any capture.
        wv.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY)
        )
        wv.layout(0, 0, w, h)
        try {
            wm.addView(wv, params)
            windowManager = wm
            attached = true
            Log.i(TAG, "WebView overlay attached at ${w}x${h} (invisible, alpha=0)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach WebView overlay; continuing detached", e)
        }
    }

    /** Real display size in pixels (full frame incl. system decoration). */
    private fun displaySize(wm: WindowManager): Pair<Int, Int> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val b = wm.currentWindowMetrics.bounds
                (if (b.width() > 0) b.width() else 1080) to (if (b.height() > 0) b.height() else 1920)
            } else {
                val dm = context.resources.displayMetrics
                dm.widthPixels to dm.heightPixels
            }
        } catch (e: Exception) {
            1080 to 1920
        }
    }

    /** Tear down the WebView and detach from the window. */
    fun destroy() {
        mainHandler.post {
            try {
                val wv = webView
                if (wv != null) {
                    if (attached) {
                        try {
                            windowManager?.removeView(wv)
                        } catch (e: Exception) {
                            Log.w(TAG, "removeView failed", e)
                        }
                    }
                    wv.stopLoading()
                    wv.destroy()
                }
            } catch (e: Exception) {
                Log.e(TAG, "destroy failed", e)
            } finally {
                webView = null
                windowManager = null
                attached = false
            }
        }
    }

    // ------------------------------------------------------------------
    // Navigation
    // ------------------------------------------------------------------

    /** Navigate to [url] and await page load (or timeout). */
    suspend fun navigate(url: String, timeoutMs: Long = DEFAULT_LOAD_TIMEOUT_MS): LoadResult {
        ensureInitialized()
        val deferred = CompletableDeferred<LoadResult>()
        withContext(Dispatchers.Main) {
            pageLoadDeferred = deferred
            webView?.loadUrl(url) ?: deferred.complete(LoadResult(false, url, "WebView not available"))
        }
        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
        return result ?: LoadResult(false, url, "Navigation timed out after ${timeoutMs}ms")
    }

    /** Current URL, or null if nothing loaded. */
    suspend fun currentUrl(): String? = withContext(Dispatchers.Main) { webView?.url }

    /** Current page title. */
    suspend fun title(): String? = withContext(Dispatchers.Main) { webView?.title }

    // ------------------------------------------------------------------
    // JavaScript evaluation
    // ------------------------------------------------------------------

    /**
     * Evaluate [script] and return the raw result string produced by the WebView.
     *
     * The WebView returns a JSON-encoded value (e.g. a quoted string, a number, or
     * the literal `null`). Callers that want the unwrapped value can use [evalString].
     */
    suspend fun evaluate(script: String): String = withContext(Dispatchers.Main) {
        val wv = webView ?: return@withContext "null"
        suspendCoroutine { cont ->
            wv.evaluateJavascript(script) { value ->
                cont.resume(value ?: "null")
            }
        }
    }

    /**
     * Evaluate an expression that is expected to yield a string, unwrapping the
     * JSON quoting the WebView applies. Returns null for `null`/undefined results.
     */
    suspend fun evalString(script: String): String? {
        val raw = evaluate(script)
        return decodeJsString(raw)
    }

    // ------------------------------------------------------------------
    // DOM helpers (built on top of evaluate)
    // ------------------------------------------------------------------

    /** Return outerHTML for [selector] (or document.documentElement if null). */
    suspend fun getDom(selector: String?, maxChars: Int): String? {
        val target = if (selector == null) {
            "document.documentElement.outerHTML"
        } else {
            "(function(){var e=document.querySelector(${jsString(selector)});return e?e.outerHTML:null;})()"
        }
        val html = evalString(target) ?: return null
        return if (html.length > maxChars) html.substring(0, maxChars) + "\n<!-- truncated -->" else html
    }

    /** Return innerText for [selector] (or body if null). */
    suspend fun getText(selector: String?, maxChars: Int): String? {
        val target = if (selector == null) {
            "document.body ? document.body.innerText : ''"
        } else {
            "(function(){var e=document.querySelector(${jsString(selector)});return e?e.innerText:null;})()"
        }
        val text = evalString(target) ?: return null
        return if (text.length > maxChars) text.substring(0, maxChars) + "\n<!-- truncated -->" else text
    }

    /**
     * Query [selector]. When [all] is false returns a single-element JSON array (or empty),
     * otherwise all matches. Each entry has tag, text, and bounding-rect fields.
     */
    suspend fun query(selector: String, all: Boolean): String {
        val script = buildQueryScript(selector, all)
        return evalString(script) ?: "[]"
    }

    /** Click the first element matching [selector]. Returns true if an element was clicked. */
    suspend fun click(selector: String): Boolean {
        val script = """
            (function(){
              var e = document.querySelector(${jsString(selector)});
              if (!e) return false;
              e.scrollIntoView({block:'center', inline:'center'});
              if (typeof e.click === 'function') { e.click(); }
              else { e.dispatchEvent(new MouseEvent('click', {bubbles:true, cancelable:true})); }
              return true;
            })()
        """.trimIndent()
        return evaluate(script).trim() == "true"
    }

    /**
     * Set the value of an input/textarea matching [selector] and dispatch input/change
     * events so frameworks observe the change. Returns true on success.
     */
    suspend fun type(selector: String, text: String): Boolean {
        val script = """
            (function(){
              var e = document.querySelector(${jsString(selector)});
              if (!e) return false;
              e.focus();
              var proto = Object.getPrototypeOf(e);
              var desc = Object.getOwnPropertyDescriptor(proto, 'value');
              if (desc && desc.set) { desc.set.call(e, ${jsString(text)}); }
              else { e.value = ${jsString(text)}; }
              e.dispatchEvent(new Event('input', {bubbles:true}));
              e.dispatchEvent(new Event('change', {bubbles:true}));
              return true;
            })()
        """.trimIndent()
        return evaluate(script).trim() == "true"
    }

    /** Poll until [selector] exists in the DOM or [timeoutMs] elapses. */
    suspend fun waitFor(selector: String, timeoutMs: Long, pollMs: Long = 200L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val existsScript = "(document.querySelector(${jsString(selector)}) !== null)"
        while (System.currentTimeMillis() < deadline) {
            if (evaluate(existsScript).trim() == "true") return true
            delay(pollMs)
        }
        return false
    }

    /** Capture the current WebView contents as a JPEG byte array. */
    suspend fun screenshot(quality: Int = 80): ByteArray? = withContext(Dispatchers.Main) {
        val wv = webView ?: return@withContext null
        // Overlay is attached at full display size; ensure a layout pass has happened.
        val width = if (wv.width > 0) wv.width else displaySize(
            context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        ).first
        val height = if (wv.height > 0) wv.height else displaySize(
            context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        ).second
        if (wv.width == 0 || wv.height == 0) {
            wv.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY)
            )
            wv.layout(0, 0, width, height)
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // Paint an opaque white backdrop first so pages with transparent regions
        // don't capture as black/empty.
        canvas.drawColor(android.graphics.Color.WHITE)
        wv.draw(canvas)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        bitmap.recycle()
        out.toByteArray()
    }

}
