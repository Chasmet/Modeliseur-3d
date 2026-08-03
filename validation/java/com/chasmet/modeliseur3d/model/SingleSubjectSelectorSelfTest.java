package com.chasmet.modeliseur3d.model;

public final class SingleSubjectSelectorSelfTest {
    private SingleSubjectSelectorSelfTest() {
    }

    public static void main(String[] args) {
        selectsCenteredSubjectFromReferenceSheet();
        keepsNearbyDisconnectedAccessory();
        prefersLargeCompleteSubjectOverTinyCenterNoise();
        System.out.println("SingleSubjectSelectorSelfTest : OK");
    }

    private static void selectsCenteredSubjectFromReferenceSheet() {
        int width = 120;
        int height = 80;
        boolean[] mask = new boolean[width * height];
        rectangle(mask, width, 4, 12, 24, 68);
        rectangle(mask, width, 46, 6, 75, 74);
        rectangle(mask, width, 94, 15, 114, 67);
        SingleSubjectSelector.Selection selection =
                SingleSubjectSelector.select(mask, width, height);
        require(selection.getLeft() >= 44 && selection.getRight() <= 77,
                "Le personnage central doit être sélectionné");
        require(selection.getDetectedSubjectCount() == 3,
                "Trois sujets significatifs doivent être détectés");
    }

    private static void keepsNearbyDisconnectedAccessory() {
        int width = 100;
        int height = 100;
        boolean[] mask = new boolean[width * height];
        rectangle(mask, width, 30, 12, 68, 88);
        rectangle(mask, width, 70, 40, 75, 62);
        SingleSubjectSelector.Selection selection =
                SingleSubjectSelector.select(mask, width, height);
        require(selection.getRight() >= 74,
                "L'accessoire proche doit être fusionné au sujet principal");
    }

    private static void prefersLargeCompleteSubjectOverTinyCenterNoise() {
        int width = 100;
        int height = 100;
        boolean[] mask = new boolean[width * height];
        rectangle(mask, width, 8, 8, 39, 92);
        rectangle(mask, width, 48, 48, 53, 53);
        SingleSubjectSelector.Selection selection =
                SingleSubjectSelector.select(mask, width, height);
        require(selection.getLeft() <= 10 && selection.getRight() >= 38,
                "Le grand personnage doit gagner contre le bruit central");
    }

    private static void rectangle(
            boolean[] mask,
            int width,
            int left,
            int top,
            int right,
            int bottom
    ) {
        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) {
                mask[y * width + x] = true;
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
