package io.github.onaiaku.artmoon.artlight;

import android.content.Context;
import android.util.Base64;

import io.github.onaiaku.artmoon.LimeLog;
import io.github.onaiaku.artmoon.binding.crypto.AndroidCryptoProvider;
import io.github.onaiaku.artmoon.computers.IdentityManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.SocketFactory;

/**
 * ArtLightBridge — Android port of ArtMoon desktop's StreamTweakBridge.
 *
 * Talks to ArtLight/StreamTweak on the host over plain TCP port 47998.
 * Protocol: optional AUTH1 signature line, then the command line, both
 * newline-terminated. The host replies with one line (or several for STATS /
 * APPSTORES JSON payloads) terminated by '\n' or socket close.
 *
 * Commands:
 *   STATUS       — host NIC speed in Mbps (e.g. "1000")
 *   STATS        — host metrics JSON {gpu,gpu_enc,gpu_temp,vram_used,cpu,net_tx}
 *   GAMESTATE    — launch-curtain phase JSON from the host
 *   NETINFO      — host network info
 *   SETSPEED     — client names the target link speed (pre-stream matching)
 *   APPSTORES    — JSON map of app name -> store name ("Cyberpunk 2077":"Steam")
 *   UPDATESTATE  — {"pending":true|false} host updates waiting for reboot
 *   SHUTDOWN(_UPDATE) — destructive; host only honours these from approved
 *                  clients (AUTH1 signature verified). Fire-and-forget.
 *
 * Upstream boundary (locked): this class never touches the streaming path —
 * it is host-integration only, exactly like the desktop's StreamTweakBridge.
 */
public class ArtLightBridge {

    public static final int STREAMTWEAK_PORT = 47998;
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 3000;

    public interface ResponseCallback {
        /** Trimmed response, or "" on error/timeout/unreachable host. */
        void onResult(String response);
    }

    private final Context context;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public ArtLightBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /** Query host NIC speed. Callback receives e.g. "1000" or "". */
    public void requestStatus(String hostAddress, ResponseCallback onResult) {
        sendRequest(hostAddress, "STATUS", onResult);
    }

    /** Query host metrics JSON. Callback receives the JSON line or "". */
    public void requestStats(String hostAddress, ResponseCallback onResult) {
        sendRequest(hostAddress, "STATS", onResult);
    }

    /** Query launch-curtain phase JSON. "" means "no curtain", never "keep waiting". */
    public void requestGameState(String hostAddress, ResponseCallback onResult) {
        sendRequest(hostAddress, "GAMESTATE", onResult);
    }

    /** Query the app -> store map JSON for the library's store badges. */
    public void requestAppStores(String hostAddress, ResponseCallback onResult) {
        sendRequest(hostAddress, "APPSTORES", onResult);
    }

    /** Ask whether the host has updates waiting for a reboot. */
    public void requestUpdateState(String hostAddress, ResponseCallback onResult) {
        sendRequest(hostAddress, "UPDATESTATE", onResult);
    }

    /**
     * Push client-side stream telemetry to the host (desktop parity: the
     * client measures the stream, the host only displays). Wire format is
     * three lines — AUTH1 (signed over the bare "SESSIONDATA" command, like
     * the desktop), the SESSIONDATA command line, then the compact batch
     * JSON from SessionTelemetrySampler. The payload must contain no
     * embedded newlines (bridge protocol). Fire-and-forget; the host's OK
     * reply is discarded.
     */
    public void sendSessionData(String hostAddress, String jsonPayload) {
        final String addr = hostAddress;
        final String payload = jsonPayload;
        executor.execute(new Runnable() {
            @Override
            public void run() {
                Socket socket = null;
                try {
                    socket = SocketFactory.getDefault().createSocket();
                    socket.connect(new InetSocketAddress(addr, STREAMTWEAK_PORT), CONNECT_TIMEOUT_MS);
                    socket.setSoTimeout(READ_TIMEOUT_MS);

                    StringBuilder out = new StringBuilder();
                    String auth = buildAuthLine("SESSIONDATA");
                    if (auth != null) {
                        out.append(auth).append('\n');
                    }
                    out.append("SESSIONDATA").append('\n');
                    out.append(payload).append('\n');

                    OutputStreamWriter writer = new OutputStreamWriter(
                            socket.getOutputStream(), StandardCharsets.UTF_8);
                    writer.write(out.toString());
                    writer.flush();

                    // Drain the OK reply so the host isn't left with an
                    // unread socket; discard it (fire-and-forget semantics).
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    while (reader.readLine() != null) { /* drain */ }
                } catch (Exception e) {
                    LimeLog.info("ArtLightBridge: SESSIONDATA to " + addr + " failed: " + e.getMessage());
                } finally {
                    if (socket != null) {
                        try { socket.close(); } catch (Exception ignored) {}
                    }
                }
            }
        });
    }

