package com.chasmet.modeliseur3d.model;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Générateur V3 spécialisé pour une planche de rotation :
 * grande face, petite face, dos, profil et trois-quarts.
 *
 * Le calcul reste entièrement local et utilise les cœurs CPU disponibles.
 */
public final class ImageToMeshGenerator {
    private static final int ATLAS_HEIGHT = 1024;
    private static final float COMPONENT_MIN_HEIGHT = 0.18f;
    private static final float COMPONENT_MIN_WIDTH = 0.018f;
    private static final float RELAXED_COMPONENT_MIN_HEIGHT = 0.10f;
    private static final float RELAXED_COMPONENT_MIN_WIDTH = 0.008f;

    public Result generate(Bitmap source) throws Exception {
        if (source == null || source.isRecycled()) {
            throw new IllegalArgumentException("Image absente");
        }

        PerformanceProfile profile = PerformanceProfile.detect();
        boolean[] foreground = estimateForeground(source);
        List<Component> components = findComponents(
                foreground,
                source.getWidth(),
                source.getHeight()
        );
        ViewSelection selection = selectViews(
                components,
                source.getWidth(),
                source.getHeight()
        );
        if (selection.front == null) {
            throw new IllegalArgumentException(
                    "Aucune silhouette exploitable n'a été détectée dans la planche"
            );
        }

        ViewData front = createView(source, foreground, selection.front);
        ViewData back = selection.back == null
                ? front
                : createView(source, foreground, selection.back);
        ViewData side = createView(source, foreground, selection.side);

        boolean[] frontMask = normalizeMask(front, profile.width, profile.height);
        boolean[] sideMask = normalizeMask(side, profile.depth, profile.height);
        cleanNormalizedMask(frontMask, profile.width, profile.height);
        cleanNormalizedMask(sideMask, profile.depth, profile.height);

        int[] frontDistance = distanceTransform(
                frontMask,
                profile.width,
                profile.height
        );
        int[] sideDistance = distanceTransform(
                sideMask,
                profile.depth,
                profile.height
        );

        boolean[] voxels = buildVisualHull(
                frontMask,
                sideMask,
                frontDistance,
                sideDistance,
                profile
        );
        closeVolume(voxels, profile.width, profile.height, profile.depth);
        keepLargestVolume(voxels, profile.width, profile.height, profile.depth);

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
                profile,
                atlasLayout
        );
        MeshData mesh = SmoothHullMesher.build(
                voxels,
                profile.width,
                profile.height,
                profile.depth,
                atlasLayout,
                profile.processors
        );

        front.recycleOwned();
        if (back != front) {
            back.recycleOwned();
        }
        side.recycleOwned();

