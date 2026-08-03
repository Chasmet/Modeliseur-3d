package com.chasmet.modeliseur3d.model;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import com.chasmet.modeliseur3d.cloud.TripoCloudEngine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Détoure et ordonne une image, une planche ou quatre vues avant l'envoi.
 * Le modèle de segmentation est libéré avant le début du transfert réseau.
 */
public final class CloudViewPreprocessor {
    private static final int CLOUD_CANVAS_SIZE = 1536;
    private static final float SUBJECT_OCCUPANCY = 0.88f;
    private static final String[] ORDERED_ROLES = {
            "front", "left", "back", "right"
    };

    private final Context context;
    private final ImageToMeshGenerator extractor = new ImageToMeshGenerator();

    public CloudViewPreprocessor(Context context) {
        this.context = context.getApplicationContext();
    }

    public PreparedViews prepare(
            List<Bitmap> sources,
            ProgressListener listener
    ) throws Exception {
        return prepare(sources, null, listener);
    }

    public PreparedViews prepare(
            List<Bitmap> sources,
            List<String> requestedRoles,
            ProgressListener listener
    ) throws Exception {
        if (sources == null || sources.isEmpty() || sources.size() > 4) {
            throw new IllegalArgumentException("Une à quatre images sont requises");
        }
        List<String> roles = normalizedRoles(sources.size(), requestedRoles);

        AnimeSegmentationEngine segmentation = null;
        try {
            try {
                segmentation = new AnimeSegmentationEngine(context);
            } catch (Exception | OutOfMemoryError unavailable) {
                releaseMemory();
            }

            if (sources.size() == 1) {
                notifyProgress(listener, 1, 1);
                return prepareSingleSheet(sources.get(0), segmentation);
            }
            return prepareSeparateViews(
                    sources,
                    roles,
                    segmentation,
                    listener
            );
        } finally {
            if (segmentation != null) {
                segmentation.close();
            }
            releaseMemory();
        }
    }

    private PreparedViews prepareSingleSheet(
            Bitmap source,
            AnimeSegmentationEngine segmentation
    ) {
        Bitmap working = isolateOrUseOriginal(source, segmentation);
        boolean ownsWorking = working != source;
        ImageToMeshGenerator.ExtractedViews extracted = null;
        List<TripoCloudEngine.InputView> result = new ArrayList<>();
        try {
            extracted = extractor.extractViews(working);
            addPrepared(result, "front", extracted.takeFront());
            addPrepared(result, "left", extracted.takeLeft());
            addPrepared(result, "back", extracted.takeBack());
            addPrepared(result, "right", extracted.takeRight());
            if (result.isEmpty()) {
                addPrepared(result, "front", working.copy(
                        Bitmap.Config.ARGB_8888,
                        false
                ));
            }
            return new PreparedViews(result);
        } catch (RuntimeException | OutOfMemoryError extractionError) {
            recycleInputViews(result);
            Bitmap fallback = working.copy(Bitmap.Config.ARGB_8888, false);
            result.clear();
            addPrepared(result, "front", fallback);
            return new PreparedViews(result);
        } finally {
            if (extracted != null) {
                extracted.close();
            }
            if (ownsWorking) {
                recycle(working);
            }
        }
    }

    private PreparedViews prepareSeparateViews(
            List<Bitmap> sources,
            List<String> roles,
            AnimeSegmentationEngine segmentation,
            ProgressListener listener
    ) {
        List<TripoCloudEngine.InputView> result = new ArrayList<>();
        try {
            for (int index = 0; index < sources.size(); index++) {
                notifyProgress(listener, index + 1, sources.size());
                Bitmap source = sources.get(index);
                Bitmap working = isolateOrUseOriginal(source, segmentation);
                boolean ownsWorking = working != source;
                ImageToMeshGenerator.ExtractedViews extracted = null;
                Bitmap selected = null;
                try {
                    extracted = extractor.extractViews(working);
                    selected = extracted.takeFront();
                    if (selected == null) {
                        selected = working.copy(Bitmap.Config.ARGB_8888, false);
                    }
                    addPrepared(result, roles.get(index), selected);
                    selected = null;
                } catch (RuntimeException | OutOfMemoryError extractionError) {
                    recycle(selected);
                    addPrepared(
                            result,
                            roles.get(index),
                            working.copy(Bitmap.Config.ARGB_8888, false)
                    );
                } finally {
                    if (extracted != null) {
                        extracted.close();
                    }
                    if (ownsWorking) {
                        recycle(working);
                    }
                }
            }
            return new PreparedViews(result);
        } catch (RuntimeException | OutOfMemoryError error) {
            recycleInputViews(result);
            throw error;
        }
    }

