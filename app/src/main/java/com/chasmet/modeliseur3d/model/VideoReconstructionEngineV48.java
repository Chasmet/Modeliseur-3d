package com.chasmet.modeliseur3d.model;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;

import com.chasmet.modeliseur3d.performance.DevicePerformanceProfile;

import java.util.Arrays;
import java.util.List;

/**
 * Moteur vidéo V4.8 entièrement mobile.
 *
 * Contrairement aux anciennes versions, les huit silhouettes conservent une
 * échelle commune. Le volume est nettoyé par composante connexe, puis chaque
 * triangle reçoit la texture de l'angle le plus proche parmi huit vues.
 */
public final class VideoReconstructionEngineV48 implements AutoCloseable {
    private static final int VIEW_COUNT = 8;
    private static final int[] CARDINAL_INDICES = {0, 4, 2, 6};
    private static final int ALPHA_THRESHOLD = 24;

    private final Context context;
    private final DevicePerformanceProfile deviceProfile;

    public VideoReconstructionEngineV48(
            Context context,
            DevicePerformanceProfile deviceProfile
    ) {
        this.context = context.getApplicationContext();
        this.deviceProfile = deviceProfile;
    }

    public Result generate(
            List<Bitmap> frames,
            int decodedFrameCount,
            ProgressListener listener
    ) throws Exception {
        validateFrames(frames);
        long started = SystemClock.elapsedRealtime();
        Profile profile = Profile.from(deviceProfile);
        boolean[][] masks = new boolean[VIEW_COUNT][];
        Bitmap[] textures = new Bitmap[VIEW_COUNT];
        int repairedViews = 0;
        String segmentationBackend;

        try (AnimeSegmentationEngine segmentation =
                     new AnimeSegmentationEngine(
                             context,
                             deviceProfile.getNeuralThreadCount()
                     )) {
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
                } catch (Exception | OutOfMemoryError ignored) {
                    prepared = prepareFallbackView(frames.get(index), profile);
                }

                if (prepared == null) {
                    int replacement = nearestPreparedIndex(masks, index);
                    if (replacement < 0) {
                        recycleAll(textures);
                        throw new IllegalArgumentException(
                                "Aucune silhouette vidéo stable n'a été détectée"
                        );
                    }
                    repairedViews++;
                    masks[index] = Arrays.copyOf(
                            masks[replacement],
                            masks[replacement].length
                    );
                    textures[index] = textures[replacement].copy(
                            Bitmap.Config.ARGB_8888,
                            false
                    );
                } else {
                    masks[index] = prepared.mask;
                    textures[index] = prepared.texture;
                }
            }
        }

        repairedViews += repairSevereOutliers(
                masks,
                textures,
                profile.width,
                profile.height
        );
        int phase = ViewPhaseEstimator.estimate(
                masks,
                profile.width,
                profile.height
        );
        boolean[][] orderedMasks = ViewPhaseEstimator.rotate(masks, phase);
        Bitmap[] orderedTextures = rotateTextures(textures, phase);

        notifyProgress(listener, Stage.BUILDING_HULL, 0, 1);
        int support = decodedFrameCount >= 7 ? 6 : 5;
        boolean[] volume = MultiViewHullProjector.build(
                orderedMasks,
                profile.width,
                profile.height,
                profile.depth,
                support
        );
        int occupied = MultiViewHullProjector.countOccupied(volume);
        int minimumOccupied = Math.max(420, volume.length / 3200);
        if (occupied < minimumOccupied && support > 4) {
            support--;
            volume = MultiViewHullProjector.build(
                    orderedMasks,
                    profile.width,
                    profile.height,
                    profile.depth,
                    support
            );
        }
        VolumeTopologyCleaner.bridgeSingleVoxelGaps(
                volume,
                profile.width,
                profile.height,
                profile.depth
        );
        occupied = VolumeTopologyCleaner.keepLargestComponent(
                volume,
                profile.width,
                profile.height,
                profile.depth
        );
        if (occupied < Math.max(300, volume.length / 4200)) {
            recycleAll(textures);
            throw new IllegalArgumentException(
                    "Les huit angles ne forment pas un personnage 3D continu"
            );
        }

        SmoothHullMesher.AtlasLayout depthLayout =
                SmoothHullMesher.AtlasLayout.create(
                        profile.width,
                        profile.height,
                        profile.depth,
                        profile.depthAtlasHeight
                );
        Bitmap depthAtlas = null;
        Bitmap finalAtlas = null;
        try {
            depthAtlas = buildDepthAtlas(
                    orderedTextures,
                    depthLayout
            );

            notifyProgress(listener, Stage.MESHING, 0, 1);
            MeshData mesh = SmoothHullMesher.build(
                    volume,
                    profile.width,
                    profile.height,
                    profile.depth,
                    depthLayout,
                    profile.processors
            );
            try {
                mesh = MeshSurfaceOptimizer.optimize(mesh, 2);
            } catch (RuntimeException ignored) {
                // La surface extraite reste utilisable sans lissage secondaire.
            }

            releaseMemory();
            long neuralStarted = SystemClock.elapsedRealtime();
            MeshData refined = mesh;
            String depthBackend;
            int depthPasses = 0;
            notifyProgress(listener, Stage.DEPTH, 0, 3);
            try (NeuralMeshRefiner.Views views =
                         NeuralMeshRefiner.cropViews(depthAtlas);
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
                refined = NeuralMeshRefiner.refine(
                        mesh,
                        front,
                        back,
                        side
                );
                depthBackend = depth.getBackend()
                        + " • relief multivue borné";
            } catch (Exception | OutOfMemoryError error) {
                depthBackend = "coque multivue conservée • " + shortError(error);
                releaseMemory();
            }

            MultiViewTextureMapper.AtlasResult atlasResult =
                    MultiViewTextureMapper.buildAtlas(
                            orderedTextures,
                            profile.textureCellHeight
                    );
            finalAtlas = atlasResult.getBitmap();
            MeshData textured = MultiViewTextureMapper.remap(
                    refined,
                    profile.width,
                    profile.height,
                    profile.depth,
                    atlasResult.getLayout()
            );
            if (textured.getTriangleCount() > profile.triangleLimit) {
                try {
                    textured = MobileMeshOptimizer.simplify(
                            textured,
                            profile.triangleLimit
                    );
                } catch (RuntimeException ignored) {
                    // L'exporteur effectuera encore sa propre réduction mobile.
                }
            }

            String backend = segmentationBackend
                    + " • alignement global des huit vues"
                    + " • composante volumique principale"
                    + " • atlas huit angles"
                    + (repairedViews > 0
                    ? " • " + repairedViews + " vues réparées"
                    : " • 8 vues originales")
                    + " • " + depthBackend;
            String quality = profile.label
                    + " • phase " + phase
                    + " • support " + support + "/8"
                    + " • " + decodedFrameCount + " vues décodées"
                    + " • texture 8 directions";

            Bitmap returnedAtlas = finalAtlas;
            finalAtlas = null;
            return new Result(
                    textured,
                    returnedAtlas,
                    occupied,
                    quality,
                    profile.processors,
                    backend,
                    SystemClock.elapsedRealtime() - neuralStarted,
                    SystemClock.elapsedRealtime() - started,
                    decodedFrameCount,
                    repairedViews
            );
        } finally {
            if (depthAtlas != null && !depthAtlas.isRecycled()) {
                depthAtlas.recycle();
            }
            if (finalAtlas != null && !finalAtlas.isRecycled()) {
                finalAtlas.recycle();
            }
            recycleAll(textures);
        }
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
            boolean[] mask = sampleWholeFrameMask(
                    isolated,
                    profile.width,
                    profile.height
            );
            mask = selectMainSubject(mask, profile.width, profile.height);
            closeMask(mask, profile.width, profile.height);
            Bitmap texture = isolated.copy(Bitmap.Config.ARGB_8888, false);
            if (texture == null) {
                throw new IllegalStateException("Copie de texture impossible");
            }
            return new PreparedView(mask, texture, bounds);
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
            boolean[] mask = sampleWholeFrameMask(
                    isolated,
                    profile.width,
                    profile.height
            );
            mask = selectMainSubject(mask, profile.width, profile.height);
            closeMask(mask, profile.width, profile.height);
            Bitmap texture = isolated.copy(Bitmap.Config.ARGB_8888, false);
            return texture == null ? null : new PreparedView(mask, texture, bounds);
        } catch (RuntimeException ignored) {
            return null;
        } finally {
            if (!isolated.isRecycled()) {
                isolated.recycle();
            }
        }
    }

    private static boolean[] selectMainSubject(
            boolean[] mask,
            int width,
            int height
    ) {
        SingleSubjectSelector.Selection selection =
                SingleSubjectSelector.select(mask, width, height);
        return Arrays.copyOf(
                selection.getMask(),
                selection.getMask().length
        );
    }

    private static boolean[] sampleWholeFrameMask(
            Bitmap isolated,
            int targetWidth,
            int targetHeight
    ) {
        int sourceWidth = isolated.getWidth();
        int sourceHeight = isolated.getHeight();
        int[] pixels = new int[sourceWidth * sourceHeight];
        isolated.getPixels(
                pixels,
                0,
                sourceWidth,
                0,
                0,
                sourceWidth,
                sourceHeight
        );
        boolean[] output = new boolean[targetWidth * targetHeight];
        for (int y = 0; y < targetHeight; y++) {
            int sourceY = Math.min(
                    sourceHeight - 1,
                    Math.round((y + 0.5f) * sourceHeight / targetHeight - 0.5f)
            );
            for (int x = 0; x < targetWidth; x++) {
                int sourceX = Math.min(
                        sourceWidth - 1,
                        Math.round((x + 0.5f) * sourceWidth / targetWidth - 0.5f)
                );
                output[y * targetWidth + x] = Color.alpha(
                        pixels[sourceY * sourceWidth + sourceX]
                ) > ALPHA_THRESHOLD;
            }
        }
        return output;
    }

    private static int repairSevereOutliers(
            boolean[][] masks,
            Bitmap[] textures,
            int width,
            int height
    ) {
        int[] areas = new int[VIEW_COUNT];
        int[] spans = new int[VIEW_COUNT];
        int[] sortedAreas = new int[VIEW_COUNT];
        int[] sortedSpans = new int[VIEW_COUNT];
        for (int index = 0; index < VIEW_COUNT; index++) {
            areas[index] = countTrue(masks[index]);
            spans[index] = verticalSpan(masks[index], width, height);
            sortedAreas[index] = areas[index];
            sortedSpans[index] = spans[index];
        }
        Arrays.sort(sortedAreas);
        Arrays.sort(sortedSpans);
        float medianArea = (sortedAreas[3] + sortedAreas[4]) * 0.5f;
        float medianSpan = (sortedSpans[3] + sortedSpans[4]) * 0.5f;
        int repaired = 0;

        for (int index = 0; index < VIEW_COUNT; index++) {
            boolean healthy = areas[index] >= medianArea * 0.25f
                    && areas[index] <= medianArea * 2.45f
                    && spans[index] >= medianSpan * 0.62f;
            if (healthy) {
                continue;
            }
            int replacement = nearestHealthyIndex(
                    areas,
                    spans,
                    medianArea,
                    medianSpan,
                    index
            );
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
            repaired++;
        }
        return repaired;
    }

    private static int nearestHealthyIndex(
            int[] areas,
            int[] spans,
            float medianArea,
            float medianSpan,
            int target
    ) {
        for (int distance = 1; distance < VIEW_COUNT; distance++) {
            int before = target - distance;
            if (before >= 0 && isHealthy(
                    areas[before],
                    spans[before],
                    medianArea,
                    medianSpan
            )) {
                return before;
            }
            int after = target + distance;
            if (after < VIEW_COUNT && isHealthy(
                    areas[after],
                    spans[after],
                    medianArea,
                    medianSpan
            )) {
                return after;
            }
        }
        return -1;
    }

    private static boolean isHealthy(
            int area,
            int span,
            float medianArea,
            float medianSpan
    ) {
        return area >= medianArea * 0.25f
                && area <= medianArea * 2.45f
                && span >= medianSpan * 0.62f;
    }

    private static int nearestPreparedIndex(boolean[][] masks, int target) {
        for (int distance = 1; distance < VIEW_COUNT; distance++) {
            int before = target - distance;
            if (before >= 0 && masks[before] != null) {
                return before;
            }
            int after = target + distance;
            if (after < target && masks[after] != null) {
                return after;
            }
        }
        return -1;
    }

    private static Bitmap[] rotateTextures(Bitmap[] textures, int phase) {
        Bitmap[] ordered = new Bitmap[VIEW_COUNT];
        for (int index = 0; index < VIEW_COUNT; index++) {
            ordered[index] = textures[ViewPhaseEstimator.sourceIndex(index, phase)];
        }
        return ordered;
    }

    private static Bitmap buildDepthAtlas(
            Bitmap[] orderedTextures,
            SmoothHullMesher.AtlasLayout layout
    ) {
        Bitmap atlas = Bitmap.createBitmap(
                layout.atlasWidth,
                layout.atlasHeight,
                Bitmap.Config.ARGB_8888
        );
        Canvas canvas = new Canvas(atlas);
        canvas.drawColor(Color.rgb(26, 28, 34));
        Paint paint = new Paint(
                Paint.ANTI_ALIAS_FLAG
                        | Paint.FILTER_BITMAP_FLAG
                        | Paint.DITHER_FLAG
        );
        int[] starts = {
                layout.frontStart,
                layout.backStart,
                layout.rightStart,
                layout.leftStart
        };
        int[] widths = {
                layout.frontWidth,
                layout.frontWidth,
                layout.sideWidth,
                layout.sideWidth
        };
        for (int slot = 0; slot < CARDINAL_INDICES.length; slot++) {
            Bitmap source = orderedTextures[CARDINAL_INDICES[slot]];
            canvas.drawBitmap(
                    source,
                    null,
                    new RectF(
                            starts[slot],
                            0,
                            starts[slot] + widths[slot],
                            layout.atlasHeight
                    ),
                    paint
            );
        }
        return atlas;
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
            pixels[index] = distance >= 25.0f
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
        int minimum = Math.max(64, width * height / 2600);
        if (right < left || bottom < top || foreground < minimum) {
            throw new IllegalArgumentException(
                    "Sujet trop petit ou détourage vidéo insuffisant"
            );
        }
        return new Rect(left, top, right + 1, bottom + 1);
    }

    private static void closeMask(boolean[] mask, int width, int height) {
        boolean[] source = Arrays.copyOf(mask, mask.length);
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int index = y * width + x;
                if (source[index]) {
                    continue;
                }
                int neighbours = 0;
                for (int oy = -1; oy <= 1; oy++) {
                    for (int ox = -1; ox <= 1; ox++) {
                        if (source[(y + oy) * width + x + ox]) {
                            neighbours++;
                        }
                    }
                }
                if (neighbours >= 4) {
                    mask[index] = true;
                }
            }
        }
    }

    private static int verticalSpan(boolean[] mask, int width, int height) {
        int top = height;
        int bottom = -1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (mask[y * width + x]) {
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y);
                }
            }
        }
        return bottom < top ? 0 : bottom - top + 1;
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
        final Rect bounds;

        PreparedView(boolean[] mask, Bitmap texture, Rect bounds) {
            this.mask = mask;
            this.texture = texture;
            this.bounds = bounds;
        }
    }

    private static final class Profile {
        final int width;
        final int height;
        final int depth;
        final int processors;
        final int depthAtlasHeight;
        final int textureCellHeight;
        final int triangleLimit;
        final String label;

        Profile(
                int width,
                int height,
                int depth,
                int processors,
                int depthAtlasHeight,
                int textureCellHeight,
                int triangleLimit,
                String label
        ) {
            this.width = width;
            this.height = height;
            this.depth = depth;
            this.processors = processors;
            this.depthAtlasHeight = depthAtlasHeight;
            this.textureCellHeight = textureCellHeight;
            this.triangleLimit = triangleLimit;
            this.label = label;
        }

        static Profile from(DevicePerformanceProfile device) {
            int processors = Math.max(1, device.getProcessorCount());
            switch (device.getTier()) {
                case TURBO:
                    return new Profile(
                            96,
                            192,
                            96,
                            processors,
                            1024,
                            704,
                            110_000,
                            "Vidéo V4.8 multivue Turbo"
                    );
                case QUALITY:
                    return new Profile(
                            84,
                            168,
                            84,
                            processors,
                            896,
                            608,
                            88_000,
                            "Vidéo V4.8 multivue Qualité"
                    );
                case COMPATIBILITY:
                default:
                    return new Profile(
                            72,
                            144,
                            72,
                            processors,
                            768,
                            512,
                            66_000,
                            "Vidéo V4.8 multivue Compatible"
                    );
            }
        }
    }
}
