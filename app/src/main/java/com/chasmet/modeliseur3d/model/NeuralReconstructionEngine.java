package com.chasmet.modeliseur3d.model;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;

import java.util.Arrays;

/**
 * Pipeline hybride V4 :
 *
 * 1. reconstruit une enveloppe multivue fermée avec le moteur géométrique V3 ;
 * 2. estime une profondeur neuronale indépendante sur la face, le dos et le profil ;
 * 3. fusionne les trois cartes de profondeur selon les normales du maillage ;
 * 4. déforme la surface de façon bornée puis recalcule les normales.
 *
 * Cette méthode conserve la stabilité de la reconstruction multivue tout en ajoutant
 * un relief réellement prédit par un réseau neuronal embarqué.
 */
public final class NeuralReconstructionEngine implements AutoCloseable {
    private static final int ATLAS_GAP = 8;
    private static final float FRONT_BACK_RELIEF = 0.115f;
    private static final float SIDE_RELIEF = 0.085f;
    private static final float NORMAL_RELIEF = 0.025f;

    private final ImageToMeshGenerator geometricEngine;
    private final NeuralDepthEngine neuralDepthEngine;

    public NeuralReconstructionEngine(Context context) throws Exception {
        geometricEngine = new ImageToMeshGenerator();
        neuralDepthEngine = new NeuralDepthEngine(context);
    }

    public Result generate(Bitmap source) throws Exception {
        long startedAt = SystemClock.elapsedRealtime();
        ImageToMeshGenerator.Result geometric = geometricEngine.generate(source);

        Bitmap atlas = geometric.getTexture();
        AtlasViews views = AtlasViews.from(atlas);
        NeuralDepthEngine.DepthMap frontDepth;
        NeuralDepthEngine.DepthMap backDepth;
        NeuralDepthEngine.DepthMap sideDepth;

        long neuralStartedAt = SystemClock.elapsedRealtime();
        try {
            frontDepth = neuralDepthEngine.estimate(views.front);
            backDepth = neuralDepthEngine.estimate(views.back);
            sideDepth = neuralDepthEngine.estimate(views.side);
        } finally {
            views.recycle();
        }
        long neuralDuration = SystemClock.elapsedRealtime() - neuralStartedAt;

        MeshData refined = refineMesh(
                geometric.getMesh(),
                frontDepth,
                backDepth,
                sideDepth
        );

        return new Result(
                refined,
                atlas,
                geometric.getDetectedViewCount(),
                geometric.getQualityLabel(),
                geometric.getProcessorCount(),
                neuralDepthEngine.getBackend(),
                NeuralDepthEngine.MODEL_NAME,
                neuralDuration,
                SystemClock.elapsedRealtime() - startedAt
        );
    }

    public String getBackend() {
        return neuralDepthEngine.getBackend();
    }

    @Override
    public void close() {
        neuralDepthEngine.close();
    }

