package com.chasmet.modeliseur3d.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Génère une surface lisse à partir d'une enveloppe volumique binaire.
 *
 * La V3 remplace les cubes visibles de la V2 par :
 * - un champ de densité lissé ;
 * - une extraction de surface par tétraèdres ;
 * - des normales calculées depuis le gradient ;
 * - une projection de texture multivue par triangle.
 */
public final class SmoothHullMesher {
    private static final float ISO_LEVEL = 0.43f;
    private static final int SMOOTHING_PASSES = 2;

    private static final int[][] CORNER_OFFSETS = {
            {0, 0, 0},
            {1, 0, 0},
            {1, 1, 0},
            {0, 1, 0},
            {0, 0, 1},
            {1, 0, 1},
            {1, 1, 1},
            {0, 1, 1}
    };

    private static final int[][] TETRAHEDRA = {
            {0, 5, 1, 6},
            {0, 1, 2, 6},
            {0, 2, 3, 6},
            {0, 3, 7, 6},
            {0, 7, 4, 6},
            {0, 4, 5, 6}
    };

    private static final int[][] TETRA_EDGES = {
            {0, 1},
            {1, 2},
            {2, 0},
            {0, 3},
            {1, 3},
            {2, 3}
    };

    private SmoothHullMesher() {
    }

    public static MeshData build(
            boolean[] occupancy,
            int width,
            int height,
            int depth,
            AtlasLayout atlas,
            int availableProcessors
    ) throws Exception {
        if (occupancy == null || occupancy.length != width * height * depth) {
            throw new IllegalArgumentException("Volume 3D invalide");
        }
        if (width < 4 || height < 4 || depth < 4) {
            throw new IllegalArgumentException("Résolution 3D trop faible");
        }

        float[] field = createSmoothField(occupancy, width, height, depth);
        GradientField gradient = createGradientField(field, width, height, depth);

        int workers = Math.max(1, Math.min(availableProcessors - 1, 10));
        int cubeRows = height - 1;
        int rowsPerTask = Math.max(4, (cubeRows + workers - 1) / workers);

        ExecutorService executor = Executors.newFixedThreadPool(workers);
        List<Future<MeshPart>> futures = new ArrayList<>();
        for (int startY = 0; startY < cubeRows; startY += rowsPerTask) {
            final int from = startY;
            final int to = Math.min(cubeRows, startY + rowsPerTask);
            futures.add(executor.submit(new Callable<MeshPart>() {
                @Override
                public MeshPart call() {
                    return polygonizeRange(
                            field,
                            gradient,
                            width,
                            height,
                            depth,
                            atlas,
                            from,
                            to
                    );
                }
            }));
        }

        List<MeshPart> parts = new ArrayList<>();
        try {
            for (Future<MeshPart> future : futures) {
                parts.add(future.get());
            }
        } finally {
            executor.shutdownNow();
        }

        MeshData mesh = merge(parts);
        if (mesh.getTriangleCount() == 0) {
            throw new IllegalArgumentException("Aucune surface propre n'a pu être extraite");
        }
        return mesh;
    }

