package com.chasmet.modeliseur3d.model;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

import com.chasmet.modeliseur3d.performance.DevicePerformanceProfile;

import java.util.ArrayList;
import java.util.List;

/**
 * V5.5 : fabrique six vues cohérentes à partir d'une vraie face et d'un vrai dos.
 * Les vues synthétiques servent de contraintes régulières au moteur 3D local.
 */
public final class FaceBackAutoViewSynthesizer implements AutoCloseable {
    public static final int VIEW_COUNT = 8;
    public static final int OUTPUT_WIDTH = 512;
    public static final int OUTPUT_HEIGHT = 768;

    private static final float[] WIDTH_FACTORS = {
            1.00f, 0.80f, 0.50f, 0.80f,
            1.00f, 0.80f, 0.50f, 0.80f
    };
    private static final float[] BACK_WEIGHTS = {
            0.00f, 0.18f, 0.50f, 0.82f,
            1.00f, 0.82f, 0.50f, 0.18f
    };

    private final AnimeSegmentationEngine segmentation;

    public FaceBackAutoViewSynthesizer(
            Context context,
            DevicePerformanceProfile profile
    ) throws Exception {
        segmentation = new AnimeSegmentationEngine(
                context.getApplicationContext(),
                profile.getNeuralThreadCount()
        );
    }

    public Result synthesize(
            Bitmap front,
            Bitmap back,
            float depthMultiplier,
            ProgressListener listener
    ) throws Exception {
        if (front == null || back == null) {
            throw new IllegalArgumentException("Les images face et dos sont obligatoires");
        }
        notify(listener, 1, 4, "Détourage de la face…");
        Bitmap frontIsolated = isolate(front);
        notify(listener, 2, 4, "Détourage du dos…");
        Bitmap backIsolated = isolate(back);
        Bitmap frontNormalized = null;
        Bitmap backNormalized = null;
        try {
            frontNormalized = normalize(frontIsolated);
            backNormalized = normalize(backIsolated);
            notify(listener, 3, 4, "Alignement de la tête, du centre et des pieds…");
            ArrayList<Bitmap> views = new ArrayList<>(VIEW_COUNT);
            float depth = Math.max(0.72f, Math.min(1.35f, depthMultiplier));
            for (int index = 0; index < VIEW_COUNT; index++) {
                float factor = WIDTH_FACTORS[index];
                if (index == 2 || index == 6) {
                    factor *= depth;
                } else if (index == 1 || index == 3 || index == 5 || index == 7) {
                    factor *= 0.92f + 0.08f * depth;
                }
                Bitmap generated = renderView(
                        frontNormalized,
                        backNormalized,
                        BACK_WEIGHTS[index],
                        factor,
                        index >= 5
                );
                views.add(generated);
            }
            notify(listener, 4, 4, "Six vues automatiques créées.");
            return new Result(views, segmentation.getBackend());
        } finally {
            recycle(frontIsolated);
            recycle(backIsolated);
            recycle(frontNormalized);
            recycle(backNormalized);
        }
    }

    private Bitmap isolate(Bitmap source) throws Exception {
        AnimeSegmentationEngine.Mask mask = segmentation.segment(source);
        return NeuralSheetIsolator.isolate(source, mask);
    }

    private static Bitmap normalize(Bitmap source) {
        Rect bounds = foregroundBounds(source);
        if (bounds.width() < 8 || bounds.height() < 16) {
            throw new IllegalArgumentException("Silhouette trop petite ou non détectée");
        }
        Bitmap output = Bitmap.createBitmap(
                OUTPUT_WIDTH,
                OUTPUT_HEIGHT,
                Bitmap.Config.ARGB_8888
        );
        Canvas canvas = new Canvas(output);
        canvas.drawColor(Color.TRANSPARENT);
        float availableWidth = OUTPUT_WIDTH * 0.78f;
        float availableHeight = OUTPUT_HEIGHT * 0.88f;
        float scale = Math.min(
                availableWidth / bounds.width(),
                availableHeight / bounds.height()
        );
        float targetWidth = bounds.width() * scale;
        float targetHeight = bounds.height() * scale;
        float left = (OUTPUT_WIDTH - targetWidth) * 0.5f;
        float top = OUTPUT_HEIGHT * 0.06f;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(
                source,
                bounds,
                new RectF(left, top, left + targetWidth, top + targetHeight),
                paint
        );
        return output;
    }

    private static Bitmap renderView(
            Bitmap front,
            Bitmap back,
            float backWeight,
            float widthFactor,
            boolean mirror
    ) {
        Bitmap blended = Bitmap.createBitmap(
                OUTPUT_WIDTH,
                OUTPUT_HEIGHT,
                Bitmap.Config.ARGB_8888
        );
        Canvas blendCanvas = new Canvas(blended);
        blendCanvas.drawColor(Color.TRANSPARENT);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        paint.setAlpha(Math.round(255.0f * (1.0f - backWeight)));
        blendCanvas.drawBitmap(front, 0.0f, 0.0f, paint);
        paint.setAlpha(Math.round(255.0f * backWeight));
        blendCanvas.drawBitmap(back, 0.0f, 0.0f, paint);

        Bitmap output = Bitmap.createBitmap(
                OUTPUT_WIDTH,
                OUTPUT_HEIGHT,
                Bitmap.Config.ARGB_8888
        );
        Canvas canvas = new Canvas(output);
        canvas.drawColor(Color.TRANSPARENT);
        float targetWidth = OUTPUT_WIDTH * Math.max(0.34f, Math.min(1.0f, widthFactor));
        float left = (OUTPUT_WIDTH - targetWidth) * 0.5f;
        RectF destination = new RectF(left, 0.0f, left + targetWidth, OUTPUT_HEIGHT);
        if (mirror) {
            canvas.save();
            canvas.scale(-1.0f, 1.0f, OUTPUT_WIDTH * 0.5f, OUTPUT_HEIGHT * 0.5f);
        }
        paint.setAlpha(255);
        canvas.drawBitmap(blended, null, destination, paint);
        if (mirror) {
            canvas.restore();
        }
        blended.recycle();
        return output;
    }

    private static Rect foregroundBounds(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        int left = width;
        int top = height;
        int right = -1;
        int bottom = -1;
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                if (Color.alpha(pixels[row + x]) < 24) {
                    continue;
                }
                left = Math.min(left, x);
                top = Math.min(top, y);
                right = Math.max(right, x);
                bottom = Math.max(bottom, y);
            }
        }
        if (right < left || bottom < top) {
            return new Rect(0, 0, 0, 0);
        }
        return new Rect(left, top, right + 1, bottom + 1);
    }

    private static void notify(
            ProgressListener listener,
            int current,
            int total,
            String message
    ) {
        if (listener != null) {
            listener.onProgress(current, total, message);
        }
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }

    @Override
    public void close() {
        segmentation.close();
    }

    public interface ProgressListener {
        void onProgress(int current, int total, String message);
    }

    public static final class Result {
        private final List<Bitmap> views;
        private final String backend;

        Result(List<Bitmap> views, String backend) {
            this.views = views;
            this.backend = backend;
        }

        public List<Bitmap> getViews() {
            return views;
        }

        public String getBackend() {
            return backend;
        }
    }
}
