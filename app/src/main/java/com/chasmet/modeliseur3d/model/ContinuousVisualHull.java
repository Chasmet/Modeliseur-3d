package com.chasmet.modeliseur3d.model;

import java.util.Arrays;

/**
 * Enveloppe visuelle multivue continue pour la reconstruction V6.
 *
 * <p>Le projecteur historique travaillait uniquement avec des voxels vrais ou
 * faux. Une grande quantité de triangles pouvait ensuite lisser ces marches,
 * mais l'information de contour perdue ne revenait jamais. Cette classe garde
 * une distance signée sous-pixel pour chaque silhouette, combine les vues
 * opposées de façon robuste et arrondit uniquement les coins artificiels de
 * l'intersection orthographique.</p>
 *
 * <p>La classe reste en Java pur afin que la géométrie puisse être validée dans
 * GitHub Actions sans émulateur Android.</p>
 */
public final class ContinuousVisualHull {
    private static final float DISTANCE_INFINITY = 1.0e12f;
    private static final float TRANSITION_RADIUS = 0.95f;
    private static final float ADAPTIVE_PAIR_WEIGHT = 0.40f;

    private ContinuousVisualHull() {
    }

    public static Result build(
            float[][] confidences,
            boolean[][] masks,
            int width,
            int height,
            int depth,
            boolean adaptive
    ) {
        return build(
                confidences,
                masks,
                width,
                height,
                depth,
                adaptive,
                SubjectCategory.AUTO
        );
    }

    public static Result build(
            float[][] confidences,
            boolean[][] masks,
            int width,
            int height,
            int depth,
            boolean adaptive,
            SubjectCategory requestedCategory
    ) {
        validate(confidences, masks, width, height, depth);

        SubjectCategory category = SubjectCategoryClassifier.resolve(
                requestedCategory,
                masks,
                width,
                height,
                depth
        );

        float[][] signed = new float[4][];
        signed[StylizedFourViewProjector.FRONT] = signedDistance(
                masks[StylizedFourViewProjector.FRONT], width, height
        );
        signed[StylizedFourViewProjector.BACK] = signedDistance(
                masks[StylizedFourViewProjector.BACK], width, height
        );
        signed[StylizedFourViewProjector.RIGHT] = signedDistance(
                masks[StylizedFourViewProjector.RIGHT], depth, height
        );
        signed[StylizedFourViewProjector.LEFT] = signedDistance(
                masks[StylizedFourViewProjector.LEFT], depth, height
        );

        boolean[] frontUnion = mirroredUnion(
                masks[StylizedFourViewProjector.FRONT],
                masks[StylizedFourViewProjector.BACK],
                width,
                height
        );
        boolean[] sideUnion = mirroredUnion(
                masks[StylizedFourViewProjector.RIGHT],
                masks[StylizedFourViewProjector.LEFT],
                depth,
                height
        );
        boolean complexShapeMode = category == SubjectCategory.COMPOSITE_VEHICLE;
        RowShape[] rows = buildRows(
                frontUnion,
                sideUnion,
                width,
                height,
                depth,
                category
        );

        int size = width * height * depth;
        float[] density = new float[size];
        boolean[] occupancy = new boolean[size];
        int occupied = 0;
        int fractional = 0;
        int roundedAway = 0;
        int adaptivelyRecovered = 0;

        for (int y = 0; y < height; y++) {
            int frontRow = y * width;
            int sideRow = y * depth;
            RowShape row = rows[y];
            for (int x = 0; x < width; x++) {
                int frontIndex = frontRow + x;
                int backIndex = frontRow + (width - 1 - x);
                float frontDistance = signed[StylizedFourViewProjector.FRONT][frontIndex];
                float backDistance = signed[StylizedFourViewProjector.BACK][backIndex];
                float frontConfidence = confidences[StylizedFourViewProjector.FRONT][frontIndex];
                float backConfidence = confidences[StylizedFourViewProjector.BACK][backIndex];

                for (int z = 0; z < depth; z++) {
                    int sideIndex = sideRow + z;
                    int leftIndex = sideRow + (depth - 1 - z);
                    float rightDistance = signed[StylizedFourViewProjector.RIGHT][sideIndex];
                    float leftDistance = signed[StylizedFourViewProjector.LEFT][leftIndex];
                    float rightConfidence = confidences[StylizedFourViewProjector.RIGHT][sideIndex];
                    float leftConfidence = confidences[StylizedFourViewProjector.LEFT][leftIndex];

                    float strictField = minimum(
                            frontDistance,
                            backDistance,
                            rightDistance,
                            leftDistance
                    );
                    float roundedField = row.signedDistance(x, z);
                    float field = strictField;

                    if (adaptive) {
                        float frontBack = robustPair(frontDistance, backDistance);
                        float rightLeft = robustPair(rightDistance, leftDistance);
                        float robustField = Math.min(frontBack, rightLeft);
                        float axisField = Math.min(
                                Math.max(frontDistance, backDistance),
                                Math.max(rightDistance, leftDistance)
                        );
                        int support = support(frontDistance)
                                + support(backDistance)
                                + support(rightDistance)
                                + support(leftDistance);
                        boolean bothAxes = Math.max(frontDistance, backDistance) >= -0.35f
                                && Math.max(rightDistance, leftDistance) >= -0.35f;
                        if (bothAxes && support >= 3) {
                            robustField = Math.max(
                                    robustField,
                                    Math.min(axisField, roundedField)
                            );
                        } else if (bothAxes && support >= 2 && roundedField > 0.20f) {
                            robustField = Math.max(
                                    robustField,
                                    Math.min(axisField, roundedField * 0.82f)
                            );
                        }
                        field = robustField;
                    }

                    if (field >= 0.0f && roundedField < 0.0f) {
                        roundedAway++;
                    }
                    field = Math.min(field, roundedField);
                    field += confidenceBias(
                            frontDistance,
                            backDistance,
                            rightDistance,
                            leftDistance,
                            frontConfidence,
                            backConfidence,
                            rightConfidence,
                            leftConfidence
                    );

                    int voxel = index(x, y, z, width, depth);
                    float value;
                    if (x == 0 || y == 0 || z == 0
                            || x == width - 1 || y == height - 1 || z == depth - 1) {
                        value = 0.0f;
                    } else {
                        value = smoothStep(
                                -TRANSITION_RADIUS,
                                TRANSITION_RADIUS,
                                field
                        );
                    }
                    density[voxel] = value;
                    if (value > 0.02f && value < 0.98f) {
                        fractional++;
                    }
                    if (value >= 0.5f) {
                        occupancy[voxel] = true;
                        occupied++;
                        if (adaptive && strictField < 0.0f) {
                            adaptivelyRecovered++;
                        }
                    }
                }
            }
        }

        double silhouetteScore = projectionScore(
                occupancy,
                frontUnion,
                sideUnion,
                width,
                height,
                depth
        );
        return new Result(
                density,
                occupancy,
                occupied,
                fractional,
                roundedAway,
                adaptivelyRecovered,
                silhouetteScore,
                complexShapeMode,
                category
        );
    }

