package com.chasmet.modeliseur3d.model;

/**
 * V9.5.7 wrapper DA3 : la reconstruction mémoire-sûre est suivie par le
 * pilote IA qui choisit et contrôle automatiquement les réparations utiles.
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
        return AutoProgramming3DController.finish(
                first,
                masks,
                width,
                height,
                depthSize,
                category
        );
    }
}
