package com.chasmet.modeliseur3d.model;

/**
 * Pure-Java confidence policy for automatic profile rotation.
 *
 * <p>A side view can legitimately be wider than it is tall (horse, kart,
 * animal lying down).  Aspect ratio is therefore only a hint: a 90-degree
 * correction is accepted when the rotated silhouette also matches the
 * vertical structure of the face/back pair materially better.</p>
 */
public final class ProfileOrientationPolicy {
    private static final double MINIMUM_HORIZONTAL_ASPECT = 1.10;
    private static final double MINIMUM_REFERENCE_RATIO = 1.30;
    private static final double MINIMUM_ROTATED_SCORE = 0.54;
    private static final double MINIMUM_SCORE_GAIN = 0.075;

    private ProfileOrientationPolicy() {
    }

    public static boolean isQuarterTurnCandidate(
            double profileAspect,
            double referenceAspect
    ) {
        return profileAspect > MINIMUM_HORIZONTAL_ASPECT
                && (referenceAspect < 0.98
                || profileAspect > referenceAspect * MINIMUM_REFERENCE_RATIO);
    }

    public static boolean shouldQuarterTurn(
            double profileAspect,
            double referenceAspect,
            double directScore,
            double rotatedScore
    ) {
        return isQuarterTurnCandidate(profileAspect, referenceAspect)
                && Double.isFinite(directScore)
                && Double.isFinite(rotatedScore)
                && rotatedScore >= MINIMUM_ROTATED_SCORE
                && rotatedScore >= directScore + MINIMUM_SCORE_GAIN;
    }
}
