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

import java.util.List;

/**
 * Moteur 3D local V7.2 multi-formes réservé au mode quatre vues.
 *
 * La V6 conserve les corrections de profil éprouvées, mais remplace le volume
 * binaire par un champ de distances multivue continu. La V7.2 exécute Depth
 * Anything 3 Small : les quatre vues passent ensemble dans le réseau, puis les
 * profondeurs cohérentes sculptent la coque sans pouvoir dépasser les
 * silhouettes vérifiées. Le moteur 2.5D reste totalement séparé.
 */
public final class StylizedCharacter3DEngine implements AutoCloseable {
    public static final int REQUIRED_VIEW_COUNT = 4;
    private static final float FOREGROUND_ALPHA = 24.0f;
    private static final int MAXIMUM_COMPONENTS = 16;
    private static final int QUALITY_WIDTH = 96;
    private static final int QUALITY_HEIGHT = 128;

    private final Context context;

    public StylizedCharacter3DEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    public Result generate(
            List<Bitmap> views,
            float depthMultiplier,
            ProgressListener listener
    ) throws Exception {
        return generate(
                views,
                depthMultiplier,
                SubjectCategory.AUTO,
                listener
        );
    }

    public Result generate(
            List<Bitmap> views,
            float depthMultiplier,
            SubjectCategory requestedCategory,
            ProgressListener listener
    ) throws Exception {
        validateViews(views);
        if (requestedCategory == null) {
            requestedCategory = SubjectCategory.AUTO;
        }
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

        ProfileRecovery profileRecovery;
        try {
            profileRecovery = recoverUnusableProfile(isolated, bounds);
        } catch (RuntimeException | OutOfMemoryError error) {
            recycleAll(isolated);
            throw error;
        }

        Profile profile = Profile.detect(depthMultiplier, bounds, requestedCategory);
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

        SubjectCategory category = SubjectCategoryClassifier.resolve(
                requestedCategory,
                masks,
                profile.width,
                profile.height,
                profile.depth
        );

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

        NeuralMultiViewDepthEngine.Prediction neuralPrediction = null;
        String neuralBackend = "DA3 indisponible — coque V6.1 conservée";
        String neuralFallbackReason = "DA3 non exécuté";
        Bitmap[] depthInputs = null;
        try {
            notifyProgress(listener, Stage.NEURAL_DEPTH, 0, 1);
            depthInputs = buildDepthInputs(
                    isolated,
                    bounds,
                    profile,
                    profileCorrection.shouldFlipLeft()
            );
            int[] depthWidths = {
                    profile.width,
                    profile.depth,
                    profile.width,
                    profile.depth
            };
            try (NeuralMultiViewDepthEngine depthEngine =
                         new NeuralMultiViewDepthEngine(context)) {
                neuralPrediction = depthEngine.estimate(
                        depthInputs,
                        depthWidths,
                        profile.height
                );
                neuralBackend = neuralPrediction.getBackend()
                        + " • quatre vues simultanées"
                        + " • " + neuralPrediction.getDurationMs() + " ms";
                neuralFallbackReason = "cartes DA3 non fusionnées";
            }
        } catch (Exception | OutOfMemoryError neuralError) {
            neuralBackend = "DA3 ignoré sans bloquer : " + shortError(neuralError);
            neuralFallbackReason = "DA3 non exécuté : " + shortError(neuralError);
            releaseMemory();
        } finally {
            recycleAll(depthInputs);
            notifyProgress(listener, Stage.NEURAL_DEPTH, 1, 1);
        }

        notifyProgress(listener, Stage.CLEANING, REQUIRED_VIEW_COUNT, REQUIRED_VIEW_COUNT);
        releaseMemory();
        notifyProgress(listener, Stage.BUILDING_HULL, 0, 1);

        ContinuousVisualHull.Result hull;
        MultiViewDepthFusion.Result depthFusion = null;
        float[] finalDensity;
        int occupied;
        try {
            hull = ContinuousVisualHull.build(
                    confidences,
                    masks,
                    profile.width,
                    profile.height,
                    profile.depth,
                    adaptive,
                    category
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
                        true,
                        category
                );
                occupied = hull.getOccupiedVoxels();
            }
            finalDensity = hull.getDensity();
            int baseOccupied = occupied;
            if (neuralPrediction != null) {
                try {
                    depthFusion = MultiViewDepthFusion.refine(
                            finalDensity,
                            masks,
                            neuralPrediction.getDepth(),
                            neuralPrediction.getConfidence(),
                            profile.width,
                            profile.height,
                            profile.depth,
                            category
                    );
                    if (depthFusion.isApplied()
                            && depthFusion.getOccupiedVoxels() >= 320) {
                        finalDensity = depthFusion.getDensity();
                        occupied = depthFusion.getOccupiedVoxels();
                        neuralFallbackReason = null;
                    } else if (depthFusion.isApplied()) {
                        depthFusion = null;
                        occupied = baseOccupied;
                        neuralBackend += " • relief refusé par le garde-fou final";
                        neuralFallbackReason = "relief DA3 trop petit après sécurité";
                    } else {
                        neuralFallbackReason = depthFusion.getReason();
                    }
                } catch (RuntimeException | OutOfMemoryError fusionError) {
                    depthFusion = null;
                    neuralBackend += " • fusion ignorée : " + shortError(fusionError);
                    neuralFallbackReason = "fusion DA3 : " + shortError(fusionError);
                    releaseMemory();
                }
            }
        } catch (RuntimeException | OutOfMemoryError error) {
            recycleAll(isolated);
            throw error;
        }
        if (occupied < 320) {
            recycleAll(isolated);
            throw new IllegalArgumentException(
                    "Les quatre vues restent trop différentes. Utilise le même sujet, "
                            + "la même pose et le modèle entier."
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
                    finalDensity,
                    profile.width,
                    profile.height,
                    profile.depth,
                    layout,
                    profile.processors,
                    masks,
                    category
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
        if (profileRecovery.hasReplacement()) {
            correctionSummary.append(" • ")
                    .append(profileRecovery.summary());
        }
        if (adaptive) {
            correctionSummary.append(" • volume adaptatif");
        } else {
            correctionSummary.append(" • quatre vues cohérentes");
        }
        correctionSummary.append(" • champ continu sous-pixel");
        correctionSummary.append(" • sections arrondies");
        correctionSummary.append(" • famille ")
                .append(category.getDisplayName());
        if (hull.isComplexShapeMode()) {
            correctionSummary.append(" • conducteur et base large traités par zones");
        }
        if (depthFusion != null && depthFusion.isApplied()) {
            correctionSummary.append(" • modeleur de surfaces DA3 ")
                    .append(depthFusion.getValidViews())
                    .append("/4")
                    .append(" • ")
                    .append(depthFusion.getChangedVoxels())
                    .append(" voxels sculptés")
                    .append(" • recul moyen ")
                    .append(String.format(
                            java.util.Locale.FRANCE,
                            "%.2f voxel",
                            depthFusion.getMeanSurfaceInset()
                    ));
            if (depthFusion.isCollapseGuardUsed()) {
                correctionSummary.append(" • garde-fou anti-écrasement actif");
            }
            if (neuralPrediction != null
                    && neuralPrediction.getBackend().contains("repli NNAPI")) {
                correctionSummary.append(" • calcul CPU sécurisé");
            }
        } else {
            correctionSummary.append(" • coque de secours : ")
                    .append(neuralFallbackReason == null
                            ? "relief neural non appliqué"
                            : neuralFallbackReason);
        }
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
                segmentationBackend + " • " + neuralBackend,
                adaptive,
                profileCorrection.shouldFlipLeft(),
                coherence,
                profile.depth,
                category,
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
                throw new IllegalArgumentException("Une image du sujet est invalide");
            }
        }
    }

    private static boolean isProfile(int index) {
        return index == StylizedFourViewProjector.RIGHT
                || index == StylizedFourViewProjector.LEFT;
    }

    /**
     * Remplace uniquement un profil manifestement effondré. La géométrie et
     * l'atlas utilisent alors la même vue opposée en miroir : une mauvaise
     * photo ne peut plus écraser le volume ni créer des traînées de texture.
     */
    private static ProfileRecovery recoverUnusableProfile(
            Bitmap[] isolated,
            Rect[] bounds
    ) {
        int right = StylizedFourViewProjector.RIGHT;
        int left = StylizedFourViewProjector.LEFT;
        NormalizedSilhouette rightQuality = normalizeSilhouette(
                isolated[right],
                bounds[right],
                QUALITY_WIDTH,
                QUALITY_HEIGHT
        );
        NormalizedSilhouette leftQuality = normalizeSilhouette(
                isolated[left],
                bounds[left],
                QUALITY_WIDTH,
                QUALITY_HEIGHT
        );
        FourViewReliabilityAnalyzer.PairAssessment assessment =
                FourViewReliabilityAnalyzer.assessPair(
                        rightQuality.mask,
                        leftQuality.mask,
                        QUALITY_WIDTH,
                        QUALITY_HEIGHT
                );
        if (!assessment.requiresReplacement()) {
            return ProfileRecovery.none(assessment);
        }

        int target;
        int source;
        if (assessment.getReplacement()
                == FourViewReliabilityAnalyzer.Replacement.FIRST_FROM_SECOND) {
            target = right;
            source = left;
        } else {
            target = left;
            source = right;
        }
        Bitmap replacement = mirroredBitmap(isolated[source]);
        Rect replacementBounds;
        try {
            replacementBounds = findForegroundBounds(replacement);
        } catch (RuntimeException error) {
            recycle(replacement);
            throw error;
        }
        recycle(isolated[target]);
        isolated[target] = replacement;
        bounds[target] = replacementBounds;
        return new ProfileRecovery(target, source, assessment);
    }

    private static Bitmap mirroredBitmap(Bitmap source) {
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

    private static Bitmap[] buildDepthInputs(
            Bitmap[] views,
            Rect[] bounds,
            Profile profile,
            boolean flipLeft
    ) {
        Bitmap[] inputs = new Bitmap[REQUIRED_VIEW_COUNT];
        try {
            for (int view = 0; view < REQUIRED_VIEW_COUNT; view++) {
                int targetWidth = isProfile(view) ? profile.depth : profile.width;
                inputs[view] = normalizedDepthInput(
                        views[view],
                        bounds[view],
                        targetWidth,
                        profile.height,
                        view == StylizedFourViewProjector.LEFT && flipLeft
                );
            }
            return inputs;
        } catch (RuntimeException | OutOfMemoryError error) {
            recycleAll(inputs);
            throw error;
        }
    }

    private static Bitmap normalizedDepthInput(
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
        return output;
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
        fillTextureCell(output);
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

    private static void fillTextureCell(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        TextureHoleFiller.fillLocalOpaque(
                pixels,
                width,
                height,
                Math.round(FOREGROUND_ALPHA),
                Math.max(10, Math.min(width, height) / 45)
        );
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
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
        if (bitmaps == null) {
            return;
        }
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
        NEURAL_DEPTH,
        CLEANING,
        BUILDING_HULL,
        MESHING
    }

    private static String shortError(Throwable error) {
        if (error instanceof OutOfMemoryError) {
            return "mémoire insuffisante";
        }
        Throwable current = error;
        String message = null;
        while (current != null) {
            if (current.getMessage() != null
                    && !current.getMessage().trim().isEmpty()) {
                message = current.getMessage().trim();
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        if (message == null) {
            return error.getClass().getSimpleName();
        }
        return message.length() > 120
                ? message.substring(0, 117) + "…"
                : message;
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

    private static final class ProfileRecovery {
        final int target;
        final int source;
        final FourViewReliabilityAnalyzer.PairAssessment assessment;

        ProfileRecovery(
                int target,
                int source,
                FourViewReliabilityAnalyzer.PairAssessment assessment
        ) {
            this.target = target;
            this.source = source;
            this.assessment = assessment;
        }

        static ProfileRecovery none(
                FourViewReliabilityAnalyzer.PairAssessment assessment
        ) {
            return new ProfileRecovery(-1, -1, assessment);
        }

        boolean hasReplacement() {
            return target >= 0;
        }

        String summary() {
            if (!hasReplacement()) {
                return "profils conservés";
            }
            String targetName = target == StylizedFourViewProjector.RIGHT
                    ? "profil droit"
                    : "profil gauche";
            String sourceName = source == StylizedFourViewProjector.RIGHT
                    ? "droit"
                    : "gauche";
            return targetName + " incomplet remplacé par le "
                    + sourceName + " en miroir (confiance "
                    + Math.round(assessment.getConfidence() * 100.0)
                    + " %)";
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
        private final SubjectCategory subjectCategory;
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
                SubjectCategory subjectCategory,
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
            this.subjectCategory = subjectCategory;
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

        public SubjectCategory getSubjectCategory() {
            return subjectCategory;
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

        static Profile detect(
                float requestedDepth,
                Rect[] bounds,
                SubjectCategory requestedCategory
        ) {
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
                label = "3D V7.2 DA3 multi-formes précision neuronale";
            } else if (memoryMb >= 430L && processors >= 6) {
                width = 112;
                height = 224;
                maximumDepth = 264;
                atlasHeight = 2048;
                label = "3D V7.2 DA3 multi-formes haute précision";
            } else {
                width = 88;
                height = 176;
                maximumDepth = 200;
                atlasHeight = 1024;
                label = "3D V7.2 DA3 multi-formes compatible";
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
            double maximumRatio = maximumAspectRatio(
                    requestedCategory,
                    aspectRatio
            );
            aspectRatio = Math.max(0.65, Math.min(maximumRatio, aspectRatio));
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

        private static double maximumAspectRatio(
                SubjectCategory category,
                double measuredRatio
        ) {
            if (category == SubjectCategory.ANIMAL) {
                return 2.30;
            }
            if (category == SubjectCategory.COMPOSITE_VEHICLE) {
                return 2.10;
            }
            if (category == SubjectCategory.ARCHITECTURE_OBJECT) {
                return 1.95;
            }
            if (category == SubjectCategory.PLANT) {
                return 1.60;
            }
            // En Auto, un profil nettement plus long que la face est le seul
            // indice disponible avant le détourage final. Autoriser le volume
            // animal/composé évite de raccourcir un cheval dès cette étape.
            if (category == SubjectCategory.AUTO && measuredRatio >= 1.55) {
                return 2.25;
            }
            return 1.75;
        }
    }
}
