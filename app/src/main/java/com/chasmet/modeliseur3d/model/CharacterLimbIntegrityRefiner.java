package com.chasmet.modeliseur3d.model;

import java.util.Arrays;

/**
 * V9.5.6 conservative character integrity pass.
 *
 * <p>Les quatre silhouettes sont la référence. Cette passe ne retire jamais
 * un voxel. Elle répare uniquement les zones fines (bras, jambes, pans de
 * vêtement séparés) qui sont visibles dans face/dos ET dans un profil mais qui
 * ont été trop amincies par la fusion DA3.</p>
 */
final class CharacterLimbIntegrityRefiner {
    private static final float ISO = 0.50f;

    private CharacterLimbIntegrityRefiner() {
    }

    static Result refine(
            float[] density,
            boolean[][] masks,
            int width,
            int height,
            int depth
    ) {
        if (density == null || density.length != width * height * depth
                || width < 32 || height < 48 || depth < 24
                || !validMasks(masks, width, height, depth)) {
            return Result.unchanged(density, "V9.5.6 personnage : données insuffisantes");
        }

        boolean[] frontUnion = frontUnion(masks, width, height);
        boolean[] sideUnion = sideUnion(masks, depth, height);
        int top = firstOccupiedRow(frontUnion, width, height);
        int bottom = lastOccupiedRow(frontUnion, width, height);
        if (top < 0 || bottom <= top) {
            return Result.unchanged(density, "V9.5.6 personnage : silhouette vide");
        }

        int[] starts = new int[width];
        int[] ends = new int[width];
        int restored = 0;
        int newlyOccupied = 0;
        int supportedRows = 0;
        int spanY = Math.max(1, bottom - top);

        for (int y = top; y <= bottom; y++) {
            float progress = (y - top) / (float) spanY;
            int runCount = collectRuns(frontUnion, width, y, starts, ends);
            if (runCount < 2) {
                // Une veste, une robe ou un manteau peut être une seule grande
                // silhouette : on ne crée surtout pas artificiellement un trou.
                continue;
            }

            int sideStart = firstRunStart(sideUnion, depth, y);
            int sideEnd = lastRunEnd(sideUnion, depth, y);
            if (sideStart < 0 || sideEnd < sideStart) {
                continue;
            }
            float sideCenter = (sideStart + sideEnd) * 0.5f;
            float sideRadius = Math.max(1.5f, (sideEnd - sideStart + 1) * 0.5f);
            int maximumRunWidth = 1;
            for (int run = 0; run < runCount; run++) {
                maximumRunWidth = Math.max(maximumRunWidth, ends[run] - starts[run] + 1);
            }

            boolean lowerBody = progress >= 0.50f;
            boolean armBand = progress >= 0.22f && progress <= 0.63f;
            if (!lowerBody && !armBand) {
                continue;
            }

            boolean rowChanged = false;
            for (int run = 0; run < runCount; run++) {
                int runWidth = ends[run] - starts[run] + 1;
                float ratio = runWidth / (float) maximumRunWidth;

                // En bas : les runs séparés sont les jambes/pans réels.
                // Au milieu : seuls les runs plus fins que le torse sont réparés.
                if (!lowerBody && ratio > 0.72f) {
                    continue;
                }

                float localRadius = Math.min(
                        sideRadius,
                        Math.max(1.8f, runWidth * (lowerBody ? 0.70f : 0.58f))
                );
                float target = lowerBody ? 0.58f : 0.55f;

                int zMin = Math.max(sideStart, (int) Math.floor(sideCenter - localRadius));
                int zMax = Math.min(sideEnd, (int) Math.ceil(sideCenter + localRadius));
                for (int x = starts[run]; x <= ends[run]; x++) {
                    if (!frontUnion[y * width + x]) {
                        continue;
                    }
                    for (int z = zMin; z <= zMax; z++) {
                        if (!sideUnion[y * depth + z]) {
                            continue;
                        }
                        float normalized = Math.abs(z - sideCenter) / Math.max(1.0f, localRadius);
                        if (normalized > 1.0f) {
                            continue;
                        }
                        float shapedTarget = target * (1.0f - 0.14f * normalized * normalized);
                        int voxel = index(x, y, z, width, depth);
                        float previous = density[voxel];
                        if (previous >= shapedTarget) {
                            continue;
                        }
                        if (previous < ISO && shapedTarget >= ISO) {
                            newlyOccupied++;
                        }
                        density[voxel] = shapedTarget;
                        restored++;
                        rowChanged = true;
                    }
                }
            }
            if (rowChanged) {
                supportedRows++;
            }
        }

        if (restored == 0) {
            return Result.unchanged(
                    density,
                    "V9.5.6 personnage : membres déjà complets, aucune suppression"
            );
        }

        int occupied = countOccupied(density);
        String summary = "V9.5.6 personnage : intégrité silhouettes"
                + " • " + restored + " voxels restaurés"
                + " • " + newlyOccupied + " nouveaux voxels de surface"
                + " • " + supportedRows + " lignes anatomiques"
                + " • aucune suppression";
        MemoryDiagnostics.mark(summary);
        return new Result(density, true, restored, occupied, summary);
    }

    private static boolean[] frontUnion(boolean[][] masks, int width, int height) {
        boolean[] out = new boolean[width * height];
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                out[row + x] = masks[StylizedFourViewProjector.FRONT][row + x]
                        || masks[StylizedFourViewProjector.BACK][row + width - 1 - x];
            }
        }
        return out;
    }

    private static boolean[] sideUnion(boolean[][] masks, int depth, int height) {
        boolean[] out = new boolean[depth * height];
        for (int y = 0; y < height; y++) {
            int row = y * depth;
            for (int z = 0; z < depth; z++) {
                out[row + z] = masks[StylizedFourViewProjector.RIGHT][row + z]
                        || masks[StylizedFourViewProjector.LEFT][row + depth - 1 - z];
            }
        }
        return out;
    }

    private static int collectRuns(
            boolean[] mask,
            int width,
            int y,
            int[] starts,
            int[] ends
    ) {
        int count = 0;
        int cursor = 0;
        int row = y * width;
        while (cursor < width) {
            while (cursor < width && !mask[row + cursor]) {
                cursor++;
            }
            if (cursor >= width) {
                break;
            }
            int start = cursor;
            while (cursor + 1 < width && mask[row + cursor + 1]) {
                cursor++;
            }
            starts[count] = start;
            ends[count] = cursor;
            count++;
            cursor++;
        }
        return count;
    }

    private static int firstRunStart(boolean[] mask, int width, int y) {
        int row = y * width;
        for (int x = 0; x < width; x++) {
            if (mask[row + x]) return x;
        }
        return -1;
    }

    private static int lastRunEnd(boolean[] mask, int width, int y) {
        int row = y * width;
        for (int x = width - 1; x >= 0; x--) {
            if (mask[row + x]) return x;
        }
        return -1;
    }

    private static int firstOccupiedRow(boolean[] mask, int width, int height) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (mask[y * width + x]) return y;
            }
        }
        return -1;
    }

    private static int lastOccupiedRow(boolean[] mask, int width, int height) {
        for (int y = height - 1; y >= 0; y--) {
            for (int x = 0; x < width; x++) {
                if (mask[y * width + x]) return y;
            }
        }
        return -1;
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

    private static int countOccupied(float[] density) {
        int count = 0;
        for (float value : density) {
            if (value >= ISO) count++;
        }
        return count;
    }

    private static int index(int x, int y, int z, int width, int depth) {
        return (y * width + x) * depth + z;
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
}
