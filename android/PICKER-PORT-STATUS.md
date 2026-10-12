# Where the picker actually is — 12 Oct 2026 (evening update)

Nik: *"there are that many little ui things that are either missing or broken (and i dont know which
of those for each thing) that i have no idea where we are or what we are doing."*

Every item is one of four honest verdicts, and each says what you'd **see on the phone** so you can
check it yourself instead of taking my word for it.

- **PRESENT** — there, and it follows the desktop.
- **MINE** — there, but it's my version, not the desktop's.
- **MISSING** — the desktop has it, Android doesn't.
- **UNKNOWN** — can't confirm without your eyes. Counted, not hidden.

Reference: `app/gui/AppsScreen.qml` (1897 lines, 19 sections) and `app/gui/HostStage.qml`.
A verdict says *ported* only where I can point at the desktop line.

---

## 0. What changed tonight

**There were two picker screens.** `AppView.java` (1490 lines) was a second, complete picker — still
in the manifest, still the target of every home-screen shortcut, and it was where the tab counts were
drawn (`AppView:806`) and where a newly-paired host got its shortcut (`AppView:134`). Its landscape
layouts (`layout-land/app_grid_view*.xml`) were the old picker's own files carrying this page's ids.
Tonight's commits `8a387ca1`, `4aee73f0`, `29beee37` — "the picker's row, ported whole", "the tabs —
count in the label", "the list draws" — all touched those old layouts.

Nik: *"ok we will keep that then."* So `AppsPageView` survives. The other one is gone.

**Moved across first, so nothing was lost with it:**
- the tab counts, printed exactly as the old screen printed them — `ALL  24` in the label.
- the home-screen shortcut for this PC, made and bumped at the same point in the bind thread.
- the two corrections the desktop makes when the counts land (`AppsScreen.qml:437-440`): a host
  holding a Remote Monitor is APPS, and an empty tab moves to APPS when APPS has entries.
- a saved tab counts as chosen by hand, so `_openingTabFor`'s note holds here too.

**Deleted:** `AppView.java`, `grid/AppGridAdapter.java`, `activity_app_view.xml`,
`app_grid_item(.small).xml`, `app_grid_section.xml`, `app_grid_view(.small).xml` — portrait *and*
landscape — and the manifest entry.

**Verified:** `./gradlew assemble` — all four flavours, BUILD SUCCESSFUL. Installed on the Fold. Page
started with an empty crash buffer. The screen was locked, so the counts themselves are not seen
with my own eyes yet.

**Corrected in this pass:** I wrote earlier that Android had no held-Remote-Monitor state. Wrong —
`AppLibrary.Page.remoteMonitorActive` exists (`AppLibrary:107`).

## 1. The structure, in the desktop's own § order

| Desktop § | What you'd see | Verdict |
|---|---|---|
| §34 props / host identity | the page knows which host it's on | **PRESENT** — intent uuid + ComputerManager, not AppShell |
| §88 scale | everything the same size as Home | **PRESENT** — `AmScale` |
| §95 the two columns | list left, spotlight right, blurred cover behind | **PRESENT** — `apps_page.xml` 0.52/0.48, `showAmbient` → `AppAmbientArtwork` |
| §111 focus zones / boundaries | one zone, list focus, pad never stops on a button | **MISSING** — only lit/quiet rows exist |
| §231 pad glyphs | real pad artwork by button *position* (Switch swaps A/B; Select is artwork, not a word) | **PRESENT** — the picker's prompts now register with the shared `PromptBar` (commit `08c5b547`); Home's footer already did. Open: Home's host-strip arrows still print PgUp/PgDn and listen on LB/RB, where the desktop uses **LT/RT** (`HomeScreen.qml:1853`) — that pair is Nik's call |
| §239 the game in the spotlight | cover, name, state, store | **PRESENT** — `updateHero` |
| §250 play time in the spotlight | hours + sessions for the focused game | **PRESENT** — `PlaytimeManager`; was frozen on row 0 until tonight |
| §285 pinned | pinned heading + mark on the row | **PRESENT**, but reached by long-press menu, not Start/P |
| §300 GAMES / APPS tabs | three tabs, ring wraps, last choice remembered, **counts**, and the two corrections | **PRESENT** (tonight) |
| §367 GAMES / APPS by hand | move one entry between the tabs | **PRESENT** — via the menu; desktop uses M / right-stick |
| §446 host notices | "that finished" vs "launch again within 60s to confirm" | **MISSING** |
| §916 the configuration line | host link speed + your stream settings | **PRESENT** — `fillConfigLine` + `addCfgChip` |
| §1047 the game (launch screen) | the segue into a launch | **NOT PORTED** — Moonlight's own launch UI |
| §1095 action row | the verb's pill, per-game settings, Stop | **PARTIAL** — launch + Stop only, and both now carry real artwork; **no per-game settings entry** (SELECT/G, `AppsScreen.qml:1117`) |
| §1331 Continue | the last thing played sits on top | **PRESENT** — `SECTION_CONTINUE`, row 0 only |
| §1537 thumbnail | box art in the row | **PRESENT** — after the prefetch; was losing a race on every load |
| §1571 title + store | name, store in words under it | **PRESENT** |
| §1572 pinned mark | the pin on the row | **PRESENT** |
| §1638 the running session | the lit pill on the entry that's streaming | **PRESENT** |
| §1782 empty state | what it says when there's nothing | **PRESENT** — after I got it wrong twice |
| §1799 dialogs | per-game settings, pin prompt, launch confirm | **PARTIAL** — quit confirm only |

## 2. The little things — your actual complaint

| Thing | Desktop | On Android |
|---|---|---|
| Row selection indicator | lit vs quiet row | **PRESENT** — `am_row_lit` / `am_row_quiet` |
| What happens to the cursor when you finger-scroll | the cursor is the selection, full stop | **MINE** — I settle it on the middle row. The desktop has no such rule and I described it as "like the desktop" |
| Tab counts | `_countFor()` drives which tab *opens* | **PRESENT** (tonight) — in the label, as the old screen printed it |
| Store logos | seven SVGs in `app/res/` (`store_steam/gog/epic/ea/ubisoft/battlenet/xbox.svg`), drawn by `GameFacts.qml:63` | **MISSING** app-wide. The picker says the store in words on the desktop too, but the artwork exists and I told you it didn't |
| Foot key hints | swap with the input in your hand | **MINE** — keyboard letters only |
| Ambient backdrop | focused cover, blurred | **PRESENT** |
| Host chips (link / online / profile) | yes | **PRESENT** — `paintChips`; profile + tailscale chips are hidden today |
| "some may be hidden on the host" | the desktop never blames the host for a client bug | **PRESENT** — fixed; it was mine |
| Hidden apps / ShowHiddenApps | the desktop has no such feature | **EXTRA** — Android-only, inherited from the old picker. Kept, not deleted: that's your call |

## 3. What I can't tell you without your eyes

- Whether the covers actually appear now (the fetch needs the host and the list in front of you).
- Whether the bottom prompts sit right in landscape.
- How any of it *feels* — deadzones, scroll, the ring between zones.
- Which of the **MINE** items you'd accept as-is rather than a straight port.

## 4. Next, in the desktop's order

§231 pad glyphs — the most visible **MINE**, every button on that page wearing a letter — then §111
focus, then §446 notices, then §1095's action row. Nothing is called ported until you've marked it.
