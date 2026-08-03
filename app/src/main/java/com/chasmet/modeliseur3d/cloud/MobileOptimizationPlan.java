package com.chasmet.modeliseur3d.cloud;

/**
 * Presets ordonnes du plus detaille au plus compact pour obtenir un GLB de jeu
 * inferieur ou egal a 200 000 octets. La version HD d'origine est toujours
 * conservee sans cette reduction.
 */
public final class MobileOptimizationPlan {
    public static final long TARGET_BYTES = 200_000L;

    private static final Preset[] PRESETS = {
            new Preset("mobile-qualite", 1600, 256),
            new Preset("mobile-equilibre", 900, 128),
            new Preset("mobile-compact", 450, 128),
            new Preset("mobile-secours", 180, 128)
    };

    private MobileOptimizationPlan() {
    }

    public static int count() {
        return PRESETS.length;
    }

    public static Preset at(int index) {
        if (index < 0 || index >= PRESETS.length) {
            throw new IndexOutOfBoundsException("Preset mobile invalide");
        }
        return PRESETS[index];
    }

    public static boolean meetsTarget(long bytes) {
        return bytes > 0L && bytes <= TARGET_BYTES;
    }

    public static final class Preset {
        private final String label;
        private final int faceLimit;
        private final int textureSize;

        Preset(String label, int faceLimit, int textureSize) {
            this.label = label;
            this.faceLimit = faceLimit;
            this.textureSize = textureSize;
        }

        public String getLabel() {
            return label;
        }

        public int getFaceLimit() {
            return faceLimit;
        }

        public int getTextureSize() {
            return textureSize;
        }
    }
}
