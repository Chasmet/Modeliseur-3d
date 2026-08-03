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
 * Reconstruction dédiée aux rotations vidéo en huit vues.
 *
 * Contrairement à la V4.4, les trames ne sont plus simplement collées dans une
 * planche puis redétectées. Leur ordre angulaire connu est utilisé pour projeter
 * huit silhouettes dans un même volume 3D.
 */
public final class VideoReconstructionEngine implements AutoCloseable {
    private static final int REQUIRED_VIEW_COUNT = 8;
    private static final int ATLAS_HEIGHT = 1024;
    private static final int[] CARDINAL_INDICES = {0, 4, 2, 6};
    private static final float FOREGROUND_ALPHA = 24.0f;

    private final Context context;

    public VideoReconstructionEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    public Result generate(
            List<Bitmap> frames,
            ProgressListener listener
    ) throws Exception {
        if (frames == null || frames.size() != REQUIRED_VIEW_COUNT) {
            throw new IllegalArgumentException("Huit vues vidéo sont requises");
        }
        for (Bitmap frame : frames) {
            if (frame == null || frame.isRecycled()) {
                throw new IllegalArgumentException("Une trame vidéo est invalide");
            }
        }

        long started = SystemClock.elapsedRealtime();
        Profile profile = Profile.detect();
        boolean[][] masks = new boolean[REQUIRED_VIEW_COUNT][];
        Bitmap[] cardinalTextures = new Bitmap[4];
        String segmentationBackend;

        try (AnimeSegmentationEngine segmentation =
                     new AnimeSegmentationEngine(context)) {
            segmentationBackend = segmentation.getBackend();
            for (int index = 0; index < frames.size(); index++) {
                notifyProgress(
                        listener,
                        Stage.SEGMENTING,
                        index + 1,
                        frames.size()
                );
                Bitmap isolated = null;
                try {
                    AnimeSegmentationEngine.Mask neuralMask =
                            segmentation.segment(frames.get(index));
                    isolated = NeuralSheetIsolator.isolate(
                            frames.get(index),
                            neuralMask
                    );
                    Rect bounds = findForegroundBounds(isolated);
                    masks[index] = normalizeMask(
                            isolated,
                            bounds,
                            profile.width,
                            profile.height
                    );
                    closeMask(masks[index], profile.width, profile.height);
                    dilateMask(masks[index], profile.width, profile.height);

                    int cardinalSlot = cardinalSlot(index);
                    if (cardinalSlot >= 0) {
                        cardinalTextures[cardinalSlot] = cropWithMargin(
                                isolated,
                                bounds,
                                0.055f
                        );
                    }
                } finally {
                    if (isolated != null && !isolated.isRecycled()) {
                        isolated.recycle();
                    }
                }
            }
        }

        releaseMemory();
        notifyProgress(listener, Stage.BUILDING_HULL, 0, 1);
        boolean[] volume = MultiViewHullProjector.build(
                masks,
                profile.width,
                profile.height,
                profile.depth,
                7
        );
        int occupied = MultiViewHullProjector.countOccupied(volume);
        if (occupied < Math.max(400, volume.length / 2500)) {
            volume = MultiViewHullProjector.build(
                    masks,
                    profile.width,
                    profile.height,
                    profile.depth,
                    6
            );
            occupied = MultiViewHullProjector.countOccupied(volume);
        }
        if (occupied < 300) {
            recycleAll(cardinalTextures);
            throw new IllegalArgumentException(
                    "Les huit silhouettes ne se recouvrent pas assez. Garde le sujet entier, stable et centré pendant la rotation."
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
                // Le maillage brut reste valide si le lissage secondaire échoue.
            }
        } catch (Exception | OutOfMemoryError error) {
            atlas.recycle();
            throw error;
        }

        releaseMemory();
        notifyProgress(listener, Stage.DEPTH, 0, 3);
        MeshData finalMesh = mesh;
        String depthBackend;
        int depthPasses = 0;
        long depthStarted = SystemClock.elapsedRealtime();
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
            finalMesh = NeuralMeshRefiner.refine(
                    mesh,
                    front,
                    back,
                    side
            );
            depthBackend = depth.getBackend() + " • 3 vues cardinales";
        } catch (Exception | OutOfMemoryError error) {
            depthBackend = "Relief géométrique conservé : "
                    + shortError(error);
            releaseMemory();
        }

