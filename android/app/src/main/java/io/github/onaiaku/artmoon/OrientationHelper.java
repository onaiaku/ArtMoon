package io.github.onaiaku.artmoon;

import android.app.Activity;

/**
 * Orientation policy: ArtMoon is LANDSCAPE, on every device.
 *
 * Nik's call, 12 October 2026 — shown the choice between building a portrait composition and
 * locking the shape, he took the lock: "C but i forsee bug reports coming in, but do it anyway
 * and let them come."
 *
 * There is nothing to port for a portrait one. The desktop's window has minimumWidth 1280
 * (main.qml:155) and its card stops shrinking at 62% of design (HostStage.qml:315), so no desktop
 * has ever drawn this screen narrower than ~825dp. A phone in portrait is 475dp (a Fold, cover
 * screen) or ~411dp — about half of that. What portrait looked like in practice: the shell around
 * the card scaled to 36% of design while the card itself was held at 62%, so the card drew ~1.8x
 * larger than the box holding it — cropped PgUp/PgDn shoulders, the Authorised badge cut down to
 * "Artlight", last-played clipping into the hero, the picture reading as stretched, the stream
 * badges cut off down one side. Locking to landscape removes the whole class of fault instead of
 * making six separate repairs to it.
 *
 * SENSOR_LANDSCAPE and not LANDSCAPE: same shape, but the sensor variant lets the user turn the
 * device over and keeps the app the right way up for them. LANDSCAPE would pin one physical
 * direction and refuse the flip.
 *
 * ⚠️ KNOWN LIMIT — a platform rule, not ours: from Android 12, an app targeting 12+ has its
 * orientation request IGNORED on any display whose smallest width is 600dp or more. The Fold's
 * inner screen is ~704dp, so there the app still follows the device and can be turned to portrait.
 * That is benign in practice — the inner screen is landscape-shaped — but it is worth knowing
 * before a bug report says the lock "doesn't work".
 *
 * TVs are unaffected: a Shield reports a single orientation and never rotates, so landscape is
 * simply what the device already is.
 *
 * Spec: docs/android-ui-spec.md §1 — UPDATED by this. It used to say the app followed the device.
 */
public final class OrientationHelper {

    private OrientationHelper() {}

    /**
     * Call from every ArtMoon activity's onCreate AND from its onConfigurationChanged.
     *
     * Re-applying on every configuration change matters more now than it did when this was
     * "no opinion": it is what releases a lock left behind by a build that pinned something else,
     * and what keeps the shape correct when the app moves between the Fold's two screens.
     */
    public static void applyOrientation(Activity activity) {
        activity.setRequestedOrientation(
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }
}
