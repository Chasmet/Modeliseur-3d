package com.chasmet.modeliseur3d.model;

import java.util.Arrays;

/**
 * Construit un relief 2.5D fermé depuis une silhouette frontale.
 *
 * Le maillage reste volontairement peu profond : une face avant bombée,
 * une face arrière, puis des bords fermés. Les UV utilisent un atlas 2x2
 * contenant les vues avant, arrière, gauche et droite.
 */
public final class Relief25DMesher {
    private static final float EPSILON = 1.0e-6f;

    private Relief25DMesher() {
    }

    public static BuildResult build(
            float[] left,
            float[] right,
            float topV,
            float bottomV,
            float aspectScale,
            float halfDepth,
            int columns,
            AtlasLayout atlas
    ) {
        validate(left, right, topV, bottomV, aspectScale, halfDepth, columns, atlas);
        int rows = left.length;
        float[] safeLeft = Arrays.copyOf(left, rows);
        float[] safeRight = Arrays.copyOf(right, rows);
        repairRows(safeLeft, safeRight);
        smoothRows(safeLeft, safeRight, 2);

        int frontCount = rows * columns;
        int backCount = frontCount;
        int leftWallStart = frontCount + backCount;
        int rightWallStart = leftWallStart + rows * 2;
        int topCapStart = rightWallStart + rows * 2;
        int bottomCapStart = topCapStart + columns * 2;
        int vertexCount = bottomCapStart + columns * 2;

        int frontIndexCount = (rows - 1) * (columns - 1) * 6;
        int backIndexCount = frontIndexCount;
        int sideIndexCount = (rows - 1) * 6 * 2;
        int capIndexCount = (columns - 1) * 6 * 2;
        int[] indices = new int[
                frontIndexCount + backIndexCount + sideIndexCount + capIndexCount
        ];
        float[] positions = new float[vertexCount * 3];
        float[] texCoords = new float[vertexCount * 2];
        float[] normals = new float[vertexCount * 3];

        float maximumWidth = 0.0f;
        for (int row = 0; row < rows; row++) {
            maximumWidth = Math.max(maximumWidth, safeRight[row] - safeLeft[row]);
        }
        maximumWidth = Math.max(0.04f, maximumWidth);

        for (int row = 0; row < rows; row++) {
            float rowAmount = row / (float) Math.max(1, rows - 1);
            float sourceV = lerp(topV, bottomV, rowAmount);
            float y = 1.0f - rowAmount * 2.0f;
            float widthRatio = clamp(
                    (safeRight[row] - safeLeft[row]) / maximumWidth,
                    0.0f,
                    1.0f
            );
            float rowDepth = halfDepth
                    * (0.34f + 0.66f * (float) Math.sqrt(widthRatio))
                    * (0.82f + 0.18f * (float) Math.sin(Math.PI * rowAmount));

            for (int column = 0; column < columns; column++) {
                float amount = column / (float) Math.max(1, columns - 1);
                float sourceX = lerp(safeLeft[row], safeRight[row], amount);
                float x = sourceX * aspectScale;
                float bulge = 0.20f
                        + 0.80f * (float) Math.sin(Math.PI * amount);
                float z = Math.max(halfDepth * 0.08f, rowDepth * bulge);

                int front = row * columns + column;
                int back = frontCount + front;
                putPosition(positions, front, x, y, z);
                putPosition(positions, back, x, y, -z);

                float localU = clamp((sourceX + 1.0f) * 0.5f, 0.0f, 1.0f);
                atlas.put(texCoords, front, AtlasLayout.FRONT, localU, sourceV);
                atlas.put(texCoords, back, AtlasLayout.BACK, 1.0f - localU, sourceV);
            }
        }

        for (int row = 0; row < rows; row++) {
            int frontLeft = row * columns;
            int backLeft = frontCount + frontLeft;
            int leftBase = leftWallStart + row * 2;
            copyPosition(positions, frontLeft, leftBase);
            copyPosition(positions, backLeft, leftBase + 1);
            float v = row / (float) Math.max(1, rows - 1);
            atlas.put(texCoords, leftBase, AtlasLayout.LEFT, 0.0f, v);
            atlas.put(texCoords, leftBase + 1, AtlasLayout.LEFT, 1.0f, v);

            int frontRight = row * columns + columns - 1;
            int backRight = frontCount + frontRight;
            int rightBase = rightWallStart + row * 2;
            copyPosition(positions, frontRight, rightBase);
            copyPosition(positions, backRight, rightBase + 1);
            atlas.put(texCoords, rightBase, AtlasLayout.RIGHT, 0.0f, v);
            atlas.put(texCoords, rightBase + 1, AtlasLayout.RIGHT, 1.0f, v);
        }

        for (int column = 0; column < columns; column++) {
            int frontTop = column;
            int backTop = frontCount + column;
            int topBase = topCapStart + column * 2;
            copyPosition(positions, frontTop, topBase);
            copyPosition(positions, backTop, topBase + 1);
            float u = column / (float) Math.max(1, columns - 1);
            atlas.put(texCoords, topBase, AtlasLayout.FRONT, u, topV);
            atlas.put(texCoords, topBase + 1, AtlasLayout.BACK, 1.0f - u, topV);

            int frontBottom = (rows - 1) * columns + column;
            int backBottom = frontCount + frontBottom;
            int bottomBase = bottomCapStart + column * 2;
            copyPosition(positions, frontBottom, bottomBase);
            copyPosition(positions, backBottom, bottomBase + 1);
            atlas.put(texCoords, bottomBase, AtlasLayout.FRONT, u, bottomV);
            atlas.put(texCoords, bottomBase + 1, AtlasLayout.BACK, 1.0f - u, bottomV);
        }

        int cursor = 0;
        for (int row = 0; row < rows - 1; row++) {
            for (int column = 0; column < columns - 1; column++) {
                int a = row * columns + column;
                int b = (row + 1) * columns + column;
                int c = (row + 1) * columns + column + 1;
                int d = row * columns + column + 1;
                cursor = putQuad(indices, cursor, a, b, c, d, false);

                int ba = frontCount + a;
                int bb = frontCount + b;
                int bc = frontCount + c;
                int bd = frontCount + d;
                cursor = putQuad(indices, cursor, ba, bb, bc, bd, true);
            }
        }

        for (int row = 0; row < rows - 1; row++) {
            int ltFront = leftWallStart + row * 2;
            int ltBack = ltFront + 1;
            int lbFront = leftWallStart + (row + 1) * 2;
            int lbBack = lbFront + 1;
            indices[cursor++] = ltFront;
            indices[cursor++] = lbBack;
            indices[cursor++] = lbFront;
            indices[cursor++] = ltFront;
            indices[cursor++] = ltBack;
            indices[cursor++] = lbBack;

            int rtFront = rightWallStart + row * 2;
            int rtBack = rtFront + 1;
            int rbFront = rightWallStart + (row + 1) * 2;
            int rbBack = rbFront + 1;
            indices[cursor++] = rtFront;
            indices[cursor++] = rbFront;
            indices[cursor++] = rbBack;
            indices[cursor++] = rtFront;
            indices[cursor++] = rbBack;
            indices[cursor++] = rtBack;
        }

        for (int column = 0; column < columns - 1; column++) {
            int tfLeft = topCapStart + column * 2;
            int tbLeft = tfLeft + 1;
            int tfRight = topCapStart + (column + 1) * 2;
            int tbRight = tfRight + 1;
            indices[cursor++] = tfLeft;
            indices[cursor++] = tfRight;
            indices[cursor++] = tbRight;
            indices[cursor++] = tfLeft;
            indices[cursor++] = tbRight;
            indices[cursor++] = tbLeft;

            int bfLeft = bottomCapStart + column * 2;
            int bbLeft = bfLeft + 1;
            int bfRight = bottomCapStart + (column + 1) * 2;
            int bbRight = bfRight + 1;
            indices[cursor++] = bfLeft;
            indices[cursor++] = bbRight;
            indices[cursor++] = bfRight;
            indices[cursor++] = bfLeft;
            indices[cursor++] = bbLeft;
            indices[cursor++] = bbRight;
        }

        if (cursor != indices.length) {
            throw new IllegalStateException(
                    "Nombre d'indices 2.5D incohérent : " + cursor + "/" + indices.length
            );
        }
        computeNormals(positions, indices, normals);
        MeshData mesh = new MeshData(positions, normals, texCoords, indices);
        return new BuildResult(mesh, rows, columns, halfDepth);
    }

