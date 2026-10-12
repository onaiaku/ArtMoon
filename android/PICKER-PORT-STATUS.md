# Where the picker actually is — 12 Oct 2026

Nik: *"there are that many little ui things that are either missing or broken (and i dont know which
of those for each thing) that i have no idea where we are or what we are doing."*

So this is the map. Every item is one of four honest verdicts, and each one says what you'd **see on
the phone** so you can check it yourself in a minute rather than take my word for it.

- **PRESENT** — it's there and it follows the desktop.
- **MINE** — it's there, but it's my version of the thing, not the desktop's.
- **MISSING** — the desktop has it, Android doesn't.
- **UNKNOWN** — I can't confirm it without your eyes or without reading more. Counted, not hidden.

The reference is `app/gui/AppsScreen.qml` (1897 lines, 19 marked sections) and `app/gui/HostStage.qml`.
A verdict is only *ported* here if I can point at the desktop line.

---

## 1. The structure, in the desktop's own § order

| Desktop § | What you'd see | Verdict |
|---|---|---|
| §34 props / host identity | the page knows which host it's on | **PRESENT** — by intent uuid + ComputerManager, not AppShell |
| §88 scale | everything the same size as Home | **PRESENT** — `AmScale` |
| §95 the two columns | list left, spotlight right, blurred cover behind | **PRESENT** — `apps_page.xml` 0.52/0.48, `showAmbient` → `AppAmbientArtwork` |
| §111 focus zones / boundaries | one zone, list focus, pad never stops on a button | **MISSING** — only lit/quiet rows exist |
| §231 pad glyphs | real pad artwork by button *position* (Switch swaps A/B; Select is artwork, not a word) | **MINE** — printed letters: "A", "LB", "X", "Esc" |
| §239 the game in the spotlight | cover, name, state, store | **PRESENT** — `updateHero` |
| §250 play time in the spotlight | hours + sessions for the focused game | **PRESENT** — `PlaytimeManager`; was frozen on row 0 until tonight |
| §285 pinned | pinned heading + mark on the row | **PRESENT**, but reached by long-press menu, not Start/P |
| §300 GAMES / APPS tabs | three tabs, ring wraps, last choice remembered | **MINE/MISSING** — tabs + ring + saved tab are there; **the desktop's fallback rules are not**: open on APPS when a Remote Monitor is held, and jump to APPS when the current tab has 0 entries and APPS has some (`AppsScreen.qml:437-440`) |
| §367 GAMES / APPS by hand | move one entry between the tabs | **PRESENT** — via the menu; desktop uses M / right-stick |
| §446 host notices | "that finished" vs "launch again within 60s to confirm" | **MISSING** |
| §916 the configuration line | host link speed + your stream settings | **PRESENT** — `fillConfigLine` + `addCfgChip` |
| §1047 the game (launch screen) | the segue into a launch | **NOT PORTED** — Moonlight's own launch UI |
| §1095 action row | the verb's pill, per-game settings, Stop | **PARTIAL** — launch + Stop only; **no per-game settings entry**; letters not artwork |
| §1331 Continue | the last thing played sits on top | **PRESENT** — `SECTION_CONTINUE`, row 0 only |
| §1537 thumbnail | box art in the row | **PRESENT** — after tonight's prefetch; was losing a race on every load |
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
| Tab counts | `_countFor()` exists and drives which tab *opens*; I have **not** confirmed the tab prints the number | **UNKNOWN** — Android shows none either way |
| Store logos | seven SVGs in `app/res/` (`store_steam/gog/epic/ea/ubisoft/battlenet/xbox.svg`), drawn by `GameFacts.qml:63` | **MISSING** app-wide. The picker says the store in words on the desktop too, but the artwork exists and I told you it didn't |
| Foot key hints | swap with the input in your hand | **MINE** — keyboard letters only |
| Ambient backdrop | focused cover, blurred | **PRESENT** |
| Host chips (link / online / profile) | yes | **PRESENT** — `paintChips`; profile + tailscale chips are hidden today |
| "some may be hidden on the host" | the desktop never blames the host for a client bug | **PRESENT** — fixed; it was mine |
| Hidden apps / ShowHiddenApps | the desktop has no such feature | **EXTRA** — inherited from the old Android picker |

## 3. The old picker is still underneath — verified, file and line

1. `AndroidManifest.xml` declares **both** activities: `.AppView` (~120) and `.apps.AppsPageView` (~129).
2. `ShortcutTrampoline.java:176` builds its stack `PcView` → **`AppView.class`** — a home-screen
   shortcut still opens the old picker.
3. `AppsPageView` reads `AppView.NAME_EXTRA` / `UUID_EXTRA`, written by `ServerHelper.java:38-47`.
4. Its host poll says so in my own comment: *"old screen started here too (AppView.startComputerUpdates)"*.
5. The cached app-list read is copied out of `AppView` the same way, and the comment says so.
6. `AppsRowAdapter` reuses the old grid's `CachedAppAssetLoader` with `AppGridAdapter`'s dummy-`TextView` trick.
7. `SHOW_HIDDEN_APPS_EXTRA` is still in the new page's intent.

Not all reuse is a liberty: the bound `ComputerManagerService`, `ServerHelper` and the
`ComputerDetails`/`NvApp` types are the only way on Android to talk to a host. The seven above are the
old **screen's** decisions, and keeping them is what makes this a re-skin rather than a port.

## 4. What I can't tell you without your eyes

- Whether the covers actually appear now (the fetch needs the host and the list in front of you).
- Whether the bottom prompts sit right in landscape.
- How any of it *feels* — deadzones, scroll, the ring between zones.
- Which of the **MINE** items you'd accept as-is rather than a straight port.

## 5. Next, in your order

Nothing gets called ported until you've marked it. The old picker coming out of the path is the first
mechanical job. After that, the desktop's section order leads — not mine.
