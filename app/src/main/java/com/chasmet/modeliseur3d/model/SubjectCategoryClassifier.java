package com.chasmet.modeliseur3d.model;

/**
 * Classifieur géométrique léger fondé sur les quatre silhouettes.
 *
 * <p>V7.3 conserve la sélection manuelle prioritaire et ajoute un stabilisateur
 * de continuité verticale pour les formes articulées. Une séparation de pattes,
 * jambes ou bras observée sur plusieurs lignes ne peut plus disparaître pendant
 * une ou deux lignes puis réapparaître comme une masse différente.</p>
 */
public final class SubjectCategoryClassifier {
    private static final int MAX_MERGE_ROWS = 3;

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
        SubjectCategory resolved = requested != null && requested != SubjectCategory.AUTO
                ? requested
                : resolveAuto(masks, width, height, depth);
        stabilizeComponentContinuity(masks, width, height, depth, resolved);
        return resolved;
    }

    private static SubjectCategory resolveAuto(
            boolean[][] masks,
            int width,
            int height,
            int depth
    ) {
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

    /**
     * Corrige seulement les fusions verticales courtes dans les zones où des
     * membres distincts sont attendus. Aucun pixel n'est ajouté : le filtre ne
     * peut donc pas inventer une silhouette absente des prises de vue.
     */
    private static void stabilizeComponentContinuity(
            boolean[][] masks,
            int width,
            int height,
            int depth,
            SubjectCategory category
    ) {
        if (category == SubjectCategory.ARCHITECTURE_OBJECT
                || category == SubjectCategory.AUTO) {
            return;
        }
        stabilizeMask(masks[0], width, height, category);
        stabilizeMask(masks[2], width, height, category);
        stabilizeMask(masks[1], depth, height, category);
        stabilizeMask(masks[3], depth, height, category);
    }

    private static void stabilizeMask(
            boolean[] mask,
            int width,
            int height,
            SubjectCategory category
    ) {
        int top = firstOccupiedRow(mask, width, height);
        int bottom = lastOccupiedRow(mask, width, height);
        if (top < 0 || bottom <= top) {
            return;
        }

        if (category == SubjectCategory.ANIMAL) {
            stabilizeDirection(mask, width, top, bottom, 0.60, 0.99, false);
        } else if (category == SubjectCategory.CHARACTER) {
            stabilizeDirection(mask, width, top, bottom, 0.56, 0.99, false);
        } else if (category == SubjectCategory.COMPOSITE_VEHICLE) {
            // Le conducteur est articulé ; la base véhicule doit rester pleine.
            stabilizeDirection(mask, width, top, bottom, 0.08, 0.47, true);
        } else if (category == SubjectCategory.PLANT) {
            // Stabilise seulement le tronc et les branches basses, pas la canopée.
            stabilizeDirection(mask, width, top, bottom, 0.52, 0.99, false);
        }
    }

    private static void stabilizeDirection(
            boolean[] mask,
            int width,
            int top,
            int bottom,
            double startFraction,
            double endFraction,
            boolean topDown
    ) {
        int span = Math.max(1, bottom - top);
        int start = top + (int) Math.floor(span * startFraction);
        int end = top + (int) Math.ceil(span * endFraction);
        start = Math.max(top, Math.min(bottom, start));
        end = Math.max(start, Math.min(bottom, end));

        TrackRun[] stable = null;
        int mergedRows = 0;
        int y = topDown ? start : end;
        int stop = topDown ? end : start;
        int step = topDown ? 1 : -1;

        while (topDown ? y <= stop : y >= stop) {
            TrackRun[] current = extractRuns(mask, width, y);
            if (current.length >= 2) {
                stable = current;
                mergedRows = 0;
            } else if (current.length == 1 && stable != null && stable.length >= 2) {
                mergedRows++;
                if (mergedRows <= MAX_MERGE_ROWS
                        && canRestoreSeparation(current[0], stable, width)) {
                    carvePredictedSeams(mask, width, y, current[0], stable);
                    TrackRun[] repaired = extractRuns(mask, width, y);
                    if (repaired.length >= 2) {
                        stable = repaired;
                        mergedRows = 0;
                    }
                } else if (mergedRows > MAX_MERGE_ROWS) {
                    stable = null;
                    mergedRows = 0;
                }
            } else if (current.length == 0) {
                stable = null;
                mergedRows = 0;
            } else if (stable != null && current.length == 1) {
                stable = null;
                mergedRows = 0;
            }
            y += step;
        }
    }

    private static boolean canRestoreSeparation(
            TrackRun merged,
            TrackRun[] stable,
            int width
    ) {
        if (stable.length < 2 || merged.width() < 5) {
            return false;
        }
        int inside = 0;
        int totalStableWidth = 0;
        for (TrackRun run : stable) {
            if (run.center >= merged.start - 1 && run.center <= merged.end + 1) {
                inside++;
                totalStableWidth += run.width();
            }
        }
        if (inside < 2) {
            return false;
        }
        int addedBridge = merged.width() - totalStableWidth;
        return addedBridge >= 1
                && merged.width() <= Math.max(6, (int) Math.round(width * 0.62));
    }

    private static void carvePredictedSeams(
            boolean[] mask,
            int width,
            int y,
            TrackRun merged,
            TrackRun[] stable
    ) {
        for (int i = 0; i < stable.length - 1; i++) {
            TrackRun left = stable[i];
            TrackRun right = stable[i + 1];
            if (left.center < merged.start || right.center > merged.end) {
                continue;
            }
            int seam = Math.round((left.center + right.center) * 0.5f);
            seam = Math.max(merged.start + 1, Math.min(merged.end - 1, seam));
            mask[y * width + seam] = false;

            // Sur les silhouettes larges, deux pixels évitent qu'un champ
            // sous-pixel referme immédiatement la séparation au maillage.
            if (merged.width() >= 14 && seam + 1 < merged.end) {
                mask[y * width + seam + 1] = false;
            }
        }
    }

    private static TrackRun[] extractRuns(boolean[] mask, int width, int y) {
        TrackRun[] temporary = new TrackRun[Math.max(1, width / 2 + 1)];
        int count = 0;
        int row = y * width;
        int x = 0;
        while (x < width) {
            while (x < width && !mask[row + x]) {
                x++;
            }
            if (x >= width) {
                break;
            }
            int start = x;
            while (x + 1 < width && mask[row + x + 1]) {
                x++;
            }
            int end = x;
            if (count == temporary.length) {
                TrackRun[] grown = new TrackRun[temporary.length * 2];
                System.arraycopy(temporary, 0, grown, 0, temporary.length);
                temporary = grown;
            }
            temporary[count++] = new TrackRun(start, end);
            x++;
        }
        TrackRun[] output = new TrackRun[count];
        System.arraycopy(temporary, 0, output, 0, count);
        return output;
    }

    private static int firstOccupiedRow(boolean[] mask, int width, int height) {
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                if (mask[row + x]) {
                    return y;
                }
            }
        }
        return -1;
    }

    private static int lastOccupiedRow(boolean[] mask, int width, int height) {
        for (int y = height - 1; y >= 0; y--) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                if (mask[row + x]) {
                    return y;
                }
            }
        }
        return -1;
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

    private static final class TrackRun {
        final int start;
        final int end;
        final float center;

        TrackRun(int start, int end) {
            this.start = start;
            this.end = end;
            this.center = (start + end) * 0.5f;
        }

        int width() {
            return end - start + 1;
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
