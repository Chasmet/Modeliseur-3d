package com.chasmet.modeliseur3d.model;

public final class StylizedCharacter3DSelfTest {
    public static void main(String[] args) {
        testProfileMirrorCorrection();
        testIndependentSideResolution();
        testAdaptiveHullPreservesUncertainParts();
        System.out.println("StylizedCharacter3DSelfTest V5.8 OK");
    }

    private static void testProfileMirrorCorrection() {
        int width = 24;
        int height = 24;
        boolean[] right = new boolean[width * height];
        fill(right, width, 6, 4, 15, 19);
        fill(right, width, 16, 8, 21, 11);
        boolean[] wrongLeft = right.clone();
        FourViewAutoCorrector.ProfileCorrection correction =
                FourViewAutoCorrector.analyzeProfiles(right, wrongLeft, width, height);
        if (!correction.shouldFlipLeft()) {
            throw new AssertionError("Le profil gauche non inversé devait être corrigé");
        }
        boolean[] trueLeft = FourViewAutoCorrector.flipHorizontal(right, width, height);
        correction = FourViewAutoCorrector.analyzeProfiles(right, trueLeft, width, height);
        if (correction.shouldFlipLeft()) {
            throw new AssertionError("Un vrai couple droite/gauche ne doit pas être retourné");
        }
    }

    private static void testIndependentSideResolution() {
        int width = 20;
        int height = 32;
        int depth = 42;
        boolean[][] masks = new boolean[4][];
        masks[StylizedFourViewProjector.FRONT] = new boolean[width * height];
        masks[StylizedFourViewProjector.BACK] = new boolean[width * height];
        masks[StylizedFourViewProjector.RIGHT] = new boolean[depth * height];
        masks[StylizedFourViewProjector.LEFT] = new boolean[depth * height];
        fill(masks[0], width, 6, 3, 13, 28);
        fill(masks[2], width, 6, 3, 13, 28);
        fill(masks[1], depth, 3, 3, 38, 28);
        fill(masks[3], depth, 3, 3, 38, 28);
        boolean[] volume = StylizedFourViewProjector.build(
                masks, width, height, depth, depth, false
        );
        if (StylizedFourViewProjector.countOccupied(volume) <= 0) {
            throw new AssertionError("Le volume à profondeur indépendante est vide");
        }
    }

    private static void testAdaptiveHullPreservesUncertainParts() {
        int width = 24;
        int height = 32;
        int depth = 30;
        boolean[][] masks = new boolean[4][];
        masks[0] = new boolean[width * height];
        masks[2] = new boolean[width * height];
        masks[1] = new boolean[depth * height];
        masks[3] = new boolean[depth * height];
        fill(masks[0], width, 7, 4, 16, 27);
        fill(masks[2], width, 7, 4, 16, 27);
        fill(masks[1], depth, 8, 4, 21, 27);
        fill(masks[3], depth, 8, 4, 21, 27);
        // Accessoire visible seulement en face et à droite.
        fill(masks[0], width, 17, 12, 22, 14);
        fill(masks[1], depth, 22, 12, 28, 14);
        int strict = StylizedFourViewProjector.countOccupied(
                StylizedFourViewProjector.build(masks, width, height, depth, depth, false)
        );
        int adaptive = StylizedFourViewProjector.countOccupied(
                StylizedFourViewProjector.build(masks, width, height, depth, depth, true)
        );
        if (adaptive <= strict) {
            throw new AssertionError("Le mode adaptatif n'a pas conservé l'accessoire incertain");
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
