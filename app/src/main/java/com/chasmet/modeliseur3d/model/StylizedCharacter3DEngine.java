package com.chasmet.modeliseur3d.model;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;

import java.util.Arrays;
import java.util.List;

/**
 * Moteur 3D local V6 réservé au mode quatre vues.
 *
 * La V6 conserve les corrections de profil éprouvées, mais remplace le volume
 * binaire par un champ de distances multivue continu. Les contours IS-Net
 * sous-pixel, les membres séparés et les sections arrondies sont ainsi gardés
 * jusqu'au maillage final. Le moteur 2.5D reste totalement séparé.
 */
public final class StylizedCharacter3DEngine implements AutoCloseable {
    public static final int REQUIRED_VIEW_COUNT = 4;
    private static final float FOREGROUND_ALPHA = 24.0f;
    private static final int MAXIMUM_COMPONENTS = 16;

    private final Context context;

    public StylizedCharacter3DEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    public Result generate(
            List<Bitmap> views,
            float depthMultiplier,
            ProgressListener listener
    ) throws Exception {
        validateViews(views);
        long started = SystemClock.elapsedRealtime();
        Bitmap[] isolated = new Bitmap[REQUIRED_VIEW_COUNT];
        Rect[] bounds = new Rect[REQUIRED_VIEW_COUNT];
        String segmentationBackend;

        try (AnimeSegmentationEngine segmentation = new AnimeSegmentationEngine(context)) {
            segmentationBackend = segmentation.getBackend();
            for (int index = 0; index < views.size(); index++) {
                notifyProgress(listener, Stage.SEGMENTING, index + 1, views.size());
                AnimeSegmentationEngine.Mask neuralMask = segmentation.segment(views.get(index));
                isolated[index] = NeuralSheetIsolator.isolate(views.get(index), neuralMask);
                bounds[index] = findForegroundBounds(isolated[index]);
            }
        } catch (Exception | OutOfMemoryError error) {
            recycleAll(isolated);
            throw error;
        }

        notifyProgress(listener, Stage.ANALYSING, REQUIRED_VIEW_COUNT, REQUIRED_VIEW_COUNT);
        FourViewBitmapOrientationNormalizer.Result orientationCorrection;
        try {
            orientationCorrection = FourViewBitmapOrientationNormalizer.normalize(
                    isolated,
                    bounds
            );
        } catch (RuntimeException | OutOfMemoryError error) {
            recycleAll(isolated);
            throw error;
        }

        Profile profile = Profile.detect(depthMultiplier, bounds);
        boolean[][] masks = new boolean[REQUIRED_VIEW_COUNT][];
        float[][] confidences = new float[REQUIRED_VIEW_COUNT][];
        int componentCount = 0;
        try {
            for (int index = 0; index < REQUIRED_VIEW_COUNT; index++) {
                int axisWidth = isProfile(index) ? profile.depth : profile.width;
                NormalizedSilhouette normalized = normalizeSilhouette(
                        isolated[index],
                        bounds[index],
                        axisWidth,
                        profile.height
                );
                masks[index] = StylizedMaskTopology.clean(
                        normalized.mask,
                        axisWidth,
                        profile.height,
                        MAXIMUM_COMPONENTS
                );
                confidences[index] = keepCleanConfidence(
                        normalized.confidence,
                        masks[index]
                );
                componentCount += StylizedMaskTopology.countComponents(
                        masks[index],
                        axisWidth,
                        profile.height
                );
            }
        } catch (RuntimeException | OutOfMemoryError error) {
            recycleAll(isolated);
            throw error;
        }

        FourViewAutoCorrector.ProfileCorrection profileCorrection =
                FourViewAutoCorrector.analyzeProfiles(
                        masks[StylizedFourViewProjector.RIGHT],
                        masks[StylizedFourViewProjector.LEFT],
                        profile.depth,
                        profile.height
                );
        if (profileCorrection.shouldFlipLeft()) {
            masks[StylizedFourViewProjector.LEFT] = FourViewAutoCorrector.flipHorizontal(
                    masks[StylizedFourViewProjector.LEFT],
                    profile.depth,
                    profile.height
            );
            confidences[StylizedFourViewProjector.LEFT] = flipHorizontal(
                    confidences[StylizedFourViewProjector.LEFT],
                    profile.depth,
                    profile.height
            );
        }

        double coherence = FourViewAutoCorrector.computeCoherence(
                masks[StylizedFourViewProjector.FRONT],
                masks[StylizedFourViewProjector.BACK],
                profile.width,
                masks[StylizedFourViewProjector.RIGHT],
                masks[StylizedFourViewProjector.LEFT],
                profile.depth,
                profile.height
        );
        boolean adaptive = FourViewAutoCorrector.shouldUseAdaptiveHull(
                coherence,
                profileCorrection
        );

        notifyProgress(listener, Stage.CLEANING, REQUIRED_VIEW_COUNT, REQUIRED_VIEW_COUNT);
        releaseMemory();
        notifyProgress(listener, Stage.BUILDING_HULL, 0, 1);

        ContinuousVisualHull.Result hull;
        int occupied;
        try {
            hull = ContinuousVisualHull.build(
                    confidences,
                    masks,
                    profile.width,
                    profile.height,
                    profile.depth,
                    adaptive
            );
            occupied = hull.getOccupiedVoxels();
            int minimumUseful = Math.max(
                    500,
                    profile.width * profile.height * profile.depth / 3600
            );
            if (!adaptive && occupied < minimumUseful) {
                adaptive = true;
                hull = null;
                releaseMemory();
                hull = ContinuousVisualHull.build(
                        confidences,
                        masks,
                        profile.width,
                        profile.height,
                        profile.depth,
                        true
                );
                occupied = hull.getOccupiedVoxels();
            }
        } catch (RuntimeException | OutOfMemoryError error) {
            recycleAll(isolated);
            throw error;
        }
        if (occupied < 320) {
            recycleAll(isolated);
            throw new IllegalArgumentException(
                    "Les quatre vues restent trop différentes. Utilise le même personnage, "
                            + "la même pose et le corps entier."
            );
        }
        SmoothHullMesher.AtlasLayout layout = SmoothHullMesher.AtlasLayout.create(
                profile.width,
                profile.height,
                profile.depth,
                profile.atlasHeight
        );
        Bitmap atlas;
        try {
            atlas = buildAtlas(
                    isolated,
                    bounds,
                    layout,
                    profileCorrection.shouldFlipLeft()
            );
        } finally {
            recycleAll(isolated);
        }

        notifyProgress(listener, Stage.MESHING, 0, 1);
        MeshData mesh;
        try {
            mesh = SmoothHullMesher.build(
                    hull.getDensity(),
                    profile.width,
                    profile.height,
                    profile.depth,
                    layout,
                    profile.processors
            );
            try {
                mesh = MeshSurfaceOptimizer.optimize(mesh, adaptive ? 2 : 1);
            } catch (RuntimeException ignored) {
                // Le maillage brut reste valide si l'optimisation facultative échoue.
            }
            mesh = MeshOrientationCorrector.correct(mesh);
        } catch (Exception | OutOfMemoryError error) {
            recycle(atlas);
            throw error;
        }

        int averageComponents = Math.max(1, componentCount / REQUIRED_VIEW_COUNT);
        StringBuilder correctionSummary = new StringBuilder(
                orientationCorrection.getSummary()
        );
        if (profileCorrection.shouldFlipLeft()) {
            correctionSummary.append(" • profil gauche mis en miroir");
        }
        if (adaptive) {
            correctionSummary.append(" • volume adaptatif");
        } else {
            correctionSummary.append(" • quatre vues cohérentes");
        }
        correctionSummary.append(" • champ continu sous-pixel");
        correctionSummary.append(" • sections arrondies");
        correctionSummary.append(" • silhouettes ")
                .append(Math.round(hull.getSilhouetteScore() * 100.0))
                .append(" %");
        correctionSummary.append(" • texture 2K remise à l'endroit");

        return new Result(
                mesh,
                atlas,
                occupied,
                averageComponents,
                profile.label,
                profile.processors,
                segmentationBackend,
                adaptive,
                profileCorrection.shouldFlipLeft(),
                coherence,
                profile.depth,
                correctionSummary.toString(),
                SystemClock.elapsedRealtime() - started
        );
    }

