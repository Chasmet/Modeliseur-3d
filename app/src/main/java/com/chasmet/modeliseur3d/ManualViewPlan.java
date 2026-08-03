package com.chasmet.modeliseur3d;

/**
 * Ordre fixe des prises de vue utilisé par la reconstruction 3D guidée.
 * Les images suivent un tour horaire complet autour du sujet.
 */
public final class ManualViewPlan {
    public static final int VIEW_COUNT = 8;

    private static final int[] ANGLES = {
            0, 45, 90, 135, 180, 225, 270, 315
    };

    private static final String[] NAMES = {
            "Face",
            "Avant droit",
            "Profil droit",
            "Arrière droit",
            "Dos",
            "Arrière gauche",
            "Profil gauche",
            "Avant gauche"
    };

    /** Largeur visuelle du gabarit dessinée dans chaque case. */
    private static final float[] GUIDE_WIDTH_FACTORS = {
            0.72f, 0.61f, 0.45f, 0.61f,
            0.72f, 0.61f, 0.45f, 0.61f
    };

    /** Rapport largeur/hauteur attendu, avec une tolérance volontairement large. */
    private static final float[] TARGET_ASPECT_RATIOS = {
            0.46f, 0.39f, 0.30f, 0.39f,
            0.46f, 0.39f, 0.30f, 0.39f
    };

    private ManualViewPlan() {
    }

    public static int getAngleDegrees(int index) {
        checkIndex(index);
        return ANGLES[index];
    }

    public static String getName(int index) {
        checkIndex(index);
        return NAMES[index];
    }

    public static float getGuideWidthFactor(int index) {
        checkIndex(index);
        return GUIDE_WIDTH_FACTORS[index];
    }

    public static float getTargetAspectRatio(int index) {
        checkIndex(index);
        return TARGET_ASPECT_RATIOS[index];
    }

    public static boolean isFrontOrBack(int index) {
        checkIndex(index);
        return index == 0 || index == 4;
    }

    public static boolean isProfile(int index) {
        checkIndex(index);
        return index == 2 || index == 6;
    }

    public static boolean isThreeQuarter(int index) {
        checkIndex(index);
        return !isFrontOrBack(index) && !isProfile(index);
    }

    public static String getSlotLabel(int index) {
        return (index + 1)
                + " • "
                + getName(index)
                + " • "
                + getAngleDegrees(index)
                + "°";
    }

    public static int findFirstMissing(boolean[] selected) {
        if (selected == null || selected.length != VIEW_COUNT) {
            throw new IllegalArgumentException("Plan de vues incomplet");
        }
        for (int index = 0; index < selected.length; index++) {
            if (!selected[index]) {
                return index;
            }
        }
        return -1;
    }

    private static void checkIndex(int index) {
        if (index < 0 || index >= VIEW_COUNT) {
            throw new IndexOutOfBoundsException("Vue manuelle invalide : " + index);
        }
    }
}
