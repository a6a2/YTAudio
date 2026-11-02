package app.ytaudio

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/** Invisible share target. Validates the link, hands it to the service, and returns to the previous app. */
class ShareActivity : ComponentActivity() {

    private var url: String? = null
    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        url = intent?.getStringExtra(Intent.EXTRA_TEXT)?.let(YouTubeUrl::extract)
        if (url == null) {
            Toast.makeText(this, R.string.err_not_youtube, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        // Android 13+: without this, the media notification/lock-screen controls stay hidden.
        // Asked once (system dialog only); playback proceeds either way.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) else start()
    }

    private fun start() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, PlaybackService::class.java)
                .setAction(PlaybackService.ACTION_PLAY)
                .putExtra(PlaybackService.EXTRA_URL, url)
        )
        finish()
    }
}
