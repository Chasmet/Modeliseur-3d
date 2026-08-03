package com.chasmet.modeliseur3d.model;

/** Validation Java du maillage Face/Dos V5.2 et de l'orientation des UV. */
public final class FaceBack25DMesherSelfTest {
    private FaceBack25DMesherSelfTest() {
    }

    public static void main(String[] args) {
        int rows = 32;
        int columns = 16;
        float[] left = new float[rows];
        float[] right = new float[rows];
        for (int row = 0; row < rows; row++) {
            float amount = row / (float) (rows - 1);
            float width = 0.26f + 0.34f * (float) Math.sin(Math.PI * amount);
            left[row] = -width;
            right[row] = width;
        }

        FaceBack25DMesher.AtlasLayout atlas =
                new FaceBack25DMesher.AtlasLayout(256, 384, 4);
        FaceBack25DMesher.BuildResult result = FaceBack25DMesher.build(
                left,
                right,
                0.08f,
                0.92f,
                0.72f,
                0.18f,
                columns,
                atlas
        );
        MeshData mesh = result.getMesh();
        require(mesh.getTriangleCount() > 100, "Maillage Face/Dos trop pauvre");
        require(mesh.getVertexCount() > rows * columns * 2,
                "Les parois Face/Dos sont absentes");

        float[] positions = mesh.getPositions();
        float[] uvs = mesh.getTexCoords();
        int frontTop = 0;
        int frontBottom = (rows - 1) * columns;
        require(positions[frontTop * 3 + 1] > positions[frontBottom * 3 + 1],
                "Le maillage vertical est inversé");
        require(uvs[frontTop * 2 + 1] < uvs[frontBottom * 2 + 1],
                "Les UV V5.2 sont encore tête en bas");

        int surfaceCount = rows * columns;
        require(positions[frontTop * 3 + 2] > 0.0f,
                "La face n'est pas devant");
        require(positions[(surfaceCount + frontTop) * 3 + 2] < 0.0f,
                "Le dos n'est pas derrière");

        for (float value : positions) {
            require(Float.isFinite(value), "Position non finie");
        }
        for (float value : uvs) {
            require(Float.isFinite(value) && value >= 0.0f && value <= 1.0f,
                    "UV hors atlas");
        }
        System.out.println("FaceBack25DMesherSelfTest OK");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
