package com.chasmet.modeliseur3d.model;

import java.util.Arrays;

/**
 * V7.3 structural hotfix: component-aware continuous visual hull.
 *
 * <p>The four silhouettes remain hard constraints. Disconnected runs from the
 * front and side silhouettes are preserved on both axes, so repeated thin
 * parts such as quadruped legs, human limbs and branches are no longer
 * extruded through the complete opposite silhouette.</p>
 */
public final class ContinuousVisualHull {
    private static final float DISTANCE_INFINITY = 1.0e12f;
    private static final float TRANSITION_RADIUS = 0.88f;
    private static final float ADAPTIVE_PAIR_WEIGHT = 0.40f;
    private static final float ISO = 0.50f;

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
                confidences, masks, width, height, depth, adaptive,
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
                requestedCategory, masks, width, height, depth
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
                masks[StylizedFourViewProjector.BACK], width, height
        );
        boolean[] sideUnion = mirroredUnion(
                masks[StylizedFourViewProjector.RIGHT],
                masks[StylizedFourViewProjector.LEFT], depth, height
        );

        int top = Math.min(
                firstOccupiedRow(frontUnion, width, height),
                firstOccupiedRow(sideUnion, depth, height)
        );
        int bottom = Math.max(
                lastOccupiedRow(frontUnion, width, height),
                lastOccupiedRow(sideUnion, depth, height)
        );
        if (top < 0) {
            top = 0;
        }
        if (bottom < top) {
            bottom = height - 1;
        }

        RowComponents[] rows = new RowComponents[height];
        for (int y = 0; y < height; y++) {
            float progress = bottom <= top
                    ? 0.5f
                    : clamp01((y - top) / (float) (bottom - top));
            rows[y] = RowComponents.from(
                    frontUnion, sideUnion, width, depth, y, progress, category
            );
        }

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
            RowComponents row = rows[y];
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
                            frontDistance, backDistance,
                            rightDistance, leftDistance
                    );
                    float componentField = row.signedDistance(x, z);
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
                                    Math.min(axisField, componentField)
                            );
                        } else if (bothAxes && support >= 2 && componentField > 0.20f) {
                            robustField = Math.max(
                                    robustField,
                                    Math.min(axisField, componentField * 0.80f)
                            );
                        }
                        field = robustField;
                    }

                    if (field >= 0.0f && componentField < 0.0f) {
                        roundedAway++;
                    }
                    field = Math.min(field, componentField);
                    field += confidenceBias(
                            frontDistance, backDistance, rightDistance, leftDistance,
                            frontConfidence, backConfidence,
                            rightConfidence, leftConfidence
                    );

                    int voxel = index(x, y, z, width, depth);
                    float value;
                    if (x == 0 || y == 0 || z == 0
                            || x == width - 1 || y == height - 1 || z == depth - 1) {
                        value = 0.0f;
                    } else {
                        value = smoothStep(
                                -TRANSITION_RADIUS, TRANSITION_RADIUS, field
                        );
                    }
                    density[voxel] = value;
                    if (value > 0.02f && value < 0.98f) {
                        fractional++;
                    }
                    if (value >= ISO) {
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
                occupancy, frontUnion, sideUnion, width, height, depth
        );
        return new Result(
                density,
                occupancy,
                occupied,
                fractional,
                roundedAway,
                adaptivelyRecovered,
                silhouetteScore,
                category == SubjectCategory.COMPOSITE_VEHICLE,
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
            if (absolute > 1.65f) {
                continue;
            }
            float weight = 1.0f - absolute / 1.65f;
            weighted += (clamp01(confidence[view]) - 0.5f) * weight;
            totalWeight += weight;
        }
        if (totalWeight <= 0.0f) {
            return 0.0f;
        }
        return clamp(weighted / totalWeight * 0.30f, -0.18f, 0.18f);
    }

    private static final class RowComponents {
        final Run[] xRuns;
        final Run[] zRuns;
        final int[] xRunAt;
        final int[] zRunAt;
        final boolean valid;
        final boolean orthogonalComponents;
        final SubjectCategory category;
        final float progress;

        private RowComponents(
                Run[] xRuns,
                Run[] zRuns,
                int[] xRunAt,
                int[] zRunAt,
                boolean valid,
                boolean orthogonalComponents,
                SubjectCategory category,
                float progress
        ) {
            this.xRuns = xRuns;
            this.zRuns = zRuns;
            this.xRunAt = xRunAt;
            this.zRunAt = zRunAt;
            this.valid = valid;
            this.orthogonalComponents = orthogonalComponents;
            this.category = category;
            this.progress = progress;
        }

        static RowComponents from(
                boolean[] front,
                boolean[] side,
                int width,
                int depth,
                int y,
                float progress,
                SubjectCategory category
        ) {
            RunSet frontRuns = RunSet.extract(front, width, y);
            RunSet sideRuns = RunSet.extract(side, depth, y);
            if (frontRuns.runs.length == 0 || sideRuns.runs.length == 0) {
                return new RowComponents(
                        frontRuns.runs, sideRuns.runs,
                        frontRuns.index, sideRuns.index,
                        false, false, category, progress
                );
            }

            boolean lowerArticulated = progress >= 0.50f
                    && (category == SubjectCategory.CHARACTER
                    || category == SubjectCategory.ANIMAL);
            boolean upperComposite = category == SubjectCategory.COMPOSITE_VEHICLE
                    && progress < 0.48f;
            boolean plantBranches = category == SubjectCategory.PLANT
                    && (frontRuns.runs.length > 1 || sideRuns.runs.length > 1);
            boolean orthogonal = (frontRuns.runs.length > 1
                    && sideRuns.runs.length > 1)
                    || lowerArticulated
                    || upperComposite
                    || plantBranches;

            return new RowComponents(
                    frontRuns.runs, sideRuns.runs,
                    frontRuns.index, sideRuns.index,
                    true, orthogonal, category, progress
            );
        }

        float signedDistance(int x, int z) {
            if (!valid || x < 0 || x >= xRunAt.length
                    || z < 0 || z >= zRunAt.length) {
                return -4.0f;
            }
            int xId = xRunAt[x];
            int zId = zRunAt[z];
            if (xId < 0 || zId < 0) {
                return -4.0f;
            }

            Run xr = xRuns[xId];
            Run zr = zRuns[zId];
            float radiusX = Math.max(0.66f, xr.radius);
            float radiusZ = Math.max(0.66f, zr.radius);
            float exponent = exponent();

            if (orthogonalComponents && zRuns.length == 1 && xRuns.length > 1) {
                float localScale = localCrossScale(xr, maxRadius(xRuns));
                radiusZ = Math.min(radiusZ, Math.max(0.72f, radiusZ * localScale));
            }
            if (orthogonalComponents && xRuns.length == 1 && zRuns.length > 1) {
                float localScale = localCrossScale(zr, maxRadius(zRuns));
                radiusX = Math.min(radiusX, Math.max(0.72f, radiusX * localScale));
            }

            float nx = Math.abs(x - xr.center) / radiusX;
            float nz = Math.abs(z - zr.center) / radiusZ;
            float implicit = 1.0f
                    - (float) Math.pow(nx, exponent)
                    - (float) Math.pow(nz, exponent);
            return implicit * Math.min(radiusX, radiusZ) * 0.78f;
        }

        private float exponent() {
            if (category == SubjectCategory.ARCHITECTURE_OBJECT) {
                return 8.0f;
            }
            if (category == SubjectCategory.COMPOSITE_VEHICLE && progress >= 0.48f) {
                return 4.8f;
            }
            if (category == SubjectCategory.ANIMAL) {
                return progress >= 0.52f ? 2.15f : 3.25f;
            }
            if (category == SubjectCategory.PLANT) {
                return 2.05f;
            }
            if (category == SubjectCategory.CHARACTER) {
                return progress >= 0.50f ? 2.20f : 3.15f;
            }
            return 3.0f;
        }

        private float localCrossScale(Run local, float maximumRadius) {
            float ratio = local.radius / Math.max(0.70f, maximumRadius);
            if (category == SubjectCategory.COMPOSITE_VEHICLE && progress >= 0.48f) {
                return 0.78f + 0.18f * (float) Math.sqrt(ratio);
            }
            if (category == SubjectCategory.ANIMAL && progress >= 0.50f) {
                return 0.34f + 0.34f * (float) Math.sqrt(ratio);
            }
            if (category == SubjectCategory.CHARACTER && progress >= 0.50f) {
                return 0.38f + 0.34f * (float) Math.sqrt(ratio);
            }
            if (category == SubjectCategory.PLANT) {
                return 0.38f + 0.36f * (float) Math.sqrt(ratio);
            }
            return 0.48f + 0.34f * (float) Math.sqrt(ratio);
        }
    }

    private static final class RunSet {
        final Run[] runs;
        final int[] index;

        RunSet(Run[] runs, int[] index) {
            this.runs = runs;
            this.index = index;
        }

        static RunSet extract(boolean[] mask, int width, int y) {
            Run[] temporary = new Run[Math.max(1, width / 2 + 1)];
            int[] index = new int[width];
            Arrays.fill(index, -1);
            int count = 0;
            int row = y * width;
            int cursor = 0;
            while (cursor < width) {
                while (cursor < width && !mask[row + cursor]) {
                    cursor++;
                }
                if (cursor >= width) {
                    break;
                }
                int start = cursor;
                while (cursor + 1 < width && mask[row + cursor + 1]) {
                    cursor++;
                }
                int end = cursor;
                if (count == temporary.length) {
                    temporary = Arrays.copyOf(temporary, temporary.length * 2);
                }
                Run run = new Run(start, end);
                temporary[count] = run;
                for (int value = start; value <= end; value++) {
                    index[value] = count;
                }
                count++;
                cursor++;
            }
            return new RunSet(Arrays.copyOf(temporary, count), index);
        }
    }

    private static final class Run {
        final int start;
        final int end;
        final float center;
        final float radius;

        Run(int start, int end) {
            this.start = start;
            this.end = end;
            this.center = (start + end) * 0.5f;
            this.radius = Math.max(0.70f, (end - start + 1) * 0.5f);
        }
    }

    private static float maxRadius(Run[] runs) {
        float maximum = 0.70f;
        for (Run run : runs) {
            maximum = Math.max(maximum, run.radius);
        }
        return maximum;
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
        for (int i = 0; i < mask.length; i++) {
            signed[i] = mask[i]
                    ? (float) Math.sqrt(toBackground[i]) - 0.5f
                    : 0.5f - (float) Math.sqrt(toForeground[i]);
        }
        return signed;
    }

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
        for (int i = 0; i < first.length; i++) {
            if (first[i] || second[i]) {
                union++;
                if (first[i] && second[i]) {
                    intersection++;
                }
            }
        }
        return union == 0 ? 0.0 : intersection / (double) union;
    }

    private static float minimum(float a, float b, float c, float d) {
        return Math.min(Math.min(a, b), Math.min(c, d));
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
                    ? frontSize : sideSize;
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

        public float[] getDensity() { return density; }
        public boolean[] getOccupancy() { return occupancy; }
        public int getOccupiedVoxels() { return occupiedVoxels; }
        public int getFractionalSamples() { return fractionalSamples; }
        public int getRoundedVoxels() { return roundedVoxels; }
        public int getAdaptivelyRecoveredVoxels() { return adaptivelyRecoveredVoxels; }
        public double getSilhouetteScore() { return silhouetteScore; }
        public boolean isComplexShapeMode() { return complexShapeMode; }
        public SubjectCategory getCategory() { return category; }
    }
}
