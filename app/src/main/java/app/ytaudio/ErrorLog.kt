package app.ytaudio

import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Keeps the most recent problem so it can be shown and copied from the help page. Links are stripped. */
object ErrorLog {
    private const val KEY = "last_error"

    fun record(ctx: Context, what: String, t: Throwable?) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        val text = ErrorFormat.format(
            time, BuildConfig.VERSION_NAME, Build.VERSION.RELEASE, "${Build.MANUFACTURER} ${Build.MODEL}", what, t
        )
        Prefs.put(ctx, KEY, text)
    }

    fun last(ctx: Context): String? = Prefs.sp(ctx).getString(KEY, null)
    fun clear(ctx: Context) = Prefs.sp(ctx).edit().remove(KEY).apply()
}
