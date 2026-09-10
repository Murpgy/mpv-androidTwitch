package `is`.xyz.mpv

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity(R.layout.activity_main) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.setTitle(R.string.mpv_activity)

        if (savedInstanceState == null) {
            val useTwitch = true // Twitch-first: watching Twitch is main priority
            with (supportFragmentManager.beginTransaction()) {
                setReorderingAllowed(true)
                if (useTwitch) add(R.id.fragment_container_view, `is`.xyz.mpv.twitch.TwitchMainFragment())
                else add(R.id.fragment_container_view, MainScreenFragment())
                commit()
            }
        }
    }
}
