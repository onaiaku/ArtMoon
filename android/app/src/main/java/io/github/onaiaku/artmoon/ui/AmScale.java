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

    public float uiScale() {
        return uiScale;
    }

    /**
     * {@code _px(n)} — the QML call this exists to mirror. Returns dp; apply it with
     * {@code setTextSize(TypedValue.COMPLEX_UNIT_DIP, ...)} or
     * {@code ViewGroup.LayoutParams} in dp.
     */
    public int dp(float base) {
        return Math.round(base * uiScale);
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

    /** The corner radius a QML {@code _px(n)} radius becomes — radii scale too. */
    public static float cornerPx(float baseRadius, AmScale s) {
        return Math.round(baseRadius * s.uiScale)
                * android.content.res.Resources.getSystem().getDisplayMetrics().density;
    }
}
