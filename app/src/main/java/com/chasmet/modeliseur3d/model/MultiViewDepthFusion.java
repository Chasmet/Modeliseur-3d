package com.chasmet.modeliseur3d.model;

import java.util.Arrays;

/**
 * Camera-aware surface modeler for the four guided DA3 views.
 *
 * <p>The capture UI supplies exact canonical poses (front, right, back, left),
 * so each neural depth map is projected along its known camera axis. Opposite
 * maps define paired low/high surfaces and the two perpendicular pairs form a
 * continuous signed support field. The model can strongly move the surface,
 * while verified silhouettes and a collapse guard remain hard safety limits.</p>
 */
public final class MultiViewDepthFusion {
    private static final int VIEW_COUNT = 4;
    private static final float BASE_ISO = 0.50f;
    private static final float MAXIMUM_INSET_FRACTION = 0.29f;
    private static final float SURFACE_TRANSITION = 0.90f;
    private static final float MINIMUM_RETAINED_FRACTION = 0.60f;
    private static final float MINIMUM_NEURAL_INFLUENCE = 0.68f;
    private static final float CONFIDENCE_INFLUENCE = 0.22f;

    private MultiViewDepthFusion() {
    }

    public static Result refine(
            float[] baseDensity,
            boolean[][] masks,
            float[][] rawDepth,
            float[][] rawConfidence,
            int width,
            int height,
            int depth
    ) {
        validate(
                baseDensity,
                masks,
                rawDepth,
                rawConfidence,
                width,
                height,
                depth
        );
        int[] viewWidths = {width, depth, width, depth};
        PreparedDepth[] views = new PreparedDepth[VIEW_COUNT];
        int validViews = 0;
        for (int view = 0; view < VIEW_COUNT; view++) {
            views[view] = PreparedDepth.create(
                    rawDepth[view],
                    rawConfidence[view],
                    masks[view],
                    viewWidths[view],
                    height
            );
            if (views[view].valid) {
                validViews++;
            }
        }
        if (validViews < 2) {
            return Result.unchanged(
                    baseDensity,
                    validViews,
                    "profondeur DA3 plate ou insuffisante "
                            + validViews + "/4"
            );
        }

        SurfacePair[] zSurfaces = new SurfacePair[width * height];
        SurfacePair[] xSurfaces = new SurfacePair[depth * height];
        double totalShift = 0.0;
        int shiftedSurfaces = 0;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int minimum = depth;
                int maximum = -1;
                for (int z = 0; z < depth; z++) {
                    if (baseDensity[index(x, y, z, width, depth)] >= BASE_ISO) {
                        minimum = Math.min(minimum, z);
                        maximum = Math.max(maximum, z);
                    }
                }
                if (maximum < minimum) {
                    continue;
                }
                int frontIndex = y * width + x;
                int backIndex = y * width + (width - 1 - x);
                SurfacePair pair = createPair(
                        minimum,
                        maximum,
                        views[StylizedFourViewProjector.FRONT],
                        frontIndex,
                        views[StylizedFourViewProjector.BACK],
                        backIndex
                );
                zSurfaces[frontIndex] = pair;
                totalShift += pair.totalInset;
                shiftedSurfaces += pair.shiftedCount;
            }
        }

        for (int y = 0; y < height; y++) {
            for (int z = 0; z < depth; z++) {
                int minimum = width;
                int maximum = -1;
                for (int x = 0; x < width; x++) {
                    if (baseDensity[index(x, y, z, width, depth)] >= BASE_ISO) {
                        minimum = Math.min(minimum, x);
                        maximum = Math.max(maximum, x);
                    }
                }
                if (maximum < minimum) {
                    continue;
                }
                int rightIndex = y * depth + z;
                int leftIndex = y * depth + (depth - 1 - z);
                // LEFT constrains the low-X surface; RIGHT constrains high-X.
                SurfacePair pair = createPair(
                        minimum,
                        maximum,
                        views[StylizedFourViewProjector.LEFT],
                        leftIndex,
                        views[StylizedFourViewProjector.RIGHT],
                        rightIndex
                );
                xSurfaces[rightIndex] = pair;
                totalShift += pair.totalInset;
                shiftedSurfaces += pair.shiftedCount;
            }
        }

        float[] refined = new float[baseDensity.length];
        int baseOccupied = 0;
        int refinedOccupied = 0;
        int changed = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                SurfacePair zPair = zSurfaces[y * width + x];
                for (int z = 0; z < depth; z++) {
                    int voxel = index(x, y, z, width, depth);
                    float base = baseDensity[voxel];
                    if (base >= BASE_ISO) {
                        baseOccupied++;
                    }
                    if (base <= 0.0f || isBoundary(x, y, z, width, height, depth)) {
                        refined[voxel] = 0.0f;
                        continue;
                    }
                    SurfacePair xPair = xSurfaces[y * depth + z];
                    AxisSupport zSupport = support(zPair, z);
                    AxisSupport xSupport = support(xPair, x);
                    float support;
                    float confidence;
                    if (zSupport.valid && xSupport.valid) {
                        support = Math.min(zSupport.value, xSupport.value);
                        confidence = (zSupport.confidence + xSupport.confidence) * 0.5f;
                    } else if (zSupport.valid) {
                        support = zSupport.value;
                        confidence = zSupport.confidence;
                    } else if (xSupport.valid) {
                        support = xSupport.value;
                        confidence = xSupport.confidence;
                    } else {
                        refined[voxel] = base;
                        if (base >= BASE_ISO) {
                            refinedOccupied++;
                        }
                        continue;
                    }
                    float influence = MINIMUM_NEURAL_INFLUENCE
                            + CONFIDENCE_INFLUENCE * clamp01(confidence);
                    float value = base * (1.0f - influence + influence * support);
                    refined[voxel] = clamp01(value);
                    if (Math.abs(refined[voxel] - base) > 0.08f) {
                        changed++;
                    }
                    if (refined[voxel] >= BASE_ISO) {
                        refinedOccupied++;
                    }
                }
            }
        }

        boolean collapseGuard = baseOccupied > 0
                && refinedOccupied < Math.round(baseOccupied * MINIMUM_RETAINED_FRACTION);
        if (collapseGuard) {
            refinedOccupied = 0;
            changed = 0;
            for (int voxel = 0; voxel < refined.length; voxel++) {
                float guarded = baseDensity[voxel] * 0.58f + refined[voxel] * 0.42f;
                refined[voxel] = clamp01(guarded);
                if (Math.abs(refined[voxel] - baseDensity[voxel]) > 0.08f) {
                    changed++;
                }
                if (refined[voxel] >= BASE_ISO) {
                    refinedOccupied++;
                }
            }
        }

        if (changed < Math.max(12, baseDensity.length / 240_000)) {
            return Result.unchanged(
                    baseDensity,
                    validViews,
                    "relief DA3 trop faible (" + changed + " voxels)"
            );
        }
        return new Result(
                refined,
                true,
                validViews,
                changed,
                refinedOccupied,
                shiftedSurfaces == 0 ? 0.0 : totalShift / shiftedSurfaces,
                collapseGuard,
                "fusion de surfaces DA3 appliquée"
        );
    }

    private static SurfacePair createPair(
            int minimum,
            int maximum,
            PreparedDepth lowView,
            int lowIndex,
            PreparedDepth highView,
            int highIndex
    ) {
        float span = Math.max(1.0f, maximum - minimum);
        float maximumInset = Math.max(0.45f, span * MAXIMUM_INSET_FRACTION);
        float lowInset = inset(lowView, lowIndex, maximumInset);
        float highInset = inset(highView, highIndex, maximumInset);
        float low = minimum + lowInset;
        float high = maximum - highInset;
        float minimumGap = Math.min(2.0f, Math.max(0.70f, span * 0.16f));
        if (high - low < minimumGap) {
            float center = (low + high) * 0.5f;
            low = center - minimumGap * 0.5f;
            high = center + minimumGap * 0.5f;
        }
        low = clamp(low, minimum, maximum);
        high = clamp(high, minimum, maximum);
        float lowConfidence = confidence(lowView, lowIndex);
        float highConfidence = confidence(highView, highIndex);
        boolean lowValid = lowView.valid && lowView.isUsable(lowIndex);
        boolean highValid = highView.valid && highView.isUsable(highIndex);
        if (!lowValid) {
            low = minimum;
            lowConfidence = 0.0f;
        }
        if (!highValid) {
            high = maximum;
            highConfidence = 0.0f;
        }
        return new SurfacePair(
                low,
                high,
                lowValid,
                highValid,
                lowConfidence,
                highConfidence,
                lowInset + highInset,
                (lowInset > 0.05f ? 1 : 0) + (highInset > 0.05f ? 1 : 0)
        );
    }

    private static float inset(
            PreparedDepth view,
            int index,
            float maximumInset
    ) {
        if (!view.valid || !view.isUsable(index)) {
            return 0.0f;
        }
        float confidence = 0.64f + 0.36f * view.confidence[index];
        return maximumInset * view.normalized[index] * confidence;
    }

    private static float confidence(PreparedDepth view, int index) {
        return view.valid && view.isUsable(index)
                ? view.confidence[index]
                : 0.0f;
    }

    private static AxisSupport support(SurfacePair pair, float coordinate) {
        if (pair == null || (!pair.lowValid && !pair.highValid)) {
            return AxisSupport.invalid();
        }
        float low = pair.lowValid
                ? smoothStep(
                        pair.low - SURFACE_TRANSITION,
                        pair.low + SURFACE_TRANSITION,
                        coordinate
                )
                : 1.0f;
        float high = pair.highValid
                ? 1.0f - smoothStep(
                        pair.high - SURFACE_TRANSITION,
                        pair.high + SURFACE_TRANSITION,
                        coordinate
                )
                : 1.0f;
        float confidence;
        if (pair.lowValid && pair.highValid) {
            confidence = (pair.lowConfidence + pair.highConfidence) * 0.5f;
        } else {
            confidence = pair.lowValid ? pair.lowConfidence : pair.highConfidence;
        }
        return new AxisSupport(Math.min(low, high), confidence, true);
    }

    private static boolean isBoundary(
            int x,
            int y,
            int z,
            int width,
            int height,
            int depth
    ) {
        return x == 0 || y == 0 || z == 0
                || x == width - 1 || y == height - 1 || z == depth - 1;
    }

    private static int index(int x, int y, int z, int width, int depth) {
        return (y * width + x) * depth + z;
    }

    private static float smoothStep(float edge0, float edge1, float value) {
        if (edge1 <= edge0) {
            return value >= edge1 ? 1.0f : 0.0f;
        }
        float amount = clamp01((value - edge0) / (edge1 - edge0));
        return amount * amount * (3.0f - 2.0f * amount);
    }

    private static float clamp01(float value) {
        return clamp(value, 0.0f, 1.0f);
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static void validate(
            float[] baseDensity,
            boolean[][] masks,
            float[][] rawDepth,
            float[][] rawConfidence,
            int width,
            int height,
            int depth
    ) {
        int volume = width * height * depth;
        if (width < 4 || height < 4 || depth < 4
                || baseDensity == null || baseDensity.length != volume
                || masks == null || masks.length != VIEW_COUNT
                || rawDepth == null || rawDepth.length != VIEW_COUNT
                || rawConfidence == null || rawConfidence.length != VIEW_COUNT) {
            throw new IllegalArgumentException("Fusion DA3 multivue invalide");
        }
        int[] widths = {width, depth, width, depth};
        for (int view = 0; view < VIEW_COUNT; view++) {
            int expected = widths[view] * height;
            if (masks[view] == null || masks[view].length != expected
                    || rawDepth[view] == null || rawDepth[view].length != expected
                    || rawConfidence[view] == null
                    || rawConfidence[view].length != expected) {
                throw new IllegalArgumentException("Carte DA3 " + view + " invalide");
            }
        }
    }

    private static final class PreparedDepth {
        final float[] normalized;
        final float[] confidence;
        final boolean[] usable;
        final boolean valid;

        PreparedDepth(
                float[] normalized,
                float[] confidence,
                boolean[] usable,
                boolean valid
        ) {
            this.normalized = normalized;
            this.confidence = confidence;
            this.usable = usable;
            this.valid = valid;
        }

        boolean isUsable(int index) {
            return index >= 0 && index < usable.length && usable[index];
        }

        static PreparedDepth create(
                float[] raw,
                float[] rawConfidence,
                boolean[] mask,
                int width,
                int height
        ) {
            int validCount = 0;
            for (int index = 0; index < raw.length; index++) {
                if (mask[index] && Float.isFinite(raw[index])) {
                    validCount++;
                }
            }
            int minimum = Math.max(32, raw.length / 180);
            float[] normalized = new float[raw.length];
            float[] confidence = new float[raw.length];
            boolean[] usable = new boolean[raw.length];
            if (validCount < minimum) {
                return new PreparedDepth(normalized, confidence, usable, false);
            }
            float[] values = new float[validCount];
            int cursor = 0;
            for (int index = 0; index < raw.length; index++) {
                if (mask[index] && Float.isFinite(raw[index])) {
                    values[cursor++] = raw[index];
                }
            }
            Arrays.sort(values);
            float low = percentile(values, 0.10f);
            float high = percentile(values, 0.90f);
            float range = high - low;
            float scale = Math.max(1.0f, Math.max(Math.abs(low), Math.abs(high)));
            if (!Float.isFinite(range) || range <= scale * 1.0e-5f) {
                return new PreparedDepth(normalized, confidence, usable, false);
            }

            float[] confidenceValues = new float[validCount];
            cursor = 0;
            for (int index = 0; index < raw.length; index++) {
                if (mask[index] && Float.isFinite(raw[index])) {
                    float value = rawConfidence[index];
                    confidenceValues[cursor++] = Float.isFinite(value) ? value : 0.0f;
                }
            }
            Arrays.sort(confidenceValues);
            float confidenceLow = percentile(confidenceValues, 0.10f);
            float confidenceHigh = percentile(confidenceValues, 0.90f);
            float confidenceRange = confidenceHigh - confidenceLow;

            for (int index = 0; index < raw.length; index++) {
                if (!mask[index] || !Float.isFinite(raw[index])) {
                    continue;
                }
                normalized[index] = clamp01((raw[index] - low) / range);
                float rawValue = rawConfidence[index];
                confidence[index] = Float.isFinite(rawValue)
                        && confidenceRange > 1.0e-6f
                        ? clamp01((rawValue - confidenceLow) / confidenceRange)
                        : 0.75f;
                usable[index] = true;
            }
            smooth(normalized, usable, width, height, 2);
            smooth(confidence, usable, width, height, 1);
            return new PreparedDepth(normalized, confidence, usable, true);
        }

        private static void smooth(
                float[] values,
                boolean[] usable,
                int width,
                int height,
                int passes
        ) {
            float[] temporary = new float[values.length];
            for (int pass = 0; pass < passes; pass++) {
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int center = y * width + x;
                        if (!usable[center]) {
                            temporary[center] = values[center];
                            continue;
                        }
                        float total = values[center] * 2.0f;
                        float weight = 2.0f;
                        for (int dy = -1; dy <= 1; dy++) {
                            int sampleY = y + dy;
                            if (sampleY < 0 || sampleY >= height) {
                                continue;
                            }
                            for (int dx = -1; dx <= 1; dx++) {
                                if (dx == 0 && dy == 0) {
                                    continue;
                                }
                                int sampleX = x + dx;
                                if (sampleX < 0 || sampleX >= width) {
                                    continue;
                                }
                                int sample = sampleY * width + sampleX;
                                if (usable[sample]) {
                                    total += values[sample];
                                    weight += 1.0f;
                                }
                            }
                        }
                        temporary[center] = total / weight;
                    }
                }
                System.arraycopy(temporary, 0, values, 0, values.length);
            }
        }

        private static float percentile(float[] sorted, float fraction) {
            if (sorted.length == 1) {
                return sorted[0];
            }
            float position = clamp01(fraction) * (sorted.length - 1);
            int lower = (int) Math.floor(position);
            int upper = Math.min(sorted.length - 1, lower + 1);
            float amount = position - lower;
            return sorted[lower] * (1.0f - amount) + sorted[upper] * amount;
        }
    }

    private static final class SurfacePair {
        final float low;
        final float high;
        final boolean lowValid;
        final boolean highValid;
        final float lowConfidence;
        final float highConfidence;
        final float totalInset;
        final int shiftedCount;

        SurfacePair(
                float low,
                float high,
                boolean lowValid,
                boolean highValid,
                float lowConfidence,
                float highConfidence,
                float totalInset,
                int shiftedCount
        ) {
            this.low = low;
            this.high = high;
            this.lowValid = lowValid;
            this.highValid = highValid;
            this.lowConfidence = lowConfidence;
            this.highConfidence = highConfidence;
            this.totalInset = totalInset;
            this.shiftedCount = shiftedCount;
        }
    }

    private static final class AxisSupport {
        final float value;
        final float confidence;
        final boolean valid;

        AxisSupport(float value, float confidence, boolean valid) {
            this.value = value;
            this.confidence = confidence;
            this.valid = valid;
        }

        static AxisSupport invalid() {
            return new AxisSupport(1.0f, 0.0f, false);
        }
    }

    public static final class Result {
        private final float[] density;
        private final boolean applied;
        private final int validViews;
        private final int changedVoxels;
        private final int occupiedVoxels;
        private final double meanSurfaceInset;
        private final boolean collapseGuardUsed;
        private final String reason;

        Result(
                float[] density,
                boolean applied,
                int validViews,
                int changedVoxels,
                int occupiedVoxels,
                double meanSurfaceInset,
                boolean collapseGuardUsed,
                String reason
        ) {
            this.density = density;
            this.applied = applied;
            this.validViews = validViews;
            this.changedVoxels = changedVoxels;
            this.occupiedVoxels = occupiedVoxels;
            this.meanSurfaceInset = meanSurfaceInset;
            this.collapseGuardUsed = collapseGuardUsed;
            this.reason = reason;
        }

        static Result unchanged(
                float[] density,
                int validViews,
                String reason
        ) {
            int occupied = 0;
            for (float value : density) {
                if (value >= BASE_ISO) {
                    occupied++;
                }
            }
            return new Result(
                    density,
                    false,
                    validViews,
                    0,
                    occupied,
                    0.0,
                    false,
                    reason
            );
        }

        public float[] getDensity() {
            return density;
        }

        public boolean isApplied() {
            return applied;
        }

        public int getValidViews() {
            return validViews;
        }

        public int getChangedVoxels() {
            return changedVoxels;
        }

        public int getOccupiedVoxels() {
            return occupiedVoxels;
        }

        public double getMeanSurfaceInset() {
            return meanSurfaceInset;
        }

        public boolean isCollapseGuardUsed() {
            return collapseGuardUsed;
        }

        public String getReason() {
            return reason;
        }
    }
}
