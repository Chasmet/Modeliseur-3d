package com.chasmet.modeliseur3d.model;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

import java.util.Arrays;

/**
 * Relief 3D fermé pour une image unique.
 *
 * Une photo ne contient pas le dos du sujet. Ce générateur conserve donc la
 * silhouette et la texture de face, ajoute un relief neuronal borné, un dos
 * stable et des parois propres au lieu de replier une coque bruitée.
 */
public final class SingleImageReliefGenerator {
    private static final int LONG_SIDE = 112;
    private static final int MIN_SIDE = 48;
    private static final int ATLAS_HEIGHT = 1024;
    private static final int ALPHA = 28;

    public Result generate(Bitmap isolated, NeuralDepthEngine.DepthMap depth) {
        if (isolated == null || isolated.isRecycled()) {
            throw new IllegalArgumentException("Image détourée absente");
        }
        Rect crop = foregroundBounds(isolated);
        Grid grid = Grid.from(crop);
        boolean[] mask = sampleMask(isolated, crop, grid.width, grid.height);
        close(mask, grid.width, grid.height);
        int[] distance = distanceToOutside(mask, grid.width, grid.height);
        float[] relief = sampleRelief(depth, mask, distance,
                grid.width, grid.height);

        SmoothHullMesher.AtlasLayout layout = SmoothHullMesher.AtlasLayout.create(
                grid.width,
                grid.height,
                Math.max(MIN_SIDE, grid.width / 2),
                ATLAS_HEIGHT
        );
        Bitmap atlas = buildAtlas(isolated, crop, layout);
        MeshData mesh;
        try {
            mesh = buildMesh(mask, relief, distance, grid, layout);
        } catch (RuntimeException error) {
            atlas.recycle();
            throw error;
        }
        return new Result(mesh, atlas, grid.width, grid.height, count(mask));
    }

    private static MeshData buildMesh(
            boolean[] mask,
            float[] relief,
            int[] distance,
            Grid grid,
            SmoothHullMesher.AtlasLayout atlas
    ) {
        FloatList positions = new FloatList(150_000);
        FloatList normals = new FloatList(150_000);
        FloatList uvs = new FloatList(100_000);
        IntList indices = new IntList(75_000);
        int width = grid.width;
        int height = grid.height;
        float modelWidth = 2.0f * width / Math.max(1.0f, height);

        for (int y = 0; y < height - 1; y++) {
            for (int x = 0; x < width - 1; x++) {
                if (!cellOn(mask, width, x, y)) {
                    continue;
                }
                Vertex f00 = vertex(x, y, true, relief, distance,
                        width, height, modelWidth, atlas, Projection.FRONT);
                Vertex f10 = vertex(x + 1, y, true, relief, distance,
                        width, height, modelWidth, atlas, Projection.FRONT);
                Vertex f11 = vertex(x + 1, y + 1, true, relief, distance,
                        width, height, modelWidth, atlas, Projection.FRONT);
                Vertex f01 = vertex(x, y + 1, true, relief, distance,
                        width, height, modelWidth, atlas, Projection.FRONT);
                triangle(positions, normals, uvs, indices, f00, f11, f10,
                        0.0f, 0.0f, 1.0f);
                triangle(positions, normals, uvs, indices, f00, f01, f11,
                        0.0f, 0.0f, 1.0f);

                Vertex b00 = vertex(x, y, false, relief, distance,
                        width, height, modelWidth, atlas, Projection.BACK);
                Vertex b10 = vertex(x + 1, y, false, relief, distance,
                        width, height, modelWidth, atlas, Projection.BACK);
                Vertex b11 = vertex(x + 1, y + 1, false, relief, distance,
                        width, height, modelWidth, atlas, Projection.BACK);
                Vertex b01 = vertex(x, y + 1, false, relief, distance,
                        width, height, modelWidth, atlas, Projection.BACK);
                triangle(positions, normals, uvs, indices, b00, b10, b11,
                        0.0f, 0.0f, -1.0f);
                triangle(positions, normals, uvs, indices, b00, b11, b01,
                        0.0f, 0.0f, -1.0f);

                if (x == 0 || !cellOn(mask, width, x - 1, y)) {
                    wall(positions, normals, uvs, indices, x, y + 1, x, y,
                            relief, distance, width, height, modelWidth, atlas,
                            -1.0f, 0.0f, Projection.LEFT);
                }
                if (x == width - 2 || !cellOn(mask, width, x + 1, y)) {
                    wall(positions, normals, uvs, indices, x + 1, y, x + 1, y + 1,
                            relief, distance, width, height, modelWidth, atlas,
                            1.0f, 0.0f, Projection.RIGHT);
                }
                if (y == 0 || !cellOn(mask, width, x, y - 1)) {
                    wall(positions, normals, uvs, indices, x, y, x + 1, y,
                            relief, distance, width, height, modelWidth, atlas,
                            0.0f, 1.0f,
                            x < width / 2 ? Projection.LEFT : Projection.RIGHT);
                }
                if (y == height - 2 || !cellOn(mask, width, x, y + 1)) {
                    wall(positions, normals, uvs, indices, x + 1, y + 1, x, y + 1,
                            relief, distance, width, height, modelWidth, atlas,
                            0.0f, -1.0f,
                            x < width / 2 ? Projection.LEFT : Projection.RIGHT);
                }
            }
        }
        if (indices.size < 3) {
            throw new IllegalArgumentException("Silhouette image inexploitable");
        }
        return new MeshData(
                positions.array(),
                normals.array(),
                uvs.array(),
                indices.array()
        );
    }