    private static MeshData refineMesh(
            MeshData source,
            NeuralDepthEngine.DepthMap front,
            NeuralDepthEngine.DepthMap back,
            NeuralDepthEngine.DepthMap side
    ) {
        float[] positions = Arrays.copyOf(
                source.getPositions(),
                source.getPositions().length
        );
        float[] originalNormals = source.getNormals();
        float[] normals = Arrays.copyOf(
                originalNormals,
                originalNormals.length
        );
        float[] texCoords = Arrays.copyOf(
                source.getTexCoords(),
                source.getTexCoords().length
        );
        int[] indices = Arrays.copyOf(
                source.getIndices(),
                source.getIndices().length
        );

        Bounds bounds = Bounds.from(positions);
        float width = Math.max(1.0e-6f, bounds.maxX - bounds.minX);
        float height = Math.max(1.0e-6f, bounds.maxY - bounds.minY);
        float depth = Math.max(1.0e-6f, bounds.maxZ - bounds.minZ);

        for (int vertex = 0; vertex < positions.length / 3; vertex++) {
            int positionIndex = vertex * 3;
            float x = positions[positionIndex];
            float y = positions[positionIndex + 1];
            float z = positions[positionIndex + 2];
            float nx = originalNormals[positionIndex];
            float ny = originalNormals[positionIndex + 1];
            float nz = originalNormals[positionIndex + 2];

            float xNorm = clamp01((x - bounds.minX) / width);
            float yNorm = clamp01((bounds.maxY - y) / height);
            float zNorm = clamp01((z - bounds.minZ) / depth);

            float frontValue = centered(front.sample(xNorm, yNorm));
            float backValue = centered(back.sample(1.0f - xNorm, yNorm));
            float rightValue = centered(side.sample(zNorm, yNorm));
            float leftValue = centered(side.sample(1.0f - zNorm, yNorm));

            float frontWeight = Math.max(0.0f, nz);
            float backWeight = Math.max(0.0f, -nz);
            float rightWeight = Math.max(0.0f, nx);
            float leftWeight = Math.max(0.0f, -nx);
            float totalWeight = Math.max(
                    1.0e-5f,
                    frontWeight + backWeight + rightWeight + leftWeight
            );

            float zDisplacement = (
                    frontValue * frontWeight
                            - backValue * backWeight
            ) / totalWeight * FRONT_BACK_RELIEF;
            float xDisplacement = (
                    rightValue * rightWeight
                            - leftValue * leftWeight
            ) / totalWeight * SIDE_RELIEF;

            float fusedRelief = (
                    frontValue * frontWeight
                            + backValue * backWeight
                            + rightValue * rightWeight
                            + leftValue * leftWeight
            ) / totalWeight;

            float edgeProtection = 0.55f + 0.45f * clamp01(
                    1.0f - Math.abs(yNorm - 0.5f) * 0.35f
            );
            xDisplacement = clamp(
                    xDisplacement * edgeProtection,
                    -SIDE_RELIEF * 0.5f,
                    SIDE_RELIEF * 0.5f
            );
            zDisplacement = clamp(
                    zDisplacement * edgeProtection,
                    -FRONT_BACK_RELIEF * 0.5f,
                    FRONT_BACK_RELIEF * 0.5f
            );
            float normalDisplacement = clamp(
                    fusedRelief * NORMAL_RELIEF,
                    -NORMAL_RELIEF * 0.65f,
                    NORMAL_RELIEF * 0.65f
            );

            positions[positionIndex] = x
                    + xDisplacement
                    + nx * normalDisplacement;
            positions[positionIndex + 1] = y
                    + ny * normalDisplacement * 0.45f;
            positions[positionIndex + 2] = z
                    + zDisplacement
                    + nz * normalDisplacement;
        }

        rebuildNormals(positions, originalNormals, indices, normals);
        return new MeshData(positions, normals, texCoords, indices);
    }

    private static void rebuildNormals(
            float[] positions,
            float[] originalNormals,
            int[] indices,
            float[] outputNormals
    ) {
        Arrays.fill(outputNormals, 0.0f);
        for (int triangle = 0; triangle + 2 < indices.length; triangle += 3) {
            int a = indices[triangle];
            int b = indices[triangle + 1];
            int c = indices[triangle + 2];
            int ai = a * 3;
            int bi = b * 3;
            int ci = c * 3;

            float abX = positions[bi] - positions[ai];
            float abY = positions[bi + 1] - positions[ai + 1];
            float abZ = positions[bi + 2] - positions[ai + 2];
            float acX = positions[ci] - positions[ai];
            float acY = positions[ci + 1] - positions[ai + 1];
            float acZ = positions[ci + 2] - positions[ai + 2];

            float faceX = abY * acZ - abZ * acY;
            float faceY = abZ * acX - abX * acZ;
            float faceZ = abX * acY - abY * acX;
            float faceLength = length(faceX, faceY, faceZ);
            if (faceLength < 1.0e-8f) {
                continue;
            }
            faceX /= faceLength;
            faceY /= faceLength;
            faceZ /= faceLength;

            blendNormal(outputNormals, originalNormals, ai, faceX, faceY, faceZ);
            blendNormal(outputNormals, originalNormals, bi, faceX, faceY, faceZ);
            blendNormal(outputNormals, originalNormals, ci, faceX, faceY, faceZ);
        }

        for (int index = 0; index < outputNormals.length; index += 3) {
            float normalLength = length(
                    outputNormals[index],
                    outputNormals[index + 1],
                    outputNormals[index + 2]
            );
            if (normalLength < 1.0e-8f) {
                outputNormals[index] = originalNormals[index];
                outputNormals[index + 1] = originalNormals[index + 1];
                outputNormals[index + 2] = originalNormals[index + 2];
            } else {
                outputNormals[index] /= normalLength;
                outputNormals[index + 1] /= normalLength;
                outputNormals[index + 2] /= normalLength;
            }
        }
    }

