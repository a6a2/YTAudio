package app.ytaudio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.pm.PackageInfoCompat
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * In-app updates from this project's GitHub Releases.
 *
 * Safety: the APK must come from this repo's release assets over HTTPS, its SHA-256 must match the release's
 * SHA256SUMS.txt, it must be this app with a higher versionCode, and Android itself refuses any update that
 * isn't signed with the same key as the installed app, so a tampered release can't be installed.
 */
object Updater {
    const val CHANNEL = "updates_alert"       // high importance, so the notification floats on screen
    private const val OLD_CHANNEL = "updates"  // low-importance channel from 2.2.x; removed
    const val NOTIFICATION_ID = 2001
    const val CONFIRM_NOTIFICATION_ID = 2002
    const val PREFS = "settings"
    const val KEY_CHECK = "update_check"   // daily background check (default on)
    private const val KEY_NOTIFIED = "update_notified"   // last version we already announced

    /** True while MainActivity is on screen: then the installer's confirmation sheet may open directly. */
    @Volatile var uiVisible = false

    fun repo(): String? = BuildConfig.UPDATE_REPO.takeIf { UpdateLogic.isValidRepo(it) }
    val enabled: Boolean get() = repo() != null

    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun checkEnabled(ctx: Context) = prefs(ctx).getBoolean(KEY_CHECK, true)

    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    /** Opens the Android screen where the user allows this app to install updates. */
    fun installPermissionIntent(ctx: Context) =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Blocking. The newer release, or null if up to date / nothing published. Throws IOException on network trouble. */
    fun check(): UpdateInfo? {
        val repo = repo() ?: return null
        val req = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "YTAudio-updater")
            .build()
        Http.client.newCall(req).execute().use { r ->
            if (r.code == 404) return null                       // no release published yet
            if (!r.isSuccessful) throw IOException("GitHub answered HTTP ${r.code}")
            val info = UpdateLogic.parseRelease(r.body?.string().orEmpty()) ?: return null
            if (!UpdateLogic.isNewer(info.version, BuildConfig.VERSION_NAME)) return null
            if (!UpdateLogic.isTrustedAssetUrl(info.apkUrl, repo)) throw IOException("Untrusted download address")
            return info
        }
    }

    private fun fetchText(url: String): String {
        Http.client.newCall(Request.Builder().url(url).header("User-Agent", "YTAudio-updater").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return r.body?.string().orEmpty()
        }
    }

    /** Blocking. Downloads and verifies the APK; returns the file. Throws IOException if anything doesn't check out. */
    fun download(ctx: Context, info: UpdateInfo, onProgress: (Int) -> Unit = {}): File {
        val repo = repo() ?: throw IOException("Updates are not configured")
        if (!UpdateLogic.isTrustedAssetUrl(info.apkUrl, repo)) throw IOException("Untrusted download address")
        val sumsUrl = info.sha256Url ?: throw IOException("Release has no SHA256SUMS.txt")
        if (!UpdateLogic.isTrustedAssetUrl(sumsUrl, repo)) throw IOException("Untrusted checksum address")
        val expected = UpdateLogic.expectedSha(fetchText(sumsUrl), info.apkName)
            ?: throw IOException("No checksum listed for ${info.apkName}")

        val dir = File(ctx.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val file = File(dir, "update.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        Http.client.newCall(Request.Builder().url(info.apkUrl).header("User-Agent", "YTAudio-updater").build())
            .execute().use { r ->
                if (!r.isSuccessful) throw IOException("Download failed: HTTP ${r.code}")
                val body = r.body ?: throw IOException("Empty download")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var last = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != last) { last = pct; onProgress(pct) }
                            }
                        }
                    }
                }
            }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(expected, ignoreCase = true)) {
            file.delete(); throw IOException("Checksum mismatch: the download is corrupted or was tampered with")
        }
        val pi = ctx.packageManager.getPackageArchiveInfo(file.path, 0)
        val installed = PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0))
        if (pi == null || pi.packageName != ctx.packageName || PackageInfoCompat.getLongVersionCode(pi) <= installed) {
            file.delete(); throw IOException("The downloaded file is not a newer version of this app")
        }
        return file
    }

    /**
     * Hands the APK to Android's installer. Only ever called after the user taps "Update now", and Android
     * always asks for confirmation. Installing restarts the app, which stops playback.
     */
    fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("update.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val result = Intent(ctx, UpdateReceiver::class.java).setAction(UpdateReceiver.ACTION)
            // Mutable on purpose: the system adds the install status to this intent.
            val pending = PendingIntent.getBroadcast(ctx, id, result, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pending.intentSender)
        }
    }

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel(OLD_CHANNEL)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, ctx.getString(R.string.update_channel), NotificationManager.IMPORTANCE_HIGH)
        )
    }

    /** Floating (heads-up) notification, shown once per new version so a daily check never nags. */
    fun notifyAvailable(ctx: Context, version: String) {
        if (prefs(ctx).getString(KEY_NOTIFIED, null) == version) return
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setColor(ctx.getColor(R.color.status_update))
            .setContentTitle(ctx.getString(R.string.update_notify_title))
            .setContentText(ctx.getString(R.string.update_notify_text, version))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setContentIntent(open).setAutoCancel(true).build()
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
            markAnnounced(ctx, version)
        } catch (_: SecurityException) {}
    }

    /** The user has seen this version (pop-up in the app), so the floating notification would only repeat it. */
    fun markAnnounced(ctx: Context, version: String) {
        prefs(ctx).edit().putString(KEY_NOTIFIED, version).apply()
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}