    private static void wall(
            FloatList positions,
            FloatList normals,
            FloatList uvs,
            IntList indices,
            int ax,
            int ay,
            int bx,
            int by,
            float[] relief,
            int[] distance,
            int width,
            int height,
            float modelWidth,
            SmoothHullMesher.AtlasLayout atlas,
            float nx,
            float ny,
            Projection projection
    ) {
        Vertex af = vertex(ax, ay, true, relief, distance,
                width, height, modelWidth, atlas, projection);
        Vertex bf = vertex(bx, by, true, relief, distance,
                width, height, modelWidth, atlas, projection);
        Vertex bb = vertex(bx, by, false, relief, distance,
                width, height, modelWidth, atlas, projection);
        Vertex ab = vertex(ax, ay, false, relief, distance,
                width, height, modelWidth, atlas, projection);
        triangle(positions, normals, uvs, indices, af, bf, bb, nx, ny, 0.0f);
        triangle(positions, normals, uvs, indices, af, bb, ab, nx, ny, 0.0f);
    }

    private static void triangle(
            FloatList positions,
            FloatList normals,
            FloatList uvs,
            IntList indices,
            Vertex a,
            Vertex b,
            Vertex c,
            float desiredX,
            float desiredY,
            float desiredZ
    ) {
        float abx = b.x - a.x;
        float aby = b.y - a.y;
        float abz = b.z - a.z;
        float acx = c.x - a.x;
        float acy = c.y - a.y;
        float acz = c.z - a.z;
        float nx = aby * acz - abz * acy;
        float ny = abz * acx - abx * acz;
        float nz = abx * acy - aby * acx;
        if (nx * desiredX + ny * desiredY + nz * desiredZ < 0.0f) {
            Vertex swap = b;
            b = c;
            c = swap;
            nx = -nx;
            ny = -ny;
            nz = -nz;
        }
        float length = Math.max(1.0e-8f,
                (float) Math.sqrt(nx * nx + ny * ny + nz * nz));
        nx /= length;
        ny /= length;
        nz /= length;
        int base = positions.size / 3;
        addVertex(positions, normals, uvs, a, nx, ny, nz);
        addVertex(positions, normals, uvs, b, nx, ny, nz);
        addVertex(positions, normals, uvs, c, nx, ny, nz);
        indices.add(base);
        indices.add(base + 1);
        indices.add(base + 2);
    }

    private static void addVertex(
            FloatList positions,
            FloatList normals,
            FloatList uvs,
            Vertex vertex,
            float nx,
            float ny,
            float nz
    ) {
        positions.add(vertex.x, vertex.y, vertex.z);
        normals.add(nx, ny, nz);
        uvs.add(vertex.u, vertex.v);
    }

