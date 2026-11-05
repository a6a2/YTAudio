package app.ytaudio

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/**
 * Help page + updates. The real work happens in the Share sheet; this explains how to use it,
 * requests the one-time notification permission, and lets the user check for / install updates.
 * To go back to a launcher-less app, delete the LAUNCHER intent-filter of this activity in the manifest.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var action: Button
    private lateinit var progress: ProgressBar
    private var available: UpdateInfo? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        column.addView(TextView(this).apply { text = getString(R.string.app_name); textSize = 28f })
        column.addView(TextView(this).apply {
            text = getString(R.string.help_version, BuildConfig.VERSION_NAME); textSize = 14f; alpha = 0.7f
        })
        column.addView(TextView(this).apply {
            text = getString(R.string.help_body); textSize = 16f; setPadding(0, pad, 0, pad)
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            column.addView(Button(this).apply {
                text = getString(R.string.help_allow_notifications)
                setOnClickListener {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                    visibility = View.GONE
                }
            })
        }

        if (Updater.enabled) addUpdateSection(column, pad)

        // Targeting API 35 draws edge-to-edge; let the system keep content clear of the bars.
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(column, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })
        UpdateCheckJob.schedule(this)
    }

    private fun addUpdateSection(column: LinearLayout, pad: Int) {
        column.addView(TextView(this).apply {
            text = getString(R.string.update_title); textSize = 20f; setPadding(0, pad, 0, pad / 3)
        })
        status = TextView(this).apply { textSize = 16f }
        column.addView(status)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; visibility = View.GONE
        }
        column.addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        action = Button(this).apply { setOnClickListener { onAction() } }
        column.addView(action)

        column.addView(Switch(this).apply {
            text = getString(R.string.update_check_switch)
            isChecked = Updater.checkEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                Updater.prefs(this@MainActivity).edit().putBoolean(Updater.KEY_CHECK, on).apply()
                UpdateCheckJob.schedule(this@MainActivity)
            }
        })
        column.addView(Switch(this).apply {
            text = getString(R.string.update_auto)
            isChecked = Updater.autoEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                Updater.prefs(this@MainActivity).edit().putBoolean(Updater.KEY_AUTO, on).apply()
                if (on && !Updater.canInstall(this@MainActivity)) {
                    startActivity(Updater.installPermissionIntent(this@MainActivity))
                }
            }
        })
        column.addView(TextView(this).apply {
            text = getString(R.string.update_auto_hint); textSize = 13f; alpha = 0.7f
        })
        showIdle()
    }

    override fun onStart() {
        super.onStart()
        Updater.uiVisible = true
        if (Updater.enabled && available == null && !busy) checkNow()
    }

    override fun onStop() {
        Updater.uiVisible = false
        super.onStop()
    }

    private fun showIdle() {
        busy = false
        progress.visibility = View.GONE
        action.isEnabled = true
        val info = available
        if (info != null) {
            status.text = getString(R.string.update_available, info.version)
            action.text = getString(R.string.update_button_install)
        } else {
            action.text = getString(R.string.update_button_check)
        }
    }

    private fun onAction() {
        if (busy) return
        val info = available
        if (info == null) checkNow() else installNow(info)
    }

    private fun checkNow() {
        busy = true
        status.text = getString(R.string.update_checking)
        action.isEnabled = false
        Thread {
            val result = try { Result.success(Updater.check()) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.onSuccess { info ->
                    available = info
                    showIdle()
                    if (info == null) status.text = getString(R.string.update_uptodate)
                }.onFailure {
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
                Updater.install(this, apk, silentIfPossible = false)
                runOnUiThread { busy = false; action.isEnabled = true; progress.visibility = View.GONE }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    showIdle(); status.text = getString(R.string.update_failed, e.message.orEmpty())
                }
            }
        }.start()
    }
}
