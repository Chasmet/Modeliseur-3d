package com.chasmet.modeliseur3d.model;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

import com.chasmet.modeliseur3d.performance.DevicePerformanceProfile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Stabilise les huit vues autour d'un même centre sans redimensionner chaque
 * silhouette séparément. Cette règle est indispensable à une vraie fusion 3D :
 * une vue de profil doit rester plus étroite qu'une vue de face.
 */
public final class VideoSubjectNormalizer {
    private static final int VIEW_COUNT = 8;
    private static final int ANALYSIS_SIDE = 256;
    private static final float TARGET_HEIGHT_RATIO = 0.90f;

    private VideoSubjectNormalizer() {
    }

    public static Result normalize(
            List<Bitmap> frames,
            DevicePerformanceProfile profile
    ) {
        if (frames == null || frames.size() != VIEW_COUNT) {
            throw new IllegalArgumentException("Huit vues vidéo sont requises");
        }
        if (profile == null) {
            throw new IllegalArgumentException("Profil de calcul absent");
        }

        Rect[] detectedCrops = new Rect[VIEW_COUNT];
        float[] centerX = new float[VIEW_COUNT];
        float[] centerY = new float[VIEW_COUNT];
        float[] widthRatio = new float[VIEW_COUNT];
        float[] heightRatio = new float[VIEW_COUNT];
        boolean[] valid = new boolean[VIEW_COUNT];
        int detected = 0;

        for (int index = 0; index < VIEW_COUNT; index++) {
            Bitmap frame = frames.get(index);
            if (frame == null || frame.isRecycled()) {
                throw new IllegalArgumentException("Une vue vidéo est invalide");
            }
            Rect crop = detectSubject(frame);
            detectedCrops[index] = crop;
            if (crop == null) {
                continue;
            }
            valid[index] = true;
            detected++;
            centerX[index] = crop.exactCenterX() / frame.getWidth();
            centerY[index] = crop.exactCenterY() / frame.getHeight();
            widthRatio[index] = crop.width() / (float) frame.getWidth();
            heightRatio[index] = crop.height() / (float) frame.getHeight();
        }

        if (detected < 3) {
            throw new IllegalArgumentException(
                    "Le personnage n'est pas détectable dans assez de vues vidéo"
            );
        }

        float medianCenterX = medianValid(centerX, valid);
        float medianCenterY = medianValid(centerY, valid);
        float medianHeight = medianValid(heightRatio, valid);
        float robustWidth = percentileValid(widthRatio, valid, 0.75f);
        if (medianHeight <= 0.02f) {
            throw new IllegalArgumentException("Le personnage vidéo est trop petit");
        }

        int outputHeight = profile.getVideoNormalizationSide();
        float subjectAspect = robustWidth / medianHeight;
        float canvasAspect = clamp(
                subjectAspect * TARGET_HEIGHT_RATIO + 0.10f,
                0.58f,
                0.84f
        );
        int outputWidth = Math.max(360, Math.round(outputHeight * canvasAspect));

        List<Bitmap> normalized = new ArrayList<>(VIEW_COUNT);
        try {
            for (int index = 0; index < VIEW_COUNT; index++) {
                Bitmap frame = frames.get(index);
                Rect crop = detectedCrops[index];
                if (crop == null) {
                    crop = estimatedCrop(
                            frame,
                            medianCenterX,
                            medianCenterY,
                            percentileValid(widthRatio, valid, 0.50f),
                            medianHeight
                    );
                }
                normalized.add(drawNormalized(
                        frame,
                        crop,
                        medianHeight,
                        outputWidth,
                        outputHeight
                ));
            }
            return new Result(normalized, detected);
        } catch (RuntimeException | OutOfMemoryError error) {
            recycle(normalized);
            throw error;
        }
    }

    private static Rect estimatedCrop(
            Bitmap frame,
            float centerX,
            float centerY,
            float widthRatio,
            float heightRatio
    ) {
        int width = Math.max(2, Math.round(frame.getWidth() * widthRatio));
        int height = Math.max(2, Math.round(frame.getHeight() * heightRatio));
        int cx = Math.round(frame.getWidth() * centerX);
        int cy = Math.round(frame.getHeight() * centerY);
        int left = clamp(cx - width / 2, 0, Math.max(0, frame.getWidth() - 2));
        int top = clamp(cy - height / 2, 0, Math.max(0, frame.getHeight() - 2));
        int right = clamp(left + width, left + 1, frame.getWidth());
        int bottom = clamp(top + height, top + 1, frame.getHeight());
        return new Rect(left, top, right, bottom);
    }

    private static Rect detectSubject(Bitmap source) {
        int[] dimensions = fitInside(
                source.getWidth(),
                source.getHeight(),
                ANALYSIS_SIDE
        );
        Bitmap small = Bitmap.createScaledBitmap(
                source,
                dimensions[0],
                dimensions[1],
                true
        );
        try {
            int width = small.getWidth();
            int height = small.getHeight();
            int[] pixels = new int[width * height];
            small.getPixels(pixels, 0, width, 0, 0, width, height);
            float[] background = borderColor(pixels, width, height);
            boolean[] mask = new boolean[pixels.length];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int index = y * width + x;
                    int color = pixels[index];
                    float dr = Color.red(color) - background[0];
                    float dg = Color.green(color) - background[1];
                    float db = Color.blue(color) - background[2];
                    float distance = (float) Math.sqrt(
                            dr * dr + dg * dg + db * db
                    );
                    float centerWeight = 1.0f - Math.min(
                            1.0f,
                            Math.abs(x - width * 0.5f) / Math.max(1.0f, width * 0.5f)
                    );
                    float threshold = 31.0f - centerWeight * 5.0f;
                    mask[index] = distance >= threshold;
                }
            }