    private static Vertex vertex(
            int x,
            int y,
            boolean front,
            float[] relief,
            int[] distance,
            int width,
            int height,
            float modelWidth,
            SmoothHullMesher.AtlasLayout atlas,
            Projection projection
    ) {
        int index = y * width + x;
        float normalizedX = x / Math.max(1.0f, width - 1.0f);
        float normalizedY = y / Math.max(1.0f, height - 1.0f);
        float modelX = (normalizedX - 0.5f) * modelWidth;
        float modelY = 1.0f - normalizedY * 2.0f;
        float edge = clamp01(distance[index] / 7.0f);
        float halfThickness = 0.045f + 0.115f * smooth(edge);
        float z = front
                ? halfThickness + relief[index]
                : -halfThickness * 0.82f;
        float u;
        float v = 1.0f - normalizedY;
        int start;
        int cellWidth;
        if (projection == Projection.FRONT) {
            start = atlas.frontStart;
            cellWidth = atlas.frontWidth;
            u = normalizedX;
        } else if (projection == Projection.BACK) {
            start = atlas.backStart;
            cellWidth = atlas.frontWidth;
            u = 1.0f - normalizedX;
        } else {
            start = projection == Projection.RIGHT
                    ? atlas.rightStart : atlas.leftStart;
            cellWidth = atlas.sideWidth;
            float thicknessPosition = front ? 0.95f : 0.05f;
            u = projection == Projection.RIGHT
                    ? thicknessPosition : 1.0f - thicknessPosition;
        }
        float padding = 2.0f;
        float atlasU = (start + padding + clamp01(u)
                * Math.max(1.0f, cellWidth - padding * 2.0f))
                / atlas.atlasWidth;
        float atlasV = (padding + clamp01(v)
                * Math.max(1.0f, atlas.atlasHeight - padding * 2.0f))
                / atlas.atlasHeight;
        return new Vertex(modelX, modelY, z, atlasU, atlasV);
    }

