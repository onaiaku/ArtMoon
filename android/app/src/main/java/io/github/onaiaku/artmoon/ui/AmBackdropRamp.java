package io.github.onaiaku.artmoon.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * The stage card's backdrop ramp, ported from HostStage.qml:113-185.
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
 * <p>{@code _bakedStops} is what this class draws — five stops from those functions, flat to
 * 0.55 and then the values the composite produced at 72%, 92% and past. The QML makes the
 * near-flat left of the card EXACTLY flat on purpose ("a region spanning six levels over
 * fourteen hundred pixels is not a gradient — it is a flat colour that occasionally jumps, and
 * the jumps are the artefact").
 *
 * <p>Why not a shape drawable: Android's {@code <gradient>} takes three stops and this needs
 * five, and the stops are a function of the host's colours rather than constants. And why this
 * replaced what was here: the Android card carried a VERTICAL scrim, densest at the BOTTOM. The
 * desktop's darkening runs along x — heavy where the name and the fields are, gone by the right
 * edge. A vertical one darkens exactly the strip the action row sits in and leaves the left of
 * the card lighter than the desktop, which is the opposite of the intent.
 */
public final class AmBackdropRamp extends Drawable {

    /** HostStage.qml:148 — the end of the ramp. */
    private static final int END_R = 0x05, END_G = 0x08, END_B = 0x0a;

    private final int[] colours;
    private static final float[] POSITIONS = { 0.00f, 0.55f, 0.72f, 0.90f, 1.00f };

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private Shader shader;
    private final Rect bounds = new Rect();

    /** The QML's own defaults — HostStage.qml:113-114. */
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

    /** The five stops, in order, for the colours this ramp was built with. */
    public int[] colours() {
        return colours.clone();
    }

    @Override
    protected void onBoundsChange(Rect b) {
        bounds.set(b);
        // Horizontal, always: the ramp runs along x and its vertical extent is irrelevant.
        shader = new LinearGradient(b.left, b.top, b.right, b.top,
                colours, POSITIONS, Shader.TileMode.CLAMP);
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
        return android.graphics.PixelFormat.OPAQUE;
    }
}
