package com.chasmet.modeliseur3d.model;

import java.util.Arrays;

/**
 * Maillage 2.5D Face/Dos V5.2.
 *
 * Les UV suivent directement l'ordre des lignes Android dans l'atlas. Ils ne
 * sont donc pas retournés une seconde fois, ce qui corrige le personnage
 * affiché tête en bas dans la V5.1.
 */
public final class FaceBack25DMesher {
    private static final float EPSILON = 1.0e-6f;

    private FaceBack25DMesher() {
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

        int surfaceCount = rows * columns;
        int frontStart = 0;
        int backStart = surfaceCount;
        int leftWallStart = surfaceCount * 2;
        int rightWallStart = leftWallStart + rows * 2;
        int topCapStart = rightWallStart + rows * 2;
        int bottomCapStart = topCapStart + columns * 2;
        int vertexCount = bottomCapStart + columns * 2;

        int surfaceIndices = (rows - 1) * (columns - 1) * 6;
        int wallIndices = (rows - 1) * 6 * 2;
        int capIndices = (columns - 1) * 6 * 2;
        int[] indices = new int[surfaceIndices * 2 + wallIndices + capIndices];
        float[] positions = new float[vertexCount * 3];
        float[] normals = new float[vertexCount * 3];
        float[] texCoords = new float[vertexCount * 2];

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
                    * (0.56f + 0.44f * (float) Math.sqrt(widthRatio));

            for (int column = 0; column < columns; column++) {
                float amount = column / (float) Math.max(1, columns - 1);
                float sourceX = lerp(safeLeft[row], safeRight[row], amount);
                float x = sourceX * aspectScale;
                float curve = (float) Math.sin(Math.PI * amount);
                float frontZ = rowDepth * (0.72f + 0.28f * curve);
                float backZ = -rowDepth * (0.66f + 0.34f * curve);

                int front = frontStart + row * columns + column;
                int back = backStart + row * columns + column;
                putPosition(positions, front, x, y, frontZ);
                putPosition(positions, back, x, y, backZ);

                float localU = clamp((sourceX + 1.0f) * 0.5f, 0.0f, 1.0f);
                atlas.put(texCoords, front, AtlasLayout.FRONT, localU, sourceV);
                atlas.put(texCoords, back, AtlasLayout.BACK, 1.0f - localU, sourceV);
            }
        }

        for (int row = 0; row < rows; row++) {
            float rowAmount = row / (float) Math.max(1, rows - 1);
            float sourceV = lerp(topV, bottomV, rowAmount);

            int frontLeft = frontStart + row * columns;
            int backLeft = backStart + row * columns;
            int leftBase = leftWallStart + row * 2;
            copyPosition(positions, frontLeft, leftBase);
            copyPosition(positions, backLeft, leftBase + 1);
            atlas.put(texCoords, leftBase, AtlasLayout.LEFT, 0.0f, sourceV);
            atlas.put(texCoords, leftBase + 1, AtlasLayout.LEFT, 1.0f, sourceV);

            int frontRight = frontStart + row * columns + columns - 1;
            int backRight = backStart + row * columns + columns - 1;
            int rightBase = rightWallStart + row * 2;
            copyPosition(positions, frontRight, rightBase);
            copyPosition(positions, backRight, rightBase + 1);
            atlas.put(texCoords, rightBase, AtlasLayout.RIGHT, 0.0f, sourceV);
            atlas.put(texCoords, rightBase + 1, AtlasLayout.RIGHT, 1.0f, sourceV);
        }

        for (int column = 0; column < columns; column++) {
            float amount = column / (float) Math.max(1, columns - 1);
            int frontTop = frontStart + column;
            int backTop = backStart + column;
            int topBase = topCapStart + column * 2;
            copyPosition(positions, frontTop, topBase);
            copyPosition(positions, backTop, topBase + 1);
            atlas.put(texCoords, topBase, AtlasLayout.FRONT, amount, topV);
            atlas.put(texCoords, topBase + 1, AtlasLayout.BACK, 1.0f - amount, topV);

            int frontBottom = frontStart + (rows - 1) * columns + column;
            int backBottom = backStart + (rows - 1) * columns + column;
            int bottomBase = bottomCapStart + column * 2;
            copyPosition(positions, frontBottom, bottomBase);
            copyPosition(positions, backBottom, bottomBase + 1);
            atlas.put(texCoords, bottomBase, AtlasLayout.FRONT, amount, bottomV);
            atlas.put(texCoords, bottomBase + 1, AtlasLayout.BACK, 1.0f - amount, bottomV);
        }

