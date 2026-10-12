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
| §111 focus zones / boundaries | one zone, list focus, pad never stops on a button | **PORTED, unproven** — the ring starts on the list, nothing on that page but the list is focusable, and a d-pad press at an edge is swallowed instead of being thrown to the framework (`AppsPageView:1409-1423`: "an edge is then just an edge"). No pad has driven the page since the rewrite, so it is not marked |
| §231 pad glyphs | real pad artwork by button *position* (Switch swaps A/B; Select is artwork, not a word) | **PRESENT** — the picker's prompts now register with the shared `PromptBar` (commit `08c5b547`); Home's footer already did. Open: Home's host-strip arrows still print PgUp/PgDn and listen on LB/RB, where the desktop uses **LT/RT** (`HomeScreen.qml:1853`) — that pair is Nik's call |
| §239 the game in the spotlight | cover, name, state, store | **PRESENT** — `updateHero` |
| §250 play time in the spotlight | hours + sessions for the focused game | **PRESENT** — `PlaytimeManager`; was frozen on row 0 until tonight |
| §285 pinned | pinned heading + mark on the row | **PRESENT** — Start/P toggles it, the prompt flips Pin/Unpin with the row, and the mark is the desktop's pin (AppsScreen.qml:1584). Nik, 12 Oct 2026: "yep pins work" — and, on the first cut, "you used the wrong icon, you used a gold start when the desktop uses a actual pin symble", now fixed |
| §300 GAMES / APPS tabs | three tabs, ring wraps, last choice remembered, **counts**, and the two corrections | **PRESENT** (tonight) |
| §367 GAMES / APPS by hand | move one entry between the tabs | **PRESENT** — via the menu; desktop uses M / right-stick |
| §446 host notices | "that finished" vs "launch again within 60s to confirm" | **PORTED 12 Oct 2026**, awaiting his mark — see `references/host-notices.md` |
| §916 the configuration line | host link speed + your stream settings | **PRESENT** — `fillConfigLine` + `addCfgChip` |
| §1047 the game (launch screen) | the segue into a launch | **NOT PORTED** — Moonlight's own launch UI |
| §1095 action row | the verb's pill, per-game settings, Stop | **PRESENT and marked** — launch + Stop, both with real artwork, and the half-step Nik asked for on the main screen's row is in (he confirmed it himself, 12 Oct 2026: "this can be ticked off its already done"). Per-game settings is **not** counted as missing: Android does not use the desktop's settings page (Nik, 12 Oct 2026), so that entry would be Android's own surface to build, not this page's port |
| §1331 Continue | the last thing played sits on top | **PRESENT** — `SECTION_CONTINUE`, row 0 only |
| §1537 thumbnail | box art in the row | **PRESENT** — after the prefetch; was losing a race on every load |
| §1571 title + store | name, store in words under it | **PRESENT** |
| §1572 pinned mark | the pin on the row | **PRESENT** — `qrc:/res/pin.svg` path for path in `am_pin.xml`, 18dp, tinted from the row's text colour with the desktop's 0.95/0.6 opacity. It was a gold star (a TextView holding U+2B50) until Nik caught it, 12 Oct 2026 |
| §1638 the running session | the lit pill on the entry that's streaming | **PRESENT** |
| §1782 empty state | what it says when there's nothing | **PRESENT** — after I got it wrong twice |
| §1799 dialogs | per-game settings, pin prompt, launch confirm | **PARTIAL** — quit confirm only; the per-game settings dialog is not counted (see above) |

## 2. The little things — your actual complaint

