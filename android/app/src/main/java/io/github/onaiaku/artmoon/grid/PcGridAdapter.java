package io.github.onaiaku.artmoon.grid;

import android.content.Context;
import io.github.onaiaku.artmoon.artlight.HostBackgroundManager;
import io.github.onaiaku.artmoon.artlight.HostMetricsPoller.GameCover;
import android.view.View;

import io.github.onaiaku.artmoon.ui.AmActionPill;
import io.github.onaiaku.artmoon.ui.AmBackdropRamp;
import io.github.onaiaku.artmoon.ui.AmScale;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import io.github.onaiaku.artmoon.PcView;
import io.github.onaiaku.artmoon.R;
import io.github.onaiaku.artmoon.nvstream.http.ComputerDetails;
import io.github.onaiaku.artmoon.nvstream.http.PairingManager;
import io.github.onaiaku.artmoon.nvstream.jni.MoonBridge;
import io.github.onaiaku.artmoon.preferences.PreferenceConfiguration;

import java.util.Collections;
import java.util.Comparator;

public class PcGridAdapter extends GenericGridAdapter<PcView.ComputerObject> {

    private final java.util.HashMap<String, android.view.View> boundViews = new java.util.HashMap<>();

    /**
     * D-pad focus tracking, desktop parity: the adapter owns "what has the
     * ring" (like the desktop's focusZone/actionIndex) instead of trusting
     * Android focus state inside the GridView. Painted via the row's
     * activated state, which am_focus_ring shows as the solid accent ring.
     */
    private int focusedPosition = android.widget.AdapterView.INVALID_POSITION;

    public void setFocusedPosition(int pos) {
        if (focusedPosition != pos) {
            focusedPosition = pos;
            notifyDataSetChanged();
        }
    }

    public int getFocusedPosition() {
        return focusedPosition;
    }

    /** Optional hook to the owning PcView so card actions can reuse its flows. */
    private io.github.onaiaku.artmoon.PcView pcView;

    public void setPcView(io.github.onaiaku.artmoon.PcView view) {
        this.pcView = view;
    }

    private HostBackgroundManager bgManager;

    private HostBackgroundManager getBgManager() {
        if (bgManager == null) {
            bgManager = new HostBackgroundManager(context);
        }
        return bgManager;
    }

    public PcGridAdapter(Context context, PreferenceConfiguration prefs) {
        super(context, getLayoutIdForPreferences(prefs));
    }

