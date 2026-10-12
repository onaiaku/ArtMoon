package io.github.onaiaku.artmoon.artlight;

import android.graphics.drawable.Drawable;
import android.view.InputDevice;

/**
 * PadGlyphs — resolves a controller button to ArtMoon's own vendor glyph art.
 *
 * Desktop parity (app/gui/PadGlyph.qml). Two rules travel together and neither
 * may be re-derived here:
 *
 *   1. The pad's VENDOR decides which set is drawn — xbox, ps or switch.
 *   2. Buttons are resolved by SDL POSITION, not by their printed letter.
 *      Nintendo labels the same physical buttons the other way round, so the
 *      bottom button is switch_b under a Nintendo pad and xbox_a under an Xbox
 *      one, and the Switch "A" is on the right where Xbox puts its B.
 *
 * Every asset these resolve to is a rendered copy of the desktop's own
 * app/res/pad_*.svg. What Android had before was four flat coloured circles
 * with a letter typed over the top in dark text: it had the wrong green, no
 * symbol, no border, and nothing at all for a PlayStation or Switch pad.
 */
public final class PadGlyphs {

    /** Vendor sets, named as the desktop names them. */
    public static final String XBOX = "xbox";
    public static final String PS = "ps";
    public static final String SWITCH = "switch";

    /**
     * Anything SDL does not recognise draws Xbox art, because the desktop's
     * ternary chain in PadGlyph.qml falls through to its xbox branch for
     * "generic" and for an empty glyph set. Never render nothing.
     */
    public static final String UNKNOWN = XBOX;

    /** Face-button height in dp — the phone-sized counterpart of the desktop hint. */
    public static final int GLYPH_DP = 22;

    private PadGlyphs() {
    }

    /** The vendor set for a connected device. Never null. */
    public static String setForDevice(InputDevice dev) {
        if (dev == null) {
            return UNKNOWN;
        }
        return setForVendor(dev.getVendorId(), dev.getProductId());
    }

    /**
     * The vendor set for a USB vendor/product pair.
     *
     * The three big vendors are answered locally. Anything else asks SDL's
     * controller list through the native bridge, the same call ControllerHandler
     * makes when it reports arrival to the host — that is what catches a pad
     * that is PlayStation-shaped but is not a Sony vendor id. The call is
     * guarded on purpose: a pad key can arrive before the native library is
     * loaded, and the wrong glyph set is a much smaller problem than a crash.
     */
    public static String setForVendor(int vendorId, int productId) {
        switch (vendorId) {
            case 0x045e:
                return XBOX;   // Microsoft
            case 0x054c:
                return PS;     // Sony
            case 0x057e:
                return SWITCH; // Nintendo
            default:
                break;
        }
        try {
            byte type = io.github.onaiaku.artmoon.nvstream.jni.MoonBridge
                    .guessControllerType(vendorId, productId);
            if (type == io.github.onaiaku.artmoon.nvstream.jni.MoonBridge.LI_CTYPE_PS) {
                return PS;
            }
            if (type == io.github.onaiaku.artmoon.nvstream.jni.MoonBridge.LI_CTYPE_NINTENDO) {
                return SWITCH;
            }
            // Includes LI_CTYPE_XBOX, LI_CTYPE_STEAM (an Xbox-style layout with
            // extra paddles) and anything SDL cannot place.
            return XBOX;
        } catch (Throwable t) {
            return UNKNOWN;
        }
    }