    @Override
    public void close() {
    }

    private static void validateViews(List<Bitmap> views) {
        if (views == null || views.size() != REQUIRED_VIEW_COUNT) {
            throw new IllegalArgumentException(
                    "Quatre vues sont obligatoires : face, droite, dos et gauche"
            );
        }
        for (Bitmap bitmap : views) {
            if (bitmap == null || bitmap.isRecycled()) {
                throw new IllegalArgumentException("Une image du personnage est invalide");
            }
        }
    }

    private static boolean isProfile(int index) {
        return index == StylizedFourViewProjector.RIGHT
                || index == StylizedFourViewProjector.LEFT;
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
                    "Personnage trop petit ou détourage insuffisant"
            );
        }
        return new Rect(left, top, right + 1, bottom + 1);
    }

    private static NormalizedSilhouette normalizeSilhouette(
            Bitmap isolated,
            Rect bounds,
            int targetWidth,
            int targetHeight
    ) {
        Bitmap normalized = Bitmap.createBitmap(
                targetWidth,
                targetHeight,
                Bitmap.Config.ARGB_8888
        );
        int drawHeight = Math.max(1, Math.round(targetHeight * 0.92f));
        float physicalScale = drawHeight / Math.max(1.0f, bounds.height());
        int naturalWidth = Math.max(1, Math.round(bounds.width() * physicalScale));
        int drawWidth = Math.min(Math.round(targetWidth * 0.94f), naturalWidth);
        drawWidth = Math.max(1, drawWidth);
        float offsetX = (targetWidth - drawWidth) * 0.5f;
        float offsetY = (targetHeight - drawHeight) * 0.5f;
        Canvas canvas = new Canvas(normalized);
        canvas.drawColor(Color.TRANSPARENT);
        Paint paint = new Paint(
                Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG
        );
        canvas.drawBitmap(
                isolated,
                bounds,
                new RectF(
                        offsetX,
                        offsetY,
                        offsetX + drawWidth,
                        offsetY + drawHeight
                ),
                paint
        );

        int[] pixels = new int[targetWidth * targetHeight];
        normalized.getPixels(
                pixels,
                0,
                targetWidth,
                0,
                0,
                targetWidth,
                targetHeight
        );
        normalized.recycle();
        boolean[] mask = new boolean[pixels.length];
        float[] confidence = new float[pixels.length];
        for (int index = 0; index < pixels.length; index++) {
            float alpha = Color.alpha(pixels[index]) / 255.0f;
            confidence[index] = alpha;
            mask[index] = alpha >= 0.28f;
        }
        return new NormalizedSilhouette(mask, confidence);
    }

    private static float[] keepCleanConfidence(
            float[] source,
            boolean[] cleanMask
    ) {
        float[] output = new float[source.length];
        for (int index = 0; index < source.length; index++) {
            if (cleanMask[index]) {
                // Une fermeture topologique peut créer un pixel sans valeur
                // neuronale. Une confiance neutre évite d'y creuser un trou.
                output[index] = Math.max(0.26f, source[index]);
            }
        }
        return output;
    }

    private static float[] flipHorizontal(float[] source, int width, int height) {
        float[] output = new float[source.length];
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                output[row + (width - 1 - x)] = source[row + x];
            }
        }
        return output;
    }

    private static Bitmap buildAtlas(
            Bitmap[] views,
            Rect[] bounds,
            SmoothHullMesher.AtlasLayout layout,
            boolean flipLeft
    ) {
        for (int index = 0; index < views.length; index++) {
            if (views[index] == null || views[index].isRecycled() || bounds[index] == null) {
                throw new IllegalArgumentException("Une texture 3D est absente");
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
                Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG
        );
        Bitmap front = normalizedTexture(
                views[StylizedFourViewProjector.FRONT],
                bounds[StylizedFourViewProjector.FRONT],
                layout.frontWidth,
                layout.atlasHeight,
                false
        );
        Bitmap back = normalizedTexture(
                views[StylizedFourViewProjector.BACK],
                bounds[StylizedFourViewProjector.BACK],
                layout.frontWidth,
                layout.atlasHeight,
                false
        );
        Bitmap right = normalizedTexture(
                views[StylizedFourViewProjector.RIGHT],
                bounds[StylizedFourViewProjector.RIGHT],
                layout.sideWidth,
                layout.atlasHeight,
                false
        );
        Bitmap left = normalizedTexture(
                views[StylizedFourViewProjector.LEFT],
                bounds[StylizedFourViewProjector.LEFT],
                layout.sideWidth,
                layout.atlasHeight,
                flipLeft
        );
        try {
            drawCell(canvas, paint, front, layout.frontStart, layout.frontWidth, layout.atlasHeight);
            drawCell(canvas, paint, back, layout.backStart, layout.frontWidth, layout.atlasHeight);
            drawCell(canvas, paint, right, layout.rightStart, layout.sideWidth, layout.atlasHeight);
            drawCell(canvas, paint, left, layout.leftStart, layout.sideWidth, layout.atlasHeight);
        } finally {
            recycle(front);
            recycle(back);
            recycle(right);
            recycle(left);
        }
        return atlas;
    }

    private static Bitmap normalizedTexture(
            Bitmap source,
            Rect bounds,
            int targetWidth,
            int targetHeight,
            boolean flipHorizontal
    ) {
        Bitmap output = Bitmap.createBitmap(
                targetWidth,
                targetHeight,
                Bitmap.Config.ARGB_8888
        );
        Canvas canvas = new Canvas(output);
        canvas.drawColor(Color.TRANSPARENT);
        int drawHeight = Math.max(1, Math.round(targetHeight * 0.92f));
        float scale = drawHeight / Math.max(1.0f, bounds.height());
        int drawWidth = Math.max(1, Math.round(bounds.width() * scale));
        drawWidth = Math.min(Math.round(targetWidth * 0.94f), drawWidth);
        float left = (targetWidth - drawWidth) * 0.5f;
        float top = (targetHeight - drawHeight) * 0.5f;
        RectF destination = new RectF(left, top, left + drawWidth, top + drawHeight);
        Paint paint = new Paint(
                Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG
        );
        if (flipHorizontal) {
            Matrix matrix = new Matrix();
            matrix.setScale(-1.0f, 1.0f, targetWidth * 0.5f, targetHeight * 0.5f);
            canvas.save();
            canvas.concat(matrix);
            canvas.drawBitmap(source, bounds, destination, paint);
            canvas.restore();
        } else {
            canvas.drawBitmap(source, bounds, destination, paint);
        }
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
        canvas.drawBitmap(texture, null, new RectF(start, 0, start + width, height), paint);
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
            tail = propagate(pixels, distance, queue, tail, current, x - 1, y, width, height);
            tail = propagate(pixels, distance, queue, tail, current, x + 1, y, width, height);
            tail = propagate(pixels, distance, queue, tail, current, x, y - 1, width, height);
            tail = propagate(pixels, distance, queue, tail, current, x, y + 1, width, height);
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

    private static void recycleAll(Bitmap[] bitmaps) {
        for (Bitmap bitmap : bitmaps) {
            recycle(bitmap);
        }
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }

    public enum Stage {
        SEGMENTING,
        ANALYSING,
        CLEANING,
        BUILDING_HULL,
        MESHING
    }

    public interface ProgressListener {
        void onProgress(Stage stage, int current, int total);
    }

    private static final class NormalizedSilhouette {
        final boolean[] mask;
        final float[] confidence;

        NormalizedSilhouette(boolean[] mask, float[] confidence) {
            this.mask = mask;
            this.confidence = confidence;
        }
    }

    public static final class Result {
        private final MeshData mesh;
        private final Bitmap texture;
        private final int occupiedVoxels;
        private final int averageComponents;
        private final String qualityLabel;
        private final int processorCount;
        private final String backend;
        private final boolean adaptiveHull;
        private final boolean profileMirrored;
        private final double coherence;
        private final int depthResolution;
        private final String correctionSummary;
        private final long totalDurationMs;

        Result(
                MeshData mesh,
                Bitmap texture,
                int occupiedVoxels,
                int averageComponents,
                String qualityLabel,
                int processorCount,
                String backend,
                boolean adaptiveHull,
                boolean profileMirrored,
                double coherence,
                int depthResolution,
                String correctionSummary,
                long totalDurationMs
        ) {
            this.mesh = mesh;
            this.texture = texture;
            this.occupiedVoxels = occupiedVoxels;
            this.averageComponents = averageComponents;
            this.qualityLabel = qualityLabel;
            this.processorCount = processorCount;
            this.backend = backend;
            this.adaptiveHull = adaptiveHull;
            this.profileMirrored = profileMirrored;
            this.coherence = coherence;
            this.depthResolution = depthResolution;
            this.correctionSummary = correctionSummary;
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

        public int getAverageComponents() {
            return averageComponents;
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

        public boolean isAdaptiveHull() {
            return adaptiveHull;
        }

        public boolean isProfileMirrored() {
            return profileMirrored;
        }

        public double getCoherence() {
            return coherence;
        }

        public int getDepthResolution() {
            return depthResolution;
        }

        public String getCorrectionSummary() {
            return correctionSummary;
        }

        public long getTotalDurationMs() {
            return totalDurationMs;
        }
    }

    private static final class Profile {
        final int width;
        final int height;
        final int depth;
        final int atlasHeight;
        final int processors;
        final String label;

        private Profile(
                int width,
                int height,
                int depth,
                int atlasHeight,
                int processors,
                String label
        ) {
            this.width = width;
            this.height = height;
            this.depth = depth;
            this.atlasHeight = atlasHeight;
            this.processors = processors;
            this.label = label;
        }

        static Profile detect(float requestedDepth, Rect[] bounds) {
            int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
            long memoryMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
            int width;
            int height;
            int maximumDepth;
            int atlasHeight;
            String label;
            if (memoryMb >= 700L && processors >= 8) {
                width = 128;
                height = 256;
                maximumDepth = 304;
                atlasHeight = 2048;
                label = "3D V6 précision extrême";
            } else if (memoryMb >= 430L && processors >= 6) {
                width = 112;
                height = 224;
                maximumDepth = 264;
                atlasHeight = 2048;
                label = "3D V6 haute précision";
            } else {
                width = 88;
                height = 176;
                maximumDepth = 200;
                atlasHeight = 1024;
                label = "3D V6 compatible";
            }
            double frontAspect = averageAspect(
                    bounds[StylizedFourViewProjector.FRONT],
                    bounds[StylizedFourViewProjector.BACK]
            );
            double sideAspect = averageAspect(
                    bounds[StylizedFourViewProjector.RIGHT],
                    bounds[StylizedFourViewProjector.LEFT]
            );
            double aspectRatio = sideAspect / Math.max(0.18, frontAspect);
            aspectRatio = Math.max(0.65, Math.min(1.75, aspectRatio));
            float multiplier = Math.max(0.65f, Math.min(1.35f, requestedDepth));
            int depth = Math.round((float) (width * aspectRatio * multiplier));
            depth = Math.max(48, Math.min(maximumDepth, depth));
            return new Profile(
                    width,
                    height,
                    depth,
                    atlasHeight,
                    processors,
                    label
            );
        }

        private static double averageAspect(Rect first, Rect second) {
            double firstAspect = first.width() / Math.max(1.0, first.height());
            double secondAspect = second.width() / Math.max(1.0, second.height());
            return (firstAspect + secondAspect) * 0.5;
        }
    }
}
