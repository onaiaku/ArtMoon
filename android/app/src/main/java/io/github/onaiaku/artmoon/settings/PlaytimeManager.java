package io.github.onaiaku.artmoon.settings;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Per-game play time, kept by the client. Port of {@code app/settings/playtime.h} and
 * {@code playtime.cpp} — StreamLight 5.7.0, which ArtMoon Android never had.
 *
 * <p>How long you have streamed each game, and how the last session on it went. Everything here is
 * measured on this side and stored on this device: no bridge verb, no ArtLight, nothing fetched.
 * It is what the host card's "Last played" block is drawn from.</p>
 *
 * <p><b>Why this exists at all.</b> The card used to name the game from the HOST's last session
 * ({@code LASTSESSION} over {@code ArtLightBridge}, port 47998). Only our own ArtLight stack
 * answers that verb — a plain Sunshine, Apollo or Vibepollo host has no bridge, so the block never
 * appeared at all for anyone not running our stack, and on our own hosts it named whatever the
 * host last ran, which may have been another device's session. The desktop hit exactly this and
 * fixed it in 5.6.1, in its own words: <i>"Nothing here comes from the host ... It now describes
 * what YOU played, from a record kept on this machine, and works against a plain Sunshine host."</i>
 * This is that record, on this machine. Nik, 12/10/2026: <i>"we support many different sunshine
 * based servers, the ONLY one that has artlight controll is our Artlight stack ... hence why the
 * desktop app saves it locally."</i></p>
 *
 * <h3>The key</h3>
 *
 * <p>Records are keyed by host uuid + the <em>name</em> of the game, not by app id.</p>
 *
 * <p>&#9888; The name is what survives a reinstall, which is the whole requirement. App ids do not:
 * for the games our stack manages, the id is derived from the lowercased name, so it moves when a
 * title is corrected; for entries added by hand in the streaming server's own UI, the server
 * assigns it however it likes, and Apollo or Vibepollo may not agree with Sunshine.</p>
 *
 * <p>&#9888; The uuid, and NOT the host's storage key. That is how per-game overrides, host profiles
 * and the box art cache are already keyed — so a Tailscale clone and the LAN tile it was cloned
 * from share one set of hours, which is right: it is the same PC reached by another road. Keying on
 * the storage key would split them in two and restart the count the first time you streamed over
 * Tailscale.</p>
 *
 * <p>&#9888; The name cannot BE the key. On the desktop it is a Windows registry key, where
 * {@code /} is a group separator and {@code \} is rejected outright, so a title like
 * <i>"Marvel's Spider-Man: Miles Morales"</i> is not a legal key name. Here the same rule is kept
 * for the same reason plus one of our own: a SharedPreferences key is a flat string, so a name
 * carrying a {@code /} would collide with the group separator itself. The key is a hex digest of
 * the normalised name and the readable name is stored as a value inside it.</p>
 *
 * <p>Keys keep the desktop's own shape ({@code playtime/<uuid>/...}) rather than being flattened,
 * so the two stores can be read side by side.</p>
 */
public final class PlaytimeManager {

    /**
     * A session shorter than this still counts towards the total — those minutes were real — but
     * does NOT become the host's last played game. A launch corrected ten seconds later must not
     * take the Resume button away from what you were actually playing.
     */
    public static final long kMinSessionSeconds = 60;

    private static final String PREFS_NAME = "playtime";

    // The desktop's QSettings prefix (playtime.cpp:7).
    private static final String PLAYTIME_PREFIX = "playtime/";

    // Values inside a game's group (playtime.cpp:10-23).
    private static final String SER_NAME        = "name";
    private static final String SER_APPID       = "appid";
    private static final String SER_TOTAL       = "totalSeconds";
    private static final String SER_COUNT       = "sessionCount";
    private static final String SER_LASTPLAYED  = "lastPlayed";
    private static final String SER_LASTSECONDS = "lastSessionSeconds";
    private static final String SER_FPS         = "lastFpsAvg";
    private static final String SER_TARGETFPS   = "lastTargetFps";
    private static final String SER_DROPS       = "lastDropsPct";
    private static final String SER_JITTERDROPS = "lastJitterDropsPct";
    private static final String SER_RTT         = "lastRttMs";
    private static final String SER_HOSTLAT     = "lastHostLatencyMs";
    private static final String SER_DECODE      = "lastDecodeMs";
    private static final String SER_BITRATE     = "lastBitrateMbps";

