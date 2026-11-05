# YT Audio 2.2.0

One job: **YouTube → Share → YT Audio → audio plays in the background.** No player, no library.
It lives in the Share sheet and as a media notification while playing (opening the app from the launcher only shows a short help page).

- Accepts video, Shorts, Live, Music, `youtu.be` and **playlist** links (extra text in the share is ignored)
- Playlist link → plays the whole playlist; video inside a playlist → plays the playlist from that video
- Single video → after it ends, a recommended video plays automatically (3 s gap)
- A small "YT Audio" card in the notification list shows the current title with Repeat and ✕ Close buttons (works on every phone skin)
- Media player: Previous · Play/Pause · Next, with Repeat and ✕ Close on the outer sides of the expanded player
- Plays the best available **original-language** audio via [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) + Media3/ExoPlayer (audio only, no video is downloaded)
- Screen-off playback, lock-screen/notification controls, Bluetooth/headphone buttons, pauses on unplug
- Survives dropped connections and expired stream links: it re-fetches the stream and resumes where it stopped
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

## Contributing
Issues and pull requests are welcome. Run `./gradlew testDebugUnitTest` before opening a PR.
Anything touching playback should be tried on a real device (screen off, Bluetooth, network drop).

## Notes
Personal use only; not affiliated with YouTube/Google. Using YouTube outside its official apps may conflict with its Terms of Service.
License: GPL-3.0 (required by NewPipeExtractor, which is GPL-3.0). See [SECURITY.md](SECURITY.md).
