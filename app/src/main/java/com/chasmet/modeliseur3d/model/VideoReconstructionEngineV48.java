package com.chasmet.modeliseur3d.model;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.SystemClock;

import com.chasmet.modeliseur3d.performance.DevicePerformanceProfile;

import java.util.Arrays;
import java.util.List;

/**
 * Moteur vidéo V4.9 mobile fondé sur des coupes de silhouettes continues.
 *
 * Cette méthode n'utilise plus de volume voxel. Chaque hauteur du personnage
 * est reconstruite par l'intersection des huit bandes observées, puis reliée
 * en anneaux réguliers. La texture est cuite dans un dépliage cylindrique
 * unique afin d'éviter les bandes et les changements de vue par triangle.
 */
public final class VideoReconstructionEngineV48 implements AutoCloseable {
    private static final int VIEW_COUNT = 8;
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
        PreparedView[] preparedViews = new PreparedView[VIEW_COUNT];
        int repairedViews = 0;
        String segmentationBackend;
        long neuralStarted = SystemClock.elapsedRealtime();

        try (AnimeSegmentationEngine segmentation =
                     new AnimeSegmentationEngine(
                             context,
                             deviceProfile.getNeuralThreadCount()
                     )) {
            segmentationBackend = segmentation.getBackend();
            for (int index = 0; index < VIEW_COUNT; index++) {
                notifyProgress(listener, Stage.SEGMENTING, index + 1, VIEW_COUNT);
                preparedViews[index] = prepareView(
                        frames.get(index),
                        segmentation,
                        profile.sampleDimension
                );
            }
        }
        long neuralDuration = SystemClock.elapsedRealtime() - neuralStarted;

        for (int index = 0; index < VIEW_COUNT; index++) {
            if (preparedViews[index] != null) {
                continue;
            }
            int replacement = nearestPreparedIndex(preparedViews, index);
            if (replacement < 0) {
                recycleAll(preparedViews);
                throw new IllegalArgumentException(
                        "Aucune silhouette exploitable n'a été détectée dans la vidéo"
                );
            }
            preparedViews[index] = preparedViews[replacement].copy();
            repairedViews++;
        }

