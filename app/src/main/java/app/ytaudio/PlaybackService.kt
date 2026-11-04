package app.ytaudio

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import java.io.IOException

private enum class Repeat { OFF, ALL, ONE }

@UnstableApi
class PlaybackService : MediaSessionService() {

    private lateinit var exo: ExoPlayer
    private lateinit var wake: PowerManager.WakeLock
    private var session: MediaSession? = null
    private var job: Job? = null
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val advance = Runnable { drive(null, 1, true) }
    private val idleStop = Runnable { quit() }

    // Queue state (guarded by `this` where touched off the main thread).
    private val queue = ArrayList<String>()
    private val seen = HashSet<String>()
    private var index = -1
    private var autoplay = false            // single-video share: keep appending recommendations
    private var related: List<String> = emptyList()
    private var playlistUrl: String? = null
    private var nextPage: Page? = null

    private var repeat = Repeat.OFF
    private var resting = false             // the short gap between tracks
    private var resolving = false           // looking up the next stream
    private var cardText = ""               // current text on the controls card

    private val cmdClose = SessionCommand(ACTION_CLOSE, Bundle.EMPTY)
    private val cmdRepeat = SessionCommand(ACTION_REPEAT, Bundle.EMPTY)

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
        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ytaudio:transition")
            .apply { setReferenceCounted(false) }
        repeat = Repeat.values()[getSharedPreferences("s", MODE_PRIVATE).getInt("repeat", 0).coerceIn(0, 2)]

