package com.chasmet.modeliseur3d.cloud;

public final class MobileOptimizationPlanSelfTest {
    private MobileOptimizationPlanSelfTest() {
    }

    public static void main(String[] args) {
        assertTrue(MobileOptimizationPlan.count() >= 3, "presets adaptatifs");
        assertTrue(
                MobileOptimizationPlan.meetsTarget(200_000L),
                "200 000 octets acceptes"
        );
        assertTrue(
                !MobileOptimizationPlan.meetsTarget(200_001L),
                "depassement refuse"
        );
        int previousFaces = Integer.MAX_VALUE;
        int previousTexture = Integer.MAX_VALUE;
        for (int index = 0; index < MobileOptimizationPlan.count(); index++) {
            MobileOptimizationPlan.Preset preset =
                    MobileOptimizationPlan.at(index);
            assertTrue(
                    preset.getFaceLimit() < previousFaces,
                    "faces strictement decroissantes"
            );
            assertTrue(
                    preset.getTextureSize() <= previousTexture,
                    "textures non croissantes"
            );
            previousFaces = preset.getFaceLimit();
            previousTexture = preset.getTextureSize();
        }
        System.out.println("MobileOptimizationPlanSelfTest: OK");
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
