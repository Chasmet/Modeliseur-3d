package com.chasmet.modeliseur3d.model;

public final class FastMobileMeshOptimizerSelfTest {
    public static void main(String[] args) {
        MeshData source = createGrid(36, 24);
        long started = System.nanoTime();
        MeshData simplified = FastMobileMeshOptimizer.simplify(source, 420);
        long durationMs = (System.nanoTime() - started) / 1_000_000L;

        if (simplified.getTriangleCount() <= 0) {
            throw new AssertionError("La simplification rapide a supprimé tout le maillage");
        }
        if (simplified.getTriangleCount() > 420) {
            throw new AssertionError("Le budget de 420 triangles n'est pas respecté");
        }
        if (simplified.getNormals().length != simplified.getPositions().length) {
            throw new AssertionError("Les normales ne correspondent plus aux sommets");
        }
        if (simplified.getTexCoords().length != simplified.getVertexCount() * 2) {
            throw new AssertionError("Les coordonnées UV sont invalides");
        }
        System.out.println(
                "FastMobileMeshOptimizerSelfTest OK : "
                        + source.getTriangleCount() + " -> "
                        + simplified.getTriangleCount() + " triangles en "
                        + durationMs + " ms"
        );
    }

    private static MeshData createGrid(int columns, int rows) {
        int vertexColumns = columns + 1;
        int vertexRows = rows + 1;
        int vertexCount = vertexColumns * vertexRows;
        float[] positions = new float[vertexCount * 3];
        float[] normals = new float[vertexCount * 3];
        float[] uvs = new float[vertexCount * 2];

        for (int y = 0; y < vertexRows; y++) {
            for (int x = 0; x < vertexColumns; x++) {
                int vertex = y * vertexColumns + x;
                int position = vertex * 3;
                int uv = vertex * 2;
                positions[position] = x / (float) columns - 0.5f;
                positions[position + 1] = y / (float) rows - 0.5f;
                positions[position + 2] = (float) Math.sin(x * 0.23f)
                        * (float) Math.cos(y * 0.19f) * 0.08f;
                normals[position] = 0.0f;
                normals[position + 1] = 0.0f;
                normals[position + 2] = 1.0f;
                uvs[uv] = x / (float) columns;
                uvs[uv + 1] = y / (float) rows;
            }
        }

        int[] indices = new int[columns * rows * 6];
        int output = 0;
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < columns; x++) {
                int a = y * vertexColumns + x;
                int b = a + 1;
                int c = a + vertexColumns;
                int d = c + 1;
                indices[output++] = a;
                indices[output++] = c;
                indices[output++] = b;
                indices[output++] = b;
                indices[output++] = c;
                indices[output++] = d;
            }
        }
        return new MeshData(positions, normals, uvs, indices);
    }
}
