package `is`.xyz.mpv.twitch

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Performant parity for twitch_5-2026.6.21 dual-list ad handling.
 *
 * Original: ОбновлениеСписковСРекламой(false) polls site master every ~2s,
 * ОбновлениеСписковБезРекламы(true) polls picture-by-picture only when ad active,
 * checks этотСписокЗаканчиваетсяРекламой (last segment лРеклама != live || DATERANGE stitched-ad).
 * We replicate same practical behavior but via mpv-native HLS switching (loadfile replace)
 * instead of MSE/WASM queue. Much lighter for battery: one coroutine, 2-5KB polls, no WASM.
 *
 * For mpv: master = usher URL (site vs picture-by-picture), media = variant URL (e.g. chunked).
 * When site media ends with ad -> switch mpv to noAd variant; when ad ends -> switch back.
 * Handles token TTL (15m) and offline detection like original.
 */
class TwitchAdMonitor(
    private val context: Context,
    private val channel: String,
    private val preferredQualityId: String, // e.g. chunked, 720p60, audio_only
    private val onSwitch: (newUrl: String, reason: String, isAudioOnly: Boolean) -> Unit,
    private val scope: CoroutineScope
) {
    private var job: Job? = null
    private var isInAdMode = false
    @Volatile private var stopped = false

    fun start(initialMasterUrl: String, initialNoAdMasterUrl: String?) {
        if (job?.isActive == true) return
        stopped = false
        job = scope.launch {
            var siteMaster = initialMasterUrl
            var noAdMaster: String? = initialNoAdMasterUrl
            // Lazy fetch noAd master only when needed, but pre-warm if possible
            var lastSiteVariants: List<TwitchService.Variant>? = null
            var lastNoAdVariants: List<TwitchService.Variant>? = null

            while (isActive && !stopped) {
                try {
                    // Refresh site master if expired (TwitchService caches 15m)
                    siteMaster = try {
                        TwitchService.getHlsMasterUrl(context, channel, withoutAds = false)
                    } catch (e: Exception) {
                        Log.w(TAG, "site master refresh failed", e)
                        siteMaster // keep old, retry next loop
                    }

                    // Resolve preferred variant for site
                    if (lastSiteVariants == null || shouldRefreshVariants()) {
                        lastSiteVariants = try { TwitchService.fetchVariants(siteMaster) } catch (e: Exception) {
                            Log.w(TAG, "site variants fetch failed", e)
                            // offline or token rejected -> exponential backoff
                            delay(4000)
                            continue
                        }
                    }
                    val siteVariant = selectVariant(lastSiteVariants!!, preferredQualityId)
                        ?: lastSiteVariants!!.firstOrNull { !it.isAudioOnly } ?: lastSiteVariants!!.first()

                    // Fetch media playlist for this variant to detect ad
                    val siteMediaText = try { httpGetQuick(siteVariant.url) } catch (e: Exception) {
                        Log.w(TAG, "site media fetch failed", e)
                        delay(2000)
                        continue
                    }
                    val siteHasAd = isAdInMedia(siteMediaText)
                    val siteEnded = siteMediaText.contains("#EXT-X-ENDLIST")

                    if (siteEnded) {
                        Log.i(TAG, "site ENDLIST -> broadcast ended")
                        delay(5000)
                        continue
                    }

                    if (siteHasAd && !isInAdMode) {
                        // Enter ad mode -> switch to noAd
                        Log.i(TAG, "ad detected in site media, switching to noAd")
                        if (noAdMaster == null) {
                            noAdMaster = try { TwitchService.getHlsMasterUrl(context, channel, withoutAds = true) } catch (e: Exception) {
                                Log.w(TAG, "noAd master fetch failed", e)
                                delay(2000)
                                continue
                            }
                        }
                        // refresh noAd variants
                        lastNoAdVariants = try { TwitchService.fetchVariants(noAdMaster!!) } catch (e: Exception) {
                            Log.w(TAG, "noAd variants failed", e)
                            delay(2000)
                            continue
                        }
                        val noAdVariant = selectVariant(lastNoAdVariants!!, preferredQualityId)
                            ?: lastNoAdVariants!!.firstOrNull { !it.isAudioOnly } ?: lastNoAdVariants!!.first()
                        // Validate noAd media is clean
                        val noAdMedia = try { httpGetQuick(noAdVariant.url) } catch (_: Exception) { "" }
                        if (isAdInMedia(noAdMedia)) {
                            Log.w(TAG, "noAd media still has ad, skipping switch")
                            delay(2000)
                            continue
                        }
                        isInAdMode = true
                        onSwitch(noAdVariant.url, "ad_start", noAdVariant.isAudioOnly)
                        // ad pods are 30-180s, poll faster while in ad
                        delay(2000)
                        continue
                    } else if (!siteHasAd && isInAdMode) {
                        // Ad ended -> switch back to site
                        Log.i(TAG, "ad ended, switching back to site")
                        // refresh site variants (may have new session)
                        lastSiteVariants = try { TwitchService.fetchVariants(siteMaster) } catch (e: Exception) {
                            Log.w(TAG, "site variants re-fetch failed", e)
                            delay(2000)
                            continue
                        }
                        val backVariant = selectVariant(lastSiteVariants!!, preferredQualityId)
                            ?: lastSiteVariants!!.first()
                        isInAdMode = false
                        onSwitch(backVariant.url, "ad_end", backVariant.isAudioOnly)
                        delay(1500)
                        continue
                    }

                    // Normal live, poll interval based on targetDuration ~2s, ad mode faster
                    val interval = if (isInAdMode) 2000L else extractTargetDuration(siteMediaText) * 1000L / 2
                    delay(interval.coerceIn(1500, 4000))

                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    Log.w(TAG, "monitor loop error", e)
                    delay(3000)
                }
            }
        }
    }

    fun stop() {
        stopped = true
        job?.cancel()
        job = null
        isInAdMode = false
    }

    private fun shouldRefreshVariants(): Boolean {
        // Simple: refresh every loop for live, but we cache last Variants for 30s to save fetch
        // For now always re-fetch master every 60s via getHlsMasterUrl TTL handles token
        return false
    }

    private fun selectVariant(variants: List<TwitchService.Variant>, prefId: String): TwitchService.Variant? {
        // Mirror player.js:выбратьВариантТрансляции
        var v = variants.find { it.id == prefId }
        if (v != null) return v
        if (prefId == "chunked" || prefId == "audio_only") {
            return variants.firstOrNull()
        }
        // try bitrate fallback: find variant with bandwidth <= saved
        // For mpv parity we just pick highest non-audio for video, or source
        return variants.firstOrNull { !it.isAudioOnly && it.id != "audio_only" }
            ?: variants.firstOrNull()
    }

    // Lightweight GET for media playlist (small, ~2KB)
    private fun httpGetQuick(urlStr: String): String {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.setRequestProperty("Accept", "application/vnd.apple.mpegurl,*/*")
            conn.setRequestProperty("Referer", "https://www.twitch.tv/")
            conn.setRequestProperty("Origin", "https://www.twitch.tv")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            val code = conn.responseCode
            if (code !in 200..299) throw RuntimeException("HTTP $code for $urlStr")
            return BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).readText()
        } finally { conn.disconnect() }
    }

    /** Ad detection parity: check DATERANGE stitched-ad OR last segment != live */
    internal fun isAdInMedia(text: String): Boolean {
        if (text.isEmpty()) return false
        // Original: РазобратьСписок checks #EXT-X-DATERANGE CLASS="twitch-stitched-ad"
        if (text.contains("twitch-stitched-ad")) return true
        if (text.contains("X-TV-TWITCH-AD-")) return true
        // Fallback: check last #EXTINF segment name != live
        // Original: этоРекламныйСегмент(sName) => sName != "" && sName != "live"
        // Extract last EXTINF line
        val lastInf = text.lines().filter { it.startsWith("#EXTINF") }.lastOrNull() ?: return false
        val name = lastInf.substringAfter(",", "").trim()
        // name is segment name like "live" or ad id; if not live and not empty => ad
        // Also check if last segment URL contains ad token
        val lastUrl = text.lines().lastOrNull { it.isNotBlank() && !it.startsWith("#") } ?: ""
        if (lastUrl.contains("stitched") || lastUrl.contains("/ad/")) return true
        return name.isNotEmpty() && name != "live"
    }

    private fun extractTargetDuration(text: String): Long {
        val m = Regex("#EXT-X-TARGETDURATION:(\\d+)").find(text) ?: return 2
        return try { m.groupValues[1].toLong() } catch (_: Exception) { 2 }
    }

    companion object {
        private const val TAG = "TwitchAdMonitor"
    }
}
