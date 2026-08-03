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

import com.chasmet.modeliseur3d.performance.DevicePerformanceProfile;

import java.util.Arrays;
import java.util.List;

/**
 * Moteur 2.5D local V5.
 *
 * Une image produit un relief fermé peu profond. Une vidéo de rotation utilise
 * quatre vues cardinales pour texturer la face, le dos et les côtés, sans
 * tenter de reconstruire une sculpture 3D complète.
 */
public final class Relief25DEngine implements AutoCloseable {
    private static final int VIDEO_VIEW_COUNT = 8;
    private static final int ALPHA_THRESHOLD = 24;

    private final Context context;
    private final DevicePerformanceProfile deviceProfile;

    public Relief25DEngine(
            Context context,
            DevicePerformanceProfile deviceProfile
    ) {
        this.context = context.getApplicationContext();
        this.deviceProfile = deviceProfile;
    }

    public Result generateImage(
            Bitmap source,
            ProgressListener listener
    ) throws Exception {
        if (source == null || source.isRecycled()) {
            throw new IllegalArgumentException("Image 2.5D absente");
        }
        long started = SystemClock.elapsedRealtime();
        Profile profile = Profile.from(deviceProfile);
        PreparedView front;
        String segmentationBackend;

        notifyProgress(listener, Stage.SEGMENTING, 0, 1);
        try (AnimeSegmentationEngine segmentation =
                     new AnimeSegmentationEngine(
                             context,
                             deviceProfile.getNeuralThreadCount()
                     )) {
            segmentationBackend = segmentation.getBackend();
            front = prepareView(source, segmentation, profile);
        }
        notifyProgress(listener, Stage.SEGMENTING, 1, 1);

        PreparedView back = null;
        PreparedView left = null;
        PreparedView right = null;
        try {
            back = front.mirrored();
            left = front.sideProxy(false);
            right = front.sideProxy(true);
            return buildResult(
                    front,
                    back,
                    left,
                    right,
                    profile.imageHalfDepth,
                    1,
                    front.detectedSubjectCount,
                    "Image",
                    segmentationBackend
                            + " • relief frontal fermé"
                            + " • dos miroir"
                            + " • côtés 2.5D générés",
                    started,
                    listener
            );
        } finally {
            front.close();
            closeQuietly(back);
            closeQuietly(left);
            closeQuietly(right);
        }
    }

    public Result generateVideo(
            List<Bitmap> frames,
            int decodedFrameCount,
            ProgressListener listener
    ) throws Exception {
        validateVideoFrames(frames);
        long started = SystemClock.elapsedRealtime();
        Profile profile = Profile.from(deviceProfile);
        PreparedView[] views = new PreparedView[VIDEO_VIEW_COUNT];
        int repaired = 0;
        String segmentationBackend;

        try (AnimeSegmentationEngine segmentation =
                     new AnimeSegmentationEngine(
                             context,
                             deviceProfile.getNeuralThreadCount()
                     )) {
            segmentationBackend = segmentation.getBackend();
            for (int index = 0; index < VIDEO_VIEW_COUNT; index++) {
                notifyProgress(
                        listener,
                        Stage.SEGMENTING,
                        index + 1,
                        VIDEO_VIEW_COUNT
                );
                try {
                    views[index] = prepareView(
                            frames.get(index),
                            segmentation,
                            profile
                    );
                } catch (Exception | OutOfMemoryError ignored) {
                    views[index] = prepareFallback(frames.get(index), profile);
                }
            }
        }

        for (int index = 0; index < VIDEO_VIEW_COUNT; index++) {
            if (views[index] != null) {
                continue;
            }
            int replacement = nearestPreparedIndex(views, index);
            if (replacement < 0) {
                recycleAll(views);
                throw new IllegalArgumentException(
                        "Aucune vue exploitable pour le relief 2.5D"
                );
            }
            views[index] = views[replacement].copy();
            repaired++;
        }

        try {
            notifyProgress(listener, Stage.ALIGNING, 0, 1);
            int phase = chooseFrontPhase(views);
            PreparedView front = views[phase];
            PreparedView back = views[(phase + 4) % VIDEO_VIEW_COUNT];
            PreparedView right = views[(phase + 2) % VIDEO_VIEW_COUNT];
            PreparedView left = views[(phase + 6) % VIDEO_VIEW_COUNT];

            float frontWidth = Math.max(
                    0.02f,
                    (front.averageWidth + back.averageWidth) * 0.5f
            );
            float sideWidth = Math.max(
                    0.01f,
                    (left.averageWidth + right.averageWidth) * 0.5f
            );
            float sideRatio = clamp(sideWidth / frontWidth, 0.20f, 1.0f);
            float halfDepth = clamp(
                    0.045f + sideRatio * 0.095f,
                    0.060f,
                    profile.maximumVideoHalfDepth
            );
            notifyProgress(listener, Stage.ALIGNING, 1, 1);

            String backend = segmentationBackend
                    + " • sélection automatique avant/arrière/profils"
                    + " • coque 2.5D fermée"
                    + " • atlas cardinal 2x2"
                    + " • rotation volontairement limitée"
                    + (repaired > 0
                    ? " • " + repaired + " vues remplacées"
                    : " • 8 vues valides");

            return buildResult(
                    front,
                    back,
                    left,
                    right,
                    halfDepth,
                    Math.min(VIDEO_VIEW_COUNT, decodedFrameCount),
                    maximumDetectedSubjects(views),
                    "Vidéo",
                    backend,
                    started,
                    listener
            );
        } finally {
            recycleAll(views);
        }
    }