        return new Result(
                mesh,
                atlas,
                selection.detectedViewCount,
                profile.label,
                profile.processors,
                countTrue(voxels)
        );
    }

    private static ViewSelection selectViews(
            List<Component> components,
            int imageWidth,
            int imageHeight
    ) {
        int minPixels = Math.max(220, imageWidth * imageHeight / 1800);
        List<Component> usable = filterComponents(
                components,
                minPixels,
                imageWidth,
                imageHeight,
                COMPONENT_MIN_WIDTH,
                COMPONENT_MIN_HEIGHT
        );
        if (usable.size() < 2) {
            usable = filterComponents(
                    components,
                    Math.max(80, minPixels / 3),
                    imageWidth,
                    imageHeight,
                    RELAXED_COMPONENT_MIN_WIDTH,
                    RELAXED_COMPONENT_MIN_HEIGHT
            );
        }

        Collections.sort(usable, new Comparator<Component>() {
            @Override
            public int compare(Component first, Component second) {
                return Integer.compare(first.bounds.left, second.bounds.left);
            }
        });
        if (usable.isEmpty()) {
            return new ViewSelection(null, null, null, usable.size());
        }

        Component front = usable.get(0);
        for (Component component : usable) {
            if (component.pixelCount > front.pixelCount) {
                front = component;
            }
        }

        // Une image avec une seule silhouette reste générable : la même vue
        // sert d'estimation latérale, au lieu de bloquer toute la reconstruction.
        if (usable.size() == 1) {
            return new ViewSelection(front, null, front, 1);
        }

        List<Component> candidates = new ArrayList<>();
        for (Component component : usable) {
            if (component != front) {
                candidates.add(component);
            }
        }
        if (candidates.isEmpty()) {
            return new ViewSelection(front, null, null, usable.size());
        }

        Component side = candidates.get(0);
        for (Component candidate : candidates) {
            if (candidate.aspectRatio() < side.aspectRatio()) {
                side = candidate;
            }
        }

        List<Component> nonSide = new ArrayList<>();
        for (Component candidate : candidates) {
            if (candidate != side) {
                nonSide.add(candidate);
            }
        }
        Collections.sort(nonSide, new Comparator<Component>() {
            @Override
            public int compare(Component first, Component second) {
                return Integer.compare(first.bounds.left, second.bounds.left);
            }
        });

        Component back = null;
        if (nonSide.size() >= 2) {
            back = nonSide.get(1);
        } else if (!nonSide.isEmpty()) {
            back = nonSide.get(0);
        }
        return new ViewSelection(front, back, side, usable.size());
    }

    private static List<Component> filterComponents(
            List<Component> components,
            int minimumPixels,
            int imageWidth,
            int imageHeight,
            float minimumWidthFraction,
            float minimumHeightFraction
    ) {
        List<Component> usable = new ArrayList<>();
        for (Component component : components) {
            if (component.pixelCount >= minimumPixels
                    && component.bounds.height()
                    >= imageHeight * minimumHeightFraction
                    && component.bounds.width()
                    >= imageWidth * minimumWidthFraction) {
                usable.add(component);
            }
        }
        return usable;
    }

    private static ViewData createView(
            Bitmap source,
            boolean[] sourceMask,
            Component component
    ) {
        Rect crop = addMargin(
                component.bounds,
                source.getWidth(),
                source.getHeight(),
                0.035f
        );
        Bitmap bitmap = Bitmap.createBitmap(
                source,
                crop.left,
                crop.top,
                crop.width(),
                crop.height()
        );
        boolean[] mask = new boolean[crop.width() * crop.height()];
        int sourceWidth = source.getWidth();
        for (int y = 0; y < crop.height(); y++) {
            int sourceOffset = (crop.top + y) * sourceWidth + crop.left;
            int targetOffset = y * crop.width();
            System.arraycopy(
                    sourceMask,
                    sourceOffset,
                    mask,
                    targetOffset,
                    crop.width()
            );
        }
        closeSmallGaps(mask, crop.width(), crop.height(), 1);
        retainLargest2D(mask, crop.width(), crop.height());
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
        closeSmallGaps(mask, width, height, 1);
        retainLargest2D(mask, width, height);
        dilate(mask, width, height, 1);
        erode(mask, width, height, 1);
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

    private static void closeVolume(
            boolean[] voxels,
            int width,
            int height,
            int depth
    ) {
        boolean[] dilated = new boolean[voxels.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    int current = voxelIndex(x, y, z, width, depth);
                    if (voxels[current]
                            || isVoxel(voxels, x - 1, y, z, width, height, depth)
                            || isVoxel(voxels, x + 1, y, z, width, height, depth)
                            || isVoxel(voxels, x, y - 1, z, width, height, depth)
                            || isVoxel(voxels, x, y + 1, z, width, height, depth)
                            || isVoxel(voxels, x, y, z - 1, width, height, depth)
                            || isVoxel(voxels, x, y, z + 1, width, height, depth)) {
                        dilated[current] = true;
                    }
                }
            }
        }

        boolean[] eroded = new boolean[voxels.length];
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                for (int z = 1; z < depth - 1; z++) {
                    int current = voxelIndex(x, y, z, width, depth);
                    eroded[current] = dilated[current]
                            && isVoxel(dilated, x - 1, y, z, width, height, depth)
                            && isVoxel(dilated, x + 1, y, z, width, height, depth)
                            && isVoxel(dilated, x, y - 1, z, width, height, depth)
                            && isVoxel(dilated, x, y + 1, z, width, height, depth)
                            && isVoxel(dilated, x, y, z - 1, width, height, depth)
                            && isVoxel(dilated, x, y, z + 1, width, height, depth);
                }
            }
        }
        System.arraycopy(eroded, 0, voxels, 0, voxels.length);
    }

    private static void keepLargestVolume(
            boolean[] voxels,
            int width,
            int height,
            int depth
    ) {
        boolean[] visited = new boolean[voxels.length];
        int[] queue = new int[voxels.length];
        int[] largest = new int[0];

        for (int start = 0; start < voxels.length; start++) {
            if (!voxels[start] || visited[start]) {
                continue;
            }
            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            visited[start] = true;

            while (head < tail) {
                int current = queue[head++];
                int z = current % depth;
                int value = current / depth;
                int x = value % width;
                int y = value / width;

                tail = enqueueVolumeNeighbour(
                        voxels, visited, queue, tail,
                        x - 1, y, z, width, height, depth
                );
                tail = enqueueVolumeNeighbour(
                        voxels, visited, queue, tail,
                        x + 1, y, z, width, height, depth
                );
                tail = enqueueVolumeNeighbour(
                        voxels, visited, queue, tail,
                        x, y - 1, z, width, height, depth
                );
                tail = enqueueVolumeNeighbour(
                        voxels, visited, queue, tail,
                        x, y + 1, z, width, height, depth
                );
                tail = enqueueVolumeNeighbour(
                        voxels, visited, queue, tail,
                        x, y, z - 1, width, height, depth
                );
                tail = enqueueVolumeNeighbour(
                        voxels, visited, queue, tail,
                        x, y, z + 1, width, height, depth
                );
            }

            if (tail > largest.length) {
                largest = Arrays.copyOf(queue, tail);
            }
        }

        Arrays.fill(voxels, false);
        for (int index : largest) {
            voxels[index] = true;
        }
    }

    private static int enqueueVolumeNeighbour(
            boolean[] voxels,
            boolean[] visited,
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
        if (voxels[neighbour] && !visited[neighbour]) {
            visited[neighbour] = true;
            queue[tail++] = neighbour;
        }
        return tail;
    }

    private static Bitmap buildTextureAtlas(
            ViewData front,
            ViewData back,
            ViewData side,
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
        Bitmap sideTexture = normalizedTexture(
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

        bleedTexture(output);
        return output;
    }

    private static void bleedTexture(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        boolean[] visited = new boolean[pixels.length];
        int[] queue = new int[pixels.length];
        int head = 0;
        int tail = 0;

        for (int i = 0; i < pixels.length; i++) {
            if (Color.alpha(pixels[i]) > 24) {
                pixels[i] = 0xFF000000 | (pixels[i] & 0x00FFFFFF);
                visited[i] = true;
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
            int x = current % width;
            int y = current / width;
            tail = propagateTexture(pixels, visited, queue, tail,
                    current, x - 1, y, width, height);
            tail = propagateTexture(pixels, visited, queue, tail,
                    current, x + 1, y, width, height);
            tail = propagateTexture(pixels, visited, queue, tail,
                    current, x, y - 1, width, height);
            tail = propagateTexture(pixels, visited, queue, tail,
                    current, x, y + 1, width, height);
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
    }

    private static int propagateTexture(
            int[] pixels,
            boolean[] visited,
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
        if (!visited[target]) {
            visited[target] = true;
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
            dilate(alphaMask, width, height, 1);
            erode(alphaMask, width, height, 1);
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

    private static List<Component> findComponents(
            boolean[] mask,
            int width,
            int height
    ) {
        boolean[] visited = new boolean[mask.length];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        List<Component> result = new ArrayList<>();

        for (int index = 0; index < mask.length; index++) {
            if (!mask[index] || visited[index]) {
                continue;
            }
            visited[index] = true;
            queue.add(index);
            int count = 0;
            int minX = width;
            int minY = height;
            int maxX = -1;
            int maxY = -1;

            while (!queue.isEmpty()) {
                int current = queue.removeFirst();
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
                        if (mask[next] && !visited[next]) {
                            visited[next] = true;
                            queue.addLast(next);
                        }
                    }
                }
            }

            if (maxX >= minX && maxY >= minY) {
                result.add(new Component(count,
                        new Rect(minX, minY, maxX + 1, maxY + 1)));
            }
        }
        return result;
    }

    private static void retainLargest2D(
            boolean[] mask,
            int width,
            int height
    ) {
        boolean[] visited = new boolean[mask.length];
        int[] queue = new int[mask.length];
        int[] largest = new int[0];

        for (int start = 0; start < mask.length; start++) {
            if (!mask[start] || visited[start]) {
                continue;
            }
            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            visited[start] = true;

            while (head < tail) {
                int current = queue[head++];
                int x = current % width;
                int y = current / width;
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
                        if (mask[next] && !visited[next]) {
                            visited[next] = true;
                            queue[tail++] = next;
                        }
                    }
                }
            }

            if (tail > largest.length) {
                largest = Arrays.copyOf(queue, tail);
            }
        }

        Arrays.fill(mask, false);
        for (int index : largest) {
            mask[index] = true;
        }
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

    private static void closeSmallGaps(
            boolean[] mask,
            int width,
            int height,
            int iterations
    ) {
        dilate(mask, width, height, iterations);
        erode(mask, width, height, iterations);
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

        Result(
                MeshData mesh,
                Bitmap texture,
                int detectedViewCount,
                String qualityLabel,
                int processorCount,
                int voxelCount
        ) {
            this.mesh = mesh;
            this.texture = texture;
            this.detectedViewCount = detectedViewCount;
            this.qualityLabel = qualityLabel;
            this.processorCount = processorCount;
            this.voxelCount = voxelCount;
        }

        public MeshData getMesh() { return mesh; }
        public Bitmap getTexture() { return texture; }
        public int getDetectedViewCount() { return detectedViewCount; }
        public String getQualityLabel() { return qualityLabel; }
        public int getProcessorCount() { return processorCount; }
        public int getVoxelCount() { return voxelCount; }
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
        final Component front;
        final Component back;
        final Component side;
        final int detectedViewCount;

        ViewSelection(
                Component front,
                Component back,
                Component side,
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

        float aspectRatio() {
            return bounds.width() / (float) Math.max(1, bounds.height());
        }
    }
}