    private static float[] createSmoothField(
            boolean[] occupancy,
            int width,
            int height,
            int depth
    ) {
        int size = occupancy.length;
        float[] field = new float[size];
        for (int i = 0; i < size; i++) {
            field[i] = occupancy[i] ? 1.0f : 0.0f;
        }

        float[] temporary = new float[size];
        for (int pass = 0; pass < SMOOTHING_PASSES; pass++) {
            blurX(field, temporary, width, height, depth);
            blurY(temporary, field, width, height, depth);
            blurZ(field, temporary, width, height, depth);
            float[] swap = field;
            field = temporary;
            temporary = swap;
        }

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                field[index(x, y, 0, width, depth)] = 0.0f;
                field[index(x, y, depth - 1, width, depth)] = 0.0f;
            }
            for (int z = 0; z < depth; z++) {
                field[index(0, y, z, width, depth)] = 0.0f;
                field[index(width - 1, y, z, width, depth)] = 0.0f;
            }
        }
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                field[index(x, 0, z, width, depth)] = 0.0f;
                field[index(x, height - 1, z, width, depth)] = 0.0f;
            }
        }
        return field;
    }

    private static void blurX(float[] input, float[] output, int width, int height, int depth) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int leftX = Math.max(0, x - 1);
                int rightX = Math.min(width - 1, x + 1);
                for (int z = 0; z < depth; z++) {
                    output[index(x, y, z, width, depth)] =
                            (input[index(leftX, y, z, width, depth)]
                                    + 2.0f * input[index(x, y, z, width, depth)]
                                    + input[index(rightX, y, z, width, depth)]) * 0.25f;
                }
            }
        }
    }

    private static void blurY(float[] input, float[] output, int width, int height, int depth) {
        for (int y = 0; y < height; y++) {
            int topY = Math.max(0, y - 1);
            int bottomY = Math.min(height - 1, y + 1);
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    output[index(x, y, z, width, depth)] =
                            (input[index(x, topY, z, width, depth)]
                                    + 2.0f * input[index(x, y, z, width, depth)]
                                    + input[index(x, bottomY, z, width, depth)]) * 0.25f;
                }
            }
        }
    }

    private static void blurZ(float[] input, float[] output, int width, int height, int depth) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    int backZ = Math.max(0, z - 1);
                    int frontZ = Math.min(depth - 1, z + 1);
                    output[index(x, y, z, width, depth)] =
                            (input[index(x, y, backZ, width, depth)]
                                    + 2.0f * input[index(x, y, z, width, depth)]
                                    + input[index(x, y, frontZ, width, depth)]) * 0.25f;
                }
            }
        }
    }

    private static GradientField createGradientField(float[] field, int width, int height, int depth) {
        int size = field.length;
        float[] gx = new float[size];
        float[] gy = new float[size];
        float[] gz = new float[size];

        for (int y = 0; y < height; y++) {
            int y0 = Math.max(0, y - 1);
            int y1 = Math.min(height - 1, y + 1);
            for (int x = 0; x < width; x++) {
                int x0 = Math.max(0, x - 1);
                int x1 = Math.min(width - 1, x + 1);
                for (int z = 0; z < depth; z++) {
                    int z0 = Math.max(0, z - 1);
                    int z1 = Math.min(depth - 1, z + 1);
                    int current = index(x, y, z, width, depth);
                    gx[current] = field[index(x1, y, z, width, depth)]
                            - field[index(x0, y, z, width, depth)];
                    gy[current] = field[index(x, y1, z, width, depth)]
                            - field[index(x, y0, z, width, depth)];
                    gz[current] = field[index(x, y, z1, width, depth)]
                            - field[index(x, y, z0, width, depth)];
                }
            }
        }
        return new GradientField(gx, gy, gz);
    }

    private static MeshPart polygonizeRange(
            float[] field,
            GradientField gradient,
            int width,
            int height,
            int depth,
            AtlasLayout atlas,
            int startY,
            int endY
    ) {
        MeshPart result = new MeshPart(32_768);
        float[] cubeValues = new float[8];
        int[] cubeIndices = new int[8];
        float[] pointX = new float[4];
        float[] pointY = new float[4];
        float[] pointZ = new float[4];
        float[] normalX = new float[4];
        float[] normalY = new float[4];
        float[] normalZ = new float[4];

        for (int y = startY; y < endY; y++) {
            for (int x = 0; x < width - 1; x++) {
                for (int z = 0; z < depth - 1; z++) {
                    float minimum = Float.POSITIVE_INFINITY;
                    float maximum = Float.NEGATIVE_INFINITY;
                    for (int corner = 0; corner < 8; corner++) {
                        int cx = x + CORNER_OFFSETS[corner][0];
                        int cy = y + CORNER_OFFSETS[corner][1];
                        int cz = z + CORNER_OFFSETS[corner][2];
                        int gridIndex = index(cx, cy, cz, width, depth);
                        cubeIndices[corner] = gridIndex;
                        float value = field[gridIndex];
                        cubeValues[corner] = value;
                        minimum = Math.min(minimum, value);
                        maximum = Math.max(maximum, value);
                    }
                    if (minimum >= ISO_LEVEL || maximum < ISO_LEVEL) {
                        continue;
                    }

                    for (int[] tetrahedron : TETRAHEDRA) {
                        int intersectionCount = 0;
                        for (int[] edge : TETRA_EDGES) {
                            int cornerA = tetrahedron[edge[0]];
                            int cornerB = tetrahedron[edge[1]];
                            float valueA = cubeValues[cornerA];
                            float valueB = cubeValues[cornerB];
                            if ((valueA >= ISO_LEVEL) == (valueB >= ISO_LEVEL)) {
                                continue;
                            }

                            float denominator = valueB - valueA;
                            float t = Math.abs(denominator) < 0.000001f
                                    ? 0.5f
                                    : (ISO_LEVEL - valueA) / denominator;
                            t = clamp01(t);

                            float ax = x + CORNER_OFFSETS[cornerA][0];
                            float ay = y + CORNER_OFFSETS[cornerA][1];
                            float az = z + CORNER_OFFSETS[cornerA][2];
                            float bx = x + CORNER_OFFSETS[cornerB][0];
                            float by = y + CORNER_OFFSETS[cornerB][1];
                            float bz = z + CORNER_OFFSETS[cornerB][2];
                            pointX[intersectionCount] = ax + (bx - ax) * t;
                            pointY[intersectionCount] = ay + (by - ay) * t;
                            pointZ[intersectionCount] = az + (bz - az) * t;

                            int indexA = cubeIndices[cornerA];
                            int indexB = cubeIndices[cornerB];
                            float gridGx = lerp(gradient.x[indexA], gradient.x[indexB], t);
                            float gridGy = lerp(gradient.y[indexA], gradient.y[indexB], t);
                            float gridGz = lerp(gradient.z[indexA], gradient.z[indexB], t);
                            float nx = -gridGx;
                            float ny = gridGy;
                            float nz = -gridGz;
                            float normalLength = length(nx, ny, nz);
                            if (normalLength < 0.00001f) {
                                nx = 0.0f;
                                ny = 0.0f;
                                nz = 1.0f;
                            } else {
                                nx /= normalLength;
                                ny /= normalLength;
                                nz /= normalLength;
                            }
                            normalX[intersectionCount] = nx;
                            normalY[intersectionCount] = ny;
                            normalZ[intersectionCount] = nz;
                            intersectionCount++;
                        }

                        if (intersectionCount == 3) {
                            emitTriangle(result, atlas, width, height, depth,
                                    pointX, pointY, pointZ, normalX, normalY, normalZ,
                                    0, 1, 2);
                        } else if (intersectionCount == 4) {
                            int[] ordered = orderQuad(pointX, pointY, pointZ,
                                    normalX, normalY, normalZ);
                            emitTriangle(result, atlas, width, height, depth,
                                    pointX, pointY, pointZ, normalX, normalY, normalZ,
                                    ordered[0], ordered[1], ordered[2]);
                            emitTriangle(result, atlas, width, height, depth,
                                    pointX, pointY, pointZ, normalX, normalY, normalZ,
                                    ordered[0], ordered[2], ordered[3]);
                        }
                    }
                }
            }
        }
        return result;
    }

    private static int[] orderQuad(
            float[] x,
            float[] y,
            float[] z,
            float[] nx,
            float[] ny,
            float[] nz
    ) {
        float centerX = 0.0f;
        float centerY = 0.0f;
        float centerZ = 0.0f;
        float averageNx = 0.0f;
        float averageNy = 0.0f;
        float averageNz = 0.0f;
        for (int i = 0; i < 4; i++) {
            centerX += x[i];
            centerY += y[i];
            centerZ += z[i];
            averageNx += nx[i];
            averageNy += ny[i];
            averageNz += nz[i];
        }
        centerX *= 0.25f;
        centerY *= 0.25f;
        centerZ *= 0.25f;
        float normalLength = length(averageNx, averageNy, averageNz);
        if (normalLength < 0.00001f) {
            averageNx = 0.0f;
            averageNy = 0.0f;
            averageNz = 1.0f;
        } else {
            averageNx /= normalLength;
            averageNy /= normalLength;
            averageNz /= normalLength;
        }

        float tangentX;
        float tangentY;
        float tangentZ;
        if (Math.abs(averageNy) < 0.90f) {
            tangentX = averageNz;
            tangentY = 0.0f;
            tangentZ = -averageNx;
        } else {
            tangentX = 1.0f;
            tangentY = 0.0f;
            tangentZ = 0.0f;
        }
        float tangentLength = length(tangentX, tangentY, tangentZ);
        tangentX /= tangentLength;
        tangentY /= tangentLength;
        tangentZ /= tangentLength;

        float bitangentX = averageNy * tangentZ - averageNz * tangentY;
        float bitangentY = averageNz * tangentX - averageNx * tangentZ;
        float bitangentZ = averageNx * tangentY - averageNy * tangentX;

        float[] angles = new float[4];
        int[] order = {0, 1, 2, 3};
        for (int i = 0; i < 4; i++) {
            float dx = x[i] - centerX;
            float dy = y[i] - centerY;
            float dz = z[i] - centerZ;
            float u = dx * tangentX + dy * tangentY + dz * tangentZ;
            float v = dx * bitangentX + dy * bitangentY + dz * bitangentZ;
            angles[i] = (float) Math.atan2(v, u);
        }
        for (int i = 1; i < 4; i++) {
            int value = order[i];
            float angle = angles[value];
            int j = i - 1;
            while (j >= 0 && angles[order[j]] > angle) {
                order[j + 1] = order[j];
                j--;
            }
            order[j + 1] = value;
        }
        return order;
    }

    private static void emitTriangle(
            MeshPart output,
            AtlasLayout atlas,
            int width,
            int height,
            int depth,
            float[] x,
            float[] y,
            float[] z,
            float[] nx,
            float[] ny,
            float[] nz,
            int first,
            int second,
            int third
    ) {
        float ax = modelX(x[first], width, height);
        float ay = modelY(y[first], height);
        float az = modelZ(z[first], depth, height);
        float bx = modelX(x[second], width, height);
        float by = modelY(y[second], height);
        float bz = modelZ(z[second], depth, height);
        float cx = modelX(x[third], width, height);
        float cy = modelY(y[third], height);
        float cz = modelZ(z[third], depth, height);

        float edge1X = bx - ax;
        float edge1Y = by - ay;
        float edge1Z = bz - az;
        float edge2X = cx - ax;
        float edge2Y = cy - ay;
        float edge2Z = cz - az;
        float faceX = edge1Y * edge2Z - edge1Z * edge2Y;
        float faceY = edge1Z * edge2X - edge1X * edge2Z;
        float faceZ = edge1X * edge2Y - edge1Y * edge2X;

        float averageNx = nx[first] + nx[second] + nx[third];
        float averageNy = ny[first] + ny[second] + ny[third];
        float averageNz = nz[first] + nz[second] + nz[third];
        if (faceX * averageNx + faceY * averageNy + faceZ * averageNz < 0.0f) {
            int swap = second;
            second = third;
            third = swap;
            bx = modelX(x[second], width, height);
            by = modelY(y[second], height);
            bz = modelZ(z[second], depth, height);
            cx = modelX(x[third], width, height);
            cy = modelY(y[third], height);
            cz = modelZ(z[third], depth, height);
        }

        averageNx = nx[first] + nx[second] + nx[third];
        averageNy = ny[first] + ny[second] + ny[third];
        averageNz = nz[first] + nz[second] + nz[third];
        int projection = chooseProjection(averageNx, averageNy, averageNz);

        addVertex(output, atlas, projection, width, height, depth,
                x[first], y[first], z[first], ax, ay, az,
                nx[first], ny[first], nz[first]);
        addVertex(output, atlas, projection, width, height, depth,
                x[second], y[second], z[second], bx, by, bz,
                nx[second], ny[second], nz[second]);
        addVertex(output, atlas, projection, width, height, depth,
                x[third], y[third], z[third], cx, cy, cz,
                nx[third], ny[third], nz[third]);
    }

    private static void addVertex(
            MeshPart output,
            AtlasLayout atlas,
            int projection,
            int width,
            int height,
            int depth,
            float gridX,
            float gridY,
            float gridZ,
            float modelX,
            float modelY,
            float modelZ,
            float nx,
            float ny,
            float nz
    ) {
        output.positions.add(modelX, modelY, modelZ);
        output.normals.add(nx, ny, nz);
        float xNorm = clamp01(gridX / Math.max(1.0f, width - 1.0f));
        float yNorm = clamp01(1.0f - gridY / Math.max(1.0f, height - 1.0f));
        float zNorm = clamp01(gridZ / Math.max(1.0f, depth - 1.0f));
        atlas.addUv(output.texCoords, projection, xNorm, yNorm, zNorm);
    }

    private static int chooseProjection(float nx, float ny, float nz) {
        float absoluteX = Math.abs(nx);
        float absoluteZ = Math.abs(nz);
        if (absoluteZ >= absoluteX * 0.82f) {
            return nz >= 0.0f ? AtlasLayout.FRONT : AtlasLayout.BACK;
        }
        return nx >= 0.0f ? AtlasLayout.RIGHT : AtlasLayout.LEFT;
    }

    private static MeshData merge(List<MeshPart> parts) {
        int positionCount = 0;
        int normalCount = 0;
        int texCoordCount = 0;
        for (MeshPart part : parts) {
            positionCount += part.positions.size();
            normalCount += part.normals.size();
            texCoordCount += part.texCoords.size();
        }
        float[] positions = new float[positionCount];
        float[] normals = new float[normalCount];
        float[] texCoords = new float[texCoordCount];
        int positionOffset = 0;
        int normalOffset = 0;
        int texCoordOffset = 0;
        for (MeshPart part : parts) {
            float[] partPositions = part.positions.toArray();
            float[] partNormals = part.normals.toArray();
            float[] partTexCoords = part.texCoords.toArray();
            System.arraycopy(partPositions, 0, positions, positionOffset, partPositions.length);
            System.arraycopy(partNormals, 0, normals, normalOffset, partNormals.length);
            System.arraycopy(partTexCoords, 0, texCoords, texCoordOffset, partTexCoords.length);
            positionOffset += partPositions.length;
            normalOffset += partNormals.length;
            texCoordOffset += partTexCoords.length;
        }
        int vertexCount = positions.length / 3;
        int[] indices = new int[vertexCount];
        for (int i = 0; i < vertexCount; i++) {
            indices[i] = i;
        }
        return new MeshData(positions, normals, texCoords, indices);
    }

    private static float modelX(float gridX, int width, int height) {
        float halfWidth = width / (float) height;
        return -halfWidth + 2.0f * halfWidth * gridX / Math.max(1.0f, width - 1.0f);
    }

    private static float modelY(float gridY, int height) {
        return 1.0f - 2.0f * gridY / Math.max(1.0f, height - 1.0f);
    }

    private static float modelZ(float gridZ, int depth, int height) {
        float halfDepth = depth / (float) height;
        return -halfDepth + 2.0f * halfDepth * gridZ / Math.max(1.0f, depth - 1.0f);
    }

    private static int index(int x, int y, int z, int width, int depth) {
        return (y * width + x) * depth + z;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float length(float x, float y, float z) {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    public static final class AtlasLayout {
        public static final int FRONT = 0;
        public static final int BACK = 1;
        public static final int RIGHT = 2;
        public static final int LEFT = 3;

        public final int atlasWidth;
        public final int atlasHeight;
        public final int gap;
        public final int frontWidth;
        public final int sideWidth;
        public final int frontStart;
        public final int backStart;
        public final int rightStart;
        public final int leftStart;

        private AtlasLayout(
                int atlasWidth,
                int atlasHeight,
                int gap,
                int frontWidth,
                int sideWidth,
                int frontStart,
                int backStart,
                int rightStart,
                int leftStart
        ) {
            this.atlasWidth = atlasWidth;
            this.atlasHeight = atlasHeight;
            this.gap = gap;
            this.frontWidth = frontWidth;
            this.sideWidth = sideWidth;
            this.frontStart = frontStart;
            this.backStart = backStart;
            this.rightStart = rightStart;
            this.leftStart = leftStart;
        }

        public static AtlasLayout create(int width, int height, int depth, int atlasHeight) {
            int gap = 8;
            int frontWidth = Math.max(128, Math.round(atlasHeight * width / (float) height));
            int sideWidth = Math.max(96, Math.round(atlasHeight * depth / (float) height));
            int frontStart = gap;
            int backStart = frontStart + frontWidth + gap;
            int rightStart = backStart + frontWidth + gap;
            int leftStart = rightStart + sideWidth + gap;
            int atlasWidth = leftStart + sideWidth + gap;
            return new AtlasLayout(atlasWidth, atlasHeight, gap,
                    frontWidth, sideWidth, frontStart, backStart, rightStart, leftStart);
        }

        void addUv(
                FloatBuilder builder,
                int projection,
                float xNorm,
                float yNorm,
                float zNorm
        ) {
            int start;
            int width;
            float localU;
            if (projection == FRONT) {
                start = frontStart;
                width = frontWidth;
                localU = xNorm;
            } else if (projection == BACK) {
                start = backStart;
                width = frontWidth;
                localU = 1.0f - xNorm;
            } else if (projection == RIGHT) {
                start = rightStart;
                width = sideWidth;
                localU = zNorm;
            } else {
                start = leftStart;
                width = sideWidth;
                localU = 1.0f - zNorm;
            }
            float padding = 2.0f;
            float u = (start + padding + clamp01(localU)
                    * Math.max(1.0f, width - padding * 2.0f)) / atlasWidth;
            float v = (padding + clamp01(yNorm)
                    * Math.max(1.0f, atlasHeight - padding * 2.0f)) / atlasHeight;
            builder.add(u, v);
        }
    }

    private static final class GradientField {
        final float[] x;
        final float[] y;
        final float[] z;

        GradientField(float[] x, float[] y, float[] z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private static final class MeshPart {
        final FloatBuilder positions;
        final FloatBuilder normals;
        final FloatBuilder texCoords;

        MeshPart(int initialVertexCapacity) {
            positions = new FloatBuilder(initialVertexCapacity * 3);
            normals = new FloatBuilder(initialVertexCapacity * 3);
            texCoords = new FloatBuilder(initialVertexCapacity * 2);
        }
    }

    static final class FloatBuilder {
        private float[] values;
        private int size;

        FloatBuilder(int initialCapacity) {
            values = new float[Math.max(16, initialCapacity)];
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

        int size() {
            return size;
        }

        float[] toArray() {
            return Arrays.copyOf(values, size);
        }

        private void ensure(int additional) {
            int required = size + additional;
            if (required > values.length) {
                values = Arrays.copyOf(values, Math.max(required, values.length * 2));
            }
        }
    }
}
