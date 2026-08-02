package com.chasmet.modeliseur3d.model;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Générateur géométrique V4.2 pour image unique et planche de rotation.
 *
 * Le calcul regroupe d'abord les morceaux d'une même silhouette, puis choisit
 * une enveloppe multivue ou une épaisseur monoculaire arrondie.
 */
public final class ImageToMeshGenerator {
    private static final int ATLAS_HEIGHT = 1024;
    private static final float RELIABLE_SIDE_RATIO = 0.82f;

    public Result generate(Bitmap source) throws Exception {
        if (source == null || source.isRecycled()) {
            throw new IllegalArgumentException("Image absente");
        }

        PerformanceProfile profile = PerformanceProfile.detect();
        boolean[] foreground = estimateForeground(source);
        ComponentMap componentMap = findComponents(
                foreground,
                source.getWidth(),
                source.getHeight()
        );
        ViewSelection selection = selectViews(
                componentMap,
                source.getWidth(),
                source.getHeight()
        );
        if (selection.front == null) {
            throw new IllegalArgumentException(
                    "Aucune silhouette exploitable n'a été détectée dans la planche"
            );
        }

        ViewData front = createView(source, componentMap, selection.front);
        ViewData back = selection.back == null
                ? front
                : createView(source, componentMap, selection.back);
        ViewData side = selection.side == null
                ? null
                : createView(source, componentMap, selection.side);
        foreground = null;
        componentMap = null;

        try {
            boolean[] frontMask = normalizeMask(
                    front,
                    profile.width,
                    profile.height
            );
            cleanNormalizedMask(frontMask, profile.width, profile.height);
            int[] frontDistance = distanceTransform(
                    frontMask,
                    profile.width,
                    profile.height
            );

            boolean[] voxels;
            if (side != null) {
                boolean[] sideMask = normalizeMask(
                        side,
                        profile.depth,
                        profile.height
                );
                cleanNormalizedMask(sideMask, profile.depth, profile.height);
                int[] sideDistance = distanceTransform(
                        sideMask,
                        profile.depth,
                        profile.height
                );
                voxels = buildVisualHull(
                        frontMask,
                        sideMask,
                        frontDistance,
                        sideDistance,
                        profile
                );
            } else {
                voxels = buildSingleViewHull(
                        frontMask,
                        frontDistance,
                        profile
                );
            }
            repairNarrowGaps(
                    voxels,
                    profile.width,
                    profile.height,
                    profile.depth
            );
            keepMeaningfulVolumes(
                    voxels,
                    profile.width,
                    profile.height,
                    profile.depth
            );

            SmoothHullMesher.AtlasLayout atlasLayout =
                    SmoothHullMesher.AtlasLayout.create(
                            profile.width,
                            profile.height,
                            profile.depth,
                            ATLAS_HEIGHT
                    );

            Bitmap atlas = buildTextureAtlas(
                    front,
                    back,
                    side,
                    frontMask,
                    frontDistance,
                    profile,
                    atlasLayout
            );
            MeshData mesh;
            try {
                mesh = SmoothHullMesher.build(
                        voxels,
                        profile.width,
                        profile.height,
                        profile.depth,
                        atlasLayout,
                        profile.processors
                );
            } catch (Exception | OutOfMemoryError error) {
                atlas.recycle();
                throw error;
            }

            return new Result(
                    mesh,
                    atlas,
                    selection.detectedViewCount,
                    profile.label,
                    profile.processors,
                    countTrue(voxels),
                    selection.back != null,
                    selection.side != null
            );
        } finally {
            front.recycleOwned();
            if (back != front) {
                back.recycleOwned();
            }
            if (side != null && side != front && side != back) {
                side.recycleOwned();
            }
        }
    }

    private static ViewSelection selectViews(
            ComponentMap componentMap,
            int imageWidth,
            int imageHeight
    ) {
        List<ViewCandidateGrouper.Piece> pieces = new ArrayList<>();
        for (int index = 0; index < componentMap.components.size(); index++) {
            Component component = componentMap.components.get(index);
            pieces.add(new ViewCandidateGrouper.Piece(
                    index,
                    component.pixelCount,
                    component.bounds.left,
                    component.bounds.top,
                    component.bounds.right,
                    component.bounds.bottom
            ));
        }
        List<ViewCandidateGrouper.Group> usable =
                ViewCandidateGrouper.group(pieces, imageWidth, imageHeight);
        if (usable.isEmpty()) {
            return new ViewSelection(null, null, null, usable.size());
        }

        ViewCandidateGrouper.Group front = usable.get(0);
        for (ViewCandidateGrouper.Group group : usable) {
            if (frontScore(group, imageHeight) > frontScore(front, imageHeight)) {
                front = group;
            }
        }

        if (usable.size() == 1) {
            return new ViewSelection(front, null, null, 1);
        }

        List<ViewCandidateGrouper.Group> candidates = new ArrayList<>();
        for (ViewCandidateGrouper.Group group : usable) {
            if (group != front) {
                candidates.add(group);
            }
        }

        ViewCandidateGrouper.Group side = candidates.get(0);
        for (ViewCandidateGrouper.Group candidate : candidates) {
            if (candidate.aspectRatio() < side.aspectRatio()) {
                side = candidate;
            }
        }
        float frontAspect = Math.max(0.05f, front.aspectRatio());
        float medianAspect = medianAspect(usable);
        boolean sideLargeEnough = side.height() >= front.height() * 0.35f
                && side.pixelCount >= front.pixelCount * 0.035f;
        boolean sideReliable = sideLargeEnough
                && (side.aspectRatio() <= frontAspect * RELIABLE_SIDE_RATIO
                || (usable.size() >= 3
                && side.aspectRatio() <= medianAspect * 0.84f));
        if (!sideReliable) {
            side = null;
        }

        ViewCandidateGrouper.Group back = null;
        float bestBackScore = Float.POSITIVE_INFINITY;
        for (ViewCandidateGrouper.Group candidate : candidates) {
            if (candidate == side) {
                continue;
            }
            float score = viewSimilarity(front, candidate);
            if (score < bestBackScore) {
                bestBackScore = score;
                back = candidate;
            }
        }
        return new ViewSelection(front, back, side, usable.size());
    }