        val http = OkHttpDataSource.Factory(Http.client).setUserAgent(USER_AGENT)
        exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        applyRepeat()
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) =
                fail(if (error.errorCode in 2000..2999) R.string.err_network else R.string.err_generic)

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    // Rest a few seconds, then continue (see `advance`).
                    resting = true
                    holdWake()
                    handler.postDelayed(advance, GAP_MS)
                }
            }

            // Paused (or focus lost) for a while -> remove the notification and stop the service.
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                handler.removeCallbacks(idleStop)
                if (!isPlaying && !exo.playWhenReady) handler.postDelayed(idleStop, IDLE_MS)
            }
        })

        session = MediaSession.Builder(this, QueuePlayer(exo))
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession, controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult = MediaSession.ConnectionResult.accept(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(cmdClose).add(cmdRepeat).build(),
                    MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
                )

                override fun onCustomCommand(
                    session: MediaSession, controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand, args: Bundle
                ): ListenableFuture<SessionResult> {
                    when (customCommand.customAction) {
                        ACTION_CLOSE -> quit()
                        ACTION_REPEAT -> cycleRepeat()
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            })
            .setMediaButtonPreferences(layout())
            .build()
    }

    /** Adds previous/next to the session; both drive our own queue. */
    private inner class QueuePlayer(p: Player) : ForwardingPlayer(p) {
        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon().addAll(
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
            ).build()

        override fun isCommandAvailable(command: Int) = availableCommands.contains(command)
        override fun hasNextMediaItem() = true
        override fun hasPreviousMediaItem() = true
        override fun seekToNext() = drive(null, 1, false)
        override fun seekToNextMediaItem() = drive(null, 1, false)
        override fun seekToPrevious() = previous()
        override fun seekToPreviousMediaItem() = previous()
    }

    private fun previous() {
        // Like most players: restart the track if it's well underway, otherwise go back.
        if (exo.currentPosition > 3000 && exo.playbackState == Player.STATE_READY) exo.seekTo(0)
        else drive(null, -1, false)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    // Stay foreground during the gap/lookup between tracks so the next one can start from the background.
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        super.onUpdateNotification(session, startInForegroundRequired || resting || resolving)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                // Must go foreground within seconds; Media3 takes over with its own notification once audio is ready.
                ServiceCompat.startForeground(
                    this, PLACEHOLDER_ID,
                    NotificationCompat.Builder(this, CHANNEL)
                        .setSmallIcon(R.drawable.ic_stat)
                        .setContentTitle(getString(R.string.loading))
                        .setOngoing(true).setSilent(true).build(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
                postControls(getString(R.string.loading))
                val link = intent.getStringExtra(EXTRA_URL)?.let(YouTubeUrl::parse)
                if (link != null) drive(link, 1, false) else fail(R.string.err_not_youtube)
            }
            // Taps on the controls card (works even where the system media player hides custom buttons).
            ACTION_CLOSE -> quit()
            ACTION_REPEAT -> cycleRepeat()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /** A plain notification with Repeat and Close; some phone skins (e.g. realme UI) hide custom media buttons. */
    @SuppressLint("MissingPermission")
    private fun postControls(text: String) {
        cardText = text
        fun pi(action: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val (icon, label) = when (repeat) {
            Repeat.OFF -> R.drawable.ic_repeat_off to R.string.repeat_off
            Repeat.ALL -> R.drawable.ic_repeat_all to R.string.repeat_all
            Repeat.ONE -> R.drawable.ic_repeat_one to R.string.repeat_one
        }
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSilent(true).setOnlyAlertOnce(true)
            .addAction(icon, getString(label), pi(ACTION_REPEAT, 1))
            .addAction(R.drawable.ic_close, getString(R.string.close), pi(ACTION_CLOSE, 2))
            .build()
        NotificationManagerCompat.from(this).notify(CONTROLS_ID, n)
    }

    // ---- Queue driving ----------------------------------------------------------------------

    /**
     * Moves [dir] (+1 next, -1 previous) through the queue and plays the result.
     * With [link] set, first (re)builds the queue from the shared link. [auto] = triggered by track end.
     */
    private fun drive(link: Link?, dir: Int, auto: Boolean) {
        handler.removeCallbacks(advance)
        handler.removeCallbacks(idleStop)
        job?.cancel()
        resting = false
        resolving = true
        holdWake()
        job = scope.launch {
            try {
                if (link != null) withContext(Dispatchers.IO) { buildQueue(link) }
                var cur = index
                var tries = 0
                while (true) {
                    val target = withContext(Dispatchers.IO) { step(cur, dir) }
                    if (target == null) { noTarget(dir, auto); return@launch }
                    try {
                        val r = withContext(Dispatchers.IO) { Resolver.resolve(synchronized(this@PlaybackService) { queue[target] }) }
                        index = target
                        related = r.related
                        exo.setMediaItem(r.item)
                        exo.prepare()
                        exo.play()
                        postControls(r.item.mediaMetadata.title?.toString().orEmpty())
                        resolving = false
                        dropWake()
                        return@launch
                    } catch (e: Exception) {
                        // Skip dead playlist entries (up to 5 in a row); stop on network trouble.
                        if (e is CancellationException || e is IOException || queue.size <= 1 || ++tries >= 5) throw e
                        Log.w(TAG, "skipping unplayable item", e)
                        cur = target
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.e(TAG, "network", e); fail(R.string.err_network, e)
            } catch (e: ContentNotAvailableException) {
                Log.e(TAG, "unavailable", e); fail(R.string.err_unavailable, e)
            } catch (e: ExtractionException) {
                Log.e(TAG, "extraction", e); fail(R.string.err_extract, e)
            } catch (e: Exception) {
                Log.e(TAG, "generic", e); fail(R.string.err_generic, e)
            }
        }
    }

    private fun noTarget(dir: Int, auto: Boolean) {
        resolving = false
        dropWake()
        when {
            auto || exo.playbackState == Player.STATE_ENDED -> quit()   // playlist finished
            dir < 0 -> exo.seekTo(0)
        }
    }

    /** Playlist -> queue of its items (starting at the shared video); single video -> autoplay chain. */
    @Synchronized
    private fun buildQueue(link: Link) {
        queue.clear(); seen.clear()
        index = -1; autoplay = false; related = emptyList(); playlistUrl = null; nextPage = null
        val vid = link.videoId
        val list = link.listId
        if (list != null) {
            val url = if (list.startsWith("RD") && vid != null)
                "https://www.youtube.com/watch?v=$vid&list=$list"
            else "https://www.youtube.com/playlist?list=$list"
            try {
                val (items, np) = Resolver.playlistFirst(url)
                queue.addAll(items); nextPage = np; playlistUrl = url
                if (queue.isNotEmpty()) {
                    if (vid != null) {
                        var pos = queue.indexOfFirst { YouTubeUrl.idOf(it) == vid }
                        var pages = 0
                        while (pos < 0 && nextPage != null && pages++ < 5) {
                            loadMore()
                            pos = queue.indexOfFirst { YouTubeUrl.idOf(it) == vid }
                        }
                        if (pos < 0) { queue.add(0, YouTubeUrl.video(vid)); pos = 0 }
                        index = pos - 1
                    }
                    return
                }
            } catch (e: Exception) {
                if (e is CancellationException || e is IOException || vid == null) throw e
                Log.w(TAG, "playlist failed, falling back to the single video", e)
            }
            queue.clear(); playlistUrl = null; nextPage = null; index = -1
        }
        if (vid == null) throw ContentNotAvailableException("Empty playlist")
        queue.add(YouTubeUrl.video(vid)); seen.add(vid); autoplay = true
    }

    private fun loadMore(): Boolean {
        val page = nextPage ?: return false
        val (items, np) = Resolver.playlistMore(playlistUrl ?: return false, page)
        nextPage = np
        queue.addAll(items)
        return items.isNotEmpty()
    }

    /** Index to play after moving [dir] from [cur], or null if there is none. Blocking. */
    @Synchronized
    private fun step(cur: Int, dir: Int): Int? {
        if (dir < 0) {
            return when {
                cur - 1 >= 0 -> cur - 1
                repeat == Repeat.ALL && queue.isNotEmpty() -> queue.lastIndex
                else -> null
            }
        }
        while (cur + 1 >= queue.size && nextPage != null) if (!loadMore() && nextPage == null) break
        if (cur + 1 < queue.size) return cur + 1
        if (repeat == Repeat.ALL && queue.isNotEmpty()) return 0
        if (autoplay) {
            val next = related.firstOrNull { u -> YouTubeUrl.idOf(u)?.let { it !in seen } == true }
            if (next != null) {
                queue.add(next); seen.add(YouTubeUrl.idOf(next)!!)
                return queue.lastIndex
            }
        }
        return null
    }

    // ---- Repeat -----------------------------------------------------------------------------

    private fun applyRepeat() {
        exo.repeatMode = if (repeat == Repeat.ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    private fun cycleRepeat() {
        repeat = Repeat.values()[(repeat.ordinal + 1) % 3]   // off -> all -> one -> off
        applyRepeat()
        getSharedPreferences("s", MODE_PRIVATE).edit().putInt("repeat", repeat.ordinal).apply()
        session?.setMediaButtonPreferences(layout())
        postControls(cardText)
    }

    /**
     * Android shows 3 slots in compact controls (lock screen, collapsed shade): play/pause + BACK + FORWARD.
     * We put Repeat and Close there; Previous and Next go to the two overflow slots (expanded view).
     */
    private fun layout(): List<CommandButton> {
        val (icon, label) = when (repeat) {
            Repeat.OFF -> R.drawable.ic_repeat_off to R.string.repeat_off
            Repeat.ALL -> R.drawable.ic_repeat_all to R.string.repeat_all
            Repeat.ONE -> R.drawable.ic_repeat_one to R.string.repeat_one
        }
        val repeatButton = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setDisplayName(getString(label)).setCustomIconResId(icon)
            .setSessionCommand(cmdRepeat).setSlots(CommandButton.SLOT_BACK).build()
        val closeButton = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setDisplayName(getString(R.string.close)).setCustomIconResId(R.drawable.ic_close)
            .setSessionCommand(cmdClose).setSlots(CommandButton.SLOT_FORWARD).build()
        val previousButton = CommandButton.Builder(CommandButton.ICON_PREVIOUS)
            .setDisplayName(getString(R.string.previous))
            .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .setSlots(CommandButton.SLOT_OVERFLOW).build()
        val nextButton = CommandButton.Builder(CommandButton.ICON_NEXT)
            .setDisplayName(getString(R.string.next))
            .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .setSlots(CommandButton.SLOT_OVERFLOW).build()
        return listOf(repeatButton, closeButton, previousButton, nextButton)
    }

    // ---- Wake lock, errors, teardown --------------------------------------------------------

    private fun holdWake() { if (!wake.isHeld) wake.acquire(60_000L) }
    private fun dropWake() { if (wake.isHeld) wake.release() }

    private fun fail(@StringRes msg: Int, cause: Throwable? = null) {
        val detail = cause?.let { "\n(${it.javaClass.simpleName}: ${it.message?.take(70)})" }.orEmpty()
        Toast.makeText(this, getString(msg) + detail, Toast.LENGTH_LONG).show()
        quit()
    }

    private fun quit() {
        handler.removeCallbacksAndMessages(null)
        job?.cancel()
        resting = false; resolving = false
        dropWake()
        exo.stop()
        exo.clearMediaItems()
        stopForeground(STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).apply {
            cancel(NOTIFICATION_ID); cancel(CONTROLS_ID); cancel(PLACEHOLDER_ID)
        }
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        dropWake()
        session?.release()
        exo.release()
        session = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_PLAY = "app.ytaudio.PLAY"
        const val ACTION_CLOSE = "app.ytaudio.CLOSE"
        const val ACTION_REPEAT = "app.ytaudio.REPEAT"
        const val EXTRA_URL = "url"
        private const val GAP_MS = 3_000L
        private const val IDLE_MS = 10 * 60 * 1000L
        private const val TAG = "YTAudio"
        private const val CHANNEL = "playback"
        private const val NOTIFICATION_ID = 1001   // Media3 media notification
        private const val CONTROLS_ID = 1002        // Repeat / Close card
        private const val PLACEHOLDER_ID = 1003     // brief "loading" foreground notification
    }
}
