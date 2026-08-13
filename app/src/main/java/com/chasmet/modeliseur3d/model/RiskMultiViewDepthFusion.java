package com.chasmet.modeliseur3d.model;

/**
 * V9 wrapper around the proven V8/DA3 fusion.
 * Animals receive a dedicated four-leg topology pass and composite vehicles
 * receive a driver/chassis/four-wheel separation pass.
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
        MultiViewDepthFusion.Result first = MultiViewDepthFusion.refine(
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
            CompositeVehicleTopologyRefiner.Result vehicle = CompositeVehicleTopologyRefiner.refine(
                    first.getDensity(), width, height, depthSize
            );
            if (!vehicle.applied) {
                return append(first, first.getDensity(), 0, first.getOccupiedVoxels(), vehicle.summary);
            }
            return append(first, vehicle.density, vehicle.changed, vehicle.occupied, vehicle.summary);
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