| Thing | Desktop | On Android |
|---|---|---|
| Row selection indicator | lit vs quiet row | **PRESENT** — `am_row_lit` / `am_row_quiet` |
| What happens to the cursor when you finger-scroll | the cursor is the selection, full stop | **MINE** — I settle it on the middle row. The desktop has no such rule and I described it as "like the desktop" |
| Tab counts | `_countFor()` drives which tab *opens* | **PRESENT** (tonight) — in the label, as the old screen printed it |
| Store logos | seven SVGs in `app/res/` (`store_steam/gog/epic/ea/ubisoft/battlenet/xbox.svg`), drawn by `GameFacts.qml:63` | **PRESENT** (`2b793eda`) — seven marks, path for path from the desktop's own SVGs, tinted with the line's colour. Nik, 12 Oct 2026: "the store logos are working too". The **MISSING** here was mine twice over: the artwork existed and I told you it didn't |
| Foot key hints | swap with the input in your hand | **MINE** — keyboard letters only |
| Ambient backdrop | focused cover, blurred | **PRESENT** |
| Host chips (link / online / profile) | yes | **PRESENT** — `paintChips`; profile + tailscale chips are hidden today |
| "some may be hidden on the host" | the desktop never blames the host for a client bug | **PRESENT** — fixed; it was mine |
| Hidden apps / ShowHiddenApps | the desktop has no such feature | **GONE** — it was an Android-only leftover from the old picker, and Nik scrapped it (12 Oct 2026, `5029ed6c`). Removed from the app, not just from this list: the menu entry, the preference file, the intent extra, `Row.hidden`, the faint alpha, the host-blaming empty-state lines, and the string in all 26 locales |

## 3. What I can't tell you without your eyes

- ~~Whether the covers actually appear now~~ — answered 12 Oct 2026. The main screen's last-played cover: "confirmed its showing now" (`b3b570de`). The picker's own row covers and the spotlight preview: still unconfirmed.
- Whether the bottom prompts sit right in landscape.
- How any of it *feels* — deadzones, scroll, the ring between zones.
- Which of the **MINE** items you'd accept as-is rather than a straight port.

## 4. Next, in the desktop's order

§231 pad glyphs — the most visible **MINE**, every button on that page wearing a letter — then §111
focus, then §446 notices, then §1095's action row. Nothing is called ported until you've marked it.

---

## 12 Oct 2026, 20:56 — the three things Nik found with the pad in his hand, and where each one is now

He had the controller, walked the page himself, and reported three faults. All three were real.
Everything below is measured off his Fold (density 2.625, screen 1972x1248), not read off the code.

**1. The action pills were being sliced when the playtime line shows. — FIXED (`e6ef07b4`)**

Not a button size. A space problem, in his words, and the numbers agree:

| | stack needs | column has | pills drawn | keycaps |
|---|---|---|---|---|
| an entry **with** playtime | 892px | 853px | 40px (should be 79) | 63 x **32** — glyph stretched into a half-disc |
| an entry **without** | 834px | 853px | 79px | 63 x **63** — correct |

LinearLayout took the 39px shortfall out of its last child, the action row, and a compound
drawable stretches to whatever box it is given — so both glyphs came out shaved top and bottom.
`am_hero_cover` is weighted now, which is the desktop's own rule for it
(`GameFacts.qml:147`), so the picture gives up the room and the buttons never can again.

**2. Nothing on this page owned a pad button. — FIXED (`e6ef07b4`)**

X did nothing at all (36 presses in one sitting, with the prompt two inches away promising it
would stop the session); A worked only by accident, as Android's activation key on whatever held
focus. Now: X stops the running session, A launches what the spotlight shows, B is back, Y is
Settings, Select opens the per-game menu. The stray DEL this pad sends with every X press is
swallowed with it. The d-pad is deliberately not owned — the list already walks it, and the
spotlight follows (verified by moving the cursor with a d-pad keyevent and watching the hero change).

**3. The spotlight's cover has never been filled. — FIXED (`e6ef07b4`)**

`populateCover` lived on the old app grid's adapter and was deleted with the old screen. The rows
kept their own call; the hero did not. The right-hand preview has therefore been the bare
`am_detail_cover_bg` rectangle — the big blue box — since the old picker came out. The rows loading
real artwork is what proves the host's side was never the problem. Called from `updateHero()` now.

**Still open, and still his to mark:** whether the preview and the strip *look* right with the pad
in his hand, and whether a game with no artwork on the host should keep the blue frame (it does
139|today) or be hidden the way `PcGridAdapter` hides a source-less cover.

---

## 12 Oct 2026, 21:20 — the remaining list, straight after Nik asked

