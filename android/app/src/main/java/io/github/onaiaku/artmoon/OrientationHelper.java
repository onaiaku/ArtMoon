package io.github.onaiaku.artmoon;

import android.app.Activity;

/**
 * Portrait lock for phones only. TVs (Shield) are landscape-native and must
 * never be locked. Spec: docs/android-ui-spec.md §1.
 */
public final class OrientationHelper {

    private OrientationHelper() {}

    /**
     * Call from every ArtMoon activity's onCreate: portrait-locks phones,
     * leaves TV/large-screen devices untouched.
     */
    /**
     * Applies the orientation policy for the CURRENT screen state.
     * Call from onCreate AND from onConfigurationChanged: on foldables the
     * activity survives a cover-screen -> inner-screen transition (the manifest
     * handles configChanges itself), so a portrait lock acquired on the small
     * cover screen must be RELEASED once the large screen appears. TVs and
     * large screens (>= 600dp) rotate freely; phones stay portrait-locked.
     */
    public static void applyOrientation(Activity activity) {
        int uiMode = activity.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_TYPE_MASK;
        boolean isTelevision = uiMode == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
                || activity.getPackageManager().hasSystemFeature("android.software.leanback");

        // Large screens (tablets, foldables unfolded, sw >= 600dp) rotate
        // freely - portrait lock is a phone-only behaviour.
        boolean isLargeScreen = activity.getResources().getConfiguration()
                .smallestScreenWidthDp >= 600;

        if (!isTelevision && !isLargeScreen) {
            activity.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        } else {
            // Release any lock set while the activity lived on a smaller
            // screen (foldable cover display). Without this, the lock from
            // the cover screen survives unfolding and forces portrait on
            // the large display.
            activity.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
    }

    /**
     * Legacy entry point called from onCreate: same policy as applyOrientation.
     * Kept so existing call sites read naturally.
     */
    public static void lockPortraitOnPhones(Activity activity) {
        applyOrientation(activity);
    }
}
