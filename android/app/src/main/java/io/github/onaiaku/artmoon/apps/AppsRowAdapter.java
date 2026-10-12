package io.github.onaiaku.artmoon.apps;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.BitmapFactory;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.onaiaku.artmoon.R;
import io.github.onaiaku.artmoon.grid.assets.CachedAppAssetLoader;
import io.github.onaiaku.artmoon.grid.assets.DiskAssetLoader;
import io.github.onaiaku.artmoon.grid.assets.MemoryAssetLoader;
import io.github.onaiaku.artmoon.grid.assets.NetworkAssetLoader;
import io.github.onaiaku.artmoon.nvstream.http.ComputerDetails;
import io.github.onaiaku.artmoon.settings.PlaytimeManager;

/**
 * The picker's library, drawn as the desktop draws it (AppsScreen.qml §12 and §13).
 *
 * <p>Two kinds of line: a heading, and a row under it. Headings are list items rather than something
 * pinned above the list, because that is the desktop's rule and it was learned the hard way there —
 * an anchored "LAST PLAYED" stayed put while the row it belonged to scrolled off underneath it.
 *
 * <p>Row fields it never guesses at: whether the entry is running, whether it is hidden, whether it
 * is pinned, and what this client has played on it — all of those are decided by {@link AppLibrary}
 * and handed here.
 */
public class AppsRowAdapter extends BaseAdapter {

    private static final int TYPE_ROW = 0;
    private static final int TYPE_SECTION = 1;

    /** One line of the list. */
    public static final class Item {
        public final AppLibrary.Row row;
        public final String label;
        public final boolean accent;
        public final boolean firstHeading;

        Item(AppLibrary.Row row, String label, boolean accent, boolean firstHeading) {
            this.row = row;
            this.label = label;
            this.accent = accent;
            this.firstHeading = firstHeading;
        }

        boolean isHeading() {
            return row == null;
        }
    }

    private final Context context;
    private final LayoutInflater inflater;
    private final CachedAppAssetLoader loader;
    private final List<Item> items = new ArrayList<Item>();
    private final Map<String, String> stores = new HashMap<String, String>();
    private final Map<View, ValueAnimator> pulses = new HashMap<View, ValueAnimator>();

    private int focused = -1;
    private boolean listFocused = false;