    /**
     * v10: the hero card must FILL the viewport between the picker row and
     * the footer (approved render: no dead gap below the card). GridView
     * ignores a child's match_parent, so we measure the viewport from the
     * parent and set the row height explicitly on first bind.
     */
    @Override
    public android.view.View getView(int position, android.view.View convertView, android.view.ViewGroup parent) {
        boolean fresh = (convertView == null);
        // getCount() is 1, so `position` is always 0 — the item it stands for is the stage host.
        View v = super.getView(stageHostPosition(), convertView, parent);

        // Ported scale (AppShell.qml:32 — Theme.uiScale = width / 1330). Applied to a FRESH
        // inflation only: a recycled row has already been through it, and scaling twice is
        // exactly how a layout drifts out of agreement with the other platforms.
        if (fresh) {
            // The stage's scale, CLAMPED at 0.62 (HostStage.qml:315) — the card and everything on
            // it stop shrinking with the window at the same width the desktop's do.
            AmScale s = AmScale.ofStage(context);
            AmScale.applyTo(v, s);
            // HostStage.qml:556 — the stage card's radius is _px(16), so it scales with the
            // window like every other number. A shape drawable's radius cannot be expressed in
            // layout, so it is set here on the card's own mutated background.
            View card = v.findViewById(R.id.am_stage_card);
            if (card != null) {
                android.graphics.drawable.Drawable bg = card.getBackground();
                if (bg instanceof android.graphics.drawable.GradientDrawable) {
                    android.graphics.drawable.GradientDrawable g =
                            (android.graphics.drawable.GradientDrawable) bg.mutate();
                    g.setCornerRadius(AmScale.cornerPx(16f, s));
                    card.setBackground(g);
                }
            }
            // The backdrop ramp (HostStage.qml:113-185). A Drawable rather than a shape in the
            // layout because its five stops are a function of the host's colour pair, and because
            // AmScale would have to be taught about it anyway — the version that lived in XML was
            // a VERTICAL scrim, densest at the bottom, which darkened the strip the action row
            // sits in. The desktop's ramp runs along x.
            View ramp = v.findViewById(R.id.am_stage_ramp);
            if (ramp != null) {
                AmBackdropRamp drawable = AmBackdropRamp.noPicture();
                drawable.setAlpha(Math.round(0.90f * 255f));
                ramp.setBackground(drawable);
                // NOTE: bindHeroBackground() re-sets this on every bind, because which recipe is
                // right depends on whether the host has a picture — see the two stops sets in
                // AmBackdropRamp. This is the no-picture default for a freshly inflated row.
            }
            // The action buttons' bodies, built here for the same reason: a radius inside a
            // selector inside a ripple cannot be scaled, and a fixed 28dp radius on a 58dp
            // button is what sliced them down the middle.
            AmActionPill.apply((android.widget.TextView) v.findViewById(R.id.am_action_open), s, false);
            AmActionPill.apply((android.widget.TextView) v.findViewById(R.id.am_action_options), s, false);
            AmActionPill.apply((android.widget.TextView) v.findViewById(R.id.am_panel_resume), s, false);
            AmActionPill.apply((android.widget.TextView) v.findViewById(R.id.am_panel_stop), s, true);
        }
        // NOTE: PcView.paintPadFocus is the SINGLE writer of activated state.
        // The adapter previously painted the row here too, producing a second
        // ring around the whole hero card on top of the pill ring (Nik saw
        // Open and Options 'both selected at once'). Do not re-add.
        //
        // ⚠️ The viewport here is the GridView's CONTENT box, not its bounds. The stage's insets
        // are its padding, and padding is not part of the area a GridView gives an item — so an
        // item set to getHeight() is 32dp taller than it can ever be shown, which is 32dp of
        // silent scroll travel and a card whose bottom edge is below the fold. That was the
        // "still slightly scrollable" and it was also why the action row at the card's foot was
        // the part that disappeared.
        if (parent != null && parent.getHeight() > 0) {
            int viewport = parent.getHeight() - parent.getPaddingTop() - parent.getPaddingBottom();
            if (v.getLayoutParams().height != viewport) {
                android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();
                lp.height = viewport;
                v.setLayoutParams(lp);
            }
        }
        PcView.ComputerObject obj = (PcView.ComputerObject) getItem(position);
        if (obj != null) {
            boundViews.put(obj.details.uuid, v);
        }
        // Bind-time ring: the row is fresh (poll rebinds wipe activated state),
        // so have PcView's single writer repaint NOW, synchronously, before
        // this frame draws. Removes the one-frame dark gap that made the ring
        // flash at poll rhythm and keypresses feel sluggish. (This does NOT
        // re-add adapter-side painting — PcView.paintPadFocus still decides
        // everything; we only call it at the earliest possible moment.)
        if (pcView != null) {
            pcView.syncPadFocusNow();
        }
        return v;
    }

    private static int getLayoutIdForPreferences(PreferenceConfiguration prefs) {
        return R.layout.pc_grid_item;
    }

    public void updateLayoutWithPreferences(Context context, PreferenceConfiguration prefs) {
        // This will trigger the view to reload with the new layout
        setLayoutId(getLayoutIdForPreferences(prefs));
    }


    @Override
    public void clear() {
        boundViews.clear();
        super.clear();
    }

    /** How many hosts the strip can offer. The stage shows one at a time. */
    public int hostCount() {
        return itemList.size();
    }

    /** The host at a strip position. Read-only — the update/remove paths still walk itemList. */
    public PcView.ComputerObject hostAt(int i) {
        return (i >= 0 && i < itemList.size()) ? itemList.get(i) : null;
    }

    /**
     * Which host the stage is showing.
     *
     * HomeScreen.qml fronts ONE stage with the strip (HomeScreen.qml:1975) — there is no list of
     * hosts to scroll, because the tabs are how you change host. The GridView underneath is only
     * the container the stage is drawn in, so it renders exactly one item: the selected host.
     * Without this, swiping down moved to the second host, which no other platform can do.
     */
    private int stageIndex = 0;

