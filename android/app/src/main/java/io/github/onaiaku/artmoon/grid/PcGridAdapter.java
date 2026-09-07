package io.github.onaiaku.artmoon.grid;

import android.content.Context;
import io.github.onaiaku.artmoon.artlight.HostBackgroundManager;
import android.view.View;
import android.widget.ImageView;
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
        View v = super.getView(position, convertView, parent);
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
        if (focusedPosition == android.widget.AdapterView.INVALID_POSITION
                || focusedPosition < 0 || focusedPosition >= itemList.size()) {
            return null;
        }
        PcView.ComputerObject obj = itemList.get(focusedPosition);
        return boundViews.get(obj.details.uuid);
    }

    public View getViewForComputer(String uuid) {
        return boundViews.get(uuid);
    }

    /**
     * v11: paint the card's LAST SESSION panel (desktop HostStage parity).
     * snapshot == null hides the whole panel; a -1 metric renders as \u2014
     * (never folded to 0); an empty grade hides the grade line.
     */
    public void updateLastSessionByUuid(String uuid, String ago, String duration,
                                        String grade, int rttMs, int hostLatMs,
                                        double dropsPct) {
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
                duration == null || duration.isEmpty() ? ago : ago + " · " + duration);
        TextView gradeTv = v.findViewById(R.id.am_last_session_grade);
        if (gradeTv != null) {
            if (grade == null || grade.isEmpty()) {
                gradeTv.setVisibility(View.GONE);
            } else {
                gradeTv.setText(grade);
                gradeTv.setVisibility(View.VISIBLE);
            }
        }
        bindText(v, R.id.am_last_session_rtt, rttMs < 0 ? "\u2014" : rttMs + " ms");
        bindText(v, R.id.am_last_session_hostlat, hostLatMs < 0 ? "\u2014" : hostLatMs + " ms");
        bindText(v, R.id.am_last_session_drops,
                dropsPct < 0 ? "\u2014" : String.format(java.util.Locale.US, "%.1f%%", dropsPct));
        panel.setVisibility(View.VISIBLE);
    }

    /**
     * v11: HOST LINK — the STATUS NIC-speed reply, desktop-formatted
     * ("1000" -> "1 Gbps"). link == null hides the block.
     */
    public void updateHostLinkByUuid(String uuid, String link) {
        View v = boundViews.get(uuid);
        if (v == null) {
            return;
        }
        View box = v.findViewById(R.id.am_host_link_box);
        if (box == null) {
            return;
        }
        if (link == null) {
            box.setVisibility(View.GONE);
            return;
        }
        TextView tv = v.findViewById(R.id.am_host_link_value);
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