    /**
     * Which game this host was last played on, stored beside the game groups rather than found by
     * walking them: with the pointer written once per session, "what do I resume here" is one read
     * instead of loading every record and sorting it by date on every repaint of the card.
     */
    private static final String SER_LASTGAME    = "lastGame";

    private PlaytimeManager() {
    }

    /**
     * What one game on one host looks like on disk.
     *
     * <p>-1 in a metric means "never measured", which is not zero: a card that printed 0 % drops
     * for a session that never reported any would be inventing a result. Same convention as the
     * bridge's own STATS and LASTSESSION payloads.</p>
     */
    public static final class PlaytimeRecord {
        public boolean valid = false;

        public String name = "";        // as launched, for display
        public int appId = 0;           // last known id on this host — a hint, never the key

        public long totalSeconds = 0;   // every session ever, on this host
        public int sessionCount = 0;    // how many of them there were
        public long lastPlayedUtc = 0;  // epoch millis UTC; 0 until a session passes the threshold
        public long lastSessionSeconds = 0;

        public float lastFpsAvg = -1.0f;
        public int lastTargetFps = 0;
        public float lastDropsPct = -1.0f;
        public float lastJitterDropsPct = -1.0f;
        public float lastRttMs = -1.0f;
        public float lastHostLatencyMs = -1.0f;
        public float lastDecodeMs = -1.0f;
        public float lastBitrateMbps = -1.0f;
    }

    /**
     * What a finished session measured. Filled from the decoder's whole-session totals right before
     * it is torn down, exactly as the desktop fills {@code PlaytimeSessionStats}.
     *
     * <p>Every field defaults to "never measured" rather than zero, so a caller that has no numbers
     * to give (this client does not yet sample them) stores -1 and the record says so.</p>
     */
    public static final class SessionStats {
        public float fpsAvg = -1.0f;
        public int targetFps = 0;
        public float dropsPct = -1.0f;
        public float jitterDropsPct = -1.0f;
        public float rttMs = -1.0f;
        public float hostLatencyMs = -1.0f;
        public float decodeMs = -1.0f;
        public float bitrateMbps = -1.0f;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Trims, collapses runs of inner whitespace, and case-folds, so "Hollow  Knight" and
     * "Hollow Knight" are one game. The desktop uses QString::simplified().toLower() (nvapp.h:148),
     * which is the same three things: the streaming server's entry and the library's copy of a
     * title do not always agree on capitalisation or spacing.
     */
    public static String normalise(String appName) {
        if (appName == null) {
            return "";
        }
        // ⚠️ One spelling (nvapp.h:148, playtime.h:182): the record and the picker's sort have to
        // agree about whether two names are the same game, or a pinned title and its play time end
        // up filed under two keys.
        return io.github.onaiaku.artmoon.apps.AppCategories.normaliseGameName(appName);
    }

    /**
     * FNV-1a, 64-bit, over the UTF-8 of the normalised name (playtime.cpp:63).
     *
     * <p>A hash rather than the name itself because the name cannot be a key — see the class note.
     * 64 bits is far more than a library of a few hundred titles needs, and the readable name is
     * stored inside the group anyway, so a collision would be visible rather than silent.</p>
     */
    private static String digest(String normalisedName) {
        final byte[] utf8 = normalisedName.getBytes(StandardCharsets.UTF_8);
        long hash = 0xcbf29ce484222325L;        // 14695981039346656037
        for (byte b : utf8) {
            hash ^= (b & 0xff);
            hash *= 0x100000001b3L;             // 1099511628211
        }
        return String.format(Locale.ROOT, "%016x", hash);
    }