    private Result buildResult(
            PreparedView front,
            PreparedView back,
            PreparedView left,
            PreparedView right,
            float halfDepth,
            int sourceViewCount,
            int detectedSubjectCount,
            String sourceLabel,
            String backend,
            long started,
            ProgressListener listener
    ) {
        Profile profile = Profile.from(deviceProfile);
        notifyProgress(listener, Stage.MESHING, 0, 1);
        Silhouette silhouette = extractSilhouette(front, profile.rows);
        Relief25DMesher.AtlasLayout atlasLayout =
                new Relief25DMesher.AtlasLayout(
                        profile.cellWidth,
                        profile.cellHeight,
                        profile.atlasPadding
                );
        Relief25DMesher.BuildResult built = Relief25DMesher.build(
                silhouette.left,
                silhouette.right,
                silhouette.topV,
                silhouette.bottomV,
                silhouette.aspectScale,
                halfDepth,
                profile.columns,
                atlasLayout
        );
        notifyProgress(listener, Stage.MESHING, 1, 1);

        notifyProgress(listener, Stage.TEXTURING, 0, 1);
        Bitmap atlas = buildAtlas(
                front.texture,
                back.texture,
                left.texture,
                right.texture,
                atlasLayout
        );
        notifyProgress(listener, Stage.TEXTURING, 1, 1);

        String quality = sourceLabel
                + " 2.5D " + profile.label
                + " • " + built.getRows() + " couches"
                + " • " + built.getColumns() + " colonnes"
                + " • épaisseur " + Math.round(halfDepth * 200.0f) + "%";
        return new Result(
                built.getMesh(),
                atlas,
                quality,
                backend,
                profile.processors,
                SystemClock.elapsedRealtime() - started,
                detectedSubjectCount,
                sourceViewCount,
                built.getRows(),
                built.getColumns(),
                halfDepth
        );
    }

    private static PreparedView prepareView(
            Bitmap source,
            AnimeSegmentationEngine segmentation,
            Profile profile
    ) throws Exception {
        Bitmap isolated = null;
        try {
            AnimeSegmentationEngine.Mask mask = segmentation.segment(source);
            isolated = NeuralSheetIsolator.isolate(source, mask);
            return normalizePrimarySubject(
                    isolated,
                    profile.cellWidth,
                    profile.cellHeight
            );
        } finally {
            if (isolated != null && !isolated.isRecycled()) {
                isolated.recycle();
            }
        }
    }

    private static PreparedView prepareFallback(
            Bitmap source,
            Profile profile
    ) {
        Bitmap isolated = null;
        try {
            isolated = contrastIsolation(source);
            return normalizePrimarySubject(
                    isolated,
                    profile.cellWidth,
                    profile.cellHeight
            );
        } catch (RuntimeException | OutOfMemoryError ignored) {
            return null;
        } finally {
            if (isolated != null && !isolated.isRecycled()) {
                isolated.recycle();
            }
        }
    }

