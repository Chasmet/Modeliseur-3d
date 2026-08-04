package com.chasmet.modeliseur3d.model;

/**
 * Projection volumique dédiée à quatre vues réelles : face, droite, dos, gauche.
 * Les paires opposées sont contrôlées séparément pour éviter qu'une silhouette
 * erronée sur un axe fabrique un volume fantôme sur l'autre axe.
 */
public final class StylizedFourViewProjector {
    public static final int FRONT = 0;
    public static final int RIGHT = 1;
    public static final int BACK = 2;
    public static final int LEFT = 3;

    private StylizedFourViewProjector() {
    }

    public static boolean[] build(
            boolean[][] masks,
            int width,
            int height,
            int depth,
            boolean tolerant
    ) {
        validate(masks, width, height, depth);
        boolean[] volume = new boolean[width * height * depth];
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                double modelX = normalizedCoordinate(x, width);
                int frontX = projectToPixel(modelX, width);
                int backX = projectToPixel(-modelX, width);
                for (int z = 0; z < depth; z++) {
                    double modelZ = normalizedCoordinate(z, depth);
                    int rightX = projectToPixel(modelZ, width);
                    int leftX = projectToPixel(-modelZ, width);

                    boolean front = masks[FRONT][row + frontX];
                    boolean back = masks[BACK][row + backX];
                    boolean right = masks[RIGHT][row + rightX];
                    boolean left = masks[LEFT][row + leftX];

                    boolean occupied;
                    if (!tolerant) {
                        occupied = front && back && right && left;
                    } else {
                        int support = (front ? 1 : 0)
                                + (back ? 1 : 0)
                                + (right ? 1 : 0)
                                + (left ? 1 : 0);
                        occupied = support >= 3
                                && (front || back)
                                && (right || left);
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
        int expected = width * height;
        for (boolean[] mask : masks) {
            if (mask == null || mask.length != expected) {
                throw new IllegalArgumentException("Dimensions de silhouette incohérentes");
            }
        }
    }

    private static double normalizedCoordinate(int value, int size) {
        return -1.0 + 2.0 * value / Math.max(1.0, size - 1.0);
    }

    private static int projectToPixel(double normalized, int width) {
        double clamped = Math.max(-1.0, Math.min(1.0, normalized));
        return Math.max(0, Math.min(
                width - 1,
                (int) Math.round((clamped * 0.5 + 0.5) * (width - 1))
        ));
    }

    private static int index(int x, int y, int z, int width, int depth) {
        return (y * width + x) * depth + z;
    }
}
