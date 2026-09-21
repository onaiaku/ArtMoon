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
 * shrinks it hard (128px wide) and lets the picker's ImageView upsample it with
 * filtering - a cheap, dependency-free soft blur that reads right on a big screen.

 * The veil drawn over it in the layout does the rest of the depth. Results are
 * cached by app id, so roaming the list stays instant after the first pass. If a
 * fetch fails, the backdrop stays hidden - never broken art. Landscape picker
 * only: the layouts carry the view; this is inert where the view is absent。
 */
public class AppAmbientArtwork {

    private static final int MAX_CACHE = 8;
    private static final int AMBIENT_WIDTH = 128;

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
        view.setAlpha(1f);
        AlphaAnimation fade = new AlphaAnimation(0f, 1f);
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
        Bitmap bmp = BitmapFactory.decodeStream(in, null);
        if (bmp == null) return null;
        int w = bmp.getWidth();
        int h = bmp.getHeight();

        if (w <= AMBIENT_WIDTH) return bmp;

        int nw = AMBIENT_WIDTH;
        int nh = Math.max(1, Math.round((float) h * AMBIENT_WIDTH / w));
        Bitmap small = Bitmap.createScaledBitmap(bmp, nw, nh, true);
        bmp.recycle();
        return small;
    }
}