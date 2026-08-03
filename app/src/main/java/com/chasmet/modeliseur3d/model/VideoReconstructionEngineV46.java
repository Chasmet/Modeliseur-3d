package com.chasmet.modeliseur3d.model;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;

import java.util.Arrays;
import java.util.List;

/**
 * Reconstruction vidéo V4.6 tolérante aux trames et segmentations défaillantes.
 */
public final class VideoReconstructionEngineV46 implements AutoCloseable {
    private static final int VIEW_COUNT = 8;
    private static final int[] CARDINAL_INDICES = {0, 4, 2, 6};
    private static final int ATLAS_HEIGHT = 1024;
    private static final int ALPHA_THRESHOLD = 24;
    private static final int HD_TRIANGLE_LIMIT = 80_000;

    private final Context context;

    public VideoReconstructionEngineV46(Context context) {
        this.context = context.getApplicationContext();
    }

    public Result generate(
            List<Bitmap> frames,
            int decodedFrameCount,
            ProgressListener listener
    ) throws Exception {
        validateFrames(frames);
        long started = SystemClock.elapsedRealtime();
        Profile profile = Profile.detect();
        boolean[][] masks = new boolean[VIEW_COUNT][];
        Bitmap[] textures = new Bitmap[VIEW_COUNT];
        int segmentationFallbacks = 0;
        String segmentationBackend;

        try (AnimeSegmentationEngine segmentation =
                     new AnimeSegmentationEngine(context)) {
            segmentationBackend = segmentation.getBackend();
            for (int index = 0; index < VIEW_COUNT; index++) {
                notifyProgress(listener, Stage.SEGMENTING, index + 1, VIEW_COUNT);
                PreparedView prepared = null;
                try {
                    prepared = prepareNeuralView(
                            frames.get(index),
                            segmentation,
                            profile
                    );
                } catch (Exception | OutOfMemoryError neuralFailure) {
                    segmentationFallbacks++;
                    prepared = prepareFallbackView(frames.get(index), profile);
                }

                if (prepared == null && index > 0) {
                    segmentationFallbacks++;
                    masks[index] = Arrays.copyOf(
                            masks[index - 1],
                            masks[index - 1].length
                    );
                    textures[index] = textures[index - 1].copy(
                            Bitmap.Config.ARGB_8888,
                            false
                    );
                } else if (prepared == null) {
                    recycleAll(textures);
                    throw new IllegalArgumentException(
                            "La première vue vidéo ne contient aucun sujet exploitable"
                    );
                } else {
                    masks[index] = prepared.mask;
                    textures[index] = prepared.texture;
                }
            }
        }

        repairOutlierViews(masks, textures, profile.width, profile.height);
        int phase = ViewPhaseEstimator.estimate(
                masks,
                profile.width,
                profile.height
        );
        boolean[][] orderedMasks = ViewPhaseEstimator.rotate(masks, phase);
        Bitmap[] cardinalTextures = cardinalTextures(textures, phase);

        releaseMemory();
        notifyProgress(listener, Stage.BUILDING_HULL, 0, 1);
        int support = Math.max(4, Math.min(7, decodedFrameCount - 1));
        boolean[] volume = MultiViewHullProjector.build(
                orderedMasks,
                profile.width,
                profile.height,
                profile.depth,
                support
        );
        int occupied = MultiViewHullProjector.countOccupied(volume);
        if (occupied < Math.max(320, volume.length / 2800)) {
            support = Math.max(3, support - 1);
            volume = MultiViewHullProjector.build(
                    orderedMasks,
                    profile.width,
                    profile.height,
                    profile.depth,
                    support
            );
            occupied = MultiViewHullProjector.countOccupied(volume);
        }
        if (occupied < 240) {
            recycleAll(cardinalTextures);
            recycleAll(textures);
            throw new IllegalArgumentException(
                    "Les silhouettes de la vidéo ne forment pas encore un volume cohérent. Le sujet doit rester entier et tourner sur lui-même."
            );
        }
        bridgeNarrowVolumeGaps(
                volume,
                profile.width,
                profile.height,
                profile.depth
        );

        SmoothHullMesher.AtlasLayout layout =
                SmoothHullMesher.AtlasLayout.create(
                        profile.width,
                        profile.height,
                        profile.depth,
                        ATLAS_HEIGHT
                );
        Bitmap atlas;
        try {
            atlas = buildAtlas(cardinalTextures, layout);
        } finally {
            recycleAll(cardinalTextures);
            recycleAll(textures);
        }

        notifyProgress(listener, Stage.MESHING, 0, 1);
        MeshData mesh;
        try {
            mesh = SmoothHullMesher.build(
                    volume,
                    profile.width,
                    profile.height,
                    profile.depth,
                    layout,
                    profile.processors
            );
            try {
                mesh = MeshSurfaceOptimizer.optimize(mesh, 1);
            } catch (RuntimeException ignored) {
                // La surface brute reste fermée et exploitable.
            }
        } catch (Exception | OutOfMemoryError error) {
            atlas.recycle();
            throw error;
        }

        releaseMemory();
        long neuralStarted = SystemClock.elapsedRealtime();
        notifyProgress(listener, Stage.DEPTH, 0, 3);
        String depthBackend;
        MeshData refined = mesh;
        int depthPasses = 0;
        try (NeuralMeshRefiner.Views views = NeuralMeshRefiner.cropViews(atlas);
             NeuralDepthEngine depth = new NeuralDepthEngine(context)) {
            NeuralDepthEngine.DepthMap front = depth.estimate(views.front);
            depthPasses++;
            notifyProgress(listener, Stage.DEPTH, depthPasses, 3);
            NeuralDepthEngine.DepthMap back = depth.estimate(views.back);
            depthPasses++;
            notifyProgress(listener, Stage.DEPTH, depthPasses, 3);
            NeuralDepthEngine.DepthMap side = depth.estimate(views.side);
            depthPasses++;
            notifyProgress(listener, Stage.DEPTH, depthPasses, 3);
            refined = NeuralMeshRefiner.refine(mesh, front, back, side);
            depthBackend = depth.getBackend() + " • relief cardinal borné";
        } catch (Exception | OutOfMemoryError error) {
            depthBackend = "coque huit vues conservée • " + shortError(error);
            releaseMemory();
        }

        if (refined.getTriangleCount() > HD_TRIANGLE_LIMIT) {
            try {
                refined = MobileMeshOptimizer.simplify(
                        refined,
                        HD_TRIANGLE_LIMIT
                );
            } catch (RuntimeException ignored) {
                // L'exporteur possède encore sa propre simplification mobile.
            }
        }

        String backend = segmentationBackend
                + (segmentationFallbacks > 0
                ? " • " + segmentationFallbacks + " vues réparées"
                : " • 8 vues détourées")
                + " • " + depthBackend;
        String quality = profile.label
                + " • phase cardinale " + phase
                + " • support " + support + "/8"
                + " • " + decodedFrameCount + " vues réellement décodées";

        return new Result(
                refined,
                atlas,
                occupied,
                quality,
                profile.processors,
                backend,
                SystemClock.elapsedRealtime() - neuralStarted,
                SystemClock.elapsedRealtime() - started,
                decodedFrameCount,
                segmentationFallbacks
        );
    }