    /** Port of nvapp.h:30 — the host's own desktop entry, which is not a game. */
    private static boolean isDesktopName(String name) {
        if (name == null) {
            return false;
        }
        final String n = name.trim();
        return n.equalsIgnoreCase("Desktop") || n.equalsIgnoreCase("Desktop (fallback)");
    }

    /** Port of nvapp.h:37 — the host controls and launchers that appear in the app list. */
    private static boolean isSystemApp(String name) {
        if (isDesktopName(name)) {
            return true;
        }
        if (name == null) {
            return false;
        }
        final String n = name.trim();
        for (String known : new String[] {
                "Steam Big Picture", "Virtual Display",
                "Remote Input", "Remote Monitor", "Terminate", "Resume",
                "Disconnect Monitor", "Disconnect Input" }) {
            if (n.equalsIgnoreCase(known)) {
                return true;
            }
        }
        return false;
    }

    /** playtime.cpp:46 — nothing untracked is ever written, so the desktop and this agree. */
    public static boolean isTracked(String appName) {
        return appName != null && !appName.isEmpty() && !isSystemApp(appName);
    }

    /** playtime.cpp:92 — "0 h" is never worth printing; under an hour the minutes are the answer. */
    public static String formatDuration(long seconds) {
        if (seconds < 0) {
            seconds = 0;
        }
        final long hours = seconds / 3600;
        final long minutes = (seconds % 3600) / 60;
        if (hours == 0) {
            return minutes + " m";
        }
        // Two digits on the minutes past the first hour so the numbers line up in a column.
        return hours + " h " + String.format(Locale.ROOT, "%02d", minutes) + " m";
    }

    private static String hostGroup(String hostUuid) {
        return PLAYTIME_PREFIX + hostUuid;
    }

    /**
     * The 'g' prefix keeps a game group from ever colliding with a plain value written at the host
     * level — SER_LASTGAME lives in the same node (playtime.cpp:85).
     */
    private static String gameGroup(String hostUuid, String appName) {
        return hostGroup(hostUuid) + "/g" + digest(normalise(appName));
    }

    /**
     * Adds time to a game's total (playtime.cpp:110). Called every minute during a session and once
     * at the end, so a session that ends by the process being killed still keeps all but its last
     * minute.
     *
     * <p>Only the <em>hours</em> move here. Nothing about "the last session" is written, because
     * mid-session there is no such thing yet.</p>
     */
    public static void addSeconds(Context context, String hostUuid, String appName, int appId,
                                  long seconds) {
        if (seconds <= 0 || hostUuid == null || hostUuid.isEmpty() || !isTracked(appName)) {
            return;
        }

        final SharedPreferences p = prefs(context);
        final String group = gameGroup(hostUuid, appName);

        final long total = p.getLong(group + "/" + SER_TOTAL, 0) + seconds;

        // Rewritten every time rather than only on creation: the display name follows the library
        // when a title is corrected (the normalised key is what has to stay put, and a rename that
        // changes the key starts a new record by design), and the id follows the host when its app
        // list is rebuilt.
        p.edit()
                .putLong(group + "/" + SER_TOTAL, total)
                .putString(group + "/" + SER_NAME, appName)
                .putInt(group + "/" + SER_APPID, appId)
                .apply();
    }

