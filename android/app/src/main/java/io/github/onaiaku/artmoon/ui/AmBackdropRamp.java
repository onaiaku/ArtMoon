package io.github.onaiaku.artmoon.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * The stage card's backdrop, ported from HostStage.qml:113-185 and :673-695.
 *
 * <p>The desktop card is not a flat panel. Behind the identity block it is a HORIZONTAL ramp:
 * the host's colour pair for the first 55% of the card, then falling away to {@code #05080a} so
 * the text on the left has something to sit on and the right-hand side still reads as a colour.
 *
 * <pre>
 *   backdropFrom / backdropTo   the host's own pair (ComputerModel supplies them; the QML
 *                               defaults are #1c1c1c and #0d0d0d, and a hash of the host's
 *                               name stands in until the user picks something)
 *   _baseAt(p)                  from -> to across 0.00..0.46, then to #05080a across 0.46..0.90
 *   _scrimAlphaAt(p)            b0 across 0.00..0.30, then 80 -> 30 across 0.30..0.62,
 *                               then 30 -> 00 across 0.62..1.00
 *   _compositeAt(p)             the two layers, composited: #05080a over _baseAt(p)
 * </pre>
 *
 * <p>{@code _bakedStops} is what {@link #noPicture()} draws — five stops from those functions,
 * flat to 0.55 and then the values the composite produced at 72%, 92% and past. The QML makes
 * the near-flat left of the card EXACTLY flat on purpose ("a region spanning six levels over
 * fourteen hundred pixels is not a gradient — it is a flat colour that occasionally jumps, and
 * the jumps are the artefact").
 *
 * <h2>⚠️ Two recipes, not one — and the host's picture chooses</h2>
 *
 * <p>There is a second, completely different set of stops for the case where the host has a
 * PICTURE behind the card, and using the first set there is a bug that hides the user's own
 * artwork (Nik, 12 October, having just set a background on the Fold: "the background that can
 * be set for the pc hero is to dark or to faded, ive just added a bakground and can hardly see
 * it"). HostStage.qml is explicit about why:
 *
 * <pre>
 *   626-630  the base ramp gets the PLAIN colour pair over a picture — no scrim baked in
 *   680-695  the scrim is a SEPARATE layer over the picture, and only over a picture:
 *            "Without one the scrim is baked into the ramp above, and drawing it here as well
 *             would veil the card twice."
 *   "reaching fully transparent at the right edge matters, the 19% black that used to sit
 *    there is what made artwork look washed out."
 * </pre>
 *
 * <p>So over a picture the scrim is four ARGB stops — {@code #e605080a} at 0.00, {@code #b005080a}
 * at 0.30, {@code #4005080a} at 0.62, {@code #00050808} at 1.00 — dense exactly where the name
 * and the fields are and gone by the right-hand side, "so the picture gets to be itself over
 * most of the card".
 *
 * <p>Why not a shape drawable: Android's {@code <gradient>} takes three stops and the no-picture
 * ramp needs five, and the stops are a function of the host's colours rather than constants. And
 * why this replaced what was here: the Android card carried a VERTICAL scrim, densest at the
 * BOTTOM. The desktop's darkening runs along x — heavy where the name and the fields are, gone
 * by the right edge. A vertical one darkens exactly the strip the action row sits in and leaves
 * the left of the card lighter than the desktop, which is the opposite of the intent.
 */
public final class AmBackdropRamp extends Drawable {

    /** HostStage.qml:148 — the end of the ramp. */
    private static final int END_R = 0x05, END_G = 0x08, END_B = 0x0a;

    /** The baked composite's five stops — the NO-PICTURE backdrop. */
    private static final float[] POSITIONS = { 0.00f, 0.55f, 0.72f, 0.90f, 1.00f };

    /**
     * The scrim's four stops — HostStage.qml:684-695, verbatim. Alpha is carried in the colour,
     * so nothing multiplies this by {@code backdropOpacity} the way the baked ramp is thinned.
     */
    private static final float[] PICTURE_POSITIONS = { 0.00f, 0.30f, 0.62f, 1.00f };
    private static final int[] PICTURE_STOPS = {
            0xe605080a, 0xb005080a, 0x4005080a, 0x0005080a,
    };

    private final int[] colours;
    private final float[] positions;

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private Shader shader;
    private final Rect bounds = new Rect();

    /** The QML's own defaults — HostStage.qml:113-114. */
    public static AmBackdropRamp noPicture() {
        return new AmBackdropRamp(0x1c1c1c, 0x0d0d0d);
    }

    public AmBackdropRamp() {
        this(0x1c1c1c, 0x0d0d0d);
    }

    public AmBackdropRamp(int from, int to) {
        colours = new int[] {
                compositeAt(0.30f, from, to),
                compositeAt(0.55f, from, to),
                compositeAt(0.72f, from, to),
                compositeAt(0.92f, from, to),
                compositeAt(1.00f, from, to),
        };
        positions = POSITIONS;
    }

    private AmBackdropRamp(boolean overPicture) {
        colours = PICTURE_STOPS.clone();
        positions = PICTURE_POSITIONS;
        // The scrim's alphas are in its colours. Nothing thins it further: the desktop gates
        // the whole layer on there being a picture and draws exactly these four values.
        paint.setAlpha(255);
    }

    /**
     * The layer to draw OVER a host's picture — HostStage.qml:684-695.
     *
     * <p>Not the ramp. Over a picture the desktop's ramp is only what shows while the art
     * loads, and the veiling is this scrim: densest on the left, fully transparent by the right
     * edge so the artwork is not washed out. Drawing {@link #noPicture()} here instead paints a
     * near-black field across the whole of the user's picture at 90% — which is what Nik saw.
     */
    public static AmBackdropRamp overPicture() {
        return new AmBackdropRamp(true);
    }

    // ── the QML's three functions, verbatim ───────────────────────────────────────────────

    private static float[] baseAt(float p, int from, int to) {
        float[] f = { Color.red(from), Color.green(from), Color.blue(from) };
        float[] t = { Color.red(to), Color.green(to), Color.blue(to) };
        float[] e = { END_R, END_G, END_B };
        float[] out = new float[3];
        if (p <= 0.46f) {
            float k = p / 0.46f;
            for (int i = 0; i < 3; i++) out[i] = f[i] + (t[i] - f[i]) * k;
        } else if (p <= 0.90f) {
            float k = (p - 0.46f) / 0.44f;
            for (int i = 0; i < 3; i++) out[i] = t[i] + (e[i] - t[i]) * k;
        } else {
            System.arraycopy(e, 0, out, 0, 3);
        }
        return out;
    }

    private static float scrimAlphaAt(float p) {
        if (p <= 0.30f) return lerp(0xb0 / 255f, 0x80 / 255f, p / 0.30f);
        if (p <= 0.62f) return lerp(0x80 / 255f, 0x30 / 255f, (p - 0.30f) / 0.32f);
        return lerp(0x30 / 255f, 0.0f, (p - 0.62f) / 0.38f);
    }

    private static int compositeAt(float p, int from, int to) {
        float[] b = baseAt(p, from, to);
        float a = scrimAlphaAt(p);
        float[] s = { END_R, END_G, END_B };
        int[] c = new int[3];
        for (int i = 0; i < 3; i++) {
            c[i] = Math.round(s[i] * a + b[i] * (1f - a));
        }
        return Color.rgb(c[0], c[1], c[2]);
    }

    private static float lerp(float a, float b, float f) {
        return a + (b - a) * f;
    }

    // ── Drawable ──────────────────────────────────────────────────────────────────────────

    /** The stops, in order, for the colours this ramp was built with. */
    public int[] colours() {
        return colours.clone();
    }

    @Override
    protected void onBoundsChange(Rect b) {
        bounds.set(b);
        // Horizontal, always: the ramp runs along x and its vertical extent is irrelevant.
        shader = new LinearGradient(b.left, b.top, b.right, b.top,
                colours, positions, Shader.TileMode.CLAMP);
    }

    @Override
    public void draw(Canvas canvas) {
        if (shader == null) {
            onBoundsChange(getBounds());
        }
        paint.setShader(shader);
        canvas.drawRect(getBounds(), paint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(android.graphics.ColorFilter cf) {
        paint.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return android.graphics.PixelFormat.TRANSLUCENT;
    }
}
