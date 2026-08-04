package com.chasmet.modeliseur3d.model;

public final class NeuralConfidenceHullSelfTest {
    public static void main(String[] args) {
        testConfirmedThinDetailIsRetained();
        testSingleViewGhostIsRejected();
        System.out.println("NeuralConfidenceHullSelfTest V5.9.3 OK");
    }

    private static void testConfirmedThinDetailIsRetained() {
        int width = 28;
        int height = 36;
        int depth = 30;
        boolean[][] masks = masks(width, height, depth);
        fill(masks[0], width, 8, 5, 19, 31);
        fill(masks[2], width, 8, 5, 19, 31);
        fill(masks[1], depth, 9, 5, 20, 31);
        fill(masks[3], depth, 9, 5, 20, 31);

        // Manche fin confirmé sur l'axe face/dos et sur l'axe droite/gauche.
        fill(masks[0], width, 20, 16, 26, 17);
        fill(masks[2], width, 20, 16, 26, 17);
        fill(masks[1], depth, 21, 16, 28, 17);
        fill(masks[3], depth, 21, 16, 28, 17);

        boolean[] volume = StylizedFourViewProjector.build(
                masks, width, height, depth, depth, true
        );
        int bodyOnlyEstimate = 12 * 27 * 12;
        if (StylizedFourViewProjector.countOccupied(volume) <= bodyOnlyEstimate) {
            throw new AssertionError("Le détail fin confirmé par les quatre vues a disparu");
        }
    }

    private static void testSingleViewGhostIsRejected() {
        int width = 28;
        int height = 36;
        int depth = 30;
        boolean[][] masks = masks(width, height, depth);
        fill(masks[0], width, 8, 5, 19, 31);
        fill(masks[2], width, 8, 5, 19, 31);
        fill(masks[1], depth, 9, 5, 20, 31);
        fill(masks[3], depth, 9, 5, 20, 31);

        // Grande tache parasite visible uniquement sur la face.
        fill(masks[0], width, 1, 1, 5, 4);
        boolean[] withGhost = StylizedFourViewProjector.build(
                masks, width, height, depth, depth, true
        );

        boolean[][] clean = masks(width, height, depth);
        fill(clean[0], width, 8, 5, 19, 31);
        fill(clean[2], width, 8, 5, 19, 31);
        fill(clean[1], depth, 9, 5, 20, 31);
        fill(clean[3], depth, 9, 5, 20, 31);
        boolean[] withoutGhost = StylizedFourViewProjector.build(
                clean, width, height, depth, depth, true
        );

        int difference = Math.abs(
                StylizedFourViewProjector.countOccupied(withGhost)
                        - StylizedFourViewProjector.countOccupied(withoutGhost)
        );
        if (difference > 24) {
            throw new AssertionError("Une vue isolée a créé un volume fantôme");
        }
    }

    private static boolean[][] masks(int width, int height, int depth) {
        return new boolean[][]{
                new boolean[width * height],
                new boolean[depth * height],
                new boolean[width * height],
                new boolean[depth * height]
        };
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
