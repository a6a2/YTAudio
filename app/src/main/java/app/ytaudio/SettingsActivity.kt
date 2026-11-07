package app.ytaudio

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout

/** Dark, modern settings page. Every choice is saved immediately and applies the next time it is needed. */
class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = this
        val column = Ui.column(this)

        column.addView(Ui.text(this, getString(R.string.settings_title), 30f, 700))
        Ui.space(this, column, 14)

        // Feel
        Ui.card(this, column).apply {
            addView(Ui.title(ctx, getString(R.string.set_feel)))
            Ui.switch(ctx, this, getString(R.string.set_haptics), getString(R.string.set_haptics_hint), Prefs.haptics(ctx)) {
                Prefs.put(ctx, "haptics", it)
            }
        }

        // Playback
        Ui.card(this, column).apply {
            addView(Ui.title(ctx, getString(R.string.set_playback)))
            Ui.switch(ctx, this, getString(R.string.set_autoplay), getString(R.string.set_autoplay_hint), Prefs.autoplay(ctx)) {
                Prefs.put(ctx, "autoplay", it)
            }
        }

        group(column, R.string.set_quality, listOf(
            "best" to R.string.q_best, "saver" to R.string.q_saver
        ), Prefs.quality(this)) { Prefs.put(this, "quality", it) }

        group(column, R.string.set_share, listOf(
            "replace" to R.string.share_replace, "queue" to R.string.share_queue
        ), Prefs.shareMode(this)) { Prefs.put(this, "share_mode", it) }

        group(column, R.string.set_gap, listOf(
            "0" to R.string.gap_none, "1" to R.string.gap_1, "3" to R.string.gap_3
        ), Prefs.gapSeconds(this).toString()) { Prefs.put(this, "gap_s", it.toInt()) }

        group(column, R.string.set_idle, listOf(
            "5" to R.string.idle_5, "10" to R.string.idle_10, "30" to R.string.idle_30,
            "60" to R.string.idle_60, "0" to R.string.idle_never
        ), Prefs.idleMinutes(this).toString()) { Prefs.put(this, "idle_min", it.toInt()) }

        column.addView(Ui.button(this, getString(R.string.back), primary = false) { finish() })
        setContentView(Ui.page(this, column))
    }

    private fun group(column: LinearLayout, title: Int, options: List<Pair<String, Int>>, current: String, onPick: (String) -> Unit) {
        val card = Ui.card(this, column)
        card.addView(Ui.title(this, getString(title)))
        Ui.radios(this, card, options.map { (value, label) -> value to getString(label) }, current, onPick)
    }
}