    private static float robustPair(float first, float second) {
        float lower = Math.min(first, second);
        float upper = Math.max(first, second);
        return lower + (upper - lower) * ADAPTIVE_PAIR_WEIGHT;
    }

    private static int support(float distance) {
        return distance >= -0.35f ? 1 : 0;
    }

    /**
     * Le biais n'agit que dans la bande proche du contour. L'intérieur du
     * volume reste dicté par les silhouettes, tandis qu'un bord IS-Net doux
     * décale la surface de quelques dixièmes de voxel au lieu d'être tronqué.
     */
    private static float confidenceBias(
            float frontDistance,
            float backDistance,
            float rightDistance,
            float leftDistance,
            float frontConfidence,
            float backConfidence,
            float rightConfidence,
            float leftConfidence
    ) {
        float[] distances = {
                frontDistance, backDistance, rightDistance, leftDistance
        };
        float[] confidence = {
                frontConfidence, backConfidence, rightConfidence, leftConfidence
        };
        float weighted = 0.0f;
        float totalWeight = 0.0f;
        for (int view = 0; view < distances.length; view++) {
            float absolute = Math.abs(distances[view]);
            if (absolute > 1.75f) {
                continue;
            }
            float weight = 1.0f - absolute / 1.75f;
            weighted += (clamp01(confidence[view]) - 0.5f) * weight;
            totalWeight += weight;
        }
        if (totalWeight <= 0.0f) {
            return 0.0f;
        }
        return clamp(weighted / totalWeight * 0.36f, -0.22f, 0.22f);
    }

