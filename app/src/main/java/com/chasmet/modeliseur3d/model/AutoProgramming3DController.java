package com.chasmet.modeliseur3d.model;

/**
 * V9.5.7 — pilote IA / auto-programmation de reconstruction.
 *
 * <p>Le contrôleur ne réécrit jamais le code de l'application. Il programme
 * automatiquement la reconstruction à chaque sujet : prior spécialisé,
 * budget mémoire, contrôle des projections 4 vues et passes de réparation
 * additives si des morceaux visibles dans les photos manquent encore.</p>
 */
public final class AutoProgramming3DController {
    private static final float ISO = 0.50f;

    private AutoProgramming3DController() {
    }

    public static MultiViewDepthFusion.Result finish(
            MultiViewDepthFusion.Result first,
            boolean[][] masks,
            int width,
            int height,
            int depth,
            SubjectCategory category
    ) {
        if (first == null || !first.isApplied() || first.getDensity() == null) {
            return first;
        }

        SubjectCategory resolved = category == null || category == SubjectCategory.AUTO
                ? SubjectCategory.CHARACTER
                : category;
        Plan plan = Plan.forSubject(resolved, first.getDensity().length);
        float[] density = first.getDensity();
        int extraChanged = 0;
        StringBuilder decisions = new StringBuilder();
        decisions.append("V9.5.7 PILOTE IA")
                .append(" • famille ").append(resolved.getDisplayName())
                .append(" • mémoire ").append(plan.memoryMode)
                .append(" • objectif projections ")
                .append(Math.round(plan.targetCoverage * 100.0)).append(" %");

        if (resolved == SubjectCategory.CHARACTER) {
            CharacterLimbIntegrityRefiner.Result limbs = CharacterLimbIntegrityRefiner.refine(
                    density, masks, width, height, depth
            );
            density = limbs.density;
            extraChanged += limbs.changed;
            decisions.append(" • ").append(limbs.summary);
        } else if (resolved == SubjectCategory.ANIMAL) {
            AnimalLegTopologyRefiner.Result legs = AnimalLegTopologyRefiner.refine(
                    density, masks, width, height, depth
            );
            density = legs.density;
            extraChanged += legs.changed;
            decisions.append(" • ").append(legs.summary);
        } else if (resolved == SubjectCategory.COMPOSITE_VEHICLE) {
            MemorySafeCompositeVehicleTopologyRefiner.Result vehicle =
                    MemorySafeCompositeVehicleTopologyRefiner.refine(
                            density, width, height, depth
                    );
            density = vehicle.density;
            extraChanged += vehicle.changed;
            decisions.append(" • ").append(vehicle.summary);
        }

        Coverage coverage = Coverage.measure(density, masks, width, height, depth);
        decisions.append(" • couverture initiale ")
                .append(Math.round(coverage.score * 100.0)).append(" %");

        int autoPasses = 0;
        int autoAdded = 0;
        if (plan.allowProjectionRecovery && coverage.score < plan.targetCoverage) {
            while (autoPasses < plan.maxRepairPasses
                    && coverage.score < plan.targetCoverage
                    && autoAdded < plan.maximumAddedVoxels) {
                int remaining = plan.maximumAddedVoxels - autoAdded;
                Repair repair = repairProjectionGaps(
                        density,
                        masks,
                        width,
                        height,
                        depth,
                        resolved,
                        remaining,
                        plan.repairDensity,
                        plan.repairRadius
                );
                if (repair.added <= 0) {
                    break;
                }
                autoPasses++;
                autoAdded += repair.added;
                extraChanged += repair.changed;
                Coverage next = Coverage.measure(density, masks, width, height, depth);
                if (next.score <= coverage.score + 0.001) {
                    coverage = next;
                    break;
                }
                coverage = next;
            }
        }

        decisions.append(" • auto-passes ").append(autoPasses)
                .append(" • voxels récupérés ").append(autoAdded)
                .append(" • couverture finale ")
                .append(Math.round(coverage.score * 100.0)).append(" %")
                .append(" • aucune suppression par le pilote");

        return append(first, density, extraChanged, countOccupied(density), decisions.toString());
    }

    static double debugCoverage(
            float[] density,
            boolean[][] masks,
            int width,
            int height,
            int depth
    ) {
        return Coverage.measure(density, masks, width, height, depth).score;
    }

