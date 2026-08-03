package com.chasmet.modeliseur3d.model;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;

/**
 * Pipeline V4.2 robuste : segmentation anime FP32, regroupement intelligent,
 * coque adaptée au nombre de vues, puis relief neuronal.
 *
 * Chaque étape neuronale possède désormais un repli local. Une incompatibilité
 * de pilote, un manque de mémoire ou un modèle non pris en charge ne doit plus
 * empêcher la création d'un modèle 3D exportable.
 */
public final class NeuralReconstructionEngine implements AutoCloseable {
    private final Context context;
    private final ImageToMeshGenerator geometry = new ImageToMeshGenerator();
    private volatile String backend = "Réseaux V4.2 chargés à la demande";

    public NeuralReconstructionEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    public Result generate(Bitmap source) throws Exception {
        long started = SystemClock.elapsedRealtime();
        Bitmap isolated = null;
        String segmentationBackend;
        boolean segmentationUsed = false;

        try {
            try (AnimeSegmentationEngine segmentation =
                         new AnimeSegmentationEngine(context)) {
                AnimeSegmentationEngine.Mask mask = segmentation.segment(source);
                segmentationBackend = segmentation.getBackend();
                isolated = NeuralSheetIsolator.isolate(source, mask);
                segmentationUsed = true;
            }
        } catch (Exception | OutOfMemoryError segmentationError) {
            segmentationBackend = "Détourage classique de secours : "
                    + shortError(segmentationError);
            releaseMemory();
        }
        if (segmentationUsed) {
            // Libère le graphe FP32 avant la construction volumique.
            releaseMemory();
        }

        ImageToMeshGenerator.Result base;
        try {
            if (isolated != null) {
                try {
                    base = geometry.generate(isolated);
                } catch (Exception isolatedError) {
                    if (!isolated.isRecycled()) {
                        isolated.recycle();
                    }
                    isolated = null;
                    segmentationUsed = false;
                    segmentationBackend = "Détourage classique de secours : "
                            + shortError(isolatedError);
                    releaseMemory();
                    base = geometry.generate(source);
                }
            } else {
                base = geometry.generate(source);
            }
        } finally {
            if (isolated != null && !isolated.isRecycled()) {
                isolated.recycle();
            }
        }

        MeshData smooth;
        try {
            smooth = MeshSurfaceOptimizer.optimize(base.getMesh(), 2);
        } catch (RuntimeException optimizationError) {
            smooth = base.getMesh();
            segmentationBackend += " • lissage simple";
        }

        releaseMemory();

        Bitmap atlas = base.getTexture();
        MeshData finalMesh = smooth;
        String depthBackend;
        boolean depthUsed = false;
        int depthPasses = 0;
        long neuralStarted = SystemClock.elapsedRealtime();

        try (NeuralMeshRefiner.Views views = NeuralMeshRefiner.cropViews(atlas);
             NeuralDepthEngine depth = new NeuralDepthEngine(context)) {
            depthBackend = depth.getBackend();
            NeuralDepthEngine.DepthMap front = depth.estimate(views.front);
            depthPasses++;
            NeuralDepthEngine.DepthMap back;
            if (base.hasBackView()) {
                back = depth.estimate(views.back);
                depthPasses++;
            } else {
                back = front;
            }
            NeuralDepthEngine.DepthMap side = null;
            if (base.hasSideView()) {
                side = depth.estimate(views.side);
                depthPasses++;
            }
            finalMesh = NeuralMeshRefiner.refine(smooth, front, back, side);
            depthUsed = true;
            depthBackend += " • " + depthPasses
                    + (depthPasses > 1 ? " passes utiles" : " passe utile");
        } catch (Exception | OutOfMemoryError depthError) {
            depthBackend = "Relief géométrique de secours : "
                    + shortError(depthError);
            releaseMemory();
        }

        long neuralDuration = SystemClock.elapsedRealtime() - neuralStarted;
        backend = segmentationBackend + " • " + depthBackend;

        StringBuilder quality = new StringBuilder(base.getQualityLabel());
        if (segmentationUsed) {
            quality.append(" + détourage anime FP32");
        } else {
            quality.append(" + détourage de secours");
        }
        if (depthUsed) {
            quality.append(" + relief neuronal ")
                    .append(depthPasses)
                    .append(depthPasses > 1 ? " vues" : " vue");
        } else {
            quality.append(" + relief stable");
        }
        if (base.hasSideView()) {
            quality.append(" + coque multivue calibrée");
        } else if (base.getDetectedViewCount() == 1) {
            quality.append(" + volume image unique arrondi");
        } else {
            quality.append(" + volume sans profil arrondi");
        }

        return new Result(
                finalMesh,
                atlas,
                base.getDetectedViewCount(),
                quality.toString(),
                base.getProcessorCount(),
                backend,
                AnimeSegmentationEngine.MODEL_NAME
                        + " + " + NeuralDepthEngine.MODEL_NAME,
                neuralDuration,
                SystemClock.elapsedRealtime() - started
        );
    }

    public String getBackend() {
        return backend;
    }

    @Override
    public void close() {
        // Les sessions sont créées et fermées à chaque génération.
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
        String name = error instanceof OutOfMemoryError
                ? "mémoire insuffisante"
                : error.getClass().getSimpleName();
        if (message == null || message.isEmpty()) {
            return name;
        }
        if (message.length() > 110) {
            message = message.substring(0, 107) + "…";
        }
        return name + " — " + message;
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
