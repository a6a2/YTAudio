package app.ytaudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateLogicTest {
    private val base = "https://github.com/o/r/releases/download/v2.2.0"
    private val release = """{"tag_name":"v2.2.0","draft":false,"prerelease":false,"html_url":"https://github.com/o/r/releases/tag/v2.2.0","body":"notes","assets":[
        {"name":"SHA256SUMS.txt","browser_download_url":"$base/SHA256SUMS.txt"},
        {"name":"YTAudio-v2.2.0-UNSIGNED-TEST.apk","browser_download_url":"$base/bad.apk"},
        {"name":"YTAudio-v2.2.0.apk","browser_download_url":"$base/YTAudio-v2.2.0.apk"}]}"""

    @Test fun newerVersions() {
        assertTrue(UpdateLogic.isNewer("v2.2.0", "2.1.0"))
        assertTrue(UpdateLogic.isNewer("2.10.0", "2.9.9"))   // numeric, not alphabetical
        assertTrue(UpdateLogic.isNewer("3.0", "2.9.9"))
        assertTrue(UpdateLogic.isNewer("2.2.0-beta1", "2.1.0"))
    }

    @Test fun notNewer() {
        assertFalse(UpdateLogic.isNewer("2.1.0", "2.1.0"))
        assertFalse(UpdateLogic.isNewer("2.0.4", "2.1.0"))
        assertFalse(UpdateLogic.isNewer("v2.1", "2.1.0"))
    }

    @Test fun parsesReleaseAndSkipsTestBuilds() {
        val u = UpdateLogic.parseRelease(release)!!
        assertEquals("2.2.0", u.version)
        assertEquals("YTAudio-v2.2.0.apk", u.apkName)
        assertEquals("$base/SHA256SUMS.txt", u.sha256Url)
    }

    @Test fun ignoresDraftsPrereleasesAndMissingApks() {
        assertNull(UpdateLogic.parseRelease(release.replace("\"prerelease\":false", "\"prerelease\":true")))
        assertNull(UpdateLogic.parseRelease(release.replace("\"draft\":false", "\"draft\":true")))
        assertNull(UpdateLogic.parseRelease("""{"tag_name":"v9","assets":[]}"""))
        assertNull(UpdateLogic.parseRelease("""{"tag_name":"v9","assets":[{"name":"x-UNSIGNED-TEST.apk","browser_download_url":"u"}]}"""))
    }

    @Test fun checksumLookup() {
        val sums = "AbC123  YTAudio-v2.2.0.apk\nffff00 *other.apk\n"
        assertEquals("abc123", UpdateLogic.expectedSha(sums, "YTAudio-v2.2.0.apk"))
        assertEquals("ffff00", UpdateLogic.expectedSha(sums, "other.apk"))
        assertNull(UpdateLogic.expectedSha(sums, "missing.apk"))
    }

    @Test fun onlyTrustsOwnRepoReleaseAssets() {
        assertTrue(UpdateLogic.isTrustedAssetUrl("https://github.com/o/r/releases/download/v1/a.apk", "o/r"))
        assertFalse(UpdateLogic.isTrustedAssetUrl("https://evil.com/o/r/releases/download/v1/a.apk", "o/r"))
        assertFalse(UpdateLogic.isTrustedAssetUrl("http://github.com/o/r/releases/download/v1/a.apk", "o/r"))
        assertFalse(UpdateLogic.isTrustedAssetUrl("https://github.com/other/r/releases/download/v1/a.apk", "o/r"))
    }

    @Test fun repoValidation() {
        assertTrue(UpdateLogic.isValidRepo("my-name/YT.Audio_2"))
        assertFalse(UpdateLogic.isValidRepo(""))
        assertFalse(UpdateLogic.isValidRepo("a/b/c"))
    }
}
