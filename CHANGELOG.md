# Changelog

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
