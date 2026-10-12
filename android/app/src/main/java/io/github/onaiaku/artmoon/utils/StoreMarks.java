package io.github.onaiaku.artmoon.utils;

import io.github.onaiaku.artmoon.R;

/**
 * The storefront's mark, looked up by the name the host gave — the desktop's own map, ported name
 * for name (GameFacts.qml:63-72, {@code storeIconSource}).
 *
 * ⚠️ One place, deliberately. On the desktop the map lives in GameFacts because both Home and the
 * picker draw the same badge, and a second copy had already gone missing once — the spotlight lost
 * its icon when it was rebuilt. Here the picker draws it today and the main screen's card will; the
 * lookup lives outside both so they cannot drift.
 *
 * An unknown store gets 0 and no mark is drawn, which is the desktop's rule as well: it returns ""
 * for a name it does not know, and a wrong logo is worse than none.
 */
public final class StoreMarks {
    private StoreMarks() {}

    public static int drawableFor(String store) {
        if (store == null) {
            return 0;
        }

        switch (store) {
            case "Steam":
                return R.drawable.am_store_steam;
            case "Epic Games":
                return R.drawable.am_store_epic;
            case "GOG":
                return R.drawable.am_store_gog;
            case "Ubisoft Connect":
                return R.drawable.am_store_ubisoft;
            case "Xbox":
                return R.drawable.am_store_xbox;
            case "Battle.net":
                return R.drawable.am_store_battlenet;
            case "EA App":
                return R.drawable.am_store_ea;
            default:
                return 0;
        }
    }
}
