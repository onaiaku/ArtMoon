package io.github.onaiaku.artmoon.artlight;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import io.github.onaiaku.artmoon.LimeLog;

/**
 * HostAuthManager — Android port of the desktop's enrollment flow
 * (ComputerModel's CAPS probe + ENROLL + PIN lifecycle).
 *
 * States reported per host via Listener:
 *   "none"       — not an ArtLight host (no CAPS1 reply)
 *   "open"       — ArtLight host with auth=optional (works without approval)
 *   "authorized" — enrolled and approved
 *   "pending"    — waiting for the user to click Allow on the host; PIN shown
 *   "denied"     — host user declined; a later retry generates a fresh PIN
 */
public class HostAuthManager {

    public interface Listener {
        void onAuthState(String uuid, String state, String pin);
    }

    private static final long RETRY_MS = 3000;
    private static final long MAX_RETRY_MS = 15000;

    private final ArtLightBridge bridge;
    private final android.content.SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final java.util.HashMap<String, String> pinsByUuid = new java.util.HashMap<>();
    private final java.util.HashMap<String, Listener> listeners = new java.util.HashMap<>();

    private static final String PREFS_NAME = "artlight_auth";
    private static final String KEY_EVER_AUTHORIZED = "ever_authorized_uuids";

    public HostAuthManager(Context context) {
        this.bridge = new ArtLightBridge(context);
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * True once this host has ever approved us (ENROLLED). Persisted so the
     * silent background re-probe (desktop parity with HomeScreen.qml's access
     * poller) only ever targets hosts we've paired with before — hosts we've
     * never paired stay untouched, so no host is ever shown an unprompted
     * "allow this device?" request.
     */
    public boolean isEverAuthorized(String uuid) {
        java.util.Set<String> set = prefs.getStringSet(KEY_EVER_AUTHORIZED, null);
        return set != null && set.contains(uuid);
    }

    private void markEverAuthorized(String uuid) {
        java.util.Set<String> set = new java.util.HashSet<>(
                prefs.getStringSet(KEY_EVER_AUTHORIZED, new java.util.HashSet<String>()));
        if (set.add(uuid)) {
            prefs.edit().putStringSet(KEY_EVER_AUTHORIZED, set).apply();
        }
    }

    public void setListener(String uuid, Listener listener) {
        listeners.put(uuid, listener);
    }

    public void clearListener(String uuid) {
        listeners.remove(uuid);
    }

    public String getPin(String uuid) {
        return pinsByUuid.get(uuid);
    }

    /** User explicitly re-requested access after dismissing the popup. */
    public void forceRetry(String uuid) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                String pin = pinsByUuid.get(uuid);
                if (pin != null) {
                    notifyState(uuid, "pending", pin);
                }
            }
        });
    }

    public void probe(final String uuid, final String address) {
        bridge.requestCaps(address, new ArtLightBridge.ResponseCallback() {
            @Override
            public void onResult(String caps) {
                if (caps == null || !caps.startsWith("CAPS1")) {
                    notifyState(uuid, "none", null);
                    return;
                }
                if (caps.contains("auth=optional")) {
                    pinsByUuid.remove(uuid);
                    notifyState(uuid, "open", null);
                    return;
                }
                // auth required → enroll with a stable PIN while pending
                String pin = pinsByUuid.get(uuid);
                if (pin == null || pin.isEmpty()) {
                    pin = String.format("%04d", (int) (Math.random() * 10000));
                    pinsByUuid.put(uuid, pin);
                }
                enrollWithRetries(uuid, address, pin, RETRY_MS);
            }
        });
    }

    /**
     * Enrollment poll loop. Desktop parity (HomeScreen.qml): the access probe
     * runs "every 2.5 s until it settles" — NO attempt cap. If we stopped
     * after a handful of tries, a host approval that lands later than the cap
     * would never deliver ENROLLED and the PIN popup would hang open forever.
     * Backoff is gentle (3 s -> 15 s ceiling) and the loop only stops on an
     * outcome: ENROLLED, DENIED, or a host that stops answering.
     */
    private void enrollWithRetries(final String uuid, final String address,
                                   final String pin, final long delayMs) {
        bridge.enroll(address, pin, new ArtLightBridge.ResponseCallback() {
            @Override
            public void onResult(String reply) {
                if ("ENROLLED".equals(reply)) {
                    pinsByUuid.remove(uuid);
                    markEverAuthorized(uuid);
                    notifyState(uuid, "authorized", null);
                } else if ("PENDING".equals(reply)) {
                    notifyState(uuid, "pending", pin);
                    final long next = Math.min(delayMs * 2, MAX_RETRY_MS);
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            enrollWithRetries(uuid, address, pin, next);
                        }
                    }, delayMs);
                } else if ("DENIED".equals(reply)) {
                    // PIN matters only while pending; drop it so a later
                    // re-request starts fresh (desktop parity).
                    pinsByUuid.remove(uuid);
                    notifyState(uuid, "denied", null);
                } else {
                    notifyState(uuid, "none", null);
                }
            }
        });
    }

    private void notifyState(final String uuid, final String state, final String pin) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                Listener l = listeners.get(uuid);
                if (l != null) {
                    l.onAuthState(uuid, state, pin);
                }
            }
        });
    }
}