    private static int putQuad(
            int[] indices,
            int cursor,
            int a,
            int b,
            int c,
            int d,
            boolean reversed
    ) {
        if (reversed) {
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
            float nx = normals[offset];
            float ny = normals[offset + 1];
            float nz = normals[offset + 2];
            float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (length <= EPSILON) {
                normals[offset + 2] = 1.0f;
            } else {
                normals[offset] = nx / length;
                normals[offset + 1] = ny / length;
                normals[offset + 2] = nz / length;
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

    private static void repairRows(float[] left, float[] right) {
        for (int row = 0; row < left.length; row++) {
            if (valid(left[row], right[row])) {
                continue;
            }
            int before = row - 1;
            while (before >= 0 && !valid(left[before], right[before])) {
                before--;
            }
            int after = row + 1;
            while (after < left.length && !valid(left[after], right[after])) {
                after++;
            }
            if (before >= 0 && after < left.length) {
                float amount = (row - before) / (float) (after - before);
                left[row] = lerp(left[before], left[after], amount);
                right[row] = lerp(right[before], right[after], amount);
            } else if (before >= 0) {
                left[row] = left[before];
                right[row] = right[before];
            } else if (after < left.length) {
                left[row] = left[after];
                right[row] = right[after];
            } else {
                left[row] = -0.05f;
                right[row] = 0.05f;
            }
        }
    }

    private static void smoothRows(float[] left, float[] right, int passes) {
        for (int pass = 0; pass < passes; pass++) {
            float[] sourceLeft = Arrays.copyOf(left, left.length);
            float[] sourceRight = Arrays.copyOf(right, right.length);
            for (int row = 1; row < left.length - 1; row++) {
                left[row] = sourceLeft[row] * 0.62f
                        + (sourceLeft[row - 1] + sourceLeft[row + 1]) * 0.19f;
                right[row] = sourceRight[row] * 0.62f
                        + (sourceRight[row - 1] + sourceRight[row + 1]) * 0.19f;
                if (right[row] - left[row] < 0.012f) {
                    float center = (right[row] + left[row]) * 0.5f;
                    left[row] = center - 0.006f;
                    right[row] = center + 0.006f;
                }
            }
        }
    }

    private static boolean valid(float left, float right) {
        return Float.isFinite(left)
                && Float.isFinite(right)
                && right - left >= 0.01f;
    }

    private static void validate(
            float[] left,
            float[] right,
            float topV,
            float bottomV,
            float aspectScale,
            float halfDepth,
            int columns,
            AtlasLayout atlas
    ) {
        if (left == null || right == null
                || left.length != right.length
                || left.length < 12) {
            throw new IllegalArgumentException("Silhouette 2.5D invalide");
        }
        if (!(topV >= 0.0f && bottomV <= 1.0f && bottomV - topV >= 0.04f)) {
            throw new IllegalArgumentException("Plage verticale 2.5D invalide");
        }
        if (!Float.isFinite(aspectScale) || aspectScale <= 0.05f) {
            throw new IllegalArgumentException("Rapport de silhouette invalide");
        }
        if (!Float.isFinite(halfDepth) || halfDepth < 0.015f || halfDepth > 0.35f) {
            throw new IllegalArgumentException("Épaisseur 2.5D invalide");
        }
        if (columns < 4 || columns > 64 || atlas == null) {
            throw new IllegalArgumentException("Résolution 2.5D invalide");
        }
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
            int sourceVertex,
            int targetVertex
    ) {
        int source = sourceVertex * 3;
        int target = targetVertex * 3;
        positions[target] = positions[source];
        positions[target + 1] = positions[source + 1];
        positions[target + 2] = positions[source + 2];
    }

    private static float lerp(float first, float second, float amount) {
        return first + (second - first) * amount;
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public static final class AtlasLayout {
        public static final int FRONT = 0;
        public static final int BACK = 1;
        public static final int LEFT = 2;
        public static final int RIGHT = 3;

        private final int cellWidth;
        private final int cellHeight;
        private final int padding;
        private final int atlasWidth;
        private final int atlasHeight;

        public AtlasLayout(int cellWidth, int cellHeight, int padding) {
            if (cellWidth < 32 || cellHeight < 32
                    || padding < 0
                    || padding * 2 >= Math.min(cellWidth, cellHeight)) {
                throw new IllegalArgumentException("Atlas 2.5D invalide");
            }
            this.cellWidth = cellWidth;
            this.cellHeight = cellHeight;
            this.padding = padding;
            this.atlasWidth = cellWidth * 2;
            this.atlasHeight = cellHeight * 2;
        }

        public int getCellWidth() {
            return cellWidth;
        }

        public int getCellHeight() {
            return cellHeight;
        }

        public int getAtlasWidth() {
            return atlasWidth;
        }

        public int getAtlasHeight() {
            return atlasHeight;
        }

        public void put(
                float[] texCoords,
                int vertex,
                int cell,
                float localU,
                float localV
        ) {
            int column = cell & 1;
            int row = cell >> 1;
            float usableWidth = cellWidth - padding * 2.0f;
            float usableHeight = cellHeight - padding * 2.0f;
            float pixelX = column * cellWidth
                    + padding
                    + clamp(localU, 0.0f, 1.0f) * usableWidth;
            float pixelY = row * cellHeight
                    + padding
                    + clamp(localV, 0.0f, 1.0f) * usableHeight;
            int offset = vertex * 2;
            texCoords[offset] = pixelX / atlasWidth;
            texCoords[offset + 1] = 1.0f - pixelY / atlasHeight;
        }
    }

    public static final class BuildResult {
        private final MeshData mesh;
        private final int rows;
        private final int columns;
        private final float halfDepth;

        BuildResult(MeshData mesh, int rows, int columns, float halfDepth) {
            this.mesh = mesh;
            this.rows = rows;
            this.columns = columns;
            this.halfDepth = halfDepth;
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
    }
}
