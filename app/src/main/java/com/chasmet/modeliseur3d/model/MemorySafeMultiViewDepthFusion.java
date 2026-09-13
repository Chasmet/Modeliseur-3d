package com.chasmet.modeliseur3d.model;

/**
 * V9.5.6 memory-safe DA3 fusion.
 *
 * <p>Personnages et quadrupèdes sont désormais conservateurs : le passage
 * structurel générique ne retire plus de matière sur ces deux familles. Les
 * corrections anatomiques sont faites ensuite par des passes dédiées qui ne
 * peuvent qu'ajouter/récupérer des zones soutenues par les silhouettes.</p>
 */
public final class MemorySafeMultiViewDepthFusion {
    private static final float ISO = 0.50f;

    private MemorySafeMultiViewDepthFusion() {
    }

    public static MultiViewDepthFusion.Result refine(
            float[] base,
            boolean[][] masks,
            float[][] depth,
            float[][] confidence,
            int width,
            int height,
            int depthSize,
            SubjectCategory category
    ) {
        MultiViewDepthFusion.Result da3 = DepthV74Engine.run(
                base, masks, depth, confidence,
                width, height, depthSize, category
        );
        if (!da3.isApplied()) {
            return da3;
        }

        SubjectCategory resolved = category == null || category == SubjectCategory.AUTO
                ? SubjectCategory.CHARACTER
                : category;
        StructuralResult structural = refineInPlace(
                da3.getDensity(), width, height, depthSize, resolved
        );
        if (!structural.applied) {
            return new MultiViewDepthFusion.Result(
                    da3.getDensity(),
                    true,
                    da3.getValidViews(),
                    da3.getChangedVoxels(),
                    da3.getOccupiedVoxels(),
                    da3.getMeanSurfaceInset(),
                    da3.isCollapseGuardUsed(),
                    da3.getCorrespondencePrunedVoxels(),
                    appendReason(da3.getReason(), structural.summary)
            );
        }

        return new MultiViewDepthFusion.Result(
                da3.getDensity(),
                true,
                da3.getValidViews(),
                da3.getChangedVoxels() + structural.changed,
                structural.occupied,
                da3.getMeanSurfaceInset(),
                da3.isCollapseGuardUsed(),
                da3.getCorrespondencePrunedVoxels(),
                appendReason(da3.getReason(), structural.summary)
        );
    }

    private static StructuralResult refineInPlace(
            float[] density,
            int width,
            int height,
            int depth,
            SubjectCategory category
    ) {
        if (density == null || density.length != width * height * depth) {
            return StructuralResult.unchanged(0, "V9.5.6 mémoire : champ invalide");
        }

        int occupied = countOccupied(density);
        if (category == SubjectCategory.CHARACTER) {
            return StructuralResult.unchanged(
                    occupied,
                    "V9.5.6 mémoire : personnage préservé avant réparation des membres"
            );
        }
        if (category == SubjectCategory.ANIMAL) {
            return StructuralResult.unchanged(
                    occupied,
                    "V9.5.6 mémoire : quadrupède préservé avant intégrité 4 appuis"
            );
        }
        if (category == SubjectCategory.ARCHITECTURE_OBJECT) {
            return StructuralResult.unchanged(
                    occupied,
                    "V9.5.6 mémoire : objet rigide conservé"
            );
        }

        Bounds bounds = Bounds.from(density, width, height, depth);
        if (bounds.occupied < 96 || bounds.top < 0) {
            return StructuralResult.unchanged(
                    bounds.occupied,
                    "V9.5.6 mémoire : volume trop petit"
            );
        }

        int verticalSpan = Math.max(1, bounds.bottom - bounds.top);
        float centerX = (bounds.minX + bounds.maxX) * 0.5f;
        float centerZ = (bounds.minZ + bounds.maxZ) * 0.5f;
        float halfX = Math.max(1.0f, (bounds.maxX - bounds.minX + 1) * 0.5f);
        float halfZ = Math.max(1.0f, (bounds.maxZ - bounds.minZ + 1) * 0.5f);

        int changed = 0;
        int occupiedAfter = 0;
        for (int y = bounds.top; y <= bounds.bottom; y++) {
            float vertical = (y - bounds.top) / (float) verticalSpan;
            for (int x = bounds.minX; x <= bounds.maxX; x++) {
                float ax = Math.abs((x - centerX) / halfX);
                for (int z = bounds.minZ; z <= bounds.maxZ; z++) {
                    int voxel = index(x, y, z, width, depth);
                    float value = density[voxel];
                    if (value <= 0.02f) continue;
                    float az = Math.abs((z - centerZ) / halfZ);
                    float factor = structuralFactor(category, vertical, ax, az);
                    float refined = factor >= 0.9999f ? value : value * factor;
                    if (refined < value - 1.0e-6f) changed++;
                    if (refined >= ISO) occupiedAfter++;
                }
            }
        }

        int minimumChanged = Math.max(12, bounds.occupied / 1200);
        if (changed < minimumChanged
                || occupiedAfter < Math.round(bounds.occupied * minimumOccupancyRatio(category))) {
            return StructuralResult.unchanged(
                    bounds.occupied,
                    "V9.5.6 mémoire : garde-fou anti-effondrement"
            );
        }

        for (int y = bounds.top; y <= bounds.bottom; y++) {
            float vertical = (y - bounds.top) / (float) verticalSpan;
            for (int x = bounds.minX; x <= bounds.maxX; x++) {
                float ax = Math.abs((x - centerX) / halfX);
                for (int z = bounds.minZ; z <= bounds.maxZ; z++) {
                    int voxel = index(x, y, z, width, depth);
                    float value = density[voxel];
                    if (value <= 0.02f) continue;
                    float az = Math.abs((z - centerZ) / halfZ);
                    float factor = structuralFactor(category, vertical, ax, az);
                    if (factor < 0.9999f) density[voxel] = value * factor;
                }
            }
        }

        int removedPercent = Math.max(
                0,
                Math.round((bounds.occupied - occupiedAfter) * 100.0f
                        / Math.max(1, bounds.occupied))
        );
        return new StructuralResult(
                true,
                changed,
                occupiedAfter,
                "V9.5.6 mémoire " + familyName(category)
                        + " • raffinement en place"
                        + " • " + changed + " voxels reclassés"
                        + " • retrait " + removedPercent + "%"
        );
    }

