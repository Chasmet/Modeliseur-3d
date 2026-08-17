package com.chasmet.modeliseur3d.model;

/** Test autonome V9.5.6 : bras/jambes visibles dans les vues doivent survivre. */
public final class CharacterLimbIntegrityRefinerSelfTest {
    private CharacterLimbIntegrityRefinerSelfTest() {
    }

    public static void main(String[] args) {
        int width = 52;
        int height = 88;
        int depth = 40;
        float[] density = new float[width * height * depth];
        boolean[][] masks = {
                new boolean[width * height],
                new boolean[depth * height],
                new boolean[width * height],
                new boolean[depth * height]
        };

        // Torse + tête présents.
        fillBox(density, width, depth, 20, 31, 10, 50, 14, 25, 0.86f);
        fillBox(density, width, depth, 22, 29, 2, 13, 15, 24, 0.82f);

        // Une seule jambe et un seul bras restent après une fusion DA3 trop agressive.
        fillBox(density, width, depth, 20, 24, 49, 84, 16, 22, 0.78f);
        fillBox(density, width, depth, 12, 17, 28, 55, 16, 22, 0.74f);

        // Silhouette face/dos : deux bras puis deux jambes bien visibles.
        for (int y = 24; y <= 54; y++) {
            paintRun(masks[StylizedFourViewProjector.FRONT], width, y, 10, 17);
            paintRun(masks[StylizedFourViewProjector.FRONT], width, y, 20, 31);
            paintRun(masks[StylizedFourViewProjector.FRONT], width, y, 34, 41);
            paintRunMirrored(masks[StylizedFourViewProjector.BACK], width, y, 10, 17);
            paintRunMirrored(masks[StylizedFourViewProjector.BACK], width, y, 20, 31);
            paintRunMirrored(masks[StylizedFourViewProjector.BACK], width, y, 34, 41);
        }
        for (int y = 48; y <= 85; y++) {
            paintRun(masks[StylizedFourViewProjector.FRONT], width, y, 19, 24);
            paintRun(masks[StylizedFourViewProjector.FRONT], width, y, 27, 32);
            paintRunMirrored(masks[StylizedFourViewProjector.BACK], width, y, 19, 24);
            paintRunMirrored(masks[StylizedFourViewProjector.BACK], width, y, 27, 32);
        }

        // Profil : épaisseur réelle du sujet.
        for (int y = 2; y <= 85; y++) {
            paintRun(masks[StylizedFourViewProjector.RIGHT], depth, y, 13, 26);
            paintRunMirrored(masks[StylizedFourViewProjector.LEFT], depth, y, 13, 26);
        }

        int bodyBefore = countBox(density, width, depth, 20, 31, 10, 45, 14, 25);
        CharacterLimbIntegrityRefiner.Result result = CharacterLimbIntegrityRefiner.refine(
                density, masks, width, height, depth
        );

        require(result.applied, "la réparation personnage doit être appliquée");
        require(columnSupport(density, width, depth, 37, 20, 30, 50) >= 15,
                "bras droit manquant après réparation");
        require(columnSupport(density, width, depth, 29, 20, 55, 82) >= 20,
                "jambe droite manquante après réparation");
        int bodyAfter = countBox(density, width, depth, 20, 31, 10, 45, 14, 25);
        require(bodyAfter >= bodyBefore, "la réparation ne doit jamais enlever le torse");

        System.out.println("CharacterLimbIntegrityRefinerSelfTest OK : " + result.summary);
    }

    private static void fillBox(
            float[] density, int width, int depth,
            int minX, int maxX, int minY, int maxY,
            int minZ, int maxZ, float value
    ) {
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    density[index(x, y, z, width, depth)] = value;
                }
            }
        }
    }

    private static void paintRun(boolean[] mask, int axisWidth, int y, int start, int end) {
        for (int value = start; value <= end; value++) {
            mask[y * axisWidth + value] = true;
        }
    }

    private static void paintRunMirrored(
            boolean[] mask, int axisWidth, int y, int start, int end
    ) {
        for (int value = start; value <= end; value++) {
            mask[y * axisWidth + axisWidth - 1 - value] = true;
        }
    }

    private static int columnSupport(
            float[] density, int width, int depth,
            int x, int z, int minY, int maxY
    ) {
        int count = 0;
        for (int y = minY; y <= maxY; y++) {
            if (density[index(x, y, z, width, depth)] >= 0.50f) count++;
        }
        return count;
    }

    private static int countBox(
            float[] density, int width, int depth,
            int minX, int maxX, int minY, int maxY,
            int minZ, int maxZ
    ) {
        int count = 0;
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (density[index(x, y, z, width, depth)] >= 0.50f) count++;
                }
            }
        }
        return count;
    }

    private static int index(int x, int y, int z, int width, int depth) {
        return (y * width + x) * depth + z;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