    private static RowShape[] buildRows(
            boolean[] frontUnion,
            boolean[] sideUnion,
            int width,
            int height,
            int depth,
            SubjectCategory category
    ) {
        int top = firstOccupiedRow(frontUnion, width, height);
        int bottom = lastOccupiedRow(frontUnion, width, height);
        RowShape[] rows = new RowShape[height];
        for (int y = 0; y < height; y++) {
            float progress = top < 0 || bottom <= top
                    ? 0.5f
                    : clamp01((y - top) / (float) (bottom - top));
            rows[y] = RowShape.from(
                    frontUnion,
                    sideUnion,
                    width,
                    depth,
                    y,
                    progress,
                    category
            );
        }
        return rows;
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

    private static float[] signedDistance(boolean[] mask, int width, int height) {
        int foreground = 0;
        for (boolean value : mask) {
            if (value) {
                foreground++;
            }
        }
        if (foreground == 0 || foreground == mask.length) {
            throw new IllegalArgumentException(
                    "Une silhouette doit contenir du sujet et une marge de fond"
            );
        }
        float[] toForeground = squaredDistance(mask, width, height, true);
        float[] toBackground = squaredDistance(mask, width, height, false);
        float[] signed = new float[mask.length];
        for (int index = 0; index < mask.length; index++) {
            if (mask[index]) {
                signed[index] = (float) Math.sqrt(toBackground[index]) - 0.5f;
            } else {
                signed[index] = 0.5f - (float) Math.sqrt(toForeground[index]);
            }
        }
        return signed;
    }

    /** Transformée de distance euclidienne exacte en O(nombre de pixels). */
    private static float[] squaredDistance(
            boolean[] mask,
            int width,
            int height,
            boolean seedValue
    ) {
        int longest = Math.max(width, height);
        float[] line = new float[longest];
        float[] transformed = new float[longest];
        int[] sites = new int[longest];
        float[] intersections = new float[longest + 1];
        float[] horizontal = new float[mask.length];
        float[] output = new float[mask.length];

        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                line[x] = mask[row + x] == seedValue ? 0.0f : DISTANCE_INFINITY;
            }
            distanceTransform1D(line, width, transformed, sites, intersections);
            System.arraycopy(transformed, 0, horizontal, row, width);
        }

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                line[y] = horizontal[y * width + x];
            }
            distanceTransform1D(line, height, transformed, sites, intersections);
            for (int y = 0; y < height; y++) {
                output[y * width + x] = transformed[y];
            }
        }
        return output;
    }

    private static void distanceTransform1D(
            float[] source,
            int length,
            float[] output,
            int[] sites,
            float[] intersections
    ) {
        int last = 0;
        sites[0] = 0;
        intersections[0] = Float.NEGATIVE_INFINITY;
        intersections[1] = Float.POSITIVE_INFINITY;

        for (int q = 1; q < length; q++) {
            float crossing;
            do {
                int site = sites[last];
                double numerator = (double) source[q] + (double) q * q
                        - source[site] - (double) site * site;
                crossing = (float) (numerator / (2.0 * (q - site)));
                if (crossing > intersections[last]) {
                    break;
                }
                last--;
            } while (last >= 0);
            if (last < 0) {
                last = 0;
                sites[0] = q;
                intersections[0] = Float.NEGATIVE_INFINITY;
                intersections[1] = Float.POSITIVE_INFINITY;
            } else {
                last++;
                sites[last] = q;
                intersections[last] = crossing;
                intersections[last + 1] = Float.POSITIVE_INFINITY;
            }
        }

        last = 0;
        for (int q = 0; q < length; q++) {
            while (intersections[last + 1] < q) {
                last++;
            }
            int site = sites[last];
            float delta = q - site;
            output[q] = delta * delta + source[site];
        }
    }

    private static double projectionScore(
            boolean[] occupancy,
            boolean[] frontTarget,
            boolean[] sideTarget,
            int width,
            int height,
            int depth
    ) {
        boolean[] frontProjection = new boolean[width * height];
        boolean[] sideProjection = new boolean[depth * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int base = (y * width + x) * depth;
                for (int z = 0; z < depth; z++) {
                    if (occupancy[base + z]) {
                        frontProjection[y * width + x] = true;
                        sideProjection[y * depth + z] = true;
                    }
                }
            }
        }
        return (intersectionOverUnion(frontProjection, frontTarget)
                + intersectionOverUnion(sideProjection, sideTarget)) * 0.5;
    }

    private static double intersectionOverUnion(boolean[] first, boolean[] second) {
        int intersection = 0;
        int union = 0;
        for (int index = 0; index < first.length; index++) {
            if (first[index] || second[index]) {
                union++;
                if (first[index] && second[index]) {
                    intersection++;
                }
            }
        }
        return union == 0 ? 0.0 : intersection / (double) union;
    }

    private static float minimum(float first, float second, float third, float fourth) {
        return Math.min(Math.min(first, second), Math.min(third, fourth));
    }

    private static float smoothStep(float edge0, float edge1, float value) {
        float amount = clamp01((value - edge0) / Math.max(0.0001f, edge1 - edge0));
        return amount * amount * (3.0f - 2.0f * amount);
    }

    private static float clamp01(float value) {
        return clamp(value, 0.0f, 1.0f);
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static int index(int x, int y, int z, int width, int depth) {
        return (y * width + x) * depth + z;
    }

    private static void validate(
            float[][] confidences,
            boolean[][] masks,
            int width,
            int height,
            int depth
    ) {
        if (width < 8 || height < 8 || depth < 8) {
            throw new IllegalArgumentException("Résolution continue trop faible");
        }
        if (confidences == null || confidences.length != 4
                || masks == null || masks.length != 4) {
            throw new IllegalArgumentException("Quatre silhouettes continues sont requises");
        }
        int frontSize = width * height;
        int sideSize = depth * height;
        for (int view = 0; view < 4; view++) {
            int expected = view == StylizedFourViewProjector.FRONT
                    || view == StylizedFourViewProjector.BACK
                    ? frontSize
                    : sideSize;
            if (masks[view] == null || masks[view].length != expected
                    || confidences[view] == null || confidences[view].length != expected) {
                throw new IllegalArgumentException("Dimensions multivues incohérentes");
            }
            for (float value : confidences[view]) {
                if (!Float.isFinite(value)) {
                    throw new IllegalArgumentException("Confiance de contour invalide");
                }
            }
        }
    }

    private static final class RowShape {
        private final Run[] runs;
        private final int[] runAtX;
        private final float centerZ;
        private final float radiusZ;
        private final boolean valid;

        private RowShape(
                Run[] runs,
                int[] runAtX,
                float centerZ,
                float radiusZ,
                boolean valid
        ) {
            this.runs = runs;
            this.runAtX = runAtX;
            this.centerZ = centerZ;
            this.radiusZ = radiusZ;
            this.valid = valid;
        }

        static RowShape from(
                boolean[] front,
                boolean[] side,
                int width,
                int depth,
                int y,
                float bodyProgress,
                SubjectCategory category
        ) {
            int[] starts = new int[width];
            int[] ends = new int[width];
            int runCount = 0;
            int row = y * width;
            int x = 0;
            while (x < width) {
                while (x < width && !front[row + x]) {
                    x++;
                }
                if (x >= width) {
                    break;
                }
                int start = x;
                while (x + 1 < width && front[row + x + 1]) {
                    x++;
                }
                starts[runCount] = start;
                ends[runCount] = x;
                runCount++;
                x++;
            }

            int minZ = depth;
            int maxZ = -1;
            int sideRow = y * depth;
            for (int z = 0; z < depth; z++) {
                if (side[sideRow + z]) {
                    minZ = Math.min(minZ, z);
                    maxZ = Math.max(maxZ, z);
                }
            }
            if (runCount == 0 || maxZ < minZ) {
                int[] none = new int[width];
                Arrays.fill(none, -1);
                return new RowShape(new Run[0], none, 0.0f, 1.0f, false);
            }

            int maximumRun = 1;
            int minX = width;
            int maxX = -1;
            for (int run = 0; run < runCount; run++) {
                int runWidth = ends[run] - starts[run] + 1;
                maximumRun = Math.max(maximumRun, runWidth);
                minX = Math.min(minX, starts[run]);
                maxX = Math.max(maxX, ends[run]);
            }
            float overallCenter = (minX + maxX) * 0.5f;
            int[] runAtX = new int[width];
            Arrays.fill(runAtX, -1);
            Run[] runs = new Run[runCount];
            for (int run = 0; run < runCount; run++) {
                int start = starts[run];
                int end = ends[run];
                int runWidth = end - start + 1;
                float centerX = (start + end) * 0.5f;
                float ratio = runWidth / (float) maximumRun;
                boolean dominant = runWidth >= maximumRun * 0.82f
                        && Math.abs(centerX - overallCenter) <= maximumRun * 0.28f;
                float depthScale;
                float exponent;
                boolean vehicleLayer = category == SubjectCategory.COMPOSITE_VEHICLE
                        && bodyProgress >= 0.46f;
                if (vehicleLayer) {
                    depthScale = runCount == 1
                            ? 0.98f
                            : 0.82f + 0.16f * (float) Math.sqrt(ratio);
                    exponent = 2.70f;
                } else if (category == SubjectCategory.ARCHITECTURE_OBJECT) {
                    depthScale = 1.0f;
                    exponent = 7.0f;
                } else if (category == SubjectCategory.ANIMAL) {
                    if (runCount == 1 || dominant) {
                        depthScale = 1.0f;
                        exponent = 3.35f;
                    } else {
                        depthScale = 0.60f + 0.25f * (float) Math.sqrt(ratio);
                        if (bodyProgress >= 0.56f) {
                            depthScale = Math.min(depthScale, 0.78f);
                        }
                        exponent = 2.25f;
                    }
                } else if (category == SubjectCategory.PLANT) {
                    depthScale = runCount == 1
                            ? 0.88f
                            : 0.46f + 0.24f * (float) Math.sqrt(ratio);
                    exponent = 2.05f;
                } else if (runCount == 1) {
                    depthScale = bodyProgress < 0.24f ? 0.94f : 0.98f;
                    exponent = 3.20f;
                } else if (dominant) {
                    depthScale = 0.94f;
                    exponent = 3.05f;
                } else {
                    depthScale = 0.54f + 0.22f * (float) Math.sqrt(ratio);
                    if (bodyProgress >= 0.50f) {
                        depthScale = Math.min(depthScale, 0.72f);
                    }
                    exponent = 2.35f;
                }
                runs[run] = new Run(
                        centerX,
                        Math.max(0.70f, runWidth * 0.5f),
                        depthScale,
                        exponent
                );
                for (int column = start; column <= end; column++) {
                    runAtX[column] = run;
                }
            }
            return new RowShape(
                    runs,
                    runAtX,
                    (minZ + maxZ) * 0.5f,
                    Math.max(0.75f, (maxZ - minZ + 1) * 0.5f),
                    true
            );
        }

        float signedDistance(int x, int z) {
            if (!valid || x < 0 || x >= runAtX.length) {
                return -4.0f;
            }
            int runIndex = runAtX[x];
            if (runIndex < 0) {
                return -4.0f;
            }
            Run run = runs[runIndex];
            float localRadiusZ = Math.max(0.72f, radiusZ * run.depthScale);
            float normalizedX = Math.abs(x - run.centerX) / run.radiusX;
            float normalizedZ = Math.abs(z - centerZ) / localRadiusZ;
            float implicit = 1.0f
                    - (float) Math.pow(normalizedX, run.exponent)
                    - (float) Math.pow(normalizedZ, run.exponent);
            return implicit * Math.min(run.radiusX, localRadiusZ) * 0.72f;
        }
    }

    private static final class Run {
        final float centerX;
        final float radiusX;
        final float depthScale;
        final float exponent;

        Run(float centerX, float radiusX, float depthScale, float exponent) {
            this.centerX = centerX;
            this.radiusX = radiusX;
            this.depthScale = depthScale;
            this.exponent = exponent;
        }
    }

    public static final class Result {
        private final float[] density;
        private final boolean[] occupancy;
        private final int occupiedVoxels;
        private final int fractionalSamples;
        private final int roundedVoxels;
        private final int adaptivelyRecoveredVoxels;
        private final double silhouetteScore;
        private final boolean complexShapeMode;
        private final SubjectCategory category;

        Result(
                float[] density,
                boolean[] occupancy,
                int occupiedVoxels,
                int fractionalSamples,
                int roundedVoxels,
                int adaptivelyRecoveredVoxels,
                double silhouetteScore,
                boolean complexShapeMode,
                SubjectCategory category
        ) {
            this.density = density;
            this.occupancy = occupancy;
            this.occupiedVoxels = occupiedVoxels;
            this.fractionalSamples = fractionalSamples;
            this.roundedVoxels = roundedVoxels;
            this.adaptivelyRecoveredVoxels = adaptivelyRecoveredVoxels;
            this.silhouetteScore = silhouetteScore;
            this.complexShapeMode = complexShapeMode;
            this.category = category;
        }

        public float[] getDensity() {
            return density;
        }

        public boolean[] getOccupancy() {
            return occupancy;
        }

        public int getOccupiedVoxels() {
            return occupiedVoxels;
        }

        public int getFractionalSamples() {
            return fractionalSamples;
        }

        public int getRoundedVoxels() {
            return roundedVoxels;
        }

        public int getAdaptivelyRecoveredVoxels() {
            return adaptivelyRecoveredVoxels;
        }

        public double getSilhouetteScore() {
            return silhouetteScore;
        }

        public boolean isComplexShapeMode() {
            return complexShapeMode;
        }

        public SubjectCategory getCategory() {
            return category;
        }
    }
}