    private static PreparedView normalizePrimarySubject(
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
        boolean[] initialMask = new boolean[pixels.length];
        for (int index = 0; index < pixels.length; index++) {
            initialMask[index] = Color.alpha(pixels[index]) > ALPHA_THRESHOLD;
        }
        SingleSubjectSelector.Selection selection =
                SingleSubjectSelector.select(
                        initialMask,
                        sourceWidth,
                        sourceHeight
                );
        boolean[] selected = selection.getMask();
        for (int index = 0; index < pixels.length; index++) {
            if (!selected[index]) {
                pixels[index] = Color.TRANSPARENT;
            }
        }
        Bitmap primary = Bitmap.createBitmap(
                sourceWidth,
                sourceHeight,
                Bitmap.Config.ARGB_8888
        );
        primary.setPixels(
                pixels,
                0,
                sourceWidth,
                0,
                0,
                sourceWidth,
                sourceHeight
        );

        int marginX = Math.max(
                2,
                Math.round((selection.getRight() - selection.getLeft() + 1) * 0.06f)
        );
        int marginY = Math.max(
                2,
                Math.round((selection.getBottom() - selection.getTop() + 1) * 0.035f)
        );
        Rect sourceRect = new Rect(
                Math.max(0, selection.getLeft() - marginX),
                Math.max(0, selection.getTop() - marginY),
                Math.min(sourceWidth, selection.getRight() + 1 + marginX),
                Math.min(sourceHeight, selection.getBottom() + 1 + marginY)
        );

        float scale = Math.min(
                targetWidth * 0.90f / Math.max(1, sourceRect.width()),
                targetHeight * 0.92f / Math.max(1, sourceRect.height())
        );
        int drawWidth = Math.max(1, Math.round(sourceRect.width() * scale));
        int drawHeight = Math.max(1, Math.round(sourceRect.height() * scale));
        int left = (targetWidth - drawWidth) / 2;
        int top = (targetHeight - drawHeight) / 2;

        Bitmap normalized = Bitmap.createBitmap(
                targetWidth,
                targetHeight,
                Bitmap.Config.ARGB_8888
        );
        Canvas canvas = new Canvas(normalized);
        canvas.drawColor(Color.TRANSPARENT);
        Paint paint = new Paint(
                Paint.ANTI_ALIAS_FLAG
                        | Paint.FILTER_BITMAP_FLAG
                        | Paint.DITHER_FLAG
        );
        canvas.drawBitmap(
                primary,
                sourceRect,
                new RectF(left, top, left + drawWidth, top + drawHeight),
                paint
        );
        primary.recycle();

        PreparedView prepared = analyzeNormalized(
                normalized,
                selection.getDetectedSubjectCount()
        );
        if (prepared.foregroundCount
                < Math.max(80, targetWidth * targetHeight / 2200)) {
            prepared.close();
            throw new IllegalArgumentException(
                    "Personnage 2.5D trop petit après détourage"
            );
        }
        return prepared;
    }

    private static PreparedView analyzeNormalized(
            Bitmap normalized,
            int detectedSubjectCount
    ) {
        int width = normalized.getWidth();
        int height = normalized.getHeight();
        int[] pixels = new int[width * height];
        normalized.getPixels(pixels, 0, width, 0, 0, width, height);
        boolean[] mask = new boolean[pixels.length];
        int left = width;
        int top = height;
        int right = -1;
        int bottom = -1;
        int foreground = 0;
        long widthSum = 0L;
        int rowsWithPixels = 0;

        for (int y = 0; y < height; y++) {
            int rowLeft = width;
            int rowRight = -1;
            int offset = y * width;
            for (int x = 0; x < width; x++) {
                boolean on = Color.alpha(pixels[offset + x]) > ALPHA_THRESHOLD;
                mask[offset + x] = on;
                if (!on) {
                    continue;
                }
                foreground++;
                left = Math.min(left, x);
                top = Math.min(top, y);
                right = Math.max(right, x);
                bottom = Math.max(bottom, y);
                rowLeft = Math.min(rowLeft, x);
                rowRight = Math.max(rowRight, x);
            }
            if (rowRight >= rowLeft) {
                widthSum += rowRight - rowLeft + 1L;
                rowsWithPixels++;
            }
        }
        if (right < left || bottom < top) {
            normalized.recycle();
            throw new IllegalArgumentException("Silhouette 2.5D vide");
        }
        return new PreparedView(
                normalized,
                mask,
                new Rect(left, top, right + 1, bottom + 1),
                foreground,
                widthSum / (float) Math.max(1, rowsWithPixels),
                Math.max(1, detectedSubjectCount)
        );
    }