    private static float structuralFactor(
            SubjectCategory category,
            float vertical,
            float ax,
            float az
    ) {
        switch (category) {
            case COMPOSITE_VEHICLE:
                if (vertical >= 0.46f && vertical <= 0.61f
                        && ax >= 0.31f && ax <= 0.76f && az <= 0.48f) {
                    return 0.38f;
                }
                if (vertical >= 0.61f
                        && ax >= 0.50f && ax <= 0.78f && az <= 0.44f) {
                    return 0.18f;
                }
                if (vertical >= 0.67f && vertical <= 0.91f
                        && az >= 0.36f && az <= 0.58f && ax <= 0.28f) {
                    return 0.52f;
                }
                return 1.0f;
            case PLANT:
                if (vertical <= 0.48f && ax <= 0.10f && az >= 0.42f) {
                    return 0.74f;
                }
                return 1.0f;
            default:
                return 1.0f;
        }
    }

    private static float minimumOccupancyRatio(SubjectCategory category) {
        switch (category) {
            case COMPOSITE_VEHICLE:
                return 0.78f;
            case PLANT:
                return 0.90f;
            default:
                return 0.98f;
        }
    }

    private static String familyName(SubjectCategory category) {
        switch (category) {
            case COMPOSITE_VEHICLE:
                return "pilote + châssis + roues";
            case PLANT:
                return "tronc + branches";
            default:
                return "forme conservatrice";
        }
    }

    private static String appendReason(String first, String second) {
        if (first == null || first.trim().isEmpty()) return second;
        return first + " • " + second;
    }

    private static int countOccupied(float[] density) {
        int occupied = 0;
        if (density != null) {
            for (float value : density) {
                if (value >= ISO) occupied++;
            }
        }
        return occupied;
    }

    private static int index(int x, int y, int z, int width, int depth) {
        return (y * width + x) * depth + z;
    }

    private static final class StructuralResult {
        final boolean applied;
        final int changed;
        final int occupied;
        final String summary;

        StructuralResult(boolean applied, int changed, int occupied, String summary) {
            this.applied = applied;
            this.changed = changed;
            this.occupied = occupied;
            this.summary = summary;
        }

        static StructuralResult unchanged(int occupied, String summary) {
            return new StructuralResult(false, 0, occupied, summary);
        }
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
                        if (density[index(x, y, z, width, depth)] < ISO) continue;
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
            if (result.occupied == 0) {
                result.minX = result.top = result.minZ = -1;
                result.maxX = result.bottom = result.maxZ = -1;
            }
            return result;
        }
    }
}
