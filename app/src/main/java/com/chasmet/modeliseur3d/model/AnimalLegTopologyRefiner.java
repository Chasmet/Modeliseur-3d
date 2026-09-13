package com.chasmet.modeliseur3d.model;

/**
 * V9.5.5 quadruped integrity pass.
 *
 * <p>Le précédent passage quadrupède cherchait quatre pics dans le volume DA3
 * puis supprimait tout ce qui était éloigné de ces pics. Sur un cheval, deux
 * jambes arrière plus fines pouvaient être moins fortes que les jambes avant :
 * elles étaient alors effacées alors qu'elles existaient bien dans les vues.</p>
 *
 * <p>Cette version est volontairement non destructive. Elle déduit deux appuis
 * latéraux depuis face/dos et deux appuis longitudinaux depuis les profils,
 * puis restaure uniquement leurs intersections réellement soutenues par les
 * silhouettes. Le corps, la queue, l'encolure et toutes les parties déjà
 * valides ne sont jamais diminués.</p>
 */
final class AnimalLegTopologyRefiner {
    private static final float ISO = 0.50f;
    private static final float RECOVERY_DENSITY_TOP = 0.62f;
    private static final float RECOVERY_DENSITY_BOTTOM = 0.56f;

    private AnimalLegTopologyRefiner() {
    }

    static Result refine(float[] source, int width, int height, int depth) {
        return refine(source, null, width, height, depth);
    }

    static Result refine(
            float[] source,
            boolean[][] masks,
            int width,
            int height,
            int depth
    ) {
        if (source == null || source.length != width * height * depth
                || width < 32 || height < 48 || depth < 32) {
            return Result.unchanged(source, "V9.5.5 quadrupède : volume invalide");
        }

        Bounds bounds = Bounds.from(source, width, height, depth);
        if (bounds.occupied < 700 || bounds.bottom <= bounds.top) {
            return Result.unchanged(source, "V9.5.5 quadrupède : volume trop petit");
        }
        if (!validMasks(masks, width, height, depth)) {
            return Result.unchanged(
                    source,
                    "V9.5.5 quadrupède : silhouettes indisponibles, volume conservé"
            );
        }

        int spanY = Math.max(1, bounds.bottom - bounds.top);
        int legTop = bounds.top + Math.round(spanY * 0.54f);
        int supportTop = bounds.top + Math.round(spanY * 0.60f);

        int[] xSupport = new int[width];
        int[] zSupport = new int[depth];
        boolean[] densityX = new boolean[width];
        boolean[] densityZ = new boolean[depth];

        for (int y = supportTop; y <= bounds.bottom; y++) {
            java.util.Arrays.fill(densityX, false);
            java.util.Arrays.fill(densityZ, false);

            for (int x = bounds.minX; x <= bounds.maxX; x++) {
                if (frontBackSupport(masks, x, y, width)) {
                    xSupport[x] += 4;
                }
            }
            for (int z = bounds.minZ; z <= bounds.maxZ; z++) {
                if (profileSupport(masks, z, y, depth)) {
                    zSupport[z] += 4;
                }
            }

            for (int x = bounds.minX; x <= bounds.maxX; x++) {
                for (int z = bounds.minZ; z <= bounds.maxZ; z++) {
                    if (source[index(x, y, z, width, depth)] >= ISO) {
                        densityX[x] = true;
                        densityZ[z] = true;
                    }
                }
            }
            for (int x = bounds.minX; x <= bounds.maxX; x++) {
                if (densityX[x]) {
                    xSupport[x]++;
                }
            }
            for (int z = bounds.minZ; z <= bounds.maxZ; z++) {
                if (densityZ[z]) {
                    zSupport[z]++;
                }
            }
        }

        int spanX = Math.max(1, bounds.maxX - bounds.minX + 1);
        int spanZ = Math.max(1, bounds.maxZ - bounds.minZ + 1);
        int[] xCenters = selectTwoCenters(
                xSupport,
                bounds.minX,
                bounds.maxX,
                Math.max(3, Math.round(spanX * 0.12f))
        );
        int[] zCenters = selectTwoCenters(
                zSupport,
                bounds.minZ,
                bounds.maxZ,
                Math.max(4, Math.round(spanZ * 0.18f))
        );
        if (xCenters == null || zCenters == null) {
            return Result.unchanged(
                    source,
                    "V9.5.5 quadrupède : quatre zones d'appui non confirmées, rien supprimé"
            );
        }

        int restored = 0;
        int newlyOccupied = 0;
        for (int y = legTop; y <= bounds.bottom; y++) {
            float progress = (y - legTop)
                    / (float) Math.max(1, bounds.bottom - legTop);
            float radiusX = Math.max(2.2f, spanX * (0.18f - 0.07f * progress));
            float radiusZ = Math.max(2.2f, spanZ * (0.115f - 0.045f * progress));
            float targetDensity = lerp(
                    RECOVERY_DENSITY_TOP,
                    RECOVERY_DENSITY_BOTTOM,
                    progress
            );

            for (int xCenter : xCenters) {
                int minX = Math.max(bounds.minX, (int) Math.floor(xCenter - radiusX));
                int maxX = Math.min(bounds.maxX, (int) Math.ceil(xCenter + radiusX));
                for (int zCenter : zCenters) {
                    int minZ = Math.max(bounds.minZ, (int) Math.floor(zCenter - radiusZ));
                    int maxZ = Math.min(bounds.maxZ, (int) Math.ceil(zCenter + radiusZ));
                    for (int x = minX; x <= maxX; x++) {
                        float nx = (x - xCenter) / radiusX;
                        float nx2 = nx * nx;
                        if (nx2 > 1.0f || !frontBackSupport(masks, x, y, width)) {
                            continue;
                        }
                        for (int z = minZ; z <= maxZ; z++) {
                            float nz = (z - zCenter) / radiusZ;
                            if (nx2 + nz * nz > 1.0f
                                    || !profileSupport(masks, z, y, depth)) {
                                continue;
                            }
                            int voxel = index(x, y, z, width, depth);
                            float previous = source[voxel];
                            if (previous >= targetDensity) {
                                continue;
                            }
                            if (previous < ISO && targetDensity >= ISO) {
                                newlyOccupied++;
                            }
                            source[voxel] = targetDensity;
                            restored++;
                        }
                    }
                }
            }
        }

        if (restored == 0) {
            return Result.unchanged(
                    source,
                    "V9.5.5 quadrupède : quatre appuis déjà complets"
            );
        }

        int occupied = bounds.occupied + newlyOccupied;
        String summary = "V9.5.5 quadrupède : intégrité 4 appuis"
                + " • centres X " + xCenters[0] + "/" + xCenters[1]
                + " • centres Z " + zCenters[0] + "/" + zCenters[1]
                + " • " + restored + " voxels de membres restaurés"
                + " • aucune suppression";
        return new Result(source, true, restored, occupied, summary);
    }

