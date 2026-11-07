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
import android.os.SystemClock
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
import androidx.media3.exoplayer.DefaultLoadControl
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException

@UnstableApi
class PlaybackService : MediaSessionService() {

    private lateinit var exo: ExoPlayer
    private lateinit var wake: PowerManager.WakeLock
    private var session: MediaSession? = null
    private var job: Job? = null
    private var prefetchJob: Job? = null
    private var enqueueJob: Job? = null
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** All queue rules live in [QueueManager] (unit-tested, no Android code). */
    private val queue = QueueManager(NewPipeSource) { msg, t -> Log.w(TAG, msg, t) }
    private var repeat: Repeat
        get() = queue.repeat
        set(v) { queue.repeat = v }

    private val advance = Runnable { drive(null, 1, true) }
    private val idleStop = Runnable { quit(keepSession = true) }

    private var resting = false             // the short gap between tracks
    private var resolving = false           // looking up the next stream
    private var cardText = ""               // current text on the controls card
    private var closing = false
    private var retries = 0                 // consecutive automatic recoveries after a stream error
    private var currentTitle = ""
    private var hapticPending = false       // buzz once when the track we just loaded starts playing
    private var prefetched: Pair<String, Resolved>? = null
    private var prefetchDoneFor = -2        // playing index we already tried to prefetch for
    private var tickCount = 0
    private var sleepEndsAt = 0L            // SystemClock.elapsedRealtime() deadline; 0 = off
    private var sleepAfterTrack = false

    private val cmdClose = SessionCommand(ACTION_CLOSE, Bundle.EMPTY)
    private val cmdRepeat = SessionCommand(ACTION_REPEAT, Bundle.EMPTY)

    /** Every 5 s while playing: remember the position, and warm up the next track near the end. */
    private val progressTick = object : Runnable {
        override fun run() {
            if (!exo.isPlaying) return
            if (++tickCount % 2 == 0) saveProgress()
            maybePrefetch()
            handler.postDelayed(this, TICK_MS)
        }
    }

