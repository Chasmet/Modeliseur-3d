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

    /**
     * Dilate les couleurs seulement près du contour, puis utilise la couleur
     * moyenne du sujet dans le fond lointain. Une joue ou un œil ne peut ainsi
     * plus être étiré sur toute une cellule de l'atlas, tout en garantissant
     * qu'aucun texel noir ou transparent ne subsiste.
     */
    public static int fillLocalOpaque(
            int[] pixels,
            int width,
            int height,
            int alphaThreshold,
            int maximumDistance
    ) {
        if (pixels == null || width < 1 || height < 1
                || pixels.length != width * height
                || alphaThreshold < 0 || alphaThreshold > 254
                || maximumDistance < 0) {
            throw new IllegalArgumentException("Texture locale à remplir invalide");
        }
        int[] queue = new int[pixels.length];
        int[] distance = new int[pixels.length];
        boolean[] visited = new boolean[pixels.length];
        int head = 0;
        int tail = 0;
        long red = 0L;
        long green = 0L;
        long blue = 0L;
        for (int index = 0; index < pixels.length; index++) {
            int alpha = (pixels[index] >>> 24) & 0xFF;
            if (alpha > alphaThreshold) {
                int rgb = pixels[index] & 0x00FFFFFF;
                pixels[index] = 0xFF000000 | rgb;
                red += (rgb >>> 16) & 0xFF;
                green += (rgb >>> 8) & 0xFF;
                blue += rgb & 0xFF;
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
            if (distance[current] >= maximumDistance) {
                continue;
            }
            int x = current % width;
            int y = current / width;
            tail = spreadLocal(
                    pixels, visited, distance, queue, tail, current,
                    x - 1, y, width, height
            );
            tail = spreadLocal(
                    pixels, visited, distance, queue, tail, current,
                    x + 1, y, width, height
            );
            tail = spreadLocal(
                    pixels, visited, distance, queue, tail, current,
                    x, y - 1, width, height
            );
            tail = spreadLocal(
                    pixels, visited, distance, queue, tail, current,
                    x, y + 1, width, height
            );
        }

        int fallback = 0xFF000000
                | ((int) (red / foregroundCount) << 16)
                | ((int) (green / foregroundCount) << 8)
                | (int) (blue / foregroundCount);
        for (int index = 0; index < pixels.length; index++) {
            if (!visited[index]) {
                pixels[index] = fallback;
            }
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

    private static int spreadLocal(
            int[] pixels,
            boolean[] visited,
            int[] distance,
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
        distance[target] = distance[source] + 1;
        pixels[target] = pixels[source];
        queue[tail++] = target;
        return tail;
    }
}
