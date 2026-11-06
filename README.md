# YT Audio 2.3.1

One job: **YouTube → Share → YT Audio → audio plays in the background.** No player, no library.
It lives in the Share sheet and as a media notification while playing. Opening the app shows a dark home screen with the sleep timer, speed, resume, updates and settings.

- Accepts video, Shorts, Live, Music, `youtu.be` and **playlist** links (extra text in the share is ignored)
- Playlist link → plays the whole playlist; video inside a playlist → plays the playlist from that video
- Single video → after it ends, a recommended video plays automatically (3 s gap)
- A small "YT Audio" card in the notification list shows the current title with Repeat and ✕ Close buttons (works on every phone skin)
- Media player: Previous · Play/Pause · Next, with Repeat and ✕ Close on the outer sides of the expanded player
- Plays the best available **original-language** audio via [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) + Media3/ExoPlayer (audio only, no video is downloaded)
- Screen-off playback, lock-screen/notification controls, Bluetooth/headphone buttons, pauses on unplug
- Survives dropped connections and expired stream links: it re-fetches the stream and resumes where it stopped
- **Sleep timer** (15–60 min with a gentle fade-out, or end of track) and **playback speed** (0.75×–2×): tap the notification card or open the app
- **Resume** the last session (queue and position) from the home screen or by long-pressing the launcher icon
- **Next track is prepared in advance**, so the switch is instant; optional **data-saver** audio quality
- **Settings:** haptics, autoplay, quality, what sharing does while playing (replace the track, the default, or add to the queue), pause between tracks, auto-close time
- Light **haptic feedback** when playback starts and when you tap controls (can be switched off)
- Dark, modern interface in **IBM Plex Sans Arabic**, with full Arabic (RTL) support
- **In-app updates:** checks this project's GitHub Releases daily and installs new versions (details below)
- ✕ Close stops playback and removes the notification; paused for 10 min also auto-closes
- Sharing another link replaces the current one; clear error messages for network, rate-limit, bot-check and unavailable videos

## Install
1. Download `YTAudio-vX.Y.Z.apk` from **Releases** and (optionally) check it against `SHA256SUMS.txt`.
2. Open it on your phone and allow "install unknown apps". Android 8.0+ required.
3. First share: allow notifications (Android 13+) so media controls can appear. Then it's fully hands-off.

> **Upgrading from 2.0.4 or older:** the signing key changed in 2.1.0 (the old one had been published in the repository),
> so Android will refuse to update over it. Uninstall the old version once, then install 2.1.0. Future updates install normally.

## Updates (over the air)
YT Audio updates itself from this repository's **GitHub Releases**:

- Once a day it checks for a newer release. Open the app any time and tap **Check for updates** / **Update now**.
- With **Install updates automatically** on (default), it downloads over Wi-Fi when nothing is playing and installs.
  The first time, Android asks you to allow "install unknown apps" for YT Audio, and may ask to confirm each update.
- Safety: the APK must come from this repo's release assets over HTTPS, match the release's `SHA256SUMS.txt`, be this app
  with a higher version, and Android only accepts it if it is signed with the same key as the installed app.
- Privacy: it only contacts `api.github.com` / `github.com`. Turn off **Check for updates daily** to stop all checks.
- Release maintainers: attach the APK and `SHA256SUMS.txt` (the workflow does this) and publish a *non-pre-release* with a
  tag higher than the current version, e.g. `v2.2.1`. Builds made by GitHub Actions follow their own repository
  automatically; for a local build pass `-Pytaudio.repo=owner/repo` (otherwise the updater is off).
- Updating restarts the app, which stops playback.

## Build
Requires JDK 17 and the Android SDK 35.

```bash
./gradlew testDebugUnitTest      # unit tests
./gradlew assembleDebug          # quick debug build
./gradlew assembleRelease        # release build (see signing below)
```

If YouTube changes break playback, bump the `NewPipeExtractor` version in `app/build.gradle.kts`
(a weekly GitHub Action checks this for you). R8 shrinking is opt-in: `./gradlew assembleRelease -Pminify`.

### Signing (no secrets are stored in this repo)
Create your own key once:

```bash
keytool -genkeypair -v -keystore ytaudio-release.keystore -alias ytaudio \
        -keyalg RSA -keysize 4096 -validity 10000
```

* **Local release builds:** put this in `~/.gradle/gradle.properties` (not in the project):
  ```
  ytaudio.keystore=/full/path/ytaudio-release.keystore
  ytaudio.storePassword=...
  ytaudio.keyAlias=ytaudio
  ytaudio.keyPassword=...
  ```
* **GitHub Actions:** add repository secrets `KEYSTORE_BASE64` (`base64 -w0 ytaudio-release.keystore`),
  `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Without them, CI still builds, but the APK is signed with a throwaway
  debug key and named `…-UNSIGNED-TEST.apk`; it is never attached to a release.
* **Back up the keystore and its passwords.** Lose them and nobody can update the app without uninstalling.

## Google Play Protect says "harmful app blocked"?
YT Audio is open source: read the code, or build it yourself. Play Protect scans every sideloaded app and its automatic
decisions can be wrong, especially for new APKs from small projects. To install anyway, tap the small **Install anyway**
link (not "OK"). Verify your download first: its SHA-256 must match `SHA256SUMS.txt` on the release.
The permissions are each used for one thing: internet (YouTube and update checks), foreground service + notifications
(playback controls), wake lock (keep playing with the screen off), vibrate (optional haptics), network state (data saver) and
"install unknown apps" (only for the optional in-app update, which verifies a checksum and the signing key first).
Maintainers can ask Google to review a false positive: <https://support.google.com/googleplay/android-developer/contact/protectappeals>
(needs the APK's SHA-256).

## Contributing
Issues and pull requests are welcome. Run `./gradlew testDebugUnitTest` before opening a PR.
Anything touching playback should be tried on a real device (screen off, Bluetooth, network drop).

## Notes
Personal use only; not affiliated with YouTube/Google. Using YouTube outside its official apps may conflict with its Terms of Service.
License: GPL-3.0 (required by NewPipeExtractor, which is GPL-3.0). See [SECURITY.md](SECURITY.md).
The interface font, IBM Plex Sans Arabic, is © IBM Corp. under the SIL Open Font License 1.1 (`third_party/IBM-Plex-OFL.txt`).
