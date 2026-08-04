package com.chasmet.modeliseur3d.model;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * Détourage réservé au mode 3D quatre vues.
 *
 * Contrairement au détourage historique, cette version conserve dans le canal
 * alpha la confiance produite par IS-Net. La reconstruction peut donc faire la
 * différence entre le corps fortement détecté, une bordure incertaine et un
 * détail fin comme des cheveux, une aile ou un manche.
 *
 * Le moteur 2.5D continue d'utiliser NeuralSheetIsolator et n'est pas modifié.
 */
final class Neural3DSheetIsolator {
    private static final float WEAK_THRESHOLD = 0.16f;
    private static final float STRONG_THRESHOLD = 0.42f;
    private static final int MINIMUM_RETAINED_ALPHA = 64;

    private Neural3DSheetIsolator() {
    }

    static Bitmap isolate(Bitmap source, AnimeSegmentationEngine.Mask neuralMask) {
        if (source == null || source.isRecycled() || neuralMask == null) {
            throw new IllegalArgumentException("Image ou masque neuronal invalide");
        }
        int width = source.getWidth();
        int height = source.getHeight();
        int count = width * height;
        int[] pixels = new int[count];
        source.getPixels(pixels, 0, width, 0, 0, width, height);

        float[] confidence = new float[count];
        boolean[] weak = new boolean[count];
        boolean[] strong = new boolean[count];
        float widthDenominator = Math.max(1.0f, width - 1.0f);
        float heightDenominator = Math.max(1.0f, height - 1.0f);
        int strongCount = 0;
        for (int y = 0; y < height; y++) {
            float normalizedY = y / heightDenominator;
            int row = y * width;
            for (int x = 0; x < width; x++) {
                float probability = neuralMask.sampleNormalized(
                        x / widthDenominator,
                        normalizedY
                );
                int index = row + x;
                confidence[index] = probability;
                weak[index] = probability >= WEAK_THRESHOLD;
                strong[index] = probability >= STRONG_THRESHOLD;
                if (strong[index]) {
                    strongCount++;
                }
            }
        }
        if (strongCount < Math.max(24, count / 20_000)) {
            throw new IllegalArgumentException(
                    "L'IA locale ne détecte pas suffisamment le personnage"
            );
        }

        boolean[] retained = hysteresis(weak, strong, width, height);
        close(retained, width, height);
        fillSmallHoles(
                retained,
                width,
                height,
                Math.max(24, count / 9000)
        );

        for (int index = 0; index < count; index++) {
            if (!retained[index]) {
                pixels[index] = Color.TRANSPARENT;
                continue;
            }
            float normalized = smoothStep(
                    WEAK_THRESHOLD,
                    0.88f,
                    confidence[index]
            );
            int alpha = Math.max(
                    MINIMUM_RETAINED_ALPHA,
                    Math.min(255, Math.round(normalized * 255.0f))
            );
            pixels[index] = (alpha << 24) | (pixels[index] & 0x00FFFFFF);
        }

        Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        output.setPixels(pixels, 0, width, 0, 0, width, height);
        return output;
    }

    private static boolean[] hysteresis(
            boolean[] weak,
            boolean[] strong,
            int width,
            int height
    ) {
        boolean[] retained = new boolean[weak.length];
        int[] queue = new int[weak.length];
        int head = 0;
        int tail = 0;
        for (int index = 0; index < strong.length; index++) {
            if (strong[index]) {
                retained[index] = true;
                queue[tail++] = index;
            }
        }
        while (head < tail) {
            int current = queue[head++];
            int x = current % width;
            int y = current / width;
            for (int oy = -1; oy <= 1; oy++) {
                int sy = y + oy;
                if (sy < 0 || sy >= height) {
                    continue;
                }
                for (int ox = -1; ox <= 1; ox++) {
                    if (ox == 0 && oy == 0) {
                        continue;
                    }
                    int sx = x + ox;
                    if (sx < 0 || sx >= width) {
                        continue;
                    }
                    int next = sy * width + sx;
                    if (weak[next] && !retained[next]) {
                        retained[next] = true;
                        queue[tail++] = next;
                    }
                }
            }
        }
        return retained;
    }

    private static void close(boolean[] mask, int width, int height) {
        boolean[] dilated = new boolean[mask.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean enabled = false;
                for (int oy = -1; oy <= 1 && !enabled; oy++) {
                    int sy = y + oy;
                    if (sy < 0 || sy >= height) {
                        continue;
                    }
                    for (int ox = -1; ox <= 1; ox++) {
                        int sx = x + ox;
                        if (sx >= 0 && sx < width && mask[sy * width + sx]) {
                            enabled = true;
                            break;
                        }
                    }
                }
                dilated[y * width + x] = enabled;
            }
        }

        boolean[] eroded = new boolean[mask.length];
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                boolean enabled = true;
                for (int oy = -1; oy <= 1 && enabled; oy++) {
                    for (int ox = -1; ox <= 1; ox++) {
                        if (!dilated[(y + oy) * width + x + ox]) {
                            enabled = false;
                            break;
                        }
                    }
                }
                eroded[y * width + x] = enabled;
            }
        }
        System.arraycopy(eroded, 0, mask, 0, mask.length);
    }

    private static void fillSmallHoles(
            boolean[] mask,
            int width,
            int height,
            int maximumHolePixels
    ) {
        boolean[] visited = new boolean[mask.length];
        int[] queue = new int[mask.length];
        for (int start = 0; start < mask.length; start++) {
            if (mask[start] || visited[start]) {
                continue;
            }
            int head = 0;
            int tail = 0;
            boolean touchesBorder = false;
            queue[tail++] = start;
            visited[start] = true;
            while (head < tail) {
                int current = queue[head++];
                int x = current % width;
                int y = current / width;
                if (x == 0 || y == 0 || x == width - 1 || y == height - 1) {
                    touchesBorder = true;
                }
                tail = enqueueBackground(mask, visited, queue, tail,
                        x - 1, y, width, height);
                tail = enqueueBackground(mask, visited, queue, tail,
                        x + 1, y, width, height);
                tail = enqueueBackground(mask, visited, queue, tail,
                        x, y - 1, width, height);
                tail = enqueueBackground(mask, visited, queue, tail,
                        x, y + 1, width, height);
            }
            if (!touchesBorder && tail <= maximumHolePixels) {
                for (int index = 0; index < tail; index++) {
                    mask[queue[index]] = true;
                }
            }
        }
    }

    private static int enqueueBackground(
            boolean[] mask,
            boolean[] visited,
            int[] queue,
            int tail,
            int x,
            int y,
            int width,
            int height
    ) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            return tail;
        }
        int index = y * width + x;
        if (!mask[index] && !visited[index]) {
            visited[index] = true;
            queue[tail++] = index;
        }
        return tail;
    }

    private static float smoothStep(float edge0, float edge1, float value) {
        float amount = (value - edge0) / Math.max(0.0001f, edge1 - edge0);
        amount = Math.max(0.0f, Math.min(1.0f, amount));
        return amount * amount * (3.0f - 2.0f * amount);
    }
}