            SingleSubjectSelector.Selection selection;
            try {
                selection = SingleSubjectSelector.select(mask, width, height);
            } catch (IllegalArgumentException error) {
                return null;
            }
            int selectedArea = selection.getSelectedArea();
            if (selectedArea < width * height / 190
                    || selectedArea > width * height * 0.92f) {
                return null;
            }

            float scaleX = source.getWidth() / (float) width;
            float scaleY = source.getHeight() / (float) height;
            int left = Math.round(selection.getLeft() * scaleX);
            int top = Math.round(selection.getTop() * scaleY);
            int right = Math.round((selection.getRight() + 1) * scaleX);
            int bottom = Math.round((selection.getBottom() + 1) * scaleY);
            int marginX = Math.max(4, Math.round((right - left) * 0.055f));
            int marginY = Math.max(4, Math.round((bottom - top) * 0.045f));
            return new Rect(
                    clamp(left - marginX, 0, source.getWidth() - 1),
                    clamp(top - marginY, 0, source.getHeight() - 1),
                    clamp(right + marginX, left + 1, source.getWidth()),
                    clamp(bottom + marginY, top + 1, source.getHeight())
            );
        } finally {
            if (small != source && !small.isRecycled()) {
                small.recycle();
            }
        }
    }

    private static Bitmap drawNormalized(
            Bitmap source,
            Rect subject,
            float globalHeightRatio,
            int outputWidth,
            int outputHeight
    ) {
        Bitmap output = Bitmap.createBitmap(
                outputWidth,
                outputHeight,
                Bitmap.Config.ARGB_8888
        );
        Canvas canvas = new Canvas(output);
        canvas.drawColor(Color.BLACK);

        float referenceHeight = Math.max(
                2.0f,
                source.getHeight() * globalHeightRatio
        );
        float scale = outputHeight * TARGET_HEIGHT_RATIO / referenceHeight;
        float centerX = subject.exactCenterX();
        float centerY = subject.exactCenterY();
        float destinationLeft = outputWidth * 0.5f - centerX * scale;
        float destinationTop = outputHeight * 0.5f - centerY * scale;

        Paint paint = new Paint(
                Paint.ANTI_ALIAS_FLAG
                        | Paint.FILTER_BITMAP_FLAG
                        | Paint.DITHER_FLAG
        );
        canvas.drawBitmap(
                source,
                null,
                new RectF(
                        destinationLeft,
                        destinationTop,
                        destinationLeft + source.getWidth() * scale,
                        destinationTop + source.getHeight() * scale
                ),
                paint
        );
        return output;
    }

    private static float[] borderColor(int[] pixels, int width, int height) {
        long red = 0L;
        long green = 0L;
        long blue = 0L;
        int count = 0;
        int step = Math.max(1, Math.min(width, height) / 64);
        for (int x = 0; x < width; x += step) {
            int top = pixels[x];
            int bottom = pixels[(height - 1) * width + x];
            red += Color.red(top) + Color.red(bottom);
            green += Color.green(top) + Color.green(bottom);
            blue += Color.blue(top) + Color.blue(bottom);
            count += 2;
        }
        for (int y = step; y < height - step; y += step) {
            int left = pixels[y * width];
            int right = pixels[y * width + width - 1];
            red += Color.red(left) + Color.red(right);
            green += Color.green(left) + Color.green(right);
            blue += Color.blue(left) + Color.blue(right);
            count += 2;
        }
        return new float[]{
                red / (float) Math.max(1, count),
                green / (float) Math.max(1, count),
                blue / (float) Math.max(1, count)
        };
    }

    private static float medianValid(float[] values, boolean[] valid) {
        return percentileValid(values, valid, 0.50f);
    }

    private static float percentileValid(
            float[] values,
            boolean[] valid,
            float percentile
    ) {
        float[] copy = new float[values.length];
        int count = 0;
        for (int index = 0; index < values.length; index++) {
            if (valid[index]) {
                copy[count++] = values[index];
            }
        }
        if (count == 0) {
            return 0.0f;
        }
        Arrays.sort(copy, 0, count);
        int position = Math.round(clamp(percentile, 0.0f, 1.0f) * (count - 1));
        return copy[Math.max(0, Math.min(count - 1, position))];
    }

    private static int[] fitInside(int width, int height, int maximumSide) {
        float scale = Math.min(
                1.0f,
                maximumSide / (float) Math.max(width, height)
        );
        return new int[]{
                Math.max(2, Math.round(width * scale)),
                Math.max(2, Math.round(height * scale))
        };
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static void recycle(List<Bitmap> bitmaps) {
        for (Bitmap bitmap : bitmaps) {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
    }

    public static final class Result implements AutoCloseable {
        private final List<Bitmap> frames;
        private final int detectedFrameCount;

        Result(List<Bitmap> frames, int detectedFrameCount) {
            this.frames = frames;
            this.detectedFrameCount = detectedFrameCount;
        }

        public List<Bitmap> getFrames() {
            return frames;
        }

        public int getDetectedFrameCount() {
            return detectedFrameCount;
        }

        @Override
        public void close() {
            recycle(frames);
        }
    }
}
