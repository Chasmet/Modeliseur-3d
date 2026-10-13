package com.chasmet.modeliseur3d.model;

import android.graphics.Bitmap;
import java.util.concurrent.CancellationException;

/**
 * Optional, bounded high-frequency detail preparation for the genuine embedded TripoSR encoder.
 * Purely local CPU processing. Does not modify the photo, alpha silhouette, neural weights
 * or the source bitmap subsequently used for the GLB texture.
 */
public final class TripoInputDetailBoost {
    public static final String CACHE_REVISION = "edge-aware-v1";
    private static final int MAX_INPUT_SIDE = 768;
    private TripoInputDetailBoost() { }

    /** Bump CACHE_REVISION when inference image processing changes. */
    public static String cacheTag(boolean enabled, TripoDetailRegion region) {
        if (!enabled) return "";
        return ":" + CACHE_REVISION + ":" + (region == null ? "full" : region.cacheKey());
    }

    /**
     * A small edge-aware high-pass correction. Retains the original alpha exactly and
     * suppresses sharpening at semi-transparent borders to avoid silhouette halos.
     * The optional focus region is feathered so facial features can be prioritized.
     */
    public static Bitmap enhance(Bitmap source, TripoDetailRegion region, float strength) {
        if (source == null || source.isRecycled()) throw new IllegalArgumentException("Photo TripoSR absente.");
        if (!Float.isFinite(strength) || strength < 0f || strength > 1f)
            throw new IllegalArgumentException("Renforcement TripoSR invalide.");
        int originalW = source.getWidth(), originalH = source.getHeight();
        float ratio = Math.min(1f, MAX_INPUT_SIDE / (float)Math.max(originalW, originalH));
        Bitmap scaled = ratio < 1f
                ? Bitmap.createScaledBitmap(source, Math.max(1, Math.round(originalW * ratio)),
                        Math.max(1, Math.round(originalH * ratio)), true) : source;
        try {
            int w = scaled.getWidth(), h = scaled.getHeight();
            int[] input = new int[w * h], output = new int[w * h];
            scaled.getPixels(input, 0, w, 0, 0, w, h);
            System.arraycopy(input, 0, output, 0, input.length);
            if (strength > 0f && w >= 3 && h >= 3) {
                for (int y = 1; y < h - 1; y++) {
                    if (Thread.currentThread().isInterrupted()) throw new CancellationException();
                    float v = y / (float)(h - 1);
                    for (int x = 1; x < w - 1; x++) {
                        float focus = region == null ? 1f : region.feather(x / (float)(w - 1), v);
                        if (focus <= 0f) continue;
                        int i = y * w + x, center = input[i];
                        if ((center >>> 24) < 224) continue;
                        float centerLuma = luma(center), value = centerLuma * 1.5f, weight = 1.5f;
                        int[] neighbours = { i - 1, i + 1, i - w, i + w };
                        for (int j : neighbours) {
                            int near = input[j];
                            if ((near >>> 24) < 224) continue;
                            float neighbourLuma = luma(near);
                            float influence = Math.max(0f, 1f - Math.abs(centerLuma - neighbourLuma) / 48f);
                            value += neighbourLuma * influence;
                            weight += influence;
                        }
                        float delta = (centerLuma - value / weight) * 1.1f * strength * focus;
                        delta = Math.max(-16f, Math.min(16f, delta));
                        if (Math.abs(delta) < .35f) continue;
                        output[i] = (center & 0xff000000)
                                | (clamp(((center >>> 16) & 255) + Math.round(delta)) << 16)
                                | (clamp(((center >>> 8) & 255) + Math.round(delta)) << 8)
                                | clamp((center & 255) + Math.round(delta));
                    }
                }
            }
            Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            result.setPixels(output, 0, w, 0, 0, w, h);
            return result;
        } finally {
            if (scaled != source) scaled.recycle();
        }
    }

    private static float luma(int pixel) {
        return .2126f * ((pixel >>> 16) & 255)
                + .7152f * ((pixel >>> 8) & 255)
                + .0722f * (pixel & 255);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
