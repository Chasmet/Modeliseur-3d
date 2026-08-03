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

/**
 * Vrai moteur Face/Dos 2.5D V5.2.
 *
 * Les deux images sont détourées dans la même session, normalisées sur le même
 * cadre, puis utilisées ensemble pour construire une silhouette commune. Les
 * profils sont créés depuis les couleurs des bords réels au lieu d'écraser une
 * image complète en une bande verticale.
 */
public final class FaceBack25DEngine implements AutoCloseable {
    private static final int ALPHA_THRESHOLD = 24;

    private final Context context;
    private final DevicePerformanceProfile deviceProfile;

    public FaceBack25DEngine(
            Context context,
            DevicePerformanceProfile deviceProfile
    ) {
        this.context = context.getApplicationContext();
        this.deviceProfile = deviceProfile;
    }

    public Relief25DEngine.Result generate(
            Bitmap frontSource,
            Bitmap backSource,
            Relief25DEngine.ProgressListener listener
    ) throws Exception {
        validateSource(frontSource, "Image de face absente");
        if (backSource != null) {
            validateSource(backSource, "Image de dos absente");
        }

        long started = SystemClock.elapsedRealtime();
        Profile profile = Profile.from(deviceProfile);
        Prepared front = null;
        Prepared back = null;
        Prepared alignedBack = null;
        Bitmap generatedBack = null;
        Bitmap leftSide = null;
        Bitmap rightSide = null;
        String backend;

        try (AnimeSegmentationEngine segmentation =
                     new AnimeSegmentationEngine(
                             context,
                             deviceProfile.getNeuralThreadCount()
                     )) {
            backend = segmentation.getBackend();
            notifyProgress(listener, Relief25DEngine.Stage.SEGMENTING, 0,
                    backSource == null ? 1 : 2);
            front = prepare(frontSource, segmentation, profile);
            notifyProgress(listener, Relief25DEngine.Stage.SEGMENTING, 1,
                    backSource == null ? 1 : 2);

            if (backSource == null) {
                generatedBack = mirror(frontSource);
                try {
                    back = prepare(generatedBack, segmentation, profile);
                } catch (Exception error) {
                    back = front.mirroredCopy();
                }
            } else {
                back = prepare(backSource, segmentation, profile);
                notifyProgress(listener, Relief25DEngine.Stage.SEGMENTING, 2, 2);
            }
        } catch (Exception firstError) {
            closeQuietly(front);
            closeQuietly(back);
            front = prepareFallback(frontSource, profile);
            if (front == null) {
                throw firstError;
            }
            if (backSource == null) {
                back = front.mirroredCopy();
            } else {
                back = prepareFallback(backSource, profile);
                if (back == null) {
                    throw firstError;
                }
            }
            backend = "Détourage de secours local";
        } finally {
            if (generatedBack != null && !generatedBack.isRecycled()) {
                generatedBack.recycle();
            }
        }

        try {
            notifyProgress(listener, Relief25DEngine.Stage.ALIGNING, 0, 1);
            alignedBack = alignToReference(back, front);
            back.close();
            back = alignedBack;
            alignedBack = null;

            Silhouette frontSilhouette = extractSilhouette(front, profile.rows);
            Silhouette backSilhouette = extractSilhouette(back, profile.rows);
            Silhouette merged = mergeSilhouettes(frontSilhouette, backSilhouette);
            notifyProgress(listener, Relief25DEngine.Stage.ALIGNING, 1, 1);

            notifyProgress(listener, Relief25DEngine.Stage.TEXTURING, 0, 1);
            leftSide = buildSideTexture(front.texture, back.texture, true);
            rightSide = buildSideTexture(front.texture, back.texture, false);

            FaceBack25DMesher.AtlasLayout layout =
                    new FaceBack25DMesher.AtlasLayout(
                            profile.cellWidth,
                            profile.cellHeight,
                            profile.padding
                    );
            Bitmap atlas = buildAtlas(
                    front.texture,
                    back.texture,
                    leftSide,
                    rightSide,
                    layout
            );
            notifyProgress(listener, Relief25DEngine.Stage.TEXTURING, 1, 1);

            notifyProgress(listener, Relief25DEngine.Stage.MESHING, 0, 1);
            FaceBack25DMesher.BuildResult built = FaceBack25DMesher.build(
                    merged.left,
                    merged.right,
                    merged.topV,
                    merged.bottomV,
                    merged.aspectScale,
                    profile.halfDepth,
                    profile.columns,
                    layout
            );
            notifyProgress(listener, Relief25DEngine.Stage.MESHING, 1, 1);

            int sourceCount = backSource == null ? 1 : 2;
            String label = sourceCount == 2 ? "Face + Dos réel" : "Face seule";
            String quality = label
                    + " 2.5D V5.2 " + profile.label
                    + " • " + built.getRows() + " couches"
                    + " • " + built.getColumns() + " colonnes"
                    + " • épaisseur renforcée "
                    + Math.round(profile.halfDepth * 200.0f) + "%";
            String details = backend
                    + " • alignement Face/Dos commun"
                    + " • silhouette moyenne stabilisée"
                    + " • profils calculés depuis les bords réels"
                    + " • UV verticaux corrigés"
                    + " • texture avant et arrière distinctes";

            return new Relief25DEngine.Result(
                    built.getMesh(),
                    atlas,
                    quality,
                    details,
                    profile.processors,
                    SystemClock.elapsedRealtime() - started,
                    Math.max(front.detectedSubjectCount, back.detectedSubjectCount),
                    sourceCount,
                    built.getRows(),
                    built.getColumns(),
                    profile.halfDepth
            );
        } finally {
            closeQuietly(front);
            closeQuietly(back);
            closeQuietly(alignedBack);
            recycle(leftSide);
            recycle(rightSide);
        }
    }

