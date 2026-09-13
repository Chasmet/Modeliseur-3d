package com.chasmet.modeliseur3d.model;

/** Test autonome du pilote IA V9.5.7. */
public final class AutoProgramming3DControllerSelfTest {
    private AutoProgramming3DControllerSelfTest() {
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

        // Torse + jambes + bras gauche. Le bras droit est volontairement absent
        // de la 3D mais reste présent dans les quatre silhouettes.
        fillBox(density, width, depth, 17, 30, 15, 50, 25, 38, 0.80f);
        fillBox(density, width, depth, 17, 22, 50, 75, 27, 36, 0.78f);
        fillBox(density, width, depth, 25, 30, 50, 75, 27, 36, 0.78f);
        fillBox(density, width, depth, 5, 11, 22, 45, 27, 35, 0.76f);

        for (int y = 15; y <= 75; y++) {
            int torsoStart = y >= 50 ? 17 : 17;
            int torsoEnd = y >= 50 ? 30 : 30;
            paintRun(masks[StylizedFourViewProjector.FRONT], width, y, torsoStart, torsoEnd);
            paintRunMirrored(masks[StylizedFourViewProjector.BACK], width, y, torsoStart, torsoEnd);
            paintRun(masks[StylizedFourViewProjector.RIGHT], depth, y, 24, 39);
            paintRunMirrored(masks[StylizedFourViewProjector.LEFT], depth, y, 24, 39);
        }
        for (int y = 22; y <= 45; y++) {
            paintRun(masks[StylizedFourViewProjector.FRONT], width, y, 5, 11);
            paintRun(masks[StylizedFourViewProjector.FRONT], width, y, 36, 43);
            paintRunMirrored(masks[StylizedFourViewProjector.BACK], width, y, 5, 11);
            paintRunMirrored(masks[StylizedFourViewProjector.BACK], width, y, 36, 43);
        }
        for (int y = 50; y <= 75; y++) {
            // Séparation jambes dans face/dos.
            clearRun(masks[StylizedFourViewProjector.FRONT], width, y, 23, 24);
            clearRunMirrored(masks[StylizedFourViewProjector.BACK], width, y, 23, 24);
        }

        double before = AutoProgramming3DController.debugCoverage(
                density, masks, width, height, depth
        );
        int occupiedBefore = countOccupied(density);
        MultiViewDepthFusion.Result base = new MultiViewDepthFusion.Result(
                density,
                true,
                4,
                0,
                occupiedBefore,
                0.0,
                false,
                0,
                "base test"
        );

        MultiViewDepthFusion.Result result = AutoProgramming3DController.finish(
                base,
                masks,
                width,
                height,
                depth,
                SubjectCategory.CHARACTER
        );
        double after = AutoProgramming3DController.debugCoverage(
                result.getDensity(), masks, width, height, depth
        );

        require(after > before + 0.04, "le pilote doit améliorer la couverture 4 vues");
        require(after >= 0.90, "couverture finale insuffisante : " + after);
        require(columnSupport(result.getDensity(), width, depth, 39, 31, 24, 43) >= 12,
                "bras droit toujours manquant après auto-programmation");
        require(result.getReason() != null && result.getReason().contains("PILOTE IA"),
                "le diagnostic doit annoncer les décisions du pilote IA");
        require(countOccupied(result.getDensity()) >= occupiedBefore,
                "le pilote de récupération ne doit pas supprimer de matière");

        System.out.println("AutoProgramming3DControllerSelfTest OK : " + result.getReason());
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

    private static void paintRun(boolean[] mask, int width, int y, int start, int end) {
        for (int x = start; x <= end; x++) mask[y * width + x] = true;
    }

    private static void paintRunMirrored(boolean[] mask, int width, int y, int start, int end) {
        for (int x = start; x <= end; x++) mask[y * width + (width - 1 - x)] = true;
    }

    private static void clearRun(boolean[] mask, int width, int y, int start, int end) {
        for (int x = start; x <= end; x++) mask[y * width + x] = false;
    }

    private static void clearRunMirrored(boolean[] mask, int width, int y, int start, int end) {
        for (int x = start; x <= end; x++) mask[y * width + (width - 1 - x)] = false;
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
            if (density[index(x, y, z, width, depth)] >= 0.50f) count++;
        }
        return count;
    }

    private static int countOccupied(float[] density) {
        int count = 0;
        for (float value : density) if (value >= 0.50f) count++;
        return count;
    }

    private static int index(int x, int y, int z, int width, int depth) {
        return (y * width + x) * depth + z;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