    private static Silhouette extractSilhouette(
            PreparedView view,
            int rows
    ) {
        int width = view.texture.getWidth();
        int height = view.texture.getHeight();
        float[] left = new float[rows];
        float[] right = new float[rows];
        Arrays.fill(left, Float.NaN);
        Arrays.fill(right, Float.NaN);

        int top = view.bounds.top;
        int bottom = Math.max(top + 1, view.bounds.bottom - 1);
        int band = Math.max(1, height / 260);
        for (int row = 0; row < rows; row++) {
            float amount = row / (float) Math.max(1, rows - 1);
            int centerY = Math.max(
                    0,
                    Math.min(
                            height - 1,
                            Math.round(lerp(top, bottom, amount))
                    )
            );
            int minimumX = width;
            int maximumX = -1;
            for (int y = Math.max(0, centerY - band);
                 y <= Math.min(height - 1, centerY + band);
                 y++) {
                int offset = y * width;
                for (int x = 0; x < width; x++) {
                    if (view.mask[offset + x]) {
                        minimumX = Math.min(minimumX, x);
                        maximumX = Math.max(maximumX, x);
                    }
                }
            }
            if (maximumX >= minimumX) {
                left[row] = minimumX
                        / (float) Math.max(1, width - 1) * 2.0f - 1.0f;
                right[row] = maximumX
                        / (float) Math.max(1, width - 1) * 2.0f - 1.0f;
            }
        }
        repairRows(left, right);
        smoothRows(left, right, 1);

        float topV = top / (float) Math.max(1, height - 1);
        float bottomV = bottom / (float) Math.max(1, height - 1);
        float verticalSpan = Math.max(0.04f, bottomV - topV);
        float aspectScale = (width / (float) height) / verticalSpan;
        return new Silhouette(
                left,
                right,
                topV,
                bottomV,
                aspectScale
        );
    }

    private static Bitmap buildAtlas(
            Bitmap front,
            Bitmap back,
            Bitmap left,
            Bitmap right,
            Relief25DMesher.AtlasLayout layout
    ) {
        Bitmap atlas = Bitmap.createBitmap(
                layout.getAtlasWidth(),
                layout.getAtlasHeight(),
                Bitmap.Config.ARGB_8888
        );
        Canvas canvas = new Canvas(atlas);
        canvas.drawColor(Color.TRANSPARENT);
        Paint paint = new Paint(
                Paint.ANTI_ALIAS_FLAG
                        | Paint.FILTER_BITMAP_FLAG
                        | Paint.DITHER_FLAG
        );
        drawCell(canvas, front, 0, 0, layout, paint);
        drawCell(canvas, back, 1, 0, layout, paint);
        drawCell(canvas, left, 0, 1, layout, paint);
        drawCell(canvas, right, 1, 1, layout, paint);
        return atlas;
    }

    private static void drawCell(
            Canvas canvas,
            Bitmap source,
            int column,
            int row,
            Relief25DMesher.AtlasLayout layout,
            Paint paint
    ) {
        int left = column * layout.getCellWidth();
        int top = row * layout.getCellHeight();
        canvas.drawBitmap(
                source,
                null,
                new RectF(
                        left,
                        top,
                        left + layout.getCellWidth(),
                        top + layout.getCellHeight()
                ),
                paint
        );
    }

