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

The `.unofficial` applicationId suffix was removed in September 2026, while the
app had zero public installs (one test device), so no existing installation was
orphaned by the ID change. Rationale: the suffix was upstream's mechanism for
keeping unofficial builds distinct from Moonlight's OFFICIAL application ID.
ArtMoon never uses Moonlight's application ID — its ID is
`io.github.onaiaku.artmoon` — and carries no Moonlight branding (name, icons or
logos), so the concern the suffix addressed does not apply. The code itself
remains GPL-3.0 with full upstream credit.
