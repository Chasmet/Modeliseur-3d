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
 * Générateur V2 destiné aux planches de rotation contenant plusieurs vues du même personnage.
 * La reconstruction est effectuée localement par enveloppe visuelle multi-vues.
 */
public final class ImageToMeshGenerator {
    private static final int ATLAS_SIZE = 1024;
    private static final float COMPONENT_MIN_HEIGHT = 0.27f;

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
        ViewSelection selection = selectViews(components, source.getWidth(), source.getHeight());
        if (selection.front == null || selection.side == null) {
            throw new IllegalArgumentException(
                    "La V2 attend une planche avec au moins une vue de face, une vue de dos et une vue de profil"
            );
        }

        ViewData front = createView(source, foreground, selection.front);
        ViewData back = selection.back == null ? front : createView(source, foreground, selection.back);
        ViewData side = createView(source, foreground, selection.side);

        boolean[] frontMask = normalizeMask(front, profile.width, profile.height);
        boolean[] backMask = normalizeMask(back, profile.width, profile.height);
        boolean[] sideMask = normalizeMask(side, profile.depth, profile.height);

        // La vue arrière corrige les différences de silhouette tout en conservant les détails de face.
        dilate(backMask, profile.width, profile.height, 1);
        for (int i = 0; i < frontMask.length; i++) {
            frontMask[i] = frontMask[i] || backMask[i];
        }
        closeSmallGaps(frontMask, profile.width, profile.height);
        closeSmallGaps(sideMask, profile.depth, profile.height);

        int[] frontDistance = distanceTransform(frontMask, profile.width, profile.height);
        int[] sideDistance = distanceTransform(sideMask, profile.depth, profile.height);
        boolean[] voxels = buildVisualHull(
                frontMask,
                sideMask,
                frontDistance,
                sideDistance,
                profile
        );
        smoothVoxels(voxels, profile.width, profile.height, profile.depth);

        MeshData mesh = buildVoxelMesh(voxels, profile.width, profile.height, profile.depth);
        Bitmap atlas = buildTextureAtlas(front, back, side);

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

    private static ViewSelection selectViews(List<Component> components, int imageWidth, int imageHeight) {
        List<Component> usable = new ArrayList<>();
        int minPixels = Math.max(220, imageWidth * imageHeight / 1800);
        for (Component component : components) {
            if (component.pixelCount >= minPixels
                    && component.bounds.height() >= imageHeight * COMPONENT_MIN_HEIGHT
                    && component.bounds.width() >= imageWidth * 0.035f) {
                usable.add(component);
            }
        }
        Collections.sort(usable, Comparator.comparingInt(value -> value.bounds.left));
        if (usable.size() < 2) {
            return new ViewSelection(null, null, null, usable.size());
        }

        Component front = Collections.max(usable, Comparator.comparingInt(value -> value.pixelCount));
        List<Component> candidates = new ArrayList<>();
        for (Component component : usable) {
            if (component != front) {
                candidates.add(component);
            }
        }
        if (candidates.isEmpty()) {
            return new ViewSelection(front, null, null, usable.size());
        }

        Component side = Collections.min(candidates, Comparator.comparingDouble(Component::aspectRatio));
        List<Component> wide = new ArrayList<>();
        for (Component candidate : candidates) {
            if (candidate != side && candidate.aspectRatio() >= side.aspectRatio() + 0.055f) {
                wide.add(candidate);
            }
        }
        Collections.sort(wide, Comparator.comparingInt(value -> value.bounds.left));

        Component back = null;
        if (wide.size() >= 2) {
            // Sur la planche type : grande face, petite face, dos, profil, trois-quarts.
            back = wide.get(1);
        } else if (!wide.isEmpty()) {
            back = wide.get(0);
        } else {
            for (Component candidate : candidates) {
                if (candidate != side) {
                    back = candidate;
                    break;
                }
            }
        }
        return new ViewSelection(front, back, side, usable.size());
    }

