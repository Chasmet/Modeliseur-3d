package com.chasmet.modeliseur3d.model;

import java.util.Arrays;

/**
 * Nettoyage anatomique conservateur du volume quatre vues.
 *
 * Cette étape ne translate, ne redimensionne et ne tourne jamais le personnage.
 * Elle travaille uniquement à l'intérieur de l'enveloppe V5.9 stable :
 *
 * - les grands volumes centraux restent intacts ;
 * - les bras, jambes, mèches et accessoires fins reçoivent une profondeur
 *   locale adaptée à leur largeur réelle dans les vues face/dos ;
 * - les petits morceaux isolés sont retirés ;
 * - les séparations visibles entre deux jambes sont protégées avant le lissage.
 *
 * Le résultat est toujours un sous-ensemble du volume source. Il ne peut donc
 * pas créer un nouveau décalage ou un volume fantôme en dehors des quatre vues.
 */
public final class HumanoidVolumeRefiner {
    private static final float NARROW_RUN_RATIO = 0.62f;
    private static final float MINIMUM_LOCAL_DEPTH_SCALE = 0.52f;
    private static final float MAXIMUM_LOCAL_DEPTH_SCALE = 0.94f;

    private HumanoidVolumeRefiner() {
    }

    public static Result refine(
            boolean[] source,
            boolean[][] masks,
            int width,
            int height,
            int depth
    ) {
        validate(source, masks, width, height, depth);
        boolean[] volume = Arrays.copyOf(source, source.length);
        boolean[] frontUnion = createFrontUnion(masks, width, height);
        boolean[] sideUnion = createSideUnion(masks, depth, height);
        int top = firstOccupiedRow(frontUnion, width, height);
        int bottom = lastOccupiedRow(frontUnion, width, height);
        if (top < 0 || bottom <= top) {
            return new Result(volume, 0, 0, 0, 0);
        }

        RowDepth[] rowDepths = buildRowDepths(sideUnion, depth, height);
        smoothRowDepths(rowDepths);

        int prunedDepthVoxels = refineLocalDepth(
                volume,
                frontUnion,
                rowDepths,
                width,
                height,
                depth,
                top,
                bottom
        );
        int protectedLegGaps = protectLowerBodyGaps(
                volume,
                frontUnion,
                width,
                height,
                depth,
                top,
                bottom
        );
        ComponentCleanup cleanup = removeTinyComponents(
                volume,
                width,
                height,
                depth
        );
        int removedSpikes = removeUnsupportedVoxels(
                volume,
                width,
                height,
                depth
        );
        return new Result(
                volume,
                prunedDepthVoxels,
                cleanup.removedComponents,
                cleanup.removedVoxels + removedSpikes,
                protectedLegGaps
        );
    }

    private static int refineLocalDepth(
            boolean[] volume,
            boolean[] frontUnion,
            RowDepth[] rowDepths,
            int width,
            int height,
            int depth,
            int top,
            int bottom
    ) {
        int pruned = 0;
        int[] runStart = new int[width];
        int[] runEnd = new int[width];
        for (int y = top; y <= bottom; y++) {
            RowDepth rowDepth = rowDepths[y];
            if (!rowDepth.valid) {
                continue;
            }
            int runCount = collectRuns(
                    frontUnion,
                    width,
                    y,
                    runStart,
                    runEnd
            );
            if (runCount == 0) {
                continue;
            }
            int maximumRunWidth = 1;
            for (int run = 0; run < runCount; run++) {
                maximumRunWidth = Math.max(
                        maximumRunWidth,
                        runEnd[run] - runStart[run] + 1
                );
            }
            float bodyProgress = (y - top) / Math.max(1.0f, bottom - top);
            for (int run = 0; run < runCount; run++) {
                int start = runStart[run];
                int end = runEnd[run];
                int runWidth = end - start + 1;
                float ratio = runWidth / Math.max(1.0f, maximumRunWidth);
                float scale = localDepthScale(
                        ratio,
                        runCount,
                        bodyProgress,
                        runWidth,
                        maximumRunWidth
                );
                if (scale >= 0.985f) {
                    continue;
                }
                float allowedRadius = Math.max(1.35f, rowDepth.radius * scale);
                for (int x = start; x <= end; x++) {
                    int base = (y * width + x) * depth;
                    for (int z = 0; z < depth; z++) {
                        int index = base + z;
                        if (volume[index]
                                && Math.abs(z - rowDepth.center) > allowedRadius) {
                            volume[index] = false;
                            pruned++;
                        }
                    }
                }
            }
        }
        return pruned;
    }

