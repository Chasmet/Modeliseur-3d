package com.chasmet.modeliseur3d.model;

/**
 * Construit une enveloppe 3D à partir de silhouettes prises autour d'un sujet.
 * Chaque masque doit avoir la même largeur et la même hauteur.
 */
public final class MultiViewHullProjector {
    private MultiViewHullProjector() {
    }

    public static boolean[] build(
            boolean[][] masks,
            int width,
            int height,
            int depth,
            int minimumSupport
    ) {
        if (masks == null || masks.length < 4) {
            throw new IllegalArgumentException("Au moins quatre silhouettes sont requises");
        }
        if (width < 4 || height < 4 || depth < 4) {
            throw new IllegalArgumentException("Résolution volumique trop faible");
        }
        int expectedMaskLength = width * height;
        for (boolean[] mask : masks) {
            if (mask == null || mask.length != expectedMaskLength) {
                throw new IllegalArgumentException("Dimensions de silhouette incohérentes");
            }
        }

        int required = Math.max(1, Math.min(masks.length, minimumSupport));
        boolean[] volume = new boolean[width * height * depth];
        double angleStep = Math.PI * 2.0 / masks.length;
        double[] cosine = new double[masks.length];
        double[] sine = new double[masks.length];
        for (int index = 0; index < masks.length; index++) {
            double angle = index * angleStep;
            cosine[index] = Math.cos(angle);
            sine[index] = Math.sin(angle);
        }

        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                double modelX = normalizedCoordinate(x, width);
                for (int z = 0; z < depth; z++) {
                    double modelZ = normalizedCoordinate(z, depth);
                    int support = 0;
                    int remaining = masks.length;
                    for (int view = 0; view < masks.length; view++) {
                        double projected = modelX * cosine[view]
                                + modelZ * sine[view];
                        int projectedX = projectToPixel(projected, width);
                        if (projectedX >= 0
                                && masks[view][row + projectedX]) {
                            support++;
                        }
                        remaining--;
                        if (support + remaining < required) {
                            break;
                        }
                    }
                    if (support >= required) {
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
            for (boolean occupied : volume) {
                if (occupied) {
                    count++;
                }
            }
        }
        return count;
    }

    private static double normalizedCoordinate(int value, int size) {
        return -1.0 + 2.0 * value / Math.max(1.0, size - 1.0);
    }

    private static int projectToPixel(double normalized, int width) {
        if (normalized < -1.02 || normalized > 1.02) {
            return -1;
        }
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
