package io.github.onaiaku.artmoon.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

import io.github.onaiaku.artmoon.R;

/**
 * The app's floor, ported from the desktop's {@code AmbientBackground.qml} + {@code AmbientWaves.qml}
 * (6.0.0). Two things on one canvas:
 *
 * <ol>
 *   <li><b>The wash</b> — charcoal at the top, tinted towards the accent by the bottom
 *       ({@code AmbientBackground.qml:42-52}). Derived from {@code am_accent} rather than
 *       hardcoded, for the desktop's own reason: a fixed green would make the accent a lie the
 *       moment the theme moves. Dithered, because this is the worst banding case in either app —
 *       the green channel travels 21 to 25 across seventy percent of the height, so a plain 8-bit
 *       ramp lays a hard edge every couple of hundred pixels, in the dark end where it shows most
 *       ({@code DitheredGradient}, and the desktop's note about "quelle bande veramente brutte").</li>
 *   <li><b>The waves</b> — five layers travelling left to right, rising a quarter to nearly four
 *       fifths of the height. One number per layer, {@code depth} (0 = the far one, highest on
 *       screen; 1 = the near one, lowest): far layers are short, low and slow, near ones long,
 *       tall and faster. Each layer is a flat faint fill plus a slightly brighter crest line —
 *       NOT shaded inside, which the desktop tried first and it turned the layers into one blur.</li>
 * </ol>
 *
 * <p><b>Every number below is {@code AmbientWaves.qml}'s, verbatim, and scaled by the same rule:</b>
 * {@code _u = Theme.uiScale = width / 1330} (AppShell.qml:32) — the UNclamped scale, because that is
 * what AmbientWaves reads. {@code u} here comes from {@link AmScale}, which is that same divide.
 *
 * <p><b>⚠️ The paths are built ONCE per size and only TRANSLATED afterwards</b> — the desktop's own
 * rule, and it matters more here than there: rebuilding a {@link Path} costs a triangulation, and
 * this runs at the display's frame rate. Each crest is one wavelength wider than the view so a
 * shift of up to a wavelength never uncovers an edge, and both harmonics repeat every wavelength,
 * so the wrap is seamless.
 *
 * <p><b>⚠️ One clock, not one animation per layer.</b> The desktop's hard-won lesson: a running
 * animation does not pick up a new duration until it restarts, and a restart sends every layer back
 * to its start — the picture would jump the moment a session began. So {@link Choreographer}
 * advances a single {@code t} and every layer reads it, and the speed change is eased inside that
 * same clock over 1.2s.
 *
 * <p>It stops whenever nobody can see it: detached, hidden, window gone, or the system's animation
 * scale switched off — the same condition the port already applies to the live dot's pulse
 * (PcGridAdapter.paintLiveDot). The wash is still drawn in that state; only the travelling stops.
 */
public class AmbientFloor extends View {

    // ── AmbientBackground.qml:42-52 ──────────────────────────────────────────────────────────
    private static final int WASH_TOP = 0xFF151515;
    private static final float WASH_MID_POS = 0.70f;
    private static final float WASH_MID_ALPHA = 0.07f;
    private static final float WASH_BOTTOM_ALPHA = 0.20f;

    // ── AmbientWaves.qml ─────────────────────────────────────────────────────────────────────
    private static final int LAYERS = 5;
    /** Clamped, because the first frame after a pause reports the whole time it was away. */
    private static final float MAX_FRAME_MS = 100f;
    private static final float SPEED_EASE_MS = 1200f;
    /** 1 normally; 2 while a host is streaming (AmbientBackground.streaming). */
    private static final float SPEED_IDLE = 1f;
    private static final float SPEED_STREAMING = 2f;

    private static final int[] BAYER = {
             0, 32,  8, 40,  2, 34, 10, 42,
            48, 16, 56, 24, 50, 18, 58, 26,
            12, 44,  4, 36, 14, 46,  6, 38,
            60, 28, 52, 20, 62, 30, 54, 22,
             3, 35, 11, 43,  1, 33,  9, 41,
            51, 19, 59, 27, 49, 17, 57, 25,
            15, 47,  7, 39, 13, 45,  5, 37,
            63, 31, 55, 23, 61, 29, 53, 21
    };

