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

        FaceBack25DShellMesher.BuildResult separated = build(
                front, back, width, height, 96, 64
        );

        // Référence volontairement pleine entre les deux blocs. Si le moteur
        // rebouche le vide, son contour devient proche de cette référence.
        boolean[] solidFront = new boolean[width * height];
        boolean[] solidBack = new boolean[width * height];
        fill(solidFront, width, 12, 18, 84, 112);
        fill(solidBack, width, 12, 18, 84, 112);
        FaceBack25DShellMesher.BuildResult solid = build(
                solidFront, solidBack, width, height, 96, 64
        );

        require(separated.getMesh().getTriangleCount() > 100,
                "maillage séparé trop pauvre");
        require(separated.getBoundaryEdges() > solid.getBoundaryEdges() + 60,
                "le vide entre les deux parties ne crée pas ses propres contours");
        require(separated.getActiveCells() < solid.getActiveCells(),
                "le vide entre les deux parties a été rempli comme une surface pleine");
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

        FaceBack25DShellMesher.BuildResult withHole = build(
                front, back, width, height, 80, 80
        );

        boolean[] solidFront = new boolean[width * height];
        boolean[] solidBack = new boolean[width * height];
        fill(solidFront, width, 10, 10, 90, 90);
        fill(solidBack, width, 10, 10, 90, 90);
        FaceBack25DShellMesher.BuildResult solid = build(
                solidFront, solidBack, width, height, 80, 80
        );

        require(withHole.getBoundaryEdges() > solid.getBoundaryEdges() + 80,
                "le contour intérieur du trou n'est pas conservé");
        require(withHole.getActiveCells() < solid.getActiveCells(),
                "le trou central a été rebouché");
        require(withHole.getMesh().getTriangleCount() > 1000,
                "coque avec trou invalide");
    }

    private static FaceBack25DShellMesher.BuildResult build(
            boolean[] front,
            boolean[] back,
            int width,
            int height,
            int rows,
            int columns
    ) {
        return FaceBack25DShellMesher.build(
                front,
                back,
                width,
                height,
                0.90f,
                0.16f,
                rows,
                columns,
                new FaceBack25DMesher.AtlasLayout(128, 128, 2)
        );
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