        try {
            VerticalRange verticalRange = findSharedVerticalRange(preparedViews);
            float[][] left = new float[VIEW_COUNT][profile.rows];
            float[][] right = new float[VIEW_COUNT][profile.rows];
            for (int view = 0; view < VIEW_COUNT; view++) {
                extractStrips(
                        preparedViews[view].alpha,
                        verticalRange,
                        left[view],
                        right[view]
                );
                repairStripRows(left[view], right[view]);
                smoothStrips(left[view], right[view], 2);
            }
            alignStripCenters(left, right);
            repairedViews += repairWidthOutliers(left, right);

            notifyProgress(listener, Stage.BUILDING_HULL, 1, 1);
            SilhouetteStripMesher.Sweep sweep = SilhouetteStripMesher.build(
                    left,
                    right,
                    profile.sectors
            );

            notifyProgress(listener, Stage.MESHING, 1, 1);
            MeshData mesh = sweep.getMesh();

            notifyProgress(listener, Stage.DEPTH, 0, 1);
            Bitmap texture = bakeCylindricalTexture(
                    preparedViews,
                    sweep,
                    verticalRange,
                    profile.textureWidth,
                    profile.textureHeight
            );
            notifyProgress(listener, Stage.DEPTH, 1, 1);

            String quality = profile.label
                    + " • " + profile.rows + " coupes"
                    + " • " + profile.sectors + " secteurs"
                    + " • " + decodedFrameCount + " vues décodées";
            String backend = segmentationBackend
                    + " • intersection de bandes 8 vues"
                    + " • surface annulaire étanche sans voxel"
                    + " • texture cylindrique fusionnée"
                    + (repairedViews > 0
                    ? " • " + repairedViews + " vues réparées"
                    : " • 8 vues originales");

            return new Result(
                    mesh,
                    texture,
                    sweep.getSurfaceSampleCount(),
                    quality,
                    profile.processors,
                    backend,
                    neuralDuration,
                    SystemClock.elapsedRealtime() - started,
                    decodedFrameCount,
                    repairedViews
            );
        } finally {
            recycleAll(preparedViews);
        }
    }

    private static PreparedView prepareView(
            Bitmap frame,
            AnimeSegmentationEngine segmentation,
            int sampleDimension
    ) {
        Bitmap isolated = null;
        Bitmap scaled = null;
        try {
            AnimeSegmentationEngine.Mask mask = segmentation.segment(frame);
            isolated = NeuralSheetIsolator.isolate(frame, mask);
            scaled = scaleForSampling(isolated, sampleDimension);
            Rect bounds = findForegroundBounds(scaled);
            Bitmap filled = fillTransparentNearest(scaled);
            Bitmap alpha = scaled.copy(Bitmap.Config.ARGB_8888, false);
            if (alpha == null) {
                filled.recycle();
                return null;
            }
            return new PreparedView(alpha, filled, bounds);
        } catch (Exception | OutOfMemoryError ignored) {
            if (scaled != null && !scaled.isRecycled()) {
                scaled.recycle();
                scaled = null;
            }
            if (isolated != null && !isolated.isRecycled()) {
                isolated.recycle();
                isolated = null;
            }
            Bitmap fallback = null;
            Bitmap fallbackScaled = null;
            try {
                fallback = contrastIsolation(frame);
                fallbackScaled = scaleForSampling(fallback, sampleDimension);
                Rect bounds = findForegroundBounds(fallbackScaled);
                Bitmap filled = fillTransparentNearest(fallbackScaled);
                Bitmap alpha = fallbackScaled.copy(Bitmap.Config.ARGB_8888, false);
                if (alpha == null) {
                    filled.recycle();
                    return null;
                }
                return new PreparedView(alpha, filled, bounds);
            } catch (RuntimeException | OutOfMemoryError fallbackError) {
                return null;
            } finally {
                if (fallbackScaled != null && !fallbackScaled.isRecycled()) {
                    fallbackScaled.recycle();
                }
                if (fallback != null && !fallback.isRecycled()) {
                    fallback.recycle();
                }
            }
        } finally {
            if (scaled != null && !scaled.isRecycled()) {
                scaled.recycle();
            }
            if (isolated != null && !isolated.isRecycled()) {
                isolated.recycle();
            }
        }
    }

    private static Bitmap scaleForSampling(Bitmap source, int maximumDimension) {
        int width = source.getWidth();
        int height = source.getHeight();
        float scale = maximumDimension / (float) Math.max(width, height);
        int targetWidth = Math.max(1, Math.round(width * scale));
        int targetHeight = Math.max(1, Math.round(height * scale));
        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true);
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
        int minimum = Math.max(48, width * height / 3000);
        if (right < left || bottom < top || foreground < minimum) {
            throw new IllegalArgumentException("Sujet vidéo trop petit");
        }
        return new Rect(left, top, right + 1, bottom + 1);
    }

    private static Bitmap fillTransparentNearest(Bitmap source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int count = width * height;
        int[] colors = new int[count];
        source.getPixels(colors, 0, width, 0, 0, width, height);
        int[] queue = new int[count];
        boolean[] visited = new boolean[count];
        int head = 0;
        int tail = 0;
        for (int index = 0; index < count; index++) {
            if (Color.alpha(colors[index]) > ALPHA_THRESHOLD) {
                colors[index] = 0xFF000000 | (colors[index] & 0x00FFFFFF);
                visited[index] = true;
                queue[tail++] = index;
            }
        }
        if (tail == 0) {
            throw new IllegalArgumentException("Texture vidéo vide");
        }
        while (head < tail) {
            int current = queue[head++];
            int x = current % width;
            int y = current / width;
            tail = spread(colors, visited, queue, tail, current, x - 1, y, width, height);
            tail = spread(colors, visited, queue, tail, current, x + 1, y, width, height);
            tail = spread(colors, visited, queue, tail, current, x, y - 1, width, height);
            tail = spread(colors, visited, queue, tail, current, x, y + 1, width, height);
        }
        Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        output.setPixels(colors, 0, width, 0, 0, width, height);
        return output;
    }

    private static int spread(
            int[] colors,
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
        if (visited[target]) {
            return tail;
        }
        visited[target] = true;
        colors[target] = colors[sourceIndex];
        queue[tail++] = target;
        return tail;
    }

    private static VerticalRange findSharedVerticalRange(PreparedView[] views) {
        float[] tops = new float[VIEW_COUNT];
        float[] bottoms = new float[VIEW_COUNT];
        for (int index = 0; index < VIEW_COUNT; index++) {
            PreparedView view = views[index];
            tops[index] = view.bounds.top
                    / (float) Math.max(1, view.alpha.getHeight() - 1);
            bottoms[index] = (view.bounds.bottom - 1)
                    / (float) Math.max(1, view.alpha.getHeight() - 1);
        }
        Arrays.sort(tops);
        Arrays.sort(bottoms);
        float top = (tops[2] + tops[3]) * 0.5f;
        float bottom = (bottoms[4] + bottoms[5]) * 0.5f;
        float margin = Math.max(0.008f, (bottom - top) * 0.018f);
        top = clamp(top - margin, 0.0f, 0.96f);
        bottom = clamp(bottom + margin, top + 0.04f, 1.0f);
        return new VerticalRange(top, bottom);
    }

    private static void extractStrips(
            Bitmap alpha,
            VerticalRange verticalRange,
            float[] left,
            float[] right
    ) {
        int width = alpha.getWidth();
        int height = alpha.getHeight();
        int[] pixels = new int[width * height];
        alpha.getPixels(pixels, 0, width, 0, 0, width, height);
        int band = Math.max(1, height / 220);
        for (int row = 0; row < left.length; row++) {
            float amount = row / (float) Math.max(1, left.length - 1);
            float normalizedY = verticalRange.top
                    + (verticalRange.bottom - verticalRange.top) * amount;
            int centerY = Math.max(0, Math.min(
                    height - 1,
                    Math.round(normalizedY * Math.max(1, height - 1))
            ));
            int minimumX = width;
            int maximumX = -1;
            for (int y = Math.max(0, centerY - band);
                 y <= Math.min(height - 1, centerY + band);
                 y++) {
                int offset = y * width;
                for (int x = 0; x < width; x++) {
                    if (Color.alpha(pixels[offset + x]) > ALPHA_THRESHOLD) {
                        minimumX = Math.min(minimumX, x);
                        maximumX = Math.max(maximumX, x);
                    }
                }
            }
            if (maximumX < minimumX) {
                left[row] = Float.NaN;
                right[row] = Float.NaN;
            } else {
                left[row] = pixelToStrip(minimumX, width);
                right[row] = pixelToStrip(maximumX, width);
            }
        }
    }

    private static float pixelToStrip(int x, int width) {
        return x / (float) Math.max(1, width - 1) * 2.0f - 1.0f;
    }

    private static void repairStripRows(float[] left, float[] right) {
        for (int row = 0; row < left.length; row++) {
            if (isValidStrip(left[row], right[row])) {
                continue;
            }
            int before = row - 1;
            while (before >= 0 && !isValidStrip(left[before], right[before])) {
                before--;
            }
            int after = row + 1;
            while (after < left.length && !isValidStrip(left[after], right[after])) {
                after++;
            }
            if (before >= 0 && after < left.length) {
                float amount = (row - before) / (float) (after - before);
                left[row] = lerp(left[before], left[after], amount);
                right[row] = lerp(right[before], right[after], amount);
            } else if (before >= 0) {
                left[row] = left[before];
                right[row] = right[before];
            } else if (after < left.length) {
                left[row] = left[after];
                right[row] = right[after];
            } else {
                left[row] = -0.05f;
                right[row] = 0.05f;
            }
        }
    }

    private static boolean isValidStrip(float left, float right) {
        return Float.isFinite(left)
                && Float.isFinite(right)
                && right - left >= 0.01f;
    }

    private static void smoothStrips(float[] left, float[] right, int passes) {
        for (int pass = 0; pass < passes; pass++) {
            float[] sourceLeft = Arrays.copyOf(left, left.length);
            float[] sourceRight = Arrays.copyOf(right, right.length);
            for (int row = 1; row < left.length - 1; row++) {
                left[row] = sourceLeft[row] * 0.60f
                        + (sourceLeft[row - 1] + sourceLeft[row + 1]) * 0.20f;
                right[row] = sourceRight[row] * 0.60f
                        + (sourceRight[row - 1] + sourceRight[row + 1]) * 0.20f;
                if (right[row] - left[row] < 0.012f) {
                    float center = (right[row] + left[row]) * 0.5f;
                    left[row] = center - 0.006f;
                    right[row] = center + 0.006f;
                }
            }
        }
    }

    private static void alignStripCenters(float[][] left, float[][] right) {
        float[] viewCenters = new float[VIEW_COUNT];
        float[] scratch = new float[left[0].length];
        for (int view = 0; view < VIEW_COUNT; view++) {
            for (int row = 0; row < left[view].length; row++) {
                scratch[row] = (left[view][row] + right[view][row]) * 0.5f;
            }
            viewCenters[view] = median(scratch);
        }
        float globalCenter = median(viewCenters);
        for (int view = 0; view < VIEW_COUNT; view++) {
            float offset = viewCenters[view] - globalCenter;
            for (int row = 0; row < left[view].length; row++) {
                left[view][row] -= offset;
                right[view][row] -= offset;
            }
        }
    }

    private static int repairWidthOutliers(float[][] left, float[][] right) {
        float[] medians = new float[VIEW_COUNT];
        float[] widths = new float[left[0].length];
        for (int view = 0; view < VIEW_COUNT; view++) {
            for (int row = 0; row < widths.length; row++) {
                widths[row] = right[view][row] - left[view][row];
            }
            medians[view] = median(widths);
        }
        float global = median(medians);
        int repaired = 0;
        for (int view = 0; view < VIEW_COUNT; view++) {
            if (medians[view] >= global * 0.42f
                    && medians[view] <= global * 2.10f) {
                continue;
            }
            int replacement = nearestHealthyView(medians, global, view);
            if (replacement < 0) {
                continue;
            }
            left[view] = Arrays.copyOf(left[replacement], left[replacement].length);
            right[view] = Arrays.copyOf(right[replacement], right[replacement].length);
            repaired++;
        }
        return repaired;
    }

    private static int nearestHealthyView(
            float[] medians,
            float global,
            int target
    ) {
        for (int distance = 1; distance < VIEW_COUNT; distance++) {
            int before = (target - distance + VIEW_COUNT) % VIEW_COUNT;
            if (medians[before] >= global * 0.42f
                    && medians[before] <= global * 2.10f) {
                return before;
            }
            int after = (target + distance) % VIEW_COUNT;
            if (medians[after] >= global * 0.42f
                    && medians[after] <= global * 2.10f) {
                return after;
            }
        }
        return -1;
    }

    private static float median(float[] values) {
        float[] copy = Arrays.copyOf(values, values.length);
        Arrays.sort(copy);
        int middle = copy.length / 2;
        return (copy.length & 1) == 0
                ? (copy[middle - 1] + copy[middle]) * 0.5f
                : copy[middle];
    }

    private static Bitmap bakeCylindricalTexture(
            PreparedView[] views,
            SilhouetteStripMesher.Sweep sweep,
            VerticalRange verticalRange,
            int textureWidth,
            int textureHeight
    ) {
        PixelSource[] sources = new PixelSource[VIEW_COUNT];
        for (int index = 0; index < VIEW_COUNT; index++) {
            sources[index] = PixelSource.from(views[index].filled);
        }
        int[] output = new int[textureWidth * textureHeight];
        for (int y = 0; y < textureHeight; y++) {
            float v = y / (float) Math.max(1, textureHeight - 1);
            float sourceY = verticalRange.top
                    + (verticalRange.bottom - verticalRange.top) * v;
            for (int x = 0; x < textureWidth; x++) {
                float u = x / (float) Math.max(1, textureWidth - 1);
                double surfaceAngle = Math.PI * 2.0 * u;
                float surfaceX = sweep.sampleX(u, v);
                float surfaceZ = sweep.sampleZ(u, v);
                float red = 0.0f;
                float green = 0.0f;
                float blue = 0.0f;
                float totalWeight = 0.0f;

                for (int view = 0; view < VIEW_COUNT; view++) {
                    double viewAngle = Math.PI * 2.0 * view / VIEW_COUNT;
                    float facing = (float) Math.cos(surfaceAngle - viewAngle);
                    if (facing <= 0.0f) {
                        continue;
                    }
                    float weight = facing * facing * facing * facing;
                    float projection = surfaceX * (float) Math.cos(viewAngle)
                            + surfaceZ * (float) Math.sin(viewAngle);
                    int color = sources[view].sample(
                            clamp((projection + 1.0f) * 0.5f, 0.0f, 1.0f),
                            sourceY
                    );
                    red += Color.red(color) * weight;
                    green += Color.green(color) * weight;
                    blue += Color.blue(color) * weight;
                    totalWeight += weight;
                }

                if (totalWeight <= 0.0f) {
                    int nearest = Math.floorMod(
                            Math.round(u * VIEW_COUNT),
                            VIEW_COUNT
                    );
                    output[y * textureWidth + x] = sources[nearest].sample(
                            0.5f,
                            sourceY
                    );
                } else {
                    output[y * textureWidth + x] = Color.rgb(
                            clampColor(Math.round(red / totalWeight)),
                            clampColor(Math.round(green / totalWeight)),
                            clampColor(Math.round(blue / totalWeight))
                    );
                }
            }
        }
        Bitmap texture = Bitmap.createBitmap(
                textureWidth,
                textureHeight,
                Bitmap.Config.ARGB_8888
        );
        texture.setPixels(
                output,
                0,
                textureWidth,
                0,
                0,
                textureWidth,
                textureHeight
        );
        return texture;
    }

    private static int clampColor(int value) {
        return Math.max(0, Math.min(255, value));
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
            pixels[index] = distance >= 28.0f
                    ? 0xFF000000 | (color & 0x00FFFFFF)
                    : Color.TRANSPARENT;
        }
        Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        output.setPixels(pixels, 0, width, 0, 0, width, height);
        return output;
    }

    private static int nearestPreparedIndex(PreparedView[] views, int target) {
        for (int distance = 1; distance < VIEW_COUNT; distance++) {
            int before = (target - distance + VIEW_COUNT) % VIEW_COUNT;
            if (views[before] != null) {
                return before;
            }
            int after = (target + distance) % VIEW_COUNT;
            if (views[after] != null) {
                return after;
            }
        }
        return -1;
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

    private static void recycleAll(PreparedView[] views) {
        for (PreparedView view : views) {
            if (view != null) {
                view.close();
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

    private static float lerp(float first, float second, float amount) {
        return first + (second - first) * amount;
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    @Override
    public void close() {
        // Les ressources neuronales sont fermées à la fin de chaque génération.
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

    private static final class PreparedView implements AutoCloseable {
        final Bitmap alpha;
        final Bitmap filled;
        final Rect bounds;

        PreparedView(Bitmap alpha, Bitmap filled, Rect bounds) {
            this.alpha = alpha;
            this.filled = filled;
            this.bounds = bounds;
        }

        PreparedView copy() {
            Bitmap alphaCopy = alpha.copy(Bitmap.Config.ARGB_8888, false);
            Bitmap filledCopy = filled.copy(Bitmap.Config.ARGB_8888, false);
            if (alphaCopy == null || filledCopy == null) {
                if (alphaCopy != null) {
                    alphaCopy.recycle();
                }
                if (filledCopy != null) {
                    filledCopy.recycle();
                }
                throw new IllegalStateException("Copie de vue vidéo impossible");
            }
            return new PreparedView(alphaCopy, filledCopy, new Rect(bounds));
        }

        @Override
        public void close() {
            if (!alpha.isRecycled()) {
                alpha.recycle();
            }
            if (!filled.isRecycled()) {
                filled.recycle();
            }
        }
    }

    private static final class VerticalRange {
        final float top;
        final float bottom;

        VerticalRange(float top, float bottom) {
            this.top = top;
            this.bottom = bottom;
        }
    }

    private static final class PixelSource {
        final int width;
        final int height;
        final int[] pixels;

        PixelSource(int width, int height, int[] pixels) {
            this.width = width;
            this.height = height;
            this.pixels = pixels;
        }

        static PixelSource from(Bitmap bitmap) {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            return new PixelSource(width, height, pixels);
        }

        int sample(float normalizedX, float normalizedY) {
            float x = clamp(normalizedX, 0.0f, 1.0f) * Math.max(1, width - 1);
            float y = clamp(normalizedY, 0.0f, 1.0f) * Math.max(1, height - 1);
            int x0 = Math.max(0, Math.min(width - 1, (int) Math.floor(x)));
            int y0 = Math.max(0, Math.min(height - 1, (int) Math.floor(y)));
            int x1 = Math.min(width - 1, x0 + 1);
            int y1 = Math.min(height - 1, y0 + 1);
            float tx = x - x0;
            float ty = y - y0;
            int top = blend(pixels[y0 * width + x0], pixels[y0 * width + x1], tx);
            int bottom = blend(pixels[y1 * width + x0], pixels[y1 * width + x1], tx);
            return blend(top, bottom, ty);
        }

        private static int blend(int first, int second, float amount) {
            return Color.rgb(
                    clampColor(Math.round(lerp(
                            Color.red(first),
                            Color.red(second),
                            amount
                    ))),
                    clampColor(Math.round(lerp(
                            Color.green(first),
                            Color.green(second),
                            amount
                    ))),
                    clampColor(Math.round(lerp(
                            Color.blue(first),
                            Color.blue(second),
                            amount
                    )))
            );
        }
    }

    private static final class Profile {
        final int rows;
        final int sectors;
        final int processors;
        final int sampleDimension;
        final int textureWidth;
        final int textureHeight;
        final String label;

        Profile(
                int rows,
                int sectors,
                int processors,
                int sampleDimension,
                int textureWidth,
                int textureHeight,
                String label
        ) {
            this.rows = rows;
            this.sectors = sectors;
            this.processors = processors;
            this.sampleDimension = sampleDimension;
            this.textureWidth = textureWidth;
            this.textureHeight = textureHeight;
            this.label = label;
        }

        static Profile from(DevicePerformanceProfile device) {
            int processors = Math.max(1, device.getProcessorCount());
            switch (device.getTier()) {
                case TURBO:
                    return new Profile(
                            176,
                            40,
                            processors,
                            576,
                            1024,
                            1024,
                            "Vidéo V4.9 Surface continue Turbo"
                    );
                case QUALITY:
                    return new Profile(
                            152,
                            36,
                            processors,
                            512,
                            896,
                            896,
                            "Vidéo V4.9 Surface continue Qualité"
                    );
                case COMPATIBILITY:
                default:
                    return new Profile(
                            128,
                            32,
                            processors,
                            448,
                            768,
                            768,
                            "Vidéo V4.9 Surface continue Compatible"
                    );
            }
        }
    }
}