    /**
     * Closes a session: banks the time not yet flushed, then records it as the last one played
     * (playtime.cpp:132).
     *
     * <p>&#9888; Two durations, and they are not interchangeable. {@code unbankedSeconds} is what
     * {@link #addSeconds} has not already taken during the session — passing the full length here
     * would count every flushed minute twice. {@code sessionSeconds} is the session's real length,
     * which is what gets stored as "last session" and what the threshold is judged against.</p>
     */
    public static void endSession(Context context, String hostUuid, String appName, int appId,
                                  long unbankedSeconds, long sessionSeconds, SessionStats stats) {
        if (hostUuid == null || hostUuid.isEmpty() || !isTracked(appName)) {
            return;
        }

        // Only what the flushes have not already taken — see above.
        addSeconds(context, hostUuid, appName, appId, unbankedSeconds);

        if (sessionSeconds < kMinSessionSeconds) {
            return;
        }

        final SharedPreferences p = prefs(context);
        final String group = gameGroup(hostUuid, appName);
        final SessionStats s = stats != null ? stats : new SessionStats();

        // Counted past the same threshold that decides "last played", and for the same reason:
        // a launch corrected ten seconds later was not a session you had.
        p.edit()
                .putInt(group + "/" + SER_COUNT, p.getInt(group + "/" + SER_COUNT, 0) + 1)
                .putLong(group + "/" + SER_LASTPLAYED, System.currentTimeMillis())
                .putLong(group + "/" + SER_LASTSECONDS, sessionSeconds)
                .putFloat(group + "/" + SER_FPS, s.fpsAvg)
                .putInt(group + "/" + SER_TARGETFPS, s.targetFps)
                .putFloat(group + "/" + SER_DROPS, s.dropsPct)
                .putFloat(group + "/" + SER_JITTERDROPS, s.jitterDropsPct)
                .putFloat(group + "/" + SER_RTT, s.rttMs)
                .putFloat(group + "/" + SER_HOSTLAT, s.hostLatencyMs)
                .putFloat(group + "/" + SER_DECODE, s.decodeMs)
                .putFloat(group + "/" + SER_BITRATE, s.bitrateMbps)
                // The pointer, written in the same commit as the record it names so a kill between
                // the two cannot leave the host claiming a last game with no record behind it.
                .putString(hostGroup(hostUuid) + "/" + SER_LASTGAME, appName)
                .apply();
    }

    /** playtime.cpp:173. An invalid record means draw nothing at all — no empty state. */
    public static PlaytimeRecord recordFor(Context context, String hostUuid, String appName) {
        final PlaytimeRecord r = new PlaytimeRecord();
        if (hostUuid == null || hostUuid.isEmpty() || appName == null || appName.isEmpty()) {
            return r;
        }

        final SharedPreferences p = prefs(context);
        final String group = gameGroup(hostUuid, appName);

        // No total means no record: every write path sets it, so its absence is the one reliable
        // "nothing here" test.
        if (!p.contains(group + "/" + SER_TOTAL)) {
            return r;
        }

        r.valid = true;
        r.name = p.getString(group + "/" + SER_NAME, appName);
        r.appId = p.getInt(group + "/" + SER_APPID, 0);
        r.totalSeconds = p.getLong(group + "/" + SER_TOTAL, 0);
        r.sessionCount = p.getInt(group + "/" + SER_COUNT, 0);
        r.lastSessionSeconds = p.getLong(group + "/" + SER_LASTSECONDS, 0);
        r.lastPlayedUtc = p.getLong(group + "/" + SER_LASTPLAYED, 0);

        r.lastFpsAvg = p.getFloat(group + "/" + SER_FPS, -1.0f);
        r.lastTargetFps = p.getInt(group + "/" + SER_TARGETFPS, 0);
        r.lastDropsPct = p.getFloat(group + "/" + SER_DROPS, -1.0f);
        r.lastJitterDropsPct = p.getFloat(group + "/" + SER_JITTERDROPS, -1.0f);
        r.lastRttMs = p.getFloat(group + "/" + SER_RTT, -1.0f);
        r.lastHostLatencyMs = p.getFloat(group + "/" + SER_HOSTLAT, -1.0f);
        r.lastDecodeMs = p.getFloat(group + "/" + SER_DECODE, -1.0f);
        r.lastBitrateMbps = p.getFloat(group + "/" + SER_BITRATE, -1.0f);

        return r;
    }

    /**
     * The game this host was last played on, or an invalid record when there is none
     * (playtime.cpp:216). When it returns invalid the UI draws nothing at all — no empty state,
     * no greyed row.
     */
    public static PlaytimeRecord lastPlayedOn(Context context, String hostUuid) {
        if (hostUuid == null || hostUuid.isEmpty()) {
            return new PlaytimeRecord();
        }

        final SharedPreferences p = prefs(context);
        final String name = p.getString(hostGroup(hostUuid) + "/" + SER_LASTGAME, "");
        if (name == null || name.isEmpty()) {
            return new PlaytimeRecord();
        }

        // The pointer can outlive the record it names — reset() clears a game without knowing
        // whether it was the last one played. recordFor() returning invalid is the answer in that
        // case, and the caller draws nothing.
        return recordFor(context, hostUuid, name);
    }

