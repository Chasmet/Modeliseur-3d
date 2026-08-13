package com.chasmet.modeliseur3d.model;

/** Paramètres locaux de sculpture DA3 V7.4. */
final class DepthFusionZone {
    final float maximumInsetFraction;
    final float minimumGapFraction;
    final float minimumInfluence;
    final float confidenceInfluence;
    final float ambiguityProtection;
    final float crossAxisScale;
    final float coreRadius;
    final float coreFloor;

    DepthFusionZone(
            float maximumInsetFraction,
            float minimumGapFraction,
            float minimumInfluence,
            float confidenceInfluence,
            float ambiguityProtection,
            float crossAxisScale,
            float coreRadius,
            float coreFloor
    ) {
        this.maximumInsetFraction = maximumInsetFraction;
        this.minimumGapFraction = minimumGapFraction;
        this.minimumInfluence = minimumInfluence;
        this.confidenceInfluence = confidenceInfluence;
        this.ambiguityProtection = ambiguityProtection;
        this.crossAxisScale = crossAxisScale;
        this.coreRadius = coreRadius;
        this.coreFloor = coreFloor;
    }
}