    private static void blendNormal(
            float[] destination,
            float[] original,
            int index,
            float faceX,
            float faceY,
            float faceZ
    ) {
        destination[index] += original[index] * 0.72f + faceX * 0.28f;
        destination[index + 1] += original[index + 1] * 0.72f + faceY * 0.28f;
        destination[index + 2] += original[index + 2] * 0.72f + faceZ * 0.28f;
    }

    private static float centered(float value) {
        float centered = (value - 0.5f) * 2.0f;
        float signedCurve = Math.signum(centered)
                * (float) Math.pow(Math.abs(centered), 0.82f);
        return clamp(signedCurve, -1.0f, 1.0f);
    }

    private static float length(float x, float y, float z) {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static final class AtlasViews {
        final Bitmap front;
        final Bitmap back;
        final Bitmap side;

        AtlasViews(Bitmap front, Bitmap back, Bitmap side) {
            this.front = front;
            this.back = back;
            this.side = side;
        }

        static AtlasViews from(Bitmap atlas) {
            int height = atlas.getHeight();
            int frontWidth = Math.max(128, Math.round(height * 0.5f));
            int sideWidth = Math.max(96, Math.round(height * 0.375f));
            int frontStart = ATLAS_GAP;
            int backStart = frontStart + frontWidth + ATLAS_GAP;
            int sideStart = backStart + frontWidth + ATLAS_GAP;

            if (sideStart + sideWidth > atlas.getWidth()) {
                throw new IllegalArgumentException("Atlas V4 incompatible");
            }
            return new AtlasViews(
                    Bitmap.createBitmap(
                            atlas,
                            frontStart,
                            0,
                            frontWidth,
                            height
                    ),
                    Bitmap.createBitmap(
                            atlas,
                            backStart,
                            0,
                            frontWidth,
                            height
                    ),
                    Bitmap.createBitmap(
                            atlas,
                            sideStart,
                            0,
                            sideWidth,
                            height
                    )
            );
        }

        void recycle() {
            if (!front.isRecycled()) front.recycle();
            if (!back.isRecycled()) back.recycle();
            if (!side.isRecycled()) side.recycle();
        }
    }

    private static final class Bounds {
        final float minX;
        final float minY;
        final float minZ;
        final float maxX;
        final float maxY;
        final float maxZ;

        Bounds(
                float minX,
                float minY,
                float minZ,
                float maxX,
                float maxY,
                float maxZ
        ) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }

        static Bounds from(float[] positions) {
            float minX = Float.POSITIVE_INFINITY;
            float minY = Float.POSITIVE_INFINITY;
            float minZ = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY;
            float maxY = Float.NEGATIVE_INFINITY;
            float maxZ = Float.NEGATIVE_INFINITY;
            for (int index = 0; index < positions.length; index += 3) {
                minX = Math.min(minX, positions[index]);
                minY = Math.min(minY, positions[index + 1]);
                minZ = Math.min(minZ, positions[index + 2]);
                maxX = Math.max(maxX, positions[index]);
                maxY = Math.max(maxY, positions[index + 1]);
                maxZ = Math.max(maxZ, positions[index + 2]);
            }
            return new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
        }
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
