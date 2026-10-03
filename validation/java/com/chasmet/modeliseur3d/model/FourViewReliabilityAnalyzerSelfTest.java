package com.chasmet.modeliseur3d.model;

/** Régression du profil presque vide observé dans le GLB V5.9.7 fourni. */
public final class FourViewReliabilityAnalyzerSelfTest {
    private FourViewReliabilityAnalyzerSelfTest() {
    }

    public static void main(String[] args) {
        testRejectsCollapsedRightProfile();
        testKeepsTwoLegitimateProfiles();
        testMirroredRepairPreservesPixels();
        System.out.println("FourViewReliabilityAnalyzerSelfTest V6.1 OK");
    }

    private static void testRejectsCollapsedRightProfile() {
        int width = 96;
        int height = 128;
        boolean[] collapsed = new boolean[width * height];
        boolean[] complete = new boolean[width * height];

        // Environ 9 % de couverture contre 44 %, comme dans l'atlas du GLB.
        fill(collapsed, width, 43, 4, 51, 123);
        fill(complete, width, 18, 4, 77, 123);
        fill(complete, width, 10, 72, 85, 118);

        FourViewReliabilityAnalyzer.PairAssessment result =
                FourViewReliabilityAnalyzer.assessPair(
                        collapsed,
                        complete,
                        width,
                        height
                );
        if (result.getReplacement()
                != FourViewReliabilityAnalyzer.Replacement.FIRST_FROM_SECOND) {
            throw new AssertionError(
                    "Le profil effondré n'a pas été remplacé : "
                            + result.getReplacement()
            );
        }
        if (result.getAreaRatio() >= 0.42) {
            throw new AssertionError("Le rapport de surface de régression est faux");
        }
        if (result.getConfidence() < 0.45) {
            throw new AssertionError("La récupération manque de confiance");
        }
    }

    private static void testKeepsTwoLegitimateProfiles() {
        int width = 96;
        int height = 128;
        boolean[] right = new boolean[width * height];
        boolean[] left = new boolean[width * height];
        fill(right, width, 25, 7, 69, 120);
        fill(left, width, 27, 7, 71, 120);
        fill(right, width, 17, 57, 78, 103);
        fill(left, width, 19, 57, 80, 103);

        FourViewReliabilityAnalyzer.PairAssessment result =
                FourViewReliabilityAnalyzer.assessPair(
                        right,
                        left,
                        width,
                        height
                );
        if (result.requiresReplacement()) {
            throw new AssertionError("Deux profils valides ont été modifiés");
        }
    }

    private static void testMirroredRepairPreservesPixels() {
        int width = 12;
        int height = 8;
        boolean[] source = new boolean[width * height];
        source[2 * width + 1] = true;
        source[5 * width + 4] = true;
        boolean[] mirrored = FourViewReliabilityAnalyzer.mirroredCopy(
                source,
                width,
                height
        );
        if (!mirrored[2 * width + 10] || !mirrored[5 * width + 7]) {
            throw new AssertionError("La copie miroir déplace mal le profil");
        }
        int count = 0;
        for (boolean value : mirrored) {
            count += value ? 1 : 0;
        }
        if (count != 2) {
            throw new AssertionError("La copie miroir a changé la silhouette");
        }
    }

    private static void fill(
            boolean[] mask,
            int width,
            int left,
            int top,
            int right,
            int bottom
    ) {
        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) {
                mask[y * width + x] = true;
            }
        }
    }
}
