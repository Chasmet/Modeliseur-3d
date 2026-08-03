package com.chasmet.modeliseur3d.model;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;

import com.chasmet.modeliseur3d.performance.DevicePerformanceProfile;

/** Pipeline image V4.7 : un seul sujet, relief dense et maillage fermé indexé. */
public final class NeuralReconstructionEngineV47 implements AutoCloseable {
    private final Context context;
    private final DevicePerformanceProfile profile;
    private volatile String backend = "Moteur V4.7 chargé à la demande";

    public NeuralReconstructionEngineV47(
            Context context,
            DevicePerformanceProfile profile
    ) {
        this.context = context.getApplicationContext();
        this.profile = profile;
    }

    public Result generate(Bitmap source) throws Exception {
        if (source == null || source.isRecycled()) {
            throw new IllegalArgumentException("Image absente");
        }
        long started = SystemClock.elapsedRealtime();
        long neuralStarted = started;
        String segmentationBackend;
        String selectionLabel;
        int detectedSubjects;
        Bitmap singleSubject = null;

        try (AnimeSegmentationEngine segmentation =
                     new AnimeSegmentationEngine(
                             context,
                             profile.getNeuralThreadCount()
                     )) {
            AnimeSegmentationEngine.Mask mask = segmentation.segment(source);
            segmentationBackend = segmentation.getBackend();
            try (SingleSubjectExtractor.Result selected =
                         SingleSubjectExtractor.extract(source, mask)) {
                detectedSubjects = selected.getDetectedSubjectCount();
                selectionLabel = selected.getSelectionLabel();
                singleSubject = selected.getBitmap().copy(
                        Bitmap.Config.ARGB_8888,
                        false
                );
            }
        }
        if (singleSubject == null) {
            throw new IllegalStateException("Extraction du personnage impossible");
        }

        NeuralDepthEngine.DepthMap depthMap = null;
        String depthBackend;
        try {
            try (NeuralDepthEngine depth = new NeuralDepthEngine(context)) {
                depthMap = depth.estimate(singleSubject);
                depthBackend = depth.getBackend();
            }
        } catch (Exception | OutOfMemoryError depthFailure) {
            depthBackend = "relief géométrique de secours : "
                    + shortError(depthFailure);
            releaseMemory();
        }

        SingleImageMeshGeneratorV47.Result generated;
        try {
            generated = new SingleImageMeshGeneratorV47().generate(
                    singleSubject,
                    depthMap,
                    profile
            );
        } finally {
            if (!singleSubject.isRecycled()) {
                singleSubject.recycle();
            }
        }

        backend = segmentationBackend
                + " • " + selectionLabel
                + " • " + depthBackend
                + " • surface indexée V4.7";
        return new Result(
                generated.getMesh(),
                generated.getTexture(),
                1,
                detectedSubjects,
                generated.getQualityLabel(),
                profile.getProcessorCount(),
                backend,
                AnimeSegmentationEngine.MODEL_NAME
                        + " + " + NeuralDepthEngine.MODEL_NAME,
                SystemClock.elapsedRealtime() - neuralStarted,
                SystemClock.elapsedRealtime() - started,
                generated.getGridWidth(),
                generated.getGridHeight(),
                generated.getOccupiedCells()
        );
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
        if (message.length() > 110) {
            message = message.substring(0, 107) + "…";
        }
        return type + " — " + message;
    }

    public String getBackend() {
        return backend;
    }

    @Override
    public void close() {
        // Les sessions ONNX sont fermées après chaque reconstruction.
    }

    public static final class Result {
        private final MeshData mesh;
        private final Bitmap texture;
        private final int finalSubjectCount;
        private final int detectedSubjectCount;
        private final String qualityLabel;
        private final int processorCount;
        private final String neuralBackend;
        private final String neuralModel;
        private final long neuralDurationMs;
        private final long totalDurationMs;
        private final int gridWidth;
        private final int gridHeight;
        private final int occupiedCells;

        Result(
                MeshData mesh,
                Bitmap texture,
                int finalSubjectCount,
                int detectedSubjectCount,
                String qualityLabel,
                int processorCount,
                String neuralBackend,
                String neuralModel,
                long neuralDurationMs,
                long totalDurationMs,
                int gridWidth,
                int gridHeight,
                int occupiedCells
        ) {
            this.mesh = mesh;
            this.texture = texture;
            this.finalSubjectCount = finalSubjectCount;
            this.detectedSubjectCount = detectedSubjectCount;
            this.qualityLabel = qualityLabel;
            this.processorCount = processorCount;
            this.neuralBackend = neuralBackend;
            this.neuralModel = neuralModel;
            this.neuralDurationMs = neuralDurationMs;
            this.totalDurationMs = totalDurationMs;
            this.gridWidth = gridWidth;
            this.gridHeight = gridHeight;
            this.occupiedCells = occupiedCells;
        }

        public MeshData getMesh() {
            return mesh;
        }

        public Bitmap getTexture() {
            return texture;
        }

        public int getFinalSubjectCount() {
            return finalSubjectCount;
        }

        public int getDetectedSubjectCount() {
            return detectedSubjectCount;
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

        public int getGridWidth() {
            return gridWidth;
        }

        public int getGridHeight() {
            return gridHeight;
        }

        public int getOccupiedCells() {
            return occupiedCells;
        }
    }
}
