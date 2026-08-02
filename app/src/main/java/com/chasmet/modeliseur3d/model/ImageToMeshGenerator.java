package com.chasmet.modeliseur3d.model;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;

import java.util.ArrayDeque;
import java.util.Arrays;

public final class ImageToMeshGenerator {
    private static final int GRID_LONG_SIDE = 96;
    private static final int TEXTURE_LONG_SIDE = 512;

    public Result generate(Bitmap source) {
        if (source == null || source.isRecycled()) {
            throw new IllegalArgumentException("Image absente");
        }

        boolean[] foreground = estimateForeground(source);
        Component component = findLargestComponent(foreground, source.getWidth(), source.getHeight());
        if (component == null || component.pixelCount < Math.max(64, source.getWidth() * source.getHeight() / 250)) {
            throw new IllegalArgumentException("Aucun personnage suffisamment net n'a été détecté");
        }

        Rect crop = addMargin(component.bounds, source.getWidth(), source.getHeight(), 0.04f);
        Bitmap cropped = Bitmap.createBitmap(source, crop.left, crop.top, crop.width(), crop.height());

        int textureWidth;
        int textureHeight;
        if (cropped.getWidth() >= cropped.getHeight()) {
            textureWidth = TEXTURE_LONG_SIDE;
            textureHeight = Math.max(32, Math.round(TEXTURE_LONG_SIDE * cropped.getHeight() / (float) cropped.getWidth()));
        } else {
            textureHeight = TEXTURE_LONG_SIDE;
            textureWidth = Math.max(32, Math.round(TEXTURE_LONG_SIDE * cropped.getWidth() / (float) cropped.getHeight()));
        }
        Bitmap scaledTexture = Bitmap.createScaledBitmap(cropped, textureWidth, textureHeight, true);
        Bitmap texture = scaledTexture.copy(Bitmap.Config.ARGB_8888, true);
        if (scaledTexture != cropped) {
            scaledTexture.recycle();
        }
        if (!cropped.isRecycled()) {
            cropped.recycle();
        }

        boolean[] textureMask = buildMaskForCrop(source, foreground, crop, textureWidth, textureHeight);
        applyTransparentBackground(texture, textureMask);

        int gridWidth;
        int gridHeight;
        if (textureWidth >= textureHeight) {
            gridWidth = GRID_LONG_SIDE;
            gridHeight = Math.max(20, Math.round(GRID_LONG_SIDE * textureHeight / (float) textureWidth));
        } else {
            gridHeight = GRID_LONG_SIDE;
            gridWidth = Math.max(20, Math.round(GRID_LONG_SIDE * textureWidth / (float) textureHeight));
        }

        boolean[] cellMask = resizeMask(textureMask, textureWidth, textureHeight, gridWidth, gridHeight);
        closeSmallGaps(cellMask, gridWidth, gridHeight);
        int[] distance = distanceTransform(cellMask, gridWidth, gridHeight);
        MeshData mesh = buildMesh(cellMask, distance, gridWidth, gridHeight);
        return new Result(mesh, texture, component.pixelCount);
    }