    public void setStageIndex(int i) {
        int n = itemList.size();
        if (n == 0) {
            stageIndex = 0;
            return;
        }
        int c = ((i % n) + n) % n;
        if (c != stageIndex) {
            stageIndex = c;
            notifyDataSetChanged();
        }
    }

    public int getStageIndex() {
        return stageIndex;
    }

    /** The itemList position the stage is drawn from. */
    private int stageHostPosition() {
        if (itemList.isEmpty()) {
            return 0;
        }
        if (stageIndex < 0 || stageIndex >= itemList.size()) {
            stageIndex = 0;
        }
        return stageIndex;
    }

    @Override
    public int getCount() {
        return itemList.isEmpty() ? 0 : 1;
    }

    @Override
    public Object getItem(int position) {
        if (itemList.isEmpty()) {
            return null;
        }
        return itemList.get(stageHostPosition());
    }

    public void addComputer(PcView.ComputerObject computer) {
        itemList.add(computer);
        sortList();
    }

    private void sortList() {
        Collections.sort(itemList, new Comparator<PcView.ComputerObject>() {
            @Override
            public int compare(PcView.ComputerObject lhs, PcView.ComputerObject rhs) {
                return lhs.details.name.toLowerCase().compareTo(rhs.details.name.toLowerCase());
            }
        });
    }

    /**
     * The bound row view for the currently focused position (d-pad model):
     * per-row buttons resolve inside it.
     */
    /** Any bound row view carrying the Open button (mid-rebuild fallback). */
    public View getFirstBoundRowWithActions() {
        for (PcView.ComputerObject obj : itemList) {
            View v = boundViews.get(obj.details.uuid);
            if (v != null && v.isAttachedToWindow() && v.findViewById(R.id.am_action_open) != null) {
                return v;
            }
        }
        return null;
    }

    public View getFocusedRowView() {
        // One row on screen, so "the focused row" is the stage host's — resolving through
        // focusedPosition would pick a host that is not drawn (see setStageIndex).
        if (itemList.isEmpty()) {
            return null;
        }
        PcView.ComputerObject obj = itemList.get(stageHostPosition());
        return boundViews.get(obj.details.uuid);
    }

    public View getViewForComputer(String uuid) {
        return boundViews.get(uuid);
    }

