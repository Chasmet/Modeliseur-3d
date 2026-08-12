package com.chasmet.modeliseur3d.model;

/**
 * Classifieur géométrique léger fondé sur les quatre silhouettes.
 *
 * <p>Il fonctionne hors réseau et sans nouveau modèle neuronal. Une sélection
 * manuelle reste prioritaire pour les sujets ambigus, notamment un conducteur
 * en contact avec un véhicule.</p>
 */
public final class SubjectCategoryClassifier {
    private SubjectCategoryClassifier() {
    }

    public static SubjectCategory resolve(
            SubjectCategory requested,
            boolean[][] masks,
            int width,
            int height,
            int depth
    ) {
        validate(masks, width, height, depth);
        if (requested != null && requested != SubjectCategory.AUTO) {
            return requested;
        }

        boolean[] front = mirroredUnion(masks[0], masks[2], width, height);
        boolean[] side = mirroredUnion(masks[1], masks[3], depth, height);
        Metrics frontMetrics = Metrics.measure(front, width, height);
        Metrics sideMetrics = Metrics.measure(side, depth, height);

        if (looksComposite(front, width, height, frontMetrics)) {
            return SubjectCategory.COMPOSITE_VEHICLE;
        }

        double elongation = sideMetrics.aspect
                / Math.max(0.12, frontMetrics.aspect);
        if (sideMetrics.aspect >= 0.52
                && elongation >= 1.34
                && frontMetrics.lowerWidth < frontMetrics.maximumWidth * 0.72) {
            return SubjectCategory.ANIMAL;
        }

        boolean broadCanopy = frontMetrics.upperWidth
                >= Math.max(3.0, frontMetrics.lowerWidth * 1.45);
        boolean narrowBase = frontMetrics.lowerWidth
                <= frontMetrics.maximumWidth * 0.36;
        if (broadCanopy && narrowBase && frontMetrics.fillRatio < 0.66) {
            return SubjectCategory.PLANT;
        }

        boolean frontRigid = frontMetrics.fillRatio >= 0.69
                && frontMetrics.widthVariation <= 0.23;
        boolean sideRigid = sideMetrics.fillRatio >= 0.66
                && sideMetrics.widthVariation <= 0.27;
        if (frontRigid && sideRigid) {
            return SubjectCategory.ARCHITECTURE_OBJECT;
        }
        return SubjectCategory.CHARACTER;
    }

    private static boolean looksComposite(
            boolean[] front,
            int width,
            int height,
            Metrics metrics
    ) {
        if (metrics.top < 0 || metrics.bottom - metrics.top < 8) {
            return false;
        }
        double middleFill = bandFill(
                front, width, metrics.top, metrics.bottom, 0.22, 0.58
        );
        double lowerFill = bandFill(
                front, width, metrics.top, metrics.bottom, 0.58, 0.96
        );
        return metrics.aspect >= 0.42
                && lowerFill >= 0.36
                && lowerFill >= middleFill * 0.90
                && metrics.lowerWidth >= metrics.upperWidth * 1.18;
    }

