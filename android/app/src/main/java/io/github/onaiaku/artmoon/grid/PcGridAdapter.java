package io.github.onaiaku.artmoon.grid;

import android.content.Context;
import io.github.onaiaku.artmoon.artlight.HostBackgroundManager;
import io.github.onaiaku.artmoon.artlight.HostMetricsPoller.GameCover;
import android.view.View;
import android.view.ViewGroup;

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
            // The stage's scale: width / 1330, and deliberately NOT clamped at 0.62 the way
            // HostStage.qml:315 is. See AmScale.ofStage — the desktop's floor cannot fire (its
            // window stops at 1280 wide, 0.96 of the design width), while on a phone it was the
            // card drawn ~1.8x too big for its own box. One rule for the shell and the card.
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

            // Paint what this host last told us, now that there is a view to paint it into.
            //
            // A screen change rebuilds the whole tree and makes a NEW adapter with empty caches,
            // so without this the card came up blank and stayed blank until the poller's next
            // round trip landed — the "dag and a age" Nik saw on every rotate and unfold. The
            // values come from the process-level cache rather than the network, so they are on
            // screen in this frame; the next poll corrects them ~2s later. Silent for a host that
            // has never reported, so an unknown host still shows its own empty state.
            io.github.onaiaku.artmoon.artlight.HostMetricsPoller.applyCachedTo(this, obj.details.uuid);
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
     * The last session's game name, per host — the name the card's block shows, and therefore the
     * one the panel's Play again starts (the desktop's resumeLastPlayed looks the game up BY NAME
     * in the host's own list). Kept here rather than read back off the card because the cover strip
     * is built in code and has no ids to read; the button has to be able to ask at the moment it is
     * pressed, long after the poll that painted the block.
     */
    /**
     * What this CLIENT last played on this host — from the record kept on this machine
     * ({@link io.github.onaiaku.artmoon.settings.PlaytimeManager}), never from the host.
     *
     * <p>&#9888; This is the whole point of the class it replaced. The block used to be named from
     * the host's last session ({@code LASTSESSION} over the ArtLight bridge), which only our own
     * stack answers — so on a plain Sunshine, Apollo or Vibepollo host the block never appeared at
     * all, and on our own hosts it named whatever the host last ran, possibly another device's
     * session. The desktop hit exactly this and fixed it in 5.6.1 (HostStage.qml:201-216): "Nothing
     * here comes from the host ... It now describes what YOU played, from a record kept on this
     * machine, and works against a plain Sunshine host."</p>
     *
     * <p>The host's own session numbers are a SEPARATE fact with a separate owner: they are the
     * card-interior LAST SESSION panel, painted by {@link #updateLastSessionByUuid} and still
     * ArtLight-only. Two facts, both true at once, and neither standing in for the other.</p>
     */
    private static final class LastPlayed {
        final String name;
        final android.graphics.Bitmap cover;
        final String total;   // the playtime TOTAL — the badge's muted half
        final String ago;

        LastPlayed(String name, android.graphics.Bitmap cover, String total, String ago) {
            this.name = name;
            this.cover = cover;
            this.total = total;
            this.ago = ago;
        }
    }

    private final java.util.HashMap<String, LastPlayed> lastPlayedByUuid = new java.util.HashMap<>();

    /**
     * The host's own display name. The placeholder line names it — "Your last game on %1$s will
     * show up here" (HostStage.qml:1948) — and it has to be readable on a host where nothing has
     * been played yet, which is exactly when there is no record to carry it.
     */
    private final java.util.HashMap<String, String> hostNameByUuid = new java.util.HashMap<>();

    /**
     * Tells the block what this client last played here. An empty name clears it and the block
     * goes back to the placeholder — the desktop's rule: no name, no block, and no empty state
     * behind it (HostStage.qml:215-216, 1611-1613), but a placeholder in front of it.
     */
    public void setLastPlayedByUuid(String uuid, String hostName, String name,
                                    android.graphics.Bitmap cover, String total, String ago) {
        if (uuid == null) {
            return;
        }
        if (hostName != null && !hostName.isEmpty()) {
            hostNameByUuid.put(uuid, hostName);
        }
        if (name == null || name.isEmpty()) {
            lastPlayedByUuid.remove(uuid);
        } else {
            lastPlayedByUuid.put(uuid, new LastPlayed(name, cover, total, ago));
        }
        refreshGameFacts(uuid);
    }

    /** The shared placeholder art, decoded once — R.drawable.no_app_image, as the app rows use. */
    private android.graphics.Bitmap noAppImage;

    private android.graphics.Bitmap noAppImage() {
        if (noAppImage == null) {
            noAppImage = android.graphics.BitmapFactory.decodeResource(
                    context.getResources(), R.drawable.no_app_image);
        }
        return noAppImage;
    }

    /** The game the card names for this host, or null when nothing has been played here. */
    public String lastGameNameFor(String uuid) {
        LastPlayed lp = uuid == null ? null : lastPlayedByUuid.get(uuid);
        return lp == null ? null : lp.name;
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
            // ⚠️ No "which game" work here any more, deliberately. This panel is the HOST's last
            // session and nothing else; what game the block names comes from this client's own
            // record and is pushed in by setLastPlayedByUuid(). The two used to share this method,
            // which is how a host's session list came to name the card.
            View v = boundViews.get(uuid);
            if (v == null) {
                return;
            }
            View panel = v.findViewById(R.id.am_last_session);
            if (panel == null) {
                return;
            }
            if (ago == null || ago.isEmpty()) {
                // Clear any clamp before hiding, or a recycled panel comes back shifted.
                panel.setTranslationY(0f);
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

            // ── THE CLAMP (HostStage.qml:1237) ────────────────────────────────────────────────
            // The desktop keeps this panel whole AND keeps it off the buttons, and it does it in
            // one line:
            //
            //     y: Math.min(_idealY, actionRow.y - height - stage._px(20))
            //
            // `_idealY` is "directly under the fields" (:1229), which is where the layout already
            // puts it — 28dp below the block above. The min() is the half that only matters when
            // the card is short, and the desktop is explicit about why it exists (:1230-1236):
            //
            //     "The fields and the buttons can be close enough that the ideal offset puts this
            //      panel under Enter/Profiles/Options, and a doubled-up grid is worse than a panel
            //      sitting a little high — so the panel gives up the gap before it gives up the
            //      buttons. On a card with room, the min() is never taken."
            //
            // A "doubled-up grid" is exactly what Nik saw twice: the three stat cells landing on
            // the Open/Options row. The port had the ideal offset and never had the clamp, so the
            // panel had nowhere to go but under the buttons — and the column it lives in clips.
            //
            // Android has no absolute y to clamp, but it has the same two facts: the column is
            // what clips, and its bottom edge is already the strip the action row owns plus the
            // desktop's own _px(20) — 15 + 58 + 20 = 93dp, see the layout. So measuring the panel
            // against that edge IS `actionRow.y - _px(20)`, and anything past it is the doubled-up
            // case: the panel comes up by exactly the overflow, giving up the gap above it.
            //
            // Reset first. Views are recycled, and the panel's own height is only known after a
            // layout pass — which is why this is a LAYOUT listener and not a one-shot post (see
            // the ⚠️ note below).
            final View col = (panel.getParent() instanceof View) ? (View) panel.getParent() : null;
            panel.setTranslationY(0f);
            if (col != null) {
                if (panel.getTag(R.id.am_last_session) == null) {
                    // ⚠️ THE CLAMP MUST RE-RUN ON EVERY LAYOUT, NOT ONCE AFTER BIND.
                    // The first version of this posted a single runnable. It measured a panel that
                    // had not yet been laid out at its new height — a recycled row still carries
                    // the PREVIOUS host's geometry, which was smaller and fitted — so `over` came
                    // out 0, nothing moved, and the numbers went back on top of the buttons. Nik:
                    // "didnt change, from last time still clipping into the buttons the same
                    // amount". A layout-change listener has no such gap: it fires with the real
                    // bounds whenever the panel's own height changes, including the first time.
                    panel.setTag(R.id.am_last_session, Boolean.TRUE);
                    panel.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                        @Override
                        public void onLayoutChange(View v, int l, int t, int r, int b,
                                                   int ol, int ot, int orr, int ob) {
                            int over = b - col.getHeight();
                            v.setTranslationY(over > 0 ? -over : 0f);
                        }
                    });
                } else {
                    // Already listening — clamp against the geometry we have now; the listener
                    // corrects it the moment the layout settles.
                    int over = panel.getBottom() - col.getHeight();
                    panel.setTranslationY(over > 0 ? -over : 0f);
                }
            }
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
    /* ── What the host is streaming, and what it last played ───────────────────────────────
     *
     * HostStage.qml:1616: "Every field below picks between the two, and only those fields: the
     * geometry is one, so the running session is sized, scaled and aligned by exactly the rules
     * that were already tuned for Last played." The block shows the running session when there is
     * one and the last game when there is not (_hasPanel: _hasRunning || _hasLastPlayed), and the
     * ONLY fields that change are the badge's words and its dot, the title, and the cover.
     */
    private static final class RunningGame {
        final int gameId;
        final String name;
        final android.graphics.Bitmap cover;

        RunningGame(int gameId, String name, android.graphics.Bitmap cover) {
            this.gameId = gameId;
            this.name = name;
            this.cover = cover;
        }
    }

    private final java.util.HashMap<String, RunningGame> runningGames = new java.util.HashMap<>();

    /** True while the host has a session running — the same gate the label and Stop ride. */
    public boolean isRunning(String uuid) {
        RunningGame rg = uuid == null ? null : runningGames.get(uuid);
        return rg != null && rg.gameId != 0;
    }

    /** The running game we already know about, so a bind does not re-fetch what we have. */
    public int runningGameIdFor(String uuid) {
        RunningGame rg = uuid == null ? null : runningGames.get(uuid);
        return rg == null ? 0 : rg.gameId;
    }

    /** Name AND cover in hand for this session — the desktop's runningApp is both or neither. */
    public boolean hasRunningGameInfo(String uuid, int gameId) {
        RunningGame rg = uuid == null ? null : runningGames.get(uuid);
        return rg != null && rg.gameId == gameId
                && rg.name != null && rg.cover != null;
    }

    /**
     * Tell the block what the host is streaming. gameId 0 clears it and the block goes back to the
     * last game — the desktop's own fallback order. It repaints at once from the facts we were last
     * given, so the block does not sit on the last game until the metrics poll happens to catch up
     * with a session Nik can already see running on the PC.
     */
    public void setRunningGameByUuid(String uuid, int gameId, String name,
                                     android.graphics.Bitmap cover) {
        if (uuid == null) {
            return;
        }
        if (gameId == 0) {
            runningGames.remove(uuid);
        } else {
            runningGames.put(uuid, new RunningGame(gameId, name, cover));
        }
        refreshGameFacts(uuid);
    }

    /**
     * The badge's live dot. Theme.accent while a session runs and not drawn at all when one does
     * not (HostStage.qml:1666 — badgeDot is "transparent"), pulsing 1.0 <-> 0.45 over 900ms each
     * way with an easing in and out, which is the rhythm of the host page's STREAMING tag
     * (AppsScreen.qml:1655-1665). It holds still when the system has animations switched off, and
     * a recycled card's pulse is cancelled before a new one starts, so one animator rides one card.
     */
    private void paintLiveDot(View card, boolean running) {
        View dot = card.findViewById(R.id.am_facts_badge_dot);
        if (dot == null) {
            return;
        }
        Object previous = dot.getTag(R.id.am_facts_badge_dot);
        if (previous instanceof android.animation.Animator) {
            ((android.animation.Animator) previous).cancel();
            dot.setTag(R.id.am_facts_badge_dot, null);
        }
        dot.setAlpha(1f);
        dot.setVisibility(running ? View.VISIBLE : View.GONE);
        if (!running) {
            return;
        }
        float scale = android.provider.Settings.Global.getFloat(context.getContentResolver(),
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        if (scale == 0f) {
            return;
        }
        android.animation.ObjectAnimator pulse =
                android.animation.ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.45f);
        pulse.setDuration(900);
        pulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        pulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        pulse.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        pulse.start();
        dot.setTag(R.id.am_facts_badge_dot, pulse);
    }

    /**
     * The state before the first game on this host (HostStage.qml:1898-1957): the badge the block
     * will carry, and one line naming the host, centred where the block will stand.
     *
     * <p>&#9888; It is deliberately NOT an outline of the block. The desktop built that first — the
     * cover's exact box, dashed, with "Your last game" at title size — and its own note calls the
     * result "the ugliest thing on the screen: the largest object on that side, holding nothing, so
     * the eye went to it and found a hole."</p>
     */
    private void showLastPlayedHint(View card, String uuid) {
        View hint = card.findViewById(R.id.am_facts_hint);
        if (hint == null) {
            return;
        }
        bindText(card, R.id.am_facts_hint_badge_text, context.getString(R.string.am_last_played));
        String host = uuid == null ? null : hostNameByUuid.get(uuid);
        bindText(card, R.id.am_facts_hint_text,
                context.getString(R.string.am_last_played_hint,
                        host == null || host.isEmpty() ? "" : host));
        hint.setVisibility(View.VISIBLE);
    }

    private void hideLastPlayedHint(View card) {
        View hint = card.findViewById(R.id.am_facts_hint);
        if (hint != null) {
            hint.setVisibility(View.GONE);
        }
    }

    /**
     * Repaints the right-hand block from what it already knows — the running session, and the game
     * this client last played here. No arguments: the facts are held, so a repaint never re-reads a
     * poll's payload, and the block cannot be named by something the host happened to say.
     */
    public void refreshGameFacts(String uuid) {
        View v = boundViews.get(uuid);
        if (v == null) {
            return;
        }
        final View col = v.findViewById(R.id.am_facts_col);
        if (col == null) {
            return;
        }

        final RunningGame rg = uuid == null ? null : runningGames.get(uuid);
        final boolean running = rg != null && rg.gameId != 0;

        final LastPlayed lp = uuid == null ? null : lastPlayedByUuid.get(uuid);

        String title = "";
        android.graphics.Bitmap art = null;
        if (running) {
            // The session, not the last game. The badge below says "Streaming now" and the title
            // and the artwork are the running app's (HostStage.qml:1671-1681).
            title = rg.name == null ? "" : rg.name;
            art = rg.cover;
        } else if (lp != null) {
            // What YOU played here, from the record on this machine — not the host's session.
            title = lp.name;
            art = lp.cover;
        }
        // No name, no block (HostStage.qml:215-216, 1611-1613) — except while a session is
        // running: the badge names the state before the host's list has told us the game's name,
        // so the block stays up rather than blinking out for the second the lookup takes.
        if (!running && title.isEmpty()) {
            // Before the first game. No block — but NOT nothing: one badge and one quiet line
            // where the block will stand, level with the middle of the card (HostStage.qml:1898-
            // 1957). Hiding the column and leaving the space bare is what made the right of the
            // card read as broken.
            col.setVisibility(View.GONE);
            hidePanelStack(v);
            showLastPlayedHint(v, uuid);
            return;
        }
        hideLastPlayedHint(v);

        bindText(v, R.id.am_facts_badge_main,
                running ? context.getString(R.string.am_streaming_now)
                        : (lp == null || lp.ago == null || lp.ago.isEmpty()
                           ? context.getString(R.string.am_last_played)
                           : context.getString(R.string.am_last_played_ago, lp.ago)));
        // No figure beside it while running: "the host does not say how long the session has run,
        // nor whose it is" (HostStage.qml:1663-1665 — badgeMuted is empty).
        //
        // Otherwise the muted half is the playtime TOTAL, which only the local record has — it was
        // left empty while the block was named by the host, because a host's session list has no
        // such number. The desktop draws the badge with its main half alone when `total` is empty.
        bindText(v, R.id.am_facts_badge_muted,
                (running || lp == null || lp.total == null) ? "" : lp.total);
        View badge = v.findViewById(R.id.am_facts_badge);
        if (badge != null) {
            badge.setVisibility(View.VISIBLE);
        }
        paintLiveDot(v, running);

        bindText(v, R.id.am_facts_title, title);

        final ImageView cover = v.findViewById(R.id.am_facts_cover);
        if (cover != null) {
            // The picture, or the shared placeholder — never a hole. That is the desktop's rule for
            // BOTH halves of this block, in its own words: "the cache only, never a fetch, and the
            // shared placeholder when the picture is not on disk" (computermodel.cpp:1476-1481, and
            // the same sentence at :1411-1417 for the last-played record). This block already
            // followed the first half; it answered the second by hiding the view, and a hidden view
            // is the one thing the desktop never draws there. no_app_image.png is the placeholder
            // "every other screen uses" — the app rows have always drawn it (AppsRowAdapter:85).
            //
            // ⚠️ Nik, 12 Oct 2026: "sometimes its just blank (showing no art) until i go to the app
            // picker and go back". The blank was this line; the delay was the app list, and the
            // service now fills that from disk (ComputerManagerService.appListFromCache).
            cover.setImageBitmap(art != null ? art : noAppImage());
            cover.setVisibility(View.VISIBLE);
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

        // ── The status line and its stream badges (HostStage.qml:1038-1103) ─────────────────
        // ONE row, and the desktop says why in as many words: a version that wrapped the badges
        // onto a second line "was tried first and turned down: this is one statement, and it
        // reads as one only on one line."
        //
        // These are what the next launch would look like, in the same shapes and the same words
        // as the host page, because this is the screen you are on when you decide whether to
        // press A.
        TextView specRes = parentView.findViewById(R.id.am_spec_res);
        TextView specFps = parentView.findViewById(R.id.am_spec_fps);
        TextView specBitrate = parentView.findViewById(R.id.am_spec_bitrate);
        TextView specHdr = parentView.findViewById(R.id.am_spec_hdr);
        TextView specCodec = parentView.findViewById(R.id.am_spec_codec);
        TextView specAudio = parentView.findViewById(R.id.am_spec_audio);
        View sublRow = parentView.findViewById(R.id.am_subl_row);
        boolean online = obj.details.state == ComputerDetails.State.ONLINE;
        boolean paired = obj.details.pairState == PairingManager.PairState.PAIRED;

        if (sublRow != null && specRes != null && specFps != null && specBitrate != null) {
            // Only worth stating for a host that can actually be streamed to — which is also
            // what keeps this row short, since every long status line belongs to a state with no
            // settings to show.
            //
            // ⚠️ The desktop's condition is `online && paired && serverSupported`
            // (HostStage.qml:1067). Android's host list records no server version, so
            // online && paired is as far as the data goes here: a host too old to stream would
            // still show its badges. That is a poller gap, not a rendering one.
            PreferenceConfiguration prefs = (online && paired)
                    ? PreferenceConfiguration.readPreferences(context) : null;

            if (prefs != null) {
                // The desktop's own labels, not the raw values: _resLabel() gives "1080p" where
                // the port printed "1920x1080", and the bitrate rounds rather than always
                // rounding up. Resolution and fps are the same two numbers either way.
                specRes.setText(resLabel(prefs.width, prefs.height));
                specFps.setText(prefs.fps + " FPS");
                specBitrate.setText(Math.round(prefs.bitrate / 1000f) + " Mbps");
                specCodec.setText(codecLabel(prefs.videoFormat));
                specAudio.setText(audioLabel(prefs.audioConfiguration));
                // HDR is pushed into the row only when it is on (HostStage.qml:1072-1073), so it
                // appears BETWEEN the bitrate and the codec rather than at the end.
                //
                // ⚠️ Its label is the only chip text that is not set here — it lives on the view
                // in the layout (@string/am_spec_hdr, "HDR", the desktop's own literal). That is
                // not a style choice: this line used to reveal a chip whose text nothing in the
                // port had ever written, so turning HDR on drew an empty badge between the
                // bitrate and the codec.
                specHdr.setVisibility(prefs.enableHdr ? View.VISIBLE : View.GONE);
            } else if (specHdr != null) {
                specHdr.setVisibility(View.GONE);
            }

            TextView[] chips = {specRes, specFps, specBitrate, specCodec, specAudio};
            for (TextView c : chips) {
                if (c != null) c.setVisibility(prefs != null ? View.VISIBLE : View.GONE);
            }
            bindSublRow((LinearLayout) sublRow, obj.details.uuid);
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
    /** HostStage.qml:73 — the desktop's words, including "Auto codec". Android's FormatOption
     *  has no PyroWave (the desktop's c === 5), so the four it does have map one for one. */
    private static String codecLabel(PreferenceConfiguration.FormatOption fmt) {
        if (fmt == PreferenceConfiguration.FormatOption.FORCE_AV1) return "AV1";
        if (fmt == PreferenceConfiguration.FormatOption.FORCE_HEVC) return "HEVC";
        if (fmt == PreferenceConfiguration.FormatOption.FORCE_H264) return "H.264";
        return "Auto codec";
    }

    private static String audioLabel(MoonBridge.AudioConfiguration audio) {
        if (audio == MoonBridge.AUDIO_CONFIGURATION_51_SURROUND) return "5.1";
        if (audio == MoonBridge.AUDIO_CONFIGURATION_71_SURROUND) return "7.1";
        return "Stereo";
    }

    /** HostStage.qml:76-83 — the friendly label, not the raw pair: "1080p", not "1920x1080". */
    private static String resLabel(int w, int h) {
        if (w == 3840 && h == 2160) return "4K";
        if (w == 2560 && h == 1440) return "1440p";
        if (w == 1920 && h == 1080) return "1080p";
        if (w == 1280 && h == 720)  return "720p";
        return w + "\u00d7" + h;
    }

    /**
     * The status line and its badges, shrunk to fit only when they have to be
     * (HostStage.qml:785-792 and :1038-1047).
     *
     * `_fit` is 1 whenever the row fits — the usual case — and below 1 only for the moments that
     * would otherwise run past the card's edge. The ROW is scaled as a whole rather than each
     * font and padding being re-derived, so the badges keep their exact proportions; and the box
     * takes the SCALED height, so what sits below moves up with the row instead of keeping a gap
     * the row no longer fills.
     *
     * Android has no equivalent of QML's implicitWidth plus scale, so this does it in two steps
     * after layout: pin every child to the width it actually wants (so LinearLayout cannot
     * squeeze the later badges to nothing first), then scale the row to whatever is left.
     */
    private void bindSublRow(final LinearLayout row, final String uuid) {
        row.setTag(R.id.am_subl_row, uuid);
        final Context ctx = row.getContext();
        final int[] attempts = {0};
        row.post(new Runnable() {
            @Override
            public void run() {
                // A recycled row may be showing a different host by the time this runs.
                if (uuid == null || !uuid.equals(row.getTag(R.id.am_subl_row))) return;

                int natural = 0;
                for (int i = 0; i < row.getChildCount(); i++) {
                    View c = row.getChildAt(i);
                    if (c.getVisibility() == View.GONE) continue;
                    int w = naturalWidth(c);
                    ViewGroup.LayoutParams lp = c.getLayoutParams();
                    if (lp.width != w) {
                        lp.width = w;
                        c.setLayoutParams(lp);
                    }
                    if (lp instanceof ViewGroup.MarginLayoutParams) {
                        ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
                        natural += m.leftMargin + m.rightMargin;
                    }
                    natural += w;
                }

                int available = row.getWidth();
                if (available <= 0 && attempts[0]++ < 4) {
                    // Not laid out yet — nothing to measure against. Try again next frame.
                    row.post(this);
                    return;
                }

                float fit = (available <= 0 || natural <= 0)
                        ? 1f : Math.min(1f, available / (float) natural);
                row.setPivotX(0f);
                row.setPivotY(0f);
                row.setScaleX(fit);
                row.setScaleY(fit);

                // _rowH is _px(28) — the base height, not the one we may already have shrunk to,
                // or the scale would compound on every bind.
                int h = Math.round(AmScale.ofStage(ctx).lengthPx(28f, ctx) * fit);
                ViewGroup.LayoutParams rlp = row.getLayoutParams();
                if (rlp.height != h) {
                    rlp.height = h;
                    row.setLayoutParams(rlp);
                }
            }
        });
    }

    /** The width a child wants, measured with no limit at all — QML's implicitWidth. */
    private static int naturalWidth(View v) {
        int w0 = v.getMeasuredWidth(), h0 = v.getMeasuredHeight();

        // ⚠️ AN ELLIPSIZED LINE MEASURES ITSELF FROM THE LINE IT IS DRAWING, NOT FROM ITS TEXT.
        //
        // The ready line is `singleLine + ellipsize=end`. Once it has ever clipped
        // "Ready to stream" down to "Rea…", an UNSPECIFIED pass reads that clipped width back, so
        // `bindSublRow` freezes the child to it — and the freeze is permanent: the row is then
        // `available (859px) / natural (78px) > 1`, `fit` stays 1, and the card keeps the ellipsis
        // with the whole width free beside it. Measured on the Shield, 13 Oct 2026: the row was
        // 859px wide and the line inside it 78px, reading "Rea…".
        //
        // So the ellipsize comes off for the measurement. The TEXT is the natural width; the
        // ellipsis is only ever what the box it happened to be in could fit.
        android.text.TextUtils.TruncateAt elide = null;
        android.widget.TextView tv = null;
        if (v instanceof android.widget.TextView) {
            tv = (android.widget.TextView) v;
            elide = tv.getEllipsize();
            if (elide != null) {
                tv.setEllipsize(null);
            }
        }

        v.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                  View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int w = v.getMeasuredWidth();

        if (tv != null && elide != null) {
            tv.setEllipsize(elide);
        }
        // Put it back: the parent re-measures after this anyway, but leave no trace.
        v.measure(View.MeasureSpec.makeMeasureSpec(w0, View.MeasureSpec.EXACTLY),
                  View.MeasureSpec.makeMeasureSpec(h0, View.MeasureSpec.EXACTLY));
        return w;
    }
}