    /** Ask the host to power off. Destructive; approved clients only. */
    public void sendShutdown(String hostAddress) {
        sendCommand(hostAddress, "SHUTDOWN");
    }

    /** Ask the host to install pending updates, then power off. Destructive. */
    public void sendShutdownUpdate(String hostAddress) {
        sendCommand(hostAddress, "SHUTDOWN_UPDATE");
    }

    /** Tell the host the link speed we want it to run at (pre-stream matching). */
    public void sendSetSpeed(String hostAddress, long mbps) {
        sendCommand(hostAddress, "SETSPEED " + mbps);
    }

    /**
     * Quick reachability probe used to detect an ArtLight host (port 47998).
     * Invokes with "OK" when an ArtLight host answered STATUS, "" otherwise.
     * Used by the Control-pairing flow after adding a host.
     */
    public void probeArtLightHost(String hostAddress, ResponseCallback onResult) {
        sendRequest(hostAddress, "STATUS", onResult);
    }

    // ── Capability negotiation / enrollment (unauthenticated bootstrap) ─────

    /**
     * CAPS is the one verb that needs no authentication, so it answers even on
     * a host that has never approved this client — exactly the host we're asking
     * about. Reply starts with "CAPS1"; "auth=optional" inside means no approval
     * needed, otherwise the host requires enrollment.
     */
    public void requestCaps(String hostAddress, ResponseCallback onResult) {
        sendRawRequest(hostAddress, new String[]{"CAPS"}, onResult);
    }

    /**
     * ENROLL <uid> <pin> <nameB64> then a second line with the base64 PEM cert.
     * The host shows the PIN to its user to compare; the reply is "ENROLLED"
     * (approved), "PENDING" (waiting for the user to click Allow on the host),
     * or "DENIED".
     */
    public void enroll(String hostAddress, String pin, ResponseCallback onResult) {
        try {
            IdentityManager identity = new IdentityManager(context);
            AndroidCryptoProvider crypto = new AndroidCryptoProvider(context);
            byte[] pem = crypto.getPemEncodedClientCertificate();
            if (pem == null) {
                if (onResult != null) onResult.onResult("");
                return;
            }
            String uid = identity.getUniqueId();
            String nameB64 = Base64.encodeToString(android.os.Build.MODEL.getBytes(StandardCharsets.UTF_8),
                    Base64.NO_WRAP);
            String certB64 = Base64.encodeToString(pem, Base64.NO_WRAP);

            String[] lines = {
                    "ENROLL " + uid + " " + pin + " " + nameB64,
                    certB64
            };
            sendRawRequest(hostAddress, lines, onResult);
        } catch (Exception e) {
            LimeLog.severe("ArtLightBridge: enroll failed: " + e.getMessage());
            if (onResult != null) onResult.onResult("");
        }
    }

    /** Extra queries the ArtLight settings tab will use. */
    public void requestLockState(String hostAddress, ResponseCallback onResult) {
        sendRequest(hostAddress, "LOCKSTATE", onResult);
    }

    public void requestLastSession(String hostAddress, ResponseCallback onResult) {
        sendRequest(hostAddress, "LASTSESSION", onResult);
    }

