# ArtMoon Android — UI Behaviour Spec

Design source: approved renders `/home/rias/artmoon-android-preview.html` (hosts),
`/home/rias/artmoon-android-app-picker.html` (app picker) and
`/home/rias/artmoon-android-settings.html` (settings, all 9 tabs, interactive).
Tokens from desktop `theme.cpp`.
Status: DESIGN APPROVED 2026-09-05 (hosts screen, app picker, settings incl. ArtLight tab).
This file covers behaviour.

## 1. Orientation & form factor

- **Phones: portrait-locked.** `screenOrientation="portrait"` on PcView, AppView, Help,
  AddComputerManually, StreamSettings, etc. Landscape layouts apply only on TV/large-screen
  devices (Shield), which are natively landscape — no rotation handling needed.
- TV-banner / leanback metadata: reuse upstream's existing declarations.

## 2. Hosts screen (hero card)

Data shown per host card: per-host background photo (device gallery, picked via Photo Picker;
fallback = accent-well glyph), ONLINE / AUTHORIZED badge row, host name (big, shadowed),
"Ready to stream" / "Offline" subtitle, spec chip row (res/FPS/bitrate/codec/audio — from the
host's active stream config when online), HOST LINK big-number (network speed class), LAST SESSION
badge + duration. RTT / HOST LAT. / DROPS bars: **hidden unless a session's live telemetry is
available** (they only mean something mid-stream); the LAST SESSION block shows instead.

Input handling:
- **Touch:** tap anywhere on the hero card = open that host's app picker. Tap the badge/pill row
  = nothing (not interactive). Long-press host card = context menu (View details / Forget host /
  Change host photo / Test connection).
- **Gamepad (Shield):** D-pad moves a focus ring between host cards, then the rail buttons
  (Settings, Help, Add host). Focus ring = the blue glow border (same as selected row in render).
  A-button on focused card = open. Menu button = Settings. Back = exit confirm.
- **Keyboard:** arrows navigate; Enter opens; shortcut chips active as on desktop
  (P = shutdown host with confirm dialog, S = settings, Esc = exit).

Header: ArtMoon wordmark + Gamestream subtitle, clock/date top-right (hidden on phones in
portrait — wasted space; the Android status bar already has a clock).

## 3. App picker (master-detail)

Left list rows: app icon (host `appasset` art via existing CachedAppAssetLoader chain —
NetworkAssetLoader → DiskAssetLoader → memory cache; fallback = stylised glyph card), bold name,
platform subtext. STREAMING chip on the running app's row. Selected row = blue glow.

Right detail panel (TV/landscape): large cover card (same appasset art), big title,
"running now" in green when applicable, action row.

Input handling:
- **Touch:** tap row = select (updates detail) — second tap on already-selected row = play.
  On phones (portrait) there is no detail panel: **single tap on a row = play directly**,
  long-press = context menu (View details / Quit app / Resume). This matches "tap to stream"
  muscle memory from every launcher.
- **Gamepad:** D-pad up/down through rows; moving focus updates the detail panel live (desktop
  behaviour). A = play focused app. Y = quit/stop running app (confirm dialog). Menu = Settings.
  Back = return to hosts screen.
- **Keyboard:** arrows navigate, Enter = play, G = resume running session, S = stop running
  session (desktop's bindings, shown in the keycaps), Esc = back to hosts.

Keycap labels adapt to input mode: keyboard → letter keycaps (Enter/G/S/Esc); gamepad → controller
glyphs (A/Y/Menu); touch → no keycaps, plain labels. Detection: InputDevice sources (SOURCE_CLASS_
BUTTON + gamepad vs SOURCE_CLASS_POINTER), same approach as desktop's input probing.

## 4. Streaming-related behaviour

- Entering a stream: reuse upstream Game activity unchanged (upstream streaming code stays
  upstream — locked decision). Our UI work stops at "play was tapped".
- If a session is already running for a host, the hosts hero card shows the STREAMING chip and
  its action becomes "Resume" (primary).
- Telemetry bars on the hero card read from the same client telemetry the desktop consumes; when
  the client doesn't have live numbers, bars hide (never show fake data).

## 5. Art & assets

- Per-host hero photo: Android Photo Picker (no storage permission needed), stored per-host in
  app-private storage. Fallback = accent-well glyph card.
- App artwork: host applist `appasset` (AssetType=2, AssetIdx=0) via NvHTTP — existing pipeline,
  no reinvention. Fallback glyph card per app (monitor for Desktop, generic pad otherwise).
- Launcher icon: ArtMoon adaptive icon (already done in rebrand phase).

## 8. Settings screen (all categories + ArtLight tab)

Render: `/home/rias/artmoon-android-settings.html` (interactive — click through tabs).

Structure: settings is NOT the desktop's tab set — it keeps Android's real
`preferences.xml` PreferenceCategories, restyled into ArtMoon grammar:
scrollable category chip rail → card sections; chips for enumerable choices,
sliders for bitrate/deadzone/opacity/rumble, ArtMoon toggles for switches.

The 8 real categories (from preferences.xml, ported to the grammar):
- **Basic** — resolution chips, FPS chips, bitrate slider, frame-pacing segmented
  (latency/balanced/smoothness), stretch toggle.
- **Audio** — quality chips (stereo low/high, 5.1, 7.1), audio effects toggle.
- **Gamepad** — deadzone slider, multi-controller, Xbox USB driver, rumble
  fallback toggle + strength slider, flip face buttons, touchpad-as-mouse,
  motion sensors.
- **Input** — touchscreen trackpad, mouse nav buttons, absolute mouse.
- **On-screen controls** — show OSC, vibrate, L3/R3-only, guide button,
  opacity slider, layout chips (default/legacy/custom) + reset.
- **Host** — SOPS toggle, host audio toggle.
- **UI** — language chips, PiP toggle, small icon mode.
- **Advanced** — video format chips (H.264/HEVC/AV1), HDR, full range,
  unlock FPS, reduce refresh rate, perf overlay, disable warnings, latency toast.

Dropped as desktop-only (do NOT port): display mode (fullscreen/borderless/
windowed — Android streams fullscreen), V-Sync toggle, match refresh rate
(Android's "reduce refresh rate" pref already covers the close analogue).

**9th tab: ARTLIGHT** (new — no Moonlight ancestor; mirrors desktop's ArtLight tab):
- Gold-moon hero card, "Host integration by onaiaku & Rias".
- Status card: server name + IP, ArtLight Server version vs latest release
  (up-to-date state via GitHub releases check), library sync status (app count
  from applist), Gamestream authorized state.
- Buttons: **Changelogs** (changelog view), **GitHub releases**.
  NO "Open ArtLight Control" button — CORRECTED 2026-09-05: ArtLight Control is a
  host-side Windows desktop app (StreamTweak fork, C#/named-pipes), NOT a web
  surface. There is nothing for a phone to deep-link to. Control's real user-facing
  surface (PIN pairing, AUTHORIZED badge, stream stats) is ported in §9 via
  HostAuthManager + PcView pairing dialog + HostMetricsPoller.
- ArtMoon self-update card: installed vs latest, "Update now" only when older,
  GitHub releases link.

## 9. ArtLight feature port — under the hood (StreamTweakBridge)

The desktop client talks to ArtLight/StreamTweak on the host over plain TCP
port 47998 (`app/StreamTweakBridge.h` in /projects/ArtMoon) — newline-terminated
JSON, per-request socket + watchdog. **Portable to Android as a plain Java
TCP client; no native code needed.** Commands to port and the surfaces they feed:

| Command | Desktop source | Android surface |
|---|---|---|
| `STATS` | HostMetricsPoller → live GPU %, encoder %, temp, VRAM, CPU, net TX | Live telemetry on host hero card + during-stream overlay (feeds the RTT/LAT/DROPS bars instead of hiding them) |
| `GAMESTATE` | LaunchGate → launch-curtain phases ("has the game's window appeared?") | Stream launch: "Launching…" phases on the app detail / hero card instead of a dumb spinner |
| `NETINFO` / `SETSPEED` | LinkMatcher → NIC-speed matching before stream | Pre-stream check; HOST LINK big-number on hero card gets its real value |
| `APPSTORES` | Store map `{"Cyberpunk 2077":"Steam"}` | Platform/store subtext + badge chips on app rows in the picker |
| `UPDATESTATE` | "host has updates waiting" prompt | ArtLight tab status card + gentle banner on host card |
| `SHUTDOWN` / `SHUTDOWN_UPDATE` | Power dialog, AUTH1-signed | Power action on host context menu (confirm dialog), signature scheme ported to Java |
| `STATUS` | NIC speed query | Backs HOST LINK display when STATS unavailable |

**New surfaces with no Moonlight ancestor (build, don't style):**
- **ArtLight Control pairing popup** — Moonlight Android only knows the PIN pairing
  dialog for Sunshine. ArtLight hosts pair the client as an *approved client*
  (AUTH1 signature approval, see StreamTweakBridge header). Android needs its own
  pairing/approval flow surfaced after host add: detect an ArtLight host (port
  47998 reachable), show the Control-pairing dialog, store approval state per host.
- **Approved-client state** — shown on the host card / ArtLight tab; destructive
  commands (SHUTDOWN) are gated on it, exactly like desktop.
- **Upstream boundary (locked):** moonlight-common-c / streaming core stays
  upstream. All ArtLight features live in a separate Java bridge class
  (e.g. `ArtLightBridge`) + UI, never touching the stream path.

## 10. Build discipline (LOCKED by Nik)

Design-first: iterate in HTML renders; **one commit → one build** when design is signed off.
Local verification (XML parse + resource-reference audit) before every push. No CI for
design iterations.

## 11. Known repo hazards before the build

- Working tree has `***` literal tokens in committed layout files (killed CI runs 33958665243 &
  33958814653). Must be cleaned and re-audited BEFORE the single build.
- `GenericGridAdapter` id contract (grid_image/grid_overlay/grid_text/grid_spinner) must hold or
  be replaced cleanly — new list-row layouts change geometry, adapters get patched to match.