    private static PreparedView prepareNeuralView(
            Bitmap frame,
            AnimeSegmentationEngine segmentation,
            Profile profile
    ) throws Exception {
        Bitmap isolated = null;
        try {
            AnimeSegmentationEngine.Mask neuralMask = segmentation.segment(frame);
            isolated = NeuralSheetIsolator.isolate(frame, neuralMask);
            Rect bounds = findForegroundBounds(isolated);
            boolean[] mask = normalizeMask(
                    isolated,
                    bounds,
                    profile.width,
                    profile.height
            );
            closeMask(mask, profile.width, profile.height);
            dilateMask(mask, profile.width, profile.height);
            Bitmap texture = cropWithMargin(isolated, bounds, 0.055f);
            return new PreparedView(mask, texture);
        } finally {
            if (isolated != null && !isolated.isRecycled()) {
                isolated.recycle();
            }
        }
    }

    private static PreparedView prepareFallbackView(
            Bitmap frame,
            Profile profile
    ) {
        Bitmap isolated = contrastIsolation(frame);
        try {
            Rect bounds = findForegroundBounds(isolated);
            boolean[] mask = normalizeMask(
                    isolated,
                    bounds,
                    profile.width,
                    profile.height
            );
            closeMask(mask, profile.width, profile.height);
            dilateMask(mask, profile.width, profile.height);
            return new PreparedView(
                    mask,
                    cropWithMargin(isolated, bounds, 0.055f)
            );
        } catch (RuntimeException ignored) {
            return null;
        } finally {
            if (!isolated.isRecycled()) {
                isolated.recycle();
            }
        }
    }

