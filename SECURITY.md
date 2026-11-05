# Security

- **Report a vulnerability** privately via GitHub → *Security* → *Report a vulnerability* (or open an issue without exploit details).
- The release signing key is **not** in this repository. Official APKs are built and signed by the GitHub Actions workflow
  from repository secrets; verify downloads against `SHA256SUMS.txt` on each release.
- The playback service is not exported, so other apps cannot bind to it or send it commands.
