package com.chasmet.modeliseur3d.model;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;

/**
 * Redresse les quatre vues après le détourage neural et avant la création du
 * volume. Les bitmaps sont déjà transparents : l'orientation peut donc être
 * déterminée à partir de la silhouette sans modifier le moteur 2.5D.
 */
public final class FourViewBitmapOrientationNormalizer {
    private static final float FOREGROUND_ALPHA = 24.0f;
    private static final int PROFILE_BINS = 128;

    private FourViewBitmapOrientationNormalizer() {
    }

    public static Result normalize(Bitmap[] images, Rect[] bounds) {
        validate(images, bounds);
        int quarterTurns = 0;
        int halfTurns = 0;

        // Le dos doit avoir le même haut et le même bas que la face.
        double[] frontRows = rowProfile(
                images[StylizedFourViewProjector.FRONT],
                bounds[StylizedFourViewProjector.FRONT]
        );
        int backIndex = StylizedFourViewProjector.BACK;
        double[] backRows = rowProfile(images[backIndex], bounds[backIndex]);
        if (similarity(frontRows, reversed(backRows))
                > similarity(frontRows, backRows) + 0.075) {
            replaceRotated(images, bounds, backIndex, 180.0f);
            halfTurns++;
            backRows = rowProfile(images[backIndex], bounds[backIndex]);
        }

        double[] reference = average(frontRows, backRows);
        double referenceAspect = averageAspect(
                bounds[StylizedFourViewProjector.FRONT],
                bounds[StylizedFourViewProjector.BACK]
        );

        int[] profileIndices = {
                StylizedFourViewProjector.RIGHT,
                StylizedFourViewProjector.LEFT
        };
        for (int index : profileIndices) {
            Rect currentBounds = bounds[index];
            double aspect = currentBounds.width()
                    / Math.max(1.0, currentBounds.height());
            boolean horizontalPose = aspect > 1.10
                    && (referenceAspect < 0.98 || aspect > referenceAspect * 1.30);

            if (horizontalPose) {
                Candidate clockwise = candidate(images[index], 90.0f, reference);
                Candidate counterClockwise = candidate(images[index], -90.0f, reference);
                Candidate selected = clockwise.score >= counterClockwise.score
                        ? clockwise
                        : counterClockwise;
                Candidate rejected = selected == clockwise
                        ? counterClockwise
                        : clockwise;
                replace(images, bounds, index, selected.bitmap, selected.bounds);
                recycle(rejected.bitmap);
                quarterTurns++;
            } else {
                double[] direct = rowProfile(images[index], currentBounds);
                if (similarity(reference, reversed(direct))
                        > similarity(reference, direct) + 0.09) {
                    replaceRotated(images, bounds, index, 180.0f);
                    halfTurns++;
                }
            }
        }

        String summary;
        if (quarterTurns == 0 && halfTurns == 0) {
            summary = "orientation verticale vérifiée";
        } else if (quarterTurns > 0 && halfTurns > 0) {
            summary = quarterTurns + " profil(s) redressé(s) à 90° et "
                    + halfTurns + " vue(s) retournée(s) à 180°";
        } else if (quarterTurns > 0) {
            summary = quarterTurns + " profil(s) redressé(s) automatiquement à 90°";
        } else {
            summary = halfTurns + " vue(s) retournée(s) automatiquement à 180°";
        }
        return new Result(quarterTurns, halfTurns, summary);
    }

    private static Candidate candidate(
            Bitmap source,
            float degrees,
            double[] reference
    ) {
        Bitmap rotated = rotate(source, degrees);
        Rect rotatedBounds = findForegroundBounds(rotated);
        double score = similarity(reference, rowProfile(rotated, rotatedBounds));
        return new Candidate(rotated, rotatedBounds, score);
    }

    private static void replaceRotated(
            Bitmap[] images,
            Rect[] bounds,
            int index,
            float degrees
    ) {
        Bitmap rotated = rotate(images[index], degrees);
        Rect rotatedBounds = findForegroundBounds(rotated);
        replace(images, bounds, index, rotated, rotatedBounds);
    }

    private static void replace(
            Bitmap[] images,
            Rect[] bounds,
            int index,
            Bitmap replacement,
            Rect replacementBounds
    ) {
        Bitmap previous = images[index];
        images[index] = replacement;
        bounds[index] = replacementBounds;
        if (previous != replacement) {
            recycle(previous);
        }
    }

    private static Bitmap rotate(Bitmap source, float degrees) {
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        return Bitmap.createBitmap(
                source,
                0,
                0,
                source.getWidth(),
                source.getHeight(),
                matrix,
                true
        );
    }