    private static double bandFill(
            boolean[] mask,
            int width,
            int top,
            int bottom,
            double startFraction,
            double endFraction
    ) {
        int span = Math.max(1, bottom - top);
        int start = top + (int) Math.floor(span * startFraction);
        int end = top + (int) Math.ceil(span * endFraction);
        start = Math.max(top, Math.min(bottom, start));
        end = Math.max(start, Math.min(bottom, end));
        long foreground = 0L;
        int rows = 0;
        for (int y = start; y <= end; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                foreground += mask[row + x] ? 1L : 0L;
            }
            rows++;
        }
        return foreground / Math.max(1.0, rows * (double) width);
    }

    private static double bandAverageWidth(
            boolean[] mask,
            int width,
            int top,
            int bottom,
            double startFraction,
            double endFraction
    ) {
        int span = Math.max(1, bottom - top);
        int start = top + (int) Math.floor(span * startFraction);
        int end = top + (int) Math.ceil(span * endFraction);
        start = Math.max(top, Math.min(bottom, start));
        end = Math.max(start, Math.min(bottom, end));
        double total = 0.0;
        int rows = 0;
        for (int y = start; y <= end; y++) {
            total += rowWidth(mask, width, y);
            rows++;
        }
        return total / Math.max(1, rows);
    }

    private static int rowWidth(boolean[] mask, int width, int y) {
        int left = width;
        int right = -1;
        int row = y * width;
        for (int x = 0; x < width; x++) {
            if (mask[row + x]) {
                left = Math.min(left, x);
                right = Math.max(right, x);
            }
        }
        return right < left ? 0 : right - left + 1;
    }

    private static boolean[] mirroredUnion(
            boolean[] first,
            boolean[] opposite,
            int width,
            int height
    ) {
        boolean[] union = new boolean[width * height];
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                union[row + x] = first[row + x]
                        || opposite[row + (width - 1 - x)];
            }
        }
        return union;
    }

    private static void validate(
            boolean[][] masks,
            int width,
            int height,
            int depth
    ) {
        if (width < 4 || height < 4 || depth < 4
                || masks == null || masks.length != 4
                || masks[0] == null || masks[0].length != width * height
                || masks[2] == null || masks[2].length != width * height
                || masks[1] == null || masks[1].length != depth * height
                || masks[3] == null || masks[3].length != depth * height) {
            throw new IllegalArgumentException("Silhouettes de classification invalides");
        }
    }

    private static final class Metrics {
        final int top;
        final int bottom;
        final double aspect;
        final double fillRatio;
        final double maximumWidth;
        final double upperWidth;
        final double lowerWidth;
        final double widthVariation;

        Metrics(
                int top,
                int bottom,
                double aspect,
                double fillRatio,
                double maximumWidth,
                double upperWidth,
                double lowerWidth,
                double widthVariation
        ) {
            this.top = top;
            this.bottom = bottom;
            this.aspect = aspect;
            this.fillRatio = fillRatio;
            this.maximumWidth = maximumWidth;
            this.upperWidth = upperWidth;
            this.lowerWidth = lowerWidth;
            this.widthVariation = widthVariation;
        }

        static Metrics measure(boolean[] mask, int width, int height) {
            int top = height;
            int bottom = -1;
            int left = width;
            int right = -1;
            long count = 0L;
            int maximumWidth = 0;
            for (int y = 0; y < height; y++) {
                int rowWidth = rowWidth(mask, width, y);
                maximumWidth = Math.max(maximumWidth, rowWidth);
                if (rowWidth > 0) {
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y);
                }
                int row = y * width;
                for (int x = 0; x < width; x++) {
                    if (mask[row + x]) {
                        left = Math.min(left, x);
                        right = Math.max(right, x);
                        count++;
                    }
                }
            }
            if (bottom < top || right < left) {
                return new Metrics(-1, -1, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0);
            }
            int boxWidth = right - left + 1;
            int boxHeight = bottom - top + 1;
            double mean = 0.0;
            int occupiedRows = 0;
            for (int y = top; y <= bottom; y++) {
                int rowWidth = rowWidth(mask, width, y);
                if (rowWidth > 0) {
                    mean += rowWidth;
                    occupiedRows++;
                }
            }
            mean /= Math.max(1, occupiedRows);
            double variance = 0.0;
            for (int y = top; y <= bottom; y++) {
                int rowWidth = rowWidth(mask, width, y);
                if (rowWidth > 0) {
                    double delta = rowWidth - mean;
                    variance += delta * delta;
                }
            }
            variance /= Math.max(1, occupiedRows);
            return new Metrics(
                    top,
                    bottom,
                    boxWidth / (double) boxHeight,
                    count / Math.max(1.0, boxWidth * (double) boxHeight),
                    maximumWidth,
                    bandAverageWidth(mask, width, top, bottom, 0.05, 0.38),
                    bandAverageWidth(mask, width, top, bottom, 0.62, 0.96),
                    Math.sqrt(variance) / Math.max(1.0, mean)
            );
        }
    }
}
