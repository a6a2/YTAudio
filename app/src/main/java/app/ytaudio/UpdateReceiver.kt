package app.ytaudio

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat

/** Receives the installer's result for an update started by [Updater.install]. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm: Intent? =
                    if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirm == null) return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (Updater.uiVisible) {
                    // The user just tapped "Update now": Android's confirmation sheet can open right away.
                    try { context.startActivity(confirm) } catch (_: Exception) { notifyConfirm(context, confirm) }
                } else {
                    // Background (automatic) update: apps can't open screens, so ask via a notification.
                    notifyConfirm(context, confirm)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                context.getSystemService(NotificationManager::class.java).cancel(Updater.CONFIRM_NOTIFICATION_ID)
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                Toast.makeText(context, context.getString(R.string.update_failed, msg), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun notifyConfirm(context: Context, confirm: Intent) {
        Updater.ensureChannel(context)
        val tap = PendingIntent.getActivity(context, 1, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, Updater.CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.update_confirm_title))
            .setContentIntent(tap).setAutoCancel(true).build()
        try { context.getSystemService(NotificationManager::class.java).notify(Updater.CONFIRM_NOTIFICATION_ID, n) }
        catch (_: SecurityException) {}
    }

    companion object { const val ACTION = "app.ytaudio.UPDATE_RESULT" }
}