    public AppsRowAdapter(Context context, ComputerDetails computer, String uniqueId) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
        this.loader = new CachedAppAssetLoader(computer, 1.0,
                new NetworkAssetLoader(context, uniqueId),
                new MemoryAssetLoader(),
                new DiskAssetLoader(context),
                BitmapFactory.decodeResource(context.getResources(), R.drawable.no_app_image));
    }

    /** Whichever store each entry came from, in words. Filled in when the host answers. */
    public void setStores(Map<String, String> map) {
        stores.clear();
        if (map != null) {
            stores.putAll(map);
        }
        notifyDataSetChanged();
    }

    public void setFocused(int position) {
        this.focused = position;
        notifyDataSetChanged();
    }

    public void setListHasFocus(boolean focused) {
        this.listFocused = focused;
        notifyDataSetChanged();
    }

    public int positionOfAppId(int appId) {
        return AppLibrary.indexOfAppId(rows(), appId);
    }

    public AppLibrary.Row rowAt(int position) {
        if (position < 0 || position >= items.size()) {
            return null;
        }
        return items.get(position).row;
    }

    private List<AppLibrary.Row> rows() {
        List<AppLibrary.Row> out = new ArrayList<AppLibrary.Row>();
        for (Item item : items) {
            if (!item.isHeading()) {
                out.add(item.row);
            }
        }
        return out;
    }

    /**
     * Takes a built page and lays it out: a heading for every change of section, then its rows.
     *
     * @param category the tab in front of the user, which names the last section
     */
    public void setPage(AppLibrary.Page page, String category) {
        items.clear();
        String lastSection = null;
        boolean first = true;
        if (page != null) {
            for (AppLibrary.Row row : page.rows) {
                if (lastSection == null || !lastSection.equals(row.section)) {
                    items.add(new Item(null, headingFor(row.section, category),
                            AppLibrary.SECTION_CONTINUE.equals(row.section), first));
                    first = false;
                    lastSection = row.section;
                }
                items.add(new Item(row, null, false, false));
            }
        }
        if (focused >= items.size()) {
            focused = items.isEmpty() ? -1 : 0;
        }
        notifyDataSetChanged();
    }

    private static String headingFor(String section, String category) {
        if (AppLibrary.SECTION_CONTINUE.equals(section)) {
            return "Last played";
        }
        if (AppLibrary.SECTION_PINNED.equals(section)) {
            return "Pinned";
        }
        if (AppLibrary.CAT_GAMES.equals(category)) {
            return "All games";
        }
        if (AppLibrary.CAT_APPS.equals(category)) {
            return "All apps";
        }
        return "All";
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public Object getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public int getViewTypeCount() {
        return 2;
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position).isHeading() ? TYPE_SECTION : TYPE_ROW;
    }

    @Override
    public boolean areAllItemsEnabled() {
        return false;
    }

    @Override
    public boolean isEnabled(int position) {
        return !items.get(position).isHeading();
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Item item = items.get(position);
        return item.isHeading() ? heading(item, convertView, parent) : row(item, convertView, parent);
    }

    private View heading(Item item, View convertView, ViewGroup parent) {
        View v = convertView;
        if (v == null) {
            v = inflater.inflate(R.layout.apps_section, parent, false);
        }
        TextView title = (TextView) v.findViewById(R.id.am_sec_title);
        title.setText(item.label);
        // LAST PLAYED is the one heading in the accent colour — it is where the cursor lands.
        title.setTextColor(context.getResources().getColor(
                item.accent ? R.color.am_accent : R.color.am_text2));
        // The first heading in the list gets no rule above it; every later one does.
        v.findViewById(R.id.am_sec_rule).setVisibility(item.firstHeading ? View.GONE : View.VISIBLE);
        return v;
    }

    private View row(Item item, View convertView, ViewGroup parent) {
        View v = convertView;
        if (v == null) {
            v = inflater.inflate(R.layout.apps_row, parent, false);
        }
        AppLibrary.Row row = item.row;

        TextView title = (TextView) v.findViewById(R.id.am_row_title);
        TextView meta = (TextView) v.findViewById(R.id.am_row_meta);
        TextView pin = (TextView) v.findViewById(R.id.am_row_pin);
        TextView tag = (TextView) v.findViewById(R.id.am_row_tag);
        ImageView cover = (ImageView) v.findViewById(R.id.am_row_cover);

        title.setText(row.name);
        // DemiBold when it is the one under the cursor, plain otherwise (the desktop's own weight
        // change, and the only one the eye needs on this column).
        title.setTypeface(null, isLit(item) ? android.graphics.Typeface.BOLD
                : android.graphics.Typeface.NORMAL);

        meta.setText(metaLine(row));
        meta.setVisibility(meta.getText().length() == 0 ? View.GONE : View.VISIBLE);

        pin.setVisibility(row.pinned ? View.VISIBLE : View.GONE);
        pin.setAlpha(isLit(item) ? 0.95f : 0.6f);

        // The session may be held by a game far down the list, not only by the one in the
        // spotlight, so the pill is on the row itself and it pulses there (900 ms, §13).
        if (row.running) {
            tag.setVisibility(View.VISIBLE);
            startPulse(tag);
        }
        else {
            tag.setVisibility(View.GONE);
            stopPulse(tag);
        }

        // Hidden entries are drawn, faintly — the page shows what the host will still launch.
        v.setAlpha(row.hidden ? 0.45f : 1.0f);

        // Selected is not the same as focused: a dialog on top takes the focus away while the
        // spotlight still shows this game, so the row keeps a quiet marker and lights only when the
        // list itself has the focus.
        if (isLit(item)) {
            v.setBackgroundResource(R.drawable.am_row_lit);
        }
        else if (items.indexOf(item) == focused) {
            v.setBackgroundResource(R.drawable.am_row_quiet);
        }
        else {
            v.setBackgroundResource(android.R.color.transparent);
        }

        loader.populateImageView(row.app, cover, null);
        return v;
    }

    private boolean isLit(Item item) {
        return listFocused && items.indexOf(item) == focused;
    }

    /**
     * The meta line: the store in words — never a badge you have to learn — then what has been
     * played on it, then whether it carries settings of its own. Nothing at all when there is
     * nothing to say.
     */
    private String metaLine(AppLibrary.Row row) {
        List<String> parts = new ArrayList<String>();

        String store = stores.get(row.name);
        if (store != null && !store.isEmpty()) {
            parts.add(store);
        }

        // Never on an app: the hours belong to the game, and an entry that is not the game must not
        // wear its figures.
        if (!row.isApp && row.playtime != null && row.playtime.totalSeconds > 0) {
            parts.add(PlaytimeManager.formatDuration(row.playtime.totalSeconds) + " played");
        }

        if (row.overridden) {
            parts.add(context.getString(R.string.am_meta_custom_settings));
        }

        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) {
                sb.append("  ·  ");
            }
            sb.append(part);
        }
        return sb.toString();
    }

    private void startPulse(final View tag) {
        if (pulses.containsKey(tag)) {
            return;
        }
        ValueAnimator anim = ValueAnimator.ofFloat(1.0f, 0.45f);
        anim.setDuration(900);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setRepeatMode(ValueAnimator.REVERSE);
        anim.setInterpolator(new AccelerateDecelerateInterpolator());
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                tag.setAlpha((Float) animation.getAnimatedValue());
            }
        });
        pulses.put(tag, anim);
        anim.start();
    }

    private void stopPulse(View tag) {
        ValueAnimator anim = pulses.remove(tag);
        if (anim != null) {
            anim.cancel();
            tag.setAlpha(1.0f);
        }
    }

    /** Nothing should keep ticking once the page is gone. */
    public void cancelPulses() {
        for (ValueAnimator anim : pulses.values()) {
            anim.cancel();
        }
        pulses.clear();
        loader.cancelBackgroundLoads();
        loader.cancelForegroundLoads();
    }
}
