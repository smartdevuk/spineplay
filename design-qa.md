# Native player design review

Reviewed 24 October 2026. This covers the rendered player and chapter sheet in the first private Android build. It is not an end-to-end phone or Plex playback sign-off.

## Evidence and scale

- Selected combined reference: `docs/design/selected-player.png` (853 × 1844).
- Actual Compose player render: `docs/design/player-built.png` (780 × 1688).
- Actual Compose chapter-sheet render: `docs/design/chapters-built.png` (780 × 1688).
- Comparison layout: `docs/design/comparison.html`, displaying each image at 390px wide without changing its aspect ratio.

The native renders use Robolectric native graphics at a 390 × 844 dp test viewport and 2× density. At the same 390px logical width, the reference is 843.1px tall and the implementation is 844px tall. The reference and renders were opened and inspected together; the browser's local-file policy prevented opening the HTML comparison. A browser-rendered side-by-side comparison remains unverified. No pixel-perfect comparison is claimed.

Both player images show The Impossible Fortune, Chapter 1 · Joyce, chapter position 03:12, 1% whole-book progress, playback active, and an available undo. The one-second whole-book counter difference follows the exact millisecond fixture position. The chapter sheet uses real initial chapter markers plus explicitly synthetic remaining sections in the test fixture. Runtime chapter names come from Plex, not that fixture.

## Review findings

| Surface | Result |
| --- | --- |
| Typography | Warm serif title and clear sans-serif controls preserve the chosen hierarchy. Native fonts differ from the generated reference. Title and main labels fit at the tested default font size. |
| Spacing and geometry | All main controls, recovery message and bottom actions fit at 390 × 844. The cover is deliberately smaller than the reference to keep both progress displays and recovery controls visible. This changes the reference's proportions and needs review on the user's phone. |
| Colours and contrast | Warm near-black background, ivory text and amber emphasis carry through. The native background is flat rather than the reference's blurred cover glow. Formal accessibility contrast and large-font checks remain part of the device trial. |
| Images and icons | A debug-only cover fixture is used for deterministic rendering. Production uses Plex cover art. Standard Material icons replace the reference's illustrative icons. |
| Copy and information | Chapter-relative position, chapter time remaining, whole-book percentage and whole-book time remaining are distinct. The chapter sheet highlights the current section and displays its progress. Fine seek is included as a direct recovery action. |

Issues found and corrected during iteration: invisible default text colour, an oversized default slider thumb, title wrapping, and bottom controls outside the test viewport. The player and chapter sheet were recaptured after those changes.

Remaining visual follow-ups: cover-derived background glow, final app icon, typography refinement and physical-device review of the smaller cover. Small screens, display scaling, large fonts and TalkBack are not yet signed off.

## Behaviour and build evidence

The final `testDebugUnitTest`, `lintDebug` and `assembleDebug` run succeeded. All 12 tests passed with no failures or errors. Lint reported no errors and 14 warnings; most concern the pinned dependency baseline or standard Android integration suggestions. The debug APK's signature verified successfully.

The rendered interaction test checks a 30-second jump, undo, opening the chapter sheet, selecting the next chapter, and pause/play. Model tests cover multi-file boundaries, invalid chapter markers, millisecond rounding tails, exact-time input and server-address validation. A database test checks separate family-profile progress/history/bookmarks and persistence after reopening storage.

Real Plex authentication, Home PIN behaviour, streaming, media notifications, lock-screen/headset controls, interrupted playback, network changes and cross-device progress were not independently verified by the agent on a phone. Use `docs/device-trial.md` for that trial.

## Version 0.2 phone-feedback iteration

The user supplied screenshots of onboarding, incomplete Plex entries and a 25-hour Unleashed file. This update moves connection/profile/library changes into Settings, automatically chooses a sole server, retains authorised setup, adds multiple-library selection and a resume-first library, remembers browsing state, makes authors clickable and adds offline downloads and published chapter lookup.

New native renders are `docs/design/library-built.png` and `docs/design/author-built.png`; the player render is updated. The same 390 × 844 dp rendering configuration is used. The author-title line height and selected-chip palette were corrected after inspecting the first renders. The actual activity also removes duplicate system-bar padding identified from the user's phone screenshots. That activity inset change still needs a physical-device check; the component renders do not include system bars.

Test cover art is deliberately reused across fixture books; production resolves each Plex book's own cover. Portrait covers now fit within their square frame instead of being cropped. Incomplete Plex entries use a quiet placeholder and move below recognised books, which remain sorted by date added.

