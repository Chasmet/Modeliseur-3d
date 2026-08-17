package com.chasmet.modeliseur3d.model;

/**
 * V9.5.6 wrapper around DA3 with memory-safe, non-destructive integrity passes.
 */
public final class RiskMultiViewDepthFusion {
    private RiskMultiViewDepthFusion() {
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
        MultiViewDepthFusion.Result first = MemorySafeMultiViewDepthFusion.refine(
                base,
                masks,
                depth,
                confidence,
                width,
                height,
                depthSize,
                category
        );
        if (!first.isApplied()) {
            return first;
        }

        if (category == SubjectCategory.CHARACTER) {
            CharacterLimbIntegrityRefiner.Result limbs = CharacterLimbIntegrityRefiner.refine(
                    first.getDensity(),
                    masks,
                    width,
                    height,
                    depthSize
            );
            if (!limbs.applied) {
                return append(
                        first,
                        first.getDensity(),
                        0,
                        first.getOccupiedVoxels(),
                        limbs.summary
                );
            }
            return append(
                    first,
                    limbs.density,
                    limbs.changed,
                    limbs.occupied,
                    limbs.summary
            );
        }

        if (category == SubjectCategory.ANIMAL) {
            AnimalLegTopologyRefiner.Result legs = AnimalLegTopologyRefiner.refine(
                    first.getDensity(),
                    masks,
                    width,
                    height,
                    depthSize
            );
            if (!legs.applied) {
                return append(
                        first,
                        first.getDensity(),
                        0,
                        first.getOccupiedVoxels(),
                        legs.summary
                );
            }
            return append(
                    first,
                    legs.density,
                    legs.changed,
                    legs.occupied,
                    legs.summary
            );
        }

        if (category == SubjectCategory.COMPOSITE_VEHICLE) {
            MemorySafeCompositeVehicleTopologyRefiner.Result vehicle =
                    MemorySafeCompositeVehicleTopologyRefiner.refine(
                            first.getDensity(),
                            width,
                            height,
                            depthSize
                    );
            if (!vehicle.applied) {
                return append(
                        first,
                        first.getDensity(),
                        0,
                        first.getOccupiedVoxels(),
                        vehicle.summary
                );
            }
            return append(
                    first,
                    vehicle.density,
                    vehicle.changed,
                    vehicle.occupied,
                    vehicle.summary
            );
        }

        return first;
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
}
