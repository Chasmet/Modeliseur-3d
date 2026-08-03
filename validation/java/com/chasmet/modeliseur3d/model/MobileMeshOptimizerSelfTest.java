package com.chasmet.modeliseur3d.model;

public final class MobileMeshOptimizerSelfTest {
    private MobileMeshOptimizerSelfTest() {
    }

    public static void main(String[] args) {
        MeshData cube = createCube();
        MeshData simplified = MobileMeshOptimizer.simplify(cube, 4);

        require(simplified.getTriangleCount() > 0, "aucun triangle conservé");
        require(simplified.getTriangleCount() <= 4, "budget de triangles dépassé");
        require(simplified.getVertexCount() <= 12, "trop de sommets mobiles");
        for (int index : simplified.getIndices()) {
            require(index >= 0, "indice négatif");
            require(index < simplified.getVertexCount(), "indice hors limite");
        }

        MeshData complete = MobileMeshOptimizer.simplify(cube, 100);
        require(complete.getTriangleCount() == cube.getTriangleCount(),
                "le maillage complet n'est pas conservé");

        System.out.println("MobileMeshOptimizerSelfTest : OK");
    }

    private static MeshData createCube() {
        float[] positions = {
                -1, -1, -1,
                1, -1, -1,
                1, 1, -1,
                -1, 1, -1,
                -1, -1, 1,
                1, -1, 1,
                1, 1, 1,
                -1, 1, 1
        };
        float[] normals = {
                -0.57f, -0.57f, -0.57f,
                0.57f, -0.57f, -0.57f,
                0.57f, 0.57f, -0.57f,
                -0.57f, 0.57f, -0.57f,
                -0.57f, -0.57f, 0.57f,
                0.57f, -0.57f, 0.57f,
                0.57f, 0.57f, 0.57f,
                -0.57f, 0.57f, 0.57f
        };
        float[] uvs = {
                0, 0,
                1, 0,
                1, 1,
                0, 1,
                0, 0,
                1, 0,
                1, 1,
                0, 1
        };
        int[] indices = {
                0, 1, 2, 0, 2, 3,
                4, 6, 5, 4, 7, 6,
                0, 4, 5, 0, 5, 1,
                3, 2, 6, 3, 6, 7,
                0, 3, 7, 0, 7, 4,
                1, 5, 6, 1, 6, 2
        };
        return new MeshData(positions, normals, uvs, indices);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
