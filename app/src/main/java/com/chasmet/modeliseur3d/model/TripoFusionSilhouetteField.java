package com.chasmet.modeliseur3d.model;

/**
 * Conservative four-photo geometry support.
 *
 * TripoSR predicts four independent shapes. Their learned volumes can disagree
 * even when all photographed silhouettes contain a limb. A neural-only consensus
 * therefore can erase whole legs or faces. Anchor the exterior to the four
 * registered PHOTO silhouettes; use the learned consensus only as a small
 * offset around the silhouette boundary, never as a veto of observed anatomy.
 *
 * Signed contour distance is in voxels (positive inside).
 */
public final class TripoFusionSilhouetteField {
    private TripoFusionSilhouetteField() {}

    public static float probability(float signedContour,float neuralConsensus) {
        if(!Float.isFinite(signedContour)||!Float.isFinite(neuralConsensus)
                ||neuralConsensus<0||neuralConsensus>1)
            throw new IllegalArgumentException("Densité de fusion multivue invalide.");
        // An alpha silhouette has at least +0.5 for an occupied boundary pixel.
        // Even a neural prediction of zero must not erase that observed pixel.
        float silhouette=.5f+.32f*signedContour;
        float detail=.08f*(neuralConsensus-.5f);
        return Math.max(0,Math.min(1,silhouette+detail));
    }

    public static boolean hasSupport(float signedContour){
        if(!Float.isFinite(signedContour))throw new IllegalArgumentException("Contour non fini.");
        // A narrow buffer covers small errors from silhouette resampling.
        return signedContour> -2f;
    }
}
