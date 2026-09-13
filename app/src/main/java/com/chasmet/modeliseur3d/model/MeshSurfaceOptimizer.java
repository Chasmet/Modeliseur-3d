package com.chasmet.modeliseur3d.model;

/**
 * Compatibility entry point for the V7.3 feature-aware mesh finisher.
 *
 * <p>The previous implementation used the same Taubin smoothing on every
 * model. The V7.3 delegate detects the structural family from mesh proportions
 * and normals, preserves hard edges and reduces movement on thin/lower parts.</p>
 */
public final class MeshSurfaceOptimizer {
    private MeshSurfaceOptimizer() {
    }

    public static MeshData optimize(MeshData source, int iterations) {
        return FeatureAwareMeshOptimizer.optimize(source, iterations);
    }
}
