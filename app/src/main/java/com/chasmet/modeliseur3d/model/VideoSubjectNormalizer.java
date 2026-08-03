package com.chasmet.modeliseur3d.model;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

import com.chasmet.modeliseur3d.performance.DevicePerformanceProfile;

import java.util.ArrayList;
import java.util.List;

/** Recentre et égalise l'échelle du sujet avant les huit passes vidéo. */
public final class VideoSubjectNormalizer {
    private static final int ANALYSIS_SIDE = 256;

    private VideoSubjectNormalizer() {
    }

    public static Result normalize(
            List<Bitmap> frames,
            DevicePerformanceProfile profile
    ) {
        if (frames == null || frames.size() != 8) {
            throw new IllegalArgumentException("Huit vues vidéo sont requises");
        }
        int outputHeight = profile.getVideoNormalizationSide();
        int outputWidth = Math.max(480, Math.round(outputHeight * 0.72f));
        List<Bitmap> normalized = new ArrayList<>(frames.size());
        int detected = 0;
        try {
            for (Bitmap frame : frames) {
                Rect crop = detectSubject(frame);
                if (crop != null) {
                    detected++;
                } else {
                    crop = new Rect(0, 0, frame.getWidth(), frame.getHeight());
                }
                normalized.add(drawNormalized(
                        frame,
                        crop,
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
                    float centerX = 1.0f - Math.abs(
                            x - (width - 1) * 0.5f
                    ) / Math.max(1.0f, width * 0.5f);
                    float centerY = 1.0f - Math.abs(
                            y - (height - 1) * 0.5f
                    ) / Math.max(1.0f, height * 0.5f);
                    float threshold = 34.0f
                            - 7.0f * Math.max(0.0f, centerX * centerY);
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
            if (selectedArea < width * height / 180
                    || selectedArea > width * height * 0.92f) {
                return null;
            }
            float scaleX = source.getWidth() / (float) width;
            float scaleY = source.getHeight() / (float) height;
            int left = Math.round(selection.getLeft() * scaleX);
            int top = Math.round(selection.getTop() * scaleY);
            int right = Math.round((selection.getRight() + 1) * scaleX);
            int bottom = Math.round((selection.getBottom() + 1) * scaleY);
            int marginX = Math.max(4, Math.round((right - left) * 0.09f));
            int marginY = Math.max(4, Math.round((bottom - top) * 0.07f));
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
            Rect crop,
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
        float scale = Math.min(
                outputWidth * 0.92f / crop.width(),
                outputHeight * 0.92f / crop.height()
        );
        float width = crop.width() * scale;
        float height = crop.height() * scale;
        Paint paint = new Paint(
                Paint.ANTI_ALIAS_FLAG
                        | Paint.FILTER_BITMAP_FLAG
                        | Paint.DITHER_FLAG
        );
        canvas.drawBitmap(
                source,
                crop,
                new RectF(
                        (outputWidth - width) * 0.5f,
                        (outputHeight - height) * 0.5f,
                        (outputWidth + width) * 0.5f,
                        (outputHeight + height) * 0.5f
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