    private Bitmap isolateOrUseOriginal(
            Bitmap source,
            AnimeSegmentationEngine segmentation
    ) {
        if (source == null || source.isRecycled()) {
            throw new IllegalArgumentException("Image source invalide");
        }
        if (segmentation == null) {
            return source;
        }
        try {
            AnimeSegmentationEngine.Mask mask = segmentation.segment(source);
            return NeuralSheetIsolator.isolate(source, mask);
        } catch (Exception | OutOfMemoryError segmentationError) {
            releaseMemory();
            return source;
        }
    }

    private static void addPrepared(
            List<TripoCloudEngine.InputView> output,
            String role,
            Bitmap extracted
    ) {
        if (extracted == null) {
            return;
        }
        Bitmap square = prepareSquare(extracted);
        recycle(extracted);
        output.add(new TripoCloudEngine.InputView(role, square));
    }

    private static List<String> normalizedRoles(
            int sourceCount,
            List<String> requestedRoles
    ) {
        List<String> result = new ArrayList<>(sourceCount);
        if (requestedRoles == null || requestedRoles.isEmpty()) {
            for (int index = 0; index < sourceCount; index++) {
                result.add(ORDERED_ROLES[index]);
            }
            return result;
        }
        if (requestedRoles.size() != sourceCount) {
            throw new IllegalArgumentException(
                    "Le nombre de rôles ne correspond pas aux vues"
            );
        }
        Set<String> unique = new java.util.HashSet<>();
        for (String requested : requestedRoles) {
            String role = requested == null
                    ? ""
                    : requested.trim().toLowerCase(java.util.Locale.ROOT);
            boolean valid = false;
            for (String allowed : ORDERED_ROLES) {
                if (allowed.equals(role)) {
                    valid = true;
                    break;
                }
            }
            if (!valid || !unique.add(role)) {
                throw new IllegalArgumentException("Rôle multivue invalide");
            }
            result.add(role);
        }
        if (!unique.contains("front")) {
            throw new IllegalArgumentException("La vue de face est obligatoire");
        }
        return result;
    }

    private static Bitmap prepareSquare(Bitmap source) {
        Bitmap result = Bitmap.createBitmap(
                CLOUD_CANVAS_SIZE,
                CLOUD_CANVAS_SIZE,
                Bitmap.Config.ARGB_8888
        );
        Canvas canvas = new Canvas(result);
        canvas.drawColor(Color.TRANSPARENT);

        float maximum = CLOUD_CANVAS_SIZE * SUBJECT_OCCUPANCY;
        float scale = Math.min(
                maximum / Math.max(1.0f, source.getWidth()),
                maximum / Math.max(1.0f, source.getHeight())
        );
        // Ne pas agrandir démesurément une petite source ; l'autofix cloud se
        // charge de son amélioration sans fabriquer de contours artificiels.
        scale = Math.min(scale, 2.0f);
        float width = Math.max(1.0f, source.getWidth() * scale);
        float height = Math.max(1.0f, source.getHeight() * scale);
        float left = (CLOUD_CANVAS_SIZE - width) * 0.5f;
        float top = (CLOUD_CANVAS_SIZE - height) * 0.5f;
        Paint paint = new Paint(
                Paint.ANTI_ALIAS_FLAG
                        | Paint.FILTER_BITMAP_FLAG
                        | Paint.DITHER_FLAG
        );
        canvas.drawBitmap(
                source,
                null,
                new RectF(left, top, left + width, top + height),
                paint
        );
        return result;
    }

    private static void recycleInputViews(
            List<TripoCloudEngine.InputView> views
    ) {
        for (TripoCloudEngine.InputView view : views) {
            recycle(view.getBitmap());
        }
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }

    private static void notifyProgress(
            ProgressListener listener,
            int current,
            int total
    ) {
        if (listener != null) {
            listener.onViewPrepared(current, total);
        }
    }

    private static void releaseMemory() {
        Runtime.getRuntime().gc();
        System.runFinalization();
    }

    public interface ProgressListener {
        void onViewPrepared(int current, int total);
    }

    public static final class PreparedViews implements AutoCloseable {
        private final List<TripoCloudEngine.InputView> views;

        PreparedViews(List<TripoCloudEngine.InputView> views) {
            if (views == null || views.isEmpty()) {
                throw new IllegalArgumentException("Aucune vue cloud préparée");
            }
            this.views = Collections.unmodifiableList(
                    new ArrayList<>(views)
            );
        }

        public List<TripoCloudEngine.InputView> getViews() {
            return views;
        }

        @Override
        public void close() {
            Set<Bitmap> recycled = Collections.newSetFromMap(
                    new IdentityHashMap<>()
            );
            for (TripoCloudEngine.InputView view : views) {
                Bitmap bitmap = view.getBitmap();
                if (bitmap != null && recycled.add(bitmap)) {
                    recycle(bitmap);
                }
            }
        }
    }
}
