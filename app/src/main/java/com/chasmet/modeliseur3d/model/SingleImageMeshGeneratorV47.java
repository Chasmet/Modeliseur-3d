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

import com.chasmet.modeliseur3d.performance.DevicePerformanceProfile;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Générateur image V4.7 : maillage fermé indexé, sommets partagés, relief
 * neuronal et épaisseur guidée par la distance à la silhouette.
 */
public final class SingleImageMeshGeneratorV47 {
    private static final int ALPHA_THRESHOLD = 20;
    private static final float MAX_MODEL_WIDTH = 2.8f;

    public Result generate(
            Bitmap isolated,
            NeuralDepthEngine.DepthMap depthMap,
            DevicePerformanceProfile profile
    ) {
        if (isolated == null || isolated.isRecycled()) {
            throw new IllegalArgumentException("Personnage détouré absent");
        }
        if (profile == null) {
            throw new IllegalArgumentException("Profil de calcul absent");
        }

        Rect crop = foregroundBounds(isolated);
        Grid grid = Grid.from(
                crop.width(),
                crop.height(),
                profile.getImageGridLongSide()
        );
        boolean[] occupied = sampleCells(
                isolated,
                crop,
                grid.cellWidth,
                grid.cellHeight
        );
        close(occupied, grid.cellWidth, grid.cellHeight, 2);
        occupied = SingleSubjectSelector.select(
                occupied,
                grid.cellWidth,
                grid.cellHeight
        ).getMask();
        fillEnclosedHoles(occupied, grid.cellWidth, grid.cellHeight);

        int occupiedCount = count(occupied);
        if (occupiedCount < Math.max(40, occupied.length / 100)) {
            throw new IllegalArgumentException(
                    "Silhouette insuffisante pour créer un maillage propre"
            );
        }

        int[] distance = distanceToOutside(
                occupied,
                grid.cellWidth,
                grid.cellHeight
        );
        DepthGrid depth = DepthGrid.sample(
                depthMap,
                grid.cellWidth + 1,
                grid.cellHeight + 1
        );
        TextureAtlas atlas = buildAtlas(
                isolated,
                crop,
                profile.getTextureHeight()
        );

        MeshData mesh;
        try {
            mesh = buildMesh(
                    occupied,
                    distance,
                    depth,
                    grid,
                    atlas
            );
        } catch (RuntimeException error) {
            atlas.bitmap.recycle();
            throw error;
        }

        if (mesh.getTriangleCount() > profile.getImageTriangleTarget() * 6 / 5) {
            try {
                mesh = MobileMeshOptimizer.simplify(
                        mesh,
                        profile.getImageTriangleTarget()
                );
            } catch (RuntimeException ignored) {
                // Le maillage indexé original reste propre et fermé.
            }
        }
        return new Result(
                mesh,
                atlas.bitmap,
                grid.cellWidth,
                grid.cellHeight,
                occupiedCount,
                profile.getLabel()
                        + " • maillage indexé fermé • relief Depth Anything"
        );
    }

