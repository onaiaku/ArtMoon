package io.github.onaiaku.artmoon.grid;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.ImageView;
import android.widget.AdapterView;
import android.widget.ProgressBar;
import android.widget.TextView;

import io.github.onaiaku.artmoon.AppView;
import io.github.onaiaku.artmoon.LimeLog;
import io.github.onaiaku.artmoon.R;
import io.github.onaiaku.artmoon.apps.AppLibrary;
import io.github.onaiaku.artmoon.grid.assets.CachedAppAssetLoader;
import io.github.onaiaku.artmoon.grid.assets.DiskAssetLoader;
import io.github.onaiaku.artmoon.grid.assets.MemoryAssetLoader;
import io.github.onaiaku.artmoon.grid.assets.NetworkAssetLoader;
import io.github.onaiaku.artmoon.nvstream.http.ComputerDetails;
import io.github.onaiaku.artmoon.nvstream.http.NvApp;
import io.github.onaiaku.artmoon.preferences.PreferenceConfiguration;
import io.github.onaiaku.artmoon.settings.PlaytimeManager;

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

    /** The streaming chip's pulse: 1.0 down to 0.45 and back, as the desktop has it. */
    private static final long STREAM_PULSE_MS = 900;

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

    /*
     * ── The page ────────────────────────────────────────────────────────────────────────────────
     * When a page is set, this adapter draws the library as the desktop draws it: section headings
     * as rows in the same list, so a heading scrolls with the entries it belongs to, and the rows
     * below them carrying a name, a meta line in words, a pin mark and a streaming chip.
     *
     * Until then it draws the flat list it always drew. The two modes exist for one step, so the
     * old list keeps working while the page is wired up underneath it; the flat path goes when
     * AppView hands over a page and nothing else.
     */
    private AppLibrary.Page page;
    private String pageCategory = AppLibrary.CAT_ALL;
    private final ArrayList<Item> items = new ArrayList<>();
    private int selectedPosition = AdapterView.INVALID_POSITION;

    /** One line in the library list: either a section heading or an entry. */
    private static final class Item {
        final String heading;
        final AppLibrary.Row row;

        private Item(String heading, AppLibrary.Row row) {
            this.heading = heading;
            this.row = row;
        }

        static Item heading(String text) {
            return new Item(text, null);
        }

        static Item row(AppLibrary.Row row) {
            return new Item(null, row);
        }
    }

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

    /*
     * ── The page, and what a position means once headings are in the list ────────────────────────
     */

    /**
     * Show a page of the library. Headings are emitted whenever the section changes, and only the
     * FIRST heading in the list loses its rule — a page that opened on a line would read as though
     * something had been cut off above it.
     *
     * <p>The position of a row is no longer its index in the page: headings take positions too, so
     * everything that acts on a row must ask for the row, not count from the top. {@link #rowAt}
     * and {@link #positionOfAppId} are what the click handling and the detail panel use.</p>
     */
    public void setPage(AppLibrary.Page newPage, String category) {
        this.page = newPage;
        this.pageCategory = (category != null) ? category : AppLibrary.CAT_ALL;

        items.clear();
        if (newPage != null) {
            String lastSection = null;
            for (AppLibrary.Row row : newPage.rows) {
                String section = (row.section != null) ? row.section : AppLibrary.SECTION_ALL;
                if (!section.equals(lastSection)) {
                    items.add(Item.heading(headingForSection(section)));
                    lastSection = section;
                }
                items.add(Item.row(row));

                // Fetch the art for the whole page up front, the way the desktop warms its covers.
                // The loader caps what it will hold pending, so a long library just queues.
                loader.queueCacheLoad(row.app);
            }
        }

        notifyDataSetChanged();
    }

    public AppLibrary.Page getPage() {
        return page;
    }

    /** The entry at a list position, or null when that position is a section heading. */
    public AppLibrary.Row rowAt(int position) {
        if (page == null || position < 0 || position >= items.size()) {
            return null;
        }
        return items.get(position).row;
    }

    /** The list position showing a given app, or INVALID_POSITION when it is not on this page. */
    public int positionOfAppId(int appId) {
        if (page == null) {
            return AdapterView.INVALID_POSITION;
        }
        for (int i = 0; i < items.size(); i++) {
            AppLibrary.Row row = items.get(i).row;
            if (row != null && row.appId == appId) {
                return i;
            }
        }
        return AdapterView.INVALID_POSITION;
    }

    /** The position to open on, in the page's own order, for the entry the desktop would pick. */
    public int positionOfFirstRow() {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).row != null) {
                return i;
            }
        }
        return AdapterView.INVALID_POSITION;
    }

    private String headingForSection(String section) {
        if (AppLibrary.SECTION_CONTINUE.equals(section)) {
            return context.getString(R.string.am_sec_last_played);
        }
        if (AppLibrary.SECTION_PINNED.equals(section)) {
            return context.getString(R.string.am_sec_pinned);
        }
        if (AppLibrary.CAT_GAMES.equals(pageCategory)) {
            return context.getString(R.string.am_sec_all_games);
        }
        if (AppLibrary.CAT_APPS.equals(pageCategory)) {
            return context.getString(R.string.am_sec_all_apps);
        }
        return context.getString(R.string.am_sec_all);
    }

    /** Which row the detail panel should be showing, in page positions rather than item positions. */
    public void setSelectedPosition(int position) {
        if (selectedPosition != position) {
            selectedPosition = position;
            notifyDataSetChanged();
        }
    }

    public int getSelectedPosition() {
        return selectedPosition;
    }

    @Override
    public int getCount() {
        return (page != null) ? items.size() : itemList.size();
    }

    @Override
    public Object getItem(int i) {
        return (page != null) ? items.get(i) : itemList.get(i);
    }

    @Override
    public int getViewTypeCount() {
        return (page != null) ? 2 : 1;
    }

    @Override
    public int getItemViewType(int position) {
        if (page == null) {
            return 0;
        }
        return (items.get(position).row != null) ? 1 : 0;
    }

    @Override
    public boolean isEnabled(int position) {
        // A heading is part of the list, not a thing you can open.
        return rowAt(position) != null;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (page == null) {
            View legacy = super.getView(position, convertView, parent);
            // Desktop FocusFrame parity: the focused row wears the accent ring.
            legacy.setActivated(position == focusedPosition);
            return legacy;
        }

        LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        if (rowAt(position) == null) {
            View v = convertView;
            if (v == null || v.findViewById(R.id.am_section_title) == null) {
                v = inflater.inflate(R.layout.app_grid_section, parent, false);
            }
            ((TextView) v.findViewById(R.id.am_section_title)).setText(items.get(position).heading);
            v.findViewById(R.id.am_section_rule).setVisibility((position == 0) ? View.GONE : View.VISIBLE);
            return v;
        }

        View v = convertView;
        if (v == null || v.findViewById(R.id.am_row) == null) {
            v = inflater.inflate(R.layout.app_grid_item, parent, false);
        }
        bindRow(v, position, items.get(position).row);
        return v;
    }

    /**
     * One entry, drawn the desktop's way: the cover, the name, and under it the meta line in words
     * rather than a chip to decode — the store, the hours, and whether it carries settings of its
     * own. Hours are never shown for an app: nothing but a game accumulates them.
     */
    private void bindRow(View v, int position, AppLibrary.Row row) {
        ImageView cover = v.findViewById(R.id.grid_image);
        ProgressBar spinner = v.findViewById(R.id.grid_spinner);
        ImageView overlay = v.findViewById(R.id.grid_overlay);
        TextView name = v.findViewById(R.id.grid_text);
        TextView meta = v.findViewById(R.id.am_row_meta);
        ImageView pin = v.findViewById(R.id.am_row_pin);
        TextView chip = v.findViewById(R.id.am_row_streaming);

        if (loaderTextViewDummy == null) {
            loaderTextViewDummy = new android.widget.TextView(context);
        }
        loader.populateImageView(row.app, cover, loaderTextViewDummy);

        // The row is a list entry, not the poster grid: no spinner (a visible one on every row
        // read as "constantly refreshing"), and the running game is marked by the chip, not by a
        // play badge over its art.
        spinner.setVisibility(View.GONE);
        overlay.setVisibility(View.GONE);

        name.setText(row.name);
        name.setTypeface(null, (position == selectedPosition) ? Typeface.BOLD : Typeface.NORMAL);

        String metaText = metaLineFor(row);
        if (metaText.isEmpty()) {
            meta.setVisibility(View.GONE);
        }
        else {
            meta.setText(metaText);
            meta.setVisibility(View.VISIBLE);
        }

        pin.setVisibility(row.pinned ? View.VISIBLE : View.GONE);
        pin.setAlpha((position == selectedPosition) ? 0.95f : 0.6f);

        // The chip pulses, so the one session that is up is findable without walking the library.
        // A recycled view may still be running the last row's animation, so it is cancelled first
        // and the alpha is reset — otherwise a row that was never streaming would arrive faded.
        Object tag = chip.getTag();
        if (tag instanceof ObjectAnimator) {
            ((ObjectAnimator) tag).cancel();
            chip.setTag(null);
        }
        chip.setAlpha(1.0f);
        if (row.running) {
            ObjectAnimator pulse = ObjectAnimator.ofFloat(chip, "alpha", 1.0f, 0.45f);
            pulse.setDuration(STREAM_PULSE_MS);
            pulse.setRepeatMode(ValueAnimator.REVERSE);
            pulse.setRepeatCount(ValueAnimator.INFINITE);
            pulse.setInterpolator(new AccelerateDecelerateInterpolator());
            chip.setTag(pulse);
            pulse.start();
            chip.setVisibility(View.VISIBLE);
        }
        else {
            chip.setVisibility(View.GONE);
        }

        // A hidden entry stays in the list, dimmed — it is still launchable, and taking it out
        // would leave nothing to un-hide it from.
        v.setAlpha(row.hidden ? 0.45f : 1.0f);

        // Two levels of light: selected is what the panel is showing, activated is where the
        // D-pad is. They are not the same thing and a row can be one without the other.
        v.setSelected(position == selectedPosition);
        v.setActivated(position == focusedPosition);
    }

    private String metaLineFor(AppLibrary.Row row) {
        StringBuilder sb = new StringBuilder();
        String sep = context.getString(R.string.am_meta_sep);

        String store = storeFor(row.name);
        if (store != null && !store.isEmpty()) {
            sb.append(store);
        }

        if (!row.isApp && row.playtime != null && row.playtime.totalSeconds > 0) {
            if (sb.length() > 0) {
                sb.append(sep);
            }
            sb.append(PlaytimeManager.formatDuration(row.playtime.totalSeconds));
        }

        if (row.overridden) {
            if (sb.length() > 0) {
                sb.append(sep);
            }
            sb.append(context.getString(R.string.am_meta_custom_settings));
        }

        return sb.toString();
    }

    /**
     * Landscape detail panel: load the app's box art into the big cover view
     * through the same cached asset pipeline the rows use.
     */
    public void populateCover(NvApp app, ImageView coverView) {
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
     * command via ArtLightBridge) and refresh the meta lines on rows.
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
        items.clear();
        page = null;
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
            parentView.setBackgroundResource(R.drawable.am_card_ambient);
        }
    }
}