    private static boolean[] estimateForeground(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        long rSum = 0;
        long gSum = 0;
        long bSum = 0;
        int count = 0;
        int border = Math.max(2, Math.min(width, height) / 40);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (x < border || y < border || x >= width - border || y >= height - border) {
                    int color = pixels[y * width + x];
                    if (Color.alpha(color) > 20) {
                        rSum += Color.red(color);
                        gSum += Color.green(color);
                        bSum += Color.blue(color);
                        count++;
                    }
                }
            }
        }

        int bgR = count == 0 ? 245 : (int) (rSum / count);
        int bgG = count == 0 ? 245 : (int) (gSum / count);
        int bgB = count == 0 ? 245 : (int) (bSum / count);
        int bgLum = (bgR * 299 + bgG * 587 + bgB * 114) / 1000;

        boolean[] mask = new boolean[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            int color = pixels[i];
            int alpha = Color.alpha(color);
            if (alpha < 24) {
                continue;
            }
            int r = Color.red(color);
            int g = Color.green(color);
            int b = Color.blue(color);
            int dr = r - bgR;
            int dg = g - bgG;
            int db = b - bgB;
            int distanceSquared = dr * dr + dg * dg + db * db;
            int lum = (r * 299 + g * 587 + b * 114) / 1000;
            int max = Math.max(r, Math.max(g, b));
            int min = Math.min(r, Math.min(g, b));
            int saturation = max - min;

            mask[i] = distanceSquared > 34 * 34
                    || Math.abs(lum - bgLum) > 24
                    || saturation > 34;
        }

        dilate(mask, width, height, 1);
        erode(mask, width, height, 1);
        return mask;
    }

    private static Component findLargestComponent(boolean[] mask, int width, int height) {
        boolean[] visited = new boolean[mask.length];
        Component best = null;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        int[] dx = {1, -1, 0, 0};
        int[] dy = {0, 0, 1, -1};

        for (int index = 0; index < mask.length; index++) {
            if (!mask[index] || visited[index]) {
                continue;
            }
            visited[index] = true;
            queue.add(index);
            int count = 0;
            int minX = width;
            int minY = height;
            int maxX = -1;
            int maxY = -1;

            while (!queue.isEmpty()) {
                int current = queue.removeFirst();
                int x = current % width;
                int y = current / width;
                count++;
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);

                for (int d = 0; d < 4; d++) {
                    int nx = x + dx[d];
                    int ny = y + dy[d];
                    if (nx < 0 || ny < 0 || nx >= width || ny >= height) {
                        continue;
                    }
                    int next = ny * width + nx;
                    if (mask[next] && !visited[next]) {
                        visited[next] = true;
                        queue.addLast(next);
                    }
                }
            }

            if (best == null || count > best.pixelCount) {
                best = new Component(count, new Rect(minX, minY, maxX + 1, maxY + 1));
            }
        }
        return best;
    }

    private static Rect addMargin(Rect source, int width, int height, float fraction) {
        int marginX = Math.max(2, Math.round(source.width() * fraction));
        int marginY = Math.max(2, Math.round(source.height() * fraction));
        return new Rect(
                Math.max(0, source.left - marginX),
                Math.max(0, source.top - marginY),
                Math.min(width, source.right + marginX),
                Math.min(height, source.bottom + marginY)
        );
    }

    private static boolean[] buildMaskForCrop(
            Bitmap source,
            boolean[] sourceMask,
            Rect crop,
            int targetWidth,
            int targetHeight
    ) {
        int sourceWidth = source.getWidth();
        boolean[] output = new boolean[targetWidth * targetHeight];
        for (int y = 0; y < targetHeight; y++) {
            int sy = crop.top + Math.min(crop.height() - 1, (int) ((y + 0.5f) * crop.height() / targetHeight));
            for (int x = 0; x < targetWidth; x++) {
                int sx = crop.left + Math.min(crop.width() - 1, (int) ((x + 0.5f) * crop.width() / targetWidth));
                output[y * targetWidth + x] = sourceMask[sy * sourceWidth + sx];
            }
        }
        return output;
    }

    private static void applyTransparentBackground(Bitmap bitmap, boolean[] mask) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        for (int i = 0; i < pixels.length; i++) {
            if (!mask[i]) {
                pixels[i] = Color.TRANSPARENT;
            } else {
                pixels[i] = (0xFF << 24) | (pixels[i] & 0x00FFFFFF);
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
    }

    private static boolean[] resizeMask(boolean[] source, int sourceWidth, int sourceHeight, int width, int height) {
        boolean[] result = new boolean[width * height];
        for (int y = 0; y < height; y++) {
            int y0 = y * sourceHeight / height;
            int y1 = Math.max(y0 + 1, (y + 1) * sourceHeight / height);
            for (int x = 0; x < width; x++) {
                int x0 = x * sourceWidth / width;
                int x1 = Math.max(x0 + 1, (x + 1) * sourceWidth / width);
                int total = 0;
                int foreground = 0;
                for (int sy = y0; sy < Math.min(sourceHeight, y1); sy++) {
                    for (int sx = x0; sx < Math.min(sourceWidth, x1); sx++) {
                        total++;
                        if (source[sy * sourceWidth + sx]) {
                            foreground++;
                        }
                    }
                }
                result[y * width + x] = foreground * 2 >= Math.max(1, total);
            }
        }
        return result;
    }

    private static void closeSmallGaps(boolean[] mask, int width, int height) {
        dilate(mask, width, height, 1);
        erode(mask, width, height, 1);
    }

    private static void dilate(boolean[] mask, int width, int height, int iterations) {
        for (int it = 0; it < iterations; it++) {
            boolean[] copy = Arrays.copyOf(mask, mask.length);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (copy[y * width + x]) {
                        continue;
                    }
                    boolean neighbour = false;
                    for (int oy = -1; oy <= 1 && !neighbour; oy++) {
                        for (int ox = -1; ox <= 1; ox++) {
                            int nx = x + ox;
                            int ny = y + oy;
                            if (nx >= 0 && ny >= 0 && nx < width && ny < height && copy[ny * width + nx]) {
                                neighbour = true;
                                break;
                            }
                        }
                    }
                    if (neighbour) {
                        mask[y * width + x] = true;
                    }
                }
            }
        }
    }

    private static void erode(boolean[] mask, int width, int height, int iterations) {
        for (int it = 0; it < iterations; it++) {
            boolean[] copy = Arrays.copyOf(mask, mask.length);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (!copy[y * width + x]) {
                        continue;
                    }
                    boolean touchesBackground = false;
                    for (int oy = -1; oy <= 1 && !touchesBackground; oy++) {
                        for (int ox = -1; ox <= 1; ox++) {
                            int nx = x + ox;
                            int ny = y + oy;
                            if (nx < 0 || ny < 0 || nx >= width || ny >= height || !copy[ny * width + nx]) {
                                touchesBackground = true;
                                break;
                            }
                        }
                    }
                    if (touchesBackground) {
                        mask[y * width + x] = false;
                    }
                }
            }
        }
    }

    private static int[] distanceTransform(boolean[] mask, int width, int height) {
        int infinity = 1_000_000;
        int[] distance = new int[mask.length];
        for (int i = 0; i < mask.length; i++) {
            distance[i] = mask[i] ? infinity : 0;
        }

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                if (!mask[index]) {
                    continue;
                }
                int best = distance[index];
                if (x > 0) best = Math.min(best, distance[index - 1] + 3);
                if (y > 0) best = Math.min(best, distance[index - width] + 3);
                if (x > 0 && y > 0) best = Math.min(best, distance[index - width - 1] + 4);
                if (x + 1 < width && y > 0) best = Math.min(best, distance[index - width + 1] + 4);
                distance[index] = best;
            }
        }

        for (int y = height - 1; y >= 0; y--) {
            for (int x = width - 1; x >= 0; x--) {
                int index = y * width + x;
                if (!mask[index]) {
                    continue;
                }
                int best = distance[index];
                if (x + 1 < width) best = Math.min(best, distance[index + 1] + 3);
                if (y + 1 < height) best = Math.min(best, distance[index + width] + 3);
                if (x + 1 < width && y + 1 < height) best = Math.min(best, distance[index + width + 1] + 4);
                if (x > 0 && y + 1 < height) best = Math.min(best, distance[index + width - 1] + 4);
                distance[index] = best;
            }
        }
        return distance;
    }

    private static MeshData buildMesh(boolean[] mask, int[] distance, int width, int height) {
        int gridVertexCount = (width + 1) * (height + 1);
        FloatBuilder positions = new FloatBuilder(gridVertexCount * 2 * 3 + 4096);
        FloatBuilder normals = new FloatBuilder(gridVertexCount * 2 * 3 + 4096);
        FloatBuilder texCoords = new FloatBuilder(gridVertexCount * 2 * 2 + 4096);
        IntBuilder indices = new IntBuilder(width * height * 12 + 4096);

        float aspect = width / (float) height;
        float[] vertexDepth = buildVertexDepth(mask, distance, width, height);

        for (int layer = 0; layer < 2; layer++) {
            boolean front = layer == 0;
            for (int y = 0; y <= height; y++) {
                float py = 1.0f - 2.0f * y / height;
                float v = 1.0f - y / (float) height;
                for (int x = 0; x <= width; x++) {
                    float px = (2.0f * x / width - 1.0f) * aspect;
                    float u = x / (float) width;
                    float z = vertexDepth[y * (width + 1) + x];
                    if (!front) {
                        z = -z * 0.82f;
                    }
                    positions.add(px, py, z);
                    normals.add(0.0f, 0.0f, front ? 1.0f : -1.0f);
                    texCoords.add(front ? u : 1.0f - u, v);
                }
            }
        }

        int backOffset = gridVertexCount;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int cell = y * width + x;
                if (!mask[cell]) {
                    continue;
                }
                int a = y * (width + 1) + x;
                int b = a + 1;
                int c = a + (width + 1);
                int d = c + 1;

                indices.add(a, c, b);
                indices.add(b, c, d);

                int ab = a + backOffset;
                int bb = b + backOffset;
                int cb = c + backOffset;
                int db = d + backOffset;
                indices.add(ab, bb, cb);
                indices.add(bb, db, cb);

                if (y == 0 || !mask[(y - 1) * width + x]) {
                    addSide(positions, normals, texCoords, indices,
                            x, y, x + 1, y,
                            width, height, aspect, vertexDepth,
                            0.0f, 1.0f, 0.0f);
                }
                if (y == height - 1 || !mask[(y + 1) * width + x]) {
                    addSide(positions, normals, texCoords, indices,
                            x + 1, y + 1, x, y + 1,
                            width, height, aspect, vertexDepth,
                            0.0f, -1.0f, 0.0f);
                }
                if (x == 0 || !mask[y * width + x - 1]) {
                    addSide(positions, normals, texCoords, indices,
                            x, y + 1, x, y,
                            width, height, aspect, vertexDepth,
                            -1.0f, 0.0f, 0.0f);
                }
                if (x == width - 1 || !mask[y * width + x + 1]) {
                    addSide(positions, normals, texCoords, indices,
                            x + 1, y, x + 1, y + 1,
                            width, height, aspect, vertexDepth,
                            1.0f, 0.0f, 0.0f);
                }
            }
        }

        return new MeshData(
                positions.toArray(),
                normals.toArray(),
                texCoords.toArray(),
                indices.toArray()
        );
    }

    private static float[] buildVertexDepth(boolean[] mask, int[] distance, int width, int height) {
        float[] result = new float[(width + 1) * (height + 1)];
        for (int y = 0; y <= height; y++) {
            for (int x = 0; x <= width; x++) {
                int samples = 0;
                float sum = 0.0f;
                for (int oy = -1; oy <= 0; oy++) {
                    for (int ox = -1; ox <= 0; ox++) {
                        int cx = x + ox;
                        int cy = y + oy;
                        if (cx >= 0 && cy >= 0 && cx < width && cy < height) {
                            int index = cy * width + cx;
                            if (mask[index]) {
                                float normalized = Math.min(1.0f, distance[index] / 36.0f);
                                sum += 0.08f + 0.30f * (float) Math.sqrt(normalized);
                                samples++;
                            }
                        }
                    }
                }
                result[y * (width + 1) + x] = samples == 0 ? 0.07f : sum / samples;
            }
        }
        return result;
    }

    private static void addSide(
            FloatBuilder positions,
            FloatBuilder normals,
            FloatBuilder texCoords,
            IntBuilder indices,
            int x1,
            int y1,
            int x2,
            int y2,
            int width,
            int height,
            float aspect,
            float[] depth,
            float nx,
            float ny,
            float nz
    ) {
        int base = positions.size() / 3;
        float px1 = (2.0f * x1 / width - 1.0f) * aspect;
        float py1 = 1.0f - 2.0f * y1 / height;
        float px2 = (2.0f * x2 / width - 1.0f) * aspect;
        float py2 = 1.0f - 2.0f * y2 / height;
        float z1 = depth[y1 * (width + 1) + x1];
        float z2 = depth[y2 * (width + 1) + x2];
        float u1 = x1 / (float) width;
        float v1 = 1.0f - y1 / (float) height;
        float u2 = x2 / (float) width;
        float v2 = 1.0f - y2 / (float) height;

        positions.add(px1, py1, z1);
        positions.add(px2, py2, z2);
        positions.add(px2, py2, -z2 * 0.82f);
        positions.add(px1, py1, -z1 * 0.82f);
        for (int i = 0; i < 4; i++) {
            normals.add(nx, ny, nz);
        }
        texCoords.add(u1, v1);
        texCoords.add(u2, v2);
        texCoords.add(u2, v2);
        texCoords.add(u1, v1);
        indices.add(base, base + 1, base + 2);
        indices.add(base, base + 2, base + 3);
    }

    public static final class Result {
        private final MeshData mesh;
        private final Bitmap texture;
        private final int foregroundPixelCount;

        Result(MeshData mesh, Bitmap texture, int foregroundPixelCount) {
            this.mesh = mesh;
            this.texture = texture;
            this.foregroundPixelCount = foregroundPixelCount;
        }

        public MeshData getMesh() {
            return mesh;
        }

        public Bitmap getTexture() {
            return texture;
        }

        public int getForegroundPixelCount() {
            return foregroundPixelCount;
        }
    }

    private static final class Component {
        final int pixelCount;
        final Rect bounds;

        Component(int pixelCount, Rect bounds) {
            this.pixelCount = pixelCount;
            this.bounds = bounds;
        }
    }

    private static final class FloatBuilder {
        private float[] data;
        private int size;

        FloatBuilder(int capacity) {
            data = new float[Math.max(16, capacity)];
        }

        void add(float a, float b) {
            ensure(2);
            data[size++] = a;
            data[size++] = b;
        }

        void add(float a, float b, float c) {
            ensure(3);
            data[size++] = a;
            data[size++] = b;
            data[size++] = c;
        }

        int size() {
            return size;
        }

        float[] toArray() {
            return Arrays.copyOf(data, size);
        }

        private void ensure(int extra) {
            if (size + extra > data.length) {
                data = Arrays.copyOf(data, Math.max(size + extra, data.length * 2));
            }
        }
    }

    private static final class IntBuilder {
        private int[] data;
        private int size;

        IntBuilder(int capacity) {
            data = new int[Math.max(16, capacity)];
        }

        void add(int a, int b, int c) {
            ensure(3);
            data[size++] = a;
            data[size++] = b;
            data[size++] = c;
        }

        int[] toArray() {
            return Arrays.copyOf(data, size);
        }

        private void ensure(int extra) {
            if (size + extra > data.length) {
                data = Arrays.copyOf(data, Math.max(size + extra, data.length * 2));
            }
        }
    }
}
