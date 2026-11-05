package app.ytaudio

import org.json.JSONArray
import org.json.JSONObject

data class SavedSession(
    val urls: List<String>,
    val index: Int,
    val positionMs: Long,
    val autoplay: Boolean,
    val title: String,
)

/** Saved-session <-> JSON. Pure (no Android storage) so it is testable. */
object SessionCodec {
    fun encode(urls: List<String>, index: Int, autoplay: Boolean, title: String): String =
        JSONObject().put("urls", JSONArray(urls)).put("index", index).put("autoplay", autoplay).put("title", title).toString()

    /** Null for anything unreadable or empty: a bad save must never crash the app. */
    fun decode(json: String, positionMs: Long): SavedSession? = try {
        val o = JSONObject(json)
        val arr = o.getJSONArray("urls")
        val urls = List(arr.length()) { arr.getString(it) }
        if (urls.isEmpty()) null
        else SavedSession(urls, o.getInt("index").coerceIn(0, urls.lastIndex), positionMs.coerceAtLeast(0),
            o.optBoolean("autoplay"), o.optString("title"))
    } catch (e: Exception) { null }
}
