package `is`.xyz.mpv.twitch

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import `is`.xyz.mpv.MPVActivity
import `is`.xyz.mpv.R
import `is`.xyz.mpv.Utils
import `is`.xyz.mpv.databinding.FragmentTwitchMainBinding
import kotlinx.coroutines.launch

/**
 * Twitch-first main screen. Replaces generic file picker as default.
 * Features:
 * - Add / remove favorite streamers
 * - Tap to play (fetches usher m3u8 then launches MPVActivity)
 * - Long press for options (remove, open in browser)
 * - Audio-only toggle (battery saver) persists as preference
 * - Quality pre-select (remember last)
 */
class TwitchMainFragment : Fragment(R.layout.fragment_twitch_main) {
    private lateinit var binding: FragmentTwitchMainBinding
    private lateinit var adapter: ChannelAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        try {
            binding = FragmentTwitchMainBinding.bind(view)
            Utils.handleInsetsAsPadding(binding.root)

            adapter = ChannelAdapter(
                onClick = { channel -> playChannel(channel) },
                onLongClick = { channel -> showChannelOptions(channel) }
            )

            binding.recycler.layoutManager = LinearLayoutManager(requireContext())
            binding.recycler.adapter = adapter

            binding.addBtn.setOnClickListener { showAddDialog() }
            binding.settingsBtn.setOnClickListener {
                startActivity(Intent(context, `is`.xyz.mpv.preferences.PreferenceActivity::class.java))
            }
            binding.urlBtn.setOnClickListener {
                val helper = Utils.OpenUrlDialog(requireContext())
                with(helper) { builder.setPositiveButton(R.string.dialog_ok) { _, _ -> playUrl(helper.text) }
                    builder.setNegativeButton(R.string.dialog_cancel) { d,_ -> d.cancel() }; create().show() }
            }

            // audio-only switch reflects mpv background + power saver
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            binding.audioOnlySwitch.isChecked = prefs.getBoolean("twitch_audio_only", false)
            binding.audioOnlySwitch.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("twitch_audio_only", checked).apply()
                Toast.makeText(requireContext(), if(checked) "Audio-only: max battery" else "Video enabled", Toast.LENGTH_SHORT).show()
            }

            binding.swipeRefresh.setOnRefreshListener { refreshList() }
            // quick open twitch
            binding.openTwitchBtn.setOnClickListener {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://twitch.tv/directory"))) } catch (_: Exception) {}
            }

