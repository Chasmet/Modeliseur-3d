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

    /** The four views need not agree pixel-perfectly. One photo may miss a hand
     * or a leg due to pose, masking or projection. Accept three agreeing views,
     * but reject a strong mismatch (over six voxels) and reject 2-of-4 guesses. */
    public static float consensusContour(float front,float back,float right,float left) {
        if(!Float.isFinite(front)||!Float.isFinite(back)||!Float.isFinite(right)||!Float.isFinite(left))
            throw new IllegalArgumentException("Contour multivue non fini.");
        float worst=Float.POSITIVE_INFINITY,second=Float.POSITIVE_INFINITY;
        float[] values={front,back,right,left};
        for(float value:values){
            if(value<worst){second=worst;worst=value;}
            else if(value<second)second=value;
        }
        if(worst>=.0f||worst<=-6f||second<.5f)return worst;
        return Math.min(second,.75f);
    }

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