    /**
     * Clears one game's record, and takes the "last played here" pointer with it when it named this
     * game, or the card would go on offering a game with no record behind it (playtime.cpp:235).
     */
    public static void reset(Context context, String hostUuid, String appName) {
        if (hostUuid == null || hostUuid.isEmpty() || appName == null || appName.isEmpty()) {
            return;
        }

        final SharedPreferences p = prefs(context);
        final SharedPreferences.Editor editor = p.edit();
        editor.remove(gameGroup(hostUuid, appName));

        final String pointer = p.getString(hostGroup(hostUuid) + "/" + SER_LASTGAME, "");
        if (pointer != null && normalise(pointer).equals(normalise(appName))) {
            editor.remove(hostGroup(hostUuid) + "/" + SER_LASTGAME);
        }

        editor.apply();
    }

    // ── Pinned games (6.0.0) ────────────────────────────────────────────────────────────────────
    /*
     * The games pinned on this host, which the picker lists under PINNED, right after LAST PLAYED.
     * They live here, beside the play time, because they are keyed exactly the same way and for the
     * same reason: host uuid + the game's NAME, so a pin survives a reinstall and an apps.json
     * rebuilt with new ids, and a Tailscale clone shares the pins of the LAN tile it came from.
     *
     * ⚠️ One value per host holding the names, not a group per game: pins are read whole on every
     * sort, and one read beats walking every record to rebuild the list. It sits in the host node,
     * so forgetHost() takes it along, and reset() — which clears one game's HOURS — does not.
     */
    private static final String SER_PINNED  = "pinned";

    // Entries moved by hand to the other tab (6.1.0), one list per direction (playtime.cpp:33-35).
    private static final String SER_ASAPPS  = "movedToApps";
    private static final String SER_ASGAMES = "movedToGames";

    // The picker's tab the user last chose on this host (6.3.0).
    private static final String SER_LASTTAB = "lastTab";

    /**
     * Reads one of the name lists stored at the host level. Always a copy: SharedPreferences hands
     * back the live set for a string set, and mutating it in place is a documented way to corrupt
     * the store on the next write.
     */
    private static Set<String> readNameSet(SharedPreferences p, String key) {
        Set<String> stored = p.getStringSet(key, null);
        return stored == null ? new LinkedHashSet<String>() : new LinkedHashSet<String>(stored);
    }

    /** Names are compared the one way — see AppCategories.normaliseGameName(). */
    private static String key(String appName) {
        return io.github.onaiaku.artmoon.apps.AppCategories.normaliseGameName(appName);
    }

    private static void writeNameSet(SharedPreferences.Editor editor, String key, Set<String> names) {
        // Empty means "nothing here", not "an empty list": the desktop removes the key so a later
        // change to the automatic answer still reaches every entry the user never touched.
        if (names.isEmpty()) {
            editor.remove(key);
        } else {
            editor.putStringSet(key, names);
        }
    }

    /** Normalised names — the form AppCategories.appSortOrder() looks them up in. */
    public static Set<String> pinnedOn(Context context, String hostUuid) {
        Set<String> out = new LinkedHashSet<String>();
        if (hostUuid == null || hostUuid.isEmpty()) {
            return out;
        }
        for (String name : readNameSet(prefs(context), hostGroup(hostUuid) + "/" + SER_PINNED)) {
            out.add(key(name));
        }
        return out;
    }

