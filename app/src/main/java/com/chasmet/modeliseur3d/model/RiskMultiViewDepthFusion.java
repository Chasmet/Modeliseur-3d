package com.chasmet.modeliseur3d.model;

/**
 * V9.5 wrapper around DA3 with memory-safe structural passes.
 *
 * <p>Le mode composite n'alloue plus de copies float[] complètes après DA3.
 * Les mêmes garde-fous géométriques sont évalués avant toute sculpture, puis
 * les modifications sont appliquées en place pour réduire fortement le pic RAM.</p>
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

        if (category == SubjectCategory.ANIMAL) {
            AnimalLegTopologyRefiner.Result legs = AnimalLegTopologyRefiner.refine(
                    first.getDensity(), width, height, depthSize
            );
            if (!legs.applied) {
                return append(first, first.getDensity(), 0, first.getOccupiedVoxels(), legs.summary);
            }
            return append(first, legs.density, legs.changed, legs.occupied, legs.summary);
        }

        if (category == SubjectCategory.COMPOSITE_VEHICLE) {
            MemorySafeCompositeVehicleTopologyRefiner.Result vehicle =
                    MemorySafeCompositeVehicleTopologyRefiner.refine(
                            first.getDensity(), width, height, depthSize
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
        return new MultiViewDepthFusion.Result(
                density,
                true,
                first.getValidViews(),
                first.getChangedVoxels() + extraChanged,
                occupied,
                first.getMeanSurfaceInset(),
                first.isCollapseGuardUsed(),
                first.getCorrespondencePrunedVoxels(),
                first.getReason() + " • " + summary
        );
    }
}