        int cursor = 0;
        for (int row = 0; row < rows - 1; row++) {
            for (int column = 0; column < columns - 1; column++) {
                int a = frontStart + row * columns + column;
                int b = frontStart + (row + 1) * columns + column;
                int c = frontStart + (row + 1) * columns + column + 1;
                int d = frontStart + row * columns + column + 1;
                cursor = putQuad(indices, cursor, a, b, c, d, false);

                int ba = backStart + row * columns + column;
                int bb = backStart + (row + 1) * columns + column;
                int bc = backStart + (row + 1) * columns + column + 1;
                int bd = backStart + row * columns + column + 1;
                cursor = putQuad(indices, cursor, ba, bb, bc, bd, true);
            }
        }

        for (int row = 0; row < rows - 1; row++) {
            int lf = leftWallStart + row * 2;
            int lb = lf + 1;
            int nlf = leftWallStart + (row + 1) * 2;
            int nlb = nlf + 1;
            indices[cursor++] = lf;
            indices[cursor++] = nlb;
            indices[cursor++] = nlf;
            indices[cursor++] = lf;
            indices[cursor++] = lb;
            indices[cursor++] = nlb;

            int rf = rightWallStart + row * 2;
            int rb = rf + 1;
            int nrf = rightWallStart + (row + 1) * 2;
            int nrb = nrf + 1;
            indices[cursor++] = rf;
            indices[cursor++] = nrf;
            indices[cursor++] = nrb;
            indices[cursor++] = rf;
            indices[cursor++] = nrb;
            indices[cursor++] = rb;
        }

        for (int column = 0; column < columns - 1; column++) {
            int tf = topCapStart + column * 2;
            int tb = tf + 1;
            int ntf = topCapStart + (column + 1) * 2;
            int ntb = ntf + 1;
            indices[cursor++] = tf;
            indices[cursor++] = ntf;
            indices[cursor++] = ntb;
            indices[cursor++] = tf;
            indices[cursor++] = ntb;
            indices[cursor++] = tb;

            int bf = bottomCapStart + column * 2;
            int bb = bf + 1;
            int nbf = bottomCapStart + (column + 1) * 2;
            int nbb = nbf + 1;
            indices[cursor++] = bf;
            indices[cursor++] = nbb;
            indices[cursor++] = nbf;
            indices[cursor++] = bf;
            indices[cursor++] = bb;
            indices[cursor++] = nbb;
        }

        if (cursor != indices.length) {
            throw new IllegalStateException(
                    "Indices Face/Dos incohérents : " + cursor + "/" + indices.length
            );
        }
        computeNormals(positions, indices, normals);
        return new BuildResult(
                new MeshData(positions, normals, texCoords, indices),
                rows,
                columns,
                halfDepth
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
                left[row] = -0.04f;
                right[row] = 0.04f;
            }
        }
    }

    private static void smoothRows(float[] left, float[] right, int passes) {
        for (int pass = 0; pass < passes; pass++) {
            float[] sourceLeft = Arrays.copyOf(left, left.length);
            float[] sourceRight = Arrays.copyOf(right, right.length);
            for (int row = 1; row < left.length - 1; row++) {
                left[row] = sourceLeft[row] * 0.64f
                        + (sourceLeft[row - 1] + sourceLeft[row + 1]) * 0.18f;
                right[row] = sourceRight[row] * 0.64f
                        + (sourceRight[row - 1] + sourceRight[row + 1]) * 0.18f;
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
            throw new IllegalArgumentException("Silhouette Face/Dos invalide");
        }
        if (topV < 0.0f || bottomV > 1.0f || bottomV - topV < 0.04f) {
            throw new IllegalArgumentException("Hauteur Face/Dos invalide");
        }
        if (!Float.isFinite(aspectScale) || aspectScale <= 0.05f) {
            throw new IllegalArgumentException("Proportions Face/Dos invalides");
        }
        if (!Float.isFinite(halfDepth) || halfDepth < 0.04f || halfDepth > 0.40f) {
            throw new IllegalArgumentException("Épaisseur Face/Dos invalide");
        }
        if (columns < 8 || columns > 96 || atlas == null) {
            throw new IllegalArgumentException("Qualité Face/Dos invalide");
        }
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
                throw new IllegalArgumentException("Atlas Face/Dos invalide");
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
            texCoords[offset + 1] = pixelY / atlasHeight;
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