    private static float frontScore(
            ViewCandidateGrouper.Group group,
            int imageHeight
    ) {
        float heightWeight = 0.70f
                + 0.30f * Math.min(1.0f, group.height() / (float) imageHeight);
        return group.pixelCount * heightWeight;
    }

    private static float medianAspect(
            List<ViewCandidateGrouper.Group> groups
    ) {
        float[] values = new float[groups.size()];
        for (int index = 0; index < groups.size(); index++) {
            values[index] = groups.get(index).aspectRatio();
        }
        Arrays.sort(values);
        return values[values.length / 2];
    }

    private static float viewSimilarity(
            ViewCandidateGrouper.Group reference,
            ViewCandidateGrouper.Group candidate
    ) {
        float aspect = Math.abs((float) Math.log(
                Math.max(0.04f, candidate.aspectRatio())
                        / Math.max(0.04f, reference.aspectRatio())
        ));
        float height = Math.abs((float) Math.log(
                Math.max(1.0f, candidate.height())
                        / Math.max(1.0f, reference.height())
        ));
        float area = Math.abs((float) Math.log(
                Math.max(1.0f, candidate.pixelCount)
                        / Math.max(1.0f, reference.pixelCount)
        ));
        return aspect + height * 0.42f + area * 0.18f;
    }

    private static ViewData createView(
            Bitmap source,
            ComponentMap componentMap,
            ViewCandidateGrouper.Group group
    ) {
        Rect crop = addMargin(
                new Rect(group.left, group.top, group.right, group.bottom),
                source.getWidth(),
                source.getHeight(),
                0.045f
        );
        Bitmap bitmap = Bitmap.createBitmap(
                source,
                crop.left,
                crop.top,
                crop.width(),
                crop.height()
        );
        boolean[] mask = new boolean[crop.width() * crop.height()];
        boolean[] allowedComponents = new boolean[
                componentMap.components.size()
        ];
        group.markPieces(allowedComponents);
        int sourceWidth = source.getWidth();
        for (int y = 0; y < crop.height(); y++) {
            int sourceOffset = (crop.top + y) * sourceWidth + crop.left;
            int targetOffset = y * crop.width();
            for (int x = 0; x < crop.width(); x++) {
                int componentId = componentMap.labels[sourceOffset + x];
                mask[targetOffset + x] = componentId >= 0
                        && componentId < allowedComponents.length
                        && allowedComponents[componentId];
            }
        }
        bridgeNarrowGaps2D(mask, crop.width(), crop.height());
        return new ViewData(bitmap, mask, crop.width(), crop.height());
    }

    private static boolean[] normalizeMask(
            ViewData view,
            int targetWidth,
            int targetHeight
    ) {
        boolean[] output = new boolean[targetWidth * targetHeight];
        float scale = Math.min(
                targetHeight / (float) view.height,
                targetWidth / (float) view.width
        );
        int drawWidth = Math.max(1, Math.round(view.width * scale));
        int drawHeight = Math.max(1, Math.round(view.height * scale));
        int offsetX = (targetWidth - drawWidth) / 2;
        int offsetY = (targetHeight - drawHeight) / 2;

        for (int y = 0; y < drawHeight; y++) {
            int sourceY = Math.min(
                    view.height - 1,
                    (int) ((y + 0.5f) * view.height / drawHeight)
            );
            int targetY = offsetY + y;
            for (int x = 0; x < drawWidth; x++) {
                int sourceX = Math.min(
                        view.width - 1,
                        (int) ((x + 0.5f) * view.width / drawWidth)
                );
                if (view.mask[sourceY * view.width + sourceX]) {
                    output[targetY * targetWidth + offsetX + x] = true;
                }
            }
        }
        return output;
    }

