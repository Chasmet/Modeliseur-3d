package com.chasmet.modeliseur3d.model;

import java.util.Arrays;

/**
 * Camera-aware multi-expert DA3 surface modeler.
 *
 * <p>V7.3 keeps the four verified silhouettes as a hard outer shell, but no
 * longer sculpts every row with one global category coefficient. A lightweight
 * shape profile identifies vertical zones and their relative widths. The
 * active expert then changes inset, cross-axis mixing, core protection and
 * collapse limits for heads, torsos, legs, quadruped bodies, vehicle bases,
 * rigid volumes, trunks and canopies.</p>
 */
public final class MultiViewDepthFusion {
    private static final int VIEW_COUNT = 4;
    private static final float BASE_ISO = 0.50f;
    private static final float SURFACE_TRANSITION = 0.82f;

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
        return refine(
                baseDensity,
                masks,
                rawDepth,
                rawConfidence,
                width,
                height,
                depth,
                SubjectCategory.CHARACTER
        );
    }

    public static Result refine(
            float[] baseDensity,
            boolean[][] masks,
            float[][] rawDepth,
            float[][] rawConfidence,
            int width,
            int height,
            int depth,
            SubjectCategory category
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
        SubjectCategory resolved = category == null || category == SubjectCategory.AUTO
                ? SubjectCategory.CHARACTER
                : category;
        FusionPolicy policy = FusionPolicy.forCategory(resolved);
        ShapeProfile shape = ShapeProfile.from(masks, width, height, depth);

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
                    "profondeur DA3 plate ou insuffisante " + validViews + "/4"
            );
        }

        SurfacePair[] zSurfaces = new SurfacePair[width * height];
        SurfacePair[] xSurfaces = new SurfacePair[depth * height];
        double totalShift = 0.0;
        int shiftedSurfaces = 0;

        for (int y = 0; y < height; y++) {
            ZonePolicy zone = policy.zone(
                    shape.progress(y),
                    shape.frontRatio(y),
                    shape.sideRatio(y)
            );
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
                        backIndex,
                        zone.maximumInsetFraction,
                        zone.minimumGapFraction
                );
                zSurfaces[frontIndex] = pair;
                totalShift += pair.totalInset;
                shiftedSurfaces += pair.shiftedCount;
            }
        }

        for (int y = 0; y < height; y++) {
            ZonePolicy zone = policy.zone(
                    shape.progress(y),
                    shape.frontRatio(y),
                    shape.sideRatio(y)
            );
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
                SurfacePair pair = createPair(
                        minimum,
                        maximum,
                        views[StylizedFourViewProjector.LEFT],
                        leftIndex,
                        views[StylizedFourViewProjector.RIGHT],
                        rightIndex,
                        zone.maximumInsetFraction * zone.crossAxisScale,
                        zone.minimumGapFraction
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
            ZonePolicy zone = policy.zone(
                    shape.progress(y),
                    shape.frontRatio(y),
                    shape.sideRatio(y)
            );
            float rowCenterX = shape.frontCenter(y);
            float rowHalfWidth = Math.max(1.0f, shape.frontHalfWidth(y));
            float rowCenterZ = shape.sideCenter(y);
            float rowHalfDepth = Math.max(1.0f, shape.sideHalfWidth(y));

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
                        float minimum = Math.min(zSupport.value, xSupport.value);
                        float maximum = Math.max(zSupport.value, xSupport.value);
                        support = minimum * (1.0f - zone.ambiguityProtection)
                                + maximum * zone.ambiguityProtection;
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

                    float influence = zone.minimumInfluence
                            + zone.confidenceInfluence * clamp01(confidence);
                    float value = base * (1.0f - influence + influence * support);

                    float nx = Math.abs(x - rowCenterX) / rowHalfWidth;
                    float nz = Math.abs(z - rowCenterZ) / rowHalfDepth;
                    float coreDistance = Math.max(nx, nz);
                    if (coreDistance <= zone.coreRadius) {
                        float coreWeight = 1.0f
                                - smoothStep(
                                        zone.coreRadius * 0.55f,
                                        zone.coreRadius,
                                        coreDistance
                                );
                        float protectedFloor = base * zone.coreFloor * coreWeight;
                        value = Math.max(value, protectedFloor);
                    }

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
                && refinedOccupied < Math.round(
                        baseOccupied * policy.minimumRetainedFraction
                );
        if (collapseGuard) {
            refinedOccupied = 0;
            changed = 0;
            float baseWeight = policy.collapseBaseWeight;
            float refinedWeight = 1.0f - baseWeight;
            for (int voxel = 0; voxel < refined.length; voxel++) {
                float guarded = baseDensity[voxel] * baseWeight
                        + refined[voxel] * refinedWeight;
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
                "fusion DA3 multi-experts par zones appliquée"
        );
    }

    static float debugInsetFraction(
            SubjectCategory category,
            float progress,
            float frontRatio,
            float sideRatio
    ) {
        SubjectCategory resolved = category == null || category == SubjectCategory.AUTO
                ? SubjectCategory.CHARACTER
                : category;
        return FusionPolicy.forCategory(resolved)
                .zone(progress, frontRatio, sideRatio)
                .maximumInsetFraction;
    }

    private static SurfacePair createPair(
            int minimum,
            int maximum,
            PreparedDepth lowView,
            int lowIndex,
            PreparedDepth highView,
            int highIndex,
            float maximumInsetFraction,
            float minimumGapFraction
    ) {
        float span = Math.max(1.0f, maximum - minimum);
        float maximumInset = Math.max(0.38f, span * maximumInsetFraction);
        float lowInset = inset(lowView, lowIndex, maximumInset);
        float highInset = inset(highView, highIndex, maximumInset);
        float low = minimum + lowInset;
        float high = maximum - highInset;
        float minimumGap = Math.min(
                Math.max(0.70f, span * minimumGapFraction),
                Math.max(0.70f, span)
        );
        if (high - low < minimumGap) {
            float center = (low + high) * 0.5f;
            low = center - minimumGap * 0.5f;
            high = center + minimumGap * 0.5f;
        }
        low = clamp(low, minimum, maximum);
        high = clamp(high, minimum, maximum);
        if (high < low) {
            float center = (minimum + maximum) * 0.5f;
            low = center;
            high = center;
        }

        float lowConfidence = confidence(lowView, lowIndex);
        float highConfidence = confidence(highView, highIndex);
        boolean lowValid = lowView.valid && lowView.isUsable(lowIndex);
        boolean highValid = highView.valid && highView.isUsable(highIndex);
        if (!lowValid) {
            low = minimum;
            lowConfidence = 0.0f;
            lowInset = 0.0f;
        }
        if (!highValid) {
            high = maximum;
            highConfidence = 0.0f;
            highInset = 0.0f;
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
        float confidence = 0.60f + 0.40f * view.confidence[index];
        float detail = 0.08f + 0.92f * view.normalized[index];
        return maximumInset * detail * confidence;
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
            float low = percentile(values, 0.08f);
            float high = percentile(values, 0.92f);
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
            float confidenceLow = percentile(confidenceValues, 0.08f);
            float confidenceHigh = percentile(confidenceValues, 0.92f);
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

            edgeAwareSmooth(normalized, usable, width, height, 2, 0.20f);
            edgeAwareSmooth(confidence, usable, width, height, 1, 0.34f);
            return new PreparedDepth(normalized, confidence, usable, true);
        }

        private static void edgeAwareSmooth(
                float[] values,
                boolean[] usable,
                int width,
                int height,
                int passes,
                float edgeThreshold
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
                        float centerValue = values[center];
                        float total = centerValue * 3.0f;
                        float weight = 3.0f;
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
                                if (!usable[sample]) {
                                    continue;
                                }
                                float delta = Math.abs(values[sample] - centerValue);
                                if (delta > edgeThreshold) {
                                    continue;
                                }
                                float neighborWeight = 1.0f
                                        - 0.55f * delta / Math.max(0.001f, edgeThreshold);
                                total += values[sample] * neighborWeight;
                                weight += neighborWeight;
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

    private static final class ShapeProfile {
        final int top;
        final int bottom;
        final int[] frontMin;
        final int[] frontMax;
        final int[] sideMin;
        final int[] sideMax;
        final float[] frontRatio;
        final float[] sideRatio;

        ShapeProfile(
                int top,
                int bottom,
                int[] frontMin,
                int[] frontMax,
                int[] sideMin,
                int[] sideMax,
                float[] frontRatio,
                float[] sideRatio
        ) {
            this.top = top;
            this.bottom = bottom;
            this.frontMin = frontMin;
            this.frontMax = frontMax;
            this.sideMin = sideMin;
            this.sideMax = sideMax;
            this.frontRatio = frontRatio;
            this.sideRatio = sideRatio;
        }

        static ShapeProfile from(
                boolean[][] masks,
                int width,
                int height,
                int depth
        ) {
            boolean[] front = mirroredUnion(
                    masks[StylizedFourViewProjector.FRONT],
                    masks[StylizedFourViewProjector.BACK],
                    width,
                    height
            );
            boolean[] side = mirroredUnion(
                    masks[StylizedFourViewProjector.RIGHT],
                    masks[StylizedFourViewProjector.LEFT],
                    depth,
                    height
            );
            int[] frontMin = new int[height];
            int[] frontMax = new int[height];
            int[] sideMin = new int[height];
            int[] sideMax = new int[height];
            Arrays.fill(frontMin, -1);
            Arrays.fill(frontMax, -1);
            Arrays.fill(sideMin, -1);
            Arrays.fill(sideMax, -1);
            int top = height;
            int bottom = -1;
            int maxFrontSpan = 1;
            int maxSideSpan = 1;

            for (int y = 0; y < height; y++) {
                int[] frontSpan = span(front, width, y);
                int[] sideSpan = span(side, depth, y);
                frontMin[y] = frontSpan[0];
                frontMax[y] = frontSpan[1];
                sideMin[y] = sideSpan[0];
                sideMax[y] = sideSpan[1];
                if (frontSpan[0] >= 0 || sideSpan[0] >= 0) {
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y);
                }
                if (frontSpan[0] >= 0) {
                    maxFrontSpan = Math.max(
                            maxFrontSpan,
                            frontSpan[1] - frontSpan[0] + 1
                    );
                }
                if (sideSpan[0] >= 0) {
                    maxSideSpan = Math.max(
                            maxSideSpan,
                            sideSpan[1] - sideSpan[0] + 1
                    );
                }
            }
            if (bottom < top) {
                top = 0;
                bottom = Math.max(1, height - 1);
            }

            float[] frontRatio = new float[height];
            float[] sideRatio = new float[height];
            for (int y = 0; y < height; y++) {
                if (frontMin[y] >= 0) {
                    frontRatio[y] = (frontMax[y] - frontMin[y] + 1)
                            / (float) maxFrontSpan;
                }
                if (sideMin[y] >= 0) {
                    sideRatio[y] = (sideMax[y] - sideMin[y] + 1)
                            / (float) maxSideSpan;
                }
            }
            softenRatios(frontRatio);
            softenRatios(sideRatio);
            return new ShapeProfile(
                    top,
                    bottom,
                    frontMin,
                    frontMax,
                    sideMin,
                    sideMax,
                    frontRatio,
                    sideRatio
            );
        }

        float progress(int y) {
            if (bottom <= top) {
                return 0.5f;
            }
            return clamp01((y - top) / (float) (bottom - top));
        }

        float frontRatio(int y) {
            return frontRatio[Math.max(0, Math.min(frontRatio.length - 1, y))];
        }

        float sideRatio(int y) {
            return sideRatio[Math.max(0, Math.min(sideRatio.length - 1, y))];
        }

        float frontCenter(int y) {
            int min = frontMin[y];
            int max = frontMax[y];
            return min >= 0 ? (min + max) * 0.5f : 0.0f;
        }

        float frontHalfWidth(int y) {
            int min = frontMin[y];
            int max = frontMax[y];
            return min >= 0 ? (max - min + 1) * 0.5f : 1.0f;
        }

        float sideCenter(int y) {
            int min = sideMin[y];
            int max = sideMax[y];
            return min >= 0 ? (min + max) * 0.5f : 0.0f;
        }

        float sideHalfWidth(int y) {
            int min = sideMin[y];
            int max = sideMax[y];
            return min >= 0 ? (max - min + 1) * 0.5f : 1.0f;
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

        private static int[] span(boolean[] mask, int width, int y) {
            int min = width;
            int max = -1;
            int row = y * width;
            for (int x = 0; x < width; x++) {
                if (mask[row + x]) {
                    min = Math.min(min, x);
                    max = Math.max(max, x);
                }
            }
            return max < min ? new int[]{-1, -1} : new int[]{min, max};
        }

        private static void softenRatios(float[] ratios) {
            if (ratios.length < 3) {
                return;
            }
            float[] source = ratios.clone();
            for (int y = 1; y < ratios.length - 1; y++) {
                if (source[y] <= 0.0f) {
                    continue;
                }
                float total = source[y] * 2.0f;
                float weight = 2.0f;
                if (source[y - 1] > 0.0f) {
                    total += source[y - 1];
                    weight += 1.0f;
                }
                if (source[y + 1] > 0.0f) {
                    total += source[y + 1];
                    weight += 1.0f;
                }
                ratios[y] = total / weight;
            }
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

    private static final class FusionPolicy {
        final SubjectCategory category;
        final float minimumRetainedFraction;
        final float collapseBaseWeight;

        FusionPolicy(
                SubjectCategory category,
                float minimumRetainedFraction,
                float collapseBaseWeight
        ) {
            this.category = category;
            this.minimumRetainedFraction = minimumRetainedFraction;
            this.collapseBaseWeight = collapseBaseWeight;
        }

        ZonePolicy zone(
                float progress,
                float frontRatio,
                float sideRatio
        ) {
            float p = clamp01(progress);
            float broadness = clamp01((frontRatio + sideRatio) * 0.5f);

            if (category == SubjectCategory.COMPOSITE_VEHICLE) {
                if (p < 0.46f) {
                    float narrowBoost = 0.08f * (1.0f - broadness);
                    return new ZonePolicy(
                            0.27f + narrowBoost,
                            0.10f,
                            0.62f,
                            0.22f,
                            0.12f,
                            1.08f,
                            0.26f,
                            0.54f
                    );
                }
                return new ZonePolicy(
                        0.105f,
                        0.30f,
                        0.43f,
                        0.15f,
                        0.48f,
                        0.82f,
                        0.44f,
                        0.72f
                );
            }

            if (category == SubjectCategory.ANIMAL) {
                if (p >= 0.62f && broadness < 0.72f) {
                    return new ZonePolicy(
                            0.35f,
                            0.09f,
                            0.70f,
                            0.22f,
                            0.05f,
                            1.02f,
                            0.20f,
                            0.52f
                    );
                }
                if (p >= 0.18f && p < 0.64f && broadness >= 0.58f) {
                    return new ZonePolicy(
                            0.135f,
                            0.31f,
                            0.54f,
                            0.18f,
                            0.16f,
                            0.88f,
                            0.43f,
                            0.72f
                    );
                }
                return new ZonePolicy(
                        0.235f,
                        0.16f,
                        0.64f,
                        0.20f,
                        0.10f,
                        0.96f,
                        0.30f,
                        0.62f
                );
            }

            if (category == SubjectCategory.ARCHITECTURE_OBJECT) {
                return new ZonePolicy(
                        0.085f,
                        0.48f,
                        0.38f,
                        0.12f,
                        0.42f,
                        0.78f,
                        0.60f,
                        0.80f
                );
            }

            if (category == SubjectCategory.PLANT) {
                if (p >= 0.58f && broadness < 0.58f) {
                    return new ZonePolicy(
                            0.31f,
                            0.11f,
                            0.64f,
                            0.20f,
                            0.10f,
                            1.05f,
                            0.22f,
                            0.54f
                    );
                }
                if (p < 0.58f && broadness > 0.54f) {
                    return new ZonePolicy(
                            0.19f,
                            0.17f,
                            0.58f,
                            0.20f,
                            0.14f,
                            0.96f,
                            0.27f,
                            0.60f
                    );
                }
                return new ZonePolicy(
                        0.25f,
                        0.12f,
                        0.60f,
                        0.20f,
                        0.12f,
                        1.00f,
                        0.24f,
                        0.56f
                );
            }

            if (p < 0.23f) {
                return new ZonePolicy(
                        0.22f,
                        0.18f,
                        0.63f,
                        0.22f,
                        0.04f,
                        0.96f,
                        0.32f,
                        0.64f
                );
            }
            if (p < 0.62f && broadness >= 0.48f) {
                return new ZonePolicy(
                        0.15f,
                        0.29f,
                        0.56f,
                        0.20f,
                        0.08f,
                        0.90f,
                        0.42f,
                        0.70f
                );
            }
            return new ZonePolicy(
                    0.32f,
                    0.10f,
                    0.72f,
                    0.22f,
                    0.02f,
                    1.05f,
                    0.20f,
                    0.52f
            );
        }

        static FusionPolicy forCategory(SubjectCategory category) {
            if (category == SubjectCategory.COMPOSITE_VEHICLE) {
                return new FusionPolicy(category, 0.76f, 0.64f);
            }
            if (category == SubjectCategory.ARCHITECTURE_OBJECT) {
                return new FusionPolicy(category, 0.84f, 0.68f);
            }
            if (category == SubjectCategory.ANIMAL) {
                return new FusionPolicy(category, 0.69f, 0.58f);
            }
            if (category == SubjectCategory.PLANT) {
                return new FusionPolicy(category, 0.68f, 0.56f);
            }
            return new FusionPolicy(SubjectCategory.CHARACTER, 0.61f, 0.58f);
        }
    }

    private static final class ZonePolicy {
        final float maximumInsetFraction;
        final float minimumGapFraction;
        final float minimumInfluence;
        final float confidenceInfluence;
        final float ambiguityProtection;
        final float crossAxisScale;
        final float coreRadius;
        final float coreFloor;

        ZonePolicy(
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
