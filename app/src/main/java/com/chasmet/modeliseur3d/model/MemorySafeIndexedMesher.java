package com.chasmet.modeliseur3d.model;

import java.util.Arrays;

/**
 * V9.5.3 final : marching tetrahedra indexé et pré-dimensionné.
 *
 * <p>L'ancien mesher dupliquait trois sommets complets par triangle. Sur les
 * sujets composites (kart + pilote), le heap Java 512 Mo pouvait donc être
 * saturé avant même la texture. Cette implémentation conserve exactement la
 * même isosurface et la même projection UV, mais réutilise les intersections
 * d'arêtes qui partagent la même vue de texture.</p>
 *
 * <p>Deux passes sont utilisées : une pré-analyse compte exactement les
 * triangles et les sommets indexés, puis les tableaux finaux sont alloués à
 * leur taille exacte. Aucun doublement de gros tableau n'est nécessaire.
 * Si le maillage complet ne peut pas tenir avec une réserve de sécurité, une
 * grille virtuelle légèrement réduite est utilisée sans dupliquer le champ
 * de densité en mémoire.</p>
 */
public final class MemorySafeIndexedMesher {
    private static final float ISO = 0.50f;
    private static final int CACHE_ROWS = 8;
    private static final long MIN_RESERVE_BYTES = 96L * 1024L * 1024L;
    private static final float MIN_SCALE = 0.68f;

