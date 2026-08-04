package com.chasmet.modeliseur3d.model;

public final class MeshOrientationCorrectorSelfTest {
    public static void main(String[] args) {
        float[] positions = {
                0.0f, 1.0f, 0.0f,
                1.0f, -1.0f, 0.0f,
                -1.0f, -1.0f, 0.0f
        };
        float[] normals = {
                0.0f, 0.75f, 0.25f,
                0.0f, -0.50f, 0.50f,
                0.0f, 0.25f, 0.75f
        };
        float[] uv = {
                0.10f, 0.02f,
                0.50f, 0.40f,
                0.90f, 0.98f
        };
        int[] indices = {0, 1, 2};
        MeshData source = new MeshData(positions, normals, uv, indices);
        MeshData corrected = MeshOrientationCorrector.correct(source);

        assertNear(corrected.getPositions()[1], 1.0f, "La géométrie ne doit pas tourner");
        assertNear(corrected.getNormals()[1], -0.75f, "Normale Y supérieure");
        assertNear(corrected.getNormals()[4], 0.50f, "Normale Y inférieure");
        assertNear(corrected.getTexCoords()[1], 0.98f, "UV haut");
        assertNear(corrected.getTexCoords()[3], 0.60f, "UV milieu");
        assertNear(corrected.getTexCoords()[5], 0.02f, "UV bas");

        if (source.getTexCoords()[1] != 0.02f) {
            throw new AssertionError("Le maillage source a été modifié");
        }
        System.out.println("MeshOrientationCorrectorSelfTest OK");
    }

    private static void assertNear(float actual, float expected, String label) {
        if (Math.abs(actual - expected) > 0.0001f) {
            throw new AssertionError(label + " : " + actual + " au lieu de " + expected);
        }
    }
}
