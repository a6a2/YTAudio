package app.ytaudio

import android.content.Context
import android.content.SharedPreferences

/** User settings (one small file shared with the updater and the repeat mode). */
object Prefs {
    const val FILE = "settings"
    fun sp(ctx: Context): SharedPreferences = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Light vibrations on taps and when playback starts. */
    fun haptics(ctx: Context) = sp(ctx).getBoolean("haptics", true)
    /** A single video continues with a recommended one. */
    fun autoplay(ctx: Context) = sp(ctx).getBoolean("autoplay", true)
    /** "best" or "saver". (An older "auto" setting counts as "best".) */
    fun quality(ctx: Context) = if (sp(ctx).getString("quality", "best") == "saver") "saver" else "best"
    /** What sharing a link does while something plays: "replace" (default) or "queue". */
    fun shareMode(ctx: Context) = sp(ctx).getString("share_mode", "replace") ?: "replace"
    /** Pause between tracks, in seconds. */
    fun gapSeconds(ctx: Context) = sp(ctx).getInt("gap_s", 3)
    /** Minutes paused before the app closes itself; 0 = never. */
    fun idleMinutes(ctx: Context) = sp(ctx).getInt("idle_min", 10)
    fun speed(ctx: Context) = sp(ctx).getFloat("speed", 1f)

    fun put(ctx: Context, key: String, value: String) = sp(ctx).edit().putString(key, value).apply()
    fun put(ctx: Context, key: String, value: Int) = sp(ctx).edit().putInt(key, value).apply()
    fun put(ctx: Context, key: String, value: Boolean) = sp(ctx).edit().putBoolean(key, value).apply()
    fun put(ctx: Context, key: String, value: Float) = sp(ctx).edit().putFloat(key, value).apply()
}