    private static Repair repairProjectionGaps(
            float[] density,
            boolean[][] masks,
            int width,
            int height,
            int depth,
            SubjectCategory category,
            int budget,
            float targetDensity,
            int radius
    ) {
        if (!validMasks(masks, width, height, depth) || budget <= 0) {
            return Repair.NONE;
        }

        boolean[] frontTarget = frontUnion(masks, width, height);
        boolean[] sideTarget = sideUnion(masks, depth, height);
        boolean[] frontProjection = new boolean[width * height];
        boolean[] sideProjection = new boolean[depth * height];
        project(density, width, height, depth, frontProjection, sideProjection);

        int added = 0;
        int changed = 0;
        for (int y = 0; y < height && added < budget; y++) {
            int frontStart = firstTrue(frontTarget, width, y);
            int frontEnd = lastTrue(frontTarget, width, y);
            int sideStart = firstTrue(sideTarget, depth, y);
            int sideEnd = lastTrue(sideTarget, depth, y);
            if (frontStart < 0 || sideStart < 0) {
                continue;
            }

            int preferredZ = preferredCenter(sideProjection, sideTarget, depth, y, sideStart, sideEnd);
            int preferredX = preferredCenter(frontProjection, frontTarget, width, y, frontStart, frontEnd);

            for (int x = frontStart; x <= frontEnd && added < budget; x++) {
                int p = y * width + x;
                if (!frontTarget[p] || frontProjection[p]) {
                    continue;
                }
                for (int dz = -radius; dz <= radius && added < budget; dz++) {
                    int z = preferredZ + dz;
                    if (z < sideStart || z > sideEnd || !sideTarget[y * depth + z]) {
                        continue;
                    }
                    int voxel = index(x, y, z, width, depth);
                    float shaped = targetDensity - 0.015f * Math.abs(dz);
                    if (density[voxel] < shaped) {
                        if (density[voxel] < ISO && shaped >= ISO) {
                            added++;
                        }
                        density[voxel] = shaped;
                        changed++;
                    }
                }
            }

            for (int z = sideStart; z <= sideEnd && added < budget; z++) {
                int p = y * depth + z;
                if (!sideTarget[p] || sideProjection[p]) {
                    continue;
                }
                for (int dx = -radius; dx <= radius && added < budget; dx++) {
                    int x = preferredX + dx;
                    if (x < frontStart || x > frontEnd || !frontTarget[y * width + x]) {
                        continue;
                    }
                    int voxel = index(x, y, z, width, depth);
                    float shaped = targetDensity - 0.015f * Math.abs(dx);
                    if (density[voxel] < shaped) {
                        if (density[voxel] < ISO && shaped >= ISO) {
                            added++;
                        }
                        density[voxel] = shaped;
                        changed++;
                    }
                }
            }
        }
        return new Repair(added, changed);
    }

    private static int preferredCenter(
            boolean[] projection,
            boolean[] target,
            int width,
            int y,
            int start,
            int end
    ) {
        int row = y * width;
        int first = -1;
        int last = -1;
        for (int x = start; x <= end; x++) {
            if (projection[row + x] && target[row + x]) {
                if (first < 0) first = x;
                last = x;
            }
        }
        if (first >= 0) {
            return (first + last) / 2;
        }
        return (start + end) / 2;
    }

