package com.chasmet.modeliseur3d.model;

/** Test JVM pur du maillage V5.5 Shell, sans Android ni modèle IA. */
public final class FaceBack25DShellSelfTest {
    private FaceBack25DShellSelfTest() {
    }

    public static void main(String[] args) {
        testSeparatedPartsRemainSeparated();
        testCentralHoleCreatesInnerBoundary();
        System.out.println("FaceBack25DShellSelfTest OK");
    }

    private static void testSeparatedPartsRemainSeparated() {
        int width = 96;
        int height = 128;
        boolean[] front = new boolean[width * height];
        boolean[] back = new boolean[width * height];

        fill(front, width, 12, 18, 38, 112);
        fill(front, width, 58, 24, 84, 106);
        fill(back, width, 14, 20, 40, 110);
        fill(back, width, 56, 22, 82, 108);

        FaceBack25DShellMesher.BuildResult result = FaceBack25DShellMesher.build(
                front,
                back,
                width,
                height,
                0.85f,
                0.16f,
                96,
                64,
                new FaceBack25DMesher.AtlasLayout(128, 128, 2)
        );

        require(result.getMesh().getTriangleCount() > 100,
                "maillage séparé trop pauvre");
        require(result.getBoundaryEdges() > 300,
                "les deux contours ne sont pas suffisamment préservés");
        require(result.getActiveCells() < 96 * 64 * 3 / 4,
                "le vide entre les deux parties a été rebouché");
    }

    private static void testCentralHoleCreatesInnerBoundary() {
        int width = 100;
        int height = 100;
        boolean[] front = new boolean[width * height];
        boolean[] back = new boolean[width * height];
        fill(front, width, 10, 10, 90, 90);
        fill(back, width, 10, 10, 90, 90);
        clear(front, width, 34, 30, 66, 72);
        clear(back, width, 34, 30, 66, 72);

        FaceBack25DShellMesher.BuildResult result = FaceBack25DShellMesher.build(
                front,
                back,
                width,
                height,
                1.0f,
                0.15f,
                80,
                80,
                new FaceBack25DMesher.AtlasLayout(128, 128, 2)
        );

        require(result.getBoundaryEdges() > 450,
                "le contour intérieur du trou n'est pas conservé");
        require(result.getMesh().getTriangleCount() > 1000,
                "coque avec trou invalide");
    }

    private static void fill(
            boolean[] mask,
            int width,
            int left,
            int top,
            int right,
            int bottom
    ) {
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                mask[y * width + x] = true;
            }
        }
    }

    private static void clear(
            boolean[] mask,
            int width,
            int left,
            int top,
            int right,
            int bottom
    ) {
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                mask[y * width + x] = false;
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