    private static int[] selectTwoCenters(
            int[] support,
            int minimum,
            int maximum,
            int minimumSeparation
    ) {
        int first = -1;
        int firstScore = 0;
        for (int value = minimum; value <= maximum; value++) {
            if (support[value] > firstScore) {
                firstScore = support[value];
                first = value;
            }
        }
        if (first < 0 || firstScore < 3) {
            return null;
        }

        int threshold = Math.max(2, Math.round(firstScore * 0.14f));
        int second = -1;
        int secondScore = 0;
        float bestWeighted = Float.NEGATIVE_INFINITY;
        float span = Math.max(1.0f, maximum - minimum + 1.0f);
        for (int value = minimum; value <= maximum; value++) {
            int score = support[value];
            if (score < threshold || Math.abs(value - first) < minimumSeparation) {
                continue;
            }
            float distanceBonus = Math.abs(value - first) / span;
            float weighted = score * (1.0f + distanceBonus * 0.85f);
            if (weighted > bestWeighted) {
                bestWeighted = weighted;
                secondScore = score;
                second = value;
            }
        }
        if (second < 0 || secondScore < threshold) {
            return null;
        }
        return first < second ? new int[]{first, second} : new int[]{second, first};
    }

    private static boolean validMasks(
            boolean[][] masks,
            int width,
            int height,
            int depth
    ) {
        return masks != null
                && masks.length == 4
                && masks[StylizedFourViewProjector.FRONT] != null
                && masks[StylizedFourViewProjector.BACK] != null
                && masks[StylizedFourViewProjector.RIGHT] != null
                && masks[StylizedFourViewProjector.LEFT] != null
                && masks[StylizedFourViewProjector.FRONT].length == width * height
                && masks[StylizedFourViewProjector.BACK].length == width * height
                && masks[StylizedFourViewProjector.RIGHT].length == depth * height
                && masks[StylizedFourViewProjector.LEFT].length == depth * height;
    }

    private static boolean frontBackSupport(
            boolean[][] masks,
            int x,
            int y,
            int width
    ) {
        int front = y * width + x;
        int back = y * width + (width - 1 - x);
        return masks[StylizedFourViewProjector.FRONT][front]
                || masks[StylizedFourViewProjector.BACK][back];
    }

    private static boolean profileSupport(
            boolean[][] masks,
            int z,
            int y,
            int depth
    ) {
        int right = y * depth + z;
        int left = y * depth + (depth - 1 - z);
        return masks[StylizedFourViewProjector.RIGHT][right]
                || masks[StylizedFourViewProjector.LEFT][left];
    }

    private static int index(int x, int y, int z, int width, int depth) {
        return (y * width + x) * depth + z;
    }

    private static float lerp(float first, float second, float t) {
        return first + (second - first) * t;
    }

    static final class Result {
        final float[] density;
        final boolean applied;
        final int changed;
        final int occupied;
        final String summary;

        Result(float[] density, boolean applied, int changed, int occupied, String summary) {
            this.density = density;
            this.applied = applied;
            this.changed = changed;
            this.occupied = occupied;
            this.summary = summary;
        }

        static Result unchanged(float[] density, String summary) {
            return new Result(density, false, 0, countOccupied(density), summary);
        }
    }

    private static int countOccupied(float[] density) {
        int count = 0;
        if (density != null) {
            for (float value : density) {
                if (value >= ISO) {
                    count++;
                }
            }
        }
        return count;
    }

    private static final class Bounds {
        int minX;
        int maxX;
        int top;
        int bottom;
        int minZ;
        int maxZ;
        int occupied;

        static Bounds from(float[] density, int width, int height, int depth) {
            Bounds result = new Bounds();
            result.minX = width;
            result.maxX = -1;
            result.top = height;
            result.bottom = -1;
            result.minZ = depth;
            result.maxZ = -1;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    for (int z = 0; z < depth; z++) {
                        if (density[index(x, y, z, width, depth)] < ISO) {
                            continue;
                        }
                        result.occupied++;
                        result.minX = Math.min(result.minX, x);
                        result.maxX = Math.max(result.maxX, x);
                        result.top = Math.min(result.top, y);
                        result.bottom = Math.max(result.bottom, y);
                        result.minZ = Math.min(result.minZ, z);
                        result.maxZ = Math.max(result.maxZ, z);
                    }
                }
            }
            return result;
        }
    }
}