    /**
     * Pins or unpins one game. The readable spelling is what gets stored, so the list can be read
     * by a human; lookups fold case through key() on both sides.
     */
    public static void setPinned(Context context, String hostUuid, String appName, boolean pinned) {
        if (hostUuid == null || hostUuid.isEmpty() || appName == null || appName.isEmpty()) {
            return;
        }

        final SharedPreferences p = prefs(context);
        final String prefKey = hostGroup(hostUuid) + "/" + SER_PINNED;
        final Set<String> names = readNameSet(p, prefKey);
        final String want = key(appName);

        // Out first, whatever spelling it went in under — unpinning "Hollow  Knight" has to remove
        // a pin made as "hollow knight".
        for (String existing : new java.util.ArrayList<String>(names)) {
            if (key(existing).equals(want)) {
                names.remove(existing);
            }
        }
        if (pinned) {
            names.add(appName);
        }

        final SharedPreferences.Editor editor = p.edit();
        writeNameSet(editor, prefKey, names);
        editor.apply();
    }

    /** Normalised name → true when the user put it under APPS, false when under GAMES. */
    public static Map<String, Boolean> categoryOverridesOn(Context context, String hostUuid) {
        Map<String, Boolean> out = new LinkedHashMap<String, Boolean>();
        if (hostUuid == null || hostUuid.isEmpty()) {
            return out;
        }

        final SharedPreferences p = prefs(context);
        for (String name : readNameSet(p, hostGroup(hostUuid) + "/" + SER_ASGAMES)) {
            out.put(key(name), Boolean.FALSE);
        }
        for (String name : readNameSet(p, hostGroup(hostUuid) + "/" + SER_ASAPPS)) {
            out.put(key(name), Boolean.TRUE);
        }
        return out;
    }

    /**
     * Moves one entry to the other tab, or back. {@code asApp} is where it goes; {@code automatic}
     * is where it would land by itself — only a departure from that is stored, so a later change to
     * the automatic split still reaches every entry the user never touched (playtime.cpp:318).
     */
    public static void setCategoryOverride(Context context, String hostUuid, String appName,
                                           boolean asApp, boolean automatic) {
        if (hostUuid == null || hostUuid.isEmpty() || appName == null || appName.isEmpty()) {
            return;
        }

        final SharedPreferences p = prefs(context);
        final SharedPreferences.Editor editor = p.edit();
        final String want = key(appName);

        for (String listKey : new String[] { SER_ASAPPS, SER_ASGAMES }) {
            final String fullKey = hostGroup(hostUuid) + "/" + listKey;
            final Set<String> names = readNameSet(p, fullKey);
            for (String existing : new java.util.ArrayList<String>(names)) {
                if (key(existing).equals(want)) {
                    names.remove(existing);
                }
            }
            if (asApp != automatic && listKey.equals(asApp ? SER_ASAPPS : SER_ASGAMES)) {
                names.add(appName);
            }
            writeNameSet(editor, fullKey, names);
        }
        editor.apply();
    }

    /** "all", "games" or "apps"; empty when nothing was ever chosen on this host. */
    public static String lastTabOn(Context context, String hostUuid) {
        if (hostUuid == null || hostUuid.isEmpty()) {
            return "";
        }
        return prefs(context).getString(hostGroup(hostUuid) + "/" + SER_LASTTAB, "");
    }

    /** Only a choice made by hand is written — the picker's own fallbacks never overwrite it. */
    public static void setLastTab(Context context, String hostUuid, String tab) {
        if (hostUuid == null || hostUuid.isEmpty() || tab == null || tab.isEmpty()) {
            return;
        }
        prefs(context).edit().putString(hostGroup(hostUuid) + "/" + SER_LASTTAB, tab).apply();
    }

    /**
     * Drops every record belonging to a host (playtime.cpp:365). Keyed by uuid, so once the host is
     * gone nothing can reach them again.
     */
    public static void forgetHost(Context context, String hostUuid) {
        if (hostUuid == null || hostUuid.isEmpty()) {
            return;
        }

        final SharedPreferences p = prefs(context);
        final String prefix = hostGroup(hostUuid) + "/";

        final SharedPreferences.Editor editor = p.edit();
        for (String key : p.getAll().keySet()) {
            if (key.startsWith(prefix)) {
                editor.remove(key);
            }
        }
        editor.apply();
    }
}