    private static Prepared prepare(
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
            recycle(isolated);
        }
    }

    private static Prepared prepareFallback(
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
            recycle(isolated);
        }
    }

    private static Prepared normalizePrimarySubject(
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

        int subjectWidth = selection.getRight() - selection.getLeft() + 1;
        int subjectHeight = selection.getBottom() - selection.getTop() + 1;
        int marginX = Math.max(2, Math.round(subjectWidth * 0.055f));
        int marginY = Math.max(2, Math.round(subjectHeight * 0.035f));
        Rect sourceRect = new Rect(
                Math.max(0, selection.getLeft() - marginX),
                Math.max(0, selection.getTop() - marginY),
                Math.min(sourceWidth, selection.getRight() + 1 + marginX),
                Math.min(sourceHeight, selection.getBottom() + 1 + marginY)
        );

        float scale = Math.min(
                targetWidth * 0.92f / Math.max(1, sourceRect.width()),
                targetHeight * 0.94f / Math.max(1, sourceRect.height())
        );
        int drawWidth = Math.max(1, Math.round(sourceRect.width() * scale));
        int drawHeight = Math.max(1, Math.round(sourceRect.height() * scale));
        int targetLeft = (targetWidth - drawWidth) / 2;
        int targetTop = (targetHeight - drawHeight) / 2;

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
                new RectF(
                        targetLeft,
                        targetTop,
                        targetLeft + drawWidth,
                        targetTop + drawHeight
                ),
                paint
        );
        primary.recycle();

        Prepared prepared = analyzeNormalized(
                normalized,
                selection.getDetectedSubjectCount()
        );
        if (prepared.foregroundCount
                < Math.max(80, targetWidth * targetHeight / 2200)) {
            prepared.close();
            throw new IllegalArgumentException(
                    "Personnage trop petit après détourage"
            );
        }
        return prepared;
    }

    private static Prepared alignToReference(
            Prepared source,
            Prepared reference
    ) {
        int width = reference.texture.getWidth();
        int height = reference.texture.getHeight();
        Bitmap aligned = Bitmap.createBitmap(
                width,
                height,
                Bitmap.Config.ARGB_8888
        );
        Canvas canvas = new Canvas(aligned);
        canvas.drawColor(Color.TRANSPARENT);
        Paint paint = new Paint(
                Paint.ANTI_ALIAS_FLAG
                        | Paint.FILTER_BITMAP_FLAG
                        | Paint.DITHER_FLAG
        );

        Rect destination = new Rect(reference.bounds);
        int expandX = Math.max(1, Math.round(destination.width() * 0.01f));
        destination.left = Math.max(0, destination.left - expandX);
        destination.right = Math.min(width, destination.right + expandX);
        canvas.drawBitmap(
                source.texture,
                source.bounds,
                destination,
                paint
        );
        return analyzeNormalized(aligned, source.detectedSubjectCount);
    }

    private static Prepared analyzeNormalized(
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

        for (int y = 0; y < height; y++) {
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
            }
        }
        if (right < left || bottom < top) {
            normalized.recycle();
            throw new IllegalArgumentException("Silhouette Face/Dos vide");
        }
        return new Prepared(
                normalized,
                mask,
                new Rect(left, top, right + 1, bottom + 1),
                foreground,
                Math.max(1, detectedSubjectCount)
        );
    }

    private static Silhouette extractSilhouette(
            Prepared view,
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
        return new Silhouette(left, right, topV, bottomV, aspectScale);
    }

    private static Silhouette mergeSilhouettes(
            Silhouette front,
            Silhouette back
    ) {
        int rows = front.left.length;
        float[] left = new float[rows];
        float[] right = new float[rows];
        for (int row = 0; row < rows; row++) {
            float frontCenter = (front.left[row] + front.right[row]) * 0.5f;
            float backCenter = (back.left[row] + back.right[row]) * 0.5f;
            float frontHalf = (front.right[row] - front.left[row]) * 0.5f;
            float backHalf = (back.right[row] - back.left[row]) * 0.5f;
            float large = Math.max(frontHalf, backHalf);
            float small = Math.min(frontHalf, backHalf);
            float center = (frontCenter + backCenter) * 0.5f;
            float half = large * 0.68f + small * 0.32f;
            left[row] = center - half;
            right[row] = center + half;
        }
        smoothRows(left, right, 2);
        return new Silhouette(
                left,
                right,
                (front.topV + back.topV) * 0.5f,
                (front.bottomV + back.bottomV) * 0.5f,
                (front.aspectScale + back.aspectScale) * 0.5f
        );
    }

    private static Bitmap buildSideTexture(
            Bitmap front,
            Bitmap back,
            boolean leftSide
    ) {
        int width = front.getWidth();
        int height = front.getHeight();
        int[] frontPixels = new int[width * height];
        int[] backPixels = new int[width * height];
        int[] output = new int[width * height];
        front.getPixels(frontPixels, 0, width, 0, 0, width, height);
        back.getPixels(backPixels, 0, width, 0, 0, width, height);
        int inset = Math.max(1, width / 256);

        for (int y = 0; y < height; y++) {
            int frontEdge = findNearestEdge(
                    frontPixels,
                    width,
                    height,
                    y,
                    leftSide
            );
            int backEdge = findNearestEdge(
                    backPixels,
                    width,
                    height,
                    y,
                    !leftSide
            );
            if (frontEdge < 0 && backEdge < 0) {
                continue;
            }
            if (frontEdge >= 0) {
                frontEdge = clampInt(
                        frontEdge + (leftSide ? inset : -inset),
                        0,
                        width - 1
                );
            }
            if (backEdge >= 0) {
                backEdge = clampInt(
                        backEdge + (!leftSide ? inset : -inset),
                        0,
                        width - 1
                );
            }

            int frontColor = frontEdge >= 0
                    ? frontPixels[y * width + frontEdge]
                    : backPixels[y * width + backEdge];
            int backColor = backEdge >= 0
                    ? backPixels[y * width + backEdge]
                    : frontColor;
            for (int x = 0; x < width; x++) {
                float amount = x / (float) Math.max(1, width - 1);
                output[y * width + x] = mixColor(
                        frontColor,
                        backColor,
                        amount
                );
            }
        }

        Bitmap result = Bitmap.createBitmap(
                width,
                height,
                Bitmap.Config.ARGB_8888
        );
        result.setPixels(output, 0, width, 0, 0, width, height);
        return result;
    }

    private static int findNearestEdge(
            int[] pixels,
            int width,
            int height,
            int centerY,
            boolean fromLeft
    ) {
        for (int distance = 0; distance <= 3; distance++) {
            int before = centerY - distance;
            if (before >= 0) {
                int edge = findEdge(pixels, width, before, fromLeft);
                if (edge >= 0) {
                    return edge;
                }
            }
            int after = centerY + distance;
            if (distance > 0 && after < height) {
                int edge = findEdge(pixels, width, after, fromLeft);
                if (edge >= 0) {
                    return edge;
                }
            }
        }
        return -1;
    }

    private static int findEdge(
            int[] pixels,
            int width,
            int y,
            boolean fromLeft
    ) {
        int offset = y * width;
        if (fromLeft) {
            for (int x = 0; x < width; x++) {
                if (Color.alpha(pixels[offset + x]) > ALPHA_THRESHOLD) {
                    return x;
                }
            }
        } else {
            for (int x = width - 1; x >= 0; x--) {
                if (Color.alpha(pixels[offset + x]) > ALPHA_THRESHOLD) {
                    return x;
                }
            }
        }
        return -1;
    }

    private static int mixColor(int first, int second, float amount) {
        amount = clamp(amount, 0.0f, 1.0f);
        int alpha = Math.max(Color.alpha(first), Color.alpha(second));
        int red = Math.round(lerp(Color.red(first), Color.red(second), amount));
        int green = Math.round(lerp(Color.green(first), Color.green(second), amount));
        int blue = Math.round(lerp(Color.blue(first), Color.blue(second), amount));
        return Color.argb(alpha, red, green, blue);
    }

    private static Bitmap buildAtlas(
            Bitmap front,
            Bitmap back,
            Bitmap left,
            Bitmap right,
            FaceBack25DMesher.AtlasLayout layout
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
            FaceBack25DMesher.AtlasLayout layout,
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
                left[row] = sourceLeft[row] * 0.64f
                        + (sourceLeft[row - 1] + sourceLeft[row + 1]) * 0.18f;
                right[row] = sourceRight[row] * 0.64f
                        + (sourceRight[row - 1] + sourceRight[row + 1]) * 0.18f;
            }
        }
    }

    private static boolean valid(float left, float right) {
        return Float.isFinite(left)
                && Float.isFinite(right)
                && right - left >= 0.01f;
    }

    private static void validateSource(Bitmap source, String message) {
        if (source == null || source.isRecycled()) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void notifyProgress(
            Relief25DEngine.ProgressListener listener,
            Relief25DEngine.Stage stage,
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

    private static int clampInt(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }

    private static void closeQuietly(Prepared prepared) {
        if (prepared != null) {
            prepared.close();
        }
    }

    @Override
    public void close() {
        // Les sessions ONNX sont fermées à la fin de chaque génération.
    }

    private static final class Prepared implements AutoCloseable {
        final Bitmap texture;
        final boolean[] mask;
        final Rect bounds;
        final int foregroundCount;
        final int detectedSubjectCount;

        Prepared(
                Bitmap texture,
                boolean[] mask,
                Rect bounds,
                int foregroundCount,
                int detectedSubjectCount
        ) {
            this.texture = texture;
            this.mask = mask;
            this.bounds = bounds;
            this.foregroundCount = foregroundCount;
            this.detectedSubjectCount = detectedSubjectCount;
        }

        Prepared mirroredCopy() {
            Bitmap bitmap = mirror(texture);
            return analyzeNormalized(bitmap, detectedSubjectCount);
        }

        @Override
        public void close() {
            recycle(texture);
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
        final int padding;
        final int processors;
        final float halfDepth;
        final String label;

        Profile(
                int cellWidth,
                int cellHeight,
                int rows,
                int columns,
                int padding,
                int processors,
                float halfDepth,
                String label
        ) {
            this.cellWidth = cellWidth;
            this.cellHeight = cellHeight;
            this.rows = rows;
            this.columns = columns;
            this.padding = padding;
            this.processors = processors;
            this.halfDepth = halfDepth;
            this.label = label;
        }

        static Profile from(DevicePerformanceProfile device) {
            int processors = Math.max(1, device.getProcessorCount());
            switch (device.getTier()) {
                case TURBO:
                    return new Profile(
                            512,
                            768,
                            144,
                            32,
                            5,
                            processors,
                            0.190f,
                            "Turbo"
                    );
                case QUALITY:
                    return new Profile(
                            448,
                            672,
                            120,
                            28,
                            4,
                            processors,
                            0.178f,
                            "Qualité"
                    );
                case COMPATIBILITY:
                default:
                    return new Profile(
                            384,
                            576,
                            92,
                            24,
                            4,
                            processors,
                            0.162f,
                            "Compatible"
                    );
            }
        }
    }
}
