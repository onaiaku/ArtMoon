package io.github.onaiaku.artmoon.grid;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.AdapterView;
import android.widget.ProgressBar;
import android.widget.TextView;

import io.github.onaiaku.artmoon.AppView;
import io.github.onaiaku.artmoon.LimeLog;
import io.github.onaiaku.artmoon.R;
import io.github.onaiaku.artmoon.grid.assets.CachedAppAssetLoader;
import io.github.onaiaku.artmoon.grid.assets.DiskAssetLoader;
import io.github.onaiaku.artmoon.grid.assets.MemoryAssetLoader;
import io.github.onaiaku.artmoon.grid.assets.NetworkAssetLoader;
import io.github.onaiaku.artmoon.nvstream.http.ComputerDetails;
import io.github.onaiaku.artmoon.preferences.PreferenceConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@SuppressWarnings("unchecked")
public class AppGridAdapter extends GenericGridAdapter<AppView.AppObject> {
    private static final int ART_WIDTH_PX = 300;
    private static final int SMALL_WIDTH_DP = 100;
    private static final int LARGE_WIDTH_DP = 150;

    private final ComputerDetails computer;
    private final String uniqueId;
    private final boolean showHiddenApps;

    private CachedAppAssetLoader loader;
    private android.widget.TextView loaderTextViewDummy;
    // Landscape master-detail: the row currently shown in the detail panel.
    private AppView.AppObject selectedApp;
    private Set<Integer> hiddenAppIds = new HashSet<>();
    private ArrayList<AppView.AppObject> allApps = new ArrayList<>();
    private java.util.HashMap<String, String> storeMap = new java.util.HashMap<>();

    public AppGridAdapter(Context context, PreferenceConfiguration prefs, ComputerDetails computer, String uniqueId, boolean showHiddenApps) {
        super(context, getLayoutIdForPreferences(prefs));

        this.computer = computer;
        this.uniqueId = uniqueId;
        this.showHiddenApps = showHiddenApps;

        updateLayoutWithPreferences(context, prefs);
    }

    public void updateHiddenApps(Set<Integer> newHiddenAppIds, boolean hideImmediately) {
        this.hiddenAppIds.clear();
        this.hiddenAppIds.addAll(newHiddenAppIds);

        if (hideImmediately) {
            // Reconstruct the itemList with the new hidden app set
            itemList.clear();
            for (AppView.AppObject app : allApps) {
                app.isHidden = hiddenAppIds.contains(app.app.getAppId());

                if (!app.isHidden || showHiddenApps) {
                    itemList.add(app);
                }
            }
        }
        else {
            // Just update the isHidden state to show the correct UI indication
            for (AppView.AppObject app : allApps) {
                app.isHidden = hiddenAppIds.contains(app.app.getAppId());
            }
        }

        notifyDataSetChanged();
    }

    private static int getLayoutIdForPreferences(PreferenceConfiguration prefs) {
        if (prefs.smallIconMode) {
            return R.layout.app_grid_item_small;
        }
        else {
            return R.layout.app_grid_item;
        }
    }

    public void updateLayoutWithPreferences(Context context, PreferenceConfiguration prefs) {
        int dpi = context.getResources().getDisplayMetrics().densityDpi;
        int dp;

        if (prefs.smallIconMode) {
            dp = SMALL_WIDTH_DP;
        }
        else {
            dp = LARGE_WIDTH_DP;
        }

        double scalingDivisor = ART_WIDTH_PX / (dp * (dpi / 160.0));
        if (scalingDivisor < 1.0) {
            // We don't want to make them bigger before draw-time
            scalingDivisor = 1.0;
        }
        LimeLog.info("Art scaling divisor: " + scalingDivisor);

        if (loader != null) {
            // Cancel operations on the old loader
            cancelQueuedOperations();
        }

        this.loader = new CachedAppAssetLoader(computer, scalingDivisor,
                new NetworkAssetLoader(context, uniqueId),
                new MemoryAssetLoader(),
                new DiskAssetLoader(context),
                BitmapFactory.decodeResource(context.getResources(), R.drawable.no_app_image));

        // This will trigger the view to reload with the new layout
        setLayoutId(getLayoutIdForPreferences(prefs));
    }

    public void setSelectedApp(AppView.AppObject app) {
        this.selectedApp = app;
    }

    /**
     * D-pad focus tracking, desktop FocusFrame parity: the ring follows the
     * focused row via the activated state (set in getView), independent of
     * the selection band that follows the picked app.
     */
    private int focusedPosition = AdapterView.INVALID_POSITION;

    public void setFocusedPosition(int pos) {
        if (focusedPosition != pos) {
            focusedPosition = pos;
            notifyDataSetChanged();
        }
    }

