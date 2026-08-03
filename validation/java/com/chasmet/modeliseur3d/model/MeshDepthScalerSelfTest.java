package com.chasmet.modeliseur3d.model;

public final class MeshDepthScalerSelfTest {
    private MeshDepthScalerSelfTest() {
    }

    public static void main(String[] arguments) {
        MeshData source = new MeshData(
                new float[]{
                        -1.0f, 0.0f, 0.50f,
                        1.0f, 0.0f, 0.50f,
                        0.0f, 1.0f, -0.50f
                },
                new float[]{
                        0.0f, 0.0f, 1.0f,
                        0.0f, 0.0f, 1.0f,
                        0.0f, 0.0f, 1.0f
                },
                new float[]{
                        0.0f, 0.0f,
                        1.0f, 0.0f,
                        0.5f, 1.0f
                },
                new int[]{0, 1, 2}
        );

        MeshData doubled = MeshDepthScaler.scaleDepth(source, 2.0f);
        assertClose(doubled.getPositions()[2], 1.0f, "profondeur avant");
        assertClose(doubled.getPositions()[8], -1.0f, "profondeur arrière");
        assertClose(doubled.getPositions()[0], -1.0f, "largeur inchangée");
        if (doubled.getTexCoords()[4] != source.getTexCoords()[4]) {
            throw new AssertionError("Les UV ne doivent pas changer");
        }

        MeshData minimum = MeshDepthScaler.scaleDepth(source, 0.10f);
        assertClose(minimum.getPositions()[2], 0.25f, "limite minimale");

        MeshData maximum = MeshDepthScaler.scaleDepth(source, 8.0f);
        assertClose(maximum.getPositions()[2], 1.0f, "limite maximale");

        System.out.println("Réglage de profondeur Face/Dos validé");
    }

    private static void assertClose(float actual, float expected, String name) {
        if (Math.abs(actual - expected) > 0.0001f) {
            throw new AssertionError(
                    name + " incorrect : " + actual + " au lieu de " + expected
            );
        }
    }
}
