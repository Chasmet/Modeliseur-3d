package com.chasmet.modeliseur3d.model;

/**
 * Projection volumique à quatre vues réelles.
 *
 * V5.9.3 : les silhouettes produites par l'IA locale IS-Net ne sont plus
 * fusionnées par une simple intersection rigide. Une carte de confiance locale
 * est calculée pour chaque vue, puis les quatre vues votent pour chaque voxel.
 * Les détails fins confirmés par deux axes sont mieux conservés tandis que les
 * volumes fantômes restent contenus par une enveloppe anatomique par ligne.
 */
public final class StylizedFourViewProjector {
    public static final int FRONT = 0;
    public static final int RIGHT = 1;
    public static final int BACK = 2;
    public static final int LEFT = 3;

    private StylizedFourViewProjector() {
    }

    /** Compatibilité avec les anciens tests où les quatre masques ont la même largeur. */
    public static boolean[] build(
            boolean[][] masks,
            int width,
            int height,
            int depth,
            boolean adaptive
    ) {
        return build(masks, width, height, depth, width, adaptive);
    }

    public static boolean[] build(
            boolean[][] masks,
            int width,
            int height,
            int depth,
            int sideWidth,
            boolean adaptive
    ) {
        if (sideWidth != depth) {
            throw new IllegalArgumentException(
                    "La largeur des profils doit correspondre à la profondeur"
            );
        }
        validate(masks, width, height, depth);
        NeuralConfidenceHullBuilder.Result neural = NeuralMaskConfidence.build(
                masks,
                width,
                height,
                depth,
                adaptive
        );
        return neural.getVolume();
    }

    public static int countOccupied(boolean[] volume) {
        int count = 0;
        if (volume != null) {
            for (boolean value : volume) {
                if (value) {
                    count++;
                }
            }
        }
        return count;
    }

    private static void validate(
            boolean[][] masks,
            int width,
            int height,
            int depth
    ) {
        if (masks == null || masks.length != 4) {
            throw new IllegalArgumentException("Quatre silhouettes sont requises");
        }
        if (width < 4 || height < 4 || depth < 4) {
            throw new IllegalArgumentException("Résolution volumique trop faible");
        }
        int frontExpected = width * height;
        int sideExpected = depth * height;
        if (masks[FRONT] == null || masks[FRONT].length != frontExpected
                || masks[BACK] == null || masks[BACK].length != frontExpected
                || masks[RIGHT] == null || masks[RIGHT].length != sideExpected
                || masks[LEFT] == null || masks[LEFT].length != sideExpected) {
            throw new IllegalArgumentException("Dimensions de silhouette incohérentes");
        }
    }
}
