package `is`.xyz.mpv.twitch

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import kotlin.random.Random

/**
 * Port of twitch_5-2026.6.21 player.js: ПолучитьАбсолютныйАдресСпискаВариантов
 * + gqltoken.js + content.js logic, adapted for Android/mpv.
 *
 * Main purpose: fetch Twitch HLS master URL efficiently for background audio playback.
 * Does NOT do MSE/WASM transmux - mpv handles HLS natively via ffmpeg.
 */
object TwitchService {
    private const val TAG = "TwitchService"
    const val CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko"

    data class Variant(
        val id: String,          // e.g. "chunked", "720p60", "480p", "160p", "audio_only"
        val name: String,        // display name
        val url: String,
        val bandwidth: Long = 0,
        val width: Int = 0,
        val height: Int = 0,
        val fps: Double = 0.0,
        val isAudioOnly: Boolean = false,
        val groupId: String = ""
    ) {
        val isSource: Boolean get() = id == "chunked"
        fun displayLabel(): String = when {
            isAudioOnly -> "Audio only \u00b7 battery saver"
            isSource -> "Source"
            else -> name
        }
    }

    data class PlaybackAccessToken(
        val value: String,
        val signature: String,
        val channelId: String = ""
    )

    // Device ID like player.js:getUniqueDeviceId - persistent per install
    fun getDeviceId(context: Context): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        var id = prefs.getString("twitch_device_id", null)
        if (id.isNullOrEmpty()) {
            // OLDdtwitch logic: 0000000000000000 + random
            val random = prefs.getFloat("twitch_random", Random.nextFloat().let { if (it==0f) 0.1f else it })
            // ensure persistence of random
            if (!prefs.contains("twitch_random")) {
                prefs.edit().putFloat("twitch_random", random).apply()
            }
            val randSuffix = String.format("%.16f", random).substring(2) // after "0."
            id = "0000000000000000$randSuffix".take(32).padEnd(32,'0')
            // fallback to UUID prefix if above weird
            if (id.length < 16) id = UUID.randomUUID().toString().replace("-","").take(16)
            prefs.edit().putString("twitch_device_id", id).apply()
            Log.v(TAG, "generated device id: $id")
        }
        return id
    }

    fun getOAuthToken(context: Context): String? {
        // Check SharedPreferences set by user login via WebView (if any)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getString("twitch_oauth_token", null)?.takeIf { it.isNotBlank() }
    }

    // In-memory cache for PlaybackAccessToken URL like player.js:5682
    private var cachedM3u8Url: String = ""
    private var cachedExpiryMs: Long = 0
    private const val TOKEN_TTL_MS = 15 * 60 * 1000L

    /**
     * Get HLS master m3u8 URL for channel. Equivalent to ПолучитьАбсолютныйАдресСпискаВариантов.
     * @param channel lowercase login
     * @param withoutAds if true use playerType=picture-by-picture (ad-free path), else "site"
     */
    suspend fun getHlsMasterUrl(context: Context, channel: String, withoutAds: Boolean = false): String = withContext(Dispatchers.IO) {
        val clean = channel.trim().lowercase()
        require(clean.isNotEmpty()) { "empty channel" }
        // Return cached if valid and not ad-free request (ad path uses different token)
        if (!withoutAds && cachedM3u8Url.isNotEmpty() && System.currentTimeMillis() < cachedExpiryMs) {
            // add cache-bust p= param like player.js:5689
            return@withContext "$cachedM3u8Url&p=${Random.nextInt(0, 9999999)}"
        }

        val deviceId = getDeviceId(context)
        val token = fetchPlaybackAccessToken(clean, deviceId, context, withoutAds)

        // Build usher URL exactly like player.js:5735
        val base = "https://usher.ttvnw.net/api/channel/hls/${URLEncoder.encode(clean, "UTF-8")}.m3u8"
        val params = listOf(
            "allow_audio_only=true",
            "allow_source=true",
            "cdm=wv",
            "platform=web",
            "player_backend=mediaplayer",
            "reassignments_supported=true",
            "supported_codecs=h264",
            "transcode_mode=cbr_v1",
            "token=${URLEncoder.encode(token.value, "UTF-8")}",
            "sig=${URLEncoder.encode(token.signature, "UTF-8")}"
        ).joinToString("&")

        var url = "$base?$params"
        if (!withoutAds) {
            val playSessionId = (0 until 32).map { "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"[Random.nextInt(62)] }.joinToString("")
            // store playSession for minute-watched tracking (not needed for playback but kept)
            PreferenceManager.getDefaultSharedPreferences(context).edit().putString("twitch_play_session", playSessionId).apply()
            url += "&play_session_id=$playSessionId"
            cachedM3u8Url = url
            cachedExpiryMs = System.currentTimeMillis() + TOKEN_TTL_MS
        }
        Log.v(TAG, "usher url for $clean (withoutAds=$withoutAds): $url")
        // Add random p bust like extension? Only non-ad path uses it on return, not initially
        url
    }

    private fun fetchPlaybackAccessToken(channel: String, deviceId: String, context: Context, withoutAds: Boolean): PlaybackAccessToken {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val oauth = getOAuthToken(context)

        val query = """query(${'$'}login: String!, ${'$'}playerType: String!, ${'$'}disableHTTPS: Boolean!) {
  streamPlaybackAccessToken(channelName: ${'$'}login params: {disableHTTPS: ${'$'}disableHTTPS playerType: ${'$'}playerType platform: "web" playerBackend: "mediaplayer"}) {
    value
    signature
  }
}"""
        val variables = JSONObject().apply {
            put("login", channel)
            put("playerType", if (withoutAds) "picture-by-picture" else "site")
            put("disableHTTPS", false)
        }
        val body = JSONObject().apply {
            put("query", query)
            put("variables", variables)
        }.toString()

        val headers = mutableMapOf(
            "Accept-Language" to "en-US",
            "Client-ID" to CLIENT_ID,
            "Content-Type" to "text/plain; charset=UTF-8",
            "X-Device-ID" to deviceId
        )
        if (!oauth.isNullOrEmpty()) {
            headers["Authorization"] = "OAuth $oauth"
        }
        // Note: Client-Integrity header intentionally omitted - many usher flows work without it.
        // If Twitch starts rejecting, we fall back to WebView extraction (see TwitchIntegrityWebView).
        // For now try without; extension's gqltoken.js fetches via iframe but same token is optional for PlaybackAccessToken.

        val resp = postJson("https://gql.twitch.tv/gql", body, headers, 15000)
        val json = JSONObject(resp)
        if (json.has("errors")) {
            val errs = json.getJSONArray("errors")
            Log.w(TAG, "GQL errors: $errs")
            // Check for failed integrity
            for (i in 0 until errs.length()) {
                val msg = errs.getJSONObject(i).optString("message")
                if (msg.contains("failed integrity")) {
                    throw SecurityException("ACCESS_DENIED_INTEGRITY")
                }
            }
            throw RuntimeException("GQL error: $errs")
        }
        val data = json.optJSONObject("data") ?: throw RuntimeException("No data in GQL response")
        val tokenObj = data.optJSONObject("streamPlaybackAccessToken") ?: throw RuntimeException("No streamPlaybackAccessToken")
        val value = tokenObj.optString("value")
        val sig = tokenObj.optString("signature")
        if (value.isEmpty() || sig.isEmpty()) throw RuntimeException("Empty token/sig")
        // Parse value to extract channel_id like player.js:5724
        var channelId = ""
        try {
            val inner = JSONObject(value)
            channelId = inner.optString("channel_id", "")
            if (inner.optBoolean("ci_gb", false)) {
                throw RuntimeException("ci_gb blocked")
            }
        } catch (_: Exception) {}
        return PlaybackAccessToken(value, sig, channelId)
    }

    private fun postJson(urlStr: String, body: String, headers: Map<String,String>, timeoutMs: Int): String {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.doInput = true
            for ((k,v) in headers) conn.setRequestProperty(k, v)
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
            if (code !in 200..299) {
                Log.w(TAG, "HTTP $code for $urlStr: $resp")
                throw RuntimeException("HTTP $code: $resp")
            }
            return resp
        } finally {
            conn.disconnect()
        }
    }

    /** Fetch and parse master m3u8 for quality list - for live switching UI */
    suspend fun fetchVariants(masterUrl: String): List<Variant> = withContext(Dispatchers.IO) {
        val text = httpGet(masterUrl, 10000)
        parseMasterPlaylist(text, masterUrl)
    }

    private fun httpGet(urlStr: String, timeoutMs: Int): String {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Accept", "application/vnd.apple.mpegurl,*/*")
            conn.setRequestProperty("Origin", "https://www.twitch.tv")
            conn.setRequestProperty("Referer", "https://www.twitch.tv/")
            val code = conn.responseCode
            if (code !in 200..299) throw RuntimeException("HTTP $code fetching $urlStr")
            return BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).readText()
        } finally {
            conn.disconnect()
        }
    }

    internal fun parseMasterPlaylist(text: String, baseUrl: String): List<Variant> {
        val variants = mutableListOf<Variant>()
        val lines = text.lines()
        var pendingInfo: String? = null
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#EXTM3U") || line.startsWith("#EXT-X-TWITCH")) {
                // Twitch adds #EXT-X-TWITCH-PREFETCH etc - ignore
                if (line.startsWith("#EXT-X-STREAM-INF") || line.startsWith("#EXT-X-MEDIA")) {
                    pendingInfo = line
                }
                continue
            }
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                pendingInfo = line
                continue
            }
            if (line.startsWith("#EXT-X-MEDIA:")) {
                // audio_only is declared as MEDIA GROUP-ID="audio_only"
                // We'll synthesize a variant entry for it
                if (line.contains("GROUP-ID=\"audio_only\"") || line.contains("NAME=\"Audio Only\"")) {
                    // URL may be on next line? Actually Twitch uses separate URI in MEDIA tag
                    val uriMatch = Regex("URI=\"([^\"]+)\"").find(line)
                    val nameMatch = Regex("NAME=\"([^\"]+)\"").find(line)
                    val uri = uriMatch?.groupValues?.get(1)
                    val name = nameMatch?.groupValues?.get(1) ?: "audio_only"
                    if (uri != null) {
                        val abs = resolveUrl(uri, baseUrl)
                        // avoid duplicates
                        if (variants.none { it.isAudioOnly }) {
                            variants.add(Variant("audio_only", "Audio Only", abs, isAudioOnly = true))
                        }
                    }
                }
                continue
            }
            if (pendingInfo != null && !line.startsWith("#")) {
                // line is URL for previous STREAM-INF
                val info = pendingInfo
                pendingInfo = null
                val url = resolveUrl(line, baseUrl)
                // parse info attributes
                val bandwidth = Regex("BANDWIDTH=(\\d+)").find(info)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val res = Regex("RESOLUTION=(\\d+)x(\\d+)").find(info)
                val w = res?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val h = res?.groupValues?.get(2)?.toIntOrNull() ?: 0
                val nameAttr = Regex("NAME=\"([^\"]+)\"").find(info)?.groupValues?.get(1)
                val videoAttr = Regex("VIDEO=\"([^\"]+)\"").find(info)?.groupValues?.get(1) ?: ""
                // also check CODECS etc but not needed
                // Derive display name like extension's variant logic
                val fpsHint = if (nameAttr?.contains("60") == true) 60.0 else 30.0
                val id = when {
                    videoAttr == "chunked" || nameAttr == "chunked" -> "chunked"
                    nameAttr != null -> nameAttr
                    h != 0 -> "${h}p${if(fpsHint==60.0) "60" else ""}"
                    else -> "variant_${variants.size}"
                }
                val display = nameAttr ?: if (h!=0) "${h}p${if(fpsHint==60.0) "60" else ""}" else id
                val isAudio = id == "audio_only" || display.lowercase().contains("audio")
                variants.add(Variant(id, display, url, bandwidth, w, h, fpsHint, isAudio, videoAttr))
            } else if (!line.startsWith("#") && line.isNotEmpty()) {
                // orphan URL without STREAM-INF - could be audio_only fallback URI
                // ignore unless looks like audio
                if (line.contains("audio_only")) {
                    val url = resolveUrl(line, baseUrl)
                    if (variants.none { it.isAudioOnly }) {
                        variants.add(Variant("audio_only", "Audio Only", url, isAudioOnly = true))
                    }
                }
            }
        }
        // If audio_only not in variants but master URL contains it, add synthetic
        if (variants.none { it.isAudioOnly }) {
            // Twitch often provides audio_only via separate rendition - but we can synthesize
            // Try to infer audio_only URL by replacing variant name in base master? simpler: keep missing
        }
        // Sort like player.js:5683 sort - chunked first, audio_only last, rest by bitrate desc
        return variants.sortedWith(compareBy<Variant> {
            when (it.id) {
                "chunked" -> -1
                "audio_only" -> 1
                else -> 0
            }
        }.thenByDescending { it.bandwidth })
    }

    private fun resolveUrl(relative: String, base: String): String {
        return try {
            URL(URL(base), relative).toString()
        } catch (_: Exception) { relative }
    }

    // Favorites persistence helpers
    fun getFavorites(context: Context): MutableList<String> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val json = prefs.getString("twitch_favorites", "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            MutableList(arr.length()) { arr.getString(it) }
        } catch (_: Exception) { mutableListOf() }
    }

    fun addFavorite(context: Context, channel: String) {
        val list = getFavorites(context)
        val clean = channel.trim().lowercase()
        if (clean.isEmpty() || list.contains(clean)) return
        list.add(clean)
        saveFavorites(context, list)
    }

    fun removeFavorite(context: Context, channel: String) {
        val list = getFavorites(context)
        if (list.remove(channel.lowercase())) saveFavorites(context, list)
    }

    private fun saveFavorites(context: Context, list: List<String>) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putString("twitch_favorites", JSONArray(list).toString()).apply()
    }

    fun getRecent(context: Context): MutableList<String> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val json = prefs.getString("twitch_recent", "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            MutableList(arr.length()) { arr.getString(it) }
        } catch (_: Exception) { mutableListOf() }
    }

    fun pushRecent(context: Context, channel: String) {
        val list = getRecent(context)
        val clean = channel.lowercase()
        list.remove(clean)
        list.add(0, clean)
        if (list.size > 20) list.subList(20, list.size).clear()
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString("twitch_recent", JSONArray(list).toString()).apply()
    }
}
