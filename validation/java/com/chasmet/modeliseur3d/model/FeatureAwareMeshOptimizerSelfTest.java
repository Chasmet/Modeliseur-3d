package com.chasmet.modeliseur3d.model;

/** Pure Java regression tests for category-aware mesh finishing. */
public final class FeatureAwareMeshOptimizerSelfTest {
    private FeatureAwareMeshOptimizerSelfTest() {
    }

    public static void main(String[] args) {
        rigidObjectsMoveLessThanCharacters();
        compositeProtectsLowerVehicleLayer();
        preservesUvsAndTopology();
        System.out.println("FeatureAwareMeshOptimizerSelfTest: OK");
    }

    private static void rigidObjectsMoveLessThanCharacters() {
        MeshData source = noisyGrid();
        MeshData rigid = FeatureAwareMeshOptimizer.optimize(
                source, 2, SubjectCategory.ARCHITECTURE_OBJECT
        );
        MeshData character = FeatureAwareMeshOptimizer.optimize(
                source, 2, SubjectCategory.CHARACTER
        );
        double rigidMove = meanDisplacement(source, rigid, 0, source.getVertexCount());
        double characterMove = meanDisplacement(source, character, 0, source.getVertexCount());
        check(rigidMove < characterMove * 0.55,
                "Rigid objects must preserve edges substantially more than characters");
    }

    private static void compositeProtectsLowerVehicleLayer() {
        MeshData source = noisyGrid();
        MeshData composite = FeatureAwareMeshOptimizer.optimize(
                source, 2, SubjectCategory.COMPOSITE_VEHICLE
        );
        int columns = 4;
        double lower = meanDisplacement(source, composite, 0, columns * 2);
        double upper = meanDisplacement(source, composite, columns * 2, columns * 4);
        check(lower < upper * 0.55,
                "The lower chassis layer must move far less than the upper driver");
    }

    private static void preservesUvsAndTopology() {
        MeshData source = noisyGrid();
        MeshData optimized = FeatureAwareMeshOptimizer.optimize(
                source, 2, SubjectCategory.ANIMAL
        );
        check(optimized.getIndices().length == source.getIndices().length,
                "Triangle topology must stay unchanged");
        check(optimized.getTexCoords().length == source.getTexCoords().length,
                "UV count must stay unchanged");
        for (int i = 0; i < source.getTexCoords().length; i++) {
            check(optimized.getTexCoords()[i] == source.getTexCoords()[i],
                    "UV coordinates must not move");
        }
        for (float value : optimized.getPositions()) {
            check(Float.isFinite(value), "Optimized position must remain finite");
        }
    }

    private static MeshData noisyGrid() {
        int columns = 4;
        int rows = 4;
        int vertices = columns * rows;
        float[] positions = new float[vertices * 3];
        float[] normals = new float[vertices * 3];
        float[] uvs = new float[vertices * 2];
        int cursor = 0;
        for (int row = 0; row < rows; row++) {
            float y = -1.0f + row * (2.0f / (rows - 1));
            for (int column = 0; column < columns; column++) {
                float x = -1.0f + column * (2.0f / (columns - 1));
                float z = ((row + column) & 1) == 0 ? 0.12f : -0.12f;
                int p = cursor * 3;
                positions[p] = x;
                positions[p + 1] = y;
                positions[p + 2] = z;
                normals[p + 2] = 1.0f;
                int uv = cursor * 2;
                uvs[uv] = column / (float) (columns - 1);
                uvs[uv + 1] = row / (float) (rows - 1);
                cursor++;
            }
        }
        int[] indices = new int[(columns - 1) * (rows - 1) * 6];
        int index = 0;
        for (int row = 0; row < rows - 1; row++) {
            for (int column = 0; column < columns - 1; column++) {
                int a = row * columns + column;
                int b = a + 1;
                int c = a + columns;
                int d = c + 1;
                indices[index++] = a;
                indices[index++] = c;
                indices[index++] = b;
                indices[index++] = b;
                indices[index++] = c;
                indices[index++] = d;
            }
        }
        return new MeshData(positions, normals, uvs, indices);
    }

    private static double meanDisplacement(
            MeshData source,
            MeshData optimized,
            int firstVertex,
            int endVertex
    ) {
        double total = 0.0;
        int count = 0;
        for (int vertex = firstVertex; vertex < endVertex; vertex++) {
            int p = vertex * 3;
            double dx = optimized.getPositions()[p] - source.getPositions()[p];
            double dy = optimized.getPositions()[p + 1] - source.getPositions()[p + 1];
            double dz = optimized.getPositions()[p + 2] - source.getPositions()[p + 2];
            total += Math.sqrt(dx * dx + dy * dy + dz * dz);
            count++;
        }
        return total / Math.max(1, count);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
