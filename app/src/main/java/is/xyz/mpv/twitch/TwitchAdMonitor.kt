package `is`.xyz.mpv.twitch

import android.content.Context
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

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
    private var lastSiteVariantsFetchMs = 0L
    private var consecutiveFails = 0

    @Synchronized
    fun start(initialMasterUrl: String, initialNoAdMasterUrl: String?) {
        if (job?.isActive == true) return
        stopped = false
        // Run polling on IO dispatcher, only onSwitch on Main
        job = scope.launch(Dispatchers.IO) {
            var siteMaster = initialMasterUrl
            var noAdMaster: String? = initialNoAdMasterUrl
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

                    // Resolve preferred variant for site - refresh every 60s or on failure
                    if (lastSiteVariants == null || shouldRefreshVariants() || System.currentTimeMillis() - lastSiteVariantsFetchMs > 60_000) {
                        lastSiteVariants = try {
                            TwitchService.fetchVariants(siteMaster).also { lastSiteVariantsFetchMs = System.currentTimeMillis(); consecutiveFails = 0 }
                        } catch (e: Exception) {
                            Log.w(TAG, "site variants fetch failed", e)
                            consecutiveFails++
                            delay((2000L * (1 shl consecutiveFails.coerceAtMost(4)) + Random.nextLong(500)).coerceAtMost(16000))
                            continue
                        }
                    }
                    val siteVariant = selectVariant(lastSiteVariants!!, preferredQualityId)
                        ?: lastSiteVariants!!.firstOrNull { !it.isAudioOnly } ?: lastSiteVariants!!.first()

                    // Fetch media playlist for this variant to detect ad - on IO
                    val siteMediaText = try { httpGetQuick(siteVariant.url) } catch (e: Exception) {
                        Log.w(TAG, "site media fetch failed", e)
                        consecutiveFails++
                        delay((2000L * (1 shl consecutiveFails.coerceAtMost(4)) + Random.nextLong(300)).coerceAtMost(12000))
                        continue
                    }
                    consecutiveFails = 0
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
                        withContext(Dispatchers.Main) { onSwitch(noAdVariant.url, "ad_start", noAdVariant.isAudioOnly) }
                        // ad pods are 30-180s, poll faster while in ad
                        delay(2000)
                        continue
                    } else if (!siteHasAd && isInAdMode) {
                        // Ad ended -> switch back to site
                        Log.i(TAG, "ad ended, switching back to site")
                        // refresh site variants (may have new session)
                        lastSiteVariants = try { TwitchService.fetchVariants(siteMaster).also { lastSiteVariantsFetchMs = System.currentTimeMillis() } } catch (e: Exception) {
                            Log.w(TAG, "site variants re-fetch failed", e)
                            delay(2000)
                            continue
                        }
                        val backVariant = selectVariant(lastSiteVariants!!, preferredQualityId)
                            ?: lastSiteVariants!!.first()
                        isInAdMode = false
                        withContext(Dispatchers.Main) { onSwitch(backVariant.url, "ad_end", backVariant.isAudioOnly) }
                        delay(1500)
                        continue
                    }

                    // Normal live, poll interval based on targetDuration - increased to save battery, respect power saver
                    val pm = try { context.getSystemService(PowerManager::class.java) } catch (_: Exception) { null }
                    val isPowerSave = try { pm?.isPowerSaveMode == true } catch (_: Exception) { false }
                    val baseInterval = if (isInAdMode) 2000L else (extractTargetDuration(siteMediaText) * 1000L * 3 / 4)
                    var interval = baseInterval.coerceIn(3000, 8000)
                    if (isPowerSave) interval = (interval * 1.2).toLong().coerceAtMost(8000)
                    interval += Random.nextLong(400) // jitter
                    delay(interval)

                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    Log.w(TAG, "monitor loop error", e)
                    delay(3000)
                }
            }
        }
    }

    @Synchronized
    fun stop(persistAdState: Boolean = false) {
        stopped = true
        job?.cancel()
        job = null
        if (!persistAdState) isInAdMode = false
        lastSiteVariantsFetchMs = 0
    }

    fun isRunning(): Boolean = job?.isActive == true

    private fun shouldRefreshVariants(): Boolean {
        return System.currentTimeMillis() - lastSiteVariantsFetchMs > 60_000
    }

    private fun selectVariant(variants: List<TwitchService.Variant>, prefId: String): TwitchService.Variant? {
        // Use fps-equivalent fallback: 720p <-> 720p60 (user request)
        return TwitchService.findBestVariant(variants, prefId)
    }

    // Lightweight GET for media playlist (small, ~2KB) - caller already on IO, no extra hop
    private fun httpGetQuick(urlStr: String): String {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Accept", "application/vnd.apple.mpegurl,*/*")
            conn.setRequestProperty("Referer", "https://www.twitch.tv/")
            conn.setRequestProperty("Origin", "https://www.twitch.tv")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126.0.0.0 Safari/537.36")
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() } ?: ""
                val safe = urlStr.replace(Regex("token=[^&\\s]+"), "token=***").replace(Regex("sig=[^&\\s]+"), "sig=***")
                throw RuntimeException("HTTP $code for $safe: $err")
            }
            return conn.inputStream.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }
        } finally { conn.disconnect() }
    }

    /** Ad detection parity: check DATERANGE stitched-ad OR last segment != live - avoid 3x lines() alloc */
    internal fun isAdInMedia(text: String): Boolean {
        if (text.isEmpty()) return false
        if (text.contains("twitch-stitched-ad", ignoreCase = true)) return true
        if (text.contains("X-TV-TWITCH-AD-", ignoreCase = true)) return true
        var lastInf: String? = null
        var lastUrl = ""
        text.lineSequence().forEach { line ->
            if (line.startsWith("#EXTINF")) lastInf = line
            else if (line.isNotBlank() && !line.startsWith("#")) lastUrl = line
        }
        if (lastUrl.contains("stitched", ignoreCase = true) || lastUrl.contains("/ad/")) return true
        if (lastInf == null) return false
        // Twitch title is after first comma; handle titles with commas correctly
        val name = Regex("""#EXTINF:[^,]*,(.*)""").find(lastInf!!)?.groupValues?.get(1)?.trim() ?: lastInf!!.substringAfter(",", "").trim()
        return name.isNotEmpty() && !name.equals("live", ignoreCase = true)
    }

    private fun extractTargetDuration(text: String): Long {
        val m = TARGET_DURATION_REGEX.find(text) ?: return 2
        return try { m.groupValues[1].toLong() } catch (_: Exception) { 2 }
    }

    companion object {
        private const val TAG = "TwitchAdMonitor"
        private val TARGET_DURATION_REGEX = Regex("#EXT-X-TARGETDURATION:(\\d+)")
    }
}
