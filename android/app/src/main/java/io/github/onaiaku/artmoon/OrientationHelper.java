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
    public static void lockPortraitOnPhones(Activity activity) {
        int uiMode = activity.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_TYPE_MASK;
        boolean isTelevision = uiMode == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
                || activity.getPackageManager().hasSystemFeature("android.software.leanback");

        // Large screens (tablets, sw >= 600dp) rotate freely - portrait lock is
        // a phone-only behaviour. Spec: docs/android-ui-spec.md section 1.
        boolean isLargeScreen = activity.getResources().getConfiguration()
                .smallestScreenWidthDp >= 600;

        if (!isTelevision && !isLargeScreen) {
            activity.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
    }
}
