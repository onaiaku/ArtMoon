package io.github.onaiaku.artmoon.ui;

import android.content.Context;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;

/**
 * The desktop's scale rule, ported.
 *
 * <p>AppShell.qml:32 — {@code Theme.uiScale = width / 1330}, and every dimension on every
 * desktop screen is {@code _px(n) = round(n * uiScale)}. That one rule is the whole reason the
 * Windows, Linux and SteamOS builds agree with each other. Android never had it: its numbers are
 * fixed dp/sp literals, so it cannot agree with anything, even where it copies the right values.
 *
 * <p>The rule is ported rather than approximated. Numbers handed to this class are the QML's own
 * base numbers, verbatim and unscaled — 44 sides, 22 top, 60 tall, icon 46, name 68 — and the
 * class turns them into dp for the width the view actually has. Nothing on a screen should carry
 * a hand-tuned dp value ever again; if a number is in a layout it goes through here.
 *
 * <p>QML's logical px and Android's dp are the same unit, which is what makes the port exact
 * rather than close.
 */
public final class AmScale {

    /** The desktop's design width. AppShell.qml divides by this and nothing else. */
    public static final float BASE_WIDTH = 1330f;

    private final float uiScale;

    private AmScale(float uiScale) {
        this.uiScale = uiScale;
    }

    /** Scale for a width in dp — the Android twin of {@code width / 1330}. */
    public static AmScale ofWidthDp(float widthDp) {
        return new AmScale(widthDp / BASE_WIDTH);
    }

    /** Scale for the width a view actually ended up with. */
    public static AmScale of(View v) {
        DisplayMetrics dm = v.getResources().getDisplayMetrics();
        float widthDp = (dm.density > 0f) ? (v.getWidth() / dm.density) : 0f;
        if (widthDp <= 0f) {
            // Not laid out yet — fall back to the display, which is what the shell would use.
            widthDp = dm.widthPixels / dm.density;
        }
        return new AmScale(widthDp / BASE_WIDTH);
    }

    /** Scale for the current window. */
    public static AmScale ofScreen(Context c) {
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        return new AmScale((dm.widthPixels / dm.density) / BASE_WIDTH);
    }

    /**
     * The STAGE's scale, which the desktop clamps and the shell does not.
     *
     * <p>HostStage.qml:315 — {@code _u = Math.max(0.62, Math.min(1.60, width / 1330))}. HomeScreen
     * and AppShell both divide and stop (HomeScreen.qml:116, AppShell.qml:32), so the brand row,
     * the tab strip and the footer keep shrinking with the window while the card and everything
     * on it stop at 0.62. That is the desktop's own behaviour at a narrow width and the port has
     * to reproduce it, not improve on it.
     */
    public static AmScale ofStage(Context c) {
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        float raw = (dm.widthPixels / dm.density) / BASE_WIDTH;
        return new AmScale(Math.max(0.62f, Math.min(1.60f, raw)));
    }

    public float uiScale() {
        return uiScale;
    }

    /**
     * {@code _px(n)} as a scaled dp NUMBER — the QML's own arithmetic, in the unit QML measures
     * in.
     *
     * <p>⚠️ This is a dp number, NOT pixels. It is right for exactly one kind of call:
     * {@code setTextSize(TypedValue.COMPLEX_UNIT_DIP, dp(n))}, because that API converts
     * dp to px itself. It is WRONG for {@code setPadding}, {@code setMargins} and
     * {@code ViewGroup.LayoutParams} — those take PHYSICAL PIXELS, and a dp number handed to
     * one makes the box {@code density}x too small (2.2x on the Fold). That mistake is how a
     * 19dp-tall tab becomes 13 pixels tall, too short for its own 19dp label, so the text
     * overflows the pill and the pill appears to cut through it.
     *
     * <p>Anything that is not a text size wants {@link #lengthPx}.
     */
    public int dp(float base) {
        return Math.round(base * uiScale);
    }

    /**
     * {@code _px(n)} in PHYSICAL PIXELS — what {@code setPadding}, {@code setMargins} and
     * {@code ViewGroup.LayoutParams} actually take, and the only correct way to size a view
     * built in code.
     *
     * <p>Same rule as {@link #radiusPx}, same rule layouts get for free: the inflation carries
     * the QML's base numbers as real dp, {@code applyTo} multiplies those PIXELS by uiScale, and
     * this is that same result arrived at from a base number instead of from a layout.
     * Verified against a layout: {@code lengthPx(40)} and a {@code 40dp} tab in XML both come
     * out as 62px on the Fold at uiScale 0.70 / density 2.2.
     */
    public int lengthPx(float base, Context c) {
        return Math.round(Math.round(base * uiScale)
                * c.getResources().getDisplayMetrics().density);
    }