    private static float localDepthScale(
            float runRatio,
            int runCount,
            float bodyProgress,
            int runWidth,
            int maximumRunWidth
    ) {
        if (runCount == 1) {
            if (bodyProgress < 0.24f) {
                return 0.90f;
            }
            if (bodyProgress < 0.58f) {
                return 0.96f;
            }
            return 0.90f;
        }

        if (runRatio >= NARROW_RUN_RATIO) {
            return bodyProgress >= 0.52f ? 0.78f : 0.92f;
        }

        float normalized = Math.max(0.0f, Math.min(1.0f, runRatio / NARROW_RUN_RATIO));
        float scale = MINIMUM_LOCAL_DEPTH_SCALE
                + (MAXIMUM_LOCAL_DEPTH_SCALE - MINIMUM_LOCAL_DEPTH_SCALE)
                * (float) Math.sqrt(normalized);

        if (bodyProgress >= 0.55f) {
            scale = Math.min(scale, 0.72f);
        }
        if (runWidth <= Math.max(2, maximumRunWidth / 10)) {
            scale = Math.min(scale, 0.60f);
        }
        return scale;
    }

    private static int protectLowerBodyGaps(
            boolean[] volume,
            boolean[] frontUnion,
            int width,
            int height,
            int depth,
            int top,
            int bottom
    ) {
        int protectedGaps = 0;
        int[] runStart = new int[width];
        int[] runEnd = new int[width];
        int lowerStart = top + Math.round((bottom - top) * 0.50f);
        for (int y = Math.max(top, lowerStart); y <= bottom; y++) {
            int count = collectRuns(frontUnion, width, y, runStart, runEnd);
            if (count < 2) {
                continue;
            }
            for (int run = 0; run + 1 < count; run++) {
                int gapStart = runEnd[run] + 1;
                int gapEnd = runStart[run + 1] - 1;
                int gapWidth = gapEnd - gapStart + 1;
                int leftWidth = runEnd[run] - runStart[run] + 1;
                int rightWidth = runEnd[run + 1] - runStart[run + 1] + 1;
                if (gapWidth < 1 || gapWidth > 2 || leftWidth < 3 || rightWidth < 3) {
                    continue;
                }
                int leftBoundary = runEnd[run];
                int rightBoundary = runStart[run + 1];
                for (int z = 0; z < depth; z++) {
                    volume[(y * width + leftBoundary) * depth + z] = false;
                    volume[(y * width + rightBoundary) * depth + z] = false;
                }
                protectedGaps++;
            }
        }
        return protectedGaps;
    }

