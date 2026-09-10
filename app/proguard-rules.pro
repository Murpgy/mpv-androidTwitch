-dontobfuscate

# for JNI interfacing
-keep class is.xyz.mpv.MPVLib {
	*;
}

# Twitch fork: keep Twitch classes and transitive deps (R8 fullMode would strip them otherwise)
-keep class is.xyz.mpv.twitch.** { *; }
-keep class org.json.** { *; }
-keep class androidx.swiperefreshlayout.** { *; }
-keep class androidx.cardview.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep class kotlinx.coroutines.** { *; }
