package com.chasmet.modeliseur3d.model;

/**
 * Analyse pure Java des quatre silhouettes. Cette classe ne touche jamais au
 * moteur 2.5D. Elle harmonise les deux profils et mesure la cohérence du jeu
 * d'images avant la construction du volume 3D.
 */
public final class FourViewAutoCorrector {
    private static final int MAX_SHIFT = 4;

    private FourViewAutoCorrector() {
    }

    public static ProfileCorrection analyzeProfiles(
            boolean[] right,
            boolean[] left,
            int width,
            int height
    ) {
        validate(right, width, height);
        validate(left, width, height);
        double mirrored = bestDice(right, left, width, height, true);
        double sameDirection = bestDice(right, left, width, height, false);
        boolean flipLeft = sameDirection > mirrored + 0.025;
        double selected = flipLeft ? sameDirection : mirrored;
        double confidence = Math.min(1.0, Math.max(0.0,
                Math.abs(sameDirection - mirrored) * 2.5 + selected * 0.35
        ));
        return new ProfileCorrection(
                flipLeft,
                mirrored,
                sameDirection,
                selected,
                confidence
        );
    }

    public static double computeCoherence(
            boolean[] front,
            boolean[] back,
            int frontWidth,
            boolean[] right,
            boolean[] left,
            int sideWidth,
            int height
    ) {
        validate(front, frontWidth, height);
        validate(back, frontWidth, height);
        validate(right, sideWidth, height);
        validate(left, sideWidth, height);

        double rowOverlap = rowOverlap(
                unionRows(front, back, frontWidth, height, true),
                unionRows(right, left, sideWidth, height, true)
        );
        double frontBack = Math.max(
                bestDice(front, back, frontWidth, height, false),
                bestDice(front, back, frontWidth, height, true)
        );
        double rightLeft = Math.max(
                bestDice(right, left, sideWidth, height, false),
                bestDice(right, left, sideWidth, height, true)
        );
        return clamp01(rowOverlap * 0.55 + frontBack * 0.20 + rightLeft * 0.25);
    }

    public static boolean shouldUseAdaptiveHull(double coherence, ProfileCorrection profiles) {
        return coherence < 0.62 || profiles.getSelectedScore() < 0.34;
    }

    public static boolean[] flipHorizontal(boolean[] source, int width, int height) {
        validate(source, width, height);
        boolean[] output = new boolean[source.length];
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                output[row + (width - 1 - x)] = source[row + x];
            }
        }
        return output;
    }

    public static double bestDice(
            boolean[] first,
            boolean[] second,
            int width,
            int height,
            boolean mirrorSecond
    ) {
        validate(first, width, height);
        validate(second, width, height);
        int firstCount = count(first);
        int secondCount = count(second);
        if (firstCount == 0 || secondCount == 0) {
            return 0.0;
        }
        double best = 0.0;
        for (int shiftY = -MAX_SHIFT; shiftY <= MAX_SHIFT; shiftY++) {
            for (int shiftX = -MAX_SHIFT; shiftX <= MAX_SHIFT; shiftX++) {
                int intersection = 0;
                for (int y = 0; y < height; y++) {
                    int sourceY = y - shiftY;
                    if (sourceY < 0 || sourceY >= height) {
                        continue;
                    }
                    int firstRow = y * width;
                    int secondRow = sourceY * width;
                    for (int x = 0; x < width; x++) {
                        if (!first[firstRow + x]) {
                            continue;
                        }
                        int sourceX = x - shiftX;
                        if (sourceX < 0 || sourceX >= width) {
                            continue;
                        }
                        if (mirrorSecond) {
                            sourceX = width - 1 - sourceX;
                        }
                        if (second[secondRow + sourceX]) {
                            intersection++;
                        }
                    }
                }
                double dice = 2.0 * intersection / Math.max(1.0, firstCount + secondCount);
                best = Math.max(best, dice);
            }
        }
        return best;
    }

    private static double[] unionRows(
            boolean[] first,
            boolean[] second,
            int width,
            int height,
            boolean mirrorSecond
    ) {
        double[] rows = new double[height];
        double maximum = 1.0;
        for (int y = 0; y < height; y++) {
            int count = 0;
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int secondX = mirrorSecond ? width - 1 - x : x;
                if (first[row + x] || second[row + secondX]) {
                    count++;
                }
            }
            rows[y] = count;
            maximum = Math.max(maximum, count);
        }
        for (int y = 0; y < height; y++) {
            rows[y] /= maximum;
        }
        return rows;
    }

    private static double rowOverlap(double[] first, double[] second) {
        if (first.length != second.length) {
            throw new IllegalArgumentException("Profils verticaux incohérents");
        }
        double minimum = 0.0;
        double maximum = 0.0;
        for (int index = 0; index < first.length; index++) {
            minimum += Math.min(first[index], second[index]);
            maximum += Math.max(first[index], second[index]);
        }
        return maximum <= 0.0 ? 0.0 : minimum / maximum;
    }

    private static int count(boolean[] values) {
        int count = 0;
        for (boolean value : values) {
            if (value) {
                count++;
            }
        }
        return count;
    }

    private static void validate(boolean[] mask, int width, int height) {
        if (mask == null || width < 2 || height < 2 || mask.length != width * height) {
            throw new IllegalArgumentException("Silhouette invalide");
        }
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    public static final class ProfileCorrection {
        private final boolean flipLeft;
        private final double mirroredScore;
        private final double sameDirectionScore;
        private final double selectedScore;
        private final double confidence;

        ProfileCorrection(
                boolean flipLeft,
                double mirroredScore,
                double sameDirectionScore,
                double selectedScore,
                double confidence
        ) {
            this.flipLeft = flipLeft;
            this.mirroredScore = mirroredScore;
            this.sameDirectionScore = sameDirectionScore;
            this.selectedScore = selectedScore;
            this.confidence = confidence;
        }

        public boolean shouldFlipLeft() {
            return flipLeft;
        }

        public double getMirroredScore() {
            return mirroredScore;
        }

        public double getSameDirectionScore() {
            return sameDirectionScore;
        }

        public double getSelectedScore() {
            return selectedScore;
        }

        public double getConfidence() {
            return confidence;
        }
    }
}