    /** Raw multi-line request without auth (CAPS/enroll bootstrap). */
    private void sendRawRequest(final String hostAddress, final String[] lines,
                                final ResponseCallback onResult) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                String result = blockingRawRequest(hostAddress, lines);
                if (onResult != null) {
                    onResult.onResult(result);
                }
            }
        });
    }

    private String blockingRawRequest(String hostAddress, String[] lines) {
        Socket socket = null;
        try {
            socket = SocketFactory.getDefault().createSocket();
            socket.connect(new InetSocketAddress(hostAddress, STREAMTWEAK_PORT), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);

            StringBuilder out = new StringBuilder();
            for (String line : lines) {
                out.append(line).append('\n');
            }
            OutputStreamWriter writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
            writer.write(out.toString());
            writer.flush();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (response.length() > 0) {
                    response.append('\n');
                }
                response.append(line);
                if (response.length() > 512 * 1024) {
                    break;
                }
            }
            return response.toString().trim();
        } catch (Exception e) {
            LimeLog.info("ArtLightBridge: raw request to " + hostAddress + " failed: " + e.getMessage());
            return "";
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }
    }

    // ── Protocol ────────────────────────────────────────────────────────────

    /** Fire-and-forget authenticated command. The reply ("OK") is discarded. */
    private void sendCommand(String hostAddress, String command) {
        sendRequest(hostAddress, command, null);
    }

    /**
     * One-shot request: opens its own socket, sends AUTH1 line (if the client
     * identity is ready) + command line, buffers the response until the
     * protocol '\n' terminator or peer close, then fires the callback exactly
     * once. A watchdog guarantees the callback even if the host connects and
     * never replies. Concurrent requests never cross-talk.
     */
    private void sendRequest(final String hostAddress, final String command,
                             final ResponseCallback onResult) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                String result = blockingRequest(hostAddress, command);
                if (onResult != null) {
                    onResult.onResult(result);
                }
            }
        });
    }

    private String blockingRequest(String hostAddress, String command) {
        Socket socket = null;
        try {
            socket = SocketFactory.getDefault().createSocket();
            socket.connect(new InetSocketAddress(hostAddress, STREAMTWEAK_PORT), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);

            StringBuilder out = new StringBuilder();
            String auth = buildAuthLine(command);
            if (auth != null) {
                out.append(auth).append('\n');
            }
            out.append(command).append('\n');

            OutputStreamWriter writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
            writer.write(out.toString());
            writer.flush();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (response.length() > 0) {
                    response.append('\n');
                }
                response.append(line);
                // Single-line replies are complete as soon as we have one line;
                // keep reading for multi-line JSON payloads until close/timeout.
                if (response.length() > 512 * 1024) {
                    break; // sanity cap
                }
            }
            return response.toString().trim();
        } catch (Exception e) {
            LimeLog.info("ArtLightBridge: " + command + " to " + hostAddress + " failed: " + e.getMessage());
            return "";
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * "AUTH1 <uniqueId> <unixMillis> <base64(SHA256withRSA(uid\nts\ncommand))>"
     * — byte-for-byte the desktop's scheme, signed with the same client
     * identity that pairing uses. Returns null if the identity isn't ready
     * (host will treat us as unapproved: queries still work, SHUTDOWN won't).
     */
    private String buildAuthLine(String command) {
        try {
            IdentityManager identity = new IdentityManager(context);
            AndroidCryptoProvider crypto = new AndroidCryptoProvider(context);
            PrivateKey key = crypto.getClientPrivateKey();
            if (key == null) {
                return null;
            }

            String uid = identity.getUniqueId();
            long ts = System.currentTimeMillis();
            byte[] payload = (uid + "\n" + ts + "\n" + command).getBytes(StandardCharsets.UTF_8);

            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(key);
            signer.update(payload);
            byte[] sig = signer.sign();

            return "AUTH1 " + uid + " " + ts + " " +
                    Base64.encodeToString(sig, Base64.NO_WRAP);
        } catch (Exception e) {
            LimeLog.severe("ArtLightBridge: auth line build failed: " + e.getMessage());
            return null;
        }
    }
}
