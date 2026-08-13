package com.chasmet.modeliseur3d.model;

/**
 * V9 wrapper around the proven V8/DA3 fusion.
 * For animals only, a second topology pass removes phantom lower-leg
 * cross-combinations while preserving the existing DA3 safety gates.
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
        if (category != SubjectCategory.ANIMAL || !first.isApplied()) {
            return first;
        }

        AnimalLegTopologyRefiner.Result legs = AnimalLegTopologyRefiner.refine(
                first.getDensity(), width, height, depthSize
        );
        if (!legs.applied) {
            return new MultiViewDepthFusion.Result(
                    first.getDensity(),
                    first.isApplied(),
                    first.getValidViews(),
                    first.getChangedVoxels(),
                    first.getOccupiedVoxels(),
                    first.getMeanSurfaceInset(),
                    first.isCollapseGuardUsed(),
                    first.getCorrespondencePrunedVoxels(),
                    first.getReason() + " • " + legs.summary
            );
        }

        return new MultiViewDepthFusion.Result(
                legs.density,
                true,
                first.getValidViews(),
                first.getChangedVoxels() + legs.changed,
                legs.occupied,
                first.getMeanSurfaceInset(),
                first.isCollapseGuardUsed(),
                first.getCorrespondencePrunedVoxels(),
                first.getReason() + " • " + legs.summary
        );
    }
}
