package com.chasmet.modeliseur3d.model;

/**
 * Transforme les silhouettes binaires issues d'IS-Net en cartes de confiance
 * locales. Le cœur d'une zone détectée reçoit une confiance forte, les bords
 * une confiance progressive et les pixels isolés une confiance faible.
 */
final class NeuralMaskConfidence {
    private NeuralMaskConfidence() {
    }

    static NeuralConfidenceHullBuilder.Result build(
            boolean[][] masks,
            int width,
            int height,
            int depth,
            boolean adaptive
    ) {
        if (!adaptive) {
            return strictIntersection(masks, width, height, depth);
        }
        float[][] confidence = new float[4][];
        confidence[StylizedFourViewProjector.FRONT] = soften(
                masks[StylizedFourViewProjector.FRONT], width, height
        );
        confidence[StylizedFourViewProjector.BACK] = soften(
                masks[StylizedFourViewProjector.BACK], width, height
        );
        confidence[StylizedFourViewProjector.RIGHT] = soften(
                masks[StylizedFourViewProjector.RIGHT], depth, height
        );
        confidence[StylizedFourViewProjector.LEFT] = soften(
                masks[StylizedFourViewProjector.LEFT], depth, height
        );
        return NeuralConfidenceHullBuilder.build(
                masks,
                confidence,
                width,
                height,
                depth,
                true
        );
    }

    private static NeuralConfidenceHullBuilder.Result strictIntersection(
            boolean[][] masks,
            int width,
            int height,
            int depth
    ) {
        boolean[] volume = new boolean[width * height * depth];
        int occupied = 0;
        for (int y = 0; y < height; y++) {
            int frontRow = y * width;
            int sideRow = y * depth;
            for (int x = 0; x < width; x++) {
                boolean front = masks[StylizedFourViewProjector.FRONT][frontRow + x];
                boolean back = masks[StylizedFourViewProjector.BACK][
                        frontRow + width - 1 - x
                ];
                if (!front || !back) {
                    continue;
                }
                for (int z = 0; z < depth; z++) {
                    boolean right = masks[StylizedFourViewProjector.RIGHT][sideRow + z];
                    boolean left = masks[StylizedFourViewProjector.LEFT][
                            sideRow + depth - 1 - z
                    ];
                    if (right && left) {
                        volume[(y * width + x) * depth + z] = true;
                        occupied++;
                    }
                }
            }
        }
        return new NeuralConfidenceHullBuilder.Result(
                volume,
                occupied,
                occupied == 0 ? 0.0 : 1.0,
                occupied,
                0,
                0
        );
    }

    private static float[] soften(boolean[] mask, int width, int height) {
        if (mask == null || mask.length != width * height) {
            throw new IllegalArgumentException("Silhouette neuronale invalide");
        }
        float[] confidence = new float[mask.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                int enabled = 0;
                int samples = 0;
                for (int oy = -2; oy <= 2; oy++) {
                    int sy = y + oy;
                    if (sy < 0 || sy >= height) {
                        continue;
                    }
                    for (int ox = -2; ox <= 2; ox++) {
                        int sx = x + ox;
                        if (sx < 0 || sx >= width) {
                            continue;
                        }
                        samples++;
                        if (mask[sy * width + sx]) {
                            enabled++;
                        }
                    }
                }
                float density = enabled / Math.max(1.0f, samples);
                if (mask[index]) {
                    confidence[index] = 0.34f + 0.66f * density;
                } else if (density >= 0.32f) {
                    confidence[index] = 0.08f + 0.22f * density;
                } else {
                    confidence[index] = 0.0f;
                }
            }
        }
        return confidence;
    }
}
