package com.chasmet.modeliseur3d.model;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;

import java.util.List;

/** Analyse légère affichée avant la génération ONNX complète. */
public final class QuickFourViewValidator {
    private static final int GRID_WIDTH = 96;
    private static final int GRID_HEIGHT = 128;

    private QuickFourViewValidator() {
    }

    public static Result analyze(List<Bitmap> views) {
        if (views == null || views.size() != 4) {
            throw new IllegalArgumentException("Quatre vues sont requises");
        }
        boolean[][] masks = new boolean[4][];
        for (int index = 0; index < views.size(); index++) {
            masks[index] = createNormalizedMask(views.get(index));
        }

        double faceBack = Math.max(
                FourViewAutoCorrector.bestDice(
                        masks[StylizedFourViewProjector.FRONT],
                        masks[StylizedFourViewProjector.BACK],
                        GRID_WIDTH,
                        GRID_HEIGHT,
                        false
                ),
                FourViewAutoCorrector.bestDice(
                        masks[StylizedFourViewProjector.FRONT],
                        masks[StylizedFourViewProjector.BACK],
                        GRID_WIDTH,
                        GRID_HEIGHT,
                        true
                )
        );
        FourViewAutoCorrector.ProfileCorrection profiles =
                FourViewAutoCorrector.analyzeProfiles(
                        masks[StylizedFourViewProjector.RIGHT],
                        masks[StylizedFourViewProjector.LEFT],
                        GRID_WIDTH,
                        GRID_HEIGHT
                );
        double coherence = FourViewAutoCorrector.computeCoherence(
                masks[StylizedFourViewProjector.FRONT],
                masks[StylizedFourViewProjector.BACK],
                GRID_WIDTH,
                masks[StylizedFourViewProjector.RIGHT],
                masks[StylizedFourViewProjector.LEFT],
                GRID_WIDTH,
                GRID_HEIGHT
        );

        boolean faceBackWarning = faceBack < 0.18;
        boolean profileWarning = profiles.getSelectedScore() < 0.18;
        String message;
        if (profiles.shouldFlipLeft()) {
            message = "Profil détecté en miroir — correction automatique appliquée.";
        } else if (faceBackWarning || profileWarning || coherence < 0.42) {
            message = "Vues différentes — le mode adaptatif corrigera l'échelle et la profondeur.";
        } else {
            message = "Face, dos et profils cohérents — génération prête.";
        }
        return new Result(
                profiles.shouldFlipLeft(),
                faceBackWarning,
                profileWarning,
                coherence,
                message
        );
    }

    private static boolean[] createNormalizedMask(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            throw new IllegalArgumentException("Image invalide");
        }
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        int background = averageCorners(pixels, width, height);
        boolean transparentBackground = cornersMostlyTransparent(pixels, width, height);
        int left = width;
        int top = height;
        int right = -1;
        int bottom = -1;
        boolean[] raw = new boolean[pixels.length];
        int foreground = 0;
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int color = pixels[row + x];
                boolean visible = transparentBackground
                        ? Color.alpha(color) > 28
                        : Color.alpha(color) > 28 && colorDistance(color, background) > 38;
                if (visible) {
                    raw[row + x] = true;
                    foreground++;
                    left = Math.min(left, x);
                    top = Math.min(top, y);
                    right = Math.max(right, x);
                    bottom = Math.max(bottom, y);
                }
            }
        }
        if (foreground < Math.max(32, width * height / 2500) || right < left || bottom < top) {
            throw new IllegalArgumentException("Personnage non détecté sur une vue");
        }
        Rect bounds = new Rect(left, top, right + 1, bottom + 1);
        return normalize(raw, width, height, bounds);
    }

    private static boolean[] normalize(
            boolean[] raw,
            int sourceWidth,
            int sourceHeight,
            Rect bounds
    ) {
        boolean[] output = new boolean[GRID_WIDTH * GRID_HEIGHT];
        int drawHeight = Math.round(GRID_HEIGHT * 0.90f);
        int drawWidth = Math.max(1, Math.round(
                bounds.width() * drawHeight / Math.max(1.0f, bounds.height())
        ));
        drawWidth = Math.min(Math.round(GRID_WIDTH * 0.94f), drawWidth);
        int offsetX = (GRID_WIDTH - drawWidth) / 2;
        int offsetY = (GRID_HEIGHT - drawHeight) / 2;
        for (int y = 0; y < drawHeight; y++) {
            int sourceY = Math.min(
                    bounds.bottom - 1,
                    bounds.top + (int) ((y + 0.5f) * bounds.height() / drawHeight)
            );
            for (int x = 0; x < drawWidth; x++) {
                int sourceX = Math.min(
                        bounds.right - 1,
                        bounds.left + (int) ((x + 0.5f) * bounds.width() / drawWidth)
                );
                if (raw[sourceY * sourceWidth + sourceX]) {
                    output[(offsetY + y) * GRID_WIDTH + offsetX + x] = true;
                }
            }
        }
        return output;
    }

    private static int averageCorners(int[] pixels, int width, int height) {
        int marginX = Math.max(1, width / 12);
        int marginY = Math.max(1, height / 12);
        long red = 0;
        long green = 0;
        long blue = 0;
        long count = 0;
        for (int y = 0; y < height; y++) {
            boolean verticalEdge = y < marginY || y >= height - marginY;
            for (int x = 0; x < width; x++) {
                if (!verticalEdge && x >= marginX && x < width - marginX) {
                    continue;
                }
                int color = pixels[y * width + x];
                if (Color.alpha(color) < 16) {
                    continue;
                }
                red += Color.red(color);
                green += Color.green(color);
                blue += Color.blue(color);
                count++;
            }
        }
        if (count == 0) {
            return Color.TRANSPARENT;
        }
        return Color.rgb((int) (red / count), (int) (green / count), (int) (blue / count));
    }

    private static boolean cornersMostlyTransparent(int[] pixels, int width, int height) {
        int[][] points = {
                {0, 0},
                {width - 1, 0},
                {0, height - 1},
                {width - 1, height - 1}
        };
        int transparent = 0;
        for (int[] point : points) {
            if (Color.alpha(pixels[point[1] * width + point[0]]) < 32) {
                transparent++;
            }
        }
        return transparent >= 2;
    }

    private static int colorDistance(int first, int second) {
        int red = Color.red(first) - Color.red(second);
        int green = Color.green(first) - Color.green(second);
        int blue = Color.blue(first) - Color.blue(second);
        return (int) Math.sqrt(red * red + green * green + blue * blue);
    }

    public static final class Result {
        private final boolean mirrorCorrection;
        private final boolean faceBackWarning;
        private final boolean profileWarning;
        private final double coherence;
        private final String message;

        Result(
                boolean mirrorCorrection,
                boolean faceBackWarning,
                boolean profileWarning,
                double coherence,
                String message
        ) {
            this.mirrorCorrection = mirrorCorrection;
            this.faceBackWarning = faceBackWarning;
            this.profileWarning = profileWarning;
            this.coherence = coherence;
            this.message = message;
        }

        public boolean hasMirrorCorrection() {
            return mirrorCorrection;
        }

        public boolean hasFaceBackWarning() {
            return faceBackWarning;
        }

        public boolean hasProfileWarning() {
            return profileWarning;
        }

        public double getCoherence() {
            return coherence;
        }

        public String getMessage() {
            return message;
        }
    }
}