    private static MeshData buildMesh(
            boolean[] occupied,
            int[] distance,
            DepthGrid depth,
            Grid grid,
            TextureAtlas atlas
    ) {
        int cellsWidth = grid.cellWidth;
        int cellsHeight = grid.cellHeight;
        int verticesWidth = cellsWidth + 1;
        int verticesHeight = cellsHeight + 1;
        int vertexGridCount = verticesWidth * verticesHeight;
        boolean[] activeCorner = new boolean[vertexGridCount];
        for (int y = 0; y < cellsHeight; y++) {
            for (int x = 0; x < cellsWidth; x++) {
                if (!occupied[y * cellsWidth + x]) {
                    continue;
                }
                activeCorner[y * verticesWidth + x] = true;
                activeCorner[y * verticesWidth + x + 1] = true;
                activeCorner[(y + 1) * verticesWidth + x] = true;
                activeCorner[(y + 1) * verticesWidth + x + 1] = true;
            }
        }

        FloatList positions = new FloatList(vertexGridCount * 8);
        FloatList uvs = new FloatList(vertexGridCount * 4);
        IntList indices = new IntList(cellsWidth * cellsHeight * 15);
        int[] frontIds = new int[vertexGridCount];
        int[] backIds = new int[vertexGridCount];
        Arrays.fill(frontIds, -1);
        Arrays.fill(backIds, -1);

        float modelWidth = Math.min(
                MAX_MODEL_WIDTH,
                2.0f * grid.sourceAspect
        );
        for (int y = 0; y < verticesHeight; y++) {
            float v = y / (float) Math.max(1, cellsHeight);
            float modelY = 1.0f - v * 2.0f;
            for (int x = 0; x < verticesWidth; x++) {
                int corner = y * verticesWidth + x;
                if (!activeCorner[corner]) {
                    continue;
                }
                float u = x / (float) Math.max(1, cellsWidth);
                float modelX = (u - 0.5f) * modelWidth;
                float edge = smooth(clamp01(
                        cornerDistance(distance, occupied,
                                cellsWidth, cellsHeight, x, y) / 10.0f
                ));
                float centeredDepth = depth.centered[corner];
                float localDetail = depth.detail[corner];
                float halfThickness = 0.035f + 0.225f * edge;
                float frontZ = halfThickness
                        + centeredDepth * 0.19f * edge
                        + localDetail * 0.075f * edge;
                float backZ = -halfThickness * (0.72f + 0.10f * edge)
                        + centeredDepth * 0.028f * edge;
                frontZ = clamp(frontZ, 0.025f, 0.48f);
                backZ = clamp(backZ, -0.38f, -0.018f);

                frontIds[corner] = addVertex(
                        positions,
                        uvs,
                        modelX,
                        modelY,
                        frontZ,
                        atlas.frontU(u),
                        1.0f - v
                );
                backIds[corner] = addVertex(
                        positions,
                        uvs,
                        modelX,
                        modelY,
                        backZ,
                        atlas.backU(u),
                        1.0f - v
                );
            }
        }

        for (int y = 0; y < cellsHeight; y++) {
            for (int x = 0; x < cellsWidth; x++) {
                if (!occupied[y * cellsWidth + x]) {
                    continue;
                }
                int c00 = y * verticesWidth + x;
                int c10 = c00 + 1;
                int c01 = (y + 1) * verticesWidth + x;
                int c11 = c01 + 1;

                addTriangleOriented(
                        positions,
                        indices,
                        frontIds[c00],
                        frontIds[c11],
                        frontIds[c10],
                        0.0f,
                        0.0f,
                        1.0f
                );
                addTriangleOriented(
                        positions,
                        indices,
                        frontIds[c00],
                        frontIds[c01],
                        frontIds[c11],
                        0.0f,
                        0.0f,
                        1.0f
                );
                addTriangleOriented(
                        positions,
                        indices,
                        backIds[c00],
                        backIds[c10],
                        backIds[c11],
                        0.0f,
                        0.0f,
                        -1.0f
                );
                addTriangleOriented(
                        positions,
                        indices,
                        backIds[c00],
                        backIds[c11],
                        backIds[c01],
                        0.0f,
                        0.0f,
                        -1.0f
                );

                if (x == 0 || !occupied[y * cellsWidth + x - 1]) {
                    addWall(
                            positions,
                            uvs,
                            indices,
                            frontIds[c01],
                            frontIds[c00],
                            backIds[c00],
                            backIds[c01],
                            atlas,
                            y / (float) cellsHeight,
                            (y + 1) / (float) cellsHeight,
                            -1.0f,
                            0.0f
                    );
                }
                if (x == cellsWidth - 1
                        || !occupied[y * cellsWidth + x + 1]) {
                    addWall(
                            positions,
                            uvs,
                            indices,
                            frontIds[c10],
                            frontIds[c11],
                            backIds[c11],
                            backIds[c10],
                            atlas,
                            y / (float) cellsHeight,
                            (y + 1) / (float) cellsHeight,
                            1.0f,
                            0.0f
                    );
                }
                if (y == 0 || !occupied[(y - 1) * cellsWidth + x]) {
                    addWall(
                            positions,
                            uvs,
                            indices,
                            frontIds[c00],
                            frontIds[c10],
                            backIds[c10],
                            backIds[c00],
                            atlas,
                            x / (float) cellsWidth,
                            (x + 1) / (float) cellsWidth,
                            0.0f,
                            1.0f
                    );
                }
                if (y == cellsHeight - 1
                        || !occupied[(y + 1) * cellsWidth + x]) {
                    addWall(
                            positions,
                            uvs,
                            indices,
                            frontIds[c11],
                            frontIds[c01],
                            backIds[c01],
                            backIds[c11],
                            atlas,
                            x / (float) cellsWidth,
                            (x + 1) / (float) cellsWidth,
                            0.0f,
                            -1.0f
                    );
                }
            }
        }

        float[] positionArray = positions.toArray();
        int[] indexArray = indices.toArray();
        if (indexArray.length < 3) {
            throw new IllegalArgumentException("Maillage image vide");
        }
        float[] normalArray = calculateNormals(positionArray, indexArray);
        return new MeshData(
                positionArray,
                normalArray,
                uvs.toArray(),
                indexArray
        );
    }

