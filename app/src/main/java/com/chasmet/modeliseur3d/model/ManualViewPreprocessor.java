package com.chasmet.modeliseur3d.model;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

import com.chasmet.modeliseur3d.ManualViewPlan;

/**
 * Valide et normalise une photo avant la reconstruction 3D guidée.
 * Toutes les vues ressortent avec la tête, le centre et les pieds alignés.
 */
public final class ManualViewPreprocessor {
    private static final int OUTPUT_WIDTH = 512;
    private static final int OUTPUT_HEIGHT = 768;
    private static final int ALPHA_THRESHOLD = 24;

    public Result process(
            Bitmap source,
            AnimeSegmentationEngine segmentation,
            int viewIndex
    ) throws Exception {
        if (source == null || source.isRecycled()) {
            throw new IllegalArgumentException("Image absente");
        }
        if (segmentation == null) {
            throw new IllegalArgumentException("Moteur de détourage absent");
        }

        Bitmap isolated = null;
        try {
            AnimeSegmentationEngine.Mask mask = segmentation.segment(source);
            isolated = NeuralSheetIsolator.isolate(source, mask);
            Foreground foreground = findForeground(isolated);
            Metrics metrics = measure(source, foreground, viewIndex);
            Bitmap normalized = normalize(isolated, foreground.bounds);
            int score = score(metrics, viewIndex);
            String rejection = rejectionReason(metrics, viewIndex);
            boolean accepted = rejection == null && score >= 60;
            String message = accepted
                    ? "Conforme au gabarit • " + score + "%"
                    : (rejection != null
                    ? rejection
                    : "Silhouette trop différente du gabarit");
            return new Result(
                    normalized,
                    accepted,
                    score,
                    message,
                    metrics.silhouetteAspect,
                    metrics.heightRatio,
                    metrics.centerOffset,
                    metrics.fillRatio
            );
        } finally {
            if (isolated != null && !isolated.isRecycled()) {
                isolated.recycle();
            }
        }
    }

    private static Metrics measure(
            Bitmap source,
            Foreground foreground,
            int viewIndex
    ) {
        Rect bounds = foreground.bounds;
        float width = Math.max(1.0f, source.getWidth());
        float height = Math.max(1.0f, source.getHeight());
        float subjectWidth = bounds.width();
        float subjectHeight = bounds.height();
        float heightRatio = subjectHeight / height;
        float widthRatio = subjectWidth / width;
        float silhouetteAspect = subjectWidth / Math.max(1.0f, subjectHeight);
        float centerX = bounds.exactCenterX() / width;
        float centerOffset = Math.abs(centerX - 0.5f);
        float topMargin = bounds.top / height;
        float bottomMargin = (source.getHeight() - bounds.bottom) / height;
        float leftMargin = bounds.left / width;
        float rightMargin = (source.getWidth() - bounds.right) / width;
        float fillRatio = foreground.pixelCount
                / Math.max(1.0f, subjectWidth * subjectHeight);
        return new Metrics(
                heightRatio,
                widthRatio,
                silhouetteAspect,
                centerOffset,
                topMargin,
                bottomMargin,
                leftMargin,
                rightMargin,
                fillRatio,
                ManualViewPlan.getTargetAspectRatio(viewIndex)
        );
    }

    private static int score(Metrics metrics, int viewIndex) {
        float value = 100.0f;
        value -= Math.abs(metrics.heightRatio - 0.82f) * 52.0f;
        value -= metrics.centerOffset * 125.0f;
        value -= Math.abs(
                metrics.silhouetteAspect - metrics.targetAspect
        ) * 42.0f;

        if (metrics.topMargin < 0.008f) {
            value -= 16.0f;
        }
        if (metrics.bottomMargin < 0.006f) {
            value -= 18.0f;
        }
        if (metrics.leftMargin < 0.004f || metrics.rightMargin < 0.004f) {
            value -= 12.0f;
        }
        if (metrics.fillRatio < 0.24f) {
            value -= (0.24f - metrics.fillRatio) * 90.0f;
        }
        if (metrics.fillRatio > 0.88f) {
            value -= (metrics.fillRatio - 0.88f) * 80.0f;
        }
        if (ManualViewPlan.isProfile(viewIndex)
                && metrics.silhouetteAspect > 0.56f) {
            value -= 18.0f;
        }
        if (ManualViewPlan.isFrontOrBack(viewIndex)
                && metrics.silhouetteAspect < 0.22f) {
            value -= 18.0f;
        }
        return Math.max(0, Math.min(100, Math.round(value)));
    }

    private static String rejectionReason(Metrics metrics, int viewIndex) {
        if (metrics.heightRatio < 0.44f || metrics.widthRatio < 0.10f) {
            return "Sujet trop petit : rapproche ou agrandis l’image";
        }
        if (metrics.topMargin < 0.002f || metrics.bottomMargin < 0.002f) {
            return "Corps coupé : tête et pieds doivent être visibles";
        }
        if (metrics.centerOffset > 0.19f) {
            return "Sujet trop décalé : place-le au centre de la silhouette";
        }
        if (metrics.fillRatio < 0.16f) {
            return "Détourage incomplet ou fond trop complexe";
        }
        if (ManualViewPlan.isProfile(viewIndex)
                && metrics.silhouetteAspect > 0.64f) {
            return "Profil pas assez latéral : tourne le sujet à 90°";
        }
        if (ManualViewPlan.isFrontOrBack(viewIndex)
                && metrics.silhouetteAspect < 0.17f) {
            return "Vue trop fine : utilise une vraie face ou un vrai dos";
        }
        return null;
    }

