package com.chasmet.modeliseur3d.util;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.IOException;
import java.io.InputStream;

public final class BitmapUtils {
    private BitmapUtils() {
    }

    public static Bitmap decodeBitmapFromUri(ContentResolver resolver, Uri uri, int maxSide)
            throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream stream = resolver.openInputStream(uri)) {
            if (stream == null) {
                throw new IOException("Flux image indisponible");
            }
            BitmapFactory.decodeStream(stream, null, bounds);
        }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("Dimensions d'image invalides");
        }

        int sampleSize = 1;
        while (Math.max(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) > maxSide * 2) {
            sampleSize *= 2;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;

        Bitmap decoded;
        try (InputStream stream = resolver.openInputStream(uri)) {
            if (stream == null) {
                throw new IOException("Flux image indisponible");
            }
            decoded = BitmapFactory.decodeStream(stream, null, options);
        }

        if (decoded == null) {
            throw new IOException("Décodage impossible");
        }

        int width = decoded.getWidth();
        int height = decoded.getHeight();
        int largest = Math.max(width, height);
        if (largest <= maxSide) {
            return decoded;
        }

        float scale = maxSide / (float) largest;
        int targetWidth = Math.max(1, Math.round(width * scale));
        int targetHeight = Math.max(1, Math.round(height * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true);
        if (scaled != decoded) {
            decoded.recycle();
        }
        return scaled;
    }
}
