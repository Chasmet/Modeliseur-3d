package com.chasmet.modeliseur3d.model;

import java.util.Arrays;

/**
 * Fusion volumique légère pilotée par la confiance de l'IA IS-Net.
 *
 * Les anciennes versions transformaient immédiatement les quatre sorties IA
 * en silhouettes binaires. Ici, chaque voxel reçoit un score issu des quatre
 * probabilités neuronales. Les détails fins peuvent être conservés quand deux
 * axes les confirment, tandis que l'enveloppe elliptique empêche les volumes
 * fantômes et le gonflement du personnage.
 */
public final class NeuralConfidenceHullBuilder {
    private NeuralConfidenceHullBuilder() {
    }

    public static Result build(
            boolean[][] hardMasks,
            float[][] neuralConfidence,
            int width,
            int height,
            int depth,
            boolean adaptive
    ) {
        validate(hardMasks, neuralConfidence, width, height, depth);
        RowPrior[] priors = buildRowPriors(hardMasks, width, height, depth);
        boolean[] volume = new boolean[width * height * depth];
        long confidenceSamples = 0L;
        double confidenceTotal = 0.0;
        int rescuedDetails = 0;
        int strongConsensusVoxels = 0;

        for (int y = 0; y < height; y++) {
            int frontRow = y * width;
            int sideRow = y * depth;
            RowPrior prior = priors[y];
            if (!prior.valid) {
                continue;
            }
            for (int x = 0; x < width; x++) {
                int frontIndex = frontRow + x;
                int backIndex = frontRow + (width - 1 - x);
                boolean frontHard = hardMasks[StylizedFourViewProjector.FRONT][frontIndex];
                boolean backHard = hardMasks[StylizedFourViewProjector.BACK][backIndex];
                float front = neuralConfidence[StylizedFourViewProjector.FRONT][frontIndex];
                float back = neuralConfidence[StylizedFourViewProjector.BACK][backIndex];
                for (int z = 0; z < depth; z++) {
                    int rightIndex = sideRow + z;
                    int leftIndex = sideRow + (depth - 1 - z);
                    boolean rightHard = hardMasks[StylizedFourViewProjector.RIGHT][rightIndex];
                    boolean leftHard = hardMasks[StylizedFourViewProjector.LEFT][leftIndex];
                    float right = neuralConfidence[StylizedFourViewProjector.RIGHT][rightIndex];
                    float left = neuralConfidence[StylizedFourViewProjector.LEFT][leftIndex];

                    int support = (frontHard ? 1 : 0)
                            + (backHard ? 1 : 0)
                            + (rightHard ? 1 : 0)
                            + (leftHard ? 1 : 0);
                    boolean frontAxisHard = frontHard || backHard;
                    boolean sideAxisHard = rightHard || leftHard;
                    float frontAxis = Math.max(front, back);
                    float sideAxis = Math.max(right, left);
                    float axisAgreement = Math.min(frontAxis, sideAxis);
                    float oppositeAgreement = 0.5f
                            * (Math.min(front, back) + Math.min(right, left));
                    float neuralScore = 0.76f * axisAgreement
                            + 0.24f * oppositeAgreement;

                    boolean exact = support == 4;
                    boolean strongConsensus = support >= 3
                            && neuralScore >= (adaptive ? 0.18f : 0.30f);
                    boolean axisConsensus = frontAxisHard
                            && sideAxisHard
                            && prior.accepts(x, z, false)
                            && neuralScore >= prior.threshold;
                    boolean neuralRescue = prior.accepts(x, z, true)
                            && frontAxis >= 0.62f
                            && sideAxis >= 0.52f
                            && neuralScore >= Math.max(0.16f, prior.threshold - 0.045f);

                    boolean occupied;
                    if (adaptive) {
                        occupied = exact || strongConsensus || axisConsensus || neuralRescue;
                    } else {
                        occupied = exact || strongConsensus
                                || (axisConsensus && neuralScore >= 0.36f);
                    }
                    if (!occupied) {
                        continue;
                    }
                    int voxel = index(x, y, z, width, depth);
                    volume[voxel] = true;
                    confidenceTotal += neuralScore;
                    confidenceSamples++;
                    if (strongConsensus || exact) {
                        strongConsensusVoxels++;
                    } else if (neuralRescue) {
                        rescuedDetails++;
                    }
                }
            }
        }

        int filledCracks = fillNarrowCracks(volume, width, height, depth);
        int occupied = StylizedFourViewProjector.countOccupied(volume);
        double meanConfidence = confidenceSamples == 0L
                ? 0.0
                : confidenceTotal / confidenceSamples;
        return new Result(
                volume,
                occupied,
                meanConfidence,
                strongConsensusVoxels,
                rescuedDetails,
                filledCracks
        );
    }