He asked the right question: *"whats left to implement from porting (because a lot of this shit
wouldnt be a thing if things were ported properly)"*. He is right about the cause, and the honest
answer is a list, not a promise. Two items he has just found are **still broken** — they are first
because they are live, not because they are the biggest.

### Both live faults, one cause — found in the code, 12 Oct 2026, late

**A section heading is a list ITEM on this page. On the desktop it is not** — the desktop's sections
belong to the ListView (`section.property` / `section.delegate`), so its `currentIndex` only ever
addresses a real row and its spotlight can never be handed a heading. Everything below follows from
that one divergence:

- **The artwork that only appears after a scroll.** `selectedRow()` fell back to position 0 when
  nothing was focused, position 0 is usually a heading, `rowAt(heading)` is null, and `updateHero()`
  answers a null row by clearing the cover. One scroll moved the cursor onto a real row and the art
  appeared. The fix is the desktop's rule, one item-type check wide: the cursor only ever addresses
  rows, so `selectedRow()` walks down to the first real entry rather than answering null.
- **The first entry selected only sometimes.** The landing was retried on a timer (24 × 120 ms) for
  the wait before the host answers, and gave up. A list that arrived after the retries ran out left
  the page with nothing selected. The rows now land the cursor the moment they are in (`fillPage()`),
  which is the event that actually matters — not a clock.
- **And the d-pad stopped dead on a heading.** `moveCursor()` stepped one index, `selectRow()` rightly
  refused the heading, and the list's own selection had already gone there — so the next press tried
  the same refusal. It now walks past headings in the pressed direction and clamps at the ends.

None of the three is proven on the phone yet: that is Nik's eyes, and the fixes are on the build. My
two earlier attempts at (1) were both reasoned at the loader; the loader was never the fault.

### Missing in the picker (desktop section → what Android doesn't have)

⚠️ **Corrected 12 Oct 2026, after Nik said so** — and one thing in this row was wrong when first written: the picker's app-list poller IS started (`AppsPageView.java:1074-1077`, with its stop in `onDestroy`), so that was never the reason: **looked at properly and corrected the same night, rather than left standing.** "Last played" and "Pinned" were called PRESENT off the
constants in the code (`SECTION_CONTINUE`, `SECTION_PINNED`, `PinnedRole`) instead of off a proper look
at what the desktop actually does with them. Both are below now, with the desktop's own lines and what
is really true on the phone.

| Desktop § | Missing |
|---|---|
| **Main screen: the host strip's pair** | `HomeScreen.qml:1853, 1949` (arrows print **LT/RT**, key **PgUp/PgDn**), `sdlgamepadkeynavigation.cpp:525-554` (the trigger axes, edge-detected with hysteresis) | **LB/RB on the phone, deliberately** — Nik, 12 Oct 2026: *"they sould be LB and RB and they need wiring up"*, then *"yep confirmed working"*. The desktop's pair cannot fire on Android: a trigger arrives as `AXIS_LTRIGGER`/`AXIS_RTRIGGER` motion events, never as `KEYCODE_BUTTON_L2/R2`. LB/RB page the stage, PgUp/PgDn page it from a keyboard (and were handled nowhere before), the triggers stay wired as the desktop's own second door, and the caps follow the input mode. Written into `PromptBar.buttonKeyFor` so it is not "fixed" back. Commit `b661f46c` |