All 22 automated tests pass. Added coverage checks metadata/date ordering, old session JSON compatibility, retained browsing preferences, profile-isolated offline metadata and chapter corrections, edition mismatch/overlap rejection, preserved track timelines, author navigation and returning searches. A real Media3 download/cache test downloads two files for each of two test profiles, confirms the correct per-profile token reaches the local-only test server, reopens storage, turns off the server and reads the saved audio bytes without a network source. This verifies download/cache persistence, not Android foreground-service lifecycle or actual audio decoding.

The public UK Unleashed chapter endpoint was queried and returned 63 accurate-marked markers over 92,472,417ms, compared with 92,465,000ms shown in the user's screenshot. The chapter UI requires edition validation and explicit application, allows an opening-credit timing adjustment and never stretches timings. Applied markers still need checking against that actual recording. Unchaptered long files get explicitly labelled 15-minute navigation sections meanwhile.

Physical-device checks remain for persisted Android Keystore sessions, library selection, download notifications/background execution, interrupted download resume, airplane-mode playback, system insets, large fonts and screen readers. Shared recovery history, automated edition search, speech-based chapter detection and release preparation remain future work.

## Version 0.3 Spineplay iteration

Spineplay retains the warm player, amber controls and existing package identity. The adaptive launcher icon and native launch mark use three book spines and a play triangle. The silent intro lasts at most 900ms, exits early once content is ready, can be tapped away and skips ordinary resumes/restored activities or disabled system animations. Native Compose renders are saved as `docs/design/brand-built.png`, `details-built.png` and `bedtime-built.png`; library/player/chapter/author renders are refreshed. All use the existing 390 × 844 dp viewport. System splash and status/navigation bars still require a phone check.

The library keeps its existing layout. Reading filters share a dropdown; details, series corrections and Listen later live behind existing book entry points. The timer sheet holds remembered durations, extension and bedtime recovery. Settings holds backup management and pending-sync state. No additional top-level navigation was introduced.

The details render uses explicitly illustrative narrator, publisher and description fixtures, with the same debug cover used by other screenshots. Production details come from Plex. Missing metadata is omitted; series numbers are read only from known compatible tags or a user's local correction, and unavailable order is labelled. The bedtime render shows the exact recoverable start and its date. The brand, details, bedtime and updated library renders were visually inspected at the tested viewport.

All **45 automated tests** pass, alongside `lintDebug` and `assembleDebug`. Lint reports no errors and 30 warnings, chiefly dependency-update/KTX suggestions plus minor Compose/resource/icon guidance and the exported media-session service integration. Coverage now includes migrations from both shipped database schemas, archive checksum/profile/bounds validation, restore without replacing existing progress, persistent sync queues, in-flight acknowledgement races, profile-switch guards, conflict resolution held through the actual position checkpoint, reading flags, decimal series order, bedtime fade calculations, launch skip/timing behaviour, details controls, filters and nonzero scroll restoration. A Robolectric DocumentsProvider test exercises verified writes, three-copy retention per profile, unrelated-file preservation, readback failure and saving-state recovery. This is a local simulated provider, not a test of the user's cloud or phone storage provider.

Plex updates now use a durable outbox and compare the remote place with a stored baseline before writing. Both alternatives remain recoverable, but the Plex timeline API still lacks an atomic compare-and-write operation. Optional folder snapshots cover listening records, chapter/series corrections and reading flags; they do not provide live cross-phone merging. No backup folder has been selected on the user's device by the agent.

The final APK is a signed debug development update. Real Plex authentication/streaming, simultaneous-device progress, installed system splash, actual sound fading and Android folder-provider/background behaviour remain in the expanded `docs/device-trial.md` checklist. Release signing, store preparation, larger text and accessibility review remain outstanding.

## Version 0.3.1 playback intent

Opening a different book through a cover, title, recent card or Downloads now prepares it paused. The service also defaults an OPEN command without an explicit play flag to paused. Explicit Play/Resume actions carry their intent through Plex position choices and finished-book confirmation; a details Resume action for an already loaded book starts it without toggling an already playing book off. Switching books keeps the existing checkpoint/recovery behaviour.

The library already refreshes on fresh launch after loading cached books. Its existing listing/XML work is now explicitly dispatched off the UI thread; no additional polling or background service was introduced. Offline fallback is retained.

All **46 tests** pass, with no lint errors, and the development APK builds successfully. The added native UI regression checks that opening a different book and pressing its Resume icon route to separate actions while another book is active. The actual service/audio handoff and restored-position flows remain phone-trial checks. No visual redesign was needed. The Google Play preparation route and current release blockers are recorded in README; nothing has been submitted to Play Console.