    /**
         * v11+: paint the card's LAST SESSION panel (desktop HostStage parity,
         * extended for rtt_peak_ms / grade_color / the games cover strip).
         * ago == null hides the whole panel; a -1 metric renders as \u2014
         * (never folded to 0); an empty grade hides the grade line.
         */
        public void updateLastSessionByUuid(String uuid, String ago, String duration,
                                            String grade, String gradeColor,
                                            int rttMs, int rttPeakMs, int hostLatMs,
                                            double dropsPct, int gamesTotal,
                                            java.util.List<GameCover> games) {
            View v = boundViews.get(uuid);
            if (v == null) {
                return;
            }
            View panel = v.findViewById(R.id.am_last_session);
            if (panel == null) {
                return;
            }
            if (ago == null || ago.isEmpty()) {
                panel.setVisibility(View.GONE);
                return;
            }

            // WHEN — a bordered badge carrying the static label and the age as two halves
            // (HostStage.qml:1273-1292). The age is uppercased in its own view; it used to be
            // concatenated onto the duration, which said how long without saying when.
            bindText(v, R.id.am_last_session_when, context.getString(R.string.am_last_session));
            bindText(v, R.id.am_last_session_ago, ago.toUpperCase(java.util.Locale.getDefault()));

            // GRADE — the one value on this panel that keeps a frame, because it is a verdict
            // and the colour is the point (HostStage.qml:1303-1323): height _px(42) from the
            // layout, radius _px(8) here because it scales, and its colour at 16% behind it.
            TextView gradeTv = v.findViewById(R.id.am_last_session_grade);
            if (gradeTv != null) {
                if (grade == null || grade.isEmpty()) {
                    gradeTv.setVisibility(View.GONE);
                } else {
                    gradeTv.setVisibility(View.VISIBLE);
                    gradeTv.setText(grade);
                    int color = parseColorHex(gradeColor);
                    if (color == 0) {
                        color = context.getResources().getColor(R.color.am_online);
                    }
                    gradeTv.setTextColor(color);
                    io.github.onaiaku.artmoon.ui.AmScale st =
                            io.github.onaiaku.artmoon.ui.AmScale.ofStage(context);
                    android.graphics.drawable.GradientDrawable pill =
                            new android.graphics.drawable.GradientDrawable();
                    pill.setCornerRadius(st.radiusPx(8, context));   // _px(8)
                    pill.setColor((color & 0x00FFFFFF) | 0x29000000); // 16% alpha
                    gradeTv.setBackground(pill);
                }
            }

            // DURATION — plain, no frame: "a bordered box around a plain fact was a second
            // frame this panel did not need" (HostStage.qml:1296-1298).
            bindText(v, R.id.am_last_session_duration, duration == null ? "" : duration);

            // THE THREE NUMBERS — a bare value plus a unit view, so the unit sits on the
            // value's baseline and disappears when the host never measured the figure
            // (HostStage.qml:1345-1391, `visible: modelData.value !== "—"`).
            bindValueUnit(v, R.id.am_last_session_rtt, R.id.am_last_session_rtt_unit, rttMs, 0, "ms");
            bindValueUnit(v, R.id.am_last_session_hostlat, R.id.am_last_session_hostlat_unit,
                    hostLatMs, 1, "ms");
            bindValueUnit(v, R.id.am_last_session_drops, R.id.am_last_session_drops_unit,
                    dropsPct, 1, "%");

            // RTT caption: "11 peak" when the host measured a peak, plain "RTT" when it never
            // did — byte-for-byte the desktop's logic (HostStage.qml:1348-1350).
            TextView rttCap = v.findViewById(R.id.am_last_session_rtt_caption);
            if (rttCap != null) {
                rttCap.setText(rttPeakMs < 0
                        ? context.getString(R.string.am_stat_rtt)
                        : rttPeakMs + " " + context.getString(R.string.am_stat_peak));
            }

            panel.setVisibility(View.VISIBLE);
        }

        /**
         * HostStage.qml:1244-1247 — {@code _num(v, decimals)}. A negative figure is the host
         * saying "never measured", which is NOT the same as zero: a client that printed 0 ms
         * would be inventing a result it was explicitly not given. So the value is an em dash and
         * the unit goes away with it.
         */
        private void bindValueUnit(View v, int valueId, int unitId, double value,
                                   int decimals, String unit) {
            boolean missing = value < 0;
            bindText(v, valueId, missing
                    ? "\u2014"
                    : String.format(java.util.Locale.US, "%." + decimals + "f", value));
            TextView u = v.findViewById(unitId);
            if (u == null) {
                return;
            }
            u.setText(unit);
            u.setVisibility(missing ? View.GONE : View.VISIBLE);
        }

        /** Decodes a host inline cover (base64 PNG/JPEG); cached by payload so the
         *  2-second poll does not re-decode the same thumbnails every cycle. */
        private final java.util.HashMap<String, android.graphics.Bitmap> coverCache =
                new java.util.HashMap<>();