    private final Paint washPaint = new Paint();
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crestPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float[] mDepth = new float[LAYERS];
    private final float[] mLevel = new float[LAYERS];
    private final float[] mAmp = new float[LAYERS];
    private final float[] mWavelength = new float[LAYERS];
    private final float[] mTravel = new float[LAYERS];
    private final float[] mSwellAmp = new float[LAYERS];
    private final float[] mSwellPeriod = new float[LAYERS];
    private final float[] mPhase = new float[LAYERS];
    private final float[] mShapeY = new float[LAYERS];
    private final float[] mRiseSpan = new float[LAYERS];
    private final Path[] mCrest = new Path[LAYERS];
    private final Path[] mFill = new Path[LAYERS];
    private final int[] mFillColor = new int[LAYERS];
    private final int[] mCrestColor = new int[LAYERS];
    private float mCrestStrokeWidth = 1f;

    private Bitmap mWash;
    private float mU = 1f;
    private float mT = 0f;
    private boolean mWavesOn = true;
    private float mRise = 1f;

    private float mSpeedFrom = SPEED_IDLE;
    private float mSpeedTo = SPEED_IDLE;
    private long mSpeedStartNanos = 0L;

    private boolean mAttached;
    private boolean mWindowVisible;
    private boolean mFramePosted;
    private long mLastNanos;

