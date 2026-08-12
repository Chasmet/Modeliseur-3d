package com.chasmet.modeliseur3d.model;

/** Tests géométriques JVM du projecteur multivue continu V6. */
public final class ContinuousVisualHullSelfTest {
    public static void main(String[] args) {
        testContinuousRoundedCrossSection();
        testLocalDepthForLimbs();
        testWideVehicleDoesNotBecomeFlatSheets();
        testAdaptiveRecoveryNeedsTwoAxes();
        System.out.println("ContinuousVisualHullSelfTest V6 OK");
    }

    private static void testContinuousRoundedCrossSection() {
        int width = 25;
        int height = 32;
        int depth = 21;
        boolean[][] masks = createMasks(width, height, depth);
        fill(masks[0], width, 5, 3, 19, 28);
        fillMirrored(masks[2], width, 5, 3, 19, 28);
        fill(masks[1], depth, 4, 3, 16, 28);
        fillMirrored(masks[3], depth, 4, 3, 16, 28);

        ContinuousVisualHull.Result result = ContinuousVisualHull.build(
                confidence(masks), masks, width, height, depth, false
        );
        float[] density = result.getDensity();
        float center = density[index(12, 16, 10, width, depth)];
        float artificialCorner = density[index(5, 16, 4, width, depth)];
        if (center < 0.90f) {
            throw new AssertionError("Le centre du volume continu est trop faible");
        }
        if (artificialCorner >= 0.50f) {
            throw new AssertionError("La section est encore un bloc rectangulaire");
        }
        if (result.getRoundedVoxels() <= 0) {
            throw new AssertionError("Aucun coin artificiel n'a été arrondi");
        }
        if (result.getFractionalSamples() <= 0) {
            throw new AssertionError("Le champ a perdu ses valeurs sous-pixel");
        }
        if (result.getSilhouetteScore() < 0.90) {
            throw new AssertionError(
                    "Les silhouettes ont été trop modifiées : "
                            + result.getSilhouetteScore()
            );
        }
    }

    private static void testLocalDepthForLimbs() {
        int width = 32;
        int height = 42;
        int depth = 24;
        boolean[][] masks = createMasks(width, height, depth);

        // Tête et torse.
        fill(masks[0], width, 10, 4, 21, 24);
        fillMirrored(masks[2], width, 10, 4, 21, 24);
        // Bras réellement séparés.
        fill(masks[0], width, 3, 10, 5, 21);
        fill(masks[0], width, 26, 10, 28, 21);
        fillMirrored(masks[2], width, 3, 10, 5, 21);
        fillMirrored(masks[2], width, 26, 10, 28, 21);
        // Deux jambes avec une séparation nette.
        fill(masks[0], width, 10, 25, 14, 38);
        fill(masks[0], width, 17, 25, 21, 38);
        fillMirrored(masks[2], width, 10, 25, 14, 38);
        fillMirrored(masks[2], width, 17, 25, 21, 38);
        fill(masks[1], depth, 5, 4, 18, 38);
        fillMirrored(masks[3], depth, 5, 4, 18, 38);

        ContinuousVisualHull.Result result = ContinuousVisualHull.build(
                confidence(masks), masks, width, height, depth, false
        );
        boolean[] occupancy = result.getOccupancy();
        int armDepth = depthCount(occupancy, width, depth, 4, 16);
        int torsoDepth = depthCount(occupancy, width, depth, 15, 16);
        int legDepth = depthCount(occupancy, width, depth, 12, 31);
        if (armDepth <= 0 || armDepth >= torsoDepth) {
            throw new AssertionError("Le bras n'a pas une profondeur locale crédible");
        }
        if (legDepth <= 0 || legDepth >= torsoDepth) {
            throw new AssertionError("La jambe n'a pas été distinguée du torse");
        }
        if (depthCount(occupancy, width, depth, 15, 31) != 0
                || depthCount(occupancy, width, depth, 16, 31) != 0) {
            throw new AssertionError("L'espace réel entre les jambes a été rebouché");
        }
    }