    private static void addWall(
            FloatList positions,
            FloatList uvs,
            IntList indices,
            int frontA,
            int frontB,
            int backB,
            int backA,
            TextureAtlas atlas,
            float firstV,
            float secondV,
            float desiredX,
            float desiredY
    ) {
        int a = duplicateVertex(
                positions,
                uvs,
                frontA,
                atlas.sideU(true),
                1.0f - firstV
        );
        int b = duplicateVertex(
                positions,
                uvs,
                frontB,
                atlas.sideU(true),
                1.0f - secondV
        );
        int c = duplicateVertex(
                positions,
                uvs,
                backB,
                atlas.sideU(false),
                1.0f - secondV
        );
        int d = duplicateVertex(
                positions,
                uvs,
                backA,
                atlas.sideU(false),
                1.0f - firstV
        );
        addTriangleOriented(
                positions,
                indices,
                a,
                b,
                c,
                desiredX,
                desiredY,
                0.0f
        );
        addTriangleOriented(
                positions,
                indices,
                a,
                c,
                d,
                desiredX,
                desiredY,
                0.0f
        );
    }

    private static int addVertex(
            FloatList positions,
            FloatList uvs,
            float x,
            float y,
            float z,
            float u,
            float v
    ) {
        int id = positions.size / 3;
        positions.add(x, y, z);
        uvs.add(clamp01(u), clamp01(v));
        return id;
    }

    private static int duplicateVertex(
            FloatList positions,
            FloatList uvs,
            int sourceId,
            float u,
            float v
    ) {
        int position = sourceId * 3;
        return addVertex(
                positions,
                uvs,
                positions.values[position],
                positions.values[position + 1],
                positions.values[position + 2],
                u,
                v
        );
    }

    private static void addTriangleOriented(
            FloatList positions,
            IntList indices,
            int a,
            int b,
            int c,
            float desiredX,
            float desiredY,
            float desiredZ
    ) {
        if (a < 0 || b < 0 || c < 0 || a == b || b == c || c == a) {
            return;
        }
        float[] normal = triangleNormal(positions.values, a, b, c);
        if (normal[0] * desiredX
                + normal[1] * desiredY
                + normal[2] * desiredZ < 0.0f) {
            int swap = b;
            b = c;
            c = swap;
        }
        indices.add(a);
        indices.add(b);
        indices.add(c);
    }

    private static float[] triangleNormal(float[] positions, int a, int b, int c) {
        int ai = a * 3;
        int bi = b * 3;
        int ci = c * 3;
        float abx = positions[bi] - positions[ai];
        float aby = positions[bi + 1] - positions[ai + 1];
        float abz = positions[bi + 2] - positions[ai + 2];
        float acx = positions[ci] - positions[ai];
        float acy = positions[ci + 1] - positions[ai + 1];
        float acz = positions[ci + 2] - positions[ai + 2];
        return new float[]{
                aby * acz - abz * acy,
                abz * acx - abx * acz,
                abx * acy - aby * acx
        };
    }