    public int getFocusedPosition() {
        return focusedPosition;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View v = super.getView(position, convertView, parent);
        // Desktop FocusFrame parity: the focused row wears the accent ring.
        v.setActivated(position == focusedPosition);
        return v;
    }

    /**
     * Landscape detail panel: load the app's box art into the big cover view
     * through the same cached asset pipeline the rows use.
     */
    public void populateCover(io.github.onaiaku.artmoon.nvstream.http.NvApp app, ImageView coverView) {
        if (loaderTextViewDummy == null) {
            loaderTextViewDummy = new android.widget.TextView(context);
        }
        loader.populateImageView(app, coverView, loaderTextViewDummy);
    }

    public void cancelQueuedOperations() {
        loader.cancelForegroundLoads();
        loader.cancelBackgroundLoads();
        loader.freeCacheMemory();
    }

    /**
     * ArtLight integration: receive the host's app -> store map (APPSTORES
     * command via ArtLightBridge) and refresh the badge chips on rows.
     */
    public void setStoreMap(java.util.HashMap<String, String> map) {
        this.storeMap = (map != null) ? map : new java.util.HashMap<>();
        notifyDataSetChanged();
    }

    private String storeFor(String appName) {
        return storeMap.get(appName);
    }

    private static void sortList(List<AppView.AppObject> list) {
        Collections.sort(list, new Comparator<AppView.AppObject>() {
            @Override
            public int compare(AppView.AppObject lhs, AppView.AppObject rhs) {
                return lhs.app.getAppName().toLowerCase().compareTo(rhs.app.getAppName().toLowerCase());
            }
        });
    }

    public void addApp(AppView.AppObject app) {
        // Update hidden state
        app.isHidden = hiddenAppIds.contains(app.app.getAppId());

        // Always add the app to the all apps list
        allApps.add(app);
        sortList(allApps);

        // Add the app to the adapter data if it's not hidden
        if (showHiddenApps || !app.isHidden) {
            // Queue a request to fetch this bitmap into cache
            loader.queueCacheLoad(app.app);

            // Add the app to our sorted list
            itemList.add(app);
            sortList(itemList);
        }
    }

    public void removeApp(AppView.AppObject app) {
        itemList.remove(app);
        allApps.remove(app);
    }

    @Override
    public void clear() {
        super.clear();
        allApps.clear();
    }

    @Override
    public void populateView(View parentView, ImageView imgView, ProgressBar prgView, TextView txtView, ImageView overlayView, AppView.AppObject obj) {
        // Let the cached asset loader handle the art, then FORCE the row's own
        // name visible. The shared loader was built for the TV poster grid, where
        // the name only shows when art falls back to a placeholder — in this list
        // row (thumb + title beside it) the title must ALWAYS show, whatever the
        // art state, and the indeterminate spinner must never linger: a visible
        // spinner on every row read as "constantly refreshing each app".
        // Give the loader a detached dummy TextView so its visibility dance
        // (INVISIBLE while loading, GONE once real box art lands) can't touch the
        // row's real title. The async completion used to fire AFTER this method and
        // GONE the title — which is why names only appeared after scrolling away
        // and back (rebind from memory cache, no late callback). The dummy is never
        // attached to any layout, so the loader writes into the void.
        if (loaderTextViewDummy == null) {
            loaderTextViewDummy = new android.widget.TextView(parentView.getContext());
        }
        loader.populateImageView(obj.app, imgView, loaderTextViewDummy);
        prgView.setVisibility(View.GONE);
        txtView.setText(obj.app.getAppName());
        txtView.setVisibility(View.VISIBLE);

        // ArtLight store badge (Steam / Epic / …) when the host reported one
        TextView storeBadge = parentView.findViewById(R.id.am_store_badge);
        if (storeBadge != null) {
            String store = storeFor(obj.app.getAppName());
            if (store != null && !store.isEmpty()) {
                storeBadge.setText(store);
                storeBadge.setVisibility(View.VISIBLE);
            } else {
                storeBadge.setVisibility(View.GONE);
            }
        }

        if (obj.isRunning) {
            // Show the play button overlay
            overlayView.setImageResource(R.drawable.ic_play);
            overlayView.setVisibility(View.VISIBLE);
        }
        else {
            overlayView.setVisibility(View.GONE);
        }

        if (obj.isHidden) {
            parentView.setAlpha(0.40f);
        }
        else {
            parentView.setAlpha(1.0f);
        }

        // Landscape master-detail: the row feeding the detail panel gets the
        // blue selection glow from the approved render; every other row stays
        // on the plain card background.
        if (selectedApp != null && obj == selectedApp) {
            parentView.setBackgroundResource(R.drawable.am_pick_row_sel);
        } else {
            parentView.setBackgroundResource(R.drawable.am_card);
        }
    }
}