    private static void testAdaptiveRecoveryNeedsTwoAxes() {
        int width = 32;
        int height = 36;
        int depth = 24;
        boolean[][] masks = createMasks(width, height, depth);
        fill(masks[0], width, 10, 4, 21, 31);
        fillMirrored(masks[2], width, 10, 4, 21, 31);
        fill(masks[1], depth, 6, 4, 17, 31);
        fillMirrored(masks[3], depth, 6, 4, 17, 31);

        // Une manche/accessoire confirmée par la face et le profil droit,
        // mais cachée dans le dos et le profil gauche.
        fill(masks[0], width, 22, 14, 27, 17);
        fill(masks[1], depth, 18, 14, 22, 17);

        ContinuousVisualHull.Result strict = ContinuousVisualHull.build(
                confidence(masks), masks, width, height, depth, false
        );
        ContinuousVisualHull.Result adaptive = ContinuousVisualHull.build(
                confidence(masks), masks, width, height, depth, true
        );
        if (adaptive.getOccupiedVoxels() <= strict.getOccupiedVoxels()) {
            throw new AssertionError("Le détail confirmé par deux axes a disparu");
        }
        if (adaptive.getAdaptivelyRecoveredVoxels() <= 0) {
            throw new AssertionError("La récupération adaptative n'est pas mesurée");
        }
        if (adaptive.getSilhouetteScore() < 0.82) {
            throw new AssertionError("Le mode adaptatif crée trop de volume fantôme");
        }
    }

    private static void testWideVehicleDoesNotBecomeFlatSheets() {
        int width = 40;
        int height = 48;
        int depth = 32;
        boolean[][] masks = createMasks(width, height, depth);

        // Conducteur étroit au-dessus d'un kart large en trois volumes.
        fill(masks[0], width, 15, 4, 24, 23);
        fill(masks[0], width, 10, 11, 12, 18);
        fill(masks[0], width, 27, 11, 29, 18);
        fill(masks[0], width, 3, 24, 10, 43);
        fill(masks[0], width, 12, 24, 27, 43);
        fill(masks[0], width, 29, 24, 36, 43);
        fillMirrored(masks[2], width, 15, 4, 24, 23);
        fillMirrored(masks[2], width, 10, 11, 12, 18);
        fillMirrored(masks[2], width, 27, 11, 29, 18);
        fillMirrored(masks[2], width, 3, 24, 10, 43);
        fillMirrored(masks[2], width, 12, 24, 27, 43);
        fillMirrored(masks[2], width, 29, 24, 36, 43);
        fill(masks[1], depth, 4, 4, 27, 43);
        fillMirrored(masks[3], depth, 4, 4, 27, 43);

        ContinuousVisualHull.Result result = ContinuousVisualHull.build(
                confidence(masks),
                masks,
                width,
                height,
                depth,
                false
        );
        if (!result.isComplexShapeMode()) {
            throw new AssertionError("Le kart large a été pris pour deux jambes");
        }
        boolean[] occupancy = result.getOccupancy();
        int wheelDepth = depthCount(occupancy, width, depth, 6, 34);
        int chassisDepth = depthCount(occupancy, width, depth, 20, 34);
        int armDepth = depthCount(occupancy, width, depth, 11, 15);
        if (wheelDepth < chassisDepth * 0.80f) {
            throw new AssertionError(
                    "La roue latérale est encore aplatie en feuille : "
                            + wheelDepth + "/" + chassisDepth
            );
        }
        if (armDepth <= 0 || armDepth >= chassisDepth * 0.82f) {
            throw new AssertionError(
                    "Le bras du conducteur est encore extrudé dans tout le véhicule : "
                            + armDepth + "/" + chassisDepth
            );
        }
    }

    private static boolean[][] createMasks(int width, int height, int depth) {
        return new boolean[][]{
                new boolean[width * height],
                new boolean[depth * height],
                new boolean[width * height],
                new boolean[depth * height]
        };
    }

    private static float[][] confidence(boolean[][] masks) {
        float[][] confidence = new float[masks.length][];
        for (int view = 0; view < masks.length; view++) {
            confidence[view] = new float[masks[view].length];
            for (int index = 0; index < masks[view].length; index++) {
                confidence[view][index] = masks[view][index] ? 1.0f : 0.0f;
            }
        }
        return confidence;
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

    private static void fillMirrored(
            boolean[] mask,
            int width,
            int left,
            int top,
            int right,
            int bottom
    ) {
        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) {
                mask[y * width + width - 1 - x] = true;
            }
        }
    }

    private static int depthCount(
            boolean[] volume,
            int width,
            int depth,
            int x,
            int y
    ) {
        int count = 0;
        int base = (y * width + x) * depth;
        for (int z = 0; z < depth; z++) {
            if (volume[base + z]) {
                count++;
            }
        }
        return count;
    }

    private static int index(int x, int y, int z, int width, int depth) {
        return (y * width + x) * depth + z;
    }
}
