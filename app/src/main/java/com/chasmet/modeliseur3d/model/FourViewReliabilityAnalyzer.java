package com.chasmet.modeliseur3d.model;

/**
 * Détecte une vue opposée fortement incomplète avant qu'elle n'aplatisse le
 * volume entier. Le cas réel visé est un profil presque vide face à un profil
 * complet du même sujet : l'ancien moteur l'acceptait puis extrudait des
 * bandes de texture sur toute la profondeur.
 *
 * <p>La classe est volontairement en Java pur afin que les seuils de
 * non-régression soient testés sans appareil Android.</p>
 */
public final class FourViewReliabilityAnalyzer {
    private static final double MINIMUM_ANCHOR_COVERAGE = 0.075;
    private static final double SEVERE_AREA_RATIO = 0.42;
    private static final double CRITICAL_AREA_RATIO = 0.28;
    private static final double SEVERE_EFFECTIVE_WIDTH_RATIO = 0.58;
    private static final double LOW_PAIR_DICE = 0.38;

    private FourViewReliabilityAnalyzer() {
    }

    public static PairAssessment assessPair(
            boolean[] first,
            boolean[] second,
            int width,
            int height
    ) {
        Metrics firstMetrics = measure(first, width, height);
        Metrics secondMetrics = measure(second, width, height);
        double largerCoverage = Math.max(
                firstMetrics.coverage,
                secondMetrics.coverage
        );
        double areaRatio = ratio(
                firstMetrics.coverage,
                secondMetrics.coverage
        );
        double effectiveWidthRatio = ratio(
                firstMetrics.effectiveWidth,
                secondMetrics.effectiveWidth
        );
        double pairDice = Math.max(
                FourViewAutoCorrector.bestDice(
                        first,
                        second,
                        width,
                        height,
                        false
                ),
                FourViewAutoCorrector.bestDice(
                        first,
                        second,
                        width,
                        height,
                        true
                )
        );

        boolean anchorExists = largerCoverage >= MINIMUM_ANCHOR_COVERAGE;
        boolean severeAreaImbalance = areaRatio < SEVERE_AREA_RATIO;
        boolean corroborated = areaRatio < CRITICAL_AREA_RATIO
                || effectiveWidthRatio < SEVERE_EFFECTIVE_WIDTH_RATIO
                || pairDice < LOW_PAIR_DICE;
        Replacement replacement = Replacement.NONE;
        if (anchorExists && severeAreaImbalance && corroborated) {
            replacement = firstMetrics.coverage < secondMetrics.coverage
                    ? Replacement.FIRST_FROM_SECOND
                    : Replacement.SECOND_FROM_FIRST;
        }

        double confidence = replacement == Replacement.NONE
                ? 0.0
                : clamp01(
                        (1.0 - areaRatio) * 0.58
                                + (1.0 - effectiveWidthRatio) * 0.27
                                + (1.0 - pairDice) * 0.15
                );
        return new PairAssessment(
                replacement,
                firstMetrics,
                secondMetrics,
                areaRatio,
                effectiveWidthRatio,
                pairDice,
                confidence
        );
    }

    public static boolean[] mirroredCopy(
            boolean[] source,
            int width,
            int height
    ) {
        validate(source, width, height);
        boolean[] output = new boolean[source.length];
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                output[row + width - 1 - x] = source[row + x];
            }
        }
        return output;
    }

    public static float[] mirroredCopy(
            float[] source,
            int width,
            int height
    ) {
        if (source == null || source.length != width * height) {
            throw new IllegalArgumentException("Confiance de profil invalide");
        }
        float[] output = new float[source.length];
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                output[row + width - 1 - x] = source[row + x];
            }
        }
        return output;
    }

    private static Metrics measure(boolean[] mask, int width, int height) {
        validate(mask, width, height);
        int foreground = 0;
        int occupiedRows = 0;
        int left = width;
        int right = -1;
        int top = height;
        int bottom = -1;
        for (int y = 0; y < height; y++) {
            int rowForeground = 0;
            int row = y * width;
            for (int x = 0; x < width; x++) {
                if (!mask[row + x]) {
                    continue;
                }
                foreground++;
                rowForeground++;
                left = Math.min(left, x);
                right = Math.max(right, x);
                top = Math.min(top, y);
                bottom = Math.max(bottom, y);
            }
            if (rowForeground > 0) {
                occupiedRows++;
            }
        }
        if (foreground == 0 || occupiedRows == 0) {
            return new Metrics(0.0, 0.0, 0.0, 0.0);
        }
        double coverage = foreground / (double) (width * height);
        double effectiveWidth = foreground / (double) occupiedRows / width;
        double boundingWidth = (right - left + 1) / (double) width;
        double boundingHeight = (bottom - top + 1) / (double) height;
        return new Metrics(
                coverage,
                effectiveWidth,
                boundingWidth,
                boundingHeight
        );
    }

    private static double ratio(double first, double second) {
        double maximum = Math.max(first, second);
        return maximum <= 1.0e-9
                ? 1.0
                : Math.min(first, second) / maximum;
    }

    private static void validate(boolean[] mask, int width, int height) {
        if (mask == null || width < 4 || height < 4
                || mask.length != width * height) {
            throw new IllegalArgumentException("Silhouette de fiabilité invalide");
        }
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    public enum Replacement {
        NONE,
        FIRST_FROM_SECOND,
        SECOND_FROM_FIRST
    }

    public static final class Metrics {
        private final double coverage;
        private final double effectiveWidth;
        private final double boundingWidth;
        private final double boundingHeight;

        Metrics(
                double coverage,
                double effectiveWidth,
                double boundingWidth,
                double boundingHeight
        ) {
            this.coverage = coverage;
            this.effectiveWidth = effectiveWidth;
            this.boundingWidth = boundingWidth;
            this.boundingHeight = boundingHeight;
        }

        public double getCoverage() {
            return coverage;
        }

        public double getEffectiveWidth() {
            return effectiveWidth;
        }

        public double getBoundingWidth() {
            return boundingWidth;
        }

        public double getBoundingHeight() {
            return boundingHeight;
        }
    }

    public static final class PairAssessment {
        private final Replacement replacement;
        private final Metrics first;
        private final Metrics second;
        private final double areaRatio;
        private final double effectiveWidthRatio;
        private final double pairDice;
        private final double confidence;

        PairAssessment(
                Replacement replacement,
                Metrics first,
                Metrics second,
                double areaRatio,
                double effectiveWidthRatio,
                double pairDice,
                double confidence
        ) {
            this.replacement = replacement;
            this.first = first;
            this.second = second;
            this.areaRatio = areaRatio;
            this.effectiveWidthRatio = effectiveWidthRatio;
            this.pairDice = pairDice;
            this.confidence = confidence;
        }

        public Replacement getReplacement() {
            return replacement;
        }

        public boolean requiresReplacement() {
            return replacement != Replacement.NONE;
        }

        public Metrics getFirst() {
            return first;
        }

        public Metrics getSecond() {
            return second;
        }

        public double getAreaRatio() {
            return areaRatio;
        }

        public double getEffectiveWidthRatio() {
            return effectiveWidthRatio;
        }

        public double getPairDice() {
            return pairDice;
        }

        public double getConfidence() {
            return confidence;
        }
    }
}
