package io.github.onaiaku.artmoon.apps;

import android.content.Context;

import io.github.onaiaku.artmoon.nvstream.http.NvApp;
import io.github.onaiaku.artmoon.settings.PlaytimeManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The picker's list, decided. Port of the desktop's {@code AppModel} ({@code app/gui/appmodel.cpp})
 * — the model behind {@code AppsScreen.qml}.
 *
 * <p>Everything the page needs to draw a host's library is worked out in one pass here: which entries
 * the current tab shows, what order they are in, which heading each one falls under, and the three
 * counts the tabs print. The view is left to draw what it is handed.</p>
 *
 * <h3>The order (nvapp.h: appSortOrder, shared with the host list)</h3>
 * <p>Last played → pinned → Desktop → Virtual Display → Steam Big Picture → the rest, ties broken by
 * name. The one game played last is what the page calls Continue, and it sits at the very top.</p>
 *
 * <h3>⚠️ The two things that are never taken from the host's own list</h3>
 * <ul>
 *   <li><b>What you played last.</b> From this client's own record ({@link PlaytimeManager}), never
 *   from the host's LASTSESSION — see that class. A plain Sunshine/Apollo/Vibepollo host has no
 *   bridge to ask, and on our own hosts the answer would be whatever the host last ran, which may
 *   have been another device's session.</li>
 *   <li><b>Pins, and entries moved between tabs.</b> Also local, keyed host uuid + the game's name,
 *   so they survive a reinstall and an apps.json rebuilt with new ids.</li>
 * </ul>
 */
public final class AppLibrary {

    /** The three tabs. The host's saved tab is one of these (PlaytimeManager.lastTabOn). */
    public static final String CAT_ALL   = "all";
    public static final String CAT_GAMES = "games";
    public static final String CAT_APPS  = "apps";

    /** A row's heading. "all" is the tab's own heading — ALL GAMES, ALL APPS, or ALL. */
    public static final String SECTION_CONTINUE = "continue";
    public static final String SECTION_PINNED   = "pinned";
    public static final String SECTION_ALL      = "all";

    /** One line of the list: everything the row draws, decided here rather than in the adapter. */
    public static final class Row {
        public final NvApp app;
        public final String name;
        public final int appId;
        public final String uuid;
        public final boolean running;
        public final boolean hidden;
        public final boolean pinned;
        public final boolean isApp;
        public final boolean movable;

        /**
         * This entry has settings of its own. Always false until the per-app settings page is
         * ported — the desktop reads it from AppSettingsManager, which has no Android side yet. The
         * flag exists now so the row's meta line has one place to ask when it does.
         */
        public final boolean overridden;

        public final AppCategories.HostControl control;
        public final String section;
        public final PlaytimeManager.PlaytimeRecord playtime;

        Row(NvApp app, String name, boolean running, boolean hidden, boolean pinned, boolean isApp,
            boolean overridden, AppCategories.HostControl control, String section,
            PlaytimeManager.PlaytimeRecord playtime) {
            this.app = app;
            this.name = name;
            this.appId = app.getAppId();
            this.uuid = app.getUuid();
            this.running = running;
            this.hidden = hidden;
            this.pinned = pinned;
            this.isApp = isApp;
            // ⚠️ A host control is never moveable: the running-game copy would otherwise be offered a
            // move that the server ignores (nvapp.h, 6.1.0).
            this.movable = !AppCategories.isHostControlEntry(app.getAppId(), app.getUuid(), name);
            this.overridden = overridden;
            this.control = control;
            this.section = section;
            this.playtime = playtime;
        }

        /** True when this line sits under LAST PLAYED. Only ever row 0 (appmodel.cpp:364). */
        public boolean isContinue() {
            return SECTION_CONTINUE.equals(section);
        }
    }

    /** A host's list on one tab, plus the numbers the header and the tabs print. */
    public static final class Page {
        public final List<Row> rows = new ArrayList<Row>();
        public int gamesCount;
        public int appsCount;
        public int allCount;
        /** A Disconnect Monitor control is in the list — a 2.0 host is being monitored right now. */
        public boolean remoteMonitorActive;
    }

