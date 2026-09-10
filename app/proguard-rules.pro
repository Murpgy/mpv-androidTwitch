-dontobfuscate
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-dontwarn com.bumptech.glide.**
-dontwarn android.support.v4.media.**
-keep class androidx.media.** { *; }
-keep class android.support.v4.media.** { *; }

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
-keep class com.bumptech.glide.** { *; }
-keep class com.github.bumptech.glide.** { *; }