    private static Bitmap contrastIsolation(Bitmap source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int[] pixels = new int[width * height];
        source.getPixels(pixels, 0, width, 0, 0, width, height);
        long red = 0L;
        long green = 0L;
        long blue = 0L;
        int samples = 0;
        int step = Math.max(1, Math.min(width, height) / 80);
        for (int x = 0; x < width; x += step) {
            int top = pixels[x];
            int bottom = pixels[(height - 1) * width + x];
            red += Color.red(top) + Color.red(bottom);
            green += Color.green(top) + Color.green(bottom);
            blue += Color.blue(top) + Color.blue(bottom);
            samples += 2;
        }
        for (int y = step; y < height - step; y += step) {
            int left = pixels[y * width];
            int right = pixels[y * width + width - 1];
            red += Color.red(left) + Color.red(right);
            green += Color.green(left) + Color.green(right);
            blue += Color.blue(left) + Color.blue(right);
            samples += 2;
        }
        float backgroundR = red / (float) Math.max(1, samples);
        float backgroundG = green / (float) Math.max(1, samples);
        float backgroundB = blue / (float) Math.max(1, samples);

        for (int index = 0; index < pixels.length; index++) {
            int color = pixels[index];
            float dr = Color.red(color) - backgroundR;
            float dg = Color.green(color) - backgroundG;
            float db = Color.blue(color) - backgroundB;
            float distance = (float) Math.sqrt(dr * dr + dg * dg + db * db);
            int x = index % width;
            int y = index / width;
            float centerX = 1.0f - Math.abs(
                    x - (width - 1) * 0.5f
            ) / Math.max(1.0f, width * 0.5f);
            float centerY = 1.0f - Math.abs(
                    y - (height - 1) * 0.5f
            ) / Math.max(1.0f, height * 0.5f);
            float threshold = 26.0f - 6.0f * Math.max(0.0f, centerX * centerY);
            pixels[index] = distance >= threshold
                    ? 0xFF000000 | (color & 0x00FFFFFF)
                    : Color.TRANSPARENT;
        }
        Bitmap output = Bitmap.createBitmap(
                width,
                height,
                Bitmap.Config.ARGB_8888
        );
        output.setPixels(pixels, 0, width, 0, 0, width, height);
        return output;
    }

    private static void repairOutlierViews(
            boolean[][] masks,
            Bitmap[] textures,
            int width,
            int height
    ) {
        int[] areas = new int[VIEW_COUNT];
        int[] sorted = new int[VIEW_COUNT];
        for (int index = 0; index < VIEW_COUNT; index++) {
            areas[index] = countTrue(masks[index]);
            sorted[index] = areas[index];
        }
        Arrays.sort(sorted);
        float median = (sorted[3] + sorted[4]) * 0.5f;
        for (int index = 0; index < VIEW_COUNT; index++) {
            if (areas[index] >= median * 0.42f
                    && areas[index] <= median * 2.15f) {
                continue;
            }
            int replacement = nearestHealthyIndex(areas, median, index);
            if (replacement < 0) {
                continue;
            }
            masks[index] = Arrays.copyOf(
                    masks[replacement],
                    width * height
            );
            if (textures[index] != null && !textures[index].isRecycled()) {
                textures[index].recycle();
            }
            textures[index] = textures[replacement].copy(
                    Bitmap.Config.ARGB_8888,
                    false
            );
        }
    }

    private static int nearestHealthyIndex(
            int[] areas,
            float median,
            int target
    ) {
        for (int distance = 1; distance < VIEW_COUNT; distance++) {
            int before = target - distance;
            if (before >= 0
                    && areas[before] >= median * 0.42f
                    && areas[before] <= median * 2.15f) {
                return before;
            }
            int after = target + distance;
            if (after < VIEW_COUNT
                    && areas[after] >= median * 0.42f
                    && areas[after] <= median * 2.15f) {
                return after;
            }
        }
        return -1;
    }

    private static Bitmap[] cardinalTextures(Bitmap[] views, int phase) {
        Bitmap[] output = new Bitmap[CARDINAL_INDICES.length];
        for (int slot = 0; slot < CARDINAL_INDICES.length; slot++) {
            int source = ViewPhaseEstimator.sourceIndex(
                    CARDINAL_INDICES[slot],
                    phase
            );
            output[slot] = views[source].copy(
                    Bitmap.Config.ARGB_8888,
                    false
            );
        }
        return output;
    }

