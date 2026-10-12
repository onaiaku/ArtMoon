package io.github.onaiaku.artmoon.grid;

import android.content.Context;
import io.github.onaiaku.artmoon.artlight.HostBackgroundManager;
import io.github.onaiaku.artmoon.artlight.HostMetricsPoller.GameCover;
import android.view.View;

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
            AmScale s = AmScale.ofScreen(context);
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
        }
        // NOTE: PcView.paintPadFocus is the SINGLE writer of activated state.
        // The adapter previously painted the row here too, producing a second
        // ring around the whole hero card on top of the pill ring (Nik saw
        // Open and Options 'both selected at once'). Do not re-add.
        if (parent != null && parent.getHeight() > 0
                && v.getLayoutParams().height != parent.getHeight()) {
            android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();
            lp.height = parent.getHeight();
            v.setLayoutParams(lp);
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
            if (ago == null) {
                panel.setVisibility(View.GONE);
                return;
            }
            bindText(v, R.id.am_last_session_when,
                    duration == null || duration.isEmpty() ? ago : ago + " \u00b7 " + duration);
            TextView gradeTv = v.findViewById(R.id.am_last_session_grade);
            if (gradeTv != null) {
                if (grade == null || grade.isEmpty()) {
                    gradeTv.setVisibility(View.GONE);
                } else {
                    gradeTv.setVisibility(View.VISIBLE);
                    gradeTv.setText(grade);
                    // Desktop parity: the grade pill is the grade colour at 16%
                    // alpha with the text in that colour (HostStage.qml).
                    int color = parseColorHex(gradeColor);
                    if (color == 0) {
                        color = context.getResources().getColor(R.color.am_online);
                    }
                    gradeTv.setTextColor(color);
                    android.graphics.drawable.GradientDrawable pill =
                            new android.graphics.drawable.GradientDrawable();
                    pill.setCornerRadius(dp(6));
                    pill.setColor((color & 0x00FFFFFF) | 0x29000000); // 16% alpha
                    gradeTv.setBackground(pill);
                    gradeTv.setPadding(dp(12), dp(4), dp(12), dp(4));
                }
            }
            bindText(v, R.id.am_last_session_rtt, rttMs < 0 ? "\u2014" : rttMs + " ms");
            bindText(v, R.id.am_last_session_hostlat, hostLatMs < 0 ? "\u2014" : hostLatMs + " ms");
            bindText(v, R.id.am_last_session_drops,
                    dropsPct < 0 ? "\u2014" : String.format(java.util.Locale.US, "%.1f%%", dropsPct));

            // RTT caption: "11 peak" when the host measured a peak, plain "RTT"
            // when it never did — byte-for-byte the desktop's HostStage logic.
            TextView rttCap = v.findViewById(R.id.am_last_session_rtt_caption);
            if (rttCap != null) {
                rttCap.setText(rttPeakMs < 0
                        ? context.getString(R.string.am_stat_rtt)
                        : rttPeakMs + " " + context.getString(R.string.am_stat_peak));
            }

            renderGameCovers(v, games, gamesTotal);

            panel.setVisibility(View.VISIBLE);
        }

        /** The desktop's "+N" rule: games_total can exceed the capped list the host sends. */
        private void renderGameCovers(View v, java.util.List<GameCover> games, int gamesTotal) {
            LinearLayout covers = v.findViewById(R.id.am_last_session_covers);
            if (covers == null) {
                return;
            }
            int shown = (games == null) ? 0 : Math.min(games.size(), 3);
            if (shown == 0) {
                covers.setVisibility(View.GONE);
                return;
            }
            int[] tileIds = {
                    R.id.am_last_session_cover0,
                    R.id.am_last_session_cover1,
                    R.id.am_last_session_cover2
            };
            int[] imgIds = {
                    R.id.am_last_session_cover_img0,
                    R.id.am_last_session_cover_img1,
                    R.id.am_last_session_cover_img2
            };
            int[] nameIds = {
                    R.id.am_last_session_cover_name0,
                    R.id.am_last_session_cover_name1,
                    R.id.am_last_session_cover_name2
            };
            for (int i = 0; i < 3; i++) {
                View tile = v.findViewById(tileIds[i]);
                if (tile == null) {
                    continue;
                }
                ImageView img = v.findViewById(imgIds[i]);
                TextView name = v.findViewById(nameIds[i]);
                if (i < shown) {
                    tile.setVisibility(View.VISIBLE);
                    GameCover gc = games.get(i);
                    android.graphics.Bitmap bmp = decodeCover(gc.cover);
                    if (bmp != null && img != null) {
                        img.setImageBitmap(bmp);
                        img.setVisibility(View.VISIBLE);
                        if (name != null) {
                            name.setVisibility(View.GONE);
                        }
                    } else {
                        if (img != null) {
                            img.setVisibility(View.GONE);
                        }
                        if (name != null) {
                            name.setText(gc.name);
                            name.setVisibility(View.VISIBLE);
                        }
                    }
                } else {
                    tile.setVisibility(View.INVISIBLE);
                }
            }
            TextView more = v.findViewById(R.id.am_last_session_more);
            if (more != null) {
                int extra = gamesTotal - shown;
                if (extra > 0) {
                    more.setText("+" + extra);
                    more.setVisibility(View.VISIBLE);
                } else {
                    more.setVisibility(View.GONE);
                }
            }
            covers.setVisibility(View.VISIBLE);
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

        private int dp(float v) {
            return Math.round(v * context.getResources().getDisplayMetrics().density);
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
     * v12: the right-hand GameFacts block — desktop HostStage.qml:1625, `lastPanel`, the block
     * that stands on the floor beside the card: badge, cover, title, meta, figures.
     *
     * ⚠️ This is NOT the host's session numbers. Those live inside the card now, which is where
     * the desktop keeps them (HostStage.qml:1218 sits inside the card, anchored 38 in from its
     * left and right). The right column holds GameFacts, and only GameFacts.
     *
     * ⚠️ Source. The desktop feeds this from a playtime log it keeps itself on the machine that
     * played (computermodel.cpp:1364 → PlaytimeManager). This client has no such log yet, so the
     * block is fed from the host's own last-session reply — the only per-game record available
     * here. Same block, same geometry, one different source; when a playtime log lands on Android
     * this is the method it should feed instead.
     *
     * ago == null draws nothing at all: "An empty map means draw nothing at all ... There is no
     * empty state" (HostStage.qml:1611-1613).
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
        if (ago == null || ago.isEmpty()) {
            col.setVisibility(View.GONE);
            return;
        }

        // Badge: "Last played <ago>" with the session's length beside it (HostStage.qml:1658-1665
        // is the running/last-played wording; there are no "total" figures here — that number is
        // the desktop's local log).
        bindText(v, R.id.am_facts_badge_main,
                context.getString(R.string.am_last_played_ago, ago));
        bindText(v, R.id.am_facts_badge_muted, duration == null ? "" : duration);
        View badge = v.findViewById(R.id.am_facts_badge);
        if (badge != null) {
            badge.setVisibility(View.VISIBLE);
        }

        // Cover and title come from the first entry of the host's own games list — the game the
        // session was played in, which is what the desktop's cover and title are too.
        String title = "";
        android.graphics.Bitmap art = null;
        if (games != null && !games.isEmpty()) {
            GameCover gc = games.get(0);
            if (gc.name != null) {
                title = gc.name;
            }
            art = decodeCover(gc.cover);
        }
        bindText(v, R.id.am_facts_title, title);

        final ImageView cover = v.findViewById(R.id.am_facts_cover);
        if (cover != null) {
            if (art != null) {
                cover.setImageBitmap(art);
                cover.setVisibility(View.VISIBLE);
            } else {
                cover.setVisibility(View.GONE);
            }
        }

        // The meta row carries the store's mark and name. The host's reply has neither, so the
        // row stays hidden rather than inventing a storefront (GameFacts.qml:321 hides it on
        // empty text for the same reason).
        View meta = v.findViewById(R.id.am_facts_meta);
        if (meta != null) {
            meta.setVisibility(View.GONE);
        }

        // One figure, not two: PLAYED. The desktop's second figure is the session COUNT from its
        // own log, and the host does not report one — a "1" here would be inventing a number.
        boolean hasPlayed = duration != null && !duration.isEmpty();
        bindText(v, R.id.am_facts_played, hasPlayed ? duration : "\u2014");
        View figures = v.findViewById(R.id.am_facts_figures);
        if (figures != null) {
            figures.setVisibility(hasPlayed ? View.VISIBLE : View.GONE);
        }

        col.setVisibility(View.VISIBLE);

        // coverHeight (GameFacts.qml:147-156): whatever the panel has left once the block's own
        // text has taken its share, floored at _px(90). Two passes, because the text's height is
        // only real after layout has run.
        final ImageView cv = cover;
        if (cv != null) {
            col.post(new Runnable() {
                @Override
                public void run() {
                    View panel = (View) col.getParent();
                    if (panel == null || panel.getHeight() <= 0 || cv.getHeight() <= 0) {
                        return;
                    }
                    int others = col.getHeight() - cv.getHeight();
                    int room = panel.getHeight() - dp(30) - dp(18) - others;
                    int h = Math.max(dp(90), room);
                    if (cv.getHeight() != h) {
                        android.view.ViewGroup.LayoutParams lp = cv.getLayoutParams();
                        lp.height = h;
                        cv.setLayoutParams(lp);
                    }
                }
            });
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
