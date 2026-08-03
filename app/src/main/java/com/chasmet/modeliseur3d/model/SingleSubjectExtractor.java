package com.chasmet.modeliseur3d.model;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;

import java.util.Arrays;

/**
 * Convertit le masque neuronal d'une image ou d'une planche de références en
 * une seule image détourée contenant uniquement le personnage principal.
 */
public final class SingleSubjectExtractor {
    private static final int ANALYSIS_LONG_SIDE = 512;
    private static final float BINARY_THRESHOLD = 0.46f;

    private SingleSubjectExtractor() {
    }

    public static Result extract(
            Bitmap source,
            AnimeSegmentationEngine.Mask neuralMask
    ) {
        if (source == null || source.isRecycled() || neuralMask == null) {
            throw new IllegalArgumentException("Image ou masque de sujet absent");
        }
        int[] analysisSize = fitInside(
                source.getWidth(),
                source.getHeight(),
                ANALYSIS_LONG_SIDE
        );
        int analysisWidth = analysisSize[0];
        int analysisHeight = analysisSize[1];
        boolean[] binary = new boolean[analysisWidth * analysisHeight];
        for (int y = 0; y < analysisHeight; y++) {
            float normalizedY = (y + 0.5f) / analysisHeight;
            for (int x = 0; x < analysisWidth; x++) {
                float normalizedX = (x + 0.5f) / analysisWidth;
                binary[y * analysisWidth + x] = neuralMask.sampleNormalized(
                        normalizedX,
                        normalizedY
                ) >= BINARY_THRESHOLD;
            }
        }

        SingleSubjectSelector.Selection selection =
                SingleSubjectSelector.select(binary, analysisWidth, analysisHeight);
        boolean[] selectedMask = Arrays.copyOf(
                selection.getMask(),
                selection.getMask().length
        );
        dilate(selectedMask, analysisWidth, analysisHeight, 2);

        Rect crop = toSourceCrop(
                selection,
                analysisWidth,
                analysisHeight,
                source.getWidth(),
                source.getHeight()
        );
        Bitmap isolated = Bitmap.createBitmap(
                crop.width(),
                crop.height(),
                Bitmap.Config.ARGB_8888
        );
        int[] sourcePixels = new int[source.getWidth() * source.getHeight()];
        source.getPixels(
                sourcePixels,
                0,
                source.getWidth(),
                0,
                0,
                source.getWidth(),
                source.getHeight()
        );
        int[] output = new int[crop.width() * crop.height()];
        int kept = 0;
        for (int y = 0; y < crop.height(); y++) {
            int sourceY = crop.top + y;
            float normalizedY = sourceY
                    / Math.max(1.0f, source.getHeight() - 1.0f);
            int analysisY = clamp(
                    Math.round(normalizedY * (analysisHeight - 1)),
                    0,
                    analysisHeight - 1
            );
            for (int x = 0; x < crop.width(); x++) {
                int sourceX = crop.left + x;
                float normalizedX = sourceX
                        / Math.max(1.0f, source.getWidth() - 1.0f);
                int analysisX = clamp(
                        Math.round(normalizedX * (analysisWidth - 1)),
                        0,
                        analysisWidth - 1
                );
                int outputIndex = y * crop.width() + x;
                if (!selectedMask[analysisY * analysisWidth + analysisX]) {
                    output[outputIndex] = Color.TRANSPARENT;
                    continue;
                }
                float probability = neuralMask.sampleNormalized(
                        normalizedX,
                        normalizedY
                );
                float feather = smoothStep(0.22f, 0.70f, probability);
                int sourceColor = sourcePixels[
                        sourceY * source.getWidth() + sourceX
                ];
                int sourceAlpha = Color.alpha(sourceColor);
                int alpha = Math.round(sourceAlpha * feather);
                if (alpha < 6) {
                    output[outputIndex] = Color.TRANSPARENT;
                    continue;
                }
                kept++;
                output[outputIndex] = (alpha << 24)
                        | (sourceColor & 0x00FFFFFF);
            }
        }
        isolated.setPixels(
                output,
                0,
                crop.width(),
                0,
                0,
                crop.width(),
                crop.height()
        );
        if (kept < Math.max(96, output.length / 900)) {
            isolated.recycle();
            throw new IllegalArgumentException(
                    "Le personnage principal est trop petit dans la planche"
            );
        }
        return new Result(
                isolated,
                selection.getDetectedSubjectCount(),
                kept,
                selection.getDetectedSubjectCount() > 1
                        ? "planche détectée : un seul personnage central conservé"
                        : "un seul personnage détecté"
        );
    }

    private static Rect toSourceCrop(
            SingleSubjectSelector.Selection selection,
            int analysisWidth,
            int analysisHeight,
            int sourceWidth,
            int sourceHeight
    ) {
        float leftNormalized = selection.getLeft()
                / (float) Math.max(1, analysisWidth - 1);
        float topNormalized = selection.getTop()
                / (float) Math.max(1, analysisHeight - 1);
        float rightNormalized = selection.getRight()
                / (float) Math.max(1, analysisWidth - 1);
        float bottomNormalized = selection.getBottom()
                / (float) Math.max(1, analysisHeight - 1);

        int left = Math.round(leftNormalized * (sourceWidth - 1));
        int top = Math.round(topNormalized * (sourceHeight - 1));
        int right = Math.round(rightNormalized * (sourceWidth - 1)) + 1;
        int bottom = Math.round(bottomNormalized * (sourceHeight - 1)) + 1;
        int marginX = Math.max(3, Math.round((right - left) * 0.075f));
        int marginY = Math.max(3, Math.round((bottom - top) * 0.060f));
        left = clamp(left - marginX, 0, sourceWidth - 1);
        top = clamp(top - marginY, 0, sourceHeight - 1);
        right = clamp(right + marginX, left + 1, sourceWidth);
        bottom = clamp(bottom + marginY, top + 1, sourceHeight);
        return new Rect(left, top, right, bottom);
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

    private static void dilate(
            boolean[] mask,
            int width,
            int height,
            int passes
    ) {
        for (int pass = 0; pass < passes; pass++) {
            boolean[] source = Arrays.copyOf(mask, mask.length);
            for (int y = 1; y < height - 1; y++) {
                for (int x = 1; x < width - 1; x++) {
                    int index = y * width + x;
                    if (source[index]
                            || source[index - 1]
                            || source[index + 1]
                            || source[index - width]
                            || source[index + width]) {
                        mask[index] = true;
                    }
                }
            }
        }
    }

    private static float smoothStep(float edge0, float edge1, float value) {
        float t = Math.max(0.0f, Math.min(
                1.0f,
                (value - edge0) / Math.max(1.0e-6f, edge1 - edge0)
        ));
        return t * t * (3.0f - 2.0f * t);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public static final class Result implements AutoCloseable {
        private final Bitmap bitmap;
        private final int detectedSubjectCount;
        private final int foregroundPixelCount;
        private final String selectionLabel;

        Result(
                Bitmap bitmap,
                int detectedSubjectCount,
                int foregroundPixelCount,
                String selectionLabel
        ) {
            this.bitmap = bitmap;
            this.detectedSubjectCount = detectedSubjectCount;
            this.foregroundPixelCount = foregroundPixelCount;
            this.selectionLabel = selectionLabel;
        }

        public Bitmap getBitmap() {
            return bitmap;
        }

        public int getDetectedSubjectCount() {
            return detectedSubjectCount;
        }

        public int getForegroundPixelCount() {
            return foregroundPixelCount;
        }

        public String getSelectionLabel() {
            return selectionLabel;
        }

        @Override
        public void close() {
            if (!bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
    }
}
