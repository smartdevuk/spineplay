# Development and releases

## Build

The current baseline is JDK 17, Gradle 8.11.1, Android Gradle Plugin 8.9.2, Kotlin 2.1.20 and Android SDK/build-tools 35. Minimum Android version: 8.0 (API 26).

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

On Windows, use `gradlew.bat`. Point `ANDROID_HOME` or an untracked `local.properties` file at your SDK. Android Studio can configure the SDK location. Never commit a local SDK path, access token or signing key.

The optional Windows helpers `scripts/bootstrap-android.py` and `scripts/build-android.ps1` set up/use a project-local tool cache. The bootstrap uses official checksum-verified distributions and reuses existing SDK licence acceptances; it does not accept new licence terms. Standard Android Studio and Gradle wrapper builds are the portable entry points.

## Release signing

The first GitHub beta **preserves the existing private-test signing certificate** so current installations can update without losing local records. It is a release-variant APK with debugging disabled, signed with that existing development identity. This is not a Google Play production-signing setup. The private keystore remains outside the repository and must be backed up securely by its owner. A public certificate/fingerprint cannot sign an update.

The Windows maintainer can reproduce that beta using the already existing key:

```powershell
.\scripts\build-release.ps1 -UseExistingDevelopmentKey
```

The helper refuses to generate a missing key. A fresh clone on another computer must not silently create a replacement and present it as an update. A newly generated certificate would require a migration for existing installs; discuss that before changing the official signing identity.

For a separately authorised signing identity, `scripts/build-release.ps1 -SigningFile <private-file>` reads a JSON object with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. The default private settings location is under the current Windows user's LocalAppData `Spineplay/signing` directory. The helper passes credentials through process environment variables, restores those values when done and never places them in source. Keep the settings and keystore outside the checkout.

Other environments can provide `SPINEPLAY_KEYSTORE`, `SPINEPLAY_STORE_PASSWORD`, `SPINEPLAY_KEY_ALIAS` and `SPINEPLAY_KEY_PASSWORD`, then run:

```sh
./gradlew testDebugUnitTest lintRelease assembleRelease
```

Official downloadable assets belong on GitHub Releases, not in Git history. For each release, attach the signed APK, `SHA256SUMS.txt` and its signer certificate fingerprint. Verify the APK signature and compare the certificate with the previous official release before publishing. Increase the Android version code for every update.

## Architecture

- `Models.kt`: book/track/chapter timelines and compatible serialization.
- `PlexClient.kt`: Plex browser sign-in, Home profiles, library discovery and scoped requests.
- `Storage.kt`: encrypted credential vault and per-profile progress/history/metadata storage.
- `PlaybackService.kt`: Media3 session, checkpoints, playback, seeking and timers.
- `LibraryViewModel.kt`, `LibraryUi.kt`, `MainActivity.kt`, `FeatureUi.kt`: Compose flows and cancellable operations.
- `OfflineDownloads.kt`: resumable downloads and per-profile cache keys.
- `ChapterLookup.kt`: Audnexus edition validation and chapter timing adjustments.
- `ProgressSync.kt`: durable outbox, remote-baseline checks and conflict choices.
- `ProgressBackup.kt`: checked archives, folder retention and non-destructive restore.
- `Branding.kt`: native launch identity and short animation.

Startup uses cached books first and refreshes the selected libraries from Plex on an I/O dispatcher. It does not add continuous library polling. User actions may cancel the in-flight refresh; another launch or manual refresh tries again.

## Tests and screenshots

Tests cover timeline boundaries, profile isolation, database upgrades, chapter-edition checks, offline cache persistence, progress races, backup validation/retention and native Compose interactions. They do not replace real-device audio and foreground-service testing. Follow [device-trial.md](device-trial.md).

`PublicShowcaseTest` renders the public README gallery with fictional book/account metadata and code-drawn covers. Outputs appear in `app/build/outputs/public-screenshots`; copy reviewed images to `docs/screenshots` when the UI changes. Earlier design evidence under `docs/design` uses debug fixtures and is not a recording of a real account.

## Publication checks

1. Scan the exact Git candidate files, including decoded/archived content, for credentials. Review suspicious literals and ignored local files separately. The `.gitleaks.toml` rules extend standard Gitleaks checks with Plex-specific literals.
2. Verify screenshots contain only intended sample content and remove personal addresses or account details from documentation and fixtures.
3. Run tests and release lint, build/sign the release, inspect its packaged files and verify no keystore or credential file is embedded.
4. Scan repository history before pushing, and inspect the changed-file list. Publish only the audited commit.
5. Create a GitHub prerelease while the app remains in beta and verify its actual public APK download/checksum.

There is no configured automatic signing/upload service. No signing secrets need to be stored in GitHub for the local release process.
