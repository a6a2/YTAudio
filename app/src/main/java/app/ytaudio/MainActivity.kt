package app.ytaudio

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.app.Dialog
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * The app's face: a dark, modern home screen. Everything real happens in the Share sheet; this page explains it,
 * offers the sleep timer and speed while audio plays, resumes the last session, updates the app and shows the
 * last problem. To go back to a launcher-less app, delete the LAUNCHER intent-filter in the manifest.
 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var nowCard: LinearLayout
    private lateinit var speedChips: List<TextView>
    private lateinit var sleepChips: List<TextView>
    private lateinit var sleepStatus: TextView
    private var sleepChoice = 0                       // 0 = off, minutes, or -1 = end of track
    private val speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
    private val sleepMinutes = listOf(0, 15, 30, 45, 60, -1)

    private lateinit var resumeCard: LinearLayout
    private lateinit var resumeText: TextView
    private var saved: SavedSession? = null
    private var serviceWasUp: Boolean? = null

    private lateinit var statusTitle: TextView        // "Latest version" (green) / "Update available" (red)
    private lateinit var status: TextView
    private lateinit var action: Button
    private lateinit var progress: ProgressBar
    private var available: UpdateInfo? = null
    private var latest = false                        // the last check said we are up to date
    private var busy = false
    private var dialog: Dialog? = null
    private var dialogShown = false                   // the pop-up appears once per visit

    private lateinit var problemCard: LinearLayout
    private lateinit var problemText: TextView

    private val ticker = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1000L) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Launcher shortcut "Resume": start playing the saved session straight away.
        if (intent?.action == PlaybackService.ACTION_RESUME && PlaybackService.instance == null && SessionStore.load(this) != null) {
            ContextCompat.startForegroundService(
                this, Intent(this, PlaybackService::class.java).setAction(PlaybackService.ACTION_RESUME)
            )
            finish()
            return
        }

        val column = Ui.column(this)
        buildHeader(column)
        buildHowTo(column)
        buildNowPlaying(column)
        buildResume(column)
        if (Updater.enabled) buildUpdates(column)
        column.addView(Ui.button(this, getString(R.string.settings_button), primary = false) {
            startActivity(Intent(this, SettingsActivity::class.java))
        })
        buildProblem(column)
        column.addView(Ui.text(this, getString(R.string.about_footer), 12f, muted = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(this@MainActivity, 18), 0, 0)
        })
        setContentView(Ui.page(this, column))
        UpdateCheckJob.schedule(this)
    }

    // ---- sections ---------------------------------------------------------------------------

    private fun buildHeader(column: LinearLayout) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(Ui.text(this, getString(R.string.app_name), 32f, 700),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Ui.badge(this, "v" + BuildConfig.VERSION_NAME))
        column.addView(row)
        column.addView(Ui.text(this, getString(R.string.tagline), 15f, muted = true).apply {
            setPadding(0, Ui.dp(this@MainActivity, 4), 0, Ui.dp(this@MainActivity, 18))
        })
    }

    private fun buildHowTo(column: LinearLayout) {
        val card = Ui.card(this, column)
        card.addView(Ui.title(this, getString(R.string.howto_title)))
        card.addView(Ui.text(this, getString(R.string.help_body), 15f, muted = true))
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            card.addView(Ui.button(this, getString(R.string.help_allow_notifications)) { b ->
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                b.visibility = View.GONE
            })
        }
    }

    private fun buildNowPlaying(column: LinearLayout) {
        nowCard = Ui.card(this, column)
        nowCard.visibility = View.GONE
        nowCard.addView(Ui.title(this, getString(R.string.np_title)))

        nowCard.addView(Ui.text(this, getString(R.string.np_speed), 13f, 500, muted = true))
        speedChips = speeds.map { s ->
            Ui.chip(this, speedLabel(s)) {
                PlaybackService.instance?.setSpeed(s)
                refresh()
            }
        }
        Ui.chipRow(this, nowCard, speedChips)

        nowCard.addView(Ui.text(this, getString(R.string.sleep_title), 13f, 500, muted = true).apply {
            setPadding(0, Ui.dp(this@MainActivity, 14), 0, 0)
        })
        sleepStatus = Ui.text(this, "", 14f)
        nowCard.addView(sleepStatus)
        sleepChips = sleepMinutes.map { m ->
            Ui.chip(this, when (m) {
                0 -> getString(R.string.sleep_off)
                -1 -> getString(R.string.sleep_end)
                else -> getString(R.string.sleep_min, m)
            }) {
                val svc = PlaybackService.instance
                sleepChoice = m
                when {
                    svc == null -> {}
                    m == 0 -> svc.cancelSleep()
                    m == -1 -> svc.setSleepAfterTrack()
                    else -> svc.setSleepMinutes(m)
                }
                refresh()
            }
        }
        Ui.chipRow(this, nowCard, sleepChips.subList(0, 3))
        Ui.chipRow(this, nowCard, sleepChips.subList(3, 6))
    }

    private fun buildResume(column: LinearLayout) {
        resumeCard = Ui.card(this, column)
        resumeCard.visibility = View.GONE
        resumeCard.addView(Ui.title(this, getString(R.string.resume_title)))
        resumeText = Ui.text(this, "", 15f, muted = true)
        resumeCard.addView(resumeText)
        resumeCard.addView(Ui.button(this, getString(R.string.resume_button)) {
            ContextCompat.startForegroundService(
                this, Intent(this, PlaybackService::class.java).setAction(PlaybackService.ACTION_RESUME)
            )
        })
    }

    private fun buildUpdates(column: LinearLayout) {
        val card = Ui.card(this, column)
        card.addView(Ui.title(this, getString(R.string.update_title)))
        statusTitle = Ui.text(this, "", 18f, 700)
        card.addView(statusTitle)
        status = Ui.text(this, "", 15f, muted = true)
        card.addView(status)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.accent))
        }
        card.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        action = Ui.button(this, "") { onAction() }
        card.addView(action)
        Ui.space(this, card, 6)
        Ui.switch(this, card, getString(R.string.update_check_switch), getString(R.string.update_hint), Updater.checkEnabled(this)) { on ->
            Prefs.put(this, Updater.KEY_CHECK, on)
            UpdateCheckJob.schedule(this)
        }
        showIdle()
    }

    private fun buildProblem(column: LinearLayout) {
        problemCard = Ui.card(this, column)
        problemCard.addView(Ui.title(this, getString(R.string.problem_title)))
        problemText = Ui.text(this, "", 11f, muted = true).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        problemCard.addView(problemText)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Ui.button(this, getString(R.string.problem_copy)) {
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("YT Audio", problemText.text))
            Toast.makeText(this, getString(R.string.problem_copied), Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(this@MainActivity, 8) })
        row.addView(Ui.button(this, getString(R.string.problem_clear), primary = false) {
            ErrorLog.clear(this); refreshProblem()
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        problemCard.addView(row)
    }

    // ---- live state -------------------------------------------------------------------------

    override fun onStart() {
        super.onStart()
        Updater.uiVisible = true
        if (Updater.enabled) {
            val known = available
            if (known != null) showUpdateDialog(known) else if (!busy) checkNow()
        }
    }

    override fun onResume() {
        super.onResume()
        serviceWasUp = null
        refreshProblem()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onStop() {
        Updater.uiVisible = false
        dialog?.dismiss()
        dialog = null
        super.onStop()
    }

    private fun speedLabel(s: Float) = (if (s % 1f == 0f) s.toInt().toString() else s.toString()) + "×"

    private fun refresh() {
        val svc = PlaybackService.instance
        val up = svc != null
        if (serviceWasUp != up) {                     // reload the saved session only when playback starts or stops
            serviceWasUp = up
            saved = if (up) null else SessionStore.load(this)
        }
        nowCard.visibility = if (up) View.VISIBLE else View.GONE
        resumeCard.visibility = if (!up && saved != null) View.VISIBLE else View.GONE
        saved?.let {
            resumeText.text = if (it.title.isNotBlank()) getString(R.string.resume_text, it.title) else getString(R.string.resume_text_untitled)
        }
        if (svc == null) return

        val speed = svc.speed()
        speedChips.forEachIndexed { i, chip -> Ui.style(chip, kotlin.math.abs(speeds[i] - speed) < 0.01f) }

        val left = svc.sleepStatus()
        if (left == PlaybackService.SLEEP_OFF) sleepChoice = 0
        sleepStatus.text = when (left) {
            PlaybackService.SLEEP_OFF -> getString(R.string.sleep_status_off)
            PlaybackService.SLEEP_END -> getString(R.string.sleep_status_end)
            else -> getString(R.string.sleep_status_left, String.format(Locale.getDefault(), "%d:%02d", left / 60000, (left / 1000) % 60))
        }
        sleepChips.forEachIndexed { i, chip -> Ui.style(chip, sleepMinutes[i] == sleepChoice) }
    }

    private fun refreshProblem() {
        val text = ErrorLog.last(this)
        problemCard.visibility = if (text == null) View.GONE else View.VISIBLE
        problemText.text = text.orEmpty()
    }

    // ---- updates ----------------------------------------------------------------------------

    private fun showIdle() {
        busy = false
        progress.visibility = View.GONE
        action.isEnabled = true
        val info = available
        when {
            info != null -> {
                showStatusTitle(R.string.update_available_title, R.color.status_update)           // red
                status.text = getString(R.string.update_available_detail, info.version)
                action.text = getString(R.string.update_button_install)
            }
            latest -> {
                showStatusTitle(R.string.update_latest_title, R.color.status_ok)                  // green
                status.text = getString(R.string.update_latest_detail, BuildConfig.VERSION_NAME)
                action.text = getString(R.string.update_button_check)
            }
            else -> {
                statusTitle.visibility = View.GONE
                status.text = ""
                action.text = getString(R.string.update_button_check)
            }
        }
    }

    private fun showStatusTitle(text: Int, color: Int) {
        statusTitle.visibility = View.VISIBLE
        statusTitle.text = getString(text)
        statusTitle.setTextColor(getColor(color))
    }

    /** Floating card in the middle of the screen, once per visit, when a newer version exists. */
    private fun showUpdateDialog(info: UpdateInfo) {
        if (dialogShown || !Updater.uiVisible || isFinishing || isDestroyed) return
        dialogShown = true
        Updater.markAnnounced(this, info.version)          // seen: the floating notification would only repeat it

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(this@MainActivity, 22)
            setPadding(p, p, p, p)
            background = Ui.cardBackground(this@MainActivity, 26)
        }
        box.addView(Ui.text(this, getString(R.string.update_available_title), 22f, 700)
            .apply { setTextColor(getColor(R.color.status_update)) })
        box.addView(Ui.text(this, getString(R.string.update_popup_text, info.version, BuildConfig.VERSION_NAME), 15f, muted = true)
            .apply { setPadding(0, Ui.dp(this@MainActivity, 6), 0, 0) })
        val notes = info.notes.trim().take(280)
        if (notes.isNotEmpty()) {
            box.addView(Ui.text(this, notes, 13f, muted = true).apply {
                maxLines = 6
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, Ui.dp(this@MainActivity, 12), 0, 0)
            })
        }
        val d = Dialog(this)
        box.addView(Ui.button(this, getString(R.string.update_button_install)) { d.dismiss(); installNow(info) })
        box.addView(Ui.button(this, getString(R.string.update_later), primary = false) { d.dismiss() })
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setContentView(box)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.CENTER)
            setDimAmount(0.65f)
            setLayout((resources.displayMetrics.widthPixels * 0.88f).toInt(), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        d.setOnDismissListener { if (dialog === d) dialog = null }
        dialog = d
        d.show()
        Haptics.start(this)
    }

    private fun onAction() {
        if (busy) return
        val info = available
        if (info == null) checkNow() else installNow(info)
    }

    private fun checkNow() {
        busy = true
        statusTitle.visibility = View.GONE
        status.text = getString(R.string.update_checking)
        action.isEnabled = false
        Thread {
            val result = try { Result.success(Updater.check()) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.onSuccess { info ->
                    available = info
                    latest = info == null
                    showIdle()
                    if (info != null) showUpdateDialog(info)
                }.onFailure {
                    ErrorLog.record(this, "Update check failed", it); refreshProblem()
                    showIdle(); status.text = getString(R.string.update_check_failed)
                }
            }
        }.start()
    }

    private fun installNow(info: UpdateInfo) {
        if (!Updater.canInstall(this)) {
            status.text = getString(R.string.update_allow_install)
            startActivity(Updater.installPermissionIntent(this))
            return
        }
        busy = true
        action.isEnabled = false
        progress.progress = 0
        progress.visibility = View.VISIBLE
        status.text = getString(R.string.update_downloading, 0)
        Thread {
            try {
                val apk = Updater.download(this, info) { pct ->
                    runOnUiThread { progress.progress = pct; status.text = getString(R.string.update_downloading, pct) }
                }
                runOnUiThread { status.text = getString(R.string.update_installing) }
                Updater.install(this, apk)
                runOnUiThread { busy = false; action.isEnabled = true; progress.visibility = View.GONE }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    ErrorLog.record(this, "Update failed", e); refreshProblem()
                    showIdle(); status.text = getString(R.string.update_failed, e.message.orEmpty())
                }
            }
        }.start()
    }
}
