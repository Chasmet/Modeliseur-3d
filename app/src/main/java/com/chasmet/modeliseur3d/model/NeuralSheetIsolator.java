package com.chasmet.modeliseur3d.model;

import android.graphics.Bitmap;
import android.graphics.Color;

/** Prépare une planche propre à partir du masque neuronal IS-Net Anime. */
final class NeuralSheetIsolator {
    private static final int BACKGROUND = Color.TRANSPARENT;
    private static final float FOREGROUND_THRESHOLD = 0.30f;

    private NeuralSheetIsolator() {
    }

    static Bitmap isolate(Bitmap source, AnimeSegmentationEngine.Mask mask) {
        int width = source.getWidth();
        int height = source.getHeight();
        int[] pixels = new int[width * height];
        source.getPixels(pixels, 0, width, 0, 0, width, height);

        boolean[] foreground = new boolean[pixels.length];
        float widthDenominator = Math.max(1.0f, width - 1.0f);
        float heightDenominator = Math.max(1.0f, height - 1.0f);
        for (int y = 0; y < height; y++) {
            float normalizedY = y / heightDenominator;
            int row = y * width;
            for (int x = 0; x < width; x++) {
                float normalizedX = x / widthDenominator;
                foreground[row + x] = mask.sampleNormalized(
                        normalizedX,
                        normalizedY
                ) >= FOREGROUND_THRESHOLD;
            }
        }

        close(foreground, width, height, 1);
        fillSmallHoles(
                foreground,
                width,
                height,
                Math.max(24, width * height / 9000)
        );

        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = foreground[i]
                    ? 0xFF000000 | (pixels[i] & 0x00FFFFFF)
                    : BACKGROUND;
        }
        Bitmap result = Bitmap.createBitmap(
                width,
                height,
                Bitmap.Config.ARGB_8888
        );
        result.setPixels(pixels, 0, width, 0, 0, width, height);
        return result;
    }

    private static void close(boolean[] mask, int width, int height, int passes) {
        for (int pass = 0; pass < passes; pass++) {
            boolean[] dilated = new boolean[mask.length];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    boolean on = false;
                    for (int oy = -1; oy <= 1 && !on; oy++) {
                        int sy = y + oy;
                        if (sy < 0 || sy >= height) {
                            continue;
                        }
                        for (int ox = -1; ox <= 1; ox++) {
                            int sx = x + ox;
                            if (sx >= 0 && sx < width
                                    && mask[sy * width + sx]) {
                                on = true;
                                break;
                            }
                        }
                    }
                    dilated[y * width + x] = on;
                }
            }

            boolean[] eroded = new boolean[mask.length];
            for (int y = 1; y < height - 1; y++) {
                for (int x = 1; x < width - 1; x++) {
                    boolean on = true;
                    for (int oy = -1; oy <= 1 && on; oy++) {
                        for (int ox = -1; ox <= 1; ox++) {
                            if (!dilated[(y + oy) * width + x + ox]) {
                                on = false;
                                break;
                            }
                        }
                    }
                    eroded[y * width + x] = on;
                }
            }
            System.arraycopy(eroded, 0, mask, 0, mask.length);
        }
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
                tail = enqueueBackground(
                        mask, visited, queue, tail,
                        x - 1, y, width, height
                );
                tail = enqueueBackground(
                        mask, visited, queue, tail,
                        x + 1, y, width, height
                );
                tail = enqueueBackground(
                        mask, visited, queue, tail,
                        x, y - 1, width, height
                );
                tail = enqueueBackground(
                        mask, visited, queue, tail,
                        x, y + 1, width, height
                );
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
}
