package com.chasmet.modeliseur3d.model;

import java.util.Arrays;

/**
 * Maillage 2.5D V5.5 sous forme de coque.
 *
 * Contrairement au maillage historique par tranche gauche/droite, cette
 * version échantillonne le masque 2D complet. Les trous, espaces entre les
 * bras, roues, accessoires et composantes séparées restent donc ouverts.
 * La profondeur est calculée localement depuis la distance au bord puis les
 * bords sont reliés par un biseau avant/milieu/arrière utilisant directement
 * les couleurs locales des textures face et dos.
 */
public final class FaceBack25DShellMesher {
    private static final float EPSILON = 1.0e-6f;
    private static final int INF = 1_000_000;

    private FaceBack25DShellMesher() {
    }

    public static BuildResult build(
            boolean[] frontMask,
            boolean[] backMask,
            int maskWidth,
            int maskHeight,
            float aspectScale,
            float halfDepth,
            int rows,
            int columns,
            FaceBack25DMesher.AtlasLayout atlas
    ) {
        validate(
                frontMask,
                backMask,
                maskWidth,
                maskHeight,
                aspectScale,
                halfDepth,
                rows,
                columns,
                atlas
        );

        Bounds bounds = findBounds(frontMask, backMask, maskWidth, maskHeight);
        int[] integral = buildUnionIntegral(
                frontMask,
                backMask,
                maskWidth,
                maskHeight
        );

        boolean[] active = new boolean[rows * columns];
        float[] coverage = new float[active.length];
        int activeCount = rasterizeCells(
                integral,
                maskWidth,
                maskHeight,
                bounds,
                rows,
                columns,
                active,
                coverage
        );
        activeCount = stabilizeCells(active, coverage, rows, columns, activeCount);
        if (activeCount < 4) {
            throw new IllegalArgumentException("Silhouette 2.5D trop petite");
        }

        int[] distances = distanceFromBoundary(active, rows, columns);
        float[] cellDepth = buildCellDepth(
                active,
                distances,
                rows,
                columns,
                halfDepth
        );
        float[] nodeDepth = buildNodeDepth(
                active,
                cellDepth,
                rows,
                columns,
                halfDepth
        );

        int boundaryEdges = countBoundaryEdges(active, rows, columns);
        int nodeColumns = columns + 1;
        int nodeRows = rows + 1;
        int nodeCount = nodeRows * nodeColumns;
        int surfaceVertices = nodeCount * 2;
        int sideVertices = boundaryEdges * 8;
        int vertexCount = surfaceVertices + sideVertices;
        int indexCount = activeCount * 12 + boundaryEdges * 12;

        float[] positions = new float[vertexCount * 3];
        float[] normals = new float[vertexCount * 3];
        float[] texCoords = new float[vertexCount * 2];
        int[] indices = new int[indexCount];
        float[] sourceU = new float[nodeCount];
        float[] sourceV = new float[nodeCount];

        float topV = bounds.top / (float) Math.max(1, maskHeight - 1);
        float bottomV = bounds.bottom / (float) Math.max(1, maskHeight - 1);
        float verticalSpan = Math.max(0.04f, bottomV - topV);

        for (int row = 0; row <= rows; row++) {
            float rowAmount = row / (float) Math.max(1, rows);
            float pixelY = lerp(bounds.top, bounds.bottom, rowAmount);
            float localV = clamp(
                    pixelY / Math.max(1.0f, maskHeight - 1.0f),
                    0.0f,
                    1.0f
            );
            float y = 1.0f
                    - ((localV - topV) / verticalSpan) * 2.0f;

            for (int column = 0; column <= columns; column++) {
                float columnAmount = column / (float) Math.max(1, columns);
                float pixelX = lerp(bounds.left, bounds.right, columnAmount);
                float localU = clamp(
                        pixelX / Math.max(1.0f, maskWidth - 1.0f),
                        0.0f,
                        1.0f
                );
                float x = (localU * 2.0f - 1.0f) * aspectScale;
                int node = row * nodeColumns + column;
                float depth = nodeDepth[node];

                sourceU[node] = localU;
                sourceV[node] = localV;
                putPosition(positions, node, x, y, depth);
                putPosition(positions, nodeCount + node, x, y, -depth * 0.98f);
                atlas.put(texCoords, node,
                        FaceBack25DMesher.AtlasLayout.FRONT,
                        localU,
                        localV);
                atlas.put(texCoords, nodeCount + node,
                        FaceBack25DMesher.AtlasLayout.BACK,
                        1.0f - localU,
                        localV);
            }
        }

        int cursor = 0;
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                if (!active[row * columns + column]) {
                    continue;
                }
                int topLeft = row * nodeColumns + column;
                int topRight = topLeft + 1;
                int bottomLeft = (row + 1) * nodeColumns + column;
                int bottomRight = bottomLeft + 1;

                cursor = putQuad(
                        indices,
                        cursor,
                        topLeft,
                        bottomLeft,
                        bottomRight,
                        topRight,
                        false
                );
                cursor = putQuad(
                        indices,
                        cursor,
                        nodeCount + topLeft,
                        nodeCount + bottomLeft,
                        nodeCount + bottomRight,
                        nodeCount + topRight,
                        true
                );
            }
        }

        int sideVertexCursor = surfaceVertices;
        float cellWorldX = estimateCellWorldX(
                bounds,
                maskWidth,
                aspectScale,
                columns
        );
        float cellWorldY = 2.0f / Math.max(1, rows);
        float bevel = Math.min(cellWorldX, cellWorldY) * 0.22f;

        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                if (!active[row * columns + column]) {
                    continue;
                }
                int topLeft = row * nodeColumns + column;
                int topRight = topLeft + 1;
                int bottomLeft = (row + 1) * nodeColumns + column;
                int bottomRight = bottomLeft + 1;

                if (!isActive(active, rows, columns, row - 1, column)) {
                    SideWrite write = writeSide(
                            positions,
                            texCoords,
                            indices,
                            cursor,
                            sideVertexCursor,
                            topLeft,
                            topRight,
                            nodeCount,
                            sourceU,
                            sourceV,
                            0.0f,
                            bevel,
                            atlas
                    );
                    cursor = write.indexCursor;
                    sideVertexCursor = write.vertexCursor;
                }
                if (!isActive(active, rows, columns, row, column + 1)) {
                    SideWrite write = writeSide(
                            positions,
                            texCoords,
                            indices,
                            cursor,
                            sideVertexCursor,
                            topRight,
                            bottomRight,
                            nodeCount,
                            sourceU,
                            sourceV,
                            bevel,
                            0.0f,
                            atlas
                    );
                    cursor = write.indexCursor;
                    sideVertexCursor = write.vertexCursor;
                }
                if (!isActive(active, rows, columns, row + 1, column)) {
                    SideWrite write = writeSide(
                            positions,
                            texCoords,
                            indices,
                            cursor,
                            sideVertexCursor,
                            bottomRight,
                            bottomLeft,
                            nodeCount,
                            sourceU,
                            sourceV,
                            0.0f,
                            -bevel,
                            atlas
                    );
                    cursor = write.indexCursor;
                    sideVertexCursor = write.vertexCursor;
                }
                if (!isActive(active, rows, columns, row, column - 1)) {
                    SideWrite write = writeSide(
                            positions,
                            texCoords,
                            indices,
                            cursor,
                            sideVertexCursor,
                            bottomLeft,
                            topLeft,
                            nodeCount,
                            sourceU,
                            sourceV,
                            -bevel,
                            0.0f,
                            atlas
                    );
                    cursor = write.indexCursor;
                    sideVertexCursor = write.vertexCursor;
                }
            }
        }

        if (cursor != indices.length || sideVertexCursor != vertexCount) {
            throw new IllegalStateException(
                    "Coque 2.5D incohérente : indices="
                            + cursor + "/" + indices.length
                            + " sommets=" + sideVertexCursor + "/" + vertexCount
            );
        }

        computeNormals(positions, indices, normals);
        return new BuildResult(
                new MeshData(positions, normals, texCoords, indices),
                rows,
                columns,
                halfDepth,
                activeCount,
                boundaryEdges
        );
    }

    private static SideWrite writeSide(
            float[] positions,
            float[] texCoords,
            int[] indices,
            int indexCursor,
            int vertexCursor,
            int nodeA,
            int nodeB,
            int nodeCount,
            float[] sourceU,
            float[] sourceV,
            float shiftX,
            float shiftY,
            FaceBack25DMesher.AtlasLayout atlas
    ) {
        int frontA = nodeA;
        int frontB = nodeB;
        int backA = nodeCount + nodeA;
        int backB = nodeCount + nodeB;

        int fa = vertexCursor;
        int fb = vertexCursor + 1;
        int fmB = vertexCursor + 2;
        int fmA = vertexCursor + 3;
        int bmA = vertexCursor + 4;
        int bmB = vertexCursor + 5;
        int bb = vertexCursor + 6;
        int ba = vertexCursor + 7;

        copyPosition(positions, frontA, fa);
        copyPosition(positions, frontB, fb);
        putMidPosition(positions, frontB, backB, fmB, shiftX, shiftY);
        putMidPosition(positions, frontA, backA, fmA, shiftX, shiftY);
        copyPosition(positions, fmA, bmA);
        copyPosition(positions, fmB, bmB);
        copyPosition(positions, backB, bb);
        copyPosition(positions, backA, ba);

        putFrontUv(texCoords, atlas, fa, nodeA, sourceU, sourceV);
        putFrontUv(texCoords, atlas, fb, nodeB, sourceU, sourceV);
        putFrontUv(texCoords, atlas, fmB, nodeB, sourceU, sourceV);
        putFrontUv(texCoords, atlas, fmA, nodeA, sourceU, sourceV);

        putBackUv(texCoords, atlas, bmA, nodeA, sourceU, sourceV);
        putBackUv(texCoords, atlas, bmB, nodeB, sourceU, sourceV);
        putBackUv(texCoords, atlas, bb, nodeB, sourceU, sourceV);
        putBackUv(texCoords, atlas, ba, nodeA, sourceU, sourceV);

        indexCursor = putQuad(indices, indexCursor, fa, fb, fmB, fmA, false);
        indexCursor = putQuad(indices, indexCursor, bmA, bmB, bb, ba, false);
        return new SideWrite(indexCursor, vertexCursor + 8);
    }

    private static void putFrontUv(
            float[] texCoords,
            FaceBack25DMesher.AtlasLayout atlas,
            int vertex,
            int node,
            float[] sourceU,
            float[] sourceV
    ) {
        atlas.put(
                texCoords,
                vertex,
                FaceBack25DMesher.AtlasLayout.FRONT,
                sourceU[node],
                sourceV[node]
        );
    }

    private static void putBackUv(
            float[] texCoords,
            FaceBack25DMesher.AtlasLayout atlas,
            int vertex,
            int node,
            float[] sourceU,
            float[] sourceV
    ) {
        atlas.put(
                texCoords,
                vertex,
                FaceBack25DMesher.AtlasLayout.BACK,
                1.0f - sourceU[node],
                sourceV[node]
        );
    }

    private static int rasterizeCells(
            int[] integral,
            int width,
            int height,
            Bounds bounds,
            int rows,
            int columns,
            boolean[] active,
            float[] coverage
    ) {
        int activeCount = 0;
        for (int row = 0; row < rows; row++) {
            int y0 = cellStart(bounds.top, bounds.bottom, row, rows, height);
            int y1 = cellEnd(bounds.top, bounds.bottom, row, rows, height);
            for (int column = 0; column < columns; column++) {
                int x0 = cellStart(bounds.left, bounds.right, column, columns, width);
                int x1 = cellEnd(bounds.left, bounds.right, column, columns, width);
                int area = Math.max(1, (x1 - x0) * (y1 - y0));
                int count = sumIntegral(integral, width, x0, y0, x1, y1);
                float ratio = count / (float) area;
                int index = row * columns + column;
                coverage[index] = ratio;
                boolean on = count > 0 && ratio >= 0.055f;
                active[index] = on;
                if (on) {
                    activeCount++;
                }
            }
        }
        return activeCount;
    }

    private static int stabilizeCells(
            boolean[] active,
            float[] coverage,
            int rows,
            int columns,
            int activeCount
    ) {
        boolean[] source = Arrays.copyOf(active, active.length);
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                int index = row * columns + column;
                int neighbors = countNeighbors(source, rows, columns, row, column);
                if (source[index]) {
                    if (neighbors == 0 && coverage[index] < 0.36f) {
                        active[index] = false;
                        activeCount--;
                    }
                } else if (neighbors >= 7) {
                    active[index] = true;
                    activeCount++;
                }
            }
        }
        return activeCount;
    }

    private static int countNeighbors(
            boolean[] active,
            int rows,
            int columns,
            int centerRow,
            int centerColumn
    ) {
        int count = 0;
        for (int dr = -1; dr <= 1; dr++) {
            for (int dc = -1; dc <= 1; dc++) {
                if (dr == 0 && dc == 0) {
                    continue;
                }
                if (isActive(
                        active,
                        rows,
                        columns,
                        centerRow + dr,
                        centerColumn + dc
                )) {
                    count++;
                }
            }
        }
        return count;
    }

    private static int[] distanceFromBoundary(
            boolean[] active,
            int rows,
            int columns
    ) {
        int[] distance = new int[active.length];
        Arrays.fill(distance, INF);
        int[] queue = new int[active.length];
        int head = 0;
        int tail = 0;

        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                int index = row * columns + column;
                if (!active[index]) {
                    distance[index] = -1;
                    continue;
                }
                if (!isActive(active, rows, columns, row - 1, column)
                        || !isActive(active, rows, columns, row + 1, column)
                        || !isActive(active, rows, columns, row, column - 1)
                        || !isActive(active, rows, columns, row, column + 1)) {
                    distance[index] = 0;
                    queue[tail++] = index;
                }
            }
        }

        while (head < tail) {
            int index = queue[head++];
            int row = index / columns;
            int column = index - row * columns;
            int nextDistance = distance[index] + 1;
            if (relaxDistance(active, distance, queue, tail, rows, columns,
                    row - 1, column, nextDistance)) {
                tail++;
            }
            if (relaxDistance(active, distance, queue, tail, rows, columns,
                    row + 1, column, nextDistance)) {
                tail++;
            }
            if (relaxDistance(active, distance, queue, tail, rows, columns,
                    row, column - 1, nextDistance)) {
                tail++;
            }
            if (relaxDistance(active, distance, queue, tail, rows, columns,
                    row, column + 1, nextDistance)) {
                tail++;
            }
        }
        return distance;
    }

    private static boolean relaxDistance(
            boolean[] active,
            int[] distance,
            int[] queue,
            int queueIndex,
            int rows,
            int columns,
            int row,
            int column,
            int candidate
    ) {
        if (row < 0 || row >= rows || column < 0 || column >= columns) {
            return false;
        }
        int index = row * columns + column;
        if (!active[index] || candidate >= distance[index]) {
            return false;
        }
        distance[index] = candidate;
        queue[queueIndex] = index;
        return true;
    }

    private static float[] buildCellDepth(
            boolean[] active,
            int[] distance,
            int rows,
            int columns,
            float halfDepth
    ) {
        float[] depth = new float[active.length];
        int maximumRowWidth = 1;
        int[] rowWidths = new int[rows];
        for (int row = 0; row < rows; row++) {
            int first = columns;
            int last = -1;
            for (int column = 0; column < columns; column++) {
                if (active[row * columns + column]) {
                    first = Math.min(first, column);
                    last = Math.max(last, column);
                }
            }
            if (last >= first) {
                rowWidths[row] = last - first + 1;
                maximumRowWidth = Math.max(maximumRowWidth, rowWidths[row]);
            }
        }

        for (int row = 0; row < rows; row++) {
            float widthRatio = rowWidths[row] / (float) maximumRowWidth;
            float rowFactor = 0.78f
                    + 0.22f * (float) Math.sqrt(clamp(widthRatio, 0.0f, 1.0f));
            for (int column = 0; column < columns; column++) {
                int index = row * columns + column;
                if (!active[index]) {
                    continue;
                }
                float distanceRatio = clamp(distance[index] / 5.0f, 0.0f, 1.0f);
                float contour = 0.24f
                        + 0.76f * (float) Math.sqrt(distanceRatio);
                depth[index] = halfDepth * rowFactor * contour;
            }
        }
        return depth;
    }

    private static float[] buildNodeDepth(
            boolean[] active,
            float[] cellDepth,
            int rows,
            int columns,
            float halfDepth
    ) {
        int nodeColumns = columns + 1;
        float[] result = new float[(rows + 1) * nodeColumns];
        for (int row = 0; row <= rows; row++) {
            for (int column = 0; column <= columns; column++) {
                float total = 0.0f;
                int count = 0;
                for (int dr = -1; dr <= 0; dr++) {
                    for (int dc = -1; dc <= 0; dc++) {
                        int cellRow = row + dr;
                        int cellColumn = column + dc;
                        if (cellRow < 0 || cellRow >= rows
                                || cellColumn < 0 || cellColumn >= columns) {
                            continue;
                        }
                        int index = cellRow * columns + cellColumn;
                        if (active[index]) {
                            total += cellDepth[index];
                            count++;
                        }
                    }
                }
                result[row * nodeColumns + column] = count == 0
                        ? halfDepth * 0.20f
                        : Math.max(halfDepth * 0.20f, total / count);
            }
        }
        return result;
    }

    private static int countBoundaryEdges(
            boolean[] active,
            int rows,
            int columns
    ) {
        int count = 0;
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                if (!active[row * columns + column]) {
                    continue;
                }
                if (!isActive(active, rows, columns, row - 1, column)) count++;
                if (!isActive(active, rows, columns, row, column + 1)) count++;
                if (!isActive(active, rows, columns, row + 1, column)) count++;
                if (!isActive(active, rows, columns, row, column - 1)) count++;
            }
        }
        return count;
    }

    private static boolean isActive(
            boolean[] active,
            int rows,
            int columns,
            int row,
            int column
    ) {
        return row >= 0 && row < rows
                && column >= 0 && column < columns
                && active[row * columns + column];
    }

    private static Bounds findBounds(
            boolean[] front,
            boolean[] back,
            int width,
            int height
    ) {
        int left = width;
        int top = height;
        int right = -1;
        int bottom = -1;
        for (int y = 0; y < height; y++) {
            int offset = y * width;
            for (int x = 0; x < width; x++) {
                int index = offset + x;
                if (!front[index] && !back[index]) {
                    continue;
                }
                left = Math.min(left, x);
                top = Math.min(top, y);
                right = Math.max(right, x);
                bottom = Math.max(bottom, y);
            }
        }
        if (right < left || bottom < top) {
            throw new IllegalArgumentException("Masques Face/Dos vides");
        }
        int marginX = Math.max(1, (right - left + 1) / 180);
        int marginY = Math.max(1, (bottom - top + 1) / 220);
        return new Bounds(
                Math.max(0, left - marginX),
                Math.max(0, top - marginY),
                Math.min(width - 1, right + marginX),
                Math.min(height - 1, bottom + marginY)
        );
    }

    private static int[] buildUnionIntegral(
            boolean[] front,
            boolean[] back,
            int width,
            int height
    ) {
        int stride = width + 1;
        int[] integral = new int[(height + 1) * stride];
        for (int y = 0; y < height; y++) {
            int rowSum = 0;
            int sourceOffset = y * width;
            int targetOffset = (y + 1) * stride;
            int previousOffset = y * stride;
            for (int x = 0; x < width; x++) {
                int index = sourceOffset + x;
                if (front[index] || back[index]) {
                    rowSum++;
                }
                integral[targetOffset + x + 1] =
                        integral[previousOffset + x + 1] + rowSum;
            }
        }
        return integral;
    }

    private static int sumIntegral(
            int[] integral,
            int width,
            int x0,
            int y0,
            int x1,
            int y1
    ) {
        int stride = width + 1;
        return integral[y1 * stride + x1]
                - integral[y0 * stride + x1]
                - integral[y1 * stride + x0]
                + integral[y0 * stride + x0];
    }

    private static int cellStart(
            int minimum,
            int maximum,
            int cell,
            int count,
            int limit
    ) {
        float span = maximum - minimum + 1.0f;
        return clampInt(
                (int) Math.floor(minimum + span * cell / count),
                0,
                limit - 1
        );
    }

    private static int cellEnd(
            int minimum,
            int maximum,
            int cell,
            int count,
            int limit
    ) {
        float span = maximum - minimum + 1.0f;
        int start = cellStart(minimum, maximum, cell, count, limit);
        int end = (int) Math.ceil(minimum + span * (cell + 1) / count);
        return clampInt(Math.max(start + 1, end), 1, limit);
    }

    private static float estimateCellWorldX(
            Bounds bounds,
            int width,
            float aspectScale,
            int columns
    ) {
        float spanU = (bounds.right - bounds.left)
                / (float) Math.max(1, width - 1);
        return Math.max(
                EPSILON,
                spanU * 2.0f * aspectScale / Math.max(1, columns)
        );
    }

    private static int putQuad(
            int[] indices,
            int cursor,
            int a,
            int b,
            int c,
            int d,
            boolean reverse
    ) {
        if (reverse) {
            indices[cursor++] = a;
            indices[cursor++] = c;
            indices[cursor++] = b;
            indices[cursor++] = a;
            indices[cursor++] = d;
            indices[cursor++] = c;
        } else {
            indices[cursor++] = a;
            indices[cursor++] = b;
            indices[cursor++] = c;
            indices[cursor++] = a;
            indices[cursor++] = c;
            indices[cursor++] = d;
        }
        return cursor;
    }

    private static void computeNormals(
            float[] positions,
            int[] indices,
            float[] normals
    ) {
        for (int index = 0; index < indices.length; index += 3) {
            int a = indices[index];
            int b = indices[index + 1];
            int c = indices[index + 2];
            int ao = a * 3;
            int bo = b * 3;
            int co = c * 3;
            float abx = positions[bo] - positions[ao];
            float aby = positions[bo + 1] - positions[ao + 1];
            float abz = positions[bo + 2] - positions[ao + 2];
            float acx = positions[co] - positions[ao];
            float acy = positions[co + 1] - positions[ao + 1];
            float acz = positions[co + 2] - positions[ao + 2];
            float nx = aby * acz - abz * acy;
            float ny = abz * acx - abx * acz;
            float nz = abx * acy - aby * acx;
            float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (length <= EPSILON) {
                continue;
            }
            nx /= length;
            ny /= length;
            nz /= length;
            addNormal(normals, a, nx, ny, nz);
            addNormal(normals, b, nx, ny, nz);
            addNormal(normals, c, nx, ny, nz);
        }
        for (int vertex = 0; vertex < normals.length / 3; vertex++) {
            int offset = vertex * 3;
            float x = normals[offset];
            float y = normals[offset + 1];
            float z = normals[offset + 2];
            float length = (float) Math.sqrt(x * x + y * y + z * z);
            if (length <= EPSILON) {
                normals[offset + 2] = 1.0f;
            } else {
                normals[offset] = x / length;
                normals[offset + 1] = y / length;
                normals[offset + 2] = z / length;
            }
        }
    }

    private static void addNormal(
            float[] normals,
            int vertex,
            float x,
            float y,
            float z
    ) {
        int offset = vertex * 3;
        normals[offset] += x;
        normals[offset + 1] += y;
        normals[offset + 2] += z;
    }

    private static void putPosition(
            float[] positions,
            int vertex,
            float x,
            float y,
            float z
    ) {
        int offset = vertex * 3;
        positions[offset] = x;
        positions[offset + 1] = y;
        positions[offset + 2] = z;
    }

    private static void copyPosition(
            float[] positions,
            int source,
            int target
    ) {
        int sourceOffset = source * 3;
        putPosition(
                positions,
                target,
                positions[sourceOffset],
                positions[sourceOffset + 1],
                positions[sourceOffset + 2]
        );
    }

    private static void putMidPosition(
            float[] positions,
            int front,
            int back,
            int target,
            float shiftX,
            float shiftY
    ) {
        int frontOffset = front * 3;
        int backOffset = back * 3;
        putPosition(
                positions,
                target,
                (positions[frontOffset] + positions[backOffset]) * 0.5f + shiftX,
                (positions[frontOffset + 1] + positions[backOffset + 1]) * 0.5f + shiftY,
                0.0f
        );
    }

    private static void validate(
            boolean[] frontMask,
            boolean[] backMask,
            int maskWidth,
            int maskHeight,
            float aspectScale,
            float halfDepth,
            int rows,
            int columns,
            FaceBack25DMesher.AtlasLayout atlas
    ) {
        if (maskWidth < 16 || maskHeight < 16
                || frontMask == null || backMask == null
                || frontMask.length != maskWidth * maskHeight
                || backMask.length != maskWidth * maskHeight) {
            throw new IllegalArgumentException("Masques Face/Dos invalides");
        }
        if (!Float.isFinite(aspectScale) || aspectScale <= 0.05f) {
            throw new IllegalArgumentException("Proportions Face/Dos invalides");
        }
        if (!Float.isFinite(halfDepth) || halfDepth < 0.04f || halfDepth > 0.40f) {
            throw new IllegalArgumentException("Épaisseur Face/Dos invalide");
        }
        if (rows < 24 || rows > 256 || columns < 16 || columns > 128 || atlas == null) {
            throw new IllegalArgumentException("Qualité coque 2.5D invalide");
        }
    }

    private static float lerp(float first, float second, float amount) {
        return first + (second - first) * amount;
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static int clampInt(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static final class Bounds {
        final int left;
        final int top;
        final int right;
        final int bottom;

        Bounds(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }

    private static final class SideWrite {
        final int indexCursor;
        final int vertexCursor;

        SideWrite(int indexCursor, int vertexCursor) {
            this.indexCursor = indexCursor;
            this.vertexCursor = vertexCursor;
        }
    }

    public static final class BuildResult {
        private final MeshData mesh;
        private final int rows;
        private final int columns;
        private final float halfDepth;
        private final int activeCells;
        private final int boundaryEdges;

        BuildResult(
                MeshData mesh,
                int rows,
                int columns,
                float halfDepth,
                int activeCells,
                int boundaryEdges
        ) {
            this.mesh = mesh;
            this.rows = rows;
            this.columns = columns;
            this.halfDepth = halfDepth;
            this.activeCells = activeCells;
            this.boundaryEdges = boundaryEdges;
        }

        public MeshData getMesh() {
            return mesh;
        }

        public int getRows() {
            return rows;
        }

        public int getColumns() {
            return columns;
        }

        public float getHalfDepth() {
            return halfDepth;
        }

        public int getActiveCells() {
            return activeCells;
        }

        public int getBoundaryEdges() {
            return boundaryEdges;
        }
    }
}