    public static float[] flipHorizontal(float[] source, int width, int height) {
        if (source == null || source.length != width * height) {
            throw new IllegalArgumentException("Confiance neuronale invalide");
        }
        float[] output = new float[source.length];
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                output[row + x] = source[row + width - 1 - x];
            }
        }
        return output;
    }

    private static RowPrior[] buildRowPriors(
            boolean[][] masks,
            int width,
            int height,
            int depth
    ) {
        RowPrior[] priors = new RowPrior[height];
        for (int y = 0; y < height; y++) {
            int frontRow = y * width;
            int sideRow = y * depth;
            int minX = width;
            int maxX = -1;
            int minZ = depth;
            int maxZ = -1;
            int frontPixels = 0;
            int sidePixels = 0;
            for (int x = 0; x < width; x++) {
                if (masks[StylizedFourViewProjector.FRONT][frontRow + x]
                        || masks[StylizedFourViewProjector.BACK][frontRow + width - 1 - x]) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    frontPixels++;
                }
            }
            for (int z = 0; z < depth; z++) {
                if (masks[StylizedFourViewProjector.RIGHT][sideRow + z]
                        || masks[StylizedFourViewProjector.LEFT][sideRow + depth - 1 - z]) {
                    minZ = Math.min(minZ, z);
                    maxZ = Math.max(maxZ, z);
                    sidePixels++;
                }
            }
            double density = 0.5 * (
                    frontPixels / Math.max(1.0, width)
                            + sidePixels / Math.max(1.0, depth)
            );
            priors[y] = new RowPrior(minX, maxX, minZ, maxZ, density);
        }
        smooth(priors);
        return priors;
    }

    private static void smooth(RowPrior[] priors) {
        if (priors.length < 3) {
            return;
        }
        RowPrior[] source = Arrays.copyOf(priors, priors.length);
        for (int y = 1; y < priors.length - 1; y++) {
            priors[y] = RowPrior.blend(source[y - 1], source[y], source[y + 1]);
        }
    }

    private static int fillNarrowCracks(
            boolean[] volume,
            int width,
            int height,
            int depth
    ) {
        boolean[] source = Arrays.copyOf(volume, volume.length);
        int rowStride = width * depth;
        int filled = 0;
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                for (int z = 1; z < depth - 1; z++) {
                    int current = index(x, y, z, width, depth);
                    if (source[current]) {
                        continue;
                    }
                    boolean xPair = source[current - depth] && source[current + depth];
                    boolean yPair = source[current - rowStride] && source[current + rowStride];
                    boolean zPair = source[current - 1] && source[current + 1];
                    if ((xPair && yPair) || (xPair && zPair) || (yPair && zPair)) {
                        volume[current] = true;
                        filled++;
                    }
                }
            }
        }
        return filled;
    }

    private static void validate(
            boolean[][] hardMasks,
            float[][] confidence,
            int width,
            int height,
            int depth
    ) {
        if (hardMasks == null || confidence == null
                || hardMasks.length != 4 || confidence.length != 4) {
            throw new IllegalArgumentException("Quatre sorties neuronales sont requises");
        }
        int frontLength = width * height;
        int sideLength = depth * height;
        for (int index = 0; index < 4; index++) {
            int expected = index == StylizedFourViewProjector.RIGHT
                    || index == StylizedFourViewProjector.LEFT
                    ? sideLength
                    : frontLength;
            if (hardMasks[index] == null || hardMasks[index].length != expected
                    || confidence[index] == null || confidence[index].length != expected) {
                throw new IllegalArgumentException("Dimensions neuronales incohérentes");
            }
        }
    }

    private static int index(int x, int y, int z, int width, int depth) {
        return (y * width + x) * depth + z;
    }

    private static final class RowPrior {
        final double centerX;
        final double radiusX;
        final double centerZ;
        final double radiusZ;
        final float threshold;
        final boolean valid;

        RowPrior(int minX, int maxX, int minZ, int maxZ, double density) {
            valid = maxX >= minX && maxZ >= minZ;
            centerX = valid ? (minX + maxX) * 0.5 : 0.0;
            centerZ = valid ? (minZ + maxZ) * 0.5 : 0.0;
            double radiusScale = density < 0.28 ? 0.48 : density > 0.62 ? 0.57 : 0.53;
            radiusX = valid ? Math.max(1.0, (maxX - minX + 1) * radiusScale) : 1.0;
            radiusZ = valid ? Math.max(1.0, (maxZ - minZ + 1) * radiusScale) : 1.0;
            threshold = density < 0.24 ? 0.205f : density > 0.58 ? 0.285f : 0.245f;
        }

        private RowPrior(
                double centerX,
                double radiusX,
                double centerZ,
                double radiusZ,
                float threshold,
                boolean valid
        ) {
            this.centerX = centerX;
            this.radiusX = radiusX;
            this.centerZ = centerZ;
            this.radiusZ = radiusZ;
            this.threshold = threshold;
            this.valid = valid;
        }

        boolean accepts(int x, int z, boolean tight) {
            if (!valid) {
                return false;
            }
            double scale = tight ? 0.86 : 1.0;
            double nx = (x - centerX) / Math.max(1.0, radiusX * scale);
            double nz = (z - centerZ) / Math.max(1.0, radiusZ * scale);
            return nx * nx + nz * nz <= (tight ? 1.0 : 1.14);
        }

        static RowPrior blend(RowPrior previous, RowPrior current, RowPrior next) {
            if (!current.valid) {
                return current;
            }
            double total = 2.0;
            double centerX = current.centerX * 2.0;
            double radiusX = current.radiusX * 2.0;
            double centerZ = current.centerZ * 2.0;
            double radiusZ = current.radiusZ * 2.0;
            double threshold = current.threshold * 2.0;
            if (previous.valid) {
                centerX += previous.centerX;
                radiusX += previous.radiusX;
                centerZ += previous.centerZ;
                radiusZ += previous.radiusZ;
                threshold += previous.threshold;
                total += 1.0;
            }
            if (next.valid) {
                centerX += next.centerX;
                radiusX += next.radiusX;
                centerZ += next.centerZ;
                radiusZ += next.radiusZ;
                threshold += next.threshold;
                total += 1.0;
            }
            return new RowPrior(
                    centerX / total,
                    radiusX / total,
                    centerZ / total,
                    radiusZ / total,
                    (float) (threshold / total),
                    true
            );
        }
    }

    public static final class Result {
        private final boolean[] volume;
        private final int occupiedVoxels;
        private final double meanConfidence;
        private final int strongConsensusVoxels;
        private final int rescuedDetailVoxels;
        private final int filledCracks;

        Result(
                boolean[] volume,
                int occupiedVoxels,
                double meanConfidence,
                int strongConsensusVoxels,
                int rescuedDetailVoxels,
                int filledCracks
        ) {
            this.volume = volume;
            this.occupiedVoxels = occupiedVoxels;
            this.meanConfidence = meanConfidence;
            this.strongConsensusVoxels = strongConsensusVoxels;
            this.rescuedDetailVoxels = rescuedDetailVoxels;
            this.filledCracks = filledCracks;
        }

        public boolean[] getVolume() {
            return volume;
        }

        public int getOccupiedVoxels() {
            return occupiedVoxels;
        }

        public double getMeanConfidence() {
            return meanConfidence;
        }

        public int getStrongConsensusVoxels() {
            return strongConsensusVoxels;
        }

        public int getRescuedDetailVoxels() {
            return rescuedDetailVoxels;
        }

        public int getFilledCracks() {
            return filledCracks;
        }
    }
}