            refreshList()
        } catch (e: Exception) {
            Log.e("TwitchMain", "onViewCreated failed", e)
            Toast.makeText(requireContext(), "Twitch UI init failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        if (!isAdded || !::binding.isInitialized || !::adapter.isInitialized) return
        val ctx = context ?: return
        val fav = TwitchService.getFavorites(ctx)
        val recent = TwitchService.getRecent(ctx)
        // merge: fav first, then recent not in fav
        val combined = (fav + recent.filterNot { fav.contains(it) }).distinct()
        adapter.submit(combined)
        binding.emptyHint.visibility = if (combined.isEmpty()) View.VISIBLE else View.GONE
        binding.swipeRefresh.isRefreshing = false
        // subtitle
        binding.subtitle.text = if (fav.isEmpty()) "Add streamers to start \u00b7 tap + below"
        else "${fav.size} favorite${if(fav.size!=1) "s" else ""} \u00b7 ${recent.size} recent"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // avoid view leak (N4) - binding holds view after destroy, but fragment is retained
        // no explicit null needed for lateinit, but clear adapter reference
        if (::adapter.isInitialized) adapter.submit(emptyList())
    }

    private fun showAddDialog() {
        val input = EditText(requireContext()).apply {
            hint = "channel name (e.g. xqc)"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Add streamer")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val raw = input.text.toString().trim().lowercase().replace(Regex("[^a-z0-9_]+"), "")
                if (!Regex("^[a-z0-9_]{4,25}$").matches(raw)) {
                    Toast.makeText(requireContext(), "Invalid name (4-25 chars a-z0-9_)", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                TwitchService.addFavorite(requireContext(), raw)
                refreshList()
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Paste URL") { _, _ ->
                // try clipboard
                val clip = requireContext().getSystemService(android.content.ClipboardManager::class.java)
                val text = clip.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                val m = Regex("twitch\\.tv/([a-zA-Z0-9_]+)").find(text)
                if (m != null) {
                    TwitchService.addFavorite(requireContext(), m.groupValues[1].lowercase())
                    refreshList()
                } else Toast.makeText(requireContext(), "No twitch URL in clipboard", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showChannelOptions(channel: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(channel)
            .setItems(arrayOf("Play", "Play audio-only", "Remove favorite", "Open in browser")) { _, which ->
                when(which) {
                    0 -> playChannel(channel, forceAudioOnly = false)
                    1 -> playChannel(channel, forceAudioOnly = true)
                    2 -> { TwitchService.removeFavorite(requireContext(), channel); refreshList() }
                    3 -> try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://twitch.tv/$channel"))) } catch (_: Exception) {}
                }
            }.show()
    }

    private fun playChannel(channel: String, forceAudioOnly: Boolean? = null) {
        if (!isAdded) return
        val appCtx = requireContext().applicationContext
        val prefs = PreferenceManager.getDefaultSharedPreferences(appCtx)
        val audioOnly = forceAudioOnly ?: prefs.getBoolean("twitch_audio_only", false)

        // remember recent
        TwitchService.pushRecent(appCtx, channel)

        // show loading - use viewLifecycleOwner to avoid leak
        val dlg = AlertDialog.Builder(requireContext())
            .setTitle("Connecting to $channel…")
            .setMessage("Fetching stream (${if(audioOnly) "audio-only" else "auto quality"})…")
            .setCancelable(false)
            .create()
        dlg.show()

        // Use viewLifecycleOwner.lifecycleScope + isAdded guards (C3)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val master = TwitchService.getHlsMasterUrl(appCtx, channel, withoutAds = false)
                if (!isAdded) return@launch
                if (master.isEmpty()) throw RuntimeException("empty master URL - offline?")
                if (dlg.isShowing) dlg.dismiss()

                // If audioOnly requested, try to fetch variants and pick audio_only
                if (audioOnly) {
                    try {
                        val variants = TwitchService.fetchVariants(master)
                        val audio = variants.firstOrNull { it.isAudioOnly }
                        val targetUrl = audio?.url ?: master
                        launchPlayer(channel, targetUrl, master, isAudioOnly = true)
                    } catch (e: Exception) {
                        Log.w("TwitchMain", "variant fetch failed, falling back to master", e)
                        launchPlayer(channel, master, master, isAudioOnly = true)
                    }
                } else {
                    launchPlayer(channel, master, master, isAudioOnly = false)
                }
            } catch (e: Exception) {
                if (!isAdded) return@launch
                if (dlg.isShowing) dlg.dismiss()
                Log.w("TwitchMain", "play failed", e)
                val msg = when {
                    e.message?.contains("ACCESS_DENIED") == true -> "Twitch blocked (integrity). Try again or update Client-ID."
                    e.message?.contains("404") == true || e.message?.contains("offline") == true -> "$channel is offline or does not exist"
                    e.message?.contains("^[a-z0-9_]{4,25}$".toRegex().toString()) == true -> "Invalid channel name"
                    else -> "Failed: ${e.message}"
                }
                if (!isAdded) return@launch
                AlertDialog.Builder(requireContext())
                    .setTitle("Cannot play $channel")
                    .setMessage(msg + "\n\nTip: you can still open in browser or try audio-only.")
                    .setPositiveButton("Open browser") { _, _ -> try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://twitch.tv/$channel"))) } catch (_: Exception) {} }
                    .setNegativeButton("OK", null)
                    .show()
            }
        }
    }

    private fun launchPlayer(channel: String, url: String, masterUrl: String, isAudioOnly: Boolean) {
        val i = Intent(requireContext(), `is`.xyz.mpv.MPVActivity::class.java).apply {
            putExtra("filepath", url)
            putExtra("twitch_channel", channel)
            putExtra("twitch_master_url", masterUrl)
            putExtra("twitch_is_audio_only", isAudioOnly)
            // title for notification/media session
            putExtra("title", channel)
            // ensure background playback
            // mpv will use vo=gpu but with vid=no no rendering
        }
        // ensure background pref set to audio-only for battery
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        if (prefs.getString("background_play", "never") == "never") {
            prefs.edit().putString("background_play", "audio-only").apply()
        }
        startActivity(i)
    }

    private fun playUrl(url: String) {
        val i = Intent(requireContext(), `is`.xyz.mpv.MPVActivity::class.java).apply { putExtra("filepath", url) }
        startActivity(i)
    }

    private inner class ChannelAdapter(
        private val onClick: (String)->Unit,
        private val onLongClick: (String)->Unit
    ): RecyclerView.Adapter<ChannelAdapter.Holder>() {
        private var items = listOf<String>()
        fun submit(l: List<String>) { items = l; notifyDataSetChanged() }
        inner class Holder(v: View): RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.channelName)
            val badge: TextView = v.findViewById(R.id.channelBadge)
            val play: ImageButton = v.findViewById(R.id.playBtn)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_twitch_channel, parent, false)
            return Holder(v)
        }
        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: Holder, pos: Int) {
            val ch = items[pos]
            h.name.text = ch
            val isFav = TwitchService.getFavorites(h.itemView.context).contains(ch)
            h.badge.text = if (isFav) "\u2605 fav" else "\u25cf recent"
            h.badge.visibility = View.VISIBLE
            h.itemView.setOnClickListener { onClick(ch) }
            h.itemView.setOnLongClickListener { onLongClick(ch); true }
            h.play.setOnClickListener { onClick(ch) }
        }
    }
}