    private static void cleanNormalizedMask(
            boolean[] mask,
            int width,
            int height
    ) {
        // Ne jamais supprimer ici les petits volumes : une patte, une main ou
        // un accessoire séparé doit rester présent dans le modèle final.
        bridgeNarrowGaps2D(mask, width, height);
    }

    private static void bridgeNarrowGaps2D(
            boolean[] mask,
            int width,
            int height
    ) {
        boolean[] source = Arrays.copyOf(mask, mask.length);
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int index = y * width + x;
                if (source[index]) {
                    continue;
                }
                boolean horizontal = source[index - 1] && source[index + 1];
                boolean vertical = source[index - width]
                        && source[index + width];
                boolean diagonalDown = source[index - width - 1]
                        && source[index + width + 1];
                boolean diagonalUp = source[index - width + 1]
                        && source[index + width - 1];
                if (horizontal || vertical || diagonalDown || diagonalUp) {
                    mask[index] = true;
                }
            }
        }
    }

    private static boolean[] buildVisualHull(
            boolean[] front,
            boolean[] side,
            int[] frontDistance,
            int[] sideDistance,
            PerformanceProfile profile
    ) throws Exception {
        int width = profile.width;
        int height = profile.height;
        int depth = profile.depth;
        boolean[] voxels = new boolean[width * height * depth];
        int[] maxFrontDistance = rowMax(
                frontDistance,
                front,
                width,
                height
        );
        int[] maxSideDistance = rowMax(
                sideDistance,
                side,
                depth,
                height
        );

        int workers = Math.max(1, Math.min(profile.processors - 1, 10));
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        List<Future<Void>> futures = new ArrayList<>();
        int rowsPerTask = Math.max(4, (height + workers - 1) / workers);

        for (int startY = 0; startY < height; startY += rowsPerTask) {
            final int from = startY;
            final int to = Math.min(height, startY + rowsPerTask);
            futures.add(executor.submit(new Callable<Void>() {
                @Override
                public Void call() {
                    for (int y = from; y < to; y++) {
                        int maxFront = Math.max(1, maxFrontDistance[y]);
                        int maxSide = Math.max(1, maxSideDistance[y]);
                        for (int x = 0; x < width; x++) {
                            int frontIndex = y * width + x;
                            if (!front[frontIndex]) {
                                continue;
                            }
                            float frontEdge = 1.0f - Math.min(
                                    1.0f,
                                    frontDistance[frontIndex] / (float) maxFront
                            );
                            for (int z = 0; z < depth; z++) {
                                int sideIndex = y * depth + z;
                                if (!side[sideIndex]) {
                                    continue;
                                }
                                float sideEdge = 1.0f - Math.min(
                                        1.0f,
                                        sideDistance[sideIndex] / (float) maxSide
                                );
                                if (frontEdge * frontEdge
                                        + sideEdge * sideEdge <= 1.34f) {
                                    voxels[voxelIndex(
                                            x,
                                            y,
                                            z,
                                            width,
                                            depth
                                    )] = true;
                                }
                            }
                        }
                    }
                    return null;
                }
            }));
        }

        try {
            for (Future<Void> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }
        return voxels;
    }

    /**
     * Volume arrondi pour une image unique.
     *
     * La V4.1.2 réutilisait la silhouette de face comme faux profil : les
     * épaules devenaient aussi épaisses que larges et les animaux s'écrasaient
     * en rubans. Ici l'épaisseur dépend de la distance réelle au bord. Les
     * membres fins restent donc fins, tandis que le torse ou le corps gagne un
     * volume progressif et symétrique.
     */
    private static boolean[] buildSingleViewHull(
            boolean[] front,
            int[] frontDistance,
            PerformanceProfile profile
    ) {
        int width = profile.width;
        int height = profile.height;
        int depth = profile.depth;
        boolean[] voxels = new boolean[width * height * depth];
        int maximumDistance = 1;
        for (int index = 0; index < front.length; index++) {
            if (front[index] && frontDistance[index] < 100_000) {
                maximumDistance = Math.max(
                        maximumDistance,
                        frontDistance[index]
                );
            }
        }

        int center = (depth - 1) / 2;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int frontIndex = y * width + x;
                if (!front[frontIndex]) {
                    continue;
                }
                int halfDepth = singleViewHalfDepth(
                        frontDistance[frontIndex],
                        maximumDistance,
                        depth
                );
                int from = Math.max(1, center - halfDepth);
                int to = Math.min(depth - 2, center + halfDepth);
                for (int z = from; z <= to; z++) {
                    voxels[voxelIndex(x, y, z, width, depth)] = true;
                }
            }
        }
        return voxels;
    }

    private static int singleViewHalfDepth(
            int distance,
            int maximumDistance,
            int depth
    ) {
        float edgeDistance = Math.max(0.0f, distance - 3.0f);
        float usableMaximum = Math.max(1.0f, maximumDistance - 3.0f);
        float normalized = Math.max(
                0.0f,
                Math.min(1.0f, edgeDistance / usableMaximum)
        );
        float rounded = (float) Math.pow(normalized, 1.25f);
        float maximumHalfDepth = Math.max(2.0f, depth * 0.42f);
        return Math.max(
                1,
                Math.round(1.0f + rounded * (maximumHalfDepth - 1.0f))
        );
    }

    private static int[] rowMax(
            int[] distance,
            boolean[] mask,
            int width,
            int height
    ) {
        int[] result = new int[height];
        for (int y = 0; y < height; y++) {
            int maximum = 0;
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                if (mask[index]) {
                    maximum = Math.max(maximum, distance[index]);
                }
            }
            result[y] = maximum;
        }
        return result;
    }

    private static void repairNarrowGaps(
            boolean[] voxels,
            int width,
            int height,
            int depth
    ) {
        boolean[] source = Arrays.copyOf(voxels, voxels.length);
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                for (int z = 1; z < depth - 1; z++) {
                    int current = voxelIndex(x, y, z, width, depth);
                    if (source[current]) {
                        continue;
                    }
                    boolean bridgeX = isVoxel(
                            source, x - 1, y, z, width, height, depth
                    ) && isVoxel(
                            source, x + 1, y, z, width, height, depth
                    );
                    boolean bridgeY = isVoxel(
                            source, x, y - 1, z, width, height, depth
                    ) && isVoxel(
                            source, x, y + 1, z, width, height, depth
                    );
                    boolean bridgeZ = isVoxel(
                            source, x, y, z - 1, width, height, depth
                    ) && isVoxel(
                            source, x, y, z + 1, width, height, depth
                    );
                    if (bridgeX || bridgeY || bridgeZ) {
                        voxels[current] = true;
                    }
                }
            }
        }
    }

    private static void keepMeaningfulVolumes(
            boolean[] voxels,
            int width,
            int height,
            int depth
    ) {
        int[] labels = new int[voxels.length];
        Arrays.fill(labels, -1);
        int[] queue = new int[voxels.length];
        int[] sizes = new int[32];
        int componentCount = 0;
        int largestSize = 0;

        for (int start = 0; start < voxels.length; start++) {
            if (!voxels[start] || labels[start] >= 0) {
                continue;
            }
            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            labels[start] = componentCount;

            while (head < tail) {
                int current = queue[head++];
                int z = current % depth;
                int value = current / depth;
                int x = value % width;
                int y = value / width;

                tail = enqueueVolumeNeighbour(
                        voxels, labels, componentCount, queue, tail,
                        x - 1, y, z, width, height, depth
                );
                tail = enqueueVolumeNeighbour(
                        voxels, labels, componentCount, queue, tail,
                        x + 1, y, z, width, height, depth
                );
                tail = enqueueVolumeNeighbour(
                        voxels, labels, componentCount, queue, tail,
                        x, y - 1, z, width, height, depth
                );
                tail = enqueueVolumeNeighbour(
                        voxels, labels, componentCount, queue, tail,
                        x, y + 1, z, width, height, depth
                );
                tail = enqueueVolumeNeighbour(
                        voxels, labels, componentCount, queue, tail,
                        x, y, z - 1, width, height, depth
                );
                tail = enqueueVolumeNeighbour(
                        voxels, labels, componentCount, queue, tail,
                        x, y, z + 1, width, height, depth
                );
            }

            if (componentCount >= sizes.length) {
                sizes = Arrays.copyOf(sizes, sizes.length * 2);
            }
            sizes[componentCount] = tail;
            largestSize = Math.max(largestSize, tail);
            componentCount++;
        }

        int minimumSize = Math.max(10, largestSize / 1_800);
        for (int index = 0; index < voxels.length; index++) {
            int label = labels[index];
            if (voxels[index]
                    && (label < 0 || sizes[label] < minimumSize)) {
                voxels[index] = false;
            }
        }
    }

    private static int enqueueVolumeNeighbour(
            boolean[] voxels,
            int[] labels,
            int component,
            int[] queue,
            int tail,
            int x,
            int y,
            int z,
            int width,
            int height,
            int depth
    ) {
        if (x < 0 || y < 0 || z < 0
                || x >= width || y >= height || z >= depth) {
            return tail;
        }
        int neighbour = voxelIndex(x, y, z, width, depth);
        if (voxels[neighbour] && labels[neighbour] < 0) {
            labels[neighbour] = component;
            queue[tail++] = neighbour;
        }
        return tail;
    }

    private static Bitmap buildTextureAtlas(
            ViewData front,
            ViewData back,
            ViewData side,
            boolean[] frontMask,
            int[] frontDistance,
            PerformanceProfile profile,
            SmoothHullMesher.AtlasLayout layout
    ) {
        Bitmap frontTexture = normalizedTexture(
                front,
                profile.width,
                profile.height
        );
        Bitmap backTexture = normalizedTexture(
                back,
                profile.width,
                profile.height
        );
        Bitmap sideTexture = side == null
                ? syntheticSideTexture(
                        frontTexture,
                        frontMask,
                        frontDistance,
                        profile.width,
                        profile.height,
                        profile.depth
                )
                : normalizedTexture(
                        side,
                        profile.depth,
                        profile.height
                );

        Bitmap atlas = Bitmap.createBitmap(
                layout.atlasWidth,
                layout.atlasHeight,
                Bitmap.Config.ARGB_8888
        );
        Canvas canvas = new Canvas(atlas);
        canvas.drawColor(Color.rgb(24, 26, 32));
        Paint paint = new Paint(
                Paint.ANTI_ALIAS_FLAG
                        | Paint.FILTER_BITMAP_FLAG
                        | Paint.DITHER_FLAG
        );

        drawAtlasCell(canvas, paint, frontTexture,
                layout.frontStart, layout.frontWidth, layout.atlasHeight, false);
        drawAtlasCell(canvas, paint, backTexture,
                layout.backStart, layout.frontWidth, layout.atlasHeight, false);
        drawAtlasCell(canvas, paint, sideTexture,
                layout.rightStart, layout.sideWidth, layout.atlasHeight, false);
        drawAtlasCell(canvas, paint, sideTexture,
                layout.leftStart, layout.sideWidth, layout.atlasHeight, true);

        frontTexture.recycle();
        backTexture.recycle();
        sideTexture.recycle();
        return atlas;
    }

    private static Bitmap normalizedTexture(
            ViewData view,
            int targetWidth,
            int targetHeight
    ) {
        Bitmap isolated = view.transparentBitmap();
        Bitmap output = Bitmap.createBitmap(
                targetWidth,
                targetHeight,
                Bitmap.Config.ARGB_8888
        );
        Canvas canvas = new Canvas(output);
        canvas.drawColor(Color.TRANSPARENT);

        float scale = Math.min(
                targetHeight / (float) view.height,
                targetWidth / (float) view.width
        );
        float drawWidth = view.width * scale;
        float drawHeight = view.height * scale;
        float left = (targetWidth - drawWidth) * 0.5f;
        float top = (targetHeight - drawHeight) * 0.5f;
        RectF destination = new RectF(
                left,
                top,
                left + drawWidth,
                top + drawHeight
        );
        Paint paint = new Paint(
                Paint.ANTI_ALIAS_FLAG
                        | Paint.FILTER_BITMAP_FLAG
                        | Paint.DITHER_FLAG
        );
        canvas.drawBitmap(isolated, null, destination, paint);
        isolated.recycle();

        bleedTexture(output, 10);
        return output;
    }

    private static Bitmap syntheticSideTexture(
            Bitmap frontTexture,
            boolean[] frontMask,
            int[] frontDistance,
            int frontWidth,
            int height,
            int depth
    ) {
        Bitmap output = Bitmap.createBitmap(
                depth,
                height,
                Bitmap.Config.ARGB_8888
        );
        int[] frontPixels = new int[frontWidth * height];
        frontTexture.getPixels(
                frontPixels,
                0,
                frontWidth,
                0,
                0,
                frontWidth,
                height
        );
        int[] sidePixels = new int[depth * height];
        int maximumDistance = 1;
        for (int index = 0; index < frontMask.length; index++) {
            if (frontMask[index] && frontDistance[index] < 100_000) {
                maximumDistance = Math.max(
                        maximumDistance,
                        frontDistance[index]
                );
            }
        }

        int centerZ = (depth - 1) / 2;
        for (int y = 0; y < height; y++) {
            int deepestX = -1;
            int rowDistance = 0;
            for (int x = 0; x < frontWidth; x++) {
                int index = y * frontWidth + x;
                if (frontMask[index]
                        && frontDistance[index] > rowDistance) {
                    rowDistance = frontDistance[index];
                    deepestX = x;
                }
            }
            if (deepestX < 0) {
                continue;
            }
            int color = 0xFF000000
                    | (frontPixels[y * frontWidth + deepestX] & 0x00FFFFFF);
            int halfDepth = singleViewHalfDepth(
                    rowDistance,
                    maximumDistance,
                    depth
            );
            int from = Math.max(0, centerZ - halfDepth);
            int to = Math.min(depth - 1, centerZ + halfDepth);
            for (int z = from; z <= to; z++) {
                sidePixels[y * depth + z] = color;
            }
        }
        output.setPixels(sidePixels, 0, depth, 0, 0, depth, height);
        bleedTexture(output, 8);
        return output;
    }

    private static void bleedTexture(Bitmap bitmap, int maximumDistance) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        int[] distance = new int[pixels.length];
        Arrays.fill(distance, -1);
        int[] queue = new int[pixels.length];
        int head = 0;
        int tail = 0;

        for (int i = 0; i < pixels.length; i++) {
            if (Color.alpha(pixels[i]) > 24) {
                pixels[i] = 0xFF000000 | (pixels[i] & 0x00FFFFFF);
                distance[i] = 0;
                queue[tail++] = i;
            }
        }

        if (tail == 0) {
            Arrays.fill(pixels, Color.rgb(128, 128, 128));
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
            return;
        }

        while (head < tail) {
            int current = queue[head++];
            if (distance[current] >= maximumDistance) {
                continue;
            }
            int x = current % width;
            int y = current / width;
            tail = propagateTexture(pixels, distance, queue, tail,
                    current, x - 1, y, width, height);
            tail = propagateTexture(pixels, distance, queue, tail,
                    current, x + 1, y, width, height);
            tail = propagateTexture(pixels, distance, queue, tail,
                    current, x, y - 1, width, height);
            tail = propagateTexture(pixels, distance, queue, tail,
                    current, x, y + 1, width, height);
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
    }

    private static int propagateTexture(
            int[] pixels,
            int[] distance,
            int[] queue,
            int tail,
            int sourceIndex,
            int x,
            int y,
            int width,
            int height
    ) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            return tail;
        }
        int target = y * width + x;
        if (distance[target] < 0) {
            distance[target] = distance[sourceIndex] + 1;
            pixels[target] = pixels[sourceIndex];
            queue[tail++] = target;
        }
        return tail;
    }

    private static void drawAtlasCell(
            Canvas canvas,
            Paint paint,
            Bitmap texture,
            int start,
            int width,
            int height,
            boolean mirror
    ) {
        RectF destination = new RectF(start, 0, start + width, height);
        if (!mirror) {
            canvas.drawBitmap(texture, null, destination, paint);
            return;
        }

        canvas.save();
        Matrix matrix = new Matrix();
        matrix.setScale(-1.0f, 1.0f,
                start + width * 0.5f, height * 0.5f);
        canvas.concat(matrix);
        canvas.drawBitmap(texture, null, destination, paint);
        canvas.restore();
    }

    private static boolean[] estimateForeground(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        boolean hasTransparentBackground = false;
        for (int color : pixels) {
            if (Color.alpha(color) < 24) {
                hasTransparentBackground = true;
                break;
            }
        }
        if (hasTransparentBackground) {
            boolean[] alphaMask = new boolean[pixels.length];
            for (int i = 0; i < pixels.length; i++) {
                alphaMask[i] = Color.alpha(pixels[i]) >= 24;
            }
            bridgeNarrowGaps2D(alphaMask, width, height);
            return alphaMask;
        }

        int sampleWidth = Math.max(4, width / 45);
        int[] leftR = new int[height];
        int[] leftG = new int[height];
        int[] leftB = new int[height];
        int[] rightR = new int[height];
        int[] rightG = new int[height];
        int[] rightB = new int[height];

        for (int y = 0; y < height; y++) {
            long lr = 0;
            long lg = 0;
            long lb = 0;
            long rr = 0;
            long rg = 0;
            long rb = 0;
            int leftCount = 0;
            int rightCount = 0;

            for (int x = 0; x < sampleWidth; x++) {
                int color = pixels[y * width + x];
                if (Color.alpha(color) > 20) {
                    lr += Color.red(color);
                    lg += Color.green(color);
                    lb += Color.blue(color);
                    leftCount++;
                }
            }
            for (int x = width - sampleWidth; x < width; x++) {
                int color = pixels[y * width + x];
                if (Color.alpha(color) > 20) {
                    rr += Color.red(color);
                    rg += Color.green(color);
                    rb += Color.blue(color);
                    rightCount++;
                }
            }

            leftCount = Math.max(1, leftCount);
            rightCount = Math.max(1, rightCount);
            leftR[y] = (int) (lr / leftCount);
            leftG[y] = (int) (lg / leftCount);
            leftB[y] = (int) (lb / leftCount);
            rightR[y] = (int) (rr / rightCount);
            rightG[y] = (int) (rg / rightCount);
            rightB[y] = (int) (rb / rightCount);
        }

        boolean[] mask = new boolean[pixels.length];
        float denominator = Math.max(1.0f, width - 1.0f);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                int color = pixels[index];
                if (Color.alpha(color) < 24) {
                    continue;
                }

                float t = x / denominator;
                int bgR = Math.round(leftR[y] + (rightR[y] - leftR[y]) * t);
                int bgG = Math.round(leftG[y] + (rightG[y] - leftG[y]) * t);
                int bgB = Math.round(leftB[y] + (rightB[y] - leftB[y]) * t);

                int r = Color.red(color);
                int g = Color.green(color);
                int b = Color.blue(color);
                int dr = r - bgR;
                int dg = g - bgG;
                int db = b - bgB;
                int distanceSquared = dr * dr + dg * dg + db * db;
                int luminance = (r * 299 + g * 587 + b * 114) / 1000;
                int backgroundLuminance =
                        (bgR * 299 + bgG * 587 + bgB * 114) / 1000;
                int maximum = Math.max(r, Math.max(g, b));
                int minimum = Math.min(r, Math.min(g, b));
                int saturation = maximum - minimum;

                mask[index] = distanceSquared > 30 * 30
                        || Math.abs(luminance - backgroundLuminance) > 20
                        || (saturation > 30 && distanceSquared > 20 * 20);
            }
        }

        dilate(mask, width, height, 1);
        erode(mask, width, height, 1);
        return mask;
    }

    private static ComponentMap findComponents(
            boolean[] mask,
            int width,
            int height
    ) {
        int[] labels = new int[mask.length];
        Arrays.fill(labels, -1);
        int[] queue = new int[mask.length];
        List<Component> result = new ArrayList<>();

        for (int index = 0; index < mask.length; index++) {
            if (!mask[index] || labels[index] >= 0) {
                continue;
            }
            int componentId = result.size();
            labels[index] = componentId;
            int head = 0;
            int tail = 0;
            queue[tail++] = index;
            int count = 0;
            int minX = width;
            int minY = height;
            int maxX = -1;
            int maxY = -1;

            while (head < tail) {
                int current = queue[head++];
                int x = current % width;
                int y = current / width;
                count++;
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);

                for (int offsetY = -1; offsetY <= 1; offsetY++) {
                    for (int offsetX = -1; offsetX <= 1; offsetX++) {
                        if (offsetX == 0 && offsetY == 0) {
                            continue;
                        }
                        int nextX = x + offsetX;
                        int nextY = y + offsetY;
                        if (nextX < 0 || nextY < 0
                                || nextX >= width || nextY >= height) {
                            continue;
                        }
                        int next = nextY * width + nextX;
                        if (mask[next] && labels[next] < 0) {
                            labels[next] = componentId;
                            queue[tail++] = next;
                        }
                    }
                }
            }

            if (maxX >= minX && maxY >= minY) {
                result.add(new Component(count,
                        new Rect(minX, minY, maxX + 1, maxY + 1)));
            }
        }
        return new ComponentMap(result, labels);
    }

    private static Rect addMargin(
            Rect source,
            int width,
            int height,
            float fraction
    ) {
        int marginX = Math.max(3, Math.round(source.width() * fraction));
        int marginY = Math.max(3, Math.round(source.height() * fraction));
        return new Rect(
                Math.max(0, source.left - marginX),
                Math.max(0, source.top - marginY),
                Math.min(width, source.right + marginX),
                Math.min(height, source.bottom + marginY)
        );
    }

    private static void dilate(
            boolean[] mask,
            int width,
            int height,
            int iterations
    ) {
        for (int iteration = 0; iteration < iterations; iteration++) {
            boolean[] copy = Arrays.copyOf(mask, mask.length);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (copy[y * width + x]) {
                        continue;
                    }
                    boolean neighbour = false;
                    for (int offsetY = -1;
                         offsetY <= 1 && !neighbour;
                         offsetY++) {
                        for (int offsetX = -1;
                             offsetX <= 1;
                             offsetX++) {
                            int nextX = x + offsetX;
                            int nextY = y + offsetY;
                            if (nextX >= 0 && nextY >= 0
                                    && nextX < width && nextY < height
                                    && copy[nextY * width + nextX]) {
                                neighbour = true;
                                break;
                            }
                        }
                    }
                    if (neighbour) {
                        mask[y * width + x] = true;
                    }
                }
            }
        }
    }

    private static void erode(
            boolean[] mask,
            int width,
            int height,
            int iterations
    ) {
        for (int iteration = 0; iteration < iterations; iteration++) {
            boolean[] copy = Arrays.copyOf(mask, mask.length);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (!copy[y * width + x]) {
                        continue;
                    }
                    boolean touchesBackground = false;
                    for (int offsetY = -1;
                         offsetY <= 1 && !touchesBackground;
                         offsetY++) {
                        for (int offsetX = -1;
                             offsetX <= 1;
                             offsetX++) {
                            int nextX = x + offsetX;
                            int nextY = y + offsetY;
                            if (nextX < 0 || nextY < 0
                                    || nextX >= width || nextY >= height
                                    || !copy[nextY * width + nextX]) {
                                touchesBackground = true;
                                break;
                            }
                        }
                    }
                    if (touchesBackground) {
                        mask[y * width + x] = false;
                    }
                }
            }
        }
    }

    private static int[] distanceTransform(
            boolean[] mask,
            int width,
            int height
    ) {
        int infinity = 1_000_000;
        int[] distance = new int[mask.length];
        for (int i = 0; i < mask.length; i++) {
            distance[i] = mask[i] ? infinity : 0;
        }

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                if (!mask[index]) {
                    continue;
                }
                int best = distance[index];
                if (x > 0) best = Math.min(best, distance[index - 1] + 3);
                if (y > 0) best = Math.min(best, distance[index - width] + 3);
                if (x > 0 && y > 0) {
                    best = Math.min(best, distance[index - width - 1] + 4);
                }
                if (x + 1 < width && y > 0) {
                    best = Math.min(best, distance[index - width + 1] + 4);
                }
                distance[index] = best;
            }
        }

        for (int y = height - 1; y >= 0; y--) {
            for (int x = width - 1; x >= 0; x--) {
                int index = y * width + x;
                if (!mask[index]) {
                    continue;
                }
                int best = distance[index];
                if (x + 1 < width) best = Math.min(best, distance[index + 1] + 3);
                if (y + 1 < height) best = Math.min(best, distance[index + width] + 3);
                if (x + 1 < width && y + 1 < height) {
                    best = Math.min(best, distance[index + width + 1] + 4);
                }
                if (x > 0 && y + 1 < height) {
                    best = Math.min(best, distance[index + width - 1] + 4);
                }
                distance[index] = best;
            }
        }
        return distance;
    }

    private static boolean isVoxel(
            boolean[] voxels,
            int x,
            int y,
            int z,
            int width,
            int height,
            int depth
    ) {
        return x >= 0 && y >= 0 && z >= 0
                && x < width && y < height && z < depth
                && voxels[voxelIndex(x, y, z, width, depth)];
    }

    private static int voxelIndex(
            int x,
            int y,
            int z,
            int width,
            int depth
    ) {
        return (y * width + x) * depth + z;
    }

    private static int countTrue(boolean[] values) {
        int count = 0;
        for (boolean value : values) {
            if (value) count++;
        }
        return count;
    }

    public static final class Result {
        private final MeshData mesh;
        private final Bitmap texture;
        private final int detectedViewCount;
        private final String qualityLabel;
        private final int processorCount;
        private final int voxelCount;
        private final boolean hasBackView;
        private final boolean hasSideView;

        Result(
                MeshData mesh,
                Bitmap texture,
                int detectedViewCount,
                String qualityLabel,
                int processorCount,
                int voxelCount,
                boolean hasBackView,
                boolean hasSideView
        ) {
            this.mesh = mesh;
            this.texture = texture;
            this.detectedViewCount = detectedViewCount;
            this.qualityLabel = qualityLabel;
            this.processorCount = processorCount;
            this.voxelCount = voxelCount;
            this.hasBackView = hasBackView;
            this.hasSideView = hasSideView;
        }

        public MeshData getMesh() { return mesh; }
        public Bitmap getTexture() { return texture; }
        public int getDetectedViewCount() { return detectedViewCount; }
        public String getQualityLabel() { return qualityLabel; }
        public int getProcessorCount() { return processorCount; }
        public int getVoxelCount() { return voxelCount; }
        public boolean hasBackView() { return hasBackView; }
        public boolean hasSideView() { return hasSideView; }
    }

    private static final class PerformanceProfile {
        final int width;
        final int height;
        final int depth;
        final int processors;
        final String label;

        PerformanceProfile(
                int width,
                int height,
                int depth,
                int processors,
                String label
        ) {
            this.width = width;
            this.height = height;
            this.depth = depth;
            this.processors = processors;
            this.label = label;
        }

        static PerformanceProfile detect() {
            int processors = Math.max(1,
                    Runtime.getRuntime().availableProcessors());
            long maxMemoryMb =
                    Runtime.getRuntime().maxMemory() / (1024L * 1024L);

            if (processors >= 8 && maxMemoryMb >= 420) {
                return new PerformanceProfile(
                        112, 224, 84, processors, "Ultra propre");
            }
            if (processors >= 6 && maxMemoryMb >= 300) {
                return new PerformanceProfile(
                        96, 192, 72, processors, "Haute précision");
            }
            if (processors >= 4 && maxMemoryMb >= 220) {
                return new PerformanceProfile(
                        80, 160, 60, processors, "Équilibrée");
            }
            return new PerformanceProfile(
                    64, 128, 48, processors, "Compatible");
        }
    }

    private static final class ViewSelection {
        final ViewCandidateGrouper.Group front;
        final ViewCandidateGrouper.Group back;
        final ViewCandidateGrouper.Group side;
        final int detectedViewCount;

        ViewSelection(
                ViewCandidateGrouper.Group front,
                ViewCandidateGrouper.Group back,
                ViewCandidateGrouper.Group side,
                int detectedViewCount
        ) {
            this.front = front;
            this.back = back;
            this.side = side;
            this.detectedViewCount = detectedViewCount;
        }
    }

    private static final class ViewData {
        final Bitmap bitmap;
        final boolean[] mask;
        final int width;
        final int height;

        ViewData(Bitmap bitmap, boolean[] mask, int width, int height) {
            this.bitmap = bitmap;
            this.mask = mask;
            this.width = width;
            this.height = height;
        }

        Bitmap transparentBitmap() {
            Bitmap result = bitmap.copy(Bitmap.Config.ARGB_8888, true);
            int[] pixels = new int[width * height];
            result.getPixels(pixels, 0, width, 0, 0, width, height);
            for (int i = 0; i < pixels.length; i++) {
                if (!mask[i]) {
                    pixels[i] = Color.TRANSPARENT;
                } else {
                    pixels[i] = 0xFF000000 | (pixels[i] & 0x00FFFFFF);
                }
            }
            result.setPixels(pixels, 0, width, 0, 0, width, height);
            return result;
        }

        void recycleOwned() {
            if (!bitmap.isRecycled()) bitmap.recycle();
        }
    }

    private static final class Component {
        final int pixelCount;
        final Rect bounds;

        Component(int pixelCount, Rect bounds) {
            this.pixelCount = pixelCount;
            this.bounds = bounds;
        }

    }

    private static final class ComponentMap {
        final List<Component> components;
        final int[] labels;

        ComponentMap(List<Component> components, int[] labels) {
            this.components = components;
            this.labels = labels;
        }
    }
}