    private static ViewData createView(Bitmap source, boolean[] sourceMask, Component component) {
        Rect crop = addMargin(component.bounds, source.getWidth(), source.getHeight(), 0.035f);
        Bitmap bitmap = Bitmap.createBitmap(source, crop.left, crop.top, crop.width(), crop.height());
        boolean[] mask = new boolean[crop.width() * crop.height()];
        int sourceWidth = source.getWidth();
        for (int y = 0; y < crop.height(); y++) {
            int sourceOffset = (crop.top + y) * sourceWidth + crop.left;
            int targetOffset = y * crop.width();
            System.arraycopy(sourceMask, sourceOffset, mask, targetOffset, crop.width());
        }
        closeSmallGaps(mask, crop.width(), crop.height());
        return new ViewData(bitmap, mask, crop.width(), crop.height());
    }

    private static boolean[] normalizeMask(ViewData view, int targetWidth, int targetHeight) {
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
            int sourceY = Math.min(view.height - 1, (int) ((y + 0.5f) * view.height / drawHeight));
            int targetY = offsetY + y;
            for (int x = 0; x < drawWidth; x++) {
                int sourceX = Math.min(view.width - 1, (int) ((x + 0.5f) * view.width / drawWidth));
                if (view.mask[sourceY * view.width + sourceX]) {
                    output[targetY * targetWidth + offsetX + x] = true;
                }
            }
        }
        return output;
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
        int[] maxFrontDistance = rowMax(frontDistance, front, width, height);
        int[] maxSideDistance = rowMax(sideDistance, side, depth, height);