    private static Bitmap normalize(Bitmap isolated, Rect bounds) {
        Bitmap output = Bitmap.createBitmap(
                OUTPUT_WIDTH,
                OUTPUT_HEIGHT,
                Bitmap.Config.ARGB_8888
        );
        Canvas canvas = new Canvas(output);
        canvas.drawColor(Color.TRANSPARENT);

        float targetHeight = OUTPUT_HEIGHT * 0.90f;
        float scale = targetHeight / Math.max(1.0f, bounds.height());
        float targetWidth = bounds.width() * scale;
        float maximumWidth = OUTPUT_WIDTH * 0.84f;
        if (targetWidth > maximumWidth) {
            float correction = maximumWidth / targetWidth;
            targetWidth *= correction;
            targetHeight *= correction;
        }

        float bottom = OUTPUT_HEIGHT * 0.965f;
        float top = bottom - targetHeight;
        float left = (OUTPUT_WIDTH - targetWidth) * 0.5f;
        RectF destination = new RectF(
                left,
                top,
                left + targetWidth,
                bottom
        );
        Paint paint = new Paint(
                Paint.ANTI_ALIAS_FLAG
                        | Paint.FILTER_BITMAP_FLAG
                        | Paint.DITHER_FLAG
        );
        canvas.drawBitmap(isolated, bounds, destination, paint);
        return output;
    }

    private static Foreground findForeground(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        int left = width;
        int top = height;
        int right = -1;
        int bottom = -1;
        int count = 0;
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                if (Color.alpha(pixels[row + x]) <= ALPHA_THRESHOLD) {
                    continue;
                }
                count++;
                left = Math.min(left, x);
                top = Math.min(top, y);
                right = Math.max(right, x);
                bottom = Math.max(bottom, y);
            }
        }
        if (right < left || bottom < top || count < Math.max(64, width * height / 5000)) {
            throw new IllegalArgumentException(
                    "Aucune silhouette complète n’a été détectée"
            );
        }
        return new Foreground(
                new Rect(left, top, right + 1, bottom + 1),
                count
        );
    }

    private static final class Foreground {
        final Rect bounds;
        final int pixelCount;

        Foreground(Rect bounds, int pixelCount) {
            this.bounds = bounds;
            this.pixelCount = pixelCount;
        }
    }

    private static final class Metrics {
        final float heightRatio;
        final float widthRatio;
        final float silhouetteAspect;
        final float centerOffset;
        final float topMargin;
        final float bottomMargin;
        final float leftMargin;
        final float rightMargin;
        final float fillRatio;
        final float targetAspect;

        Metrics(
                float heightRatio,
                float widthRatio,
                float silhouetteAspect,
                float centerOffset,
                float topMargin,
                float bottomMargin,
                float leftMargin,
                float rightMargin,
                float fillRatio,
                float targetAspect
        ) {
            this.heightRatio = heightRatio;
            this.widthRatio = widthRatio;
            this.silhouetteAspect = silhouetteAspect;
            this.centerOffset = centerOffset;
            this.topMargin = topMargin;
            this.bottomMargin = bottomMargin;
            this.leftMargin = leftMargin;
            this.rightMargin = rightMargin;
            this.fillRatio = fillRatio;
            this.targetAspect = targetAspect;
        }
    }

    public static final class Result implements AutoCloseable {
        private final Bitmap normalizedBitmap;
        private final boolean accepted;
        private final int score;
        private final String message;
        private final float silhouetteAspect;
        private final float heightRatio;
        private final float centerOffset;
        private final float fillRatio;

        Result(
                Bitmap normalizedBitmap,
                boolean accepted,
                int score,
                String message,
                float silhouetteAspect,
                float heightRatio,
                float centerOffset,
                float fillRatio
        ) {
            this.normalizedBitmap = normalizedBitmap;
            this.accepted = accepted;
            this.score = score;
            this.message = message;
            this.silhouetteAspect = silhouetteAspect;
            this.heightRatio = heightRatio;
            this.centerOffset = centerOffset;
            this.fillRatio = fillRatio;
        }

        public Bitmap getNormalizedBitmap() {
            return normalizedBitmap;
        }

        public boolean isAccepted() {
            return accepted;
        }

        public int getScore() {
            return score;
        }

        public String getMessage() {
            return message;
        }

        public float getSilhouetteAspect() {
            return silhouetteAspect;
        }

        public float getHeightRatio() {
            return heightRatio;
        }

        public float getCenterOffset() {
            return centerOffset;
        }

        public float getFillRatio() {
            return fillRatio;
        }

        @Override
        public void close() {
            if (!normalizedBitmap.isRecycled()) {
                normalizedBitmap.recycle();
            }
        }
    }
}
