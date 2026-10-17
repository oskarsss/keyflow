# Development app data

During development, the app may be completely wiped after every update, including playlists, downloads, scores, progress, and settings. Data preservation and migrations are not required. Remove this line when this policy changes and app data must be preserved.

# Development

- `docs/architecture.md` describes the packages and how a playlist becomes a playable level. Screens are `MainActivity` extension functions, one file per screen; state stays in `MainActivity`.
- Verify with `./gradlew :app:testDebugUnitTest :app:lintDebug`. Unit tests mirror the main packages.
- Release builds are produced by the GitHub release workflow; see the README.
