package `is`.xyz.mpv.twitch

import android.app.AlertDialog
import android.content.Context
import android.util.Log
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.*

/**
 * Live quality switcher - shows variants parsed from master m3u8.
 * For battery efficiency: "Audio only" sets mpv vid=no and lowers rendering.
 */
class TwitchQualityDialog(
    private val context: Context,
    private val variants: List<TwitchService.Variant>,
    private val currentUrl: String?,
    private val lifecycleScope: CoroutineScope,
    private val onQualitySelected: (TwitchService.Variant) -> Unit
) {
    fun show() {
        if (variants.isEmpty()) {
            AlertDialog.Builder(context)
                .setTitle("Quality")
                .setMessage("No variants available (offline or ad block). Try Source/Auto.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        // determine selected index by matching currentUrl
        val idx = variants.indexOfFirst { it.url == currentUrl }.takeIf { it >= 0 } ?: run {
            // heuristic: choose highest bandwidth not audio as selected
            variants.indexOfFirst { !it.isAudioOnly }.takeIf { it>=0 } ?: 0
        }

        val labels = variants.map { v ->
            when {
                v.isAudioOnly -> "Audio only \u00b7 battery saver \u266A"
                v.isSource -> "Source (Auto) \u00b7 ${formatBandwidth(v.bandwidth)}"
                else -> "${v.name} \u00b7 ${v.width}x${v.height} \u00b7 ${formatBandwidth(v.bandwidth)}"
            }
        }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle("Quality \u00b7 live switch")
            .setSingleChoiceItems(labels, idx) { dialog, which ->
                dialog.dismiss()
                val chosen = variants[which]
                applyQuality(chosen)
                onQualitySelected(chosen)
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Auto") { d, _ ->
                d.dismiss()
                // Auto = let mpv choose via hls-bitrate=max or reload master
                // For now reload master via currentUrl's master if known, else pick source
                val auto = variants.firstOrNull { it.isSource } ?: variants.firstOrNull { !it.isAudioOnly } ?: variants.first()
                applyQuality(auto)
                onQualitySelected(auto)
            }
            .show()
    }

    private fun applyQuality(v: TwitchService.Variant) {
        val safe = v.url.replace(Regex("token=[^&\\s]+"), "token=***").replace(Regex("sig=[^&\\s]+"), "sig=***")
        Log.v("TwitchQuality", "switch to ${v.id} $safe")
        try {
            if (v.isAudioOnly) {
                // Most power efficient: disable video decoding entirely
                // This prevents GPU work, thumbnail grabs, surface updates
                MPVLib.setPropertyString("vid", "no")
                // Optional: lower demuxer cache for audio only (mpv will still buffer but less)
                // Keep audio path: ensure ao still active
                // Load audio_only URL directly with replace to keep continuity
                MPVLib.command(arrayOf("loadfile", v.url, "replace"))
                // Ensure not paused for cache
                // vo remains but with vid=no no frames are rendered
            } else {
                // Restore video if coming from audio only
                val currentVid = try { MPVLib.getPropertyString("vid") } catch (_: Exception) { null }
                if (currentVid == "no") {
                    MPVLib.setPropertyString("vid", "auto")
                }
                // For live HLS, reload variant URL
                // Use replace so position stays at live edge (mpv handles)
                MPVLib.command(arrayOf("loadfile", v.url, "replace"))
            }
        } catch (e: Exception) {
            Log.w("TwitchQuality", "failed to switch", e)
        }
    }

    private fun formatBandwidth(bw: Long): String {
        if (bw <= 0) return ""
        return when {
            bw >= 1_000_000 -> String.format("%.1fM", bw / 1_000_000.0)
            bw >= 1000 -> "${bw/1000}k"
            else -> "$bw"
        }
    }

    companion object {
        fun showLoadingAndFetch(
            context: Context,
            masterUrl: String,
            scope: CoroutineScope,
            onReady: (List<TwitchService.Variant>, String) -> Unit,
            onError: (String) -> Unit
        ) {
            if (context is android.app.Activity && (context.isFinishing || context.isDestroyed)) return
            val loading = try {
                AlertDialog.Builder(context)
                    .setTitle("Quality")
                    .setMessage("Fetching variants…")
                    .setCancelable(false)
                    .create().also { it.show() }
            } catch (_: Exception) { return }
            val job = scope.launch(CoroutineExceptionHandler { _, e ->
                Log.w("TwitchQuality", "fetch failed", e)
                try { if (loading.isShowing) loading.dismiss() } catch (_: Exception) {}
                val safe = e.message?.replace(Regex("token=[^&\\s]+"), "token=***")?.replace(Regex("sig=[^&\\s]+"), "sig=***") ?: "fetch failed"
                onError(safe)
            }) {
                val vars = TwitchService.fetchVariants(masterUrl)
                try { if (loading.isShowing) loading.dismiss() } catch (_: Exception) {}
                if (vars.isEmpty()) onError("No variants")
                else onReady(vars, masterUrl)
            }
            job.invokeOnCompletion {
                try { if (loading.isShowing) loading.dismiss() } catch (_: Exception) {}
            }
        }
    }
}
