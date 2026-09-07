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
        // Find the first online host with a usable address. The desktop polls
        // the foreground host; the phone's foreground host is the first card.
        PollTarget target = null;
        for (int i = 0; i < adapter.getCount(); i++) {
            Object o = adapter.getItem(i);
            if (!(o instanceof io.github.onaiaku.artmoon.PcView.ComputerObject)) {
                continue;
            }
            ComputerDetails d = ((io.github.onaiaku.artmoon.PcView.ComputerObject) o).details;
            if (d.state == ComputerDetails.State.ONLINE) {
                target = new PollTarget(d);
                if (target.address != null) {
                    break;
                }
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
                        adapter.updateLastSessionByUuid(
                                tgt.uuid,
                                snap == null ? null : snap.ago,
                                snap == null ? null : snap.duration,
                                snap == null ? null : snap.grade,
                                snap == null ? -1 : snap.rttMs,
                                snap == null ? -1 : snap.hostLatMs,
                                snap == null ? -1 : snap.dropsPct);
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
     * has/ago/duration/grade/rtt_ms/host_latency_ms/drops_pct. A -1 means
     * the host never measured it and renders as \u2014 — never folded to 0.
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
            return new LastSessionSnapshot(
                    o.optString("ago", ""),
                    o.optString("duration", ""),
                    o.optBoolean("has_grade", false) ? o.optString("grade", "") : "",
                    o.optInt("rtt_ms", -1),
                    o.optInt("host_latency_ms", -1),
                    o.optDouble("drops_pct", -1));
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
        final int rttMs;
        final int hostLatMs;
        final double dropsPct;

        LastSessionSnapshot(String ago, String duration, String grade,
                            int rttMs, int hostLatMs, double dropsPct) {
            this.ago = ago;
            this.duration = duration;
            this.grade = grade;
            this.rttMs = rttMs;
            this.hostLatMs = hostLatMs;
            this.dropsPct = dropsPct;
        }
    }
}
