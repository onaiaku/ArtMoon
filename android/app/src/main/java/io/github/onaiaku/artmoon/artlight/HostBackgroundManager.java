package io.github.onaiaku.artmoon.artlight;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * HostBackgroundManager — per-PC hero card background images (Nik's spec:
 * long-press hero box → "Set a background" → photo picker → image becomes
 * the PC's hero card background, fill the card).
 *
 * Design:
 *  - One background per PC, keyed by the PC's UUID, so each computer in the
 *    list carries its own picture.
 *  - The picked image is COPIED into the app's private storage at pick time.
 *    It therefore survives gallery cleanups, cloud-provider unavailability,
 *    and app restarts, and needs no persistent URI permissions.
 *  - Rendered full-bleed behind the existing dark scrim, so text stays
 *    readable over any photo.
 */
public final class HostBackgroundManager {

    private static final int MAX_DIMENSION = 1920; // decode cap — cards never need more
    private static final int SAMPLE_TARGET = 1200; // inSampleSize target

    private final Context context;
    private final File dir;

    public HostBackgroundManager(Context context) {
        this.context = context.getApplicationContext();
        this.dir = this.context.getDir("hero_bg", Context.MODE_PRIVATE);
    }

    private File fileFor(String uuid) {
        // UUIDs are hex/dashes — safe as filenames
        return new File(dir, "hero_" + uuid.replaceAll("[^A-Za-z0-9_-]", "_") + ".img");
    }

    public boolean has(String uuid) {
        return uuid != null && fileFor(uuid).exists() && fileFor(uuid).length() > 0;
    }

    /**
     * Copy the picked image into private storage, downsampled to card size.
     * Returns true on success; false leaves any previous background intact.
     */
    public boolean set(String uuid, Uri source) {
        if (uuid == null || source == null) {
            return false;
        }
        try {
            Bitmap bmp = decodeDownsampled(source);
            if (bmp == null) {
                return false;
            }
            File dst = fileFor(uuid);
            File tmp = new File(dir, dst.getName() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)) {
                out.close();
                tmp.delete();
                return false;
            }
            out.close();
            // Atomic-ish swap so a crash mid-copy can't leave a corrupt file
            if (dst.exists()) {
                dst.delete();
            }
            tmp.renameTo(dst);
            return true;
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    public void remove(String uuid) {
        if (uuid == null) {
            return;
        }
        fileFor(uuid).delete();
    }

    /** Load the stored background, already downsampled at set() time. */
    public Bitmap load(String uuid) {
        if (!has(uuid)) {
            return null;
        }
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeFile(fileFor(uuid).getAbsolutePath(), o);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    private Bitmap decodeDownsampled(Uri src) throws IOException, SecurityException {
        InputStream is = context.getContentResolver().openInputStream(src);
        if (is == null) {
            return null;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeStream(is, null, bounds);
        is.close();

        int w = bounds.outWidth, h = bounds.outHeight;
        if (w <= 0 || h <= 0) {
            return null;
        }
        int sample = 1;
        while ((w / sample) > SAMPLE_TARGET * 2 || (h / sample) > SAMPLE_TARGET * 2) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        is = context.getContentResolver().openInputStream(src);
        if (is == null) {
            return null;
        }
        Bitmap bmp;
        try {
            bmp = BitmapFactory.decodeStream(is, null, opts);
        } catch (OutOfMemoryError e) {
            bmp = null;
        }
        is.close();
        if (bmp == null) {
            return null;
        }
        // Hard cap the long edge
        int maxEdge = Math.max(bmp.getWidth(), bmp.getHeight());
        if (maxEdge > MAX_DIMENSION) {
            float scale = (float) MAX_DIMENSION / maxEdge;
            Matrix m = new Matrix();
            m.postScale(scale, scale);
            Bitmap scaled = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (scaled != bmp) {
                bmp.recycle();
            }
            bmp = scaled;
        }
        return bmp;
    }
}
