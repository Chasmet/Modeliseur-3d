package com.chasmet.modeliseur3d.model;

public final class MobileMeshOptimizerSelfTest {
    private MobileMeshOptimizerSelfTest() {
    }

    public static void main(String[] args) {
        MeshData cube = createCube();
        MeshData simplified = MobileMeshOptimizer.simplify(cube, 4);
        validate(simplified, 4);

        MeshData complete = MobileMeshOptimizer.simplify(cube, 100);
        require(complete == cube,
                "le maillage sous le budget doit être conservé sans copie");

        MeshData grid = createGrid(28, 28);
        MeshData clustered = MobileMeshOptimizer.simplify(grid, 260);
        validate(clustered, 260);
        require(clustered.getTriangleCount() >= 80,
                "la simplification a détruit trop de surface");
        require(boundsWidth(clustered) > 1.8f,
                "la largeur du modèle n'est pas conservée");

        System.out.println("MobileMeshOptimizerSelfTest : OK");
    }

    private static void validate(MeshData mesh, int maximumTriangles) {
        require(mesh.getTriangleCount() > 0, "aucun triangle conservé");
        require(mesh.getTriangleCount() <= maximumTriangles,
                "budget de triangles dépassé");
        for (int index : mesh.getIndices()) {
            require(index >= 0, "indice négatif");
            require(index < mesh.getVertexCount(), "indice hors limite");
        }
    }

    private static MeshData createGrid(int width, int height) {
        int vertices = width * height;
        float[] positions = new float[vertices * 3];
        float[] normals = new float[vertices * 3];
        float[] uvs = new float[vertices * 2];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int vertex = y * width + x;
                int p = vertex * 3;
                positions[p] = -1.0f + 2.0f * x / (width - 1.0f);
                positions[p + 1] = -1.0f + 2.0f * y / (height - 1.0f);
                positions[p + 2] = 0.08f * (float) Math.sin(x * 0.32)
                        * (float) Math.cos(y * 0.27);
                normals[p + 2] = 1.0f;
                int uv = vertex * 2;
                uvs[uv] = x / (width - 1.0f);
                uvs[uv + 1] = y / (height - 1.0f);
            }
        }
        int[] indices = new int[(width - 1) * (height - 1) * 6];
        int output = 0;
        for (int y = 0; y < height - 1; y++) {
            for (int x = 0; x < width - 1; x++) {
                int a = y * width + x;
                int b = a + 1;
                int c = a + width + 1;
                int d = a + width;
                indices[output++] = a;
                indices[output++] = b;
                indices[output++] = c;
                indices[output++] = a;
                indices[output++] = c;
                indices[output++] = d;
            }
        }
        return new MeshData(positions, normals, uvs, indices);
    }

    private static float boundsWidth(MeshData mesh) {
        float minimum = Float.POSITIVE_INFINITY;
        float maximum = Float.NEGATIVE_INFINITY;
        float[] positions = mesh.getPositions();
        for (int index = 0; index < positions.length; index += 3) {
            minimum = Math.min(minimum, positions[index]);
            maximum = Math.max(maximum, positions[index]);
        }
        return maximum - minimum;
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
