package `is`.xyz.mpv.twitch

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * M1 fix: Client-Integrity fallback for Twitch GQL.
 *
 * Original extension: gqltoken.js intercepts fetch https://gql.twitch.tv/integrity via iframe
 * https://www.twitch.tv/popout/ -> document.cookie tw5~gqltoken (JSON {сТокен, чПротухнетПосле})
 * player.js:получитьТокенGql creates hidden iframe, waits 30s, extracts token.
 * Fork intentionally omitted header, but future enforcement will require it.
 *
 * This stub provides same via offscreen WebView. Call getIntegrityToken() when GQL returns
 * "failed integrity check". Returns null if not available (logged) - allows retry without header.
 */
object TwitchIntegrityWebView {
    private const val TAG = "TwitchIntegrity"
    private const val TIMEOUT_MS = 30000L

    // In-memory cache like extension's tw5~gqltoken cookie expiry
    @Volatile private var cachedToken: String? = null
    @Volatile private var cachedExpiry: Long = 0

    suspend fun getIntegrityToken(context: Context): String? {
        // Check cache first
        val now = System.currentTimeMillis()
        if (cachedToken != null && now < cachedExpiry) return cachedToken

        // Try read existing cookie without WebView
        try {
            val cm = CookieManager.getInstance()
            val cookies = cm.getCookie("https://www.twitch.tv") ?: ""
            // Cookie format: tw5~gqltoken=URLEncodedJson
            val match = Regex("tw5~gqltoken=([^;]+)").find(cookies)
            if (match != null) {
                val json = java.net.URLDecoder.decode(match.groupValues[1], "UTF-8")
                val obj = org.json.JSONObject(json)
                val token = obj.optString("сТокен", obj.optString("token", ""))
                val expiry = obj.optLong("чПротухнетПосле", obj.optLong("expiration", 0))
                if (token.isNotEmpty() && expiry > now) {
                    cachedToken = token
                    cachedExpiry = expiry
                    Log.i(TAG, "reused cookie token, expires in ${(expiry - now)/1000}s")
                    return token
                }
            }
        } catch (e: Exception) { Log.w(TAG, "cookie read failed", e) }

        // Fallback: load popout in offscreen WebView (requires main thread) - fixed leak/triple destroy
        return try {
            suspendCancellableCoroutine { cont ->
                var webView: WebView? = null
                var destroyed = false
                fun safeDestroy() { if (!destroyed) { destroyed = true; try { webView?.destroy() } catch (_: Exception) {} } }
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                val timeoutRunnable = Runnable {
                    if (cont.isActive) cont.resume(null)
                    safeDestroy()
                }
                val runnable = Runnable {
                    try {
                        webView = WebView(context.applicationContext).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    view?.postDelayed({
                                        if (!cont.isActive) { safeDestroy(); return@postDelayed }
                                        try {
                                            val cookies2 = CookieManager.getInstance().getCookie("https://www.twitch.tv") ?: ""
                                            val m2 = Regex("tw5~gqltoken=([^;]+)").find(cookies2)
                                            if (m2 != null) {
                                                val j = java.net.URLDecoder.decode(m2.groupValues[1], "UTF-8")
                                                val o = org.json.JSONObject(j)
                                                val t = o.optString("сТокен", o.optString("token", ""))
                                                val exp = o.optLong("чПротухнетПосле", o.optLong("expiration", System.currentTimeMillis() + 3600*1000))
                                                if (t.isNotEmpty()) {
                                                    cachedToken = t
                                                    cachedExpiry = exp
                                                    if (cont.isActive) cont.resume(t)
                                                    handler.removeCallbacks(timeoutRunnable)
                                                    safeDestroy()
                                                    return@postDelayed
                                                }
                                            }
                                        } catch (_: Exception) {}
                                        if (cont.isActive) cont.resume(null)
                                        handler.removeCallbacks(timeoutRunnable)
                                        safeDestroy()
                                    }, 3000)
                                }
                            }
                            loadUrl("https://www.twitch.tv/popout/")
                        }
                        handler.postDelayed(timeoutRunnable, TIMEOUT_MS)
                    } catch (e: Exception) {
                        Log.w(TAG, "WebView init failed", e)
                        handler.removeCallbacks(timeoutRunnable)
                        if (cont.isActive) cont.resume(null)
                        safeDestroy()
                    }
                }
                if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) runnable.run()
                else handler.post(runnable)

                cont.invokeOnCancellation {
                    handler.removeCallbacks(timeoutRunnable)
                    handler.removeCallbacksAndMessages(null)
                    safeDestroy()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "getIntegrityToken failed", e)
            null
        }
    }

    fun clear() {
        cachedToken = null
        cachedExpiry = 0
    }
}