    private static int chooseFrontPhase(PreparedView[] views) {
        int best = 0;
        float bestScore = Float.NEGATIVE_INFINITY;
        for (int candidate = 0; candidate < 4; candidate++) {
            float facing = views[candidate].averageWidth
                    + views[(candidate + 4) % VIDEO_VIEW_COUNT].averageWidth;
            float sides = views[(candidate + 2) % VIDEO_VIEW_COUNT].averageWidth
                    + views[(candidate + 6) % VIDEO_VIEW_COUNT].averageWidth;
            float score = facing - sides * 0.58f;
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private static int maximumDetectedSubjects(PreparedView[] views) {
        int maximum = 1;
        for (PreparedView view : views) {
            maximum = Math.max(maximum, view.detectedSubjectCount);
        }
        return maximum;
    }

    private static int nearestPreparedIndex(
            PreparedView[] views,
            int target
    ) {
        for (int distance = 1; distance < VIDEO_VIEW_COUNT; distance++) {
            int before = (target - distance + VIDEO_VIEW_COUNT)
                    % VIDEO_VIEW_COUNT;
            int after = (target + distance) % VIDEO_VIEW_COUNT;
            if (views[before] != null) {
                return before;
            }
            if (views[after] != null) {
                return after;
            }
        }
        return -1;
    }

    private static Bitmap mirror(Bitmap source) {
        Matrix matrix = new Matrix();
        matrix.setScale(-1.0f, 1.0f);
        return Bitmap.createBitmap(
                source,
                0,
                0,
                source.getWidth(),
                source.getHeight(),
                matrix,
                true
        );
    }

    private static Bitmap sideProxy(Bitmap source, boolean mirror) {
        Bitmap output = Bitmap.createBitmap(
                source.getWidth(),
                source.getHeight(),
                Bitmap.Config.ARGB_8888
        );
        Canvas canvas = new Canvas(output);
        canvas.drawColor(Color.TRANSPARENT);
        Bitmap input = mirror ? mirror(source) : source;
        try {
            float width = source.getWidth() * 0.42f;
            float left = (source.getWidth() - width) * 0.5f;
            Paint paint = new Paint(
                    Paint.ANTI_ALIAS_FLAG
                            | Paint.FILTER_BITMAP_FLAG
                            | Paint.DITHER_FLAG
            );
            canvas.drawBitmap(
                    input,
                    null,
                    new RectF(
                            left,
                            0.0f,
                            left + width,
                            source.getHeight()
                    ),
                    paint
            );
        } finally {
            if (input != source && !input.isRecycled()) {
                input.recycle();
            }
        }
        return output;
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
        Bitmap output = Bitmap.createBitmap(
                width,
                height,
                Bitmap.Config.ARGB_8888
        );
        output.setPixels(pixels, 0, width, 0, 0, width, height);
        return output;
    }

    private static void repairRows(float[] left, float[] right) {
        for (int row = 0; row < left.length; row++) {
            if (valid(left[row], right[row])) {
                continue;
            }
            int before = row - 1;
            while (before >= 0 && !valid(left[before], right[before])) {
                before--;
            }
            int after = row + 1;
            while (after < left.length && !valid(left[after], right[after])) {
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
                left[row] = -0.04f;
                right[row] = 0.04f;
            }
        }
    }

    private static void smoothRows(
            float[] left,
            float[] right,
            int passes
    ) {
        for (int pass = 0; pass < passes; pass++) {
            float[] sourceLeft = Arrays.copyOf(left, left.length);
            float[] sourceRight = Arrays.copyOf(right, right.length);
            for (int row = 1; row < left.length - 1; row++) {
                left[row] = sourceLeft[row] * 0.62f
                        + (sourceLeft[row - 1] + sourceLeft[row + 1]) * 0.19f;
                right[row] = sourceRight[row] * 0.62f
                        + (sourceRight[row - 1] + sourceRight[row + 1]) * 0.19f;
            }
        }
    }

    private static boolean valid(float left, float right) {
        return Float.isFinite(left)
                && Float.isFinite(right)
                && right - left >= 0.01f;
    }

    private static void validateVideoFrames(List<Bitmap> frames) {
        if (frames == null || frames.size() != VIDEO_VIEW_COUNT) {
            throw new IllegalArgumentException(
                    "Huit vues vidéo sont requises pour le 2.5D"
            );
        }
        for (Bitmap frame : frames) {
            if (frame == null || frame.isRecycled()) {
                throw new IllegalArgumentException(
                        "Une vue vidéo 2.5D est invalide"
                );
            }
        }
    }

    private static void recycleAll(PreparedView[] views) {
        for (PreparedView view : views) {
            closeQuietly(view);
        }
    }

    private static void closeQuietly(PreparedView view) {
        if (view != null) {
            view.close();
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
        // Les sessions de segmentation sont fermées après chaque génération.
    }

    public enum Stage {
        SEGMENTING,
        ALIGNING,
        MESHING,
        TEXTURING
    }

    public interface ProgressListener {
        void onProgress(Stage stage, int current, int total);
    }

    public static final class Result {
        private final MeshData mesh;
        private final Bitmap texture;
        private final String qualityLabel;
        private final String backend;
        private final int processorCount;
        private final long totalDurationMs;
        private final int detectedSubjectCount;
        private final int sourceViewCount;
        private final int rows;
        private final int columns;
        private final float halfDepth;

        Result(
                MeshData mesh,
                Bitmap texture,
                String qualityLabel,
                String backend,
                int processorCount,
                long totalDurationMs,
                int detectedSubjectCount,
                int sourceViewCount,
                int rows,
                int columns,
                float halfDepth
        ) {
            this.mesh = mesh;
            this.texture = texture;
            this.qualityLabel = qualityLabel;
            this.backend = backend;
            this.processorCount = processorCount;
            this.totalDurationMs = totalDurationMs;
            this.detectedSubjectCount = detectedSubjectCount;
            this.sourceViewCount = sourceViewCount;
            this.rows = rows;
            this.columns = columns;
            this.halfDepth = halfDepth;
        }

        public MeshData getMesh() {
            return mesh;
        }

        public Bitmap getTexture() {
            return texture;
        }

        public String getQualityLabel() {
            return qualityLabel;
        }

        public String getBackend() {
            return backend;
        }

        public int getProcessorCount() {
            return processorCount;
        }

        public long getTotalDurationMs() {
            return totalDurationMs;
        }

        public int getDetectedSubjectCount() {
            return detectedSubjectCount;
        }

        public int getSourceViewCount() {
            return sourceViewCount;
        }

        public int getRows() {
            return rows;
        }

        public int getColumns() {
            return columns;
        }

        public float getHalfDepth() {
            return halfDepth;
        }
    }

    private static final class PreparedView implements AutoCloseable {
        final Bitmap texture;
        final boolean[] mask;
        final Rect bounds;
        final int foregroundCount;
        final float averageWidth;
        final int detectedSubjectCount;

        PreparedView(
                Bitmap texture,
                boolean[] mask,
                Rect bounds,
                int foregroundCount,
                float averageWidth,
                int detectedSubjectCount
        ) {
            this.texture = texture;
            this.mask = mask;
            this.bounds = bounds;
            this.foregroundCount = foregroundCount;
            this.averageWidth = averageWidth;
            this.detectedSubjectCount = detectedSubjectCount;
        }

        PreparedView copy() {
            Bitmap bitmap = texture.copy(Bitmap.Config.ARGB_8888, false);
            if (bitmap == null) {
                throw new IllegalStateException(
                        "Copie de vue 2.5D impossible"
                );
            }
            return new PreparedView(
                    bitmap,
                    Arrays.copyOf(mask, mask.length),
                    new Rect(bounds),
                    foregroundCount,
                    averageWidth,
                    detectedSubjectCount
            );
        }

        PreparedView mirrored() {
            Bitmap bitmap = mirror(texture);
            return analyzeNormalized(bitmap, detectedSubjectCount);
        }

        PreparedView sideProxy(boolean mirrored) {
            Bitmap bitmap = Relief25DEngine.sideProxy(texture, mirrored);
            return analyzeNormalized(bitmap, detectedSubjectCount);
        }

        @Override
        public void close() {
            if (!texture.isRecycled()) {
                texture.recycle();
            }
        }
    }

    private static final class Silhouette {
        final float[] left;
        final float[] right;
        final float topV;
        final float bottomV;
        final float aspectScale;

        Silhouette(
                float[] left,
                float[] right,
                float topV,
                float bottomV,
                float aspectScale
        ) {
            this.left = left;
            this.right = right;
            this.topV = topV;
            this.bottomV = bottomV;
            this.aspectScale = aspectScale;
        }
    }

    private static final class Profile {
        final int cellWidth;
        final int cellHeight;
        final int rows;
        final int columns;
        final int atlasPadding;
        final int processors;
        final float imageHalfDepth;
        final float maximumVideoHalfDepth;
        final String label;

        Profile(
                int cellWidth,
                int cellHeight,
                int rows,
                int columns,
                int atlasPadding,
                int processors,
                float imageHalfDepth,
                float maximumVideoHalfDepth,
                String label
        ) {
            this.cellWidth = cellWidth;
            this.cellHeight = cellHeight;
            this.rows = rows;
            this.columns = columns;
            this.atlasPadding = atlasPadding;
            this.processors = processors;
            this.imageHalfDepth = imageHalfDepth;
            this.maximumVideoHalfDepth = maximumVideoHalfDepth;
            this.label = label;
        }

        static Profile from(DevicePerformanceProfile device) {
            int processors = Math.max(1, device.getProcessorCount());
            switch (device.getTier()) {
                case TURBO:
                    return new Profile(
                            512,
                            768,
                            128,
                            28,
                            5,
                            processors,
                            0.105f,
                            0.150f,
                            "Turbo"
                    );
                case QUALITY:
                    return new Profile(
                            448,
                            672,
                            104,
                            24,
                            4,
                            processors,
                            0.100f,
                            0.142f,
                            "Qualité"
                    );
                case COMPATIBILITY:
                default:
                    return new Profile(
                            384,
                            576,
                            80,
                            20,
                            4,
                            processors,
                            0.092f,
                            0.132f,
                            "Compatible"
                    );
            }
        }
    }
}