    private static ComponentCleanup removeTinyComponents(
            boolean[] volume,
            int width,
            int height,
            int depth
    ) {
        byte[] visited = new byte[volume.length];
        int[] queue = new int[volume.length];
        int minimumComponent = Math.max(18, volume.length / 260_000);
        int removedComponents = 0;
        int removedVoxels = 0;
        int rowStride = width * depth;

        for (int start = 0; start < volume.length; start++) {
            if (!volume[start] || visited[start] != 0) {
                continue;
            }
            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            visited[start] = 1;
            while (head < tail) {
                int current = queue[head++];
                int z = current % depth;
                int xy = current / depth;
                int x = xy % width;
                int y = xy / width;
                tail = enqueue(volume, visited, queue, tail,
                        current - 1, z > 0);
                tail = enqueue(volume, visited, queue, tail,
                        current + 1, z + 1 < depth);
                tail = enqueue(volume, visited, queue, tail,
                        current - depth, x > 0);
                tail = enqueue(volume, visited, queue, tail,
                        current + depth, x + 1 < width);
                tail = enqueue(volume, visited, queue, tail,
                        current - rowStride, y > 0);
                tail = enqueue(volume, visited, queue, tail,
                        current + rowStride, y + 1 < height);
            }
            if (tail < minimumComponent) {
                for (int index = 0; index < tail; index++) {
                    volume[queue[index]] = false;
                }
                removedComponents++;
                removedVoxels += tail;
            }
        }
        return new ComponentCleanup(removedComponents, removedVoxels);
    }

    private static int enqueue(
            boolean[] volume,
            byte[] visited,
            int[] queue,
            int tail,
            int index,
            boolean valid
    ) {
        if (valid && volume[index] && visited[index] == 0) {
            visited[index] = 1;
            queue[tail++] = index;
        }
        return tail;
    }