    private static float[] calculateNormals(float[] positions, int[] indices) {
        float[] normals = new float[positions.length];
        for (int index = 0; index + 2 < indices.length; index += 3) {
            int a = indices[index];
            int b = indices[index + 1];
            int c = indices[index + 2];
            float[] normal = triangleNormal(positions, a, b, c);
            accumulate(normals, a, normal);
            accumulate(normals, b, normal);
            accumulate(normals, c, normal);
        }
        for (int vertex = 0; vertex < normals.length / 3; vertex++) {
            int offset = vertex * 3;
            float x = normals[offset];
            float y = normals[offset + 1];
            float z = normals[offset + 2];
            float length = (float) Math.sqrt(x * x + y * y + z * z);
            if (length < 1.0e-8f) {
                normals[offset + 2] = 1.0f;
            } else {
                normals[offset] = x / length;
                normals[offset + 1] = y / length;
                normals[offset + 2] = z / length;
            }
        }
        return normals;
    }

    private static void accumulate(float[] normals, int vertex, float[] value) {
        int offset = vertex * 3;
        normals[offset] += value[0];
        normals[offset + 1] += value[1];
        normals[offset + 2] += value[2];
    }

    private static boolean[] sampleCells(
            Bitmap source,
            Rect crop,
            int width,
            int height
    ) {
        int[] pixels = new int[source.getWidth() * source.getHeight()];
        source.getPixels(
                pixels,
                0,
                source.getWidth(),
                0,
                0,
                source.getWidth(),
                source.getHeight()
        );
        boolean[] output = new boolean[width * height];
        for (int y = 0; y < height; y++) {
            float v = (y + 0.5f) / height;
            int sourceY = clamp(
                    crop.top + Math.round(v * Math.max(0, crop.height() - 1)),
                    crop.top,
                    crop.bottom - 1
            );
            for (int x = 0; x < width; x++) {
                float u = (x + 0.5f) / width;
                int sourceX = clamp(
                        crop.left + Math.round(u * Math.max(0, crop.width() - 1)),
                        crop.left,
                        crop.right - 1
                );
                output[y * width + x] = Color.alpha(
                        pixels[sourceY * source.getWidth() + sourceX]
                ) >= ALPHA_THRESHOLD;
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
        int foreground = 0;
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                if (Color.alpha(pixels[row + x]) < ALPHA_THRESHOLD) {
                    continue;
                }
                foreground++;
                left = Math.min(left, x);
                top = Math.min(top, y);
                right = Math.max(right, x);
                bottom = Math.max(bottom, y);
            }
        }
        if (right < left || bottom < top
                || foreground < Math.max(96, width * height / 1200)) {
            throw new IllegalArgumentException("Personnage détouré trop petit");
        }
        int marginX = Math.max(2, Math.round((right - left + 1) * 0.025f));
        int marginY = Math.max(2, Math.round((bottom - top + 1) * 0.020f));
        return new Rect(
                clamp(left - marginX, 0, width - 1),
                clamp(top - marginY, 0, height - 1),
                clamp(right + marginX + 1, left + 1, width),
                clamp(bottom + marginY + 1, top + 1, height)
        );
    }

