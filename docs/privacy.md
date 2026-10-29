# Spineplay privacy notes

Spineplay is a client for your Plex audiobook libraries. These notes describe the current beta implementation; they are not a claim of Google Play compliance or a substitute for each external service's own privacy policy.

## Connections

- **Plex:** sign-in uses Plex's browser authorisation flow. Account/profile details and authorised tokens are used to discover servers and libraries you can access. Playback and library requests go to the selected Plex server. Listening-position updates are sent to it. Plex/server operators can see and log the requests they receive.
- **Audnexus:** only when you request published chapter lookup, Spineplay sends the public edition ID and selected region to the Audnexus API. Your Plex authorisation and audio are not sent with that request.
- **Backups:** if you choose a folder through Android, Spineplay writes listening records and book metadata there. A cloud storage provider selected in Android handles those files under its own terms.

The app has no developer-operated account or media backend, advertising SDK, analytics SDK or automatic crash-reporting SDK. Reports you choose to post on GitHub are public; remove private information first.

## On the device

Authorised tokens are encrypted with Android Keystore. The app remembers the selected profile/server/libraries; it does not retain your Plex Home PIN or ask for your Plex password. Cached covers, library metadata, progress, reading choices, chapter corrections, bookmarks and downloaded audio are stored in private app storage.

Progress backups contain metadata and listening records, not audio or sign-in credentials. They are readable JSON and should be stored somewhere appropriate for that history. Spineplay normally retains three verified automatic copies per profile when the chosen provider supports retention operations. Manually exported copies are managed by you.

## Removal

Settings provides sign-out and download removal. Sign-out removes saved authorisation but does not erase the local listening database or files already exported to a chosen folder. Remove app data through Android's app settings, or uninstall the app, to remove local app records and downloads. Export anything you want to keep first. Delete external backup files separately through your file/cloud provider. Local deletion does not remove records held by Plex or other external services.

Spineplay does not create a separate Spineplay account. Manage your Plex account through Plex. For project questions, use the [repository](https://github.com/smartdevuk/spineplay); do not post credentials or private listening data in an issue.
