package io.github.onaiaku.artmoon.artlight;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import io.github.onaiaku.artmoon.LimeLog;
import io.github.onaiaku.artmoon.binding.video.MediaCodecDecoderRenderer;
import io.github.onaiaku.artmoon.nvstream.jni.MoonBridge;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * SessionTelemetrySampler — Android port of ArtMoon desktop's sampler.
 *
 * The host never measures the stream; the CLIENT pushes stats. Every second
 * during an active stream this samples the decoder's rolling stats window
 * (plus the bridge RTT estimate), builds one compact JSON batch in the exact
 * shape StreamTweak's parser expects, and pushes it over ArtLightBridge.
 *
 * Wire shape (must match StreamTweakBridge.cs parsing, byte-for-byte):
 *   {"target_fps":120,"target_bitrate_mbps":30.0,"samples":[
 *     {"fps_avg":119.8,"fps_min":117,"drops":3,"rtt_avg":2.3,"rtt_max":4.1,
 *      "jitter_avg":0.4,"jitter_max":0.9,"decode_ms":1.7,"bitrate_mbps":29.8,
 *      "host_latency_avg":5.2,"host_latency_max":7.9}]}
 * No embedded newlines anywhere (bridge protocol requirement).
 */
public class SessionTelemetrySampler {

    private static final long SAMPLE_INTERVAL_MS = 1000;

    private final ArtLightBridge bridge;
    private final String hostAddress;
    private final int targetFps;
    private final int targetBitrateKbps;
    private final MediaCodecDecoderRenderer decoderRenderer;

    private final Handler handler;
    private boolean running;

    // Batch-level running min/max (desktop parity: fps_min, rtt_max,
    // jitter_max, host_latency_max are refined across the whole batch).
    private float batchFpsMin = Float.MAX_VALUE;
    private float batchRttMax = -1.0f;
    private float batchJitterMax = -1.0f;
    private float batchHostLatMax = -1.0f;

    private long lastSampleUptimeMs;

    public SessionTelemetrySampler(android.content.Context context, String hostAddress,
                                   int targetFps, int targetBitrateKbps,
                                   MediaCodecDecoderRenderer decoderRenderer) {
        this.bridge = new ArtLightBridge(context);
        this.hostAddress = hostAddress;
        this.targetFps = targetFps;
        this.targetBitrateKbps = targetBitrateKbps;
        this.decoderRenderer = decoderRenderer;
        this.handler = new Handler(Looper.getMainLooper());
    }

    /** Begin sampling immediately — call from connectionStarted(). */
    public void start() {
        if (running) {
            return;
        }
        running = true;
        batchFpsMin = Float.MAX_VALUE;
        batchRttMax = -1.0f;
        batchJitterMax = -1.0f;
        batchHostLatMax = -1.0f;
        lastSampleUptimeMs = 0;
        LimeLog.info("telemetry sampler start: host=" + hostAddress
                + " targetFps=" + targetFps
                + " targetBitrateKbps=" + targetBitrateKbps);
        handler.post(sampleRunnable);
    }

    /**
     * Final flush + stop — call when the stream ends (stopConnection /
     * connectionTerminated). Emits one last batch with the batch-level
     * min/max applied, then stops ticking.
     */
    public void flushAndStop() {
        if (!running) {
            return;
        }
        running = false;
        handler.removeCallbacks(sampleRunnable);
        // One final sample+send if the window has data; the sampler sends
        // one batch per tick, so this is just a last tick with force=true
        // to bypass the interval spacing.
        tick(true);
        LimeLog.info("telemetry sampler stopped");
    }

    private final Runnable sampleRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            tick(false);
            handler.postDelayed(this, SAMPLE_INTERVAL_MS);
        }
    };

    private void tick(boolean force) {
        long now = SystemClock.uptimeMillis();
        if (!force && lastSampleUptimeMs != 0
                && now - lastSampleUptimeMs < SAMPLE_INTERVAL_MS - 100) {
            return; // interval guard against double-posts
        }
        lastSampleUptimeMs = now;

        if (decoderRenderer == null) {
            return;
        }

        MediaCodecDecoderRenderer.WindowStats ws = decoderRenderer.getLastWindowStats();

        // Desktop parity: skip a window with no rendered frames — including it
        // would corrupt the batch average and pin fps_min to 0 for the batch.
        if (ws.fpsAvg <= 0.0f) {
            return;
        }

        // RTT estimate: high 32 bits = avg, low 32 bits = min (desktop reads
        // avg/max; Moonlight-common packs avg/min here — use avg only for
        // rtt_avg, and track batch max from it).
        long rttInfo = MoonBridge.getEstimatedRttInfo();
        float rttAvgMs = (int) (rttInfo >> 32);
        float jitterMs = (int) rttInfo;

        if (ws.fpsAvg < batchFpsMin) batchFpsMin = ws.fpsAvg;
        if (rttAvgMs > batchRttMax) batchRttMax = rttAvgMs;
        if (jitterMs > batchJitterMax) batchJitterMax = jitterMs;
        if (ws.hostLatencyMaxMs > batchHostLatMax) batchHostLatMax = ws.hostLatencyMaxMs;

        String json = buildBatchJson(ws, rttAvgMs, jitterMs);
        bridge.sendSessionData(hostAddress, json);
    }

    private String buildBatchJson(MediaCodecDecoderRenderer.WindowStats ws,
                                  float rttAvgMs, float jitterMs) {
        try {
            JSONObject s = new JSONObject();
            s.put("fps_avg", round1(ws.fpsAvg));
            s.put("fps_min", (int) Math.min(batchFpsMin, ws.fpsAvg));
            s.put("drops", ws.framesLost);
            s.put("rtt_avg", round1(rttAvgMs));
            s.put("rtt_max", round1(Math.max(batchRttMax, rttAvgMs)));
            s.put("jitter_avg", round1(jitterMs));
            s.put("jitter_max", round1(Math.max(batchJitterMax, jitterMs)));
            s.put("decode_ms", round1(ws.decodeMs));
            s.put("bitrate_mbps", round1(targetBitrateKbps / 1000.0f));
            if (ws.hasHostLatency) {
                s.put("host_latency_avg", round1(ws.hostLatencyAvgMs));
                s.put("host_latency_max", round1(Math.max(batchHostLatMax, ws.hostLatencyMaxMs)));
            }

            JSONArray samples = new JSONArray();
            samples.put(s);

            JSONObject root = new JSONObject();
            root.put("target_fps", targetFps);
            root.put("target_bitrate_mbps",
                    Math.round(targetBitrateKbps / 100.0) / 10.0);
            root.put("samples", samples);

            // Compact serialization — no embedded newlines
            String json = root.toString();
            return json.replace("\n", "");
        } catch (Exception e) {
            LimeLog.info("telemetry: batch build failed: " + e.getMessage());
            return null;
        }
    }

    private static double round1(float v) {
        return Math.round(v * 10) / 10.0;
    }
}