    private static final int[][] CORNER_OFFSETS = {
            {0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {0, 1, 0},
            {0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}
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
            {0, 1}, {1, 2}, {2, 0}, {0, 3}, {1, 3}, {2, 3}
    };

    private MemorySafeIndexedMesher() {
    }

    public static MeshData build(
            float[] density,
            int width,
            int height,
            int depth,
            SmoothHullMesher.AtlasLayout atlas,
            boolean[][] masks,
            SubjectCategory category
    ) {
        validate(density, width, height, depth, masks);
        Grid grid = new Grid(density, width, height, depth, 1.0f);
        Guide guide = new Guide(masks, width, height, depth, category);

        Preflight preflight = preflight(grid, guide);
        long headroom = javaHeadroom();
        long reserve = Math.max(MIN_RESERVE_BYTES, Runtime.getRuntime().maxMemory() / 6L);
        long budget = Math.max(64L * 1024L * 1024L, headroom - reserve);

        float scale = 1.0f;
        int attempts = 0;
        while (preflight.estimatedBytes() > budget && scale > MIN_SCALE && attempts < 3) {
            double ratio = budget / (double) Math.max(1L, preflight.estimatedBytes());
            float next = (float) (scale * Math.sqrt(Math.max(0.25, ratio)) * 0.96);
            next = Math.max(MIN_SCALE, Math.min(scale - 0.04f, next));
            scale = next;
            grid = new Grid(density, width, height, depth, scale);
            preflight = preflight(grid, guide);
            attempts++;
        }

        if (preflight.triangles <= 0L || preflight.vertices <= 0L) {
            throw new IllegalArgumentException("Aucune surface propre n'a pu être extraite");
        }
        if (preflight.triangles > Integer.MAX_VALUE / 3L
                || preflight.vertices > Integer.MAX_VALUE / 3L) {
            throw new IllegalStateException("Maillage trop grand pour Android");
        }
        if (preflight.estimatedBytes() > Math.max(budget, headroom - 32L * 1024L * 1024L)) {
            throw new OutOfMemoryError(
                    "budget meshing indexé dépassé : "
                            + preflight.triangles + " triangles, "
                            + preflight.vertices + " sommets"
            );
        }

        MemoryDiagnostics.mark(
                "MESHING INDEXE "
                        + grid.width + "x" + grid.height + "x" + grid.depth
                        + " • " + preflight.triangles + " triangles"
                        + (scale < 0.999f ? " • sécurité " + Math.round(scale * 100f) + "%" : " • pleine résolution")
        );

        MeshData mesh = buildExact(grid, guide, atlas, preflight);
        MemoryDiagnostics.mark(
                "MESHING INDEXE PRET • " + mesh.getVertexCount()
                        + " sommets • " + mesh.getTriangleCount() + " triangles"
        );
        return mesh;
    }

    private static void validate(
            float[] density,
            int width,
            int height,
            int depth,
            boolean[][] masks
    ) {
        long expected = (long) width * height * depth;
        if (density == null || expected != density.length) {
            throw new IllegalArgumentException("Champ 3D continu invalide");
        }
        if (width < 4 || height < 4 || depth < 4) {
            throw new IllegalArgumentException("Résolution 3D trop faible");
        }
        if (masks == null || masks.length != 4
                || masks[0] == null || masks[0].length != width * height
                || masks[2] == null || masks[2].length != width * height
                || masks[1] == null || masks[1].length != depth * height
                || masks[3] == null || masks[3].length != depth * height) {
            throw new IllegalArgumentException("Guide de texture multivue invalide");
        }
    }

    private static Preflight preflight(Grid grid, Guide guide) {
        CountingSink sink = new CountingSink();
        polygonize(grid, guide, sink);
        return new Preflight(sink.triangles, sink.vertices);
    }

    private static MeshData buildExact(
            Grid grid,
            Guide guide,
            SmoothHullMesher.AtlasLayout atlas,
            Preflight preflight
    ) {
        ExactSink sink = new ExactSink(
                Math.toIntExact(preflight.vertices),
                Math.toIntExact(preflight.triangles),
                atlas,
                grid
        );
        polygonize(grid, guide, sink);
        if (sink.vertexCursor != sink.vertexCount
                || sink.indexCursor != sink.indices.length) {
            throw new IllegalStateException(
                    "Pré-dimensionnement meshing incohérent : sommets "
                            + sink.vertexCursor + "/" + sink.vertexCount
                            + ", indices " + sink.indexCursor + "/" + sink.indices.length
            );
        }
        return new MeshData(sink.positions, sink.normals, sink.texCoords, sink.indices);
    }

    private static void polygonize(Grid grid, Guide guide, TriangleSink sink) {
        float[] cubeValues = new float[8];
        int[] cubeIndices = new int[8];
        float[] pointX = new float[4];
        float[] pointY = new float[4];
        float[] pointZ = new float[4];
        float[] normalX = new float[4];
        float[] normalY = new float[4];
        float[] normalZ = new float[4];
        long[] edgeKeys = new long[4];

        LongIntMap cache = new LongIntMap(1 << 17);
        for (int chunkStart = 0; chunkStart < grid.height - 1; chunkStart += CACHE_ROWS) {
            cache.clear();
            sink.beginChunk(cache);
            int chunkEnd = Math.min(grid.height - 1, chunkStart + CACHE_ROWS);
            for (int y = chunkStart; y < chunkEnd; y++) {
                for (int x = 0; x < grid.width - 1; x++) {
                    for (int z = 0; z < grid.depth - 1; z++) {
                        float minimum = Float.POSITIVE_INFINITY;
                        float maximum = Float.NEGATIVE_INFINITY;
                        for (int corner = 0; corner < 8; corner++) {
                            int cx = x + CORNER_OFFSETS[corner][0];
                            int cy = y + CORNER_OFFSETS[corner][1];
                            int cz = z + CORNER_OFFSETS[corner][2];
                            int gridIndex = grid.index(cx, cy, cz);
                            cubeIndices[corner] = gridIndex;
                            float value = grid.value(cx, cy, cz);
                            cubeValues[corner] = value;
                            minimum = Math.min(minimum, value);
                            maximum = Math.max(maximum, value);
                        }
                        if (minimum >= ISO || maximum < ISO) {
                            continue;
                        }

                        for (int[] tetrahedron : TETRAHEDRA) {
                            int intersections = 0;
                            for (int[] edge : TETRA_EDGES) {
                                int cornerA = tetrahedron[edge[0]];
                                int cornerB = tetrahedron[edge[1]];
                                float valueA = cubeValues[cornerA];
                                float valueB = cubeValues[cornerB];
                                if ((valueA >= ISO) == (valueB >= ISO)) {
                                    continue;
                                }

                                float denominator = valueB - valueA;
                                float t = Math.abs(denominator) < 0.000001f
                                        ? 0.5f
                                        : (ISO - valueA) / denominator;
                                t = clamp01(t);

                                int ax = x + CORNER_OFFSETS[cornerA][0];
                                int ay = y + CORNER_OFFSETS[cornerA][1];
                                int az = z + CORNER_OFFSETS[cornerA][2];
                                int bx = x + CORNER_OFFSETS[cornerB][0];
                                int by = y + CORNER_OFFSETS[cornerB][1];
                                int bz = z + CORNER_OFFSETS[cornerB][2];
                                pointX[intersections] = ax + (bx - ax) * t;
                                pointY[intersections] = ay + (by - ay) * t;
                                pointZ[intersections] = az + (bz - az) * t;

                                float gx = lerp(
                                        grid.gradientX(ax, ay, az),
                                        grid.gradientX(bx, by, bz),
                                        t
                                );
                                float gy = lerp(
                                        grid.gradientY(ax, ay, az),
                                        grid.gradientY(bx, by, bz),
                                        t
                                );
                                float gz = lerp(
                                        grid.gradientZ(ax, ay, az),
                                        grid.gradientZ(bx, by, bz),
                                        t
                                );
                                float nx = -gx;
                                float ny = gy;
                                float nz = -gz;
                                float length = length(nx, ny, nz);
                                if (length < 0.00001f) {
                                    nx = 0.0f;
                                    ny = 0.0f;
                                    nz = 1.0f;
                                } else {
                                    nx /= length;
                                    ny /= length;
                                    nz /= length;
                                }
                                normalX[intersections] = nx;
                                normalY[intersections] = ny;
                                normalZ[intersections] = nz;
                                edgeKeys[intersections] = packEdge(
                                        cubeIndices[cornerA],
                                        cubeIndices[cornerB]
                                );
                                intersections++;
                            }

                            if (intersections == 3) {
                                sink.triangle(
                                        grid, guide,
                                        pointX, pointY, pointZ,
                                        normalX, normalY, normalZ,
                                        edgeKeys,
                                        0, 1, 2
                                );
                            } else if (intersections == 4) {
                                int[] order = orderQuad(
                                        pointX, pointY, pointZ,
                                        normalX, normalY, normalZ
                                );
                                sink.triangle(
                                        grid, guide,
                                        pointX, pointY, pointZ,
                                        normalX, normalY, normalZ,
                                        edgeKeys,
                                        order[0], order[1], order[2]
                                );
                                sink.triangle(
                                        grid, guide,
                                        pointX, pointY, pointZ,
                                        normalX, normalY, normalZ,
                                        edgeKeys,
                                        order[0], order[2], order[3]
                                );
                            }
                        }
                    }
                }
            }
        }
    }

    private abstract static class TriangleSink {
        LongIntMap cache;

        void beginChunk(LongIntMap cache) {
            this.cache = cache;
        }

        final void triangle(
                Grid grid,
                Guide guide,
                float[] x,
                float[] y,
                float[] z,
                float[] nx,
                float[] ny,
                float[] nz,
                long[] edgeKeys,
                int first,
                int second,
                int third
        ) {
            float averageNx = nx[first] + nx[second] + nx[third];
            float averageNy = ny[first] + ny[second] + ny[third];
            float averageNz = nz[first] + nz[second] + nz[third];
            float centerX = (x[first] + x[second] + x[third]) / 3.0f;
            float centerY = (y[first] + y[second] + y[third]) / 3.0f;
            float centerZ = (z[first] + z[second] + z[third]) / 3.0f;
            int projection = chooseProjection(
                    averageNx, averageNy, averageNz,
                    centerX, centerY, centerZ,
                    grid.width, grid.height, grid.depth,
                    guide
            );

            float ax = modelX(x[first], grid.width, grid.height);
            float ay = modelY(y[first], grid.height);
            float az = modelZ(z[first], grid.depth, grid.height);
            float bx = modelX(x[second], grid.width, grid.height);
            float by = modelY(y[second], grid.height);
            float bz = modelZ(z[second], grid.depth, grid.height);
            float cx = modelX(x[third], grid.width, grid.height);
            float cy = modelY(y[third], grid.height);
            float cz = modelZ(z[third], grid.depth, grid.height);
            float edge1X = bx - ax;
            float edge1Y = by - ay;
            float edge1Z = bz - az;
            float edge2X = cx - ax;
            float edge2Y = cy - ay;
            float edge2Z = cz - az;
            float faceX = edge1Y * edge2Z - edge1Z * edge2Y;
            float faceY = edge1Z * edge2X - edge1X * edge2Z;
            float faceZ = edge1X * edge2Y - edge1Y * edge2X;
            if (faceX * averageNx + faceY * averageNy + faceZ * averageNz < 0.0f) {
                int swap = second;
                second = third;
                third = swap;
            }
            consumeVertex(grid, x, y, z, nx, ny, nz, edgeKeys, first, projection);
            consumeVertex(grid, x, y, z, nx, ny, nz, edgeKeys, second, projection);
            consumeVertex(grid, x, y, z, nx, ny, nz, edgeKeys, third, projection);
            endTriangle();
        }

        abstract void consumeVertex(
                Grid grid,
                float[] x,
                float[] y,
                float[] z,
                float[] nx,
                float[] ny,
                float[] nz,
                long[] edgeKeys,
                int point,
                int projection
        );

        abstract void endTriangle();
    }

    private static final class CountingSink extends TriangleSink {
        long vertices;
        long triangles;

        @Override
        void consumeVertex(
                Grid grid,
                float[] x,
                float[] y,
                float[] z,
                float[] nx,
                float[] ny,
                float[] nz,
                long[] edgeKeys,
                int point,
                int projection
        ) {
            long key = withProjection(edgeKeys[point], projection);
            if (cache.putIfAbsent(key, 0)) {
                vertices++;
            }
        }

        @Override
        void endTriangle() {
            triangles++;
        }
    }

    private static final class ExactSink extends TriangleSink {
        final int vertexCount;
        final float[] positions;
        final float[] normals;
        final float[] texCoords;
        final int[] indices;
        final SmoothHullMesher.AtlasLayout atlas;
        final Grid grid;
        int vertexCursor;
        int indexCursor;

        ExactSink(
                int vertexCount,
                int triangleCount,
                SmoothHullMesher.AtlasLayout atlas,
                Grid grid
        ) {
            this.vertexCount = vertexCount;
            this.positions = new float[Math.multiplyExact(vertexCount, 3)];
            this.normals = new float[Math.multiplyExact(vertexCount, 3)];
            this.texCoords = new float[Math.multiplyExact(vertexCount, 2)];
            this.indices = new int[Math.multiplyExact(triangleCount, 3)];
            this.atlas = atlas;
            this.grid = grid;
        }

        @Override
        void consumeVertex(
                Grid grid,
                float[] x,
                float[] y,
                float[] z,
                float[] nx,
                float[] ny,
                float[] nz,
                long[] edgeKeys,
                int point,
                int projection
        ) {
            long key = withProjection(edgeKeys[point], projection);
            int existing = cache.get(key);
            if (existing >= 0) {
                indices[indexCursor++] = existing;
                return;
            }
            int vertex = vertexCursor++;
            if (vertex >= vertexCount) {
                throw new IllegalStateException("Dépassement compteur sommets indexés");
            }
            cache.put(key, vertex);
            int p = vertex * 3;
            positions[p] = modelX(x[point], grid.width, grid.height);
            positions[p + 1] = modelY(y[point], grid.height);
            positions[p + 2] = modelZ(z[point], grid.depth, grid.height);
            normals[p] = nx[point];
            normals[p + 1] = ny[point];
            normals[p + 2] = nz[point];
            writeUv(vertex, projection, x[point], y[point], z[point]);
            indices[indexCursor++] = vertex;
        }

        @Override
        void endTriangle() {
        }

        private void writeUv(
                int vertex,
                int projection,
                float gridX,
                float gridY,
                float gridZ
        ) {
            float xNorm = clamp01(gridX / Math.max(1.0f, grid.width - 1.0f));
            float yNorm = clamp01(1.0f - gridY / Math.max(1.0f, grid.height - 1.0f));
            float zNorm = clamp01(gridZ / Math.max(1.0f, grid.depth - 1.0f));
            int start;
            int width;
            float localU;
            if (projection == SmoothHullMesher.AtlasLayout.FRONT) {
                start = atlas.frontStart;
                width = atlas.frontWidth;
                localU = xNorm;
            } else if (projection == SmoothHullMesher.AtlasLayout.BACK) {
                start = atlas.backStart;
                width = atlas.frontWidth;
                localU = 1.0f - xNorm;
            } else if (projection == SmoothHullMesher.AtlasLayout.RIGHT) {
                start = atlas.rightStart;
                width = atlas.sideWidth;
                localU = zNorm;
            } else {
                start = atlas.leftStart;
                width = atlas.sideWidth;
                localU = 1.0f - zNorm;
            }
            float padding = 2.0f;
            texCoords[vertex * 2] = (start + padding + clamp01(localU)
                    * Math.max(1.0f, width - padding * 2.0f)) / atlas.atlasWidth;
            texCoords[vertex * 2 + 1] = (padding + clamp01(yNorm)
                    * Math.max(1.0f, atlas.atlasHeight - padding * 2.0f)) / atlas.atlasHeight;
        }
    }

    private static final class Preflight {
        final long triangles;
        final long vertices;

        Preflight(long triangles, long vertices) {
            this.triangles = triangles;
            this.vertices = vertices;
        }

        long estimatedBytes() {
            return Math.addExact(
                    Math.multiplyExact(vertices, 32L),
                    Math.multiplyExact(triangles, 12L)
            );
        }
    }

    private static final class Grid {
        final float[] source;
        final int sourceWidth;
        final int sourceHeight;
        final int sourceDepth;
        final int width;
        final int height;
        final int depth;
        final boolean direct;

        Grid(float[] source, int width, int height, int depth, float scale) {
            this.source = source;
            this.sourceWidth = width;
            this.sourceHeight = height;
            this.sourceDepth = depth;
            if (scale >= 0.999f) {
                this.width = width;
                this.height = height;
                this.depth = depth;
                this.direct = true;
            } else {
                this.width = Math.max(4, Math.round((width - 1) * scale) + 1);
                this.height = Math.max(4, Math.round((height - 1) * scale) + 1);
                this.depth = Math.max(4, Math.round((depth - 1) * scale) + 1);
                this.direct = false;
            }
            long size = (long) this.width * this.height * this.depth;
            if (size >= Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Grille de meshing trop grande");
            }
        }

        int index(int x, int y, int z) {
            return (y * width + x) * depth + z;
        }

        float value(int x, int y, int z) {
            if (direct) {
                return source[(y * sourceWidth + x) * sourceDepth + z];
            }
            float sx = x * (sourceWidth - 1.0f) / Math.max(1.0f, width - 1.0f);
            float sy = y * (sourceHeight - 1.0f) / Math.max(1.0f, height - 1.0f);
            float sz = z * (sourceDepth - 1.0f) / Math.max(1.0f, depth - 1.0f);
            int x0 = Math.max(0, Math.min(sourceWidth - 1, (int) Math.floor(sx)));
            int y0 = Math.max(0, Math.min(sourceHeight - 1, (int) Math.floor(sy)));
            int z0 = Math.max(0, Math.min(sourceDepth - 1, (int) Math.floor(sz)));
            int x1 = Math.min(sourceWidth - 1, x0 + 1);
            int y1 = Math.min(sourceHeight - 1, y0 + 1);
            int z1 = Math.min(sourceDepth - 1, z0 + 1);
            float tx = sx - x0;
            float ty = sy - y0;
            float tz = sz - z0;
            float c000 = source[(y0 * sourceWidth + x0) * sourceDepth + z0];
            float c001 = source[(y0 * sourceWidth + x0) * sourceDepth + z1];
            float c010 = source[(y1 * sourceWidth + x0) * sourceDepth + z0];
            float c011 = source[(y1 * sourceWidth + x0) * sourceDepth + z1];
            float c100 = source[(y0 * sourceWidth + x1) * sourceDepth + z0];
            float c101 = source[(y0 * sourceWidth + x1) * sourceDepth + z1];
            float c110 = source[(y1 * sourceWidth + x1) * sourceDepth + z0];
            float c111 = source[(y1 * sourceWidth + x1) * sourceDepth + z1];
            float c00 = lerp(c000, c100, tx);
            float c01 = lerp(c001, c101, tx);
            float c10 = lerp(c010, c110, tx);
            float c11 = lerp(c011, c111, tx);
            float c0 = lerp(c00, c10, ty);
            float c1 = lerp(c01, c11, ty);
            return lerp(c0, c1, tz);
        }

        float gradientX(int x, int y, int z) {
            return value(Math.min(width - 1, x + 1), y, z)
                    - value(Math.max(0, x - 1), y, z);
        }

        float gradientY(int x, int y, int z) {
            return value(x, Math.min(height - 1, y + 1), z)
                    - value(x, Math.max(0, y - 1), z);
        }

        float gradientZ(int x, int y, int z) {
            return value(x, y, Math.min(depth - 1, z + 1))
                    - value(x, y, Math.max(0, z - 1));
        }
    }

    private static final class Guide {
        final boolean[][] masks;
        final int sourceWidth;
        final int sourceHeight;
        final int sourceDepth;
        final float edgeWeight;

        Guide(boolean[][] masks, int width, int height, int depth, SubjectCategory category) {
            this.masks = masks;
            this.sourceWidth = width;
            this.sourceHeight = height;
            this.sourceDepth = depth;
            this.edgeWeight = category == SubjectCategory.COMPOSITE_VEHICLE ? 0.72f : 0.58f;
        }

        float support(int projection, float gx, float gy, float gz, int width, int height, int depth) {
            float nx = gx / Math.max(1.0f, width - 1.0f);
            float ny = gy / Math.max(1.0f, height - 1.0f);
            float nz = gz / Math.max(1.0f, depth - 1.0f);
            int x = Math.round(nx * (sourceWidth - 1));
            int y = Math.round(ny * (sourceHeight - 1));
            int z = Math.round(nz * (sourceDepth - 1));
            if (!inside(projection, x, y, z)) {
                return 0.0f;
            }
            int neighbours = 0;
            for (int radius = 1; radius <= 2; radius++) {
                neighbours += inside(projection, x - radius, y, z) ? 1 : 0;
                neighbours += inside(projection, x + radius, y, z) ? 1 : 0;
                neighbours += inside(projection, x, y - radius, z) ? 1 : 0;
                neighbours += inside(projection, x, y + radius, z) ? 1 : 0;
            }
            float interior = neighbours / 8.0f;
            return edgeWeight + (1.0f - edgeWeight) * interior;
        }

        private boolean inside(int projection, int x, int y, int z) {
            if (y < 0 || y >= sourceHeight) {
                return false;
            }
            if (projection == SmoothHullMesher.AtlasLayout.FRONT) {
                return x >= 0 && x < sourceWidth && masks[0][y * sourceWidth + x];
            }
            if (projection == SmoothHullMesher.AtlasLayout.BACK) {
                int mirroredX = sourceWidth - 1 - x;
                return mirroredX >= 0 && mirroredX < sourceWidth
                        && masks[2][y * sourceWidth + mirroredX];
            }
            if (projection == SmoothHullMesher.AtlasLayout.RIGHT) {
                return z >= 0 && z < sourceDepth && masks[1][y * sourceDepth + z];
            }
            int mirroredZ = sourceDepth - 1 - z;
            return mirroredZ >= 0 && mirroredZ < sourceDepth
                    && masks[3][y * sourceDepth + mirroredZ];
        }
    }

    private static int chooseProjection(
            float nx,
            float ny,
            float nz,
            float gridX,
            float gridY,
            float gridZ,
            int width,
            int height,
            int depth,
            Guide guide
    ) {
        float absoluteX = Math.abs(nx);
        float absoluteY = Math.abs(ny);
        float absoluteZ = Math.abs(nz);
        float[] alignment = {
                Math.max(0.0f, nz),
                Math.max(0.0f, -nz),
                Math.max(0.0f, nx),
                Math.max(0.0f, -nx)
        };
        if (absoluteY > Math.max(absoluteX, absoluteZ) * 1.18f) {
            float radialX = gridX / Math.max(1.0f, width - 1.0f) * 2.0f - 1.0f;
            float radialZ = gridZ / Math.max(1.0f, depth - 1.0f) * 2.0f - 1.0f;
            alignment[SmoothHullMesher.AtlasLayout.FRONT] = Math.max(0.0f, radialZ);
            alignment[SmoothHullMesher.AtlasLayout.BACK] = Math.max(0.0f, -radialZ);
            alignment[SmoothHullMesher.AtlasLayout.RIGHT] = Math.max(0.0f, radialX);
            alignment[SmoothHullMesher.AtlasLayout.LEFT] = Math.max(0.0f, -radialX);
        }
        int best = SmoothHullMesher.AtlasLayout.FRONT;
        float bestScore = Float.NEGATIVE_INFINITY;
        for (int projection = 0; projection < 4; projection++) {
            float support = guide.support(projection, gridX, gridY, gridZ, width, height, depth);
            float score = (0.10f + alignment[projection]) * (0.08f + 0.92f * support);
            if (score > bestScore) {
                bestScore = score;
                best = projection;
            }
        }
        return best;
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

    private static long packEdge(int first, int second) {
        int low = Math.min(first, second);
        int high = Math.max(first, second);
        return ((long) low << 31) | (high & 0x7fffffffL);
    }

    private static long withProjection(long edge, int projection) {
        long low = edge & 0x7fffffffL;
        long high = edge >>> 31;
        return (high << 33) | (low << 2) | (projection & 3L);
    }

    private static long javaHeadroom() {
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        return Math.max(0L, runtime.maxMemory() - used);
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

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float length(float x, float y, float z) {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private static final class LongIntMap {
        private long[] keys;
        private int[] values;
        private int mask;
        private int size;

        LongIntMap(int initialCapacity) {
            int capacity = 1;
            while (capacity < initialCapacity) {
                capacity <<= 1;
            }
            keys = new long[capacity];
            values = new int[capacity];
            Arrays.fill(values, -1);
            mask = capacity - 1;
        }

        int get(long key) {
            int slot = mix(key) & mask;
            while (true) {
                int value = values[slot];
                if (value < 0) {
                    return -1;
                }
                if (keys[slot] == key) {
                    return value;
                }
                slot = (slot + 1) & mask;
            }
        }

        boolean putIfAbsent(long key, int value) {
            ensureCapacity();
            int slot = mix(key) & mask;
            while (true) {
                if (values[slot] < 0) {
                    keys[slot] = key;
                    values[slot] = value;
                    size++;
                    return true;
                }
                if (keys[slot] == key) {
                    return false;
                }
                slot = (slot + 1) & mask;
            }
        }

        void put(long key, int value) {
            if (!putIfAbsent(key, value)) {
                int slot = mix(key) & mask;
                while (keys[slot] != key) {
                    slot = (slot + 1) & mask;
                }
                values[slot] = value;
            }
        }

        void clear() {
            Arrays.fill(values, -1);
            size = 0;
        }

        private void ensureCapacity() {
            if ((size + 1) * 10 < keys.length * 6) {
                return;
            }
            long[] oldKeys = keys;
            int[] oldValues = values;
            int capacity = keys.length << 1;
            keys = new long[capacity];
            values = new int[capacity];
            Arrays.fill(values, -1);
            mask = capacity - 1;
            size = 0;
            for (int i = 0; i < oldValues.length; i++) {
                if (oldValues[i] >= 0) {
                    putIfAbsent(oldKeys[i], oldValues[i]);
                }
            }
        }

        private static int mix(long value) {
            value ^= value >>> 33;
            value *= 0xff51afd7ed558ccdl;
            value ^= value >>> 33;
            value *= 0xc4ceb9fe1a85ec53l;
            value ^= value >>> 33;
            return (int) value;
        }
    }
}
