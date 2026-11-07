package app.ytaudio

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import java.io.IOException

/**
 * Once a day (survives reboots): looks for a new release and, if there is one, floats a notification.
 * It never downloads or installs anything: updating always starts from the user tapping "Update now".
 */
class UpdateCheckJob : JobService() {
    private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        worker = Thread {
            var retry = false
            try {
                if (Updater.enabled && Updater.checkEnabled(this)) {
                    Updater.check()?.let { Updater.notifyAvailable(this, it.version) }
                }
            } catch (e: IOException) {
                Log.w("YTAudio", "update check: no connection or GitHub unreachable", e)
                retry = true            // try again shortly (the system backs off), e.g. phone was offline
            } catch (e: Exception) {
                Log.w("YTAudio", "update check failed", e)
            } finally {
                jobFinished(params, retry)
            }
        }.also { it.start() }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        worker?.interrupt()
        return true      // try again later
    }

    companion object {
        private const val JOB_ID = 4242

        /** Idempotent: call whenever the app starts. Schedules or cancels according to the user's setting. */
        fun schedule(ctx: Context) {
            try {
                val js = ctx.getSystemService(JobScheduler::class.java)
                if (!Updater.enabled || !Updater.checkEnabled(ctx)) { js.cancel(JOB_ID); return }
                if (js.getPendingJob(JOB_ID) != null) return
                js.schedule(
                    JobInfo.Builder(JOB_ID, ComponentName(ctx, UpdateCheckJob::class.java))
                        .setPeriodic(24L * 60 * 60 * 1000)
                        .setPersisted(true)
                        .build()
                )
            } catch (e: Exception) {
                Log.w("YTAudio", "could not schedule the update check", e)    // never let this break the app
            }
        }
    }
}