    private static float[] sampleRelief(
            NeuralDepthEngine.DepthMap depth,
            boolean[] mask,
            int[] distance,
            int width,
            int height
    ) {
        float[] values = new float[mask.length];
        if (depth == null) {
            return values;
        }
        float[] samples = new float[count(mask)];
        int sampleCount = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                if (!mask[index]) {
                    continue;
                }
                float value = depth.sample(
                        x / Math.max(1.0f, width - 1.0f),
                        y / Math.max(1.0f, height - 1.0f)
                );
                values[index] = value;
                samples[sampleCount++] = value;
            }
        }
        if (sampleCount < 16) {
            return new float[mask.length];
        }
        Arrays.sort(samples, 0, sampleCount);
        float low = samples[Math.round((sampleCount - 1) * 0.08f)];
        float high = samples[Math.round((sampleCount - 1) * 0.92f)];
        float range = Math.max(1.0e-6f, high - low);
        for (int i = 0; i < values.length; i++) {
            if (!mask[i]) {
                values[i] = 0.0f;
                continue;
            }
            float centered = clamp01((values[i] - low) / range) - 0.5f;
            float edgeGate = smooth(clamp01(distance[i] / 8.0f));
            values[i] = centered * 0.105f * edgeGate;
        }
        blurMasked(values, mask, width, height, 2);
        return values;
    }

    private static boolean[] sampleMask(
            Bitmap source,
            Rect crop,
            int width,
            int height
    ) {
        boolean[] output = new boolean[width * height];
        int[] pixels = new int[source.getWidth() * source.getHeight()];
        source.getPixels(pixels, 0, source.getWidth(), 0, 0,
                source.getWidth(), source.getHeight());
        for (int y = 0; y < height; y++) {
            int sy = Math.min(crop.bottom - 1,
                    crop.top + Math.round(y * (crop.height() - 1)
                            / Math.max(1.0f, height - 1.0f)));
            for (int x = 0; x < width; x++) {
                int sx = Math.min(crop.right - 1,
                        crop.left + Math.round(x * (crop.width() - 1)
                                / Math.max(1.0f, width - 1.0f)));
                output[y * width + x] = Color.alpha(
                        pixels[sy * source.getWidth() + sx]) >= ALPHA;
            }
        }
        return output;
    }

    private static Rect foregroundBounds(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        int left = width;
        int top = height;
        int right = -1;
        int bottom = -1;
        int count = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (Color.alpha(pixels[y * width + x]) < ALPHA) {
                    continue;
                }
                left = Math.min(left, x);
                top = Math.min(top, y);
                right = Math.max(right, x);
                bottom = Math.max(bottom, y);
                count++;
            }
        }
        if (right < left || bottom < top
                || count < Math.max(64, width * height / 2500)) {
            throw new IllegalArgumentException("Sujet image trop petit ou mal détouré");
        }
        int marginX = Math.max(2, Math.round((right - left + 1) * 0.035f));
        int marginY = Math.max(2, Math.round((bottom - top + 1) * 0.035f));
        return new Rect(
                Math.max(0, left - marginX),
                Math.max(0, top - marginY),
                Math.min(width, right + 1 + marginX),
                Math.min(height, bottom + 1 + marginY)
        );
    }

    private static Bitmap buildAtlas(
            Bitmap isolated,
            Rect crop,
            SmoothHullMesher.AtlasLayout layout
    ) {
        Bitmap front = Bitmap.createBitmap(isolated,
                crop.left, crop.top, crop.width(), crop.height());
        Bitmap back = mirroredTint(front, 0.78f);
        Bitmap side = sideTexture(front, layout.sideWidth, layout.atlasHeight);
        Bitmap atlas = Bitmap.createBitmap(layout.atlasWidth, layout.atlasHeight,
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(atlas);
        canvas.drawColor(Color.rgb(24, 26, 32));
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG
                | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        drawContained(canvas, paint, front, layout.frontStart,
                layout.frontWidth, layout.atlasHeight);
        drawContained(canvas, paint, back, layout.backStart,
                layout.frontWidth, layout.atlasHeight);
        drawContained(canvas, paint, side, layout.rightStart,
                layout.sideWidth, layout.atlasHeight);
        drawContained(canvas, paint, side, layout.leftStart,
                layout.sideWidth, layout.atlasHeight);
        front.recycle();
        back.recycle();
        side.recycle();
        return atlas;
    }

    private static void drawContained(
            Canvas canvas,
            Paint paint,
            Bitmap source,
            int start,
            int width,
            int height
    ) {
        float scale = Math.min(width * 0.96f / source.getWidth(),
                height * 0.96f / source.getHeight());
        float drawWidth = source.getWidth() * scale;
        float drawHeight = source.getHeight() * scale;
        RectF destination = new RectF(
                start + (width - drawWidth) * 0.5f,
                (height - drawHeight) * 0.5f,
                start + (width + drawWidth) * 0.5f,
                (height + drawHeight) * 0.5f
        );
        canvas.drawBitmap(source, null, destination, paint);
    }

    private static Bitmap mirroredTint(Bitmap source, float brightness) {
        Bitmap output = Bitmap.createBitmap(source.getWidth(), source.getHeight(),
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Matrix mirror = new Matrix();
        mirror.setScale(-1.0f, 1.0f, source.getWidth() * 0.5f,
                source.getHeight() * 0.5f);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        ColorMatrix matrix = new ColorMatrix(new float[]{
                brightness, 0, 0, 0, 0,
                0, brightness, 0, 0, 0,
                0, 0, brightness, 0, 0,
                0, 0, 0, 1, 0
        });
        paint.setColorFilter(new ColorMatrixColorFilter(matrix));
        canvas.drawBitmap(source, mirror, paint);
        return output;
    }

    private static Bitmap sideTexture(Bitmap front, int width, int height) {
        Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        canvas.drawColor(Color.TRANSPARENT);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        paint.setAlpha(220);
        canvas.drawBitmap(front, null,
                new RectF(0, 0, width, height), paint);
        return output;
    }

    private static boolean cellOn(boolean[] mask, int width, int x, int y) {
        int score = 0;
        if (mask[y * width + x]) score++;
        if (mask[y * width + x + 1]) score++;
        if (mask[(y + 1) * width + x]) score++;
        if (mask[(y + 1) * width + x + 1]) score++;
        return score >= 2;
    }

    private static void close(boolean[] mask, int width, int height) {
        boolean[] source = Arrays.copyOf(mask, mask.length);
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int index = y * width + x;
                if (!source[index]) {
                    int neighbours = 0;
                    for (int oy = -1; oy <= 1; oy++) {
                        for (int ox = -1; ox <= 1; ox++) {
                            if (source[(y + oy) * width + x + ox]) neighbours++;
                        }
                    }
                    if (neighbours >= 5) mask[index] = true;
                }
            }
        }
    }

    private static int[] distanceToOutside(boolean[] mask, int width, int height) {
        int[] distance = new int[mask.length];
        Arrays.fill(distance, 1_000_000);
        int[] queue = new int[mask.length];
        int head = 0;
        int tail = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                if (!mask[index] || x == 0 || y == 0
                        || x == width - 1 || y == height - 1) {
                    distance[index] = 0;
                    queue[tail++] = index;
                }
            }
        }
        while (head < tail) {
            int current = queue[head++];
            int x = current % width;
            int y = current / width;
            tail = visit(distance, queue, tail, current, x - 1, y, width, height);
            tail = visit(distance, queue, tail, current, x + 1, y, width, height);
            tail = visit(distance, queue, tail, current, x, y - 1, width, height);
            tail = visit(distance, queue, tail, current, x, y + 1, width, height);
        }
        return distance;
    }

    private static int visit(int[] distance, int[] queue, int tail,
                             int current, int x, int y, int width, int height) {
        if (x < 0 || y < 0 || x >= width || y >= height) return tail;
        int target = y * width + x;
        int candidate = distance[current] + 1;
        if (candidate < distance[target]) {
            distance[target] = candidate;
            queue[tail++] = target;
        }
        return tail;
    }

    private static void blurMasked(float[] values, boolean[] mask,
                                   int width, int height, int passes) {
        float[] temporary = new float[values.length];
        for (int pass = 0; pass < passes; pass++) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int index = y * width + x;
                    if (!mask[index]) continue;
                    float sum = 0.0f;
                    float weight = 0.0f;
                    for (int oy = -1; oy <= 1; oy++) {
                        int sy = y + oy;
                        if (sy < 0 || sy >= height) continue;
                        for (int ox = -1; ox <= 1; ox++) {
                            int sx = x + ox;
                            if (sx < 0 || sx >= width) continue;
                            int sample = sy * width + sx;
                            if (!mask[sample]) continue;
                            float w = ox == 0 && oy == 0 ? 2.0f : 1.0f;
                            sum += values[sample] * w;
                            weight += w;
                        }
                    }
                    temporary[index] = sum / Math.max(1.0f, weight);
                }
            }
            System.arraycopy(temporary, 0, values, 0, values.length);
        }
    }

    private static int count(boolean[] values) {
        int count = 0;
        for (boolean value : values) if (value) count++;
        return count;
    }

    private static float smooth(float value) {
        return value * value * (3.0f - 2.0f * value);
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    public static final class Result {
        private final MeshData mesh;
        private final Bitmap texture;
        private final int width;
        private final int height;
        private final int occupied;

        Result(MeshData mesh, Bitmap texture, int width, int height, int occupied) {
            this.mesh = mesh;
            this.texture = texture;
            this.width = width;
            this.height = height;
            this.occupied = occupied;
        }

        public MeshData getMesh() {
            return mesh;
        }

        public Bitmap getTexture() {
            return texture;
        }

        public String getQualityLabel() {
            return "Relief image fermé " + width + "×" + height
                    + " • " + occupied + " points utiles";
        }
    }

    private enum Projection { FRONT, BACK, RIGHT, LEFT }

    private static final class Grid {
        final int width;
        final int height;

        Grid(int width, int height) {
            this.width = width;
            this.height = height;
        }

        static Grid from(Rect crop) {
            float aspect = crop.width() / (float) Math.max(1, crop.height());
            return aspect <= 1.0f
                    ? new Grid(Math.max(MIN_SIDE, Math.round(LONG_SIDE * aspect)),
                    LONG_SIDE)
                    : new Grid(LONG_SIDE,
                    Math.max(MIN_SIDE, Math.round(LONG_SIDE / aspect)));
        }
    }

    private static final class Vertex {
        final float x;
        final float y;
        final float z;
        final float u;
        final float v;

        Vertex(float x, float y, float z, float u, float v) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.u = u;
            this.v = v;
        }
    }

    private static final class FloatList {
        float[] values;
        int size;

        FloatList(int capacity) {
            values = new float[Math.max(16, capacity)];
        }

        void add(float a, float b) {
            ensure(2);
            values[size++] = a;
            values[size++] = b;
        }

        void add(float a, float b, float c) {
            ensure(3);
            values[size++] = a;
            values[size++] = b;
            values[size++] = c;
        }

        float[] array() {
            return Arrays.copyOf(values, size);
        }

        private void ensure(int additional) {
            if (size + additional > values.length) {
                values = Arrays.copyOf(values,
                        Math.max(size + additional, values.length * 2));
            }
        }
    }

    private static final class IntList {
        int[] values;
        int size;

        IntList(int capacity) {
            values = new int[Math.max(16, capacity)];
        }

        void add(int value) {
            if (size == values.length) {
                values = Arrays.copyOf(values, values.length * 2);
            }
            values[size++] = value;
        }

        int[] array() {
            return Arrays.copyOf(values, size);
        }
    }
}