        int workers = Math.max(1, Math.min(profile.processors - 1, 8));
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
                            float frontEdge = 1.0f - Math.min(1.0f,
                                    frontDistance[frontIndex] / (float) maxFront);
                            for (int z = 0; z < depth; z++) {
                                int sideIndex = y * depth + z;
                                if (!side[sideIndex]) {
                                    continue;
                                }
                                float sideEdge = 1.0f - Math.min(1.0f,
                                        sideDistance[sideIndex] / (float) maxSide);
                                // Retire seulement les coins extrêmes du visual hull afin d'arrondir le volume.
                                if (frontEdge * frontEdge + sideEdge * sideEdge <= 1.46f) {
                                    voxels[(y * width + x) * depth + z] = true;
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

    private static int[] rowMax(int[] distance, boolean[] mask, int width, int height) {
        int[] result = new int[height];
        for (int y = 0; y < height; y++) {
            int max = 0;
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                if (mask[index]) {
                    max = Math.max(max, distance[index]);
                }
            }
            result[y] = max;
        }
        return result;
    }

    private static void smoothVoxels(boolean[] voxels, int width, int height, int depth) {
        boolean[] copy = Arrays.copyOf(voxels, voxels.length);
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                for (int z = 1; z < depth - 1; z++) {
                    int index = voxelIndex(x, y, z, width, depth);
                    int neighbours = 0;
                    for (int oy = -1; oy <= 1; oy++) {
                        for (int ox = -1; ox <= 1; ox++) {
                            for (int oz = -1; oz <= 1; oz++) {
                                if (ox == 0 && oy == 0 && oz == 0) {
                                    continue;
                                }
                                if (copy[voxelIndex(x + ox, y + oy, z + oz, width, depth)]) {
                                    neighbours++;
                                }
                            }
                        }
                    }
                    if (copy[index] && neighbours <= 3) {
                        voxels[index] = false;
                    } else if (!copy[index] && neighbours >= 22) {
                        voxels[index] = true;
                    }
                }
            }
        }
    }

    private static MeshData buildVoxelMesh(boolean[] voxels, int width, int height, int depth) {
        FloatBuilder positions = new FloatBuilder(180_000);
        FloatBuilder normals = new FloatBuilder(180_000);
        FloatBuilder texCoords = new FloatBuilder(120_000);
        IntBuilder indices = new IntBuilder(180_000);

        float halfWidth = width / (float) height;
        float halfDepth = depth / (float) height;
        for (int y = 0; y < height; y++) {
            float yTop = 1.0f - 2.0f * y / height;
            float yBottom = 1.0f - 2.0f * (y + 1) / height;
            float vTop = 1.0f - y / (float) height;
            float vBottom = 1.0f - (y + 1) / (float) height;
            for (int x = 0; x < width; x++) {
                float xLeft = -halfWidth + 2.0f * halfWidth * x / width;
                float xRight = -halfWidth + 2.0f * halfWidth * (x + 1) / width;
                float uLeft = x / (float) width;
                float uRight = (x + 1) / (float) width;
                for (int z = 0; z < depth; z++) {
                    if (!voxels[voxelIndex(x, y, z, width, depth)]) {
                        continue;
                    }
                    float zBack = -halfDepth + 2.0f * halfDepth * z / depth;
                    float zFront = -halfDepth + 2.0f * halfDepth * (z + 1) / depth;
                    float sideLeft = z / (float) depth;
                    float sideRight = (z + 1) / (float) depth;

                    if (!isVoxel(voxels, x, y, z + 1, width, height, depth)) {
                        addQuad(positions, normals, texCoords, indices,
                                xLeft, yBottom, zFront,
                                xRight, yBottom, zFront,
                                xRight, yTop, zFront,
                                xLeft, yTop, zFront,
                                0.0f, 0.0f, 1.0f,
                                atlasU(0, uLeft), atlasV(0, vBottom),
                                atlasU(0, uRight), atlasV(0, vBottom),
                                atlasU(0, uRight), atlasV(0, vTop),
                                atlasU(0, uLeft), atlasV(0, vTop));
                    }
                    if (!isVoxel(voxels, x, y, z - 1, width, height, depth)) {
                        addQuad(positions, normals, texCoords, indices,
                                xRight, yBottom, zBack,
                                xLeft, yBottom, zBack,
                                xLeft, yTop, zBack,
                                xRight, yTop, zBack,
                                0.0f, 0.0f, -1.0f,
                                atlasU(1, 1.0f - uRight), atlasV(0, vBottom),
                                atlasU(1, 1.0f - uLeft), atlasV(0, vBottom),
                                atlasU(1, 1.0f - uLeft), atlasV(0, vTop),
                                atlasU(1, 1.0f - uRight), atlasV(0, vTop));
                    }
                    if (!isVoxel(voxels, x + 1, y, z, width, height, depth)) {
                        addQuad(positions, normals, texCoords, indices,
                                xRight, yBottom, zFront,
                                xRight, yBottom, zBack,
                                xRight, yTop, zBack,
                                xRight, yTop, zFront,
                                1.0f, 0.0f, 0.0f,
                                atlasU(1, 1.0f - sideRight), atlasV(1, vBottom),
                                atlasU(1, 1.0f - sideLeft), atlasV(1, vBottom),
                                atlasU(1, 1.0f - sideLeft), atlasV(1, vTop),
                                atlasU(1, 1.0f - sideRight), atlasV(1, vTop));
                    }
                    if (!isVoxel(voxels, x - 1, y, z, width, height, depth)) {
                        addQuad(positions, normals, texCoords, indices,
                                xLeft, yBottom, zBack,
                                xLeft, yBottom, zFront,
                                xLeft, yTop, zFront,
                                xLeft, yTop, zBack,
                                -1.0f, 0.0f, 0.0f,
                                atlasU(0, sideLeft), atlasV(1, vBottom),
                                atlasU(0, sideRight), atlasV(1, vBottom),
                                atlasU(0, sideRight), atlasV(1, vTop),
                                atlasU(0, sideLeft), atlasV(1, vTop));
                    }
                    if (!isVoxel(voxels, x, y - 1, z, width, height, depth)) {
                        addQuad(positions, normals, texCoords, indices,
                                xLeft, yTop, zFront,
                                xRight, yTop, zFront,
                                xRight, yTop, zBack,
                                xLeft, yTop, zBack,
                                0.0f, 1.0f, 0.0f,
                                atlasU(0, uLeft), atlasV(0, vTop),
                                atlasU(0, uRight), atlasV(0, vTop),
                                atlasU(0, uRight), atlasV(0, vTop),
                                atlasU(0, uLeft), atlasV(0, vTop));
                    }
                    if (!isVoxel(voxels, x, y + 1, z, width, height, depth)) {
                        addQuad(positions, normals, texCoords, indices,
                                xLeft, yBottom, zBack,
                                xRight, yBottom, zBack,
                                xRight, yBottom, zFront,
                                xLeft, yBottom, zFront,
                                0.0f, -1.0f, 0.0f,
                                atlasU(0, uLeft), atlasV(0, vBottom),
                                atlasU(0, uRight), atlasV(0, vBottom),
                                atlasU(0, uRight), atlasV(0, vBottom),
                                atlasU(0, uLeft), atlasV(0, vBottom));
                    }
                }
            }
        }
        if (indices.size() == 0) {
            throw new IllegalArgumentException("La planche n'a produit aucun volume exploitable");
        }
        return new MeshData(
                positions.toArray(),
                normals.toArray(),
                texCoords.toArray(),
                indices.toArray()
        );
    }

    private static float atlasU(int column, float localU) {
        return column * 0.5f + clamp01(localU) * 0.5f;
    }

    private static float atlasV(int rowFromTop, float localV) {
        if (rowFromTop == 0) {
            return 0.5f + clamp01(localV) * 0.5f;
        }
        return clamp01(localV) * 0.5f;
    }

    private static void addQuad(
            FloatBuilder positions,
            FloatBuilder normals,
            FloatBuilder texCoords,
            IntBuilder indices,
            float x0, float y0, float z0,
            float x1, float y1, float z1,
            float x2, float y2, float z2,
            float x3, float y3, float z3,
            float nx, float ny, float nz,
            float u0, float v0,
            float u1, float v1,
            float u2, float v2,
            float u3, float v3
    ) {
        int base = positions.size() / 3;
        positions.add(x0, y0, z0);
        positions.add(x1, y1, z1);
        positions.add(x2, y2, z2);
        positions.add(x3, y3, z3);
        for (int i = 0; i < 4; i++) {
            normals.add(nx, ny, nz);
        }
        texCoords.add(u0, v0);
        texCoords.add(u1, v1);
        texCoords.add(u2, v2);
        texCoords.add(u3, v3);
        indices.add(base, base + 1, base + 2);
        indices.add(base, base + 2, base + 3);
    }

    private static Bitmap buildTextureAtlas(ViewData front, ViewData back, ViewData side) {
        Bitmap atlas = Bitmap.createBitmap(ATLAS_SIZE, ATLAS_SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(atlas);
        canvas.drawColor(Color.TRANSPARENT);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        int cell = ATLAS_SIZE / 2;

        drawView(canvas, paint, front, new RectF(0, 0, cell, cell), false);
        drawView(canvas, paint, back, new RectF(cell, 0, ATLAS_SIZE, cell), false);
        drawView(canvas, paint, side, new RectF(0, cell, cell, ATLAS_SIZE), false);
        drawView(canvas, paint, side, new RectF(cell, cell, ATLAS_SIZE, ATLAS_SIZE), true);
        return atlas;
    }

    private static void drawView(Canvas canvas, Paint paint, ViewData view, RectF cell, boolean mirror) {
        Bitmap isolated = view.transparentBitmap();
        float margin = 10.0f;
        float availableWidth = cell.width() - margin * 2.0f;
        float availableHeight = cell.height() - margin * 2.0f;
        float scale = Math.min(
                availableWidth / isolated.getWidth(),
                availableHeight / isolated.getHeight()
        );
        float drawWidth = isolated.getWidth() * scale;
        float drawHeight = isolated.getHeight() * scale;
        float left = cell.left + (cell.width() - drawWidth) * 0.5f;
        float top = cell.top + (cell.height() - drawHeight) * 0.5f;
        RectF destination = new RectF(left, top, left + drawWidth, top + drawHeight);

        if (mirror) {
            canvas.save();
            Matrix matrix = new Matrix();
            matrix.setScale(-1.0f, 1.0f, cell.centerX(), cell.centerY());
            canvas.concat(matrix);
            canvas.drawBitmap(isolated, null, destination, paint);
            canvas.restore();
        } else {
            canvas.drawBitmap(isolated, null, destination, paint);
        }
        isolated.recycle();
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

    private static int voxelIndex(int x, int y, int z, int width, int depth) {
        return (y * width + x) * depth + z;
    }

    private static int countTrue(boolean[] values) {
        int count = 0;
        for (boolean value : values) {
            if (value) {
                count++;
            }
        }
        return count;
    }

    private static boolean[] estimateForeground(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        long rSum = 0;
        long gSum = 0;
        long bSum = 0;
        int count = 0;
        int border = Math.max(3, Math.min(width, height) / 35);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (x < border || y < border || x >= width - border || y >= height - border) {
                    int color = pixels[y * width + x];
                    if (Color.alpha(color) > 20) {
                        rSum += Color.red(color);
                        gSum += Color.green(color);
                        bSum += Color.blue(color);
                        count++;
                    }
                }
            }
        }
        int bgR = count == 0 ? 245 : (int) (rSum / count);
        int bgG = count == 0 ? 245 : (int) (gSum / count);
        int bgB = count == 0 ? 245 : (int) (bSum / count);
        int bgLum = (bgR * 299 + bgG * 587 + bgB * 114) / 1000;

        boolean[] mask = new boolean[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            int color = pixels[i];
            if (Color.alpha(color) < 24) {
                continue;
            }
            int r = Color.red(color);
            int g = Color.green(color);
            int b = Color.blue(color);
            int dr = r - bgR;
            int dg = g - bgG;
            int db = b - bgB;
            int distanceSquared = dr * dr + dg * dg + db * db;
            int lum = (r * 299 + g * 587 + b * 114) / 1000;
            int max = Math.max(r, Math.max(g, b));
            int min = Math.min(r, Math.min(g, b));
            int saturation = max - min;
            mask[i] = distanceSquared > 31 * 31
                    || Math.abs(lum - bgLum) > 22
                    || saturation > 31;
        }
        dilate(mask, width, height, 1);
        erode(mask, width, height, 1);
        return mask;
    }

    private static List<Component> findComponents(boolean[] mask, int width, int height) {
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
                for (int oy = -1; oy <= 1; oy++) {
                    for (int ox = -1; ox <= 1; ox++) {
                        if (ox == 0 && oy == 0) {
                            continue;
                        }
                        int nx = x + ox;
                        int ny = y + oy;
                        if (nx < 0 || ny < 0 || nx >= width || ny >= height) {
                            continue;
                        }
                        int next = ny * width + nx;
                        if (mask[next] && !visited[next]) {
                            visited[next] = true;
                            queue.addLast(next);
                        }
                    }
                }
            }
            if (maxX >= minX && maxY >= minY) {
                result.add(new Component(count, new Rect(minX, minY, maxX + 1, maxY + 1)));
            }
        }
        return result;
    }

    private static Rect addMargin(Rect source, int width, int height, float fraction) {
        int marginX = Math.max(3, Math.round(source.width() * fraction));
        int marginY = Math.max(3, Math.round(source.height() * fraction));
        return new Rect(
                Math.max(0, source.left - marginX),
                Math.max(0, source.top - marginY),
                Math.min(width, source.right + marginX),
                Math.min(height, source.bottom + marginY)
        );
    }

    private static void closeSmallGaps(boolean[] mask, int width, int height) {
        dilate(mask, width, height, 1);
        erode(mask, width, height, 1);
    }

    private static void dilate(boolean[] mask, int width, int height, int iterations) {
        for (int iteration = 0; iteration < iterations; iteration++) {
            boolean[] copy = Arrays.copyOf(mask, mask.length);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (copy[y * width + x]) {
                        continue;
                    }
                    boolean neighbour = false;
                    for (int oy = -1; oy <= 1 && !neighbour; oy++) {
                        for (int ox = -1; ox <= 1; ox++) {
                            int nx = x + ox;
                            int ny = y + oy;
                            if (nx >= 0 && ny >= 0 && nx < width && ny < height
                                    && copy[ny * width + nx]) {
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

    private static void erode(boolean[] mask, int width, int height, int iterations) {
        for (int iteration = 0; iteration < iterations; iteration++) {
            boolean[] copy = Arrays.copyOf(mask, mask.length);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (!copy[y * width + x]) {
                        continue;
                    }
                    boolean touchesBackground = false;
                    for (int oy = -1; oy <= 1 && !touchesBackground; oy++) {
                        for (int ox = -1; ox <= 1; ox++) {
                            int nx = x + ox;
                            int ny = y + oy;
                            if (nx < 0 || ny < 0 || nx >= width || ny >= height
                                    || !copy[ny * width + nx]) {
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

    private static int[] distanceTransform(boolean[] mask, int width, int height) {
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
                if (x > 0 && y > 0) best = Math.min(best, distance[index - width - 1] + 4);
                if (x + 1 < width && y > 0) best = Math.min(best, distance[index - width + 1] + 4);
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
                if (x + 1 < width && y + 1 < height) best = Math.min(best, distance[index + width + 1] + 4);
                if (x > 0 && y + 1 < height) best = Math.min(best, distance[index + width - 1] + 4);
                distance[index] = best;
            }
        }
        return distance;
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
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

        public MeshData getMesh() {
            return mesh;
        }

        public Bitmap getTexture() {
            return texture;
        }

        public int getDetectedViewCount() {
            return detectedViewCount;
        }

        public String getQualityLabel() {
            return qualityLabel;
        }

        public int getProcessorCount() {
            return processorCount;
        }

        public int getVoxelCount() {
            return voxelCount;
        }
    }

    private static final class PerformanceProfile {
        final int width;
        final int height;
        final int depth;
        final int processors;
        final String label;

        PerformanceProfile(int width, int height, int depth, int processors, String label) {
            this.width = width;
            this.height = height;
            this.depth = depth;
            this.processors = processors;
            this.label = label;
        }

        static PerformanceProfile detect() {
            int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
            long maxMemoryMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
            if (processors >= 10 && maxMemoryMb >= 700) {
                return new PerformanceProfile(96, 192, 72, processors, "Ultra");
            }
            if (processors >= 8 && maxMemoryMb >= 420) {
                return new PerformanceProfile(80, 160, 60, processors, "Élevée");
            }
            if (processors >= 6 && maxMemoryMb >= 280) {
                return new PerformanceProfile(64, 128, 48, processors, "Équilibrée");
            }
            return new PerformanceProfile(48, 96, 36, processors, "Compatible");
        }
    }

    private static final class ViewSelection {
        final Component front;
        final Component back;
        final Component side;
        final int detectedViewCount;

        ViewSelection(Component front, Component back, Component side, int detectedViewCount) {
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
            if (!bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
    }

    private static final class Component {
        final int pixelCount;
        final Rect bounds;

        Component(int pixelCount, Rect bounds) {
            this.pixelCount = pixelCount;
            this.bounds = bounds;
        }

        double aspectRatio() {
            return bounds.width() / (double) Math.max(1, bounds.height());
        }
    }

    private static final class FloatBuilder {
        private float[] data;
        private int size;

        FloatBuilder(int capacity) {
            data = new float[Math.max(16, capacity)];
        }

        void add(float a, float b) {
            ensure(2);
            data[size++] = a;
            data[size++] = b;
        }

        void add(float a, float b, float c) {
            ensure(3);
            data[size++] = a;
            data[size++] = b;
            data[size++] = c;
        }

        int size() {
            return size;
        }

        float[] toArray() {
            return Arrays.copyOf(data, size);
        }

        private void ensure(int extra) {
            if (size + extra > data.length) {
                data = Arrays.copyOf(data, Math.max(size + extra, data.length * 2));
            }
        }
    }

    private static final class IntBuilder {
        private int[] data;
        private int size;

        IntBuilder(int capacity) {
            data = new int[Math.max(16, capacity)];
        }

        void add(int a, int b, int c) {
            ensure(3);
            data[size++] = a;
            data[size++] = b;
            data[size++] = c;
        }

        int size() {
            return size;
        }

        int[] toArray() {
            return Arrays.copyOf(data, size);
        }

        private void ensure(int extra) {
            if (size + extra > data.length) {
                data = Arrays.copyOf(data, Math.max(size + extra, data.length * 2));
            }
        }
    }
}
