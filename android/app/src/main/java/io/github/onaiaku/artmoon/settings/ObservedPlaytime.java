/*
 * Playtime for the sessions this client WATCHED rather than started.
 *
 * <p>A client that merely has the app open still knows a host is streaming: the poller reads
 * `currentgame` from every serverinfo response, and the hero card draws the whole session off it —
 * Streaming now, the artwork, the title, Resume, Stop. What it never did was keep a record. Playtime
 * was only ever written by the session's OWNER (Game.java's startPlaytime/endPlaytime), so the moment
 * another client stopped the stream this client had nothing to fall back on and its last-played block
 * went empty — for a session it had been displaying on screen the entire time.</p>
 *
 * <p>This closes that. The record written here is the same record the owner would have written, to the
 * same store (PlaytimeManager), through the same two calls: addSeconds every kMinSessionSeconds while
 * the session is up, then one endSession when it ends. Nothing about the shape is re-invented — that
 * is the point.</p>
 *
 * <p>&#9888; BOTH clocks must never run for one session. If this client owns the stream, Game.java is
 * already counting it; {@link #ownSessionStarted} tells this class to stand down, and the minutes it
 * watched before it took over are banked first. That is why every entry point runs on the main looper
 * and the state is only ever touched there.</p>
 */
package io.github.onaiaku.artmoon.settings;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import io.github.onaiaku.artmoon.LimeLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class ObservedPlaytime {

    /** Only ever touched on the main looper. */
    private static final Handler handler = new Handler(Looper.getMainLooper());

    /** host uuid -> the session being watched on it. */
    private static final Map<String, Watch> watches = new HashMap<>();

    /** Hosts this client is streaming to itself, so the two clocks cannot both count one session. */
    private static final Set<String> ownSessions = new HashSet<>();

    private static boolean tickerRunning = false;
    private static volatile Context appContext;

    private ObservedPlaytime() { }

    private static final class Watch {
        final String hostUuid;
        final int gameId;
        final String name;
        final long startMs;
        long flushedSecs;

        Watch(String hostUuid, int gameId, String name) {
            this.hostUuid = hostUuid;
            this.gameId = gameId;
            this.name = name;
            this.startMs = SystemClock.elapsedRealtime();
        }
    }

    /**
     * One host's poll result, straight from ComputerManagerService.runPoll.
     *
     * <p>&#9888; An offline host is a HOLD, never an end. A phone that loses Wi-Fi sees its host go
     * offline while the stream carries on perfectly well, and banking there would split one session in
     * two and count it twice. Holding costs nothing: when the host answers again the id is 0 and the
     * session banks then.</p>
     */
    public static void onHostPolled(Context context, final String hostUuid, final boolean online,
                                    final int runningGameId, final String runningGameName) {
        if (context == null || hostUuid == null || hostUuid.isEmpty() || !online) {
            return;
        }
        appContext = context.getApplicationContext();
        final String uuid = hostUuid;
        final int gameId = runningGameId;
        final String name = runningGameName;
        handler.post(new Runnable() {
            @Override
            public void run() {
                onPolledOnMain(uuid, gameId, name);
            }
        });
    }

    /**
     * This client just took the stream over (or started it). Stand the watcher down for that host —
     * Game.java's own clock is the record from here — after banking what was watched before the
     * takeover, which was real time the session was up.
     */
    public static void ownSessionStarted(Context context, final String hostUuid) {
        if (context == null || hostUuid == null || hostUuid.isEmpty()) {
            return;
        }
        appContext = context.getApplicationContext();
        final String uuid = hostUuid;
        handler.post(new Runnable() {
            @Override
            public void run() {
                ownSessions.add(uuid);
                endWatchOnMain(uuid, "own stream took over");
            }
        });
    }

    /** This client's session on that host is over, so a NEW one there can be watched again. */
    public static void ownSessionEnded(final String hostUuid) {
        if (hostUuid == null || hostUuid.isEmpty()) {
            return;
        }
        final String uuid = hostUuid;
        handler.post(new Runnable() {
            @Override
            public void run() {
                ownSessions.remove(uuid);
            }
        });
    }

    private static void onPolledOnMain(String uuid, int gameId, String name) {
        final Watch existing = watches.get(uuid);

        // Nothing running: the session ended wherever it was started.
        if (gameId == 0) {
            endWatchOnMain(uuid, "host idle");
            return;
        }

        // Running, but this client cannot name it yet. Hold rather than end: an app list that has not
        // synced is not a stream that has stopped. A DIFFERENT id does mean the old session is over.
        if (name == null) {
            if (existing != null && existing.gameId != gameId) {
                endWatchOnMain(uuid, "host switched to an unnamed game " + gameId);
            }
            return;
        }

        // The host's Desktop entry and its controls are entries in the app list but not something
        // anyone "played" (playtime.cpp:46, refused by the same gate the owner uses).
        if (!PlaytimeManager.isTracked(name)) {
            endWatchOnMain(uuid, "untracked app " + name);
            return;
        }

        // This client owns the stream: Game.java is counting it, not us.
        if (ownSessions.contains(uuid)) {
            endWatchOnMain(uuid, "own stream owns it");
            return;
        }

        // Same session still up — the clock keeps running and nothing is written until the flush.
        if (existing != null && existing.gameId == gameId) {
            return;
        }

        // A different game: the previous session is over, this one starts now.
        if (existing != null) {
            endWatchOnMain(uuid, "host switched to " + name);
        }

        watches.put(uuid, new Watch(uuid, gameId, name));
        startTicker();
        LimeLog.info("watching session: " + uuid + " gameId=" + gameId + " " + name);
    }

    /**
     * Banks the seconds since the last flush, on the same interval Game.java uses, so a session that
     * ends without teardown — a kill, a battery pull — keeps all but its last minute.
     */
    private static void flushOnMain(Watch w) {
        final long elapsed = (SystemClock.elapsedRealtime() - w.startMs) / 1000;
        final long unbanked = elapsed - w.flushedSecs;
        if (unbanked <= 0) {
            return;
        }
        PlaytimeManager.addSeconds(appContext, w.hostUuid, w.name, w.gameId, unbanked);
        w.flushedSecs = elapsed;
    }

    /**
     * Closes a watched session: banks the time the flushes have not already taken, then records it as
     * that host's last played game.
     *
     * <p>&#9888; The two durations are not interchangeable, exactly as in Game.endPlaytime.
     * {@code unbanked} is what the flushes have not taken — passing the whole session would count
     * every flushed minute twice. {@code elapsed} is the session's real length, which is what the
     * 60-second threshold is judged against.</p>
     */
    private static void endWatchOnMain(String uuid, String why) {
        final Watch w = watches.remove(uuid);
        if (w == null) {
            return;
        }

        final long elapsed = (SystemClock.elapsedRealtime() - w.startMs) / 1000;
        final long unbanked = Math.max(0, elapsed - w.flushedSecs);

        // No stats: a client that is not streaming measures nothing, so every metric stays at
        // PlaytimeManager's "never measured" default rather than being invented.
        PlaytimeManager.endSession(appContext, w.hostUuid, w.name, w.gameId, unbanked, elapsed, null);

        LimeLog.info("watched session ended: " + uuid + " " + w.name + " " + elapsed + "s"
                     + " (unbanked " + unbanked + "s, " + why + ")");

        if (watches.isEmpty()) {
            stopTicker();
        }
    }

    private static final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (watches.isEmpty()) {
                tickerRunning = false;
                return;
            }
            for (Watch w : new ArrayList<>(watches.values())) {
                flushOnMain(w);
            }
            handler.postDelayed(this, PlaytimeManager.kMinSessionSeconds * 1000);
        }
    };

    private static void startTicker() {
        if (tickerRunning) {
            return;
        }
        tickerRunning = true;
        handler.postDelayed(ticker, PlaytimeManager.kMinSessionSeconds * 1000);
    }

    private static void stopTicker() {
        tickerRunning = false;
        handler.removeCallbacks(ticker);
    }
}
