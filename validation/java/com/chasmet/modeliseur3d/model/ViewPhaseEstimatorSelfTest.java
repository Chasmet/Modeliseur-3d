package com.chasmet.modeliseur3d.model;

public final class ViewPhaseEstimatorSelfTest {
    private ViewPhaseEstimatorSelfTest() {
    }

    public static void main(String[] args) {
        int width = 48;
        int height = 80;
        boolean[][] ordered = new boolean[8][];
        int[] widths = {30, 24, 14, 24, 29, 23, 13, 23};
        for (int i = 0; i < 8; i++) {
            ordered[i] = rectangle(width, height, widths[i], 62);
        }

        boolean[][] shifted = new boolean[8][];
        int shift = 3;
        for (int i = 0; i < 8; i++) {
            shifted[i] = ordered[(i + shift) % 8];
        }
        int phase = ViewPhaseEstimator.estimate(shifted, width, height);
        require(phase == 1 || phase == 5,
                "phase cardinale attendue 1 ou 5, obtenue " + phase);

        boolean[][] restored = ViewPhaseEstimator.rotate(shifted, phase);
        int frontWidth = visibleWidth(restored[0], width, height);
        int rightWidth = visibleWidth(restored[2], width, height);
        int backWidth = visibleWidth(restored[4], width, height);
        int leftWidth = visibleWidth(restored[6], width, height);
        require(frontWidth > rightWidth,
                "la vue 0 n'est pas plus large que le profil");
        require(backWidth > leftWidth,
                "la vue 4 n'est pas plus large que le profil");

        System.out.println("ViewPhaseEstimatorSelfTest : OK");
    }

    private static int visibleWidth(boolean[] mask, int width, int height) {
        int left = width;
        int right = -1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (mask[y * width + x]) {
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                }
            }
        }
        return right < left ? 0 : right - left + 1;
    }

    private static boolean[] rectangle(
            int width,
            int height,
            int rectangleWidth,
            int rectangleHeight
    ) {
        boolean[] mask = new boolean[width * height];
        int left = (width - rectangleWidth) / 2;
        int top = (height - rectangleHeight) / 2;
        for (int y = top; y < top + rectangleHeight; y++) {
            for (int x = left; x < left + rectangleWidth; x++) {
                mask[y * width + x] = true;
            }
        }
        return mask;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
