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

        // Fallback: load popout in offscreen WebView (requires main thread)
        return try {
            suspendCancellableCoroutine { cont ->
                var webView: WebView? = null
                val runnable = Runnable {
                    try {
                        webView = WebView(context.applicationContext).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    view?.postDelayed({
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
                                                    webView?.destroy()
                                                    return@postDelayed
                                                }
                                            }
                                        } catch (_: Exception) {}
                                        if (cont.isActive) cont.resume(null)
                                        webView?.destroy()
                                    }, 3000)
                                }
                            }
                            loadUrl("https://www.twitch.tv/popout/")
                        }
                        // timeout
                        webView?.postDelayed({
                            if (cont.isActive) {
                                cont.resume(null)
                                webView?.destroy()
                            }
                        }, TIMEOUT_MS)
                    } catch (e: Exception) {
                        Log.w(TAG, "WebView init failed", e)
                        if (cont.isActive) cont.resume(null)
                    }
                }
                // Ensure run on main thread
                if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) runnable.run()
                else android.os.Handler(android.os.Looper.getMainLooper()).post(runnable)

                cont.invokeOnCancellation { webView?.destroy() }
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
