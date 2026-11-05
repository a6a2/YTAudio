package app.ytaudio

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Optional help page shown when the app is opened from the launcher. The real work happens in the
 * Share sheet; this only explains how to use it and offers the one-time notification permission.
 * To go back to a launcher-less app, delete the LAUNCHER intent-filter of this activity in the manifest.
 */
class MainActivity : Activity() {

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val pad = (24 * dp).toInt()
        val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        column.addView(TextView(this).apply {
            text = getString(R.string.app_name); textSize = 28f
        })
        column.addView(TextView(this).apply {
            text = getString(R.string.help_version, version); textSize = 14f; alpha = 0.7f
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
                    visibility = android.view.View.GONE
                }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }

        // Targeting API 35 draws edge-to-edge; let the system keep content clear of the bars.
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(column, android.view.ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })
    }
}