        return new Result(
                finalMesh,
                atlas,
                occupied,
                profile.label,
                profile.processors,
                segmentationBackend + " • " + depthBackend,
                SystemClock.elapsedRealtime() - depthStarted,
                SystemClock.elapsedRealtime() - started
        );
    }

    @Override
    public void close() {
        // Les sessions neuronales sont volontairement fermées après chaque tâche.
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
                if (Color.alpha(pixels[row + x]) > FOREGROUND_ALPHA) {
                    foreground++;
                    left = Math.min(left, x);
                    top = Math.min(top, y);
                    right = Math.max(right, x);
                    bottom = Math.max(bottom, y);
                }
            }
        }
        int minimum = Math.max(48, width * height / 1800);
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
                ) > FOREGROUND_ALPHA) {
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
                cardinal[0],
                layout.frontWidth,
                layout.atlasHeight
        );
        Bitmap back = normalizedTexture(
                cardinal[1],
                layout.frontWidth,
                layout.atlasHeight
        );
        Bitmap right = normalizedTexture(
                cardinal[2],
                layout.sideWidth,
                layout.atlasHeight
        );
        Bitmap left = normalizedTexture(
                cardinal[3],
                layout.sideWidth,
                layout.atlasHeight
        );
        try {
            drawCell(canvas, paint, front, layout.frontStart,
                    layout.frontWidth, layout.atlasHeight);
            drawCell(canvas, paint, back, layout.backStart,
                    layout.frontWidth, layout.atlasHeight);
            drawCell(canvas, paint, right, layout.rightStart,
                    layout.sideWidth, layout.atlasHeight);
            drawCell(canvas, paint, left, layout.leftStart,
                    layout.sideWidth, layout.atlasHeight);
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
        RectF destination = new RectF(
                (targetWidth - width) * 0.5f,
                (targetHeight - height) * 0.5f,
                (targetWidth + width) * 0.5f,
                (targetHeight + height) * 0.5f
        );
        Paint paint = new Paint(
                Paint.ANTI_ALIAS_FLAG
                        | Paint.FILTER_BITMAP_FLAG
                        | Paint.DITHER_FLAG
        );
        canvas.drawBitmap(source, null, destination, paint);
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
            if (Color.alpha(pixels[index]) > FOREGROUND_ALPHA) {
                pixels[index] = 0xFF000000 | (pixels[index] & 0x00FFFFFF);
                distance[index] = 0;
                queue[tail++] = index;
            }
        }
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
                boolean on = false;
                for (int oy = -1; oy <= 1 && !on; oy++) {
                    for (int ox = -1; ox <= 1; ox++) {
                        if (mask[(y + oy) * width + x + ox]) {
                            on = true;
                            break;
                        }
                    }
                }
                dilated[index] = on;
            }
        }
        boolean[] closed = Arrays.copyOf(dilated, dilated.length);
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                boolean on = true;
                for (int oy = -1; oy <= 1 && on; oy++) {
                    for (int ox = -1; ox <= 1; ox++) {
                        if (!dilated[(y + oy) * width + x + ox]) {
                            on = false;
                            break;
                        }
                    }
                }
                closed[y * width + x] = on;
            }
        }
        System.arraycopy(closed, 0, mask, 0, mask.length);
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
                    boolean xBridge = source[index - depth]
                            && source[index + depth];
                    boolean yBridge = source[index - width * depth]
                            && source[index + width * depth];
                    boolean zBridge = source[index - 1]
                            && source[index + 1];
                    if (xBridge || yBridge || zBridge) {
                        volume[index] = true;
                    }
                }
            }
        }
    }

    private static int cardinalSlot(int frameIndex) {
        for (int slot = 0; slot < CARDINAL_INDICES.length; slot++) {
            if (CARDINAL_INDICES[slot] == frameIndex) {
                return slot;
            }
        }
        return -1;
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

        Result(
                MeshData mesh,
                Bitmap texture,
                int occupiedVoxels,
                String qualityLabel,
                int processorCount,
                String backend,
                long neuralDurationMs,
                long totalDurationMs
        ) {
            this.mesh = mesh;
            this.texture = texture;
            this.occupiedVoxels = occupiedVoxels;
            this.qualityLabel = qualityLabel;
            this.processorCount = processorCount;
            this.backend = backend;
            this.neuralDurationMs = neuralDurationMs;
            this.totalDurationMs = totalDurationMs;
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
    }

    private static final class Profile {
        final int width;
        final int height;
        final int depth;
        final int processors;
        final String label;

        private Profile(
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
            if (memoryMb >= 700L && processors >= 8) {
                return new Profile(
                        112,
                        224,
                        112,
                        processors,
                        "Vidéo 8 vues ultra"
                );
            }
            if (memoryMb >= 430L && processors >= 6) {
                return new Profile(
                        96,
                        192,
                        96,
                        processors,
                        "Vidéo 8 vues haute précision"
                );
            }
            return new Profile(
                    80,
                    160,
                    80,
                    processors,
                    "Vidéo 8 vues compatible"
            );
        }
    }
}
