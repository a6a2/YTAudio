# Changelog

## 2.3.2
- The "Last problem" card now uses Android's **share sheet** instead of writing to the clipboard. Clipboard writes are also a
  pattern used by malware, and this was the only such API in the 2.2.0 -> 2.3.x difference, so removing it may help Google Play
  Protect's automatic scan. (Nothing else changes.)
- Correction: earlier notes claimed `ACCESS_NETWORK_STATE` was missing in 2.2.0. It was not: the Media3 library already
  contributes it. The explicit declaration stays, harmlessly.

## 2.3.1
- **Fewer scary permissions.** Removed `RECEIVE_BOOT_COMPLETED`. Google Play Protect sometimes flags new sideloaded builds that
  combine "downloader" style permissions; boot persistence was the one we didn't really need. The daily update check is simply
  re-armed the next time you use the app (every share does this), so nothing else changes.
- Added a Play Protect FAQ to the README.

## 2.3.0
### New
- **Dark, modern home screen** in IBM Plex Sans Arabic (cards, pill buttons, adaptive dark launcher icon).
- **Haptics:** a light tick on every tap and a click when playback starts (not on every automatic track change). Switch off in Settings.
- **Sleep timer** (15/30/45/60 min with a 10-second fade-out, or end of track) and **playback speed**; tap the notification card to open them.
- **Resume last session** (queue + position) after the idle timer, sleep timer or system closes the app; also a launcher long-press shortcut.
- **Next track is prepared during the last 30 s** so switching is near-instant.
- **Settings screen:** haptics, autoplay on/off, audio quality (best / data saver on mobile data / always saver), pause between tracks (0/1/3 s), auto-close time (5 min–never), and what sharing does while playing.
- Sharing a link while something plays **still replaces the current track** (default). "Add to the queue" is an opt-in setting.
- **Last problem** card with a Copy button (links and tokens are stripped) for bug reports.

### Under the hood
- The queue rules moved out of the service into a plain, unit-tested `QueueManager`; the main thread never waits on a playlist download.
- New tests: queue navigation, rapid Next taps, paging safety, add-to-queue, session save/restore, quality picking, error-report privacy.

## 2.2.0
- **In-app (over-the-air) updates** from GitHub Releases: daily background check, "Update now" button, optional automatic
  install (Wi-Fi only, never during playback). Downloads are verified against `SHA256SUMS.txt`, must be this app with a higher
  version, and Android enforces the signing key. Can be switched off.
- Help page shows the update status; new notification when an update is available or needs a confirmation tap.
- New permissions: `REQUEST_INSTALL_PACKAGES` (install the update) and `RECEIVE_BOOT_COMPLETED` (keep the daily check after reboot).
- Unit tests for version comparison, release parsing, checksum lookup and download-URL trust.

## 2.1.0
### Security
- Signing key and passwords removed from the repository; signing now comes from CI secrets / local gradle properties
  (**one-time uninstall needed when upgrading from ≤ 2.0.4**).
- Playback service is no longer exported.
- JitPack restricted to `com.github.*` groups.
- CI: read-only token by default, release job only gets write access, wrapper validation, APK checksums,
  unsigned test builds can't be attached to releases.

### Fixes
- Tapping Next/Previous quickly now advances one track per tap (it used to land on the same track).
- A network drop or expired stream link no longer closes the app: it retries up to 3 times and resumes at the same position.
- Rate-limit (HTTP 429) and bot-check errors stop immediately with their own message instead of hammering YouTube by skipping tracks.
- Stale lookups are cancelled, so rapid taps no longer queue up behind old network requests; paging can no longer loop forever.
- Share text that arrives as a `CharSequence` is now read correctly; channel/handle links show "not supported" instead of "not a YouTube link".
- Link matching is case-insensitive.

### Improvements
- Prefers the original audio track on multi-language videos; audio only (video tracks disabled).
- Uses the phone's language and region for YouTube requests.
- Larger buffer for weak networks; Repeat-one replays from memory instead of re-downloading.
- Notification card is persistent; proper adaptive/themed icon; optional launcher help page; RTL support.
- Build: Gradle wrapper, JVM unit tests for link parsing, weekly extractor health check, Dependabot, opt-in R8 rules.
- Removed unused strings and deprecated `values()` calls.
