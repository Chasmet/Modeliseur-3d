package com.chasmet.modeliseur3d.model;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;

/** Pipeline V4.1 : segmentation anime, coque lissée, puis relief neuronal borné. */
public final class NeuralReconstructionEngine implements AutoCloseable {
    private final Context context;
    private final ImageToMeshGenerator geometry = new ImageToMeshGenerator();
    private volatile String backend = "Réseaux V4.1 chargés à la demande";

    public NeuralReconstructionEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    public Result generate(Bitmap source) throws Exception {
        long started = SystemClock.elapsedRealtime();
        AnimeSegmentationEngine.Mask mask;
        String segmentationBackend;
        try (AnimeSegmentationEngine segmentation = new AnimeSegmentationEngine(context)) {
            mask = segmentation.segment(source);
            segmentationBackend = segmentation.getBackend();
        }

        Bitmap isolated = NeuralSheetIsolator.isolate(source, mask);
        ImageToMeshGenerator.Result base;
        try {
            base = geometry.generate(isolated);
        } finally {
            if (!isolated.isRecycled()) isolated.recycle();
        }

        MeshData smooth = MeshSurfaceOptimizer.optimize(base.getMesh(), 3);
        Bitmap atlas = base.getTexture();
        NeuralDepthEngine.DepthMap front;
        NeuralDepthEngine.DepthMap back;
        NeuralDepthEngine.DepthMap side;
        String depthBackend;
        long neuralStarted = SystemClock.elapsedRealtime();

        try (NeuralMeshRefiner.Views views = NeuralMeshRefiner.cropViews(atlas);
             NeuralDepthEngine depth = new NeuralDepthEngine(context)) {
            depthBackend = depth.getBackend();
            front = depth.estimate(views.front);
            back = depth.estimate(views.back);
            side = depth.estimate(views.side);
        }
        long neuralDuration = SystemClock.elapsedRealtime() - neuralStarted;
        MeshData refined = NeuralMeshRefiner.refine(smooth, front, back, side);
        backend = segmentationBackend + " • " + depthBackend;

        return new Result(
                refined,
                atlas,
                base.getDetectedViewCount(),
                base.getQualityLabel() + " + détourage anime V4.1",
                base.getProcessorCount(),
                backend,
                AnimeSegmentationEngine.MODEL_NAME + " + " + NeuralDepthEngine.MODEL_NAME,
                neuralDuration,
                SystemClock.elapsedRealtime() - started
        );
    }

    public String getBackend() { return backend; }
    @Override public void close() { }

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
                MeshData mesh, Bitmap texture, int detectedViewCount,
                String qualityLabel, int processorCount, String neuralBackend,
                String neuralModel, long neuralDurationMs, long totalDurationMs
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
        public MeshData getMesh() { return mesh; }
        public Bitmap getTexture() { return texture; }
        public int getDetectedViewCount() { return detectedViewCount; }
        public String getQualityLabel() { return qualityLabel; }
        public int getProcessorCount() { return processorCount; }
        public String getNeuralBackend() { return neuralBackend; }
        public String getNeuralModel() { return neuralModel; }
        public long getNeuralDurationMs() { return neuralDurationMs; }
        public long getTotalDurationMs() { return totalDurationMs; }
    }
}