    private AppLibrary() {
    }

    /**
     * Builds the page.
     *
     * @param category     {@link #CAT_ALL}, {@link #CAT_GAMES} or {@link #CAT_APPS}
     * @param showHidden   the user asked to see hidden entries
     * @param hiddenIds    what this client has hidden on this host, by app id
     * @param keepVisibleIds what the current view is already showing, so an entry hidden a moment ago
     *                       does not vanish out from under the checkbox that just hid it
     * @param runningAppId the app the host says is running, or 0
     */
    public static Page page(Context context, String hostUuid, List<NvApp> apps, String category,
                            boolean showHidden, Set<Integer> hiddenIds, Set<Integer> keepVisibleIds,
                            int runningAppId) {
        final Page out = new Page();
        if (apps == null || apps.isEmpty()) {
            return out;
        }

        final Map<String, Boolean> moved = PlaytimeManager.categoryOverridesOn(context, hostUuid);
        final Set<String> pinned = PlaytimeManager.pinnedOn(context, hostUuid);
        final String lastPlayed = lastPlayedForSort(context, hostUuid, category);

        // Counts are over the whole library, not the tab in front of you: the tabs print what is
        // behind each of them (appmodel.cpp:197).
        for (NvApp app : apps) {
            final int id = app.getAppId();
            final String name = name(app);

            if (AppCategories.hostControlKind(id, app.getUuid(), name)
                    == AppCategories.HostControl.DISCONNECT_MONITOR) {
                out.remoteMonitorActive = true;
            }
            if (!showHidden && hiddenIds != null && hiddenIds.contains(id)) {
                continue;
            }
            if (categoryOf(app, moved)) {
                out.appsCount++;
            } else {
                out.gamesCount++;
            }
            if (AppCategories.isAllCategory(id, app.getUuid(), name)) {
                out.allCount++;
            }
        }

        final List<NvApp> visible = new ArrayList<NvApp>();
        for (NvApp app : apps) {
            if (!matchesCategory(app, category, moved)) {
                continue;
            }
            final boolean hidden = hiddenIds != null && hiddenIds.contains(app.getAppId());
            // ⚠️ Don't immediately hide what was just hidden — it is the only way the "hide" choice
            // can be taken back (appmodel.cpp:590).
            if (showHidden || !hidden
                    || (keepVisibleIds != null && keepVisibleIds.contains(app.getAppId()))) {
                visible.add(app);
            }
        }

        // ⚠️ Pins reach the sort for GAMES only, and never on APPS: the running-game copy a 2.0 host
        // sends carries a game's own name, so a stale pin would otherwise surface on the wrong tab,
        // ordering it with the pinned games while its own row says it is not one (appmodel.cpp:169).
        final Set<String> pinsForSort = new LinkedHashSet<String>();
        if (!CAT_APPS.equals(category)) {
            final Set<String> gameNames = new LinkedHashSet<String>();
            for (NvApp app : apps) {
                if (!categoryOf(app, moved)) {
                    gameNames.add(AppCategories.normaliseGameName(name(app)));
                }
            }
            for (String p : pinned) {
                if (gameNames.contains(p)) {
                    pinsForSort.add(p);
                }
            }
        }

        // Stable, exactly as the desktop sorts: equal keys keep their order, and the tie-break is the
        // name — which is what stops the list shuffling between two polls.
        Collections.sort(visible, new Comparator<NvApp>() {
            @Override
            public int compare(NvApp a, NvApp b) {
                final int oa = AppCategories.appSortOrder(name(a), lastPlayed, pinsForSort);
                final int ob = AppCategories.appSortOrder(name(b), lastPlayed, pinsForSort);
                if (oa != ob) {
                    return oa < ob ? -1 : 1;
                }
                return name(a).toLowerCase(Locale.ROOT).compareTo(name(b).toLowerCase(Locale.ROOT));
            }
        });

        for (int i = 0; i < visible.size(); i++) {
            final NvApp app = visible.get(i);
            final String name = name(app);
            final boolean isApp = categoryOf(app, moved);
            final boolean isPinned = !isApp
                    && pinned.contains(AppCategories.normaliseGameName(name));

            String section = isPinned ? SECTION_PINNED : SECTION_ALL;
            // ⚠️ Only row 0 can be Continue. That is what keeps this cheap, and what keeps the
            // heading a single row rather than a scattered set of them (appmodel.cpp:364-378).
            if (i == 0 && !lastPlayed.isEmpty() && name.equalsIgnoreCase(lastPlayed)) {
                section = SECTION_CONTINUE;
            }

            out.rows.add(new Row(app, name, app.getAppId() == runningAppId,
                    hiddenIds != null && hiddenIds.contains(app.getAppId()), isPinned, isApp, false,
                    AppCategories.hostControlKind(app.getAppId(), app.getUuid(), name), section,
                    PlaytimeManager.recordFor(context, hostUuid, name)));
        }

        return out;
    }