        private android.graphics.Bitmap decodeCover(String b64) {
            if (b64 == null || b64.isEmpty()) {
                return null;
            }
            android.graphics.Bitmap cached = coverCache.get(b64);
            if (cached != null) {
                return cached;
            }
            try {
                byte[] raw = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                android.graphics.Bitmap bmp =
                        android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.length);
                if (bmp != null) {
                    coverCache.put(b64, bmp);
                }
                return bmp;
            } catch (Exception e) {
                return null;
            }
        }

        /** "#RRGGBB" -> int; returns 0 when the string is not a colour. */
        private static int parseColorHex(String s) {
            if (s == null || s.isEmpty() || !s.startsWith("#")) {
                return 0;
            }
            try {
                return android.graphics.Color.parseColor(s);
            } catch (IllegalArgumentException e) {
                return 0;
            }
        }

    /**
     * v11: HOST LINK — the STATUS NIC-speed reply, desktop-formatted
     * ("1000" -> "1 Gbps"). link == null hides the block.
     */
    /**
     * v12: the right-hand GameFacts block — desktop HostStage.qml:1625, `lastPanel`.
     *
     * ⚠️ THREE THINGS: badge, cover, title. The desktop passes `store: ""`, `metaExtra: ""`,
     * `played: ""`, `sessions: 0` at this call site and says why — "Deliberately all empty: title
     * and cover, nothing else" (HostStage.qml:1681-1693). GameFacts' own `visible:` conditions
     * then hide the meta row and the figure row, so binding them here was drawing two rows the
     * desktop deliberately does not draw on this screen.
     *
     * ⚠️ Source. The desktop reads this from a playtime log it keeps on the machine that played
     * (computermodel.cpp:1364 → PlaytimeManager). This client has no such log, so the game named
     * here is the one the HOST's last session credited — the only per-game record reachable from
     * a phone. Same block, same geometry, one different source. The badge's muted half is the
     * playtime TOTAL, which only that local log has, so it is left empty; the desktop draws the
     * badge with its main half alone when `total` is empty.
     *
     * ⚠️ No name, no block. `_hasLastPlayed` is `lastPlayed.name !== undefined && !== ""`
     * (HostStage.qml:215-216), and there is no empty state behind it: "the card simply goes back
     * to what it looks like without one" (1611-1613).
     */
    public void updateGameFactsByUuid(String uuid, String ago, String duration,
                                      java.util.List<GameCover> games) {
        View v = boundViews.get(uuid);
        if (v == null) {
            return;
        }
        final View col = v.findViewById(R.id.am_facts_col);
        if (col == null) {
            return;
        }

        String title = "";
        android.graphics.Bitmap art = null;
        if (games != null && !games.isEmpty() && games.get(0) != null) {
            GameCover gc = games.get(0);
            if (gc.name != null) {
                title = gc.name;
            }
            art = decodeCover(gc.cover);
        }
        if (title.isEmpty()) {
            col.setVisibility(View.GONE);
            hidePanelStack(v);
            return;
        }

        bindText(v, R.id.am_facts_badge_main,
                (ago == null || ago.isEmpty())
                        ? context.getString(R.string.am_last_played)
                        : context.getString(R.string.am_last_played_ago, ago));
        bindText(v, R.id.am_facts_badge_muted, "");
        View badge = v.findViewById(R.id.am_facts_badge);
        if (badge != null) {
            badge.setVisibility(View.VISIBLE);
        }

        bindText(v, R.id.am_facts_title, title);

        final ImageView cover = v.findViewById(R.id.am_facts_cover);
        if (cover != null) {
            if (art != null) {
                cover.setImageBitmap(art);
                cover.setVisibility(View.VISIBLE);
            } else {
                // HeroCover with no source draws nothing. A framed, filled rectangle where the
                // artwork should be is a picture-shaped hole, not a picture.
                cover.setVisibility(View.GONE);
            }
        }

        col.setVisibility(View.VISIBLE);

        // Resume / Play again, under the cover and the title (HostStage.qml:1694-1896). It is
        // reachable exactly when the block is: with no game named there is nothing to play
        // again, and the desktop's `_panelActionReachable` says the same. The desktop also
        // draws Stop under it WHILE SOMETHING IS STREAMING — Android's poller does not carry a
        // running-session flag yet, so that half stays hidden rather than being guessed at.
        View stack = v.findViewById(R.id.am_panel_action_stack);
        if (stack != null) {
            stack.setVisibility(View.VISIBLE);
        }

        // coverHeight (GameFacts.qml:147-156): whatever the panel has left once the block's own
        // text has taken its share, floored at _px(90). Two passes, because the text's height is
        // only real after layout has run.
        final ImageView cv = cover;
        if (cv != null) {
            col.post(new Runnable() {
                @Override
                public void run() {
                    View panel = (View) col.getParent();
                    if (panel == null || panel.getHeight() <= 0 || cv.getHeight() <= 0
                            || cv.getVisibility() != View.VISIBLE) {
                        return;
                    }
                    int others = col.getHeight() - cv.getHeight();
                    io.github.onaiaku.artmoon.ui.AmScale st =
                            io.github.onaiaku.artmoon.ui.AmScale.ofStage(context);
                    // ⚠️ THE BUTTON'S ROOM COMES OUT OF THE BLOCK, NOT OUT OF THE CARD, and
                    // the block ends where the button BEGINS. The button is pinned to the
                    // floor, so the room it gives up is its own height PLUS the margin it sits
                    // above that floor by, PLUS the gap the desktop leaves between the two:
                    //
                    //   HostStage.qml:1649
                    //     availableHeight: panelActionStack.y - y - stage._px(16)
                    //
                    // `y` there is the block's own top, which here is the column's
                    // layout_marginTop. Reserving only the stack's height — and not its 30dp
                    // bottom margin, and not the _px(16) — left the cover about 55px too tall,
                    // and the room came out of the last thing under it. Nik, 12 October, with
                    // the two boxed on the Fold: "the play again button is still cutting into
                    // the text". Measured on the screen: the title's descenders and the pill's
                    // top edge were on the same pixel row.
                    int colTop = st.lengthPx(30f, context);            // am_facts_col marginTop
                    int stackH = 0;
                    View stack = panel.findViewById(R.id.am_panel_action_stack);
                    if (stack != null && stack.getVisibility() == View.VISIBLE) {
                        stackH = stack.getHeight();
                    }
                    int stackBottom = st.lengthPx(30f, context);       // the stack's own margin
                    int gap = st.lengthPx(16f, context);               // HostStage.qml:1649
                    int available =
                            panel.getHeight() - colTop - stackH - stackBottom - gap;
                    int room = available - others;
                    int h = Math.max(st.lengthPx(90f, context), room);
                    // 2:3, and capped by the column it lives in — GameFacts.qml:150-156 does the
                    // same in the other direction (its height is capped by `width * 3 / 2`), and
                    // HeroCover derives the width from the height. A cover stretched to the full
                    // width of the panel is a different shape from the one every other platform
                    // draws.
                    int w = Math.round(h * 2f / 3f);
                    int maxW = col.getWidth() - col.getPaddingLeft() - col.getPaddingRight();
                    if (maxW > 0) {
                        w = Math.min(w, maxW);
                    }
                    if (cv.getHeight() != h || cv.getWidth() != w) {
                        android.view.ViewGroup.LayoutParams lp = cv.getLayoutParams();
                        lp.height = h;
                        lp.width = w;
                        cv.setLayoutParams(lp);
                    }
                }
            });
        }
    }

    /**
     * The panel pair goes with the block it belongs to. With no game named there is nothing to
     * play again (HostStage.qml:1694, `_panelActionReachable`), and a button standing under
     * nothing is worse than no button.
     */
    private void hidePanelStack(View row) {
        View stack = row.findViewById(R.id.am_panel_action_stack);
        if (stack != null) {
            stack.setVisibility(View.GONE);
        }
    }

    public void updateHostLinkByUuid(String uuid, String link) {
        View v = boundViews.get(uuid);
        if (v == null) {
            return;
        }
        // The stage carries ONE HOST LINK, in the card's field row (HostStage.qml:1121). The
        // second copy that used to sit in the right-hand column duplicated it on screen and
        // does not exist on any other platform, so it is gone and this binds the one that does.
        View box = v.findViewById(R.id.am_hostlink_block);
        if (box == null) {
            return;
        }
        if (link == null) {
            box.setVisibility(View.GONE);
            return;
        }
        TextView tv = v.findViewById(R.id.am_hostlink_value);
        if (tv != null) {
            tv.setText(link);
        }
        box.setVisibility(View.VISIBLE);
    }

    /**
     * v11: ArtLight Authorised chip (desktop auth badge parity). State comes
     * from HostAuthManager — none/open/pending/authorized/denied. The chip
     * shows only for authorized (green) and pending (amber); every other
     * state hides it.
     */
    public void updateAuthStateByUuid(String uuid, String state) {
        View v = boundViews.get(uuid);
        if (v == null) {
            return;
        }
        View chip = v.findViewById(R.id.am_auth_chip);
        if (chip == null) {
            return;
        }
        boolean authorized = "authorized".equals(state);
        boolean pending = "pending".equals(state);
        if (!authorized && !pending) {
            chip.setVisibility(View.GONE);
            return;
        }
        TextView label = v.findViewById(R.id.am_auth_label);
        if (label != null) {
            label.setText(authorized ? R.string.am_hero_auth : R.string.am_auth_pending);
        }
        chip.setVisibility(View.VISIBLE);
    }

    private static void bindText(View parent, int viewId, String value) {
        TextView tv = parent.findViewById(viewId);
        if (tv != null) {
            tv.setText(value);
        }
    }

    public boolean removeComputer(PcView.ComputerObject computer) {
        return itemList.remove(computer);
    }

    @Override
    public void populateView(View parentView, ImageView imgView, ProgressBar prgView, TextView txtView, ImageView overlayView, PcView.ComputerObject obj) {
        // Upstream Moonlight's monitor glyph removed — the ArtMoon hero card
        // has no icon well in its design. GONE so it leaves no gap; the
        // offline overlay and pairing spinner in the same well stay visible
        // when they have something to say.
        imgView.setVisibility(View.GONE);

        // Per-PC hero background (long-press to set/remove). Full-bleed
        // behind the scrim; falls back to the gradient when none is set.
        ImageView heroBg = parentView.findViewById(R.id.am_hero_bg_image);
        if (heroBg != null) {
            android.graphics.Bitmap bgBmp = getBgManager().load(obj.details.uuid);
            if (bgBmp != null) {
                heroBg.setImageBitmap(bgBmp);
                heroBg.setVisibility(View.VISIBLE);
            } else {
                heroBg.setImageDrawable(null);
                heroBg.setVisibility(View.GONE);
            }

            // ⚠️ THE LAYER OVER THE CARD CHANGES WITH THE PICTURE, and this is the only place
            // that knows which case we are in. Two different recipes, both from HostStage.qml:
            //
            //   no picture   the baked ramp, thinned by backdropOpacity (0.90)
            //   a picture    the SCRIM — dense on the left where the name and the fields are,
            //                fully transparent at the right edge so the artwork is not washed
            //                out (HostStage.qml:674-695). The ramp is NOT drawn over a picture;
            //                there it is only what shows while the art loads.
            //
            // Painting the baked ramp over the user's own artwork at 90% is what made Nik's
            // background invisible: "ive just added a bakground and can hardly see it" — and
            // the desktop has the case separated for exactly that reason.
            View ramp = parentView.findViewById(R.id.am_stage_ramp);
            if (ramp != null) {
                if (bgBmp != null) {
                    ramp.setBackground(AmBackdropRamp.overPicture());
                } else {
                    AmBackdropRamp drawable = AmBackdropRamp.noPicture();
                    drawable.setAlpha(Math.round(0.90f * 255f));
                    ramp.setBackground(drawable);
                }
            }

            parentView.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    if (pcView != null) {
                        pcView.showHeroBackgroundMenu(obj.details.uuid);
                    }
                    return true;
                }
            });
        }

        // ArtMoon status dot + label, ported from the desktop's host card:
        // online green, pairing amber, offline red — always semantic, never the accent.
        View statusDot = parentView.findViewById(R.id.status_dot);
        TextView statusLabel = parentView.findViewById(R.id.status_label);

        if (obj.details.state == ComputerDetails.State.ONLINE) {
            imgView.setAlpha(1.0f);
            if (statusDot != null) statusDot.setBackgroundResource(R.drawable.am_dot_online);
            if (statusLabel != null) {
                statusLabel.setText(obj.details.pairState == PairingManager.PairState.NOT_PAIRED
                        ? R.string.am_status_unpaired : R.string.am_status_online);
                statusLabel.setTextColor(context.getResources().getColor(R.color.am_online));
            }
        }
        else if (obj.details.state == ComputerDetails.State.UNKNOWN) {
            imgView.setAlpha(0.6f);
            if (statusDot != null) statusDot.setBackgroundResource(R.drawable.am_dot_pairing);
            if (statusLabel != null) {
                statusLabel.setText(R.string.am_status_checking);
                statusLabel.setTextColor(context.getResources().getColor(R.color.am_pairing));
            }
        }
        else {
            imgView.setAlpha(0.4f);
            if (statusDot != null) statusDot.setBackgroundResource(R.drawable.am_dot_offline);
            if (statusLabel != null) {
                statusLabel.setText(R.string.am_status_offline);
                statusLabel.setTextColor(context.getResources().getColor(R.color.am_offline));
            }
        }

        // Hero card: ready line + spec chips (approved render, spec section 2)
        TextView readyLine = parentView.findViewById(R.id.am_ready_line);
        if (readyLine != null) {
            if (obj.details.state == ComputerDetails.State.ONLINE) {
                readyLine.setText(R.string.am_hero_ready);
                readyLine.setVisibility(View.VISIBLE);
            } else if (obj.details.state == ComputerDetails.State.OFFLINE) {
                readyLine.setText(R.string.am_hero_offline);
                readyLine.setVisibility(View.VISIBLE);
            } else {
                readyLine.setVisibility(View.GONE);
            }
        }

        TextView specRes = parentView.findViewById(R.id.am_spec_res);
        TextView specFps = parentView.findViewById(R.id.am_spec_fps);
        TextView specBitrate = parentView.findViewById(R.id.am_spec_bitrate);
        TextView specCodec = parentView.findViewById(R.id.am_spec_codec);
        TextView specAudio = parentView.findViewById(R.id.am_spec_audio);
        boolean online = obj.details.state == ComputerDetails.State.ONLINE;
        if (specRes != null && specFps != null && specBitrate != null) {
            if (online) {
                PreferenceConfiguration prefs = PreferenceConfiguration.readPreferences(context);
                specRes.setText(prefs.width + "\u00d7" + prefs.height);
                specFps.setText(prefs.fps + " FPS");
                specBitrate.setText((prefs.bitrate + 999) / 1000 + " Mbps");
                if (specCodec != null) {
                    specCodec.setText(codecLabel(prefs.videoFormat));
                }
                if (specAudio != null) {
                    specAudio.setText(audioLabel(prefs.audioConfiguration));
                }
                TextView[] chips = {specRes, specFps, specBitrate, specCodec, specAudio};
                for (TextView c : chips) {
                    if (c != null) c.setVisibility(View.VISIBLE);
                }
            } else {
                TextView[] chips = {specRes, specFps, specBitrate, specCodec, specAudio};
                for (TextView c : chips) {
                    if (c != null) c.setVisibility(View.GONE);
                }
            }
        }

        if (obj.details.state == ComputerDetails.State.UNKNOWN) {
            prgView.setVisibility(View.VISIBLE);
        }
        else {
            prgView.setVisibility(View.INVISIBLE);
        }

        // v10: bind the Open/Options action row to the same flows as the
        // grid tap (paired -> app list, unpaired -> pairing, offline/unknown
        // -> context menu). The hero card's buttons must never dead-end.
        if (pcView != null) {
            pcView.bindHeroCardActions(parentView, obj);
        }

        txtView.setText(obj.details.name);
        if (obj.details.state == ComputerDetails.State.ONLINE) {
            txtView.setAlpha(1.0f);
        }
        else {
            txtView.setAlpha(0.4f);
        }

        if (obj.details.state == ComputerDetails.State.OFFLINE) {
            overlayView.setImageResource(R.drawable.ic_pc_offline);
            overlayView.setAlpha(0.4f);
            overlayView.setVisibility(View.VISIBLE);
        }
        // We must check if the status is exactly online and unpaired
        // to avoid colliding with the loading spinner when status is unknown
        else if (obj.details.state == ComputerDetails.State.ONLINE &&
                obj.details.pairState == PairingManager.PairState.NOT_PAIRED) {
            overlayView.setImageResource(R.drawable.ic_lock);
            overlayView.setAlpha(1.0f);
            overlayView.setVisibility(View.VISIBLE);
        }
        else {
            overlayView.setVisibility(View.GONE);
        }
    }
    private static String codecLabel(PreferenceConfiguration.FormatOption fmt) {
        if (fmt == PreferenceConfiguration.FormatOption.FORCE_AV1) return "AV1";
        if (fmt == PreferenceConfiguration.FormatOption.FORCE_HEVC) return "HEVC";
        if (fmt == PreferenceConfiguration.FormatOption.FORCE_H264) return "H.264";
        return "Auto";
    }

    private static String audioLabel(MoonBridge.AudioConfiguration audio) {
        if (audio == MoonBridge.AUDIO_CONFIGURATION_51_SURROUND) return "5.1";
        if (audio == MoonBridge.AUDIO_CONFIGURATION_71_SURROUND) return "7.1";
        return "Stereo";
    }
}