    /** Sleep timer: counts down, fades the volume out over the last seconds, then closes (the session is kept). */
    private val sleepTick = object : Runnable {
        override fun run() {
            val left = sleepEndsAt - SystemClock.elapsedRealtime()
            if (left <= 0) { quit(keepSession = true); return }
            if (left <= FADE_MS) exo.volume = (left.toFloat() / FADE_MS).coerceIn(0f, 1f)
            handler.postDelayed(this, if (left <= FADE_MS) 250L else 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        UpdateCheckJob.schedule(this)      // makes sure the daily update check exists (no-op if already scheduled)
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
        repeat = Repeat.entries[Prefs.sp(this).getInt("repeat", 0).coerceIn(0, 2)]

        val http = OkHttpDataSource.Factory(Http.client).setUserAgent(USER_AGENT)
        exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http))
            // Bigger buffer for flaky mobile networks; the long back-buffer keeps the whole track in
            // memory so Repeat-one restarts instantly instead of downloading it again.
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(30_000, 120_000, 2_500, 5_000)
                    .setBackBuffer(10 * 60_000, true)
                    .build()
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        // Audio only: never download video (matters for the HLS fallback on live streams).
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
        exo.setPlaybackSpeed(Prefs.speed(this))
        applyRepeat()
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val io = error.errorCode in 2000..2999      // network / HTTP (403 = expired stream URL)
                when {
                    error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                        exo.seekToDefaultPosition(); exo.prepare()
                    }
                    io && retries < MAX_RETRIES -> recover()
                    else -> fail(if (io) R.string.err_network else R.string.err_generic, error)
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) retries = 0
                if (state == Player.STATE_ENDED) {
                    if (sleepAfterTrack) { quit(keepSession = true); return }
                    // Rest a moment (setting), then continue (see `advance`).
                    resting = true
                    holdWake()
                    val gap = Prefs.gapSeconds(this@PlaybackService) * 1000L
                    if (gap > 0) handler.postDelayed(advance, gap) else handler.post(advance)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                handler.removeCallbacks(idleStop)
                handler.removeCallbacks(progressTick)
                if (isPlaying) {
                    if (hapticPending) { hapticPending = false; Haptics.start(this@PlaybackService) }
                    handler.post(progressTick)
                } else {
                    saveProgress()
                    // Paused (or focus lost) for a while -> close the app (the session stays resumable).
                    val idle = Prefs.idleMinutes(this@PlaybackService)
                    if (!exo.playWhenReady && idle > 0) handler.postDelayed(idleStop, idle * 60_000L)
                }
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
                        ACTION_CLOSE -> { Haptics.click(this@PlaybackService); quit() }
                        ACTION_REPEAT -> { Haptics.click(this@PlaybackService); cycleRepeat() }
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            })
            .setMediaButtonPreferences(layout())
            .build()
        instance = this          // last: the help page may call into the service from now on
    }

    /** Adds previous/next to the session (both drive our own queue) and a light tick for controller taps. */
    private inner class QueuePlayer(p: Player) : ForwardingPlayer(p) {
        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon().addAll(
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
            ).build()

        override fun isCommandAvailable(command: Int) = availableCommands.contains(command)
        override fun hasNextMediaItem() = true
        override fun hasPreviousMediaItem() = true
        override fun play() { Haptics.click(this@PlaybackService); super.play() }
        override fun pause() { Haptics.click(this@PlaybackService); super.pause() }
        override fun seekToNext() { Haptics.click(this@PlaybackService); drive(null, 1, false) }
        override fun seekToNextMediaItem() = seekToNext()
        override fun seekToPrevious() { Haptics.click(this@PlaybackService); previous() }
        override fun seekToPreviousMediaItem() = seekToPrevious()
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
        // Media3 may take over the foreground slot (which removes our card), so make sure the card is posted.
        if (!closing) postControls(cardText)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                startCard()
                val link = intent.getStringExtra(EXTRA_URL)?.let(YouTubeUrl::parse)
                val busy = queue.size > 0 && (exo.mediaItemCount > 0 || resolving)
                when {
                    link == null -> fail(R.string.err_not_youtube)
                    // Default is to replace the current track; "add to queue" is an opt-in setting.
                    intent.getBooleanExtra(EXTRA_ENQUEUE, false) && busy -> enqueue(link)
                    else -> drive(link, 1, false)
                }
            }
            ACTION_RESUME -> resume()
            // Taps on the controls card (works even where the system media player hides custom buttons).
            ACTION_CLOSE -> { Haptics.click(this); quit() }
            ACTION_REPEAT -> { Haptics.click(this); cycleRepeat() }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /** Must go foreground within seconds of being started: the Repeat/Close card doubles as that notification. */
    private fun startCard() {
        closing = false
        ServiceCompat.startForeground(
            this, CONTROLS_ID, controlsNotification(cardText), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
    }

    /** A plain notification with Repeat and Close; some phone skins (e.g. realme UI) hide custom media buttons. */
    private fun controlsNotification(text: String): Notification {
        fun pi(action: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val (icon, label) = when (repeat) {
            Repeat.OFF -> R.drawable.ic_repeat_off to R.string.repeat_off
            Repeat.ALL -> R.drawable.ic_repeat_all to R.string.repeat_all
            Repeat.ONE -> R.drawable.ic_repeat_one to R.string.repeat_one
        }
        // Tapping the card opens the help page, which holds the sleep timer and speed controls.
        val open = PendingIntent.getActivity(
            this, 3, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setSilent(true).setOnlyAlertOnce(true).setOngoing(true)
            .addAction(icon, getString(label), pi(ACTION_REPEAT, 1))
            .addAction(R.drawable.ic_close, getString(R.string.close), pi(ACTION_CLOSE, 2))
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun postControls(text: String) {
        cardText = text
        NotificationManagerCompat.from(this).notify(CONTROLS_ID, controlsNotification(text))
    }

    // ---- Queue driving ----------------------------------------------------------------------

    /**
     * Moves [dir] (+1 next, -1 previous) through the queue and plays the result.
     * With [link] set, first (re)builds the queue from the shared link. [auto] = triggered by track end.
     * [startAt] resumes inside the track (used when resuming a saved session).
     */
    private fun drive(link: Link?, dir: Int, auto: Boolean, startAt: Long = 0L) {
        handler.removeCallbacks(advance)
        handler.removeCallbacks(idleStop)
        job?.cancel()
        prefetchJob?.cancel()
        Http.cancelPending()               // abort stale lookups so they can't block this one
        retries = 0
        resting = false
        resolving = true
        queue.autoplayAllowed = Prefs.autoplay(this)
        val saver = useSaver()
        holdWake()
        job = scope.launch {
            try {
                if (link != null) { prefetched = null; withContext(Dispatchers.IO) { queue.build(link) } }
                var cur = queue.index
                var skipped = 0
                while (true) {
                    val target = withContext(Dispatchers.IO) { queue.step(cur, dir) }
                    if (target == null) { noTarget(dir, auto); return@launch }
                    queue.index = target        // move right away so quick repeated taps keep advancing
                    val url = queue.urlAt(target)
                    try {
                        // The next track is usually already looked up (see maybePrefetch).
                        val ready = prefetched?.takeIf { it.first == url }?.second
                        val r = ready ?: withContext(Dispatchers.IO) { Resolver.resolve(url, saver) }
                        prefetched = null
                        play(r, auto, startAt)
                        return@launch
                    } catch (e: Exception) {
                        // Skip dead playlist entries (up to MAX_SKIPS in a row); stop on network/rate-limit trouble.
                        if (e is CancellationException || !canSkip(e) || ++skipped >= MAX_SKIPS) throw e
                        Log.w(TAG, "skipping unplayable item", e)
                        cur = target
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isActive) return@launch      // superseded by a newer request: ignore its failure
                report(e)
            }
        }
    }

    private fun play(r: Resolved, auto: Boolean, startAt: Long) {
        queue.playingIndex = queue.index
        queue.setRelated(r.related)
        currentTitle = r.item.mediaMetadata.title?.toString().orEmpty()
        hapticPending = !auto                // a tick when the user's action starts audio, not on every auto-advance
        prefetchDoneFor = -2
        if (startAt > 0) exo.setMediaItem(r.item, startAt) else exo.setMediaItem(r.item)
        exo.prepare()
        exo.play()
        postControls(currentTitle)
        queue.snapshot()?.let { SessionStore.saveQueue(this, it, queue.isAutoplayChain(), currentTitle) }
        resolving = false
        dropWake()
    }

    /** Last 30 s of a track: look up the next stream now so the switch is instant. Best effort. */
    private fun maybePrefetch() {
        val duration = exo.duration
        if (duration == C.TIME_UNSET || duration <= 0 || exo.isCurrentMediaItemLive || repeat == Repeat.ONE) return
        if (resolving || prefetchJob?.isActive == true) return
        val playing = queue.playingIndex
        if (playing < 0 || prefetchDoneFor == playing) return
        if (duration - exo.currentPosition > PREFETCH_BEFORE_MS) return
        prefetchDoneFor = playing
        queue.autoplayAllowed = Prefs.autoplay(this)
        val saver = useSaver()
        prefetchJob = scope.launch {
            try {
                val next = withContext(Dispatchers.IO) { queue.step(playing, 1) } ?: return@launch
                val url = queue.urlAt(next)
                if (prefetched?.first == url) return@launch
                prefetched = url to withContext(Dispatchers.IO) { Resolver.resolve(url, saver) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "prefetch skipped", e)
            }
        }
    }

    /** Sharing while something plays, with the "add to queue" setting on. */
    private fun enqueue(link: Link) {
        enqueueJob?.cancel()
        enqueueJob = scope.launch {
            try {
                val urls = withContext(Dispatchers.IO) { queue.expand(link) }
                queue.append(urls)
                prefetchDoneFor = -2            // the next track may have changed
                Toast.makeText(this@PlaybackService, getString(R.string.queue_added, urls.size), Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isActive) return@launch
                ErrorLog.record(this@PlaybackService, "Add to queue failed", e)
                Toast.makeText(this@PlaybackService, getString(R.string.err_generic), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Picks up the saved queue and position (help page button, launcher shortcut). */
    private fun resume() {
        val s = SessionStore.load(this)
        cardText = s?.title.orEmpty()
        startCard()
        if (s == null) { quit(keepSession = true); return }
        queue.restore(QueueSnapshot(s.urls, s.index), s.autoplay)
        drive(null, 1, false, startAt = s.positionMs)
    }

    /** Stream died mid-track (dropped connection, expired URL): look it up again and resume where it stopped. */
    private fun recover() {
        val url = queue.urlOrNull(queue.playingIndex) ?: return fail(R.string.err_generic)
        val position = if (exo.isCurrentMediaItemLive) C.TIME_UNSET else exo.currentPosition
        val attempt = ++retries
        handler.removeCallbacks(advance)
        handler.removeCallbacks(idleStop)
        job?.cancel()
        Http.cancelPending()
        resolving = true
        val saver = useSaver()
        holdWake()
        job = scope.launch {
            try {
                delay(attempt * RETRY_DELAY_MS)         // 2 s, 4 s, 6 s
                val r = withContext(Dispatchers.IO) { Resolver.resolve(url, saver) }
                queue.setRelated(r.related)
                exo.setMediaItem(r.item, position)
                exo.prepare()
                resolving = false
                dropWake()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isActive) return@launch
                if (e is IOException && retries < MAX_RETRIES) recover() else report(e)
            }
        }
    }

    /** Dead entries (removed/private video) are skipped; network, rate-limit and bot checks must not be. */
    private fun canSkip(e: Exception) =
        e !is IOException && e !is ReCaptchaException && !isBotCheck(e) && queue.size > 1

    private fun isBotCheck(e: Exception) =
        e.javaClass.simpleName.contains("NotBot", ignoreCase = true) ||
            e.message?.contains("not a bot", ignoreCase = true) == true

    private fun report(e: Exception) {
        Log.e(TAG, "playback failed", e)
        when {
            e is ReCaptchaException -> fail(R.string.err_ratelimit, e)
            e is IOException -> fail(R.string.err_network, e)
            e is EmptyQueueException -> fail(R.string.err_unavailable, e)
            e is ContentNotAvailableException ->
                fail(if (isBotCheck(e)) R.string.err_bot else R.string.err_unavailable, e)
            e is ExtractionException -> fail(R.string.err_extract, e)
            else -> fail(R.string.err_generic, e)
        }
    }

    private fun noTarget(dir: Int, auto: Boolean) {
        resolving = false
        dropWake()
        queue.index = queue.playingIndex      // nothing new was loaded: cursor goes back to what is playing
        when {
            auto || exo.playbackState == Player.STATE_ENDED -> quit()   // playlist finished: nothing to resume
            dir < 0 -> exo.seekTo(0)
        }
    }

    /** Data saver: pick the lowest-bitrate audio stream. */
    private fun useSaver(): Boolean = Prefs.quality(this) == "saver"

    // ---- Repeat -----------------------------------------------------------------------------

    private fun applyRepeat() {
        exo.repeatMode = if (repeat == Repeat.ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    private fun cycleRepeat() {
        repeat = Repeat.entries[(repeat.ordinal + 1) % 3]   // off -> all -> one -> off
        applyRepeat()
        Prefs.put(this, "repeat", repeat.ordinal)
        session?.setMediaButtonPreferences(layout())
        postControls(cardText)
    }

    /**
     * Previous / Play / Next stay in their standard positions. Repeat and Close go in the two overflow
     * slots, which expanded players draw on the outer left and right (same place other music apps put
     * repeat/shuffle on realme UI).
     */
    private fun layout(): List<CommandButton> {
        val (icon, label) = when (repeat) {
            Repeat.OFF -> R.drawable.ic_repeat_off to R.string.repeat_off
            Repeat.ALL -> R.drawable.ic_repeat_all to R.string.repeat_all
            Repeat.ONE -> R.drawable.ic_repeat_one to R.string.repeat_one
        }
        val repeatButton = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setDisplayName(getString(label)).setCustomIconResId(icon)
            .setSessionCommand(cmdRepeat).setSlots(CommandButton.SLOT_OVERFLOW).build()
        val closeButton = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setDisplayName(getString(R.string.close)).setCustomIconResId(R.drawable.ic_close)
            .setSessionCommand(cmdClose).setSlots(CommandButton.SLOT_OVERFLOW).build()
        return listOf(repeatButton, closeButton)
    }

    // ---- Controls used by the help page (always called on the main thread) -------------------

    fun speed(): Float = exo.playbackParameters.speed

    fun setSpeed(value: Float) {
        exo.setPlaybackSpeed(value)
        Prefs.put(this, "speed", value)
    }

    /** Milliseconds left, [SLEEP_OFF], or [SLEEP_END] (stops after the current track). */
    fun sleepStatus(): Long = when {
        sleepAfterTrack -> SLEEP_END
        sleepEndsAt > 0 -> (sleepEndsAt - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        else -> SLEEP_OFF
    }

    fun setSleepMinutes(minutes: Int) {
        cancelSleep()
        if (minutes > 0) {
            sleepEndsAt = SystemClock.elapsedRealtime() + minutes * 60_000L
            handler.postDelayed(sleepTick, 1000L)
        }
    }

    fun setSleepAfterTrack() { cancelSleep(); sleepAfterTrack = true }

    fun cancelSleep() {
        handler.removeCallbacks(sleepTick)
        sleepEndsAt = 0L
        sleepAfterTrack = false
        exo.volume = 1f
    }

    // ---- Wake lock, errors, teardown --------------------------------------------------------

    private fun holdWake() { if (!wake.isHeld) wake.acquire(60_000L) }
    private fun dropWake() { if (wake.isHeld) wake.release() }

    private fun fail(@StringRes msg: Int, cause: Throwable? = null) {
        val text = getString(msg)
        ErrorLog.record(this, text, cause)
        val detail = cause?.let { "\n(${it.javaClass.simpleName}: ${it.message?.take(70)})" }.orEmpty()
        Toast.makeText(this, text + detail, Toast.LENGTH_LONG).show()
        quit(keepSession = true)
    }

    /** Remembers where we were so a resume can pick up (not for a finished playlist or an explicit Close). */
    private fun saveProgress() {
        if (exo.mediaItemCount == 0 || queue.playingIndex < 0 || exo.isCurrentMediaItemLive) return
        val position = if (exo.playbackState == Player.STATE_ENDED) 0L else exo.currentPosition
        SessionStore.savePosition(this, position)
    }

    /** Stops playback and the service. With [keepSession] the queue and position stay saved for "Resume". */
    private fun quit(keepSession: Boolean = false) {
        if (keepSession) saveProgress() else SessionStore.clear(this)
        closing = true
        handler.removeCallbacksAndMessages(null)
        job?.cancel(); prefetchJob?.cancel(); enqueueJob?.cancel()
        Http.cancelPending()
        resting = false; resolving = false
        sleepEndsAt = 0L; sleepAfterTrack = false
        dropWake()
        exo.volume = 1f
        exo.stop()
        exo.clearMediaItems()
        stopForeground(STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).apply {
            cancel(NOTIFICATION_ID); cancel(CONTROLS_ID)
        }
        stopSelf()
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        dropWake()
        session?.release()
        exo.release()
        session = null
        super.onDestroy()
    }

    companion object {
        /** The running service, for the help page's timer and speed controls (main thread only). */
        @Volatile var instance: PlaybackService? = null
            private set

        /** True while the service is alive; the updater never installs during playback. */
        val active: Boolean get() = instance != null

        const val ACTION_PLAY = "app.ytaudio.PLAY"
        const val ACTION_RESUME = "app.ytaudio.RESUME"
        const val ACTION_CLOSE = "app.ytaudio.CLOSE"
        const val ACTION_REPEAT = "app.ytaudio.REPEAT"
        const val EXTRA_URL = "url"
        const val EXTRA_ENQUEUE = "enqueue"
        const val SLEEP_OFF = -1L
        const val SLEEP_END = -2L
        private const val FADE_MS = 10_000L
        private const val PREFETCH_BEFORE_MS = 30_000L
        private const val TICK_MS = 5_000L
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 2_000L
        private const val MAX_SKIPS = 5
        private const val TAG = "YTAudio"
        private const val CHANNEL = "playback"
        private const val NOTIFICATION_ID = 1001   // Media3 media notification
        private const val CONTROLS_ID = 1002        // Repeat / Close card
    }
}
