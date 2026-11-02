package app.ytaudio

import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import java.io.IOException

@UnstableApi
class PlaybackService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.channel)).build()
        )
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL).setNotificationId(NOTIFICATION_ID).build()
        )

        val http = OkHttpDataSource.Factory(Http.client).setUserAgent(USER_AGENT)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones unplug / BT disconnects
            .setWakeMode(C.WAKE_MODE_NETWORK)  // keep CPU + Wi-Fi alive with the screen off
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) =
                fail(if (error.errorCode in 2000..2999) R.string.err_network else R.string.err_generic)

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) quit()
            }
        })
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PLAY) {
            // Must go foreground within seconds; Media3 replaces this notification (same id) once audio is ready.
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID,
                NotificationCompat.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle(getString(R.string.loading))
                    .setOngoing(true).setSilent(true).build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
            intent.getStringExtra(EXTRA_URL)?.let(::play)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /** A newly shared link always replaces whatever is playing. */
    private fun play(url: String) {
        job?.cancel()
        player.stop()
        player.clearMediaItems()
        job = scope.launch {
            try {
                val item = withContext(Dispatchers.IO) { Resolver.resolve(url) }
                player.setMediaItem(item)
                player.prepare()
                player.play()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.e(TAG, "network", e)
                fail(R.string.err_network, e)
            } catch (e: ContentNotAvailableException) {
                Log.e(TAG, "unavailable", e)
                fail(R.string.err_unavailable, e)
            } catch (e: ExtractionException) {
                // Extractor/YouTube mismatch, not a problem with the video itself.
                Log.e(TAG, "extraction", e)
                fail(R.string.err_extract, e)
            } catch (e: Exception) {
                Log.e(TAG, "generic", e)
                fail(R.string.err_generic, e)
            }
        }
    }

    private fun fail(@StringRes msg: Int, cause: Throwable? = null) {
        // Short technical detail appended so errors are diagnosable without logcat.
        val detail = cause?.let { "\n(${it.javaClass.simpleName}: ${it.message?.take(70)})" }.orEmpty()
        Toast.makeText(this, getString(msg) + detail, Toast.LENGTH_LONG).show()
        quit()
    }

    private fun quit() {
        player.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.release()
        player.release()
        session = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_PLAY = "app.ytaudio.PLAY"
        const val EXTRA_URL = "url"
        private const val TAG = "YTAudio"
        private const val CHANNEL = "playback"
        private const val NOTIFICATION_ID = 1001
    }
}