    /**
     * Is this entry an APP?
     *
     * <p>The automatic answer is {@link AppCategories#isAppsCategory}, but an entry the user moved by
     * hand wins over it (6.1.0) — and a host control can never be moved, so it always keeps the
     * answer the server's own layout gives it.</p>
     */
    public static boolean categoryOf(NvApp app, Map<String, Boolean> moved) {
        final boolean automatic = AppCategories.isAppsCategory(
                app.getAppId(), app.getUuid(), name(app));
        if (AppCategories.isHostControlEntry(app.getAppId(), app.getUuid(), name(app))) {
            return automatic;
        }
        final Boolean override = moved == null
                ? null : moved.get(AppCategories.normaliseGameName(name(app)));
        return override != null ? override.booleanValue() : automatic;
    }

    /** Which tab this entry belongs on. An empty category means "everything" (appmodel.cpp). */
    public static boolean matchesCategory(NvApp app, String category, Map<String, Boolean> moved) {
        if (category == null || category.isEmpty()) {
            return true;
        }
        if (CAT_ALL.equals(category)) {
            return AppCategories.isAllCategory(app.getAppId(), app.getUuid(), name(app));
        }
        return categoryOf(app, moved) == CAT_APPS.equals(category);
    }

    /**
     * The name the Continue row is looking for — empty on APPS, where nothing accumulates hours, and
     * empty when this host has no record yet (appmodel.cpp:lastPlayedForSort).
     */
    private static String lastPlayedForSort(Context context, String hostUuid, String category) {
        if (CAT_APPS.equals(category)) {
            return "";
        }
        final PlaytimeManager.PlaytimeRecord record = PlaytimeManager.lastPlayedOn(context, hostUuid);
        return record == null ? "" : name(record.name, "");
    }

    /** The row index of an app by id, or -1. Used to put the cursor back where it was. */
    public static int indexOfAppId(List<Row> rows, int appId) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).appId == appId) {
                return i;
            }
        }
        return -1;
    }

    /** The row index of an app by name — the desktop's indexOfAppNamed. */
    public static int indexOfNamed(List<Row> rows, String appName) {
        for (int i = 0; i < rows.size(); i++) {
            if (name(rows.get(i).name).equalsIgnoreCase(name(appName))) {
                return i;
            }
        }
        return -1;
    }

    /** Desktop (or Desktop (fallback)), wherever it is. */
    public static int indexOfDesktop(List<Row> rows) {
        for (int i = 0; i < rows.size(); i++) {
            if (AppCategories.isDesktopName(rows.get(i).name)) {
                return i;
            }
        }
        return -1;
    }

    /** The name of the entry the host says is running, or null. */
    public static String runningName(List<Row> rows, int runningAppId) {
        final int i = indexOfAppId(rows, runningAppId);
        return i < 0 ? null : rows.get(i).name;
    }

    private static String name(NvApp app) {
        return name(app.getAppName(), "");
    }

    private static String name(String value) {
        return name(value, "");
    }

    /** A null AppTitle is a real thing a host can send (a bare &lt;AppTitle/&gt;) — never let it spread. */
    private static String name(String value, String fallback) {
        return value == null ? fallback : value;
    }
}
