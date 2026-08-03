package com.chasmet.modeliseur3d.model;

import android.content.Context;
import android.graphics.Bitmap;

import com.chasmet.modeliseur3d.performance.DevicePerformanceProfile;

import java.util.List;

/** Façade compatible de l'application, désormais branchée sur le moteur V4.8. */
public final class VideoReconstructionEngineV47 implements AutoCloseable {
    private final Context context;
    private final DevicePerformanceProfile profile;
    private VideoReconstructionEngineV48 delegate;

    public VideoReconstructionEngineV47(
            Context context,
            DevicePerformanceProfile profile
    ) {
        this.context = context.getApplicationContext();
        this.profile = profile;
    }

    public Result generate(
            List<Bitmap> frames,
            int decodedFrameCount,
            ProgressListener listener
    ) throws Exception {
        try (VideoSubjectNormalizer.Result normalized =
                     VideoSubjectNormalizer.normalize(frames, profile)) {
            if (delegate == null) {
                delegate = new VideoReconstructionEngineV48(context, profile);
            }
            VideoReconstructionEngineV48.Result result = delegate.generate(
                    normalized.getFrames(),
                    decodedFrameCount,
                    (stage, current, total) -> notifyProgress(
                            listener,
                            map(stage),
                            current,
                            total
                    )
            );
            String quality = profile.getLabel()
                    + " • alignement global dans "
                    + normalized.getDetectedFrameCount()
                    + "/8 vues • "
                    + result.getQualityLabel();
            String backend = "Pipeline mobile inspiré de l'architecture Modly"
                    + " • sans serveur • "
                    + result.getBackend();
            return new Result(
                    result.getMesh(),
                    result.getTexture(),
                    result.getOccupiedVoxels(),
                    quality,
                    profile.getProcessorCount(),
                    backend,
                    result.getNeuralDurationMs(),
                    result.getTotalDurationMs(),
                    result.getDecodedFrameCount(),
                    result.getRepairedViewCount(),
                    normalized.getDetectedFrameCount()
            );
        }
    }

    private static Stage map(VideoReconstructionEngineV48.Stage stage) {
        switch (stage) {
            case SEGMENTING:
                return Stage.SEGMENTING;
            case BUILDING_HULL:
                return Stage.BUILDING_HULL;
            case MESHING:
                return Stage.MESHING;
            case DEPTH:
            default:
                return Stage.DEPTH;
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

    @Override
    public void close() {
        if (delegate != null) {
            delegate.close();
            delegate = null;
        }
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
        private final int normalizedViewCount;

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
                int repairedViewCount,
                int normalizedViewCount
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
            this.normalizedViewCount = normalizedViewCount;
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

        public int getNormalizedViewCount() {
            return normalizedViewCount;
        }
    }
}