| **Last played** — `HostStage.qml:199-260` (the block, `lastPlayedFor()`), `appmodel.cpp:364-380` (the section rule) | the rule is ported faithfully (`AppLibrary.java:196-200`: only row 0 can be Continue; APPS excluded in `lastPlayedForSort`) and the block is ported (`_panelPlaceholder` / `lastPlayedHint`). **But nothing lights the heading on the phone:** it is fed by this client's own last-game pointer (`PlaytimeManager.lastPlayedOn` → `SER_LASTGAME`), which only a **banked** session writes, and `endSession` refuses anything under 60 seconds (`kMinSessionSeconds`). The watcher's half (`ObservedPlaytime`) could not name a running game when this was written; it can since `5f4551e3`, where the poll falls back to the host's cached app list for the name. **Nik sees it now.** 12 Oct 2026: "Last played confirmed working!" — and on the cover, which showed inconsistently, "confirmed its showing now". The heading is lit by a banked session (over 60 s, `kMinSessionSeconds`); the block's blank was a hidden `ImageView` and its delay was an app list the main screen never had — both fixed in `b3b570de`. |
| **Pinned** — `AppsScreen.qml:285-291`, `:541-557` (toggle), `:1382` (the PINNED caption), `:1572-1578` (the mark on the row), `AppShell.qml:533-542` (the pad prompt flipping Pin/Unpin) | **PRESENT and marked.** The model was ported rule for rule, and the door — Start/P, and the prompt that follows the row — is now ported too (`AppsPageView.togglePinFocused` / `updatePinPrompt`). It refuses an app, re-snaps by app id after the call because pinning moves the row, and is not drawn at all on the APPS tab, which is the desktop's own rule. Nik, 12 Oct 2026: "yep pins work". |
| §446 host notices | "that finished" vs "launch again within 60s to confirm" — **PORTED 12 Oct 2026, awaiting his mark.** Written up in `references/host-notices.md`. Not a new mechanism: Android already made the launch in Java and threw the 410's own sentence away as a generic error toast; now the sentence is shown, and the "again within" case is asked as a Yes/No and answered by launching the same entry again. |
| §1095 action row | **nothing** — launch + Stop, and per-game settings is not a port item (see the note below) |
| §1799 dialogs | only the quit confirm exists — no pin prompt, no launch confirm |
| §111 focus zones | partially: nothing else is focusable and the ring starts on the list, but unproven with the pad in hand |
| §1571 store, in artwork | **PRESENT** (`2b793eda`) — the seven marks, path for path, drawn where the desktop draws them |
| §285/§367 pin and move | pin: **PRESENT and marked** — Start/P, with the prompt flipping with the row (Nik: "yep pins work"). move: **PRESENT and marked** — right stick click or **M** (Nik: "yep that works too babe", 12 Oct 2026). The prompt carries the real RS glyph and the word flipping Move to GAMES / Move to APPS with the row, the gate the desktop states (not on ALL, never a host control), and the menu item and the pad opening onto the same `moveFocused()`. The line here said "still missing" an hour before it was done |
| §1047 the launch segue | not ported — Android hands off to Moonlight's own launch UI |

### No Android screen at all
- per-game settings — **not a port item, Nik's ruling (12 Oct 2026): "we can have per game settings as
  we dont use the desktops settings page".** Android's own stream settings page is what a per-game
  entry would sit on, so if that is ever built it is Android's surface, not the desktop's dialog
  ported across. Removed from this list.
- host profiles, pad shortcut capture, gamepad capture, stage background — **scrapped as port items,
  Nik's ruling (12 Oct 2026): "same reason we dont have the desktop settings so this wont work"**.
  They are all reached from the desktop's own settings surface, which Android does not use, so there
  is nothing here to port. `AppSettingsDialog` goes with them for the same reason.
- the desktop's own on-screen keyboard (`OnScreenKeyboard.qml`) — Android uses the system keyboard,
  which may be the right answer rather than a gap.
- ArtMoon's own update prompt (the desktop has one; I cannot point at an Android one).

### Parked on Nik (decisions, not work)
- Home's host-strip arrows: LB/RB as they are, or the desktop's LT/RT (`HomeScreen.qml:1853`).
- ~~Hidden apps / ShowHiddenApps — keep or drop~~ **answered: scrapped, and removed (`5029ed6c`)**.
- The finger-scroll rule that settles the cursor on the middle row — mine, the desktop has none.
- 66 commits on local `main`, unpushed.
- The desktop watcher-playtime fix (`6f358ea0`) still waiting for his word before it is pushed or
  built on the z13.

**And the cause he named is the cause.** The page was built by layering on the old picker rather than
porting it, which is why the faults keep arriving one at a time instead of all at once. This file
exists so each item is **marked by him**, never claimed by me.

