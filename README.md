# YT Audio 2.0.4

One job: **YouTube → Share → YT Audio → audio plays in the background.** No UI, no player, no library.
It appears only in the Share sheet (and as a media notification while playing).

- Accepts video, Shorts, Live, Music, `youtu.be` and **playlist** links (extra text in the share is ignored)
- Playlist link → plays the whole playlist; video inside a playlist → plays the playlist from that video
- Single video → after it ends, a recommended video plays automatically (3 s gap)
- A small "YT Audio" card in the notification list shows the current title with Repeat and ✕ Close buttons (works on every phone skin)
- Media player: Previous · Play/Pause · Next, with Repeat and ✕ Close on the outer sides of the expanded player
- Plays best available audio via [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) + Media3/ExoPlayer
- Screen-off playback, lock-screen/notification controls, Bluetooth/headphone buttons, pauses on unplug
- ✕ Close button in the notification stops playback and removes it; paused for 10 min also auto-closes
- Sharing another link replaces the current one; network/unavailable-video errors show a short toast

## Install
1. Download `YTAudio.apk` from **Releases** (or the latest **Actions** run → *Artifacts*).
2. Open it on your phone and allow "install unknown apps". Android 8.0+ required.
3. First share: allow notifications (Android 13+) so media controls can appear. Then it's fully hands-off.

## Build
Push to GitHub: the included workflow builds the APK. Locally: `gradle assembleRelease` (JDK 17, Android SDK 35).
If YouTube changes break playback, bump the `NewPipeExtractor` version (currently v0.26.5) in `app/build.gradle.kts`.

## Updating
Every build is signed with the same key (`app/ytaudio.keystore`), so new APKs install over old ones.
If an older build was signed differently, uninstall it once first. Check the installed version in
Settings → Apps → YT Audio (the app has no launcher icon).

## Notes
Personal use only; not affiliated with YouTube/Google. Using YouTube outside its official apps may conflict with its Terms of Service.
License: GPL-3.0 (required by NewPipeExtractor, which is GPL-3.0).
