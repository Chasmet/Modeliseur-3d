package com.chasmet.modeliseur3d.model;

/** Test autonome V9.5.5 : deux jambes arrière absentes doivent être récupérées. */
public final class AnimalLegTopologyRefinerSelfTest {
    private AnimalLegTopologyRefinerSelfTest() {
    }

    public static void main(String[] args) {
        int width = 48;
        int height = 80;
        int depth = 64;
        float[] density = new float[width * height * depth];
        boolean[][] masks = {
                new boolean[width * height],
                new boolean[depth * height],
                new boolean[width * height],
                new boolean[depth * height]
        };

        // Corps du cheval.
        fillBox(density, width, depth, 15, 33, 12, 44, 12, 52, 0.85f);

        // Seulement les deux jambes avant existent encore dans le volume DA3.
        fillBox(density, width, depth, 19, 22, 44, 75, 15, 20, 0.78f);
        fillBox(density, width, depth, 27, 30, 44, 75, 15, 20, 0.78f);

        // Face + dos : deux appuis latéraux sur toute la partie basse.
        for (int y = 42; y <= 76; y++) {
            paintRun(masks[StylizedFourViewProjector.FRONT], width, y, 18, 23);
            paintRun(masks[StylizedFourViewProjector.FRONT], width, y, 26, 31);
            paintRunMirrored(masks[StylizedFourViewProjector.BACK], width, y, 18, 23);
            paintRunMirrored(masks[StylizedFourViewProjector.BACK], width, y, 26, 31);
        }

        // Profils : appui avant ET appui arrière visibles dans les photos.
        for (int y = 42; y <= 76; y++) {
            paintRun(masks[StylizedFourViewProjector.RIGHT], depth, y, 14, 21);
            paintRun(masks[StylizedFourViewProjector.RIGHT], depth, y, 43, 50);
            paintRunMirrored(masks[StylizedFourViewProjector.LEFT], depth, y, 14, 21);
            paintRunMirrored(masks[StylizedFourViewProjector.LEFT], depth, y, 43, 50);
        }

        int before = countOccupied(density);
        AnimalLegTopologyRefiner.Result result = AnimalLegTopologyRefiner.refine(
                density, masks, width, height, depth
        );
        require(result.applied, "le passage quadrupède doit restaurer les appuis manquants");
        require(result.occupied > before, "des voxels arrière doivent repasser au-dessus de l'ISO");
        require(columnSupport(density, width, depth, 20, 47, 50, 74) >= 18,
                "jambe arrière gauche absente après réparation");
        require(columnSupport(density, width, depth, 28, 47, 50, 74) >= 18,
                "jambe arrière droite absente après réparation");
        require(density[index(24, 30, 32, width, depth)] >= 0.80f,
                "le corps ne doit jamais être diminué");

        System.out.println("AnimalLegTopologyRefinerSelfTest OK : " + result.summary);
    }

    private static void fillBox(
            float[] density,
            int width,
            int depth,
            int minX,
            int maxX,
            int minY,
            int maxY,
            int minZ,
            int maxZ,
            float value
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
            boolean[] mask,
            int axisWidth,
            int y,
            int canonicalStart,
            int canonicalEnd
    ) {
        for (int value = canonicalStart; value <= canonicalEnd; value++) {
            int mirrored = axisWidth - 1 - value;
            mask[y * axisWidth + mirrored] = true;
        }
    }

    private static int columnSupport(
            float[] density,
            int width,
            int depth,
            int x,
            int z,
            int minY,
            int maxY
    ) {
        int count = 0;
        for (int y = minY; y <= maxY; y++) {
            if (density[index(x, y, z, width, depth)] >= 0.50f) {
                count++;
            }
        }
        return count;
    }

    private static int countOccupied(float[] density) {
        int count = 0;
        for (float value : density) {
            if (value >= 0.50f) {
                count++;
            }
        }
        return count;
    }

    private static int index(int x, int y, int z, int width, int depth) {
        return (y * width + x) * depth + z;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
