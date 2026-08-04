package com.chasmet.modeliseur3d.model;

/**
 * Projection volumique à quatre vues réelles.
 *
 * V5.8 : les profils disposent de leur propre résolution horizontale. Le mode
 * adaptatif utilise l'union des deux vues opposées sur chaque axe puis limite
 * les zones incertaines par une section elliptique. Cela évite le personnage
 * écrasé quand une vue latérale contient un accessoire très long.
 */
public final class StylizedFourViewProjector {
    public static final int FRONT = 0;
    public static final int RIGHT = 1;
    public static final int BACK = 2;
    public static final int LEFT = 3;

    private StylizedFourViewProjector() {
    }

    /** Compatibilité avec les anciens tests où les quatre masques ont la même largeur. */
    public static boolean[] build(
            boolean[][] masks,
            int width,
            int height,
            int depth,
            boolean adaptive
    ) {
        return build(masks, width, height, depth, width, adaptive);
    }

    public static boolean[] build(
            boolean[][] masks,
            int width,
            int height,
            int depth,
            int sideWidth,
            boolean adaptive
    ) {
        if (sideWidth != depth) {
            throw new IllegalArgumentException("La largeur des profils doit correspondre à la profondeur");
        }
        validate(masks, width, height, depth);
        boolean[] volume = new boolean[width * height * depth];
        RowEnvelope[] envelopes = adaptive
                ? buildEnvelopes(masks, width, height, depth)
                : null;

        for (int y = 0; y < height; y++) {
            int frontRow = y * width;
            int sideRow = y * depth;
            RowEnvelope envelope = adaptive ? envelopes[y] : null;
            for (int x = 0; x < width; x++) {
                boolean front = masks[FRONT][frontRow + x];
                boolean back = masks[BACK][frontRow + (width - 1 - x)];
                for (int z = 0; z < depth; z++) {
                    boolean right = masks[RIGHT][sideRow + z];
                    boolean left = masks[LEFT][sideRow + (depth - 1 - z)];
                    boolean occupied;
                    if (!adaptive) {
                        occupied = front && back && right && left;
                    } else {
                        boolean frontAxis = front || back;
                        boolean sideAxis = right || left;
                        int support = (front ? 1 : 0)
                                + (back ? 1 : 0)
                                + (right ? 1 : 0)
                                + (left ? 1 : 0);
                        occupied = frontAxis
                                && sideAxis
                                && (support >= 3 || envelope.accepts(x, z));
                    }
                    if (occupied) {
                        volume[index(x, y, z, width, depth)] = true;
                    }
                }
            }
        }
        return volume;
    }

    public static int countOccupied(boolean[] volume) {
        int count = 0;
        if (volume != null) {
            for (boolean value : volume) {
                if (value) {
                    count++;
                }
            }
        }
        return count;
    }

    private static RowEnvelope[] buildEnvelopes(
            boolean[][] masks,
            int width,
            int height,
            int depth
    ) {
        RowEnvelope[] rows = new RowEnvelope[height];
        for (int y = 0; y < height; y++) {
            int frontRow = y * width;
            int sideRow = y * depth;
            int minX = width;
            int maxX = -1;
            int minZ = depth;
            int maxZ = -1;
            for (int x = 0; x < width; x++) {
                if (masks[FRONT][frontRow + x]
                        || masks[BACK][frontRow + (width - 1 - x)]) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                }
            }
            for (int z = 0; z < depth; z++) {
                if (masks[RIGHT][sideRow + z]
                        || masks[LEFT][sideRow + (depth - 1 - z)]) {
                    minZ = Math.min(minZ, z);
                    maxZ = Math.max(maxZ, z);
                }
            }
            rows[y] = new RowEnvelope(minX, maxX, minZ, maxZ);
        }
        smoothEnvelopes(rows);
        return rows;
    }

    private static void smoothEnvelopes(RowEnvelope[] rows) {
        if (rows.length < 3) {
            return;
        }
        RowEnvelope[] source = rows.clone();
        for (int y = 1; y < rows.length - 1; y++) {
            rows[y] = RowEnvelope.blend(source[y - 1], source[y], source[y + 1]);
        }
    }

    private static void validate(
            boolean[][] masks,
            int width,
            int height,
            int depth
    ) {
        if (masks == null || masks.length != 4) {
            throw new IllegalArgumentException("Quatre silhouettes sont requises");
        }
        if (width < 4 || height < 4 || depth < 4) {
            throw new IllegalArgumentException("Résolution volumique trop faible");
        }
        int frontExpected = width * height;
        int sideExpected = depth * height;
        if (masks[FRONT] == null || masks[FRONT].length != frontExpected
                || masks[BACK] == null || masks[BACK].length != frontExpected
                || masks[RIGHT] == null || masks[RIGHT].length != sideExpected
                || masks[LEFT] == null || masks[LEFT].length != sideExpected) {
            throw new IllegalArgumentException("Dimensions de silhouette incohérentes");
        }
    }

    private static int index(int x, int y, int z, int width, int depth) {
        return (y * width + x) * depth + z;
    }

    private static final class RowEnvelope {
        private final double centerX;
        private final double radiusX;
        private final double centerZ;
        private final double radiusZ;
        private final boolean valid;

        RowEnvelope(int minX, int maxX, int minZ, int maxZ) {
            valid = maxX >= minX && maxZ >= minZ;
            centerX = valid ? (minX + maxX) * 0.5 : 0.0;
            centerZ = valid ? (minZ + maxZ) * 0.5 : 0.0;
            radiusX = valid ? Math.max(1.0, (maxX - minX + 1) * 0.55) : 1.0;
            radiusZ = valid ? Math.max(1.0, (maxZ - minZ + 1) * 0.55) : 1.0;
        }

        private RowEnvelope(
                double centerX,
                double radiusX,
                double centerZ,
                double radiusZ,
                boolean valid
        ) {
            this.centerX = centerX;
            this.radiusX = radiusX;
            this.centerZ = centerZ;
            this.radiusZ = radiusZ;
            this.valid = valid;
        }

        boolean accepts(int x, int z) {
            if (!valid) {
                return false;
            }
            double nx = (x - centerX) / radiusX;
            double nz = (z - centerZ) / radiusZ;
            return nx * nx + nz * nz <= 1.12;
        }

        static RowEnvelope blend(RowEnvelope previous, RowEnvelope current, RowEnvelope next) {
            if (!current.valid) {
                return current;
            }
            double total = 2.0;
            double cx = current.centerX * 2.0;
            double rx = current.radiusX * 2.0;
            double cz = current.centerZ * 2.0;
            double rz = current.radiusZ * 2.0;
            if (previous.valid) {
                cx += previous.centerX;
                rx += previous.radiusX;
                cz += previous.centerZ;
                rz += previous.radiusZ;
                total += 1.0;
            }
            if (next.valid) {
                cx += next.centerX;
                rx += next.radiusX;
                cz += next.centerZ;
                rz += next.radiusZ;
                total += 1.0;
            }
            return new RowEnvelope(cx / total, rx / total, cz / total, rz / total, true);
        }
    }
}