    /** {@code _px(Theme.fontX)} — a text size in dp, same unit the QML scales. */
    public int text(float baseFontToken) {
        return Math.round(baseFontToken * uiScale);
    }

    /** A tracking/letter-spacing value: QML adds {@code n * _u} in px. */
    public float tracking(float base) {
        return base * uiScale;
    }

    // ── the part that makes every screen agree ────────────────────────────────
    //
    // Layout files carry the QML's BASE numbers (44, 22, 60, 46, 68, 19 …) as plain dp. This
    // walks a freshly inflated tree once and multiplies every one of them by uiScale — which
    // is the desktop's _px(), applied to the whole screen at once instead of call by call.
    //
    // Call it on a FRESH inflation only. A recycled GridView row has already been scaled, and
    // scaling it twice is how a layout drifts out of agreement with the other platforms.

    public static void applyTo(View root, AmScale s) {
        if (root == null || s == null) {
            return;
        }
        scaleOne(root, s);
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                applyTo(g.getChildAt(i), s);
            }
        }
    }

    private static int px(int v, AmScale s) {
        return Math.round(v * s.uiScale);
    }

    private static void scaleOne(View v, AmScale s) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp != null) {
            boolean touched = false;
            if (lp.width > 0) { lp.width = px(lp.width, s); touched = true; }
            if (lp.height > 0) { lp.height = px(lp.height, s); touched = true; }
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
                m.leftMargin = px(m.leftMargin, s);
                m.rightMargin = px(m.rightMargin, s);
                m.topMargin = px(m.topMargin, s);
                m.bottomMargin = px(m.bottomMargin, s);
                touched = true;
            }
            if (touched) {
                v.setLayoutParams(lp);
            }
        }

        int l = px(v.getPaddingLeft(), s), t = px(v.getPaddingTop(), s);
        int r = px(v.getPaddingRight(), s), b = px(v.getPaddingBottom(), s);
        if (l != v.getPaddingLeft() || t != v.getPaddingTop()
                || r != v.getPaddingRight() || b != v.getPaddingBottom()) {
            v.setPadding(l, t, r, b);
        }

        if (v.getMinimumWidth() > 0) v.setMinimumWidth(px(v.getMinimumWidth(), s));
        if (v.getMinimumHeight() > 0) v.setMinimumHeight(px(v.getMinimumHeight(), s));

        // Radii scale too, and this is the one that was actually breaking pills on screen.
        //
        // A shape drawable writes its corner radius in the layout at the QML's DESIGN size, and
        // that radius has to come down with the box it is drawn on. It was not: a 40-tall tab
        // became 28 at uiScale 0.70 while its corner radius stayed at 20 — and a radius past
        // half the height draws the two end caps straight through each other, which is exactly
        // the "cut off through the middle" a pill gets. Same drawable on LT/RT, the footer chips
        // and the badges.
        //
        // mutate() FIRST. A drawable loaded from resources is shared by every view using it, so
        // writing a radius onto the shared instance would scale it again for each row after the
        // first. mutate() gives this view its own copy.
        android.graphics.drawable.Drawable bg = v.getBackground();
        if (bg != null) {
            android.graphics.drawable.Drawable own = bg.mutate();
            if (own != null && own != bg) {
                v.setBackground(own);
                bg = own;
            }
            if (bg instanceof android.graphics.drawable.GradientDrawable) {
                android.graphics.drawable.GradientDrawable gd =
                        (android.graphics.drawable.GradientDrawable) bg;
                float designRadius = gd.getCornerRadius();
                if (designRadius > 0f) {
                    gd.setCornerRadius(designRadius * s.uiScale);
                }
            }
        }

        if (v instanceof android.widget.TextView) {
            android.widget.TextView tv = (android.widget.TextView) v;
            float size = tv.getTextSize();          // px
            if (size > 0f) {
                // NOTE: letterSpacing is in em, so it scales itself with the text size and
                // must NOT be multiplied here. The QML's `3.6 * _u` tracking is expressed in
                // the layout as 3.6/28 em and stays correct at every width.
                tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size * s.uiScale);
            }
        }
    }

    /**
     * A QML {@code _px(n)} corner radius, in physical px, for a {@link GradientDrawable} built in
     * code. Same rule {@link #lengthPx} applies; radii are just lengths a drawable wants as a
     * float.
     */
    public float radiusPx(float baseRadius, Context c) {
        return lengthPx(baseRadius, c);
    }

    /** The corner radius a QML {@code _px(n)} radius becomes — radii scale too. */
    public static float cornerPx(float baseRadius, AmScale s) {
        return Math.round(baseRadius * s.uiScale)
                * android.content.res.Resources.getSystem().getDisplayMetrics().density;
    }
}
