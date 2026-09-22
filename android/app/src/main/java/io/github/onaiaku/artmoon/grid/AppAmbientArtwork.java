package io.github.onaiaku.artmoon.grid;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.View;
import android.view.animation.AlphaAnimation;
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
 * focused app's cover art from the host (/appasset - each app's own box art),
 * downscales it to a sane working size and runs a real blur (three-box Gaussian
 * approximation) plus the desktop layer's desaturation (saturation 0.25). The
 * ImageView then upscales the soft result with filtering, so on a big screen the
 * backdrop reads calm and smooth, like the desktop's blurred cover, not smeared.

 * The veil drawn over it in the layout and the 0.5 art opacity complete the
 * desktop look. Results are cached by app id, so roaming the list stays instant
 * after the first pass. If a fetch fails, the backdrop stays hidden - never
 * broken art. Landscape picker only: the layouts carry the view; this is inert
 * where the view is absent.
 */
public class AppAmbientArtwork {

    private static final int MAX_CACHE = 8;
    private static final int AMBIENT_WIDTH = 480;
    private static final int BLUR_RADIUS = 2;
        private static final float SATURATION = 0.45f;

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

    public void show(final NvApp app, final ImageView view) {
        if (view == null || app == null) return;
        final int appId = app.getAppId();
        currentAppId = appId;

        Bitmap cached = cache.get(appId);
        if (cached != null) {
            apply(view, cached);
            return;
        }

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
        if (view.getVisibility() != View.VISIBLE) view.setVisibility(View.VISIBLE);
        view.setImageBitmap(bmp);
        view.setAlpha(0.75f);
        AlphaAnimation fade = new AlphaAnimation(0f, 0.75f);
        fade.setDuration(220);
        fade.setInterpolator(new DecelerateInterpolator());
        view.startAnimation(fade);
    }

    private Bitmap load(NvApp app) {
        InputStream in = null;
        try {
            NvHTTP http = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer),
                    computer.httpsPort, uniqueId, computer.serverCert,
                    PlatformBinding.getCryptoProvider(context));
            in = http.getBoxArt(app);
            if (in == null) return null;
            return decodeSoft(in);
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) {
                try { in.close(); } catch (Exception ignored) {}
            }
        }
    }

    private Bitmap decodeSoft(InputStream in) {
        Bitmap bmp = BitmapFactory.decodeStream(in, null, null);
        if (bmp == null) return null;
        int w = bmp.getWidth();
        int h = bmp.getHeight();

        Bitmap small;
        if (w <= AMBIENT_WIDTH) {
            small = bmp;
        } else {
            int nh = Math.max(1, Math.round((float) h * AMBIENT_WIDTH / w));
            small = Bitmap.createScaledBitmap(bmp, AMBIENT_WIDTH, nh, true);
            bmp.recycle();
        }

        Bitmap finished = blurAndDesaturate(small);
        if (finished != small) small.recycle();
        return finished;
    }

    /**
     * Desktop CoverAmbient parity in the pixels: a real blur (three-box
     * approximation of a Gaussian) plus the desktop layer's desaturation
     * (saturation 0.25). The ImageView upscales this soft result with
     * filtering, so the backdrop reads smooth and calm at full screen.
     */
    private static Bitmap blurAndDesaturate(Bitmap src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] pix = new int[w * h];
        src.getPixels(pix, 0, w, 0, 0, w, h);

        int[] tmp = new int[w * h];
        for (int i = 0; i < 3; i++) {
            boxBlurH(pix, tmp, w, h, BLUR_RADIUS);
            boxBlurV(tmp, pix, w, h, BLUR_RADIUS);
        }

        for (int i = 0; i < pix.length; i++) {
            int c = pix[i];
            int r = (c >> 16) & 0xFF;
            int g = (c >> 8) & 0xFF;
            int b = c & 0xFF;
            int lum = (r * 77 + g * 151 + b * 28) >> 8;
            r = lum + (int) ((r - lum) * SATURATION);
            g = lum + (int) ((g - lum) * SATURATION);
            b = lum + (int) ((b - lum) * SATURATION);
            pix[i] = 0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
        }

        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(pix, 0, w, 0, 0, w, h);
        return out;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    private static int clampIndex(int i, int limit) {
        return i < 0 ? 0 : (i >= limit ? limit - 1 : i);
    }

    private static void boxBlurH(int[] src, int[] dst, int w, int h, int r) {
        int div = r + r + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int rs = 0, gs = 0, bs = 0;
            for (int x = -r; x <= r; x++) {
                int c = src[row + clampIndex(x, w)];
                rs += (c >> 16) & 0xFF;
                gs += (c >> 8) & 0xFF;
                bs += c & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                dst[row + x] = 0xFF000000 | ((rs / div) << 16) | ((gs / div) << 8) | (bs / div);
                int leave = src[row + clampIndex(x - r, w)];
                int enter = src[row + clampIndex(x + r + 1, w)];
                rs += ((enter >> 16) & 0xFF) - ((leave >> 16) & 0xFF);
                gs += ((enter >> 8) & 0xFF) - ((leave >> 8) & 0xFF);
                bs += (enter & 0xFF) - (leave & 0xFF);
            }
        }
    }

    private static void boxBlurV(int[] src, int[] dst, int w, int h, int r) {
        int div = r + r + 1;
        for (int x = 0; x < w; x++) {
            int rs = 0, gs = 0, bs = 0;
            for (int y = -r; y <= r; y++) {
                int c = src[clampIndex(y, h) * w + x];
                rs += (c >> 16) & 0xFF;
                gs += (c >> 8) & 0xFF;
                bs += c & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                dst[y * w + x] = 0xFF000000 | ((rs / div) << 16) | ((gs / div) << 8) | (bs / div);
                int leave = src[clampIndex(y - r, h) * w + x];
                int enter = src[clampIndex(y + r + 1, h) * w + x];
                rs += ((enter >> 16) & 0xFF) - ((leave >> 16) & 0xFF);
                gs += ((enter >> 8) & 0xFF) - ((leave >> 8) & 0xFF);
                bs += (enter & 0xFF) - (leave & 0xFF);
            }
        }
    }
}