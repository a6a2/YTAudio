package app.ytaudio

import org.json.JSONObject

data class UpdateInfo(
    val version: String,
    val apkName: String,
    val apkUrl: String,
    val sha256Url: String?,
    val notes: String,
    val pageUrl: String,
)

/** Pure update logic (no Android, no network) so it can be unit-tested. */
object UpdateLogic {

    /** "v2.1.0" / "2.1.0-beta1" -> [2, 1, 0]. */
    fun parseVersion(s: String): List<Int> =
        s.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
            .split('.').map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    fun isNewer(remote: String, local: String): Boolean {
        val a = parseVersion(remote)
        val b = parseVersion(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** GitHub "latest release" JSON -> the update it offers, or null (draft, pre-release, no signed APK). */
    fun parseRelease(json: String): UpdateInfo? {
        val o = JSONObject(json)
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
        val tag = o.optString("tag_name").ifBlank { return null }
        val assets = o.optJSONArray("assets") ?: return null
        var apk: JSONObject? = null
        var sums: JSONObject? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name")
            // Test builds are named ...-UNSIGNED-TEST.apk and must never be offered.
            if (name.endsWith(".apk", true) && !name.contains("UNSIGNED", true) && apk == null) apk = a
            if (name.equals("SHA256SUMS.txt", true)) sums = a
        }
        val chosen = apk ?: return null
        return UpdateInfo(
            version = tag.removePrefix("v").removePrefix("V"),
            apkName = chosen.getString("name"),
            apkUrl = chosen.getString("browser_download_url"),
            sha256Url = sums?.optString("browser_download_url")?.ifBlank { null },
            notes = o.optString("body"),
            pageUrl = o.optString("html_url"),
        )
    }

    /** Reads `sha256sum` output ("<hash>  <file>") and returns the lowercase hash for [apkName]. */
    fun expectedSha(sumsText: String, apkName: String): String? =
        sumsText.lineSequence().map { it.trim() }.mapNotNull { line ->
            val parts = line.split(Regex("\\s+"), 2)
            if (parts.size == 2 && parts[1].trimStart('*') == apkName) parts[0].lowercase() else null
        }.firstOrNull()

    /** Only accept downloads served from this repo's own release assets over HTTPS. */
    fun isTrustedAssetUrl(url: String, repo: String) =
        url.startsWith("https://github.com/$repo/releases/download/")

    fun isValidRepo(s: String) = Regex("""[\w.-]+/[\w.-]+""").matches(s)
}
