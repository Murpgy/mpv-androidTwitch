package `is`.xyz.mpv.twitch

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
        // fast path without lock
        prefs.getString("twitch_device_id", null)?.takeIf { it.isNotEmpty() }?.let { return it }
        synchronized(cacheLock) {
            prefs.getString("twitch_device_id", null)?.takeIf { it.isNotEmpty() }?.let { return it }
            val hex = UUID.randomUUID().toString().replace("-", "").take(16)
            val id = "0000000000000000$hex".take(32)
            prefs.edit().putString("twitch_device_id", id).apply()
            Log.v(TAG, "generated device id: $id")
            return id
        }
    }

    fun getOAuthToken(context: Context): String? {
        // Check SharedPreferences set by user login via WebView (if any)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getString("twitch_oauth_token", null)?.takeIf { it.isNotBlank() }
    }

    // In-memory cache for PlaybackAccessToken URL like player.js:5682 - channel-keyed (C1 fix)
    private data class CachedUrl(val url: String, val expiry: Long)
    private val cacheLock = Any()
    private val cachedM3u8 = mutableMapOf<String, CachedUrl>() // key = channel
    private const val TOKEN_TTL_MS = 15 * 60 * 1000L

    // hoisted regexes (avoid recompilation per variant/poll)
    private val RES_REGEX = Regex("(\\d+)x(\\d+)")
    private val ATTR_REGEX = Regex("""([A-Z0-9\-]+)=(?:"([^"]*)"|([^,]*))""")
    private val TARGET_DURATION_REGEX = Regex("#EXT-X-TARGETDURATION:(\\d+)")
    private val TOKEN_MASK_REGEX = Regex("token=[^&]+")
    private val SIG_MASK_REGEX = Regex("sig=[^&]+")

    /**
     * Get HLS master m3u8 URL for channel. Equivalent to ПолучитьАбсолютныйАдресСпискаВариантов.
     * @param channel lowercase login
     * @param withoutAds if true use playerType=picture-by-picture (ad-free path), else "site"
     */
    suspend fun getHlsMasterUrl(context: Context, channel: String, withoutAds: Boolean = false): String = withContext(Dispatchers.IO) {
        val clean = channel.trim().lowercase()
        require(clean.isNotEmpty()) { "empty channel" }
        // Evict expired entries to bound memory
        synchronized(cacheLock) {
            val now = System.currentTimeMillis()
            cachedM3u8.entries.removeIf { now >= it.value.expiry }
        }
        // Return cached if valid and not ad-free request (ad path uses different token)
        if (!withoutAds) {
            synchronized(cacheLock) {
                val cached = cachedM3u8[clean]
                if (cached != null && System.currentTimeMillis() < cached.expiry) {
                    // add cache-bust p= param like player.js:5689 - strip existing p to avoid duplication
                    val base = cached.url.substringBefore("&p=").substringBefore("?p=")
                    // cached url already contains ?, so append &p=
                    return@withContext "$base&p=${Random.nextInt(0, 9999999)}"
                }
            }
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
            synchronized(cacheLock) {
                cachedM3u8[clean] = CachedUrl(url, System.currentTimeMillis() + TOKEN_TTL_MS)
            }
        }
        // Mask token/sig in logs (N3)
        val masked = url.replace(TOKEN_MASK_REGEX, "token=***").replace(SIG_MASK_REGEX, "sig=***")
        Log.v(TAG, "usher url for $clean (withoutAds=$withoutAds): $masked")
        // Add random p bust like extension? Only non-ad path uses it on return, not initially
        url
    }

    private suspend fun fetchPlaybackAccessToken(channel: String, deviceId: String, context: Context, withoutAds: Boolean): PlaybackAccessToken {
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
            "Content-Type" to "application/json",
            "X-Device-ID" to deviceId
        )
        if (!oauth.isNullOrEmpty()) {
            headers["Authorization"] = "OAuth $oauth"
        }
        // Note: Client-Integrity header intentionally omitted - many usher flows work without it.
        // If Twitch starts rejecting, we fall back to WebView extraction (see TwitchIntegrityWebView).
        // For now try without; extension's gqltoken.js fetches via iframe but same token is optional for PlaybackAccessToken.

        // Retry once on service timeout like player.js:5528 (5s + random) - suspend delay, not Thread.sleep
        var resp = postJson("https://gql.twitch.tv/gql", body, headers, 8000)
        var json = JSONObject(resp)
        if (json.has("errors")) {
            val errs = json.getJSONArray("errors")
            val isServiceTimeout = (0 until errs.length()).any { errs.getJSONObject(it).optString("message").contains("service timeout") }
            if (isServiceTimeout) {
                Log.w(TAG, "GQL service timeout, retrying once")
                delay(4000 + Random.nextLong(2000))
                resp = postJson("https://gql.twitch.tv/gql", body, headers, 8000)
                json = JSONObject(resp)
            }
        }
        if (json.has("errors")) {
            val errs = json.getJSONArray("errors")
            Log.w(TAG, "GQL errors: $errs")
            // Check for failed integrity - try WebView fallback (M1)
            for (i in 0 until errs.length()) {
                val msg = errs.getJSONObject(i).optString("message")
                if (msg.contains("failed integrity")) {
                    Log.w(TAG, "Trying Client-Integrity WebView fallback")
                    val integrity = try { TwitchIntegrityWebView.getIntegrityToken(context) } catch (_: Exception) { null }
                    if (!integrity.isNullOrEmpty()) {
                        headers["Client-Integrity"] = integrity
                        resp = postJson("https://gql.twitch.tv/gql", body, headers, 8000)
                        json = JSONObject(resp)
                        if (json.has("errors")) {
                            val errs2 = json.getJSONArray("errors")
                            if ((0 until errs2.length()).any { errs2.getJSONObject(it).optString("message").contains("failed integrity") }) {
                                throw SecurityException("ACCESS_DENIED_INTEGRITY")
                            }
                            throw RuntimeException("GQL error after integrity: $errs2")
                        }
                        break // success, continue to parse
                    } else {
                        throw SecurityException("ACCESS_DENIED_INTEGRITY")
                    }
                }
            }
            if (json.has("errors")) throw RuntimeException("GQL error: $errs")
        }
        val data = json.optJSONObject("data") ?: throw RuntimeException("No data in GQL response")
        val tokenObj = data.optJSONObject("streamPlaybackAccessToken") ?: throw RuntimeException("No streamPlaybackAccessToken")
        val value = tokenObj.optString("value")
        val sig = tokenObj.optString("signature")
        if (value.isEmpty() || sig.isEmpty()) throw RuntimeException("Empty token/sig")
        // Parse value to extract channel_id like player.js:5724 - ci_gb must not be swallowed
        var channelId = ""
        var inner: JSONObject? = null
        try { inner = JSONObject(value) } catch (_: Exception) {}
        if (inner != null) {
            channelId = inner.optString("channel_id", "")
            if (inner.optBoolean("ci_gb", false)) {
                throw RuntimeException("ci_gb blocked - channel inaccessible")
            }
        }
        return PlaybackAccessToken(value, sig, channelId)
    }

    private fun postJson(urlStr: String, body: String, headers: Map<String,String>, timeoutMs: Int): String {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = timeoutMs.coerceAtMost(8000)
            conn.doOutput = true
            conn.doInput = true
            for ((k,v) in headers) conn.setRequestProperty(k, v)
            conn.setRequestProperty("Connection", "keep-alive")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                ?: throw RuntimeException("HTTP $code for $urlStr: no body")
            val resp = stream.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }
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
        val text = httpGet(masterUrl, 7000)
        if (text.contains("shelblock.proxy")) {
            Log.w(TAG, "shelblock proxy detected")
            throw RuntimeException("Ad-block proxy detected (shelblock)")
        }
        // Offline / token rejected returns JSON or HTML, not m3u8
        if (!text.contains("#EXTM3U")) {
            val masked = text.replace(TOKEN_MASK_REGEX, "token=***").replace(SIG_MASK_REGEX, "sig=***")
            Log.w(TAG, "master not m3u8: ${masked.take(800)}")
            throw RuntimeException("Channel offline or token rejected: ${masked.take(300)}")
        }
        parseMasterPlaylist(text, masterUrl)
    }

    private fun httpGet(urlStr: String, timeoutMs: Int): String {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = timeoutMs.coerceAtMost(7000)
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", "application/vnd.apple.mpegurl,*/*")
            conn.setRequestProperty("Origin", "https://www.twitch.tv")
            conn.setRequestProperty("Referer", "https://www.twitch.tv/")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126.0.0.0 Safari/537.36")
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() } ?: ""
                throw RuntimeException("HTTP $code fetching $urlStr: $err")
            }
            return conn.inputStream.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }
        } finally {
            conn.disconnect()
        }
    }

    internal fun parseMasterPlaylist(text: String, baseUrl: String): List<Variant> {
        // Mimic player.js РазобратьСписок - correctly map VIDEO GROUP-ID -> NAME via EXT-X-MEDIA TYPE=VIDEO
        val renditionGroups = mutableMapOf<String, String>() // GROUP-ID -> NAME
        val variants = mutableListOf<Variant>()
        val lines = text.lines()
        var pendingStreamInf: String? = null
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXT-X-MEDIA:")) {
                // Example: #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="720p60",NAME="720p60",AUTOSELECT=YES,DEFAULT=YES
                // Also audio_only: TYPE=VIDEO,GROUP-ID="audio_only",NAME="Audio Only"
                val attrs = parseAttributes(line.substringAfter(":"))
                val type = attrs["TYPE"]
                if (type == "VIDEO") {
                    val gid = attrs["GROUP-ID"]
                    val name = attrs["NAME"]
                    if (gid != null && name != null) renditionGroups[gid] = name
                    // If this MEDIA has URI (some Twitch audio_only via MEDIA URI), synthesize variant
                    val uri = attrs["URI"]
                    if (uri != null && gid == "audio_only" && variants.none { it.isAudioOnly }) {
                        val abs = resolveUrl(uri, baseUrl)
                        variants.add(Variant("audio_only", "Audio Only", abs, isAudioOnly = true, groupId = gid))
                    }
                }
                continue
            }
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                pendingStreamInf = line
                continue
            }
            if (pendingStreamInf != null && !line.startsWith("#")) {
                val info = pendingStreamInf!!
                pendingStreamInf = null
                val url = resolveUrl(line, baseUrl)
                val attrs = parseAttributes(info.substringAfter(":"))
                val bandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: 0L
                val videoId = attrs["VIDEO"] ?: ""
                val res = attrs["RESOLUTION"]
                val fpsAttr = attrs["FRAME-RATE"]
                var w = 0; var h = 0
                if (res != null) {
                    val m = RES_REGEX.find(res)
                    w = m?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    h = m?.groupValues?.get(2)?.toIntOrNull() ?: 0
                }
                val fps = fpsAttr?.toDoubleOrNull() ?: 0.0
                // Resolve name via renditionGroups, fallback to id - fix dead branch
                val nameFromGroup = renditionGroups[videoId]
                val id = when {
                    videoId.isNotEmpty() -> videoId
                    nameFromGroup != null -> nameFromGroup
                    h != 0 -> "${h}p${if (fps == 60.0) "60" else ""}"
                    else -> "variant_${variants.size}"
                }
                val display = nameFromGroup ?: id
                // audio_only detection: GROUP-ID audio_only or name contains Audio
                val isAudio = id == "audio_only" || display.equals("Audio Only", ignoreCase = true) || display.lowercase().contains("audio")
                val fpsHint = when {
                    fps != 0.0 -> fps
                    display.contains("60") -> 60.0
                    else -> 30.0
                }
                variants.add(Variant(id, display, url, bandwidth, w, h, fpsHint, isAudio, videoId))
                continue
            }
            // ignore other tags: #EXTM3U, #EXT-X-VERSION, #EXT-X-TWITCH-INFO etc
            if (line.startsWith("#")) continue
            // orphan URL without STREAM-INF - could be audio_only fallback
            if (line.contains("audio_only") && variants.none { it.isAudioOnly }) {
                val url = resolveUrl(line, baseUrl)
                variants.add(Variant("audio_only", "Audio Only", url, isAudioOnly = true))
            }
        }
        // Sort like player.js: chunked first, audio_only last, rest by bitrate desc
        return variants.sortedWith(compareBy<Variant> {
            when (it.id) {
                "chunked" -> -1
                "audio_only" -> 1
                else -> 0
            }
        }.thenByDescending { it.bandwidth })
    }

    private fun parseAttributes(src: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (m in ATTR_REGEX.findAll(src)) {
            val k = m.groupValues[1]
            val v = if (m.groupValues[2].isNotEmpty() || src.contains("$k=\"")) m.groupValues[2] else m.groupValues[3]
            out[k] = v
        }
        return out
    }

    /**
     * Find best variant for preferredId with fps-equivalent fallback:
     * if 720p (30) not found, try 720p60 and vice versa. Then nearest height.
     * Mirrors extension's fallback and user request: try equivalent before lower.
     */
    fun findBestVariant(variants: List<Variant>, preferredId: String): Variant? {
        if (variants.isEmpty()) return null
        // 1. exact
        variants.find { it.id == preferredId }?.let { return it }
        // 2. fps-equivalent: 720p <-> 720p60, 1080p <-> 1080p60
        val equiv = when {
            preferredId.endsWith("p60") -> preferredId.removeSuffix("60") // 720p60 -> 720p
            Regex("^\\d+p$").matches(preferredId) -> "${preferredId}60" // 720p -> 720p60
            else -> null
        }
        if (equiv != null) variants.find { it.id == equiv }?.let { return it }
        // 3. same height nearest: parse height from preferredId (e.g. 720)
        val prefHeight = Regex("(\\d+)p").find(preferredId)?.groupValues?.get(1)?.toIntOrNull()
        if (prefHeight != null) {
            // Prefer same height, any fps, highest bandwidth
            variants.filter { it.height == prefHeight }.maxByOrNull { it.bandwidth }?.let { return it }
            // Same height prefix contains? e.g. 720p variants include both
            variants.filter { it.id.startsWith("${prefHeight}p") }.maxByOrNull { it.bandwidth }?.let { return it }
        }
        // 4. fallback to highest non-audio
        return variants.firstOrNull { !it.isAudioOnly } ?: variants.firstOrNull()
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

    // --- Grid metas: CDN best (GQL for icon + online, CDN jpg for thumb) ---
    data class ChannelMeta(
        val login: String,
        val iconUrl: String?,          // GQL profileImageURL(width:150)
        val isOnline: Boolean,
        val previewUrl: String         // CDN static-cdn preview 320x180
    )

    fun previewCdnUrl(login: String): String =
        "https://static-cdn.jtvnw.net/previews-ttv/live_user_${login.lowercase()}-320x180.jpg"

    suspend fun fetchChannelMetas(context: Context, logins: List<String>): Map<String, ChannelMeta> = withContext(Dispatchers.IO) {
        if (logins.isEmpty()) return@withContext emptyMap()
        val clean = logins.map { it.lowercase().trim() }.filter { it.isNotEmpty() }.distinct().take(30)
        if (clean.isEmpty()) return@withContext emptyMap()
        val deviceId = try { getDeviceId(context) } catch (_: Exception) { "" }
        val headers = mutableMapOf(
            "Accept-Language" to "en-US",
            "Client-ID" to CLIENT_ID,
            "Content-Type" to "application/json",
            "X-Device-ID" to deviceId
        )
        getOAuthToken(context)?.let { if (it.isNotBlank()) headers["Authorization"] = "OAuth $it" }
        val out = mutableMapOf<String, ChannelMeta>()
        // chunk to avoid 30-array rate limit
        for (chunk in clean.chunked(10)) {
            try {
                val ops = JSONArray()
                for (login in chunk) {
                    val inline = JSONObject().apply {
                        put("query", """query(${'$'}login:String!){ user(login:${'$'}login){ login profileImageURL(width:150) stream{ id type } } }""")
                        put("variables", JSONObject().apply { put("login", login) })
                    }
                    ops.put(inline)
                }
                val respText = postJsonArray("https://gql.twitch.tv/gql", ops.toString(), headers, 8000)
                val arr = JSONArray(respText)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val data = obj.optJSONObject("data")?.optJSONObject("user")
                    // validate login mapping, fallback to chunk order
                    val loginFromData = data?.optString("login")?.lowercase()?.takeIf { it.isNotBlank() }
                    val login = loginFromData?.takeIf { chunk.contains(it) } ?: chunk.getOrNull(i) ?: continue
                    val icon = data?.optString("profileImageURL")?.takeIf { it.isNotBlank() }
                    val stream = data?.optJSONObject("stream")
                    val isOnline = stream != null && !stream.isNull("id")
                    out[login] = ChannelMeta(login, icon, isOnline, previewCdnUrl(login))
                }
            } catch (e: Exception) {
                Log.w(TAG, "fetchChannelMetas chunk failed", e)
                for (login in chunk) if (!out.containsKey(login)) out[login] = ChannelMeta(login, null, false, previewCdnUrl(login))
            }
        }
        // Fill any missing
        for (login in clean) if (!out.containsKey(login)) out[login] = ChannelMeta(login, null, false, previewCdnUrl(login))
        return@withContext out
    }

    private fun postJsonArray(urlStr: String, body: String, headers: Map<String,String>, timeoutMs: Int): String {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = timeoutMs.coerceAtMost(8000)
            conn.doOutput = true
            conn.doInput = true
            for ((k,v) in headers) conn.setRequestProperty(k, v)
            conn.setRequestProperty("Connection", "keep-alive")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                ?: throw RuntimeException("HTTP $code: no body")
            val resp = stream.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }
            if (code !in 200..299) throw RuntimeException("HTTP $code: $resp")
            return resp
        } finally { conn.disconnect() }
    }
}