    private final Choreographer.FrameCallback mFrame = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            mFramePosted = false;
            if (!animating()) {
                mLastNanos = 0L;
                return;
            }
            if (mLastNanos != 0L) {
                float frameMs = (frameTimeNanos - mLastNanos) / 1_000_000f;
                mT += Math.min(frameMs, MAX_FRAME_MS) * speedNow(frameTimeNanos);
            }
            mLastNanos = frameTimeNanos;
            invalidate();
            postFrame();
        }
    };

    public AmbientFloor(Context context) {
        super(context);
    }

    public AmbientFloor(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public AmbientFloor(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    // ── what a screen may change ─────────────────────────────────────────────────────────────

    /** Draw the waves at all (AmbientBackground.waves, desktop 6.1.0). The wash is always drawn. */
    public void setWaves(boolean on) {
        if (mWavesOn == on) {
            return;
        }
        mWavesOn = on;
        if (on) {
            postFrame();
        }
        invalidate();
    }

    /**
     * A host is streaming right now — the waves travel twice as fast (AmbientBackground.streaming,
     * 6.0.0). Eased in the clock over 1.2s, never restarted: see the class comment.
     */
    public void setStreaming(boolean streaming) {
        float target = streaming ? SPEED_STREAMING : SPEED_IDLE;
        if (target == mSpeedTo) {
            return;
        }
        mSpeedFrom = speedNow(System.nanoTime());
        mSpeedTo = target;
        mSpeedStartNanos = System.nanoTime();
    }

    /**
     * How far the waves have risen: 0 = every layer below the bottom edge, 1 = in place
     * (AmbientWaves.rise, driven on the desktop by the opening animation so the rise, the icon and
     * the sound run on one clock). 1 here — this app has no opening animation of its own to join.
     */
    public void setRise(float rise) {
        mRise = Math.max(0f, Math.min(1f, rise));
        invalidate();
    }

    // ── lifecycle: run only while somebody can see it ────────────────────────────────────────

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mAttached = true;
        postFrame();
    }

    @Override
    protected void onDetachedFromWindow() {
        mAttached = false;
        Choreographer.getInstance().removeFrameCallback(mFrame);
        mFramePosted = false;
        mLastNanos = 0L;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        mWindowVisible = visibility == VISIBLE;
        if (mWindowVisible) {
            postFrame();
        }
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE) {
            postFrame();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        build(w, h);
    }

    private boolean animating() {
        if (!mAttached || !mWindowVisible || !mWavesOn || !isShown() || getWidth() == 0) {
            return false;
        }
        // The system's animation scale — the port's reduceAnimations, already used by the live dot.
        float scale = Settings.Global.getFloat(getContext().getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        return scale != 0f;
    }

    private void postFrame() {
        if (mFramePosted || !animating()) {
            return;
        }
        mFramePosted = true;
        Choreographer.getInstance().postFrameCallback(mFrame);
    }

    /** The eased 1 &lt;-&gt; 2, InOutSine over 1.2s (AmbientWaves.qml:69-72). */
    private float speedNow(long nowNanos) {
        if (mSpeedStartNanos == 0L || mSpeedFrom == mSpeedTo) {
            return mSpeedTo;
        }
        float p = (nowNanos - mSpeedStartNanos) / (SPEED_EASE_MS * 1_000_000f);
        if (p >= 1f) {
            return mSpeedTo;
        }
        float eased = (float) ((1.0 - Math.cos(Math.PI * p)) * 0.5);
        return mSpeedFrom + (mSpeedTo - mSpeedFrom) * eased;
    }

    // ── build once per size ─────────────────────────────────────────────────────────────────

    @SuppressWarnings("deprecation")
    private void build(int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        mU = AmScale.ofScreen(getContext()).uiScale();
        int accent = getResources().getColor(R.color.am_accent);
        float step = Math.max(6f, 10f * mU);
        mCrestStrokeWidth = Math.max(1f, 1.5f * mU);

        for (int i = 0; i < LAYERS; ++i) {
            float depth = i / (float) (LAYERS - 1);
            mDepth[i] = depth;
            float level = h * (0.25f + 0.53f * depth);
            float amp = (12f + 22f * depth) * mU;
            float wavelength = (720f + 780f * depth) * mU;
            float swellAmp = (4f + 6f * depth) * mU;
            // Enough below the bottom edge that neither the swell nor the rise can lift the fill's
            // bottom into view.
            float below = amp + swellAmp + 8f * mU;

            mLevel[i] = level;
            mAmp[i] = amp;
            mWavelength[i] = wavelength;
            mTravel[i] = 34000f - 18000f * depth;
            mSwellAmp[i] = swellAmp;
            mSwellPeriod[i] = 11000f + 2300f * i;
            mPhase[i] = i * 1.7f;
            mShapeY[i] = level - amp;
            mRiseSpan[i] = h - level + amp + swellAmp;

            float shapeHeight = h - level + amp + below;
            float crestW = w + wavelength;

            Path crest = new Path();
            Path fill = new Path();
            boolean first = true;
            for (float px = 0f; px <= crestW; px += step) {
                double a = px / wavelength * 2.0 * Math.PI;
                float y = amp * (float) (1.0 - 0.72 * Math.sin(a + mPhase[i])
                                              - 0.28 * Math.sin(2.0 * a + mPhase[i] * 2.3));
                if (first) {
                    crest.moveTo(px, y);
                    first = false;
                } else {
                    crest.lineTo(px, y);
                }
            }
            fill.addPath(crest);
            fill.lineTo(crestW, shapeHeight);
            fill.lineTo(0f, shapeHeight);
            fill.close();

            mCrest[i] = crest;
            mFill[i] = fill;
            mFillColor[i] = withAlpha(accent, 0.022f + 0.022f * depth);
            mCrestColor[i] = withAlpha(accent, 0.07f + 0.13f * depth);
        }
        buildWash(w, h, accent);
        invalidate();
        // ⚠️ This, not onAttachedToWindow, is what actually starts the clock. Attached runs before
        // the first layout, so getWidth() is still 0 there, animating() refuses, and nothing ever
        // retries — measured on the Fold: the wash drew correctly and not one pixel travelled
        // across two captures four seconds apart. With the size in hand it starts.
        postFrame();
    }

    /** The wash: three stops, dithered, built once — it never moves, only the waves do. */
    private void buildWash(int w, int h, int accent) {
        if (mWash != null && mWash.getHeight() == h) {
            return;
        }
        int top = WASH_TOP;
        int mid = tint(WASH_TOP, accent, WASH_MID_ALPHA);
        int bottom = tint(WASH_TOP, accent, WASH_BOTTOM_ALPHA);
        int[] pixels = new int[8 * h];
        for (int y = 0; y < h; ++y) {
            float p = h > 1 ? (float) y / (h - 1) : 0f;
            int base = p <= WASH_MID_POS
                    ? mix(top, mid, p / WASH_MID_POS)
                    : mix(mid, bottom, (p - WASH_MID_POS) / (1f - WASH_MID_POS));
            for (int x = 0; x < 8; ++x) {
                // Ordered dither, +/- half a level: neutral on average, and it is what keeps the
                // ramp from laying down hard edges in the dark end.
                float d = (BAYER[(y & 7) * 8 + x] / 64f) - 0.5f;
                pixels[y * 8 + x] = offset(base, d);
            }
        }
        Bitmap bmp = Bitmap.createBitmap(8, h, Bitmap.Config.ARGB_8888);
        bmp.setPixels(pixels, 0, 8, 0, 0, 8, h);
        if (mWash != null) {
            mWash.recycle();
        }
        mWash = bmp;
        washPaint.setShader(new BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP));
        washPaint.setFilterBitmap(false);
    }

    // ── draw ────────────────────────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mWash != null) {
            canvas.drawRect(0f, 0f, getWidth(), getHeight(), washPaint);
        }
        if (!mWavesOn || mCrest[0] == null) {
            return;
        }
        for (int i = 0; i < LAYERS; ++i) {
            // Travelling, then swelling, then the rise — the desktop's Shape.x / Shape.y, in the
            // same order and from the same clock.
            float cycles = mT / mTravel[i] + i * 0.37f;
            float frac = cycles - (float) Math.floor(cycles);
            float x = -mWavelength[i] + mWavelength[i] * frac;
            float swell = mSwellAmp[i]
                    * (float) Math.sin(mT / mSwellPeriod[i] * 2.0 * Math.PI + mPhase[i]);
            float unfold = clamp(mRise * 1.6f - mDepth[i] * 0.6f, 0f, 1f);
            float y = mShapeY[i] + swell + mRiseSpan[i] * (1f - unfold);

            canvas.save();
            canvas.translate(x, y);
            fillPaint.setColor(mFillColor[i]);
            canvas.drawPath(mFill[i], fillPaint);
            crestPaint.setColor(mCrestColor[i]);
            crestPaint.setStyle(Paint.Style.STROKE);
            crestPaint.setStrokeWidth(mCrestStrokeWidth);
            crestPaint.setStrokeJoin(Paint.Join.ROUND);
            canvas.drawPath(mCrest[i], crestPaint);
            canvas.restore();
        }
    }

    // ── the desktop's colour arithmetic, in Java ─────────────────────────────────────────────

    /** {@code Qt.tint(base, overlay)} — overlay's colour at overlay's alpha over base. */
    private static int tint(int base, int overlay, float alpha) {
        return mix(base, overlay, alpha);
    }

    private static int mix(int a, int b, float t) {
        float f = clamp(t, 0f, 1f);
        int ar = Color.red(a), ag = Color.green(a), ab = Color.blue(a);
        int br = Color.red(b), bg = Color.green(b), bb = Color.blue(b);
        return Color.rgb(Math.round(ar + (br - ar) * f),
                         Math.round(ag + (bg - ag) * f),
                         Math.round(ab + (bb - ab) * f));
    }

    private static int withAlpha(int rgb, float alpha) {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255f)));
        return (a << 24) | (rgb & 0x00FFFFFF);
    }

    private static int offset(int rgb, float d) {
        return Color.rgb(clampByte(Color.red(rgb) + d),
                         clampByte(Color.green(rgb) + d),
                         clampByte(Color.blue(rgb) + d));
    }

    private static int clampByte(float v) {
        return v < 0f ? 0 : (v > 255f ? 255 : Math.round(v));
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