    private static Rect findForegroundBounds(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        int left = width;
        int top = height;
        int right = -1;
        int bottom = -1;
        int foreground = 0;
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                if (Color.alpha(pixels[row + x]) > FOREGROUND_ALPHA) {
                    foreground++;
                    left = Math.min(left, x);
                    top = Math.min(top, y);
                    right = Math.max(right, x);
                    bottom = Math.max(bottom, y);
                }
            }
        }
        if (right < left || bottom < top || foreground < 24) {
            throw new IllegalArgumentException(
                    "Orientation impossible : silhouette trop petite"
            );
        }
        return new Rect(left, top, right + 1, bottom + 1);
    }

    private static double[] rowProfile(Bitmap bitmap, Rect bounds) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        double[] profile = new double[PROFILE_BINS];
        double maximum = 1.0;
        for (int bin = 0; bin < PROFILE_BINS; bin++) {
            int startY = bounds.top + (int) Math.floor(
                    bin * bounds.height() / (double) PROFILE_BINS
            );
            int endY = bounds.top + (int) Math.ceil(
                    (bin + 1) * bounds.height() / (double) PROFILE_BINS
            );
            startY = Math.max(bounds.top, Math.min(bounds.bottom - 1, startY));
            endY = Math.max(startY + 1, Math.min(bounds.bottom, endY));
            int count = 0;
            int samples = 0;
            for (int y = startY; y < endY; y++) {
                int row = y * width;
                for (int x = bounds.left; x < bounds.right; x++) {
                    samples++;
                    if (Color.alpha(pixels[row + x]) > FOREGROUND_ALPHA) {
                        count++;
                    }
                }
            }
            profile[bin] = count / Math.max(1.0, samples);
            maximum = Math.max(maximum, profile[bin]);
        }
        for (int index = 0; index < profile.length; index++) {
            profile[index] /= maximum;
        }
        return profile;
    }

    private static double similarity(double[] first, double[] second) {
        if (first.length != second.length) {
            throw new IllegalArgumentException("Profils verticaux incohérents");
        }
        double difference = 0.0;
        double weightTotal = 0.0;
        for (int index = 0; index < first.length; index++) {
            double position = index / Math.max(1.0, first.length - 1.0);
            double weight = 1.0 + 0.35 * Math.abs(position - 0.5) * 2.0;
            difference += Math.abs(first[index] - second[index]) * weight;
            weightTotal += weight;
        }
        return 1.0 - difference / Math.max(1.0, weightTotal);
    }

    private static double[] average(double[] first, double[] second) {
        double[] output = new double[first.length];
        for (int index = 0; index < output.length; index++) {
            output[index] = (first[index] + second[index]) * 0.5;
        }
        return output;
    }

    private static double[] reversed(double[] source) {
        double[] output = new double[source.length];
        for (int index = 0; index < source.length; index++) {
            output[index] = source[source.length - 1 - index];
        }
        return output;
    }

    private static double averageAspect(Rect first, Rect second) {
        double firstAspect = first.width() / Math.max(1.0, first.height());
        double secondAspect = second.width() / Math.max(1.0, second.height());
        return (firstAspect + secondAspect) * 0.5;
    }

    private static void validate(Bitmap[] images, Rect[] bounds) {
        if (images == null || bounds == null
                || images.length != 4 || bounds.length != 4) {
            throw new IllegalArgumentException("Quatre vues détourées sont requises");
        }
        for (int index = 0; index < 4; index++) {
            if (images[index] == null || images[index].isRecycled()
                    || bounds[index] == null || bounds[index].isEmpty()) {
                throw new IllegalArgumentException("Vue détourée invalide");
            }
        }
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }

    private static final class Candidate {
        final Bitmap bitmap;
        final Rect bounds;
        final double score;

        Candidate(Bitmap bitmap, Rect bounds, double score) {
            this.bitmap = bitmap;
            this.bounds = bounds;
            this.score = score;
        }
    }

    public static final class Result {
        private final int quarterTurns;
        private final int halfTurns;
        private final String summary;

        Result(int quarterTurns, int halfTurns, String summary) {
            this.quarterTurns = quarterTurns;
            this.halfTurns = halfTurns;
            this.summary = summary;
        }

        public int getQuarterTurns() {
            return quarterTurns;
        }

        public int getHalfTurns() {
            return halfTurns;
        }

        public boolean hasCorrection() {
            return quarterTurns > 0 || halfTurns > 0;
        }

        public String getSummary() {
            return summary;
        }
    }
}
