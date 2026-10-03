package com.chasmet.modeliseur3d.model;

/** Category/zone priors for V7.4 DA3 fusion. */
final class DepthFusionPolicy {
    final SubjectCategory category;
    final float minimumRetainedFraction;
    final float collapseBaseWeight;

    private DepthFusionPolicy(
            SubjectCategory category,
            float minimumRetainedFraction,
            float collapseBaseWeight
    ) {
        this.category = category;
        this.minimumRetainedFraction = minimumRetainedFraction;
        this.collapseBaseWeight = collapseBaseWeight;
    }

    Zone zone(
            float progress,
            float frontRatio,
            float sideRatio,
            float frontFill,
            float sideFill
    ) {
        float p = clamp01(progress);
        float broadness = clamp01((frontRatio + sideRatio) * 0.5f);
        float fill = clamp01(Math.min(frontFill, sideFill));

        if (category == SubjectCategory.COMPOSITE_VEHICLE) {
            if (p < 0.46f) {
                float narrowBoost = 0.08f * (1.0f - broadness);
                return new Zone(
                        0.29f + narrowBoost, 0.08f,
                        0.68f, 0.24f, 0.06f,
                        1.08f, 0.12f, 0.38f
                );
            }
            // Open frames/seats/wheels must not be protected as one solid box.
            if (fill < 0.78f) {
                float openness = clamp01((0.78f - fill) / 0.42f);
                return new Zone(
                        0.20f + 0.09f * openness,
                        0.11f,
                        0.62f + 0.08f * openness,
                        0.24f,
                        0.08f,
                        0.98f,
                        0.0f,
                        0.0f
                );
            }
            return new Zone(
                    0.105f, 0.30f,
                    0.43f, 0.15f, 0.48f,
                    0.82f, 0.42f, 0.70f
            );
        }
        if (category == SubjectCategory.ANIMAL) {
            if (p >= 0.62f && broadness < 0.72f) {
                return new Zone(0.35f, 0.09f, 0.70f, 0.22f, 0.05f,
                        1.02f, 0.18f, 0.50f);
            }
            if (p >= 0.18f && p < 0.64f && broadness >= 0.58f) {
                return new Zone(0.135f, 0.31f, 0.54f, 0.18f, 0.16f,
                        0.88f, 0.43f, 0.72f);
            }
            return new Zone(0.235f, 0.16f, 0.64f, 0.20f, 0.10f,
                    0.96f, 0.28f, 0.60f);
        }
        if (category == SubjectCategory.ARCHITECTURE_OBJECT) {
            return new Zone(0.085f, 0.48f, 0.38f, 0.12f, 0.42f,
                    0.78f, 0.60f, 0.80f);
        }
        if (category == SubjectCategory.PLANT) {
            if (p >= 0.58f && broadness < 0.58f) {
                return new Zone(0.31f, 0.11f, 0.64f, 0.20f, 0.10f,
                        1.05f, 0.18f, 0.50f);
            }
            if (p < 0.58f && broadness > 0.54f) {
                return new Zone(0.19f, 0.17f, 0.58f, 0.20f, 0.14f,
                        0.96f, 0.24f, 0.58f);
            }
            return new Zone(0.25f, 0.12f, 0.60f, 0.20f, 0.12f,
                    1.00f, 0.20f, 0.54f);
        }
        if (p < 0.23f) {
            return new Zone(0.22f, 0.18f, 0.63f, 0.22f, 0.04f,
                    0.96f, 0.30f, 0.62f);
        }
        if (p < 0.62f && broadness >= 0.48f) {
            return new Zone(0.15f, 0.29f, 0.56f, 0.20f, 0.08f,
                    0.90f, 0.40f, 0.68f);
        }
        return new Zone(0.32f, 0.10f, 0.72f, 0.22f, 0.02f,
                1.05f, 0.16f, 0.48f);
    }

    static DepthFusionPolicy forCategory(SubjectCategory category) {
        if (category == SubjectCategory.COMPOSITE_VEHICLE) {
            return new DepthFusionPolicy(category, 0.58f, 0.54f);
        }
        if (category == SubjectCategory.ARCHITECTURE_OBJECT) {
            return new DepthFusionPolicy(category, 0.84f, 0.68f);
        }
        if (category == SubjectCategory.ANIMAL) {
            return new DepthFusionPolicy(category, 0.69f, 0.58f);
        }
        if (category == SubjectCategory.PLANT) {
            return new DepthFusionPolicy(category, 0.66f, 0.54f);
        }
        return new DepthFusionPolicy(SubjectCategory.CHARACTER, 0.60f, 0.56f);
    }

    static float debugInsetFraction(
            SubjectCategory category,
            float progress,
            float frontRatio,
            float sideRatio
    ) {
        SubjectCategory resolved = category == null || category == SubjectCategory.AUTO
                ? SubjectCategory.CHARACTER : category;
        return forCategory(resolved)
                .zone(progress, frontRatio, sideRatio, 1.0f, 1.0f)
                .maximumInsetFraction;
    }

    static final class Zone {
        final float maximumInsetFraction;
        final float minimumGapFraction;
        final float minimumInfluence;
        final float confidenceInfluence;
        final float ambiguityProtection;
        final float crossAxisScale;
        final float coreRadius;
        final float coreFloor;

        Zone(
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

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
