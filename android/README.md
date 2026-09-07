# ArtMoon Android

The Android client of the ArtMoon family (moonlight-qt-based desktop client in
this repo, Android client here). Ported into this monorepo on 2026-09-07 as a
clean snapshot import — no git history was carried over.

## Provenance / lineage

- Source snapshot: `onaiaku/moonlight-android` @ `93f29137`
  ("De-stock the remaining old-Android surfaces (Nik's punch list)",
  2026-09-06 22:56 +0100) — the commit Nik verified on-device.
- That fork carries the full upstream Moonlight Android history (3,318
  commits). It remains the archive of record for lineage and for pulling
  upstream Moonlight Android changes until the cutover is confirmed stable.
- Upstream project: https://github.com/moonlight-stream/moonlight-android
- The only git submodule (`moonlight-common-c`) points directly at upstream
  `moonlight-stream/moonlight-common-c` and is unchanged by the move.

## Build

CI: `.github/workflows/build-android.yml` (JDK 17, `assembleNonRootRelease`,
APK attached to `v*` tag releases). Keystore/secrets unchanged —
`ARTMOON_KEYSTORE_B64`, `ARTMOON_KEYSTORE_PASSWORD`, `ARTMOON_KEY_ALIAS`,
`ARTMOON_KEY_PASSWORD` are repo-level secrets on this repository too.

Local: `cd android && ./gradlew assembleNonRootRelease`

## License note

The `.unofficial` applicationId suffix STAYS as long as this app contains
Moonlight code — this is the license-holder's plea and applies regardless of
which repository hosts the app.