    private static void project(
            float[] density,
            int width,
            int height,
            int depth,
            boolean[] frontProjection,
            boolean[] sideProjection
    ) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int base = (y * width + x) * depth;
                for (int z = 0; z < depth; z++) {
                    if (density[base + z] < ISO) {
                        continue;
                    }
                    frontProjection[y * width + x] = true;
                    sideProjection[y * depth + z] = true;
                }
            }
        }
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

    private static int firstTrue(boolean[] mask, int width, int y) {
        int row = y * width;
        for (int x = 0; x < width; x++) {
            if (mask[row + x]) return x;
        }
        return -1;
    }

    private static int lastTrue(boolean[] mask, int width, int y) {
        int row = y * width;
        for (int x = width - 1; x >= 0; x--) {
            if (mask[row + x]) return x;
        }
        return -1;
    }

    private static boolean validMasks(boolean[][] masks, int width, int height, int depth) {
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
        if (density != null) {
            for (float value : density) {
                if (value >= ISO) count++;
            }
        }
        return count;
    }

    private static int index(int x, int y, int z, int width, int depth) {
        return (y * width + x) * depth + z;
    }

    private static MultiViewDepthFusion.Result append(
            MultiViewDepthFusion.Result first,
            float[] density,
            int extraChanged,
            int occupied,
            String summary
    ) {
        String reason = first.getReason();
        String combined = reason == null || reason.trim().isEmpty()
                ? summary
                : reason + " • " + summary;
        return new MultiViewDepthFusion.Result(
                density,
                true,
                first.getValidViews(),
                first.getChangedVoxels() + extraChanged,
                occupied,
                first.getMeanSurfaceInset(),
                first.isCollapseGuardUsed(),
                first.getCorrespondencePrunedVoxels(),
                combined
        );
    }

    private static final class Plan {
        final String memoryMode;
        final double targetCoverage;
        final boolean allowProjectionRecovery;
        final int maxRepairPasses;
        final int maximumAddedVoxels;
        final float repairDensity;
        final int repairRadius;

        Plan(
                String memoryMode,
                double targetCoverage,
                boolean allowProjectionRecovery,
                int maxRepairPasses,
                int maximumAddedVoxels,
                float repairDensity,
                int repairRadius
        ) {
            this.memoryMode = memoryMode;
            this.targetCoverage = targetCoverage;
            this.allowProjectionRecovery = allowProjectionRecovery;
            this.maxRepairPasses = maxRepairPasses;
            this.maximumAddedVoxels = maximumAddedVoxels;
            this.repairDensity = repairDensity;
            this.repairRadius = repairRadius;
        }

        static Plan forSubject(SubjectCategory category, int volumeSize) {
            Runtime runtime = Runtime.getRuntime();
            long used = runtime.totalMemory() - runtime.freeMemory();
            long headroom = Math.max(0L, runtime.maxMemory() - used);
            long mb = 1024L * 1024L;
            String memoryMode = headroom >= 180L * mb ? "qualité adaptative"
                    : headroom >= 80L * mb ? "équilibré"
                    : "mémoire protégée";
            int passes = headroom >= 180L * mb ? 2 : headroom >= 64L * mb ? 1 : 0;
            int budget = Math.min(120000, Math.max(6000, volumeSize / 300));

            switch (category) {
                case CHARACTER:
                    return new Plan(memoryMode, 0.94, true, passes, budget, 0.55f, 2);
                case ANIMAL:
                    return new Plan(memoryMode, 0.95, true, passes, budget, 0.57f, 3);
                case COMPOSITE_VEHICLE:
                    return new Plan(memoryMode, 0.93, true, passes, budget, 0.54f, 2);
                case PLANT:
                    return new Plan(memoryMode, 0.90, false, 0, 0, 0.0f, 0);
                case ARCHITECTURE_OBJECT:
                default:
                    return new Plan(memoryMode, 0.92, false, 0, 0, 0.0f, 0);
            }
        }
    }

    private static final class Coverage {
        final double score;

        Coverage(double score) {
            this.score = score;
        }

        static Coverage measure(
                float[] density,
                boolean[][] masks,
                int width,
                int height,
                int depth
        ) {
            if (density == null || !validMasks(masks, width, height, depth)) {
                return new Coverage(1.0);
            }
            boolean[] frontTarget = frontUnion(masks, width, height);
            boolean[] sideTarget = sideUnion(masks, depth, height);
            boolean[] frontProjection = new boolean[width * height];
            boolean[] sideProjection = new boolean[depth * height];
            project(density, width, height, depth, frontProjection, sideProjection);
            double front = coverage(frontTarget, frontProjection);
            double side = coverage(sideTarget, sideProjection);
            return new Coverage((front + side) * 0.5);
        }

        private static double coverage(boolean[] target, boolean[] actual) {
            int total = 0;
            int supported = 0;
            for (int i = 0; i < target.length; i++) {
                if (!target[i]) continue;
                total++;
                if (actual[i]) supported++;
            }
            return total == 0 ? 1.0 : supported / (double) total;
        }
    }

    private static final class Repair {
        static final Repair NONE = new Repair(0, 0);
        final int added;
        final int changed;

        Repair(int added, int changed) {
            this.added = added;
            this.changed = changed;
        }
    }
}
