package app.ytaudio

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.util.Log

/** Once a day: looks for a new release, then installs it or notifies. Never while audio is playing. */
class UpdateCheckJob : JobService() {
    private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        worker = Thread {
            try { run() } catch (e: Exception) { Log.w("YTAudio", "update check failed", e) }
            finally { jobFinished(params, false) }
        }.also { it.start() }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        worker?.interrupt()
        return true      // try again later
    }

    private fun run() {
        if (!Updater.enabled || !Updater.checkEnabled(this)) return
        val info = Updater.check() ?: return
        val metered = getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered
        val canAuto = Updater.autoEnabled(this) && Updater.canInstall(this) && !PlaybackService.active && !metered
        if (!canAuto) { Updater.notifyAvailable(this, info.version); return }
        val apk = Updater.download(this, info)
        if (PlaybackService.active) { Updater.notifyAvailable(this, info.version); return }   // music started meanwhile
        Updater.install(this, apk, silentIfPossible = true)
    }

    companion object {
        private const val JOB_ID = 4242

        /**
         * Idempotent: called whenever the app or the player starts. Schedules or cancels according to the user's setting.
         * Not persisted across reboots (that would need the boot permission), so it is re-armed on next use.
         */
        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java)
            if (!Updater.enabled || !Updater.checkEnabled(ctx)) { js.cancel(JOB_ID); return }
            if (js.getPendingJob(JOB_ID) != null) return
            js.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(ctx, UpdateCheckJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(24L * 60 * 60 * 1000)
                    .setPersisted(false)
                    .build()
            )
        }
    }
}
