package com.chasmet.modeliseur3d.model;

public final class MultiViewHullProjectorSelfTest {
    private MultiViewHullProjectorSelfTest() {
    }

    public static void main(String[] args) {
        int width = 48;
        int height = 72;
        int depth = 48;
        boolean[][] masks = new boolean[8][width * height];

        for (int view = 0; view < masks.length; view++) {
            double angle = view * Math.PI * 2.0 / masks.length;
            double apparentHalfWidth = 0.30 * Math.abs(Math.cos(angle))
                    + 0.20 * Math.abs(Math.sin(angle));
            for (int y = 0; y < height; y++) {
                double normalizedY = -1.0
                        + 2.0 * y / Math.max(1.0, height - 1.0);
                double body = Math.max(
                        0.0,
                        1.0 - normalizedY * normalizedY
                );
                double rowHalfWidth = apparentHalfWidth
                        * Math.sqrt(body);
                for (int x = 0; x < width; x++) {
                    double normalizedX = -1.0
                            + 2.0 * x / Math.max(1.0, width - 1.0);
                    masks[view][y * width + x] =
                            Math.abs(normalizedX) <= rowHalfWidth;
                }
            }
        }

        boolean[] hull = MultiViewHullProjector.build(
                masks,
                width,
                height,
                depth,
                7
        );
        int occupied = MultiViewHullProjector.countOccupied(hull);
        require(occupied > 2500, "volume multivue trop petit");
        require(occupied < hull.length / 2, "volume multivue trop large");

        int center = ((height / 2) * width + width / 2) * depth
                + depth / 2;
        require(hull[center], "centre du sujet absent");

        int corner = ((height / 2) * width) * depth;
        require(!hull[corner], "un coin extérieur est occupé");

        System.out.println(
                "MultiViewHullProjectorSelfTest : OK • voxels=" + occupied
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
