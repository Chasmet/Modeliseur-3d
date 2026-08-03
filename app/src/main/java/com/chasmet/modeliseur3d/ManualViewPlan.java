package com.chasmet.modeliseur3d;

/**
 * Ordre fixe des prises de vue utilisé par la reconstruction 3D manuelle.
 * Les images doivent suivre un tour horaire complet autour du sujet.
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
