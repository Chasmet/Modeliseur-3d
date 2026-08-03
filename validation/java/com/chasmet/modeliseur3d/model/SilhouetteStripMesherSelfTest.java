package com.chasmet.modeliseur3d.model;

public final class SilhouetteStripMesherSelfTest {
    private SilhouetteStripMesherSelfTest() {
    }

    public static void main(String[] args) {
        int views = 8;
        int rows = 72;
        int sectors = 32;
        float[][] left = new float[views][rows];
        float[][] right = new float[views][rows];

        for (int view = 0; view < views; view++) {
            double angle = Math.PI * 2.0 * view / views;
            float projectedRadius = (float) Math.sqrt(
                    0.34f * 0.34f * Math.cos(angle) * Math.cos(angle)
                            + 0.22f * 0.22f * Math.sin(angle) * Math.sin(angle)
            );
            for (int row = 0; row < rows; row++) {
                float v = row / (float) (rows - 1);
                float body = 0.42f + 0.58f * (float) Math.sin(Math.PI * v);
                float center = 0.04f * (float) Math.sin(Math.PI * v);
                left[view][row] = center - projectedRadius * body;
                right[view][row] = center + projectedRadius * body;
            }
        }

        SilhouetteStripMesher.Sweep sweep = SilhouetteStripMesher.build(
                left,
                right,
                sectors
        );
        MeshData mesh = sweep.getMesh();
        int expectedTriangles = (rows - 1) * sectors * 2 + sectors * 2;
        require(mesh.getTriangleCount() == expectedTriangles, "triangles");
        require(mesh.getVertexCount() == rows * (sectors + 1) + 2, "vertices");
        require(sweep.getSurfaceSampleCount() == rows * sectors, "samples");

        for (float value : mesh.getPositions()) {
            require(Float.isFinite(value), "position non finie");
        }
        for (float value : mesh.getNormals()) {
            require(Float.isFinite(value), "normale non finie");
        }
        for (int index : mesh.getIndices()) {
            require(index >= 0 && index < mesh.getVertexCount(), "indice");
        }
        float front = sweep.sampleX(0.0f, 0.5f);
        float side = sweep.sampleZ(0.25f, 0.5f);
        require(front > 0.15f, "largeur avant");
        require(side > 0.10f, "profondeur côté");
        System.out.println("SilhouetteStripMesherSelfTest OK");
    }

    private static void require(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }
}