    private static void validateFrames(List<Bitmap> frames) {
        if (frames == null || frames.size() != VIEW_COUNT) {
            throw new IllegalArgumentException("Huit vues vidéo sont requises");
        }
        for (Bitmap frame : frames) {
            if (frame == null || frame.isRecycled()) {
                throw new IllegalArgumentException("Une trame vidéo est invalide");
            }
        }
    }

    private static Rect findForegroundBounds(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        int left = width;
        int top = height;
        int right = -1;
        int bottom = -1;
        int foreground = 0;
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                if (Color.alpha(pixels[row + x]) > ALPHA_THRESHOLD) {
                    foreground++;
                    left = Math.min(left, x);
                    top = Math.min(top, y);
                    right = Math.max(right, x);
                    bottom = Math.max(bottom, y);
                }
            }
        }
        int minimum = Math.max(48, width * height / 2200);
        if (right < left || bottom < top || foreground < minimum) {
            throw new IllegalArgumentException(
                    "Sujet trop petit ou détourage vidéo insuffisant"
            );
        }
        return new Rect(left, top, right + 1, bottom + 1);
    }

    private static boolean[] normalizeMask(
            Bitmap isolated,
            Rect bounds,
            int targetWidth,
            int targetHeight
    ) {
        boolean[] output = new boolean[targetWidth * targetHeight];
        float availableWidth = targetWidth * 0.90f;
        float availableHeight = targetHeight * 0.92f;
        float scale = Math.min(
                availableWidth / Math.max(1.0f, bounds.width()),
                availableHeight / Math.max(1.0f, bounds.height())
        );
        int drawWidth = Math.max(1, Math.round(bounds.width() * scale));
        int drawHeight = Math.max(1, Math.round(bounds.height() * scale));
        int offsetX = (targetWidth - drawWidth) / 2;
        int offsetY = (targetHeight - drawHeight) / 2;
        int[] pixels = new int[isolated.getWidth() * isolated.getHeight()];
        isolated.getPixels(
                pixels,
                0,
                isolated.getWidth(),
                0,
                0,
                isolated.getWidth(),
                isolated.getHeight()
        );
        for (int y = 0; y < drawHeight; y++) {
            int sourceY = Math.min(
                    bounds.bottom - 1,
                    bounds.top + (int) ((y + 0.5f) * bounds.height() / drawHeight)
            );
            int targetY = offsetY + y;
            for (int x = 0; x < drawWidth; x++) {
                int sourceX = Math.min(
                        bounds.right - 1,
                        bounds.left + (int) ((x + 0.5f) * bounds.width() / drawWidth)
                );
                if (Color.alpha(
                        pixels[sourceY * isolated.getWidth() + sourceX]
                ) > ALPHA_THRESHOLD) {
                    output[targetY * targetWidth + offsetX + x] = true;
                }
            }
        }
        return output;
    }

    private static Bitmap cropWithMargin(
            Bitmap source,
            Rect bounds,
            float marginRatio
    ) {
        int marginX = Math.round(bounds.width() * marginRatio);
        int marginY = Math.round(bounds.height() * marginRatio);
        int left = Math.max(0, bounds.left - marginX);
        int top = Math.max(0, bounds.top - marginY);
        int right = Math.min(source.getWidth(), bounds.right + marginX);
        int bottom = Math.min(source.getHeight(), bounds.bottom + marginY);
        return Bitmap.createBitmap(
                source,
                left,
                top,
                Math.max(1, right - left),
                Math.max(1, bottom - top)
        );
    }

    private static Bitmap buildAtlas(
            Bitmap[] cardinal,
            SmoothHullMesher.AtlasLayout layout
    ) {
        for (Bitmap bitmap : cardinal) {
            if (bitmap == null || bitmap.isRecycled()) {
                throw new IllegalArgumentException(
                        "Une vue cardinale vidéo est absente"
                );
            }
        }
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
        Bitmap front = normalizedTexture(
                cardinal[0], layout.frontWidth, layout.atlasHeight
        );
        Bitmap back = normalizedTexture(
                cardinal[1], layout.frontWidth, layout.atlasHeight
        );
        Bitmap right = normalizedTexture(
                cardinal[2], layout.sideWidth, layout.atlasHeight
        );
        Bitmap left = normalizedTexture(
                cardinal[3], layout.sideWidth, layout.atlasHeight
        );
        try {
            drawCell(canvas, paint, front,
                    layout.frontStart, layout.frontWidth, layout.atlasHeight);
            drawCell(canvas, paint, back,
                    layout.backStart, layout.frontWidth, layout.atlasHeight);
            drawCell(canvas, paint, right,
                    layout.rightStart, layout.sideWidth, layout.atlasHeight);
            drawCell(canvas, paint, left,
                    layout.leftStart, layout.sideWidth, layout.atlasHeight);
        } finally {
            front.recycle();
            back.recycle();
            right.recycle();
            left.recycle();
        }
        return atlas;
    }

    private static Bitmap normalizedTexture(
            Bitmap source,
            int targetWidth,
            int targetHeight
    ) {
        Bitmap output = Bitmap.createBitmap(
                targetWidth,
                targetHeight,
                Bitmap.Config.ARGB_8888
        );
        Canvas canvas = new Canvas(output);
        canvas.drawColor(Color.TRANSPARENT);
        float scale = Math.min(
                targetWidth * 0.94f / source.getWidth(),
                targetHeight * 0.94f / source.getHeight()
        );
        float width = source.getWidth() * scale;
        float height = source.getHeight() * scale;
        Paint paint = new Paint(
                Paint.ANTI_ALIAS_FLAG
                        | Paint.FILTER_BITMAP_FLAG
                        | Paint.DITHER_FLAG
        );
        canvas.drawBitmap(
                source,
                null,
                new RectF(
                        (targetWidth - width) * 0.5f,
                        (targetHeight - height) * 0.5f,
                        (targetWidth + width) * 0.5f,
                        (targetHeight + height) * 0.5f
                ),
                paint
        );
        bleedTexture(output, 10);
        return output;
    }

    private static void drawCell(
            Canvas canvas,
            Paint paint,
            Bitmap texture,
            int start,
            int width,
            int height
    ) {
        canvas.drawBitmap(
                texture,
                null,
                new RectF(start, 0, start + width, height),
                paint
        );
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
        for (int index = 0; index < pixels.length; index++) {
            if (Color.alpha(pixels[index]) > ALPHA_THRESHOLD) {
                pixels[index] = 0xFF000000 | (pixels[index] & 0x00FFFFFF);
                distance[index] = 0;
                queue[tail++] = index;
            }
        }
        if (tail == 0) {
            Arrays.fill(pixels, Color.rgb(96, 96, 100));
        } else {
            while (head < tail) {
                int current = queue[head++];
                if (distance[current] >= maximumDistance) {
                    continue;
                }
                int x = current % width;
                int y = current / width;
                tail = propagate(pixels, distance, queue, tail,
                        current, x - 1, y, width, height);
                tail = propagate(pixels, distance, queue, tail,
                        current, x + 1, y, width, height);
                tail = propagate(pixels, distance, queue, tail,
                        current, x, y - 1, width, height);
                tail = propagate(pixels, distance, queue, tail,
                        current, x, y + 1, width, height);
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
    }

    private static int propagate(
            int[] pixels,
            int[] distance,
            int[] queue,
            int tail,
            int source,
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
            distance[target] = distance[source] + 1;
            pixels[target] = pixels[source];
            queue[tail++] = target;
        }
        return tail;
    }

    private static void closeMask(boolean[] mask, int width, int height) {
        boolean[] dilated = Arrays.copyOf(mask, mask.length);
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int index = y * width + x;
                if (mask[index]) {
                    continue;
                }
                int neighbors = 0;
                for (int oy = -1; oy <= 1; oy++) {
                    for (int ox = -1; ox <= 1; ox++) {
                        if (mask[(y + oy) * width + x + ox]) {
                            neighbors++;
                        }
                    }
                }
                if (neighbors >= 3) {
                    dilated[index] = true;
                }
            }
        }
        System.arraycopy(dilated, 0, mask, 0, mask.length);
    }

    private static void dilateMask(boolean[] mask, int width, int height) {
        boolean[] source = Arrays.copyOf(mask, mask.length);
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int index = y * width + x;
                if (source[index]) {
                    continue;
                }
                if (source[index - 1]
                        || source[index + 1]
                        || source[index - width]
                        || source[index + width]) {
                    mask[index] = true;
                }
            }
        }
    }

    private static void bridgeNarrowVolumeGaps(
            boolean[] volume,
            int width,
            int height,
            int depth
    ) {
        boolean[] source = Arrays.copyOf(volume, volume.length);
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                for (int z = 1; z < depth - 1; z++) {
                    int index = (y * width + x) * depth + z;
                    if (source[index]) {
                        continue;
                    }
                    if ((source[index - depth] && source[index + depth])
                            || (source[index - width * depth]
                            && source[index + width * depth])
                            || (source[index - 1] && source[index + 1])) {
                        volume[index] = true;
                    }
                }
            }
        }
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

    private static void recycleAll(Bitmap[] bitmaps) {
        for (Bitmap bitmap : bitmaps) {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
    }

    private static void notifyProgress(
            ProgressListener listener,
            Stage stage,
            int current,
            int total
    ) {
        if (listener != null) {
            listener.onProgress(stage, current, total);
        }
    }

    private static void releaseMemory() {
        Runtime.getRuntime().gc();
        System.runFinalization();
    }

    private static String shortError(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return error.getClass().getSimpleName();
        }
        message = message.trim();
        return message.length() > 100
                ? message.substring(0, 97) + "…"
                : message;
    }

    @Override
    public void close() {
        // Les sessions neuronales sont fermées à chaque génération.
    }

    public enum Stage {
        SEGMENTING,
        BUILDING_HULL,
        MESHING,
        DEPTH
    }

    public interface ProgressListener {
        void onProgress(Stage stage, int current, int total);
    }

    public static final class Result {
        private final MeshData mesh;
        private final Bitmap texture;
        private final int occupiedVoxels;
        private final String qualityLabel;
        private final int processorCount;
        private final String backend;
        private final long neuralDurationMs;
        private final long totalDurationMs;
        private final int decodedFrameCount;
        private final int repairedViewCount;

        Result(
                MeshData mesh,
                Bitmap texture,
                int occupiedVoxels,
                String qualityLabel,
                int processorCount,
                String backend,
                long neuralDurationMs,
                long totalDurationMs,
                int decodedFrameCount,
                int repairedViewCount
        ) {
            this.mesh = mesh;
            this.texture = texture;
            this.occupiedVoxels = occupiedVoxels;
            this.qualityLabel = qualityLabel;
            this.processorCount = processorCount;
            this.backend = backend;
            this.neuralDurationMs = neuralDurationMs;
            this.totalDurationMs = totalDurationMs;
            this.decodedFrameCount = decodedFrameCount;
            this.repairedViewCount = repairedViewCount;
        }

        public MeshData getMesh() {
            return mesh;
        }

        public Bitmap getTexture() {
            return texture;
        }

        public int getOccupiedVoxels() {
            return occupiedVoxels;
        }

        public String getQualityLabel() {
            return qualityLabel;
        }

        public int getProcessorCount() {
            return processorCount;
        }

        public String getBackend() {
            return backend;
        }

        public long getNeuralDurationMs() {
            return neuralDurationMs;
        }

        public long getTotalDurationMs() {
            return totalDurationMs;
        }

        public int getDecodedFrameCount() {
            return decodedFrameCount;
        }

        public int getRepairedViewCount() {
            return repairedViewCount;
        }
    }

    private static final class PreparedView {
        final boolean[] mask;
        final Bitmap texture;

        PreparedView(boolean[] mask, Bitmap texture) {
            this.mask = mask;
            this.texture = texture;
        }
    }

    private static final class Profile {
        final int width;
        final int height;
        final int depth;
        final int processors;
        final String label;

        Profile(
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

        static Profile detect() {
            int processors = Math.max(
                    1,
                    Runtime.getRuntime().availableProcessors()
            );
            long memoryMb = Runtime.getRuntime().maxMemory()
                    / (1024L * 1024L);
            if (memoryMb >= 650L && processors >= 8) {
                return new Profile(
                        88,
                        176,
                        88,
                        processors,
                        "Vidéo 8 vues équilibrée HD"
                );
            }
            if (memoryMb >= 400L && processors >= 6) {
                return new Profile(
                        76,
                        152,
                        76,
                        processors,
                        "Vidéo 8 vues équilibrée"
                );
            }
            return new Profile(
                    64,
                    128,
                    64,
                    processors,
                    "Vidéo 8 vues compatible"
            );
        }
    }
}