    /**
     * The glyph for one button under one vendor set.
     *
     * buttonKey is the desktop's spelling: "A", "B", "X", "Y", "LB", "RB",
     * "LT", "RT", "SELECT", "START", "RS".
     *
     * Returns 0 when the key is not one the pad has, so a caller can fall back
     * to a labelled chip instead of drawing a hole.
     */
    public static int drawableFor(String set, String buttonKey) {
        if (buttonKey == null) {
            return 0;
        }
        boolean ps = PS.equals(set);
        boolean sw = SWITCH.equals(set);

        if ("A".equals(buttonKey)) {
            return ps ? io.github.onaiaku.artmoon.R.drawable.pad_ps_cross
                    : sw ? io.github.onaiaku.artmoon.R.drawable.pad_switch_b
                    : io.github.onaiaku.artmoon.R.drawable.pad_xbox_a;
        }
        if ("B".equals(buttonKey)) {
            return ps ? io.github.onaiaku.artmoon.R.drawable.pad_ps_circle
                    : sw ? io.github.onaiaku.artmoon.R.drawable.pad_switch_a
                    : io.github.onaiaku.artmoon.R.drawable.pad_xbox_b;
        }
        if ("X".equals(buttonKey)) {
            return ps ? io.github.onaiaku.artmoon.R.drawable.pad_ps_square
                    : sw ? io.github.onaiaku.artmoon.R.drawable.pad_switch_y
                    : io.github.onaiaku.artmoon.R.drawable.pad_xbox_x;
        }
        if ("Y".equals(buttonKey)) {
            return ps ? io.github.onaiaku.artmoon.R.drawable.pad_ps_triangle
                    : sw ? io.github.onaiaku.artmoon.R.drawable.pad_switch_x
                    : io.github.onaiaku.artmoon.R.drawable.pad_xbox_y;
        }
        if ("LB".equals(buttonKey)) {
            return ps ? io.github.onaiaku.artmoon.R.drawable.pad_ps_l1
                    : sw ? io.github.onaiaku.artmoon.R.drawable.pad_switch_l
                    : io.github.onaiaku.artmoon.R.drawable.pad_xbox_lb;
        }
        if ("RB".equals(buttonKey)) {
            return ps ? io.github.onaiaku.artmoon.R.drawable.pad_ps_r1
                    : sw ? io.github.onaiaku.artmoon.R.drawable.pad_switch_r
                    : io.github.onaiaku.artmoon.R.drawable.pad_xbox_rb;
        }
        if ("LT".equals(buttonKey)) {
            return ps ? io.github.onaiaku.artmoon.R.drawable.pad_ps_l2
                    : sw ? io.github.onaiaku.artmoon.R.drawable.pad_switch_zl
                    : io.github.onaiaku.artmoon.R.drawable.pad_xbox_lt;
        }
        if ("RT".equals(buttonKey)) {
            return ps ? io.github.onaiaku.artmoon.R.drawable.pad_ps_r2
                    : sw ? io.github.onaiaku.artmoon.R.drawable.pad_switch_zr
                    : io.github.onaiaku.artmoon.R.drawable.pad_xbox_rt;
        }
        if ("SELECT".equals(buttonKey)) {
            return ps ? io.github.onaiaku.artmoon.R.drawable.pad_ps_create
                    : sw ? io.github.onaiaku.artmoon.R.drawable.pad_switch_minus
                    : io.github.onaiaku.artmoon.R.drawable.pad_xbox_view;
        }
        if ("START".equals(buttonKey)) {
            return ps ? io.github.onaiaku.artmoon.R.drawable.pad_ps_options
                    : sw ? io.github.onaiaku.artmoon.R.drawable.pad_switch_plus
                    : io.github.onaiaku.artmoon.R.drawable.pad_xbox_start;
        }
        if ("RS".equals(buttonKey)) {
            return ps ? io.github.onaiaku.artmoon.R.drawable.pad_ps_r3
                    : sw ? io.github.onaiaku.artmoon.R.drawable.pad_switch_rs
                    : io.github.onaiaku.artmoon.R.drawable.pad_xbox_rs;
        }
        return 0;
    }

    /**
     * Scale a glyph to the face-button size and hand it back with its bounds
     * set, so it can be used as a compound drawable without being sized.
     *
     * The PNGs carry enough pixels for any density; the bounds are what make
     * the prompt row keep its height, which is the whole reason the desktop
     * draws glyphs and caps at one baseline.
     */
    public static Drawable sized(android.content.res.Resources res, int drawableId) {
        Drawable d = res.getDrawable(drawableId);
        if (d == null) {
            return null;
        }
        int px = Math.round(GLYPH_DP * res.getDisplayMetrics().density);
        d.setBounds(0, 0, px, px);
        return d;
    }
}
