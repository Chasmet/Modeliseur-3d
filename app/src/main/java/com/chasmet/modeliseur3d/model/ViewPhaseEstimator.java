package com.chasmet.modeliseur3d.model;

import java.util.Arrays;

/**
 * Aligne les huit silhouettes de rotation afin que l'indice 0 corresponde à
 * une vue large (face ou dos) et les indices 2/6 à des profils plus étroits.
 */
public final class ViewPhaseEstimator {
    private static final int VIEW_COUNT = 8;

    private ViewPhaseEstimator() {
    }

    public static int estimate(boolean[][] masks, int width, int height) {
        validate(masks, width, height);
        float[] visibleWidth = new float[VIEW_COUNT];
        float[] area = new float[VIEW_COUNT];
        float[] center = new float[VIEW_COUNT];

        for (int view = 0; view < VIEW_COUNT; view++) {
            Metrics metrics = measure(masks[view], width, height);
            visibleWidth[view] = metrics.widthRatio;
            area[view] = metrics.areaRatio;
            center[view] = metrics.centerX;
        }

        float medianArea = median(area);
        int bestPhase = 0;
        float bestScore = Float.NEGATIVE_INFINITY;
        for (int phase = 0; phase < VIEW_COUNT; phase++) {
            int front = phase;
            int right = (phase + 2) % VIEW_COUNT;
            int back = (phase + 4) % VIEW_COUNT;
            int left = (phase + 6) % VIEW_COUNT;

            float wide = visibleWidth[front] + visibleWidth[back];
            float narrow = visibleWidth[right] + visibleWidth[left];
            float cardinalPattern = wide - narrow * 0.92f;
            float oppositeConsistency = -Math.abs(
                    visibleWidth[front] - visibleWidth[back]
            ) * 0.28f - Math.abs(
                    visibleWidth[right] - visibleWidth[left]
            ) * 0.34f;
            float areaConsistency = -(
                    Math.abs(area[front] - medianArea)
                            + Math.abs(area[back] - medianArea)
            ) * 0.16f;
            float centerPenalty = -(
                    Math.abs(center[front] - 0.5f)
                            + Math.abs(center[back] - 0.5f)
                            + Math.abs(center[right] - 0.5f)
                            + Math.abs(center[left] - 0.5f)
            ) * 0.10f;
            float score = cardinalPattern
                    + oppositeConsistency
                    + areaConsistency
                    + centerPenalty;
            if (score > bestScore) {
                bestScore = score;
                bestPhase = phase;
            }
        }
        return bestPhase;
    }

    public static boolean[][] rotate(boolean[][] masks, int phase) {
        if (masks == null || masks.length != VIEW_COUNT) {
            throw new IllegalArgumentException("Huit masques sont requis");
        }
        int normalized = ((phase % VIEW_COUNT) + VIEW_COUNT) % VIEW_COUNT;
        boolean[][] output = new boolean[VIEW_COUNT][];
        for (int index = 0; index < VIEW_COUNT; index++) {
            output[index] = masks[(normalized + index) % VIEW_COUNT];
        }
        return output;
    }

    public static int sourceIndex(int orderedIndex, int phase) {
        int normalized = ((phase % VIEW_COUNT) + VIEW_COUNT) % VIEW_COUNT;
        return (normalized + orderedIndex) % VIEW_COUNT;
    }

    private static void validate(boolean[][] masks, int width, int height) {
        if (masks == null || masks.length != VIEW_COUNT) {
            throw new IllegalArgumentException("Huit masques sont requis");
        }
        int expected = width * height;
        for (boolean[] mask : masks) {
            if (mask == null || mask.length != expected) {
                throw new IllegalArgumentException("Masque de rotation invalide");
            }
        }
    }

    private static Metrics measure(boolean[] mask, int width, int height) {
        int left = width;
        int right = -1;
        long sumX = 0L;
        int count = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (!mask[y * width + x]) {
                    continue;
                }
                left = Math.min(left, x);
                right = Math.max(right, x);
                sumX += x;
                count++;
            }
        }
        if (count == 0 || right < left) {
            return new Metrics(0.0f, 0.0f, 0.5f);
        }
        return new Metrics(
                (right - left + 1) / (float) Math.max(1, width),
                count / (float) Math.max(1, width * height),
                sumX / (float) count / Math.max(1.0f, width - 1.0f)
        );
    }

    private static float median(float[] values) {
        float[] copy = Arrays.copyOf(values, values.length);
        Arrays.sort(copy);
        return (copy[3] + copy[4]) * 0.5f;
    }

    private static final class Metrics {
        final float widthRatio;
        final float areaRatio;
        final float centerX;

        Metrics(float widthRatio, float areaRatio, float centerX) {
            this.widthRatio = widthRatio;
            this.areaRatio = areaRatio;
            this.centerX = centerX;
        }
    }
}
