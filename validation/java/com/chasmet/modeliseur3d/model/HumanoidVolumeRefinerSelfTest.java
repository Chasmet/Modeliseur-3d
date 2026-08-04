package com.chasmet.modeliseur3d.model;

public final class HumanoidVolumeRefinerSelfTest {
    public static void main(String[] args) {
        testLocalDepthAndStablePlacement();
        testTinyNoiseIsRemoved();
        System.out.println("HumanoidVolumeRefinerSelfTest V5.9.7 OK");
    }

    private static void testLocalDepthAndStablePlacement() {
        int width = 30;
        int height = 40;
        int depth = 20;
        boolean[][] masks = createMasks(width, height, depth);

        // Tête + torse central.
        fill(masks[0], width, 10, 4, 19, 24);
        fillMirroredBack(masks[2], width, 10, 4, 19, 24);
        // Deux bras fins clairement séparés du torse.
        fill(masks[0], width, 3, 10, 5, 20);
        fill(masks[0], width, 24, 10, 26, 20);
        fillMirroredBack(masks[2], width, 3, 10, 5, 20);
        fillMirroredBack(masks[2], width, 24, 10, 26, 20);
        // Deux jambes avec un écart d'un voxel.
        fill(masks[0], width, 10, 25, 14, 37);
        fill(masks[0], width, 16, 25, 20, 37);
        fillMirroredBack(masks[2], width, 10, 25, 14, 37);
        fillMirroredBack(masks[2], width, 16, 25, 20, 37);

        fill(masks[1], depth, 4, 4, 15, 37);
        fillMirroredBack(masks[3], depth, 4, 4, 15, 37);

        boolean[] source = buildSourceFromMasks(masks, width, height, depth);
        boolean[] sourceCopy = source.clone();
        int armBefore = countDepth(source, width, depth, 4, 15);
        int torsoBefore = countDepth(source, width, depth, 14, 15);
        if (armBefore != torsoBefore || armBefore < 8) {
            throw new AssertionError("Le volume de test initial est incohérent");
        }

        HumanoidVolumeRefiner.Result result = HumanoidVolumeRefiner.refine(
                source,
                masks,
                width,
                height,
                depth
        );
        boolean[] refined = result.getVolume();
        int armAfter = countDepth(refined, width, depth, 4, 15);
        int torsoAfter = countDepth(refined, width, depth, 14, 15);

        if (armAfter >= torsoAfter) {
            throw new AssertionError("Le bras fin n'a pas reçu une profondeur locale plus faible");
        }
        if (torsoAfter < Math.round(torsoBefore * 0.80f)) {
            throw new AssertionError("Le torse central a été trop aminci");
        }
        if (!rowHasVoxel(refined, width, depth, 4)
                || !rowHasVoxel(refined, width, depth, 37)) {
            throw new AssertionError("La hauteur ou le placement vertical a changé");
        }
        if (columnHasVoxel(refined, width, depth, 14, 30)
                || columnHasVoxel(refined, width, depth, 16, 30)) {
            throw new AssertionError("La séparation entre les jambes n'a pas été protégée");
        }
        for (int index = 0; index < refined.length; index++) {
            if (refined[index] && !sourceCopy[index]) {
                throw new AssertionError("Le raffinement a créé un voxel hors du volume stable");
            }
        }
        if (result.getPrunedDepthVoxels() <= 0) {
            throw new AssertionError("Aucune profondeur locale n'a été corrigée");
        }
        if (result.getProtectedLegGaps() <= 0) {
            throw new AssertionError("L'écart entre les jambes n'a pas été détecté");
        }
    }

    private static void testTinyNoiseIsRemoved() {
        int width = 20;
        int height = 24;
        int depth = 16;
        boolean[][] masks = createMasks(width, height, depth);
        fill(masks[0], width, 6, 3, 13, 21);
        fillMirroredBack(masks[2], width, 6, 3, 13, 21);
        fill(masks[1], depth, 4, 3, 11, 21);
        fillMirroredBack(masks[3], depth, 4, 3, 11, 21);
        boolean[] source = buildSourceFromMasks(masks, width, height, depth);

        // Petit fragment volontairement isolé du personnage principal.
        for (int z = 1; z <= 4; z++) {
            source[(1 * width + 1) * depth + z] = true;
        }

        HumanoidVolumeRefiner.Result result = HumanoidVolumeRefiner.refine(
                source,
                masks,
                width,
                height,
                depth
        );
        for (int z = 1; z <= 4; z++) {
            if (result.getVolume()[(1 * width + 1) * depth + z]) {
                throw new AssertionError("Un petit fragment isolé a été conservé");
            }
        }
        if (result.getRemovedComponents() < 1) {
            throw new AssertionError("Le composant parasite n'a pas été comptabilisé");
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

    private static boolean[] buildSourceFromMasks(
            boolean[][] masks,
            int width,
            int height,
            int depth
    ) {
        boolean[] volume = new boolean[width * height * depth];
        for (int y = 0; y < height; y++) {
            int frontRow = y * width;
            int sideRow = y * depth;
            for (int x = 0; x < width; x++) {
                boolean front = masks[0][frontRow + x]
                        || masks[2][frontRow + width - 1 - x];
                if (!front) {
                    continue;
                }
                for (int z = 0; z < depth; z++) {
                    boolean side = masks[1][sideRow + z]
                            || masks[3][sideRow + depth - 1 - z];
                    if (side) {
                        volume[(y * width + x) * depth + z] = true;
                    }
                }
            }
        }
        return volume;
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

    private static void fillMirroredBack(
            boolean[] mask,
            int width,
            int left,
            int top,
            int right,
            int bottom
    ) {
        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) {
                mask[y * width + (width - 1 - x)] = true;
            }
        }
    }

    private static int countDepth(
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

    private static boolean rowHasVoxel(
            boolean[] volume,
            int width,
            int depth,
            int y
    ) {
        int start = y * width * depth;
        int end = start + width * depth;
        for (int index = start; index < end; index++) {
            if (volume[index]) {
                return true;
            }
        }
        return false;
    }

    private static boolean columnHasVoxel(
            boolean[] volume,
            int width,
            int depth,
            int x,
            int y
    ) {
        int base = (y * width + x) * depth;
        for (int z = 0; z < depth; z++) {
            if (volume[base + z]) {
                return true;
            }
        }
        return false;
    }
}
