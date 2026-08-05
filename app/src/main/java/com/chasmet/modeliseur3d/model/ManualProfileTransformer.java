package com.chasmet.modeliseur3d.model;

import android.graphics.Bitmap;
import android.graphics.Matrix;

/** Applique exactement la rotation et le miroir choisis sous les profils. */
public final class ManualProfileTransformer {
    private ManualProfileTransformer() {
    }

    public static Bitmap apply(Bitmap source, int rotationDegrees, boolean mirrored) {
        if (source == null || source.isRecycled()) {
            throw new IllegalArgumentException("Image de profil invalide");
        }
        int rotation = normalizeRotation(rotationDegrees);
        if (rotation == 0 && !mirrored) {
            return source;
        }
        Matrix matrix = new Matrix();
        matrix.postRotate(rotation);
        if (mirrored) {
            matrix.postScale(-1.0f, 1.0f);
        }
        return Bitmap.createBitmap(
                source,
                0,
                0,
                source.getWidth(),
                source.getHeight(),
                matrix,
                true
        );
    }

    public static int normalizeRotation(int rotationDegrees) {
        int rotation = rotationDegrees % 360;
        if (rotation < 0) {
            rotation += 360;
        }
        return rotation;
    }

    public static String stateLabel(int rotationDegrees, boolean mirrored) {
        return "Rotation " + normalizeRotation(rotationDegrees) + "° • "
                + (mirrored ? "MIROIR" : "NORMAL");
    }
}