    private static int[] distanceToOutside(
            boolean[] occupied,
            int width,
            int height
    ) {
        int[] distance = new int[occupied.length];
        Arrays.fill(distance, Integer.MAX_VALUE);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                if (!occupied[index]) {
                    distance[index] = 0;
                    queue.addLast(index);
                } else if (x == 0 || y == 0 || x == width - 1 || y == height - 1) {
                    distance[index] = 1;
                    queue.addLast(index);
                }
            }
        }
        if (queue.isEmpty()) {
            for (int x = 0; x < width; x++) {
                distance[x] = 1;
                distance[(height - 1) * width + x] = 1;
                queue.addLast(x);
                queue.addLast((height - 1) * width + x);
            }
        }
        while (!queue.isEmpty()) {
            int current = queue.removeFirst();
            int x = current % width;
            int y = current / width;
            int nextDistance = distance[current] + 1;
            if (x > 0) {
                propagateDistance(distance, queue, current - 1, nextDistance);
            }
            if (x + 1 < width) {
                propagateDistance(distance, queue, current + 1, nextDistance);
            }
            if (y > 0) {
                propagateDistance(distance, queue, current - width, nextDistance);
            }
            if (y + 1 < height) {
                propagateDistance(distance, queue, current + width, nextDistance);
            }
        }
        return distance;
    }

    private static void propagateDistance(
            int[] distance,
            ArrayDeque<Integer> queue,
            int target,
            int value
    ) {
        if (value < distance[target]) {
            distance[target] = value;
            queue.addLast(target);
        }
    }

    private static float cornerDistance(
            int[] distance,
            boolean[] occupied,
            int width,
            int height,
            int cornerX,
            int cornerY
    ) {
        float sum = 0.0f;
        int count = 0;
        for (int oy = -1; oy <= 0; oy++) {
            for (int ox = -1; ox <= 0; ox++) {
                int x = cornerX + ox;
                int y = cornerY + oy;
                if (x < 0 || y < 0 || x >= width || y >= height) {
                    continue;
                }
                int index = y * width + x;
                if (occupied[index]) {
                    sum += distance[index];
                    count++;
                }
            }
        }
        return count == 0 ? 0.0f : sum / count;
    }

    private static void close(
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
                    if (source[index]) {
                        continue;
                    }
                    int neighbors = 0;
                    for (int oy = -1; oy <= 1; oy++) {
                        for (int ox = -1; ox <= 1; ox++) {
                            if (source[(y + oy) * width + x + ox]) {
                                neighbors++;
                            }
                        }
                    }
                    if (neighbors >= 4) {
                        mask[index] = true;
                    }
                }
            }
        }
    }

    private static void fillEnclosedHoles(
            boolean[] occupied,
            int width,
            int height
    ) {
        boolean[] outside = new boolean[occupied.length];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int x = 0; x < width; x++) {
            enqueueOutside(occupied, outside, queue, x);
            enqueueOutside(occupied, outside, queue, (height - 1) * width + x);
        }
        for (int y = 0; y < height; y++) {
            enqueueOutside(occupied, outside, queue, y * width);
            enqueueOutside(occupied, outside, queue, y * width + width - 1);
        }
        while (!queue.isEmpty()) {
            int current = queue.removeFirst();
            int x = current % width;
            int y = current / width;
            if (x > 0) {
                enqueueOutside(occupied, outside, queue, current - 1);
            }
            if (x + 1 < width) {
                enqueueOutside(occupied, outside, queue, current + 1);
            }
            if (y > 0) {
                enqueueOutside(occupied, outside, queue, current - width);
            }
            if (y + 1 < height) {
                enqueueOutside(occupied, outside, queue, current + width);
            }
        }
        for (int index = 0; index < occupied.length; index++) {
            if (!occupied[index] && !outside[index]) {
                occupied[index] = true;
            }
        }
    }

    private static void enqueueOutside(
            boolean[] occupied,
            boolean[] outside,
            ArrayDeque<Integer> queue,
            int index
    ) {
        if (!occupied[index] && !outside[index]) {
            outside[index] = true;
            queue.addLast(index);
        }
    }

    private static TextureAtlas buildAtlas(
            Bitmap source,
            Rect crop,
            int requestedHeight
    ) {
        int atlasHeight = clamp(requestedHeight, 768, 2048);
        float aspect = crop.width() / (float) Math.max(1, crop.height());
        int frontWidth = clamp(
                Math.round(atlasHeight * aspect),
                atlasHeight / 3,
                1792
        );
        int sideWidth = Math.max(128, Math.min(320, frontWidth / 5));
        int atlasWidth = frontWidth * 2 + sideWidth;
        Bitmap atlas = Bitmap.createBitmap(
                atlasWidth,
                atlasHeight,
                Bitmap.Config.ARGB_8888
        );
        Canvas canvas = new Canvas(atlas);
        canvas.drawColor(Color.rgb(24, 26, 32));
        Paint paint = new Paint(
                Paint.ANTI_ALIAS_FLAG
                        | Paint.FILTER_BITMAP_FLAG
                        | Paint.DITHER_FLAG
        );
        RectF frontRect = new RectF(0, 0, frontWidth, atlasHeight);
        canvas.drawBitmap(source, crop, frontRect, paint);

        Bitmap cropped = Bitmap.createBitmap(
                source,
                crop.left,
                crop.top,
                crop.width(),
                crop.height()
        );
        Matrix mirror = new Matrix();
        mirror.setScale(-1.0f, 1.0f);
        Bitmap mirrored = Bitmap.createBitmap(
                cropped,
                0,
                0,
                cropped.getWidth(),
                cropped.getHeight(),
                mirror,
                true
        );
        Paint backPaint = darkenedPaint(0.80f, 0.92f);
        canvas.drawBitmap(
                mirrored,
                null,
                new RectF(frontWidth, 0, frontWidth * 2, atlasHeight),
                backPaint
        );
        Paint sidePaint = darkenedPaint(0.66f, 0.86f);
        canvas.drawBitmap(
                cropped,
                null,
                new RectF(
                        frontWidth * 2,
                        0,
                        frontWidth * 2 + sideWidth,
                        atlasHeight
                ),
                sidePaint
        );
        cropped.recycle();
        mirrored.recycle();
        return new TextureAtlas(
                atlas,
                frontWidth,
                sideWidth,
                atlasWidth,
                atlasHeight
        );
    }

    private static Paint darkenedPaint(float brightness, float saturation) {
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(saturation);
        ColorMatrix brightnessMatrix = new ColorMatrix(new float[]{
                brightness, 0, 0, 0, 0,
                0, brightness, 0, 0, 0,
                0, 0, brightness, 0, 0,
                0, 0, 0, 1, 0
        });
        matrix.postConcat(brightnessMatrix);
        Paint paint = new Paint(
                Paint.ANTI_ALIAS_FLAG
                        | Paint.FILTER_BITMAP_FLAG
                        | Paint.DITHER_FLAG
        );
        paint.setColorFilter(new ColorMatrixColorFilter(matrix));
        return paint;
    }

    private static int count(boolean[] values) {
        int count = 0;
        for (boolean value : values) {
            if (value) {
                count++;
            }
        }
        return count;
    }

    private static float smooth(float value) {
        value = clamp01(value);
        return value * value * (3.0f - 2.0f * value);
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public static final class Result {
        private final MeshData mesh;
        private final Bitmap texture;
        private final int gridWidth;
        private final int gridHeight;
        private final int occupiedCells;
        private final String qualityLabel;

        Result(
                MeshData mesh,
                Bitmap texture,
                int gridWidth,
                int gridHeight,
                int occupiedCells,
                String qualityLabel
        ) {
            this.mesh = mesh;
            this.texture = texture;
            this.gridWidth = gridWidth;
            this.gridHeight = gridHeight;
            this.occupiedCells = occupiedCells;
            this.qualityLabel = qualityLabel;
        }

        public MeshData getMesh() {
            return mesh;
        }

        public Bitmap getTexture() {
            return texture;
        }

        public int getGridWidth() {
            return gridWidth;
        }

        public int getGridHeight() {
            return gridHeight;
        }

        public int getOccupiedCells() {
            return occupiedCells;
        }

        public String getQualityLabel() {
            return qualityLabel;
        }
    }

    private static final class Grid {
        final int cellWidth;
        final int cellHeight;
        final float sourceAspect;

        Grid(int cellWidth, int cellHeight, float sourceAspect) {
            this.cellWidth = cellWidth;
            this.cellHeight = cellHeight;
            this.sourceAspect = sourceAspect;
        }

        static Grid from(int sourceWidth, int sourceHeight, int longSide) {
            float aspect = sourceWidth / (float) Math.max(1, sourceHeight);
            int width;
            int height;
            if (sourceWidth >= sourceHeight) {
                width = longSide;
                height = Math.max(64, Math.round(longSide / aspect));
            } else {
                height = longSide;
                width = Math.max(64, Math.round(longSide * aspect));
            }
            return new Grid(width, height, aspect);
        }
    }

    private static final class DepthGrid {
        final float[] centered;
        final float[] detail;

        DepthGrid(float[] centered, float[] detail) {
            this.centered = centered;
            this.detail = detail;
        }

        static DepthGrid sample(
                NeuralDepthEngine.DepthMap depthMap,
                int width,
                int height
        ) {
            float[] values = new float[width * height];
            if (depthMap == null) {
                return new DepthGrid(values, new float[values.length]);
            }
            float[] sorted = new float[values.length];
            for (int y = 0; y < height; y++) {
                float v = y / (float) Math.max(1, height - 1);
                for (int x = 0; x < width; x++) {
                    float u = x / (float) Math.max(1, width - 1);
                    float value = depthMap.sample(u, v);
                    values[y * width + x] = value;
                    sorted[y * width + x] = value;
                }
            }
            Arrays.sort(sorted);
            float low = sorted[Math.round((sorted.length - 1) * 0.06f)];
            float high = sorted[Math.round((sorted.length - 1) * 0.94f)];
            float range = Math.max(1.0e-6f, high - low);
            for (int index = 0; index < values.length; index++) {
                values[index] = clamp01((values[index] - low) / range) - 0.5f;
            }
            float[] blurred = Arrays.copyOf(values, values.length);
            blur(blurred, width, height, 3);
            float[] detail = new float[values.length];
            for (int index = 0; index < values.length; index++) {
                detail[index] = clamp(
                        values[index] - blurred[index],
                        -0.35f,
                        0.35f
                );
                values[index] = blurred[index];
            }
            return new DepthGrid(values, detail);
        }

        private static void blur(
                float[] values,
                int width,
                int height,
                int passes
        ) {
            float[] temporary = new float[values.length];
            for (int pass = 0; pass < passes; pass++) {
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        float sum = 0.0f;
                        int count = 0;
                        for (int oy = -1; oy <= 1; oy++) {
                            int sy = y + oy;
                            if (sy < 0 || sy >= height) {
                                continue;
                            }
                            for (int ox = -1; ox <= 1; ox++) {
                                int sx = x + ox;
                                if (sx < 0 || sx >= width) {
                                    continue;
                                }
                                sum += values[sy * width + sx];
                                count++;
                            }
                        }
                        temporary[y * width + x] = sum / Math.max(1, count);
                    }
                }
                System.arraycopy(temporary, 0, values, 0, values.length);
            }
        }
    }

    private static final class TextureAtlas {
        final Bitmap bitmap;
        final int frontWidth;
        final int sideWidth;
        final int atlasWidth;
        final int atlasHeight;

        TextureAtlas(
                Bitmap bitmap,
                int frontWidth,
                int sideWidth,
                int atlasWidth,
                int atlasHeight
        ) {
            this.bitmap = bitmap;
            this.frontWidth = frontWidth;
            this.sideWidth = sideWidth;
            this.atlasWidth = atlasWidth;
            this.atlasHeight = atlasHeight;
        }

        float frontU(float normalized) {
            return (2.0f + clamp01(normalized) * Math.max(1, frontWidth - 4))
                    / atlasWidth;
        }

        float backU(float normalized) {
            return (frontWidth + 2.0f
                    + (1.0f - clamp01(normalized)) * Math.max(1, frontWidth - 4))
                    / atlasWidth;
        }

        float sideU(boolean front) {
            float position = front ? 0.15f : 0.85f;
            return (frontWidth * 2.0f + position * sideWidth) / atlasWidth;
        }
    }

    private static final class FloatList {
        float[] values;
        int size;

        FloatList(int capacity) {
            values = new float[Math.max(16, capacity)];
        }

        void add(float first, float second) {
            ensure(2);
            values[size++] = first;
            values[size++] = second;
        }

        void add(float first, float second, float third) {
            ensure(3);
            values[size++] = first;
            values[size++] = second;
            values[size++] = third;
        }

        float[] toArray() {
            return Arrays.copyOf(values, size);
        }

        private void ensure(int amount) {
            if (size + amount <= values.length) {
                return;
            }
            values = Arrays.copyOf(
                    values,
                    Math.max(size + amount, values.length * 2)
            );
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

        int[] toArray() {
            return Arrays.copyOf(values, size);
        }
    }
}
