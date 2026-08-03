package com.chasmet.modeliseur3d.model;

import android.content.Context;
import android.graphics.Bitmap;

import com.chasmet.modeliseur3d.performance.DevicePerformanceProfile;

import java.util.List;

/** Pipeline vidéo V4.7 : recentrage du sujet puis reconstruction huit vues V4.6. */
public final class VideoReconstructionEngineV47 implements AutoCloseable {
    private final Context context;
    private final DevicePerformanceProfile profile;
    private VideoReconstructionEngineV46 delegate;

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
                delegate = new VideoReconstructionEngineV46(context);
            }
            VideoReconstructionEngineV46.Result result = delegate.generate(
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
                    + " • sujet recentré dans "
                    + normalized.getDetectedFrameCount()
                    + "/8 vues • "
                    + result.getQualityLabel();
            String backend = "Normalisation sujet unique V4.7 • "
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

    private static Stage map(VideoReconstructionEngineV46.Stage stage) {
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
