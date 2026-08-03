package com.chasmet.modeliseur3d.model;

public final class Relief25DMesherSelfTest {
    private Relief25DMesherSelfTest() {
    }

    public static void main(String[] args) {
        int rows = 48;
        float[] left = new float[rows];
        float[] right = new float[rows];
        for (int row = 0; row < rows; row++) {
            float amount = row / (float) (rows - 1);
            float width = 0.18f
                    + 0.42f * (float) Math.sin(Math.PI * amount);
            left[row] = -width;
            right[row] = width;
        }
        Relief25DMesher.AtlasLayout atlas =
                new Relief25DMesher.AtlasLayout(512, 768, 4);
        Relief25DMesher.BuildResult result = Relief25DMesher.build(
                left,
                right,
                0.05f,
                0.95f,
                0.70f,
                0.11f,
                18,
                atlas
        );
        MeshData mesh = result.getMesh();
        require(mesh.getVertexCount() > rows * 18 * 2, "bords absents");
        require(mesh.getTriangleCount() > 1000, "maillage trop pauvre");
        require(mesh.getPositions().length / 3 == mesh.getVertexCount(), "positions");
        require(mesh.getNormals().length == mesh.getPositions().length, "normales");
        require(mesh.getTexCoords().length == mesh.getVertexCount() * 2, "uv");
        for (int index : mesh.getIndices()) {
            require(index >= 0 && index < mesh.getVertexCount(), "indice invalide");
        }
        for (float uv : mesh.getTexCoords()) {
            require(Float.isFinite(uv) && uv >= -0.001f && uv <= 1.001f, "uv hors atlas");
        }
        float maximumZ = 0.0f;
        for (int index = 2; index < mesh.getPositions().length; index += 3) {
            maximumZ = Math.max(maximumZ, Math.abs(mesh.getPositions()[index]));
        }
        require(maximumZ >= 0.08f && maximumZ <= 0.13f, "épaisseur incorrecte");
        System.out.println(
                "Relief25DMesherSelfTest OK — "
                        + mesh.getTriangleCount()
                        + " triangles"
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
