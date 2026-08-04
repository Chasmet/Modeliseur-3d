package com.chasmet.modeliseur3d.model;

public final class StylizedCharacter3DSelfTest {
    public static void main(String[] args) {
        testSeparatedPartsArePreserved();
        testFourViewHull();
        System.out.println("StylizedCharacter3DSelfTest OK");
    }

    private static void testSeparatedPartsArePreserved() {
        int width = 20;
        int height = 20;
        boolean[] mask = new boolean[width * height];
        fill(mask, width, 7, 3, 12, 14);
        fill(mask, width, 3, 6, 4, 11);
        fill(mask, width, 15, 6, 16, 11);
        fill(mask, width, 7, 16, 8, 19);
        fill(mask, width, 11, 16, 12, 19);
        fill(mask, width, 17, 8, 18, 13);

        boolean[] cleaned = StylizedMaskTopology.clean(mask, width, height, 12);
        int components = StylizedMaskTopology.countComponents(cleaned, width, height);
        if (components < 5) {
            throw new AssertionError("Les membres/accessoires séparés ont été fusionnés ou supprimés");
        }
        if (!cleaned[10 * width + 17]) {
            throw new AssertionError("L'accessoire détaché n'a pas été conservé");
        }
    }

    private static void testFourViewHull() {
        int width = 24;
        int height = 32;
        int depth = 20;
        boolean[][] masks = new boolean[4][width * height];
        for (boolean[] mask : masks) {
            fill(mask, width, 7, 4, 16, 27);
        }
        boolean[] strict = StylizedFourViewProjector.build(
                masks, width, height, depth, false
        );
        int occupied = StylizedFourViewProjector.countOccupied(strict);
        if (occupied <= 0) {
            throw new AssertionError("Le volume strict est vide");
        }

        masks[StylizedFourViewProjector.LEFT][10 * width + 12] = false;
        boolean[] tolerant = StylizedFourViewProjector.build(
                masks, width, height, depth, true
        );
        if (StylizedFourViewProjector.countOccupied(tolerant) < occupied / 2) {
            throw new AssertionError("Le mode tolérant supprime trop de volume");
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
