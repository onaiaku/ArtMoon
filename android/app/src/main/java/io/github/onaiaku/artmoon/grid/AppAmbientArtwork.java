package io.github.onaiaku.artmoon.grid;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;

import io.github.onaiaku.artmoon.binding.PlatformBinding;
import io.github.onaiaku.artmoon.nvstream.http.ComputerDetails;
import io.github.onaiaku.artmoon.nvstream.http.NvApp;
import io.github.onaiaku.artmoon.nvstream.http.NvHTTP;
import io.github.onaiaku.artmoon.utils.ServerHelper;

import java.io.InputStream;
import java.util.HashMap;

/**
 * Ambient backdrop for the app picker (desktop CoverAmbient parity): fetches the
 * focused app's cover art from the host (/appasset — the same art the rows use),
 * downscales it, blurs it (RenderScript) and paints it behind the whole picker,
 * crossfading as the selection moves. Results are cached by app id so roaming
 * the list stays instant after the first pass. If RenderScript is unavailable
 * (API 31+ devices), it falls back to the sharp art — the veil still gives the
 * cover depth. Landscape picker only; the layouts carry the view, this is inert
 * where the view is absent.
 */
public class AppAmbientArtwork {
    private static final int MAX_CACHE = 8;
    private static final float BLUR_RADIUS = 22f;

    private final Context context;
    private final ComputerDetails computer;
    private final String uniqueId;

    private final HashMap<Integer, Bitmap> cache = new HashMap<>();
    private final HashMap<Integer, Boolean> inFlight = new HashMap<>();
    private volatile int currentAppId = -1;

    public AppAmbientArtwork(Context context, ComputerDetails computer, String uniqueId) {
        this.context = context;
        this.computer = computer;
        this.uniqueId = uniqueId;
    }

    /** Paint the app's cover, blurred, into the picker's ambient layer. */
    public void show(final NvApp app, final ImageView view) {
        if (view == null || app == null) return;
        final int appId = app.getAppId();
        currentAppId = appId;

        Bitmap cached = cache.get(appId);
        if (cached != null) {
            apply(view, cached);
            return;
        }

        // Only one load per app at a time;further requests wait for the current one.
        Boolean prev = inFlight.put(appId, Boolean.TRUE);
        if (prev != null) return;

        new Thread(new Runnable() {
            @Override
            public void run() {
                Bitmap bmp = load(app);
                if (bmp == null) {
                    inFlight.remove(appId);
                    return;
                }
                cache.put(appId, bmp);
                if (cache.size() > MAX_CACHE) cache.clear();
                // A newer selection may have landed while we fetched: cache the art but
                // do not paint a stale cover over the current selection.

                if (currentAppId != appId) {
                    inFlight.remove(appId);
                    return;
                }
                final Bitmap fb = bmp;
                final int id = appId;
                view.post(new Runnable() {
                    @Override
                    public void run() {
                        if (currentAppId == id) apply(view, fb);
                        inFlight.remove(id);
                    }
                });
            }
        }).start();
    }

    private void apply(ImageView view, Bitmap bmp) {
        if (view.getVisibility() != View.VISIBLE) {



view.setVisibility(View.VISIBLE);
        }
        view.setImageBitmap(bmp);
        view.setAlpha(0f);
        view.animate().alpha(1f).setDuration(220).setInterpolator(new DecelerateInterpolator()).start();
    }

    private Bitmap load(NvApp app) {

InputStream in = null;
        try {
            NvHTTP http = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer),
                    computer.httpsPort, uniqueId, computer.serverCert,
                    PlatformBinding.getCryptoProvider(context));
            in = http.getBoxArt(app);
            if (in == null) return null;

            Bitmap bmp = decodeDownscaled(in);
            if (bmp == null) return null;
            Bitmap blurred = blur(bmp, bmp);
            if (blurred != bmp) bmp.recycle();
            return blurred;

        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) {
                try { in.close(); } catch (Exception ignored) {}
            }
        }
    }

    /** Decode with a coarse sample so the blur pass operates on a small canvas. */
    private Bitmap decodeDownscaled(InputStream in) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 2;
        return BitmapFactory.decodeStream(in, null, opts);
    }

    /** RenderScript blur; falls back to the sharp bitmap on any failure. */
    private Bitmap blur(Bitmap in, Bitmap fallback) {



try {
            android.renderscript.RenderScript rs = android.renderscript.RenderScript.create(context);
            android.renderscript.Allocation input = android.renderscript.Allocation.createFromBitmap(rs, in);
            android.renderscript.Allocation output = android.renderscript.Allocation.createTyped(rs, input.getType());
            android.renderscript.ScriptIntrinsicBlur sb = android.renderscript.ScriptIntrinsicBlur.create(rs, android.renderscript.Element.U8_4(rs));
            sb.setRadius(BLUR_RADIUS);
            sb.setInput(input);
            sb.forEach(output);
            Bitmap out = Bitmap.createBitmap(in.getWidth(, in.getHeight(, in.getConfig() != null ? in.getConfig() : Bitmap.Config.ARGB_8888);
            output.copyTo(out);
            sb.destroy();
            input.destroy();
            output.destroy();
            rs.destroy();
            return out;

        } catch (Throwable t) {
            return fallback;

        }
    }
}