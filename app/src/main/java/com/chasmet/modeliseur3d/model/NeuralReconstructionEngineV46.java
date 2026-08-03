package com.chasmet.modeliseur3d.model;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;

/**
 * Pipeline image V4.6 : relief fermé pour une image unique et conservation de
 * l'ancien pipeline uniquement pour les véritables planches multivues.
 */
public final class NeuralReconstructionEngineV46 implements AutoCloseable {
    private final Context context;
    private volatile String backend = "Moteurs V4.6 chargés à la demande";

    public NeuralReconstructionEngineV46(Context context) {
        this.context = context.getApplicationContext();
    }

    public Result generate(Bitmap source) throws Exception {
        if (source == null || source.isRecycled()) {
            throw new IllegalArgumentException("Image absente");
        }
        if (looksLikeMultiViewSheet(source)) {
            return generateLegacySheet(source);
        }

        long started = SystemClock.elapsedRealtime();
        Bitmap isolated = null;
        String segmentationBackend;
        try {
            try (AnimeSegmentationEngine segmentation =
                         new AnimeSegmentationEngine(context)) {
                AnimeSegmentationEngine.Mask mask = segmentation.segment(source);
                segmentationBackend = segmentation.getBackend();
                isolated = NeuralSheetIsolator.isolate(source, mask);
            }
        } catch (Exception | OutOfMemoryError segmentationFailure) {
            releaseMemory();
            return generateLegacyFallback(
                    source,
                    "Détourage FP32 indisponible : "
                            + shortError(segmentationFailure)
            );
        }

        releaseMemory();
        NeuralDepthEngine.DepthMap depthMap = null;
        String depthBackend;
        long neuralStarted = SystemClock.elapsedRealtime();
        try (NeuralDepthEngine depth = new NeuralDepthEngine(context)) {
            depthMap = depth.estimate(isolated);
            depthBackend = depth.getBackend();
        } catch (Exception | OutOfMemoryError depthFailure) {
            depthBackend = "relief géométrique stable : "
                    + shortError(depthFailure);
            releaseMemory();
        }

        SingleImageReliefGenerator.Result relief;
        try {
            relief = new SingleImageReliefGenerator().generate(
                    isolated,
                    depthMap
            );
        } catch (Exception | OutOfMemoryError reliefFailure) {
            if (isolated != null && !isolated.isRecycled()) {
                isolated.recycle();
            }
            releaseMemory();
            return generateLegacyFallback(
                    source,
                    "Relief V4.6 indisponible : " + shortError(reliefFailure)
            );
        } finally {
            if (isolated != null && !isolated.isRecycled()) {
                isolated.recycle();
            }
        }

        backend = segmentationBackend
                + " • " + depthBackend
                + " • surface image fermée sans pliage volumique";
        return new Result(
                relief.getMesh(),
                relief.getTexture(),
                1,
                relief.getQualityLabel()
                        + " • texture frontale préservée • dos stable",
                Math.max(1, Runtime.getRuntime().availableProcessors()),
                backend,
                AnimeSegmentationEngine.MODEL_NAME
                        + " + " + NeuralDepthEngine.MODEL_NAME,
                SystemClock.elapsedRealtime() - neuralStarted,
                SystemClock.elapsedRealtime() - started
        );
    }

    private Result generateLegacySheet(Bitmap source) throws Exception {
        try (NeuralReconstructionEngine legacy =
                     new NeuralReconstructionEngine(context)) {
            NeuralReconstructionEngine.Result result = legacy.generate(source);
            backend = result.getNeuralBackend()
                    + " • planche multivue conservée";
            return adaptLegacy(result, backend);
        }
    }

    private Result generateLegacyFallback(
            Bitmap source,
            String reason
    ) throws Exception {
        try (NeuralReconstructionEngine legacy =
                     new NeuralReconstructionEngine(context)) {
            NeuralReconstructionEngine.Result result = legacy.generate(source);
            backend = reason + " • " + result.getNeuralBackend();
            return adaptLegacy(result, backend);
        }
    }

    private static Result adaptLegacy(
            NeuralReconstructionEngine.Result source,
            String backend
    ) {
        return new Result(
                source.getMesh(),
                source.getTexture(),
                source.getDetectedViewCount(),
                source.getQualityLabel(),
                source.getProcessorCount(),
                backend,
                source.getNeuralModel(),
                source.getNeuralDurationMs(),
                source.getTotalDurationMs()
        );
    }

    private static boolean looksLikeMultiViewSheet(Bitmap source) {
        float aspect = source.getWidth()
                / (float) Math.max(1, source.getHeight());
        return aspect >= 1.42f;
    }

    private static void releaseMemory() {
        Runtime.getRuntime().gc();
        System.runFinalization();
    }

    private static String shortError(Throwable error) {
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
        String type = error instanceof OutOfMemoryError
                ? "mémoire insuffisante"
                : error.getClass().getSimpleName();
        if (message == null || message.isEmpty()) {
            return type;
        }
        if (message.length() > 100) {
            message = message.substring(0, 97) + "…";
        }
        return type + " — " + message;
    }

    public String getBackend() {
        return backend;
    }

    @Override
    public void close() {
        // Les sessions sont fermées après chaque génération.
    }

    public static final class Result {
        private final MeshData mesh;
        private final Bitmap texture;
        private final int detectedViewCount;
        private final String qualityLabel;
        private final int processorCount;
        private final String neuralBackend;
        private final String neuralModel;
        private final long neuralDurationMs;
        private final long totalDurationMs;

        Result(
                MeshData mesh,
                Bitmap texture,
                int detectedViewCount,
                String qualityLabel,
                int processorCount,
                String neuralBackend,
                String neuralModel,
                long neuralDurationMs,
                long totalDurationMs
        ) {
            this.mesh = mesh;
            this.texture = texture;
            this.detectedViewCount = detectedViewCount;
            this.qualityLabel = qualityLabel;
            this.processorCount = processorCount;
            this.neuralBackend = neuralBackend;
            this.neuralModel = neuralModel;
            this.neuralDurationMs = neuralDurationMs;
            this.totalDurationMs = totalDurationMs;
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

        public String getNeuralBackend() {
            return neuralBackend;
        }

        public String getNeuralModel() {
            return neuralModel;
        }

        public long getNeuralDurationMs() {
            return neuralDurationMs;
        }

        public long getTotalDurationMs() {
            return totalDurationMs;
        }
    }
}
