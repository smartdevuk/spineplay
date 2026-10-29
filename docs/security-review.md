# Pre-publication secret review

Reviewed **29 October 2026** for the first `smartdevuk/spineplay` public beta.

## Result

No live passwords, Plex tokens, GitHub tokens, private signing keys or other credentials were detected in the publication candidate or the packaged APK. This is a bounded review of this release, not a guarantee that scanners can identify every possible secret.

The exact source candidate was selected through Git's tracked/untracked file list with the repository's ignore rules applied. **73 source/documentation/assets files**, including this report, were reviewed before the first public commit. The release APK contained **256 archive entries**. Five local build logs/settings files were also checked separately and are not published.

## Checks

- Gitleaks **8.30.1**, downloaded from its official release and verified against the published SHA-256 checksum, scanned the source candidate and decoded/archived content. The project's rules extend its default detector with Plex-specific hardcoded-token checks.
- The APK was opened as an archive and scanned separately, including printable ASCII/UTF-16 strings extracted from DEX/resources. No private keystores, credentials/settings databases, original phone attachments, audio or debug-only cover fixture were present in the package.
- Additional searches checked credential literals and personal identifiers/addresses in source and documentation. The user-specific private server address in a unit-test example was replaced with a generic private-network test address. Public sample credentials in tests are deliberate, non-functional fixtures.
- The public gallery was inspected visually. It uses fictional books, original code-drawn covers and a sample profile, with no actual account or private library details.
- The release APK's signature was verified and its signer compared with the earlier private APK. The certificate matches, and the release variant has debugging disabled. The signer fingerprint and file checksums are public; the private signing key remains outside the checkout.
- The repository history was scanned before the source push. There was no pre-existing local Git history containing older versions of the project.

## Files excluded from publication

The ignore rules exclude original chat/phone attachments, local SDK paths, tool/Gradle caches, build outputs, logs, environment files, private keys/keystores, signing settings and local databases. GitHub authorisation configuration was moved outside the project. APKs are uploaded as release assets rather than committed to source history.

The private tool cache may contain local reports and build metadata and is not intended for publication. Use Git or the explicitly reviewed file list to publish; do not upload the whole project folder as an unfiltered archive.

## Ongoing work

Scan subsequent changes and release packages again. Never paste credentials into issues or logs. A future signing-identity change needs an explicit migration plan because Android requires a matching certificate for in-place updates. Runtime security, remote-service behaviour and real-device testing remain distinct from this secret review.
