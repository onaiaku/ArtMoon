package io.github.onaiaku.artmoon.artlight;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import io.github.onaiaku.artmoon.LimeLog;
import io.github.onaiaku.artmoon.grid.PcGridAdapter;
import io.github.onaiaku.artmoon.nvstream.http.ComputerDetails;

import org.json.JSONObject;

/**
 * HostMetricsPoller — Android port of the desktop's per-host probes.
 * Polls LASTSESSION and STATUS from ArtLight hosts (via ArtLightBridge,
 * port 47998) and paints the hero card's LAST SESSION panel and HOST LINK
 * number — the desktop card's actual right-column contents. Hosts with no
 * session history keep the panel hidden; unreachable hosts hide everything.
 * No live stats are invented: desktop shows RTT/Host-lat./Drops only from a
 * real session's telemetry.
 */
public class HostMetricsPoller {

    private static final long INTERVAL_MS = 2000;

    private final ArtLightBridge bridge;
    private final PcGridAdapter adapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running = false;
    private boolean pollInFlight = false;

    private static final class PollTarget {
        final String uuid;
        final String address;
        final String name;

        PollTarget(ComputerDetails details) {
            this.uuid = details.uuid;
            this.name = details.name;
            // Prefer the reachable (active) address, then remote, then local.
            this.address =
                    (details.activeAddress != null) ? details.activeAddress.address :
                    (details.remoteAddress != null) ? details.remoteAddress.address :
                    (details.localAddress != null) ? details.localAddress.address : null;
        }
    }

    public HostMetricsPoller(Context context, PcGridAdapter adapter) {
        this.bridge = new ArtLightBridge(context);
        this.adapter = adapter;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        handler.post(pollRunnable);
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(pollRunnable);
    }

    /**
     * The last values each host gave us — kept in the PROCESS, not in the adapter.
     *
     * On a screen change the activity rebuilds its whole view tree and makes a NEW adapter, so
     * every cache that lives on the adapter comes up empty: the card painted blank and stayed
     * blank until the next round trip landed, with the cover art decoded from scratch on top of
     * that. Nik: "rotating the screen or unfolding ... its taking a dag and a age for the stats and
     * the last played to show up at all."
     *
     * None of this data is per-activity. Last session, host link and the games strip are facts
     * about the HOST, so they are held here, keyed by uuid, and re-applied to a freshly bound card
     * the moment one exists (PcGridAdapter.getView). A rebuild now paints the last known truth
     * immediately and the next poll corrects it a couple of seconds later, instead of the card
     * being empty for the whole of that time.
     */
    private static final class LastKnown {
        String ago, duration, grade, gradeColor;
        int rttMs = -1, rttPeakMs = -1, hostLatMs = -1;
        double dropsPct = -1;
        int gamesTotal;
        java.util.List<GameCover> games;
        String hostLink;
    }

    private static final java.util.HashMap<String, LastKnown> LAST_KNOWN =
            new java.util.HashMap<>();

    private static LastKnown known(String uuid) {
        LastKnown lk = LAST_KNOWN.get(uuid);
        if (lk == null) {
            lk = new LastKnown();
            LAST_KNOWN.put(uuid, lk);
        }
        return lk;
    }