    private static int removeUnsupportedVoxels(
            boolean[] volume,
            int width,
            int height,
            int depth
    ) {
        boolean[] source = Arrays.copyOf(volume, volume.length);
        int rowStride = width * depth;
        int removed = 0;
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                for (int z = 1; z < depth - 1; z++) {
                    int index = (y * width + x) * depth + z;
                    if (!source[index]) {
                        continue;
                    }
                    int neighbours = 0;
                    neighbours += source[index - 1] ? 1 : 0;
                    neighbours += source[index + 1] ? 1 : 0;
                    neighbours += source[index - depth] ? 1 : 0;
                    neighbours += source[index + depth] ? 1 : 0;
                    neighbours += source[index - rowStride] ? 1 : 0;
                    neighbours += source[index + rowStride] ? 1 : 0;
                    if (neighbours == 0) {
                        volume[index] = false;
                        removed++;
                    }
                }
            }
        }
        return removed;
    }

    private static boolean[] createFrontUnion(
            boolean[][] masks,
            int width,
            int height
    ) {
        boolean[] union = new boolean[width * height];
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                union[row + x] = masks[StylizedFourViewProjector.FRONT][row + x]
                        || masks[StylizedFourViewProjector.BACK][row + width - 1 - x];
            }
        }
        return union;
    }

    private static boolean[] createSideUnion(
            boolean[][] masks,
            int depth,
            int height
    ) {
        boolean[] union = new boolean[depth * height];
        for (int y = 0; y < height; y++) {
            int row = y * depth;
            for (int z = 0; z < depth; z++) {
                union[row + z] = masks[StylizedFourViewProjector.RIGHT][row + z]
                        || masks[StylizedFourViewProjector.LEFT][row + depth - 1 - z];
            }
        }
        return union;
    }

    private static RowDepth[] buildRowDepths(
            boolean[] sideUnion,
            int depth,
            int height
    ) {
        RowDepth[] rows = new RowDepth[height];
        for (int y = 0; y < height; y++) {
            int minimum = depth;
            int maximum = -1;
            int row = y * depth;
            for (int z = 0; z < depth; z++) {
                if (sideUnion[row + z]) {
                    minimum = Math.min(minimum, z);
                    maximum = Math.max(maximum, z);
                }
            }
            rows[y] = maximum >= minimum
                    ? new RowDepth((minimum + maximum) * 0.5f,
                    Math.max(1.0f, (maximum - minimum + 1) * 0.5f), true)
                    : new RowDepth(0.0f, 1.0f, false);
        }
        return rows;
    }

    private static void smoothRowDepths(RowDepth[] rows) {
        RowDepth[] source = Arrays.copyOf(rows, rows.length);
        for (int y = 1; y + 1 < rows.length; y++) {
            if (!source[y].valid) {
                continue;
            }
            float weight = 2.0f;
            float center = source[y].center * 2.0f;
            float radius = source[y].radius * 2.0f;
            if (source[y - 1].valid) {
                center += source[y - 1].center;
                radius += source[y - 1].radius;
                weight += 1.0f;
            }
            if (source[y + 1].valid) {
                center += source[y + 1].center;
                radius += source[y + 1].radius;
                weight += 1.0f;
            }
            rows[y] = new RowDepth(center / weight, radius / weight, true);
        }
    }

    private static int collectRuns(
            boolean[] mask,
            int width,
            int y,
            int[] starts,
            int[] ends
    ) {
        int count = 0;
        int x = 0;
        int row = y * width;
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
            starts[count] = start;
            ends[count] = x;
            count++;
            x++;
        }
        return count;
    }

    private static int firstOccupiedRow(boolean[] mask, int width, int height) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (mask[y * width + x]) {
                    return y;
                }
            }
        }
        return -1;
    }

    private static int lastOccupiedRow(boolean[] mask, int width, int height) {
        for (int y = height - 1; y >= 0; y--) {
            for (int x = 0; x < width; x++) {
                if (mask[y * width + x]) {
                    return y;
                }
            }
        }
        return -1;
    }

    private static void validate(
            boolean[] source,
            boolean[][] masks,
            int width,
            int height,
            int depth
    ) {
        if (source == null || source.length != width * height * depth) {
            throw new IllegalArgumentException("Volume anatomique invalide");
        }
        if (masks == null || masks.length != 4
                || masks[StylizedFourViewProjector.FRONT] == null
                || masks[StylizedFourViewProjector.BACK] == null
                || masks[StylizedFourViewProjector.RIGHT] == null
                || masks[StylizedFourViewProjector.LEFT] == null
                || masks[StylizedFourViewProjector.FRONT].length != width * height
                || masks[StylizedFourViewProjector.BACK].length != width * height
                || masks[StylizedFourViewProjector.RIGHT].length != depth * height
                || masks[StylizedFourViewProjector.LEFT].length != depth * height) {
            throw new IllegalArgumentException("Masques anatomiques incohérents");
        }
    }

    private static final class RowDepth {
        final float center;
        final float radius;
        final boolean valid;

        RowDepth(float center, float radius, boolean valid) {
            this.center = center;
            this.radius = radius;
            this.valid = valid;
        }
    }

    private static final class ComponentCleanup {
        final int removedComponents;
        final int removedVoxels;

        ComponentCleanup(int removedComponents, int removedVoxels) {
            this.removedComponents = removedComponents;
            this.removedVoxels = removedVoxels;
        }
    }

    public static final class Result {
        private final boolean[] volume;
        private final int prunedDepthVoxels;
        private final int removedComponents;
        private final int removedNoiseVoxels;
        private final int protectedLegGaps;

        Result(
                boolean[] volume,
                int prunedDepthVoxels,
                int removedComponents,
                int removedNoiseVoxels,
                int protectedLegGaps
        ) {
            this.volume = volume;
            this.prunedDepthVoxels = prunedDepthVoxels;
            this.removedComponents = removedComponents;
            this.removedNoiseVoxels = removedNoiseVoxels;
            this.protectedLegGaps = protectedLegGaps;
        }

        public boolean[] getVolume() {
            return volume;
        }

        public int getPrunedDepthVoxels() {
            return prunedDepthVoxels;
        }

        public int getRemovedComponents() {
            return removedComponents;
        }

        public int getRemovedNoiseVoxels() {
            return removedNoiseVoxels;
        }

        public int getProtectedLegGaps() {
            return protectedLegGaps;
        }

        public boolean hasChanges() {
            return prunedDepthVoxels > 0
                    || removedComponents > 0
                    || removedNoiseVoxels > 0
                    || protectedLegGaps > 0;
        }
    }
}
