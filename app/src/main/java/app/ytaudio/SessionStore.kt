package app.ytaudio

import android.content.Context

/**
 * Remembers the last queue and position so playback can be resumed after the app was closed by
 * the idle timer, the sleep timer or the system. The queue is written when the track changes; only the
 * (cheap) position is written while playing.
 */
object SessionStore {
    private const val FILE = "session"
    private const val KEY_QUEUE = "queue"
    private const val KEY_POS = "pos"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun saveQueue(ctx: Context, snapshot: QueueSnapshot, autoplay: Boolean, title: String) {
        sp(ctx).edit()
            .putString(KEY_QUEUE, SessionCodec.encode(snapshot.urls, snapshot.index, autoplay, title))
            .putLong(KEY_POS, 0L)
            .apply()
    }

    fun savePosition(ctx: Context, positionMs: Long) = sp(ctx).edit().putLong(KEY_POS, positionMs).apply()

    fun load(ctx: Context): SavedSession? {
        val p = sp(ctx)
        val json = p.getString(KEY_QUEUE, null) ?: return null
        return SessionCodec.decode(json, p.getLong(KEY_POS, 0L))
    }

    fun clear(ctx: Context) = sp(ctx).edit().clear().apply()
}
