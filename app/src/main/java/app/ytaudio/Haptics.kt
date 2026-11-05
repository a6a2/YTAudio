package app.ytaudio

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Light vibrations: a tick for taps, a slightly firmer click when playback starts. Respects the Settings switch. */
object Haptics {
    private fun vibrator(ctx: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") (ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)

    private fun buzz(ctx: Context, predefined: Int, fallbackMs: Long, fallbackAmplitude: Int) {
        if (!Prefs.haptics(ctx)) return
        val v = vibrator(ctx)?.takeIf { it.hasVibrator() } ?: return
        val effect =
            if (Build.VERSION.SDK_INT >= 29) VibrationEffect.createPredefined(predefined)
            else VibrationEffect.createOneShot(fallbackMs, fallbackAmplitude)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
            } else {
                v.vibrate(effect)
            }
        } catch (_: Exception) { /* haptics are never worth a crash */ }
    }

    /** A button, switch or chip was tapped. */
    fun click(ctx: Context) = buzz(ctx, VibrationEffect.EFFECT_TICK, 8, 60)

    /** Audio just started playing. */
    fun start(ctx: Context) = buzz(ctx, VibrationEffect.EFFECT_CLICK, 18, 90)
}
