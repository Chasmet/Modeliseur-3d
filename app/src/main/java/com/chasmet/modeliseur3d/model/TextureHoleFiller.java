package com.chasmet.modeliseur3d.model;

/** Nearest-colour dilation used to make every atlas texel opaque. */
public final class TextureHoleFiller {
    private TextureHoleFiller() {
    }

    /**
     * Fills every transparent pixel from its nearest foreground colour.
     * Foreground RGB values are preserved and made fully opaque.
     *
     * @return the number of original foreground pixels
     */
    public static int fillNearestOpaque(
            int[] pixels,
            int width,
            int height,
            int alphaThreshold
    ) {
        if (pixels == null || width < 1 || height < 1
                || pixels.length != width * height
                || alphaThreshold < 0 || alphaThreshold > 254) {
            throw new IllegalArgumentException("Texture à remplir invalide");
        }
        int[] queue = new int[pixels.length];
        boolean[] visited = new boolean[pixels.length];
        int head = 0;
        int tail = 0;
        for (int index = 0; index < pixels.length; index++) {
            int alpha = (pixels[index] >>> 24) & 0xFF;
            if (alpha > alphaThreshold) {
                pixels[index] = 0xFF000000 | (pixels[index] & 0x00FFFFFF);
                visited[index] = true;
                queue[tail++] = index;
            }
        }
        int foregroundCount = tail;
        if (foregroundCount == 0) {
            throw new IllegalArgumentException("Texture détourée vide");
        }
        while (head < tail) {
            int current = queue[head++];
            int x = current % width;
            int y = current / width;
            tail = spread(
                    pixels,
                    visited,
                    queue,
                    tail,
                    current,
                    x - 1,
                    y,
                    width,
                    height
            );
            tail = spread(
                    pixels,
                    visited,
                    queue,
                    tail,
                    current,
                    x + 1,
                    y,
                    width,
                    height
            );
            tail = spread(
                    pixels,
                    visited,
                    queue,
                    tail,
                    current,
                    x,
                    y - 1,
                    width,
                    height
            );
            tail = spread(
                    pixels,
                    visited,
                    queue,
                    tail,
                    current,
                    x,
                    y + 1,
                    width,
                    height
            );
        }
        return foregroundCount;
    }

    private static int spread(
            int[] pixels,
            boolean[] visited,
            int[] queue,
            int tail,
            int source,
            int x,
            int y,
            int width,
            int height
    ) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            return tail;
        }
        int target = y * width + x;
        if (visited[target]) {
            return tail;
        }
        visited[target] = true;
        pixels[target] = pixels[source];
        queue[tail++] = target;
        return tail;
    }
}