    /**
     * Paint a freshly bound card from the last values seen for it, if it has ever reported any.
     *
     * Called by the adapter at bind time — the one moment a view exists and has nothing on it.
     * Silent when the host has never reported: a host we know nothing about must still show its
     * empty state rather than somebody else's stale numbers.
     */
    public static void applyCachedTo(PcGridAdapter adapter, String uuid) {
        if (adapter == null || uuid == null) {
            return;
        }
        LastKnown lk = LAST_KNOWN.get(uuid);
        if (lk == null) {
            return;
        }
        adapter.updateLastSessionByUuid(uuid, lk.ago, lk.duration, lk.grade, lk.gradeColor,
                lk.rttMs, lk.rttPeakMs, lk.hostLatMs, lk.dropsPct, lk.gamesTotal, lk.games);
        adapter.updateGameFactsByUuid(uuid, lk.ago, lk.duration, lk.games);
        if (lk.hostLink != null) {
            adapter.updateHostLinkByUuid(uuid, lk.hostLink);
        }
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            if (!pollInFlight) {
                pollInFlight = true;
                pollOnce();
            }
            handler.postDelayed(this, INTERVAL_MS);
        }
    };

    private void pollOnce() {
        // The host the stage is showing. The desktop polls its foreground host, which is the same
        // thing, and the binders write through boundViews — only the host with a rendered card has
        // anywhere to write to, so polling any other host would fetch numbers nothing can display.
        PollTarget target = null;
        Object o = adapter.getItem(0);
        if (o instanceof io.github.onaiaku.artmoon.PcView.ComputerObject) {
            ComputerDetails d = ((io.github.onaiaku.artmoon.PcView.ComputerObject) o).details;
            if (d.state == ComputerDetails.State.ONLINE) {
                target = new PollTarget(d);
            }
        }

        if (target == null || target.address == null) {
            pollInFlight = false;
            return;
        }

        final PollTarget tgt = target;

        // Desktop parity (HostStage.qml right column): the card's right side is
        // the LAST SESSION panel fed by the host's LASTSESSION command —
        // ago/duration/grade/rtt/host-lat/drops. Hidden when the host has no
        // last session; fields the host never measured render as \u2014.
        bridge.requestLastSession(tgt.address, new ArtLightBridge.ResponseCallback() {
            @Override
            public void onResult(final String response) {
                final LastSessionSnapshot snap = parseLastSession(response);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        pollInFlight = false; // cycle complete: allow the next poll even on error replies
                        if (!running) {
                            return;
                        }
                        // Record BEFORE painting: the cache is what a rebuilt card reads, and it
                        // must never be a step behind the view it is meant to restore.
                        LastKnown lk = known(tgt.uuid);
                        lk.ago = snap == null ? null : snap.ago;
                        lk.duration = snap == null ? null : snap.duration;
                        lk.grade = snap == null ? null : snap.grade;
                        lk.gradeColor = snap == null ? null : snap.gradeColor;
                        lk.rttMs = snap == null ? -1 : snap.rttMs;
                        lk.rttPeakMs = snap == null ? -1 : snap.rttPeakMs;
                        lk.hostLatMs = snap == null ? -1 : snap.hostLatMs;
                        lk.dropsPct = snap == null ? -1 : snap.dropsPct;
                        lk.gamesTotal = snap == null ? 0 : snap.gamesTotal;
                        lk.games = snap == null ? null : snap.games;

                        adapter.updateLastSessionByUuid(tgt.uuid, lk.ago, lk.duration, lk.grade,
                                lk.gradeColor, lk.rttMs, lk.rttPeakMs, lk.hostLatMs, lk.dropsPct,
                                lk.gamesTotal, lk.games);

                        // The right-hand column is GameFacts — badge, cover, title, figures
                        // (HostStage.qml:1625). The session NUMBERS above stay in the card, where
                        // the desktop keeps them (HostStage.qml:1218 is inside the card).
                        adapter.updateGameFactsByUuid(tgt.uuid, lk.ago, lk.duration, lk.games);
                    }
                });
            }
        });

        // HOST LINK: STATUS replies with the host NIC speed in Mbps (raw
        // number, e.g. "1000"); formatted exactly like the desktop's
        // formatStreamTweakStatus -> formatMbpsShort ("1000" -> "1 Gbps").
        bridge.requestStatus(tgt.address, new ArtLightBridge.ResponseCallback() {
            @Override
            public void onResult(final String status) {
                final String link = formatMbpsShort(status);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        pollInFlight = false; // cycle complete: allow the next poll even on error replies
                        if (!running) {
                            return;
                        }
                        known(tgt.uuid).hostLink = link;
                        adapter.updateHostLinkByUuid(tgt.uuid, link);
                    }
                });
            }
        });
    }

    /**
     * Desktop formatStreamTweakStatus/formatMbpsShort: "1000" -> "1 Gbps",
     * "600" -> "600 Mbps"; empty/unparseable/ERR -> null (the block hides).
     */
    private static String formatMbpsShort(String raw) {
        if (raw == null || raw.isEmpty() || raw.startsWith("ERR")) {
            return null;
        }
        try {
            long mbps = Long.parseLong(raw.trim());
            if (mbps <= 0) {
                return null;
            }
            if (mbps >= 1000) {
                double gbps = mbps / 1000.0;
                String num = (gbps == Math.floor(gbps))
                        ? String.valueOf((long) gbps) : String.format(java.util.Locale.US, "%.1f", gbps);
                return num + " Gbps";
            }
            return mbps + " Mbps";
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Desktop ComputerModel::requestLastSession reply mapping:
     * has/ago/duration/grade/grade_color/rtt_ms/rtt_peak_ms/host_latency_ms/
     * drops_pct/games_total/games[]. A -1 means the host never measured it and
     * renders as — — never folded to 0. games[].cover is the host's inline
     * thumbnail (base64 PNG "iVBORw0KGgo…" or JPEG "/9j/…"), empty when the
     * host carries none. The desktop prefers its own cached 600x900 art by
     * name; Android keeps no per-host art cache, so the host's inline
     * thumbnail is the parity path here.
     */
    private static LastSessionSnapshot parseLastSession(String json) {
        if (json == null || json.isEmpty() || json.startsWith("ERR")
                || "STATS_UNAVAILABLE".equals(json)) {
            return null;
        }
        try {
            JSONObject o = new JSONObject(json);
            if (!o.optBoolean("has", false)) {
                return null;
            }
            java.util.List<GameCover> games = new java.util.ArrayList<>();
            org.json.JSONArray arr = o.optJSONArray("games");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject g = arr.optJSONObject(i);
                    if (g == null) {
                        continue;
                    }
                    games.add(new GameCover(g.optString("name", ""),
                                            g.optString("cover", "")));
                }
            }
            return new LastSessionSnapshot(
                    o.optString("ago", ""),
                    o.optString("duration", ""),
                    o.optBoolean("has_grade", false) ? o.optString("grade", "") : "",
                    o.optString("grade_color", ""),
                    o.optInt("rtt_ms", -1),
                    o.optInt("rtt_peak_ms", -1),
                    o.optInt("host_latency_ms", -1),
                    o.optDouble("drops_pct", -1),
                    o.optInt("games_total", 0),
                    games);
        } catch (Exception e) {
            LimeLog.info("HostMetricsPoller: lastsession parse failed: " + e.getMessage());
            return null;
        }
    }

    /** Per-poll LAST SESSION values for the hero card's right column. */
    private static final class LastSessionSnapshot {
        final String ago;
        final String duration;
        final String grade;
        final String gradeColor;
        final int rttMs;
        final int rttPeakMs;
        final int hostLatMs;
        final double dropsPct;
        final int gamesTotal;
        final java.util.List<GameCover> games;

        LastSessionSnapshot(String ago, String duration, String grade,
                            String gradeColor, int rttMs, int rttPeakMs,
                            int hostLatMs, double dropsPct, int gamesTotal,
                            java.util.List<GameCover> games) {
            this.ago = ago;
            this.duration = duration;
            this.grade = grade;
            this.gradeColor = gradeColor;
            this.rttMs = rttMs;
            this.rttPeakMs = rttPeakMs;
            this.hostLatMs = hostLatMs;
            this.dropsPct = dropsPct;
            this.gamesTotal = gamesTotal;
            this.games = games;
        }
    }

    /** One game credited in the last session: name + host inline cover (base64). */
    public static final class GameCover {
        public final String name;
        public final String cover;

        GameCover(String name, String cover) {
            this.name = name;
            this.cover = cover;
        }
    }
}
