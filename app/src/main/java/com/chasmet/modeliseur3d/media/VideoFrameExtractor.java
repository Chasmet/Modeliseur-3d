package com.chasmet.modeliseur3d.media;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Extrait huit vues nettes d'une courte vidéo de rotation, entièrement en local.
 * Le MP4 n'est jamais envoyé ni copié hors du téléphone.
 */
public final class VideoFrameExtractor {
    public static final int VIEW_COUNT = 8;
    public static final long MAXIMUM_DURATION_MS = 120_000L;

    private static final long MINIMUM_DURATION_MS = 1_200L;
    private static final int SCORE_MAX_SIDE = 320;
    private static final int OUTPUT_MAX_SIDE = 720;
    private static final double[] TARGET_FRACTIONS = {
            0.025, 0.145, 0.265, 0.385,
            0.505, 0.625, 0.745, 0.865
    };
    private static final double[] CANDIDATE_OFFSETS = {-1.0, 0.0, 1.0};
    private static final double WINDOW_FRACTION = 0.018;

    private final Context context;

    public VideoFrameExtractor(Context context) {
        this.context = context.getApplicationContext();
    }

    public Result extract(Uri videoUri, ProgressListener listener)
            throws IOException {
        if (videoUri == null) {
            throw new IllegalArgumentException("Vidéo absente");
        }

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        List<Bitmap> output = new ArrayList<>(VIEW_COUNT);
        try {
            retriever.setDataSource(context, videoUri);
            long durationMs = readLongMetadata(
                    retriever,
                    MediaMetadataRetriever.METADATA_KEY_DURATION
            );
            if (durationMs < MINIMUM_DURATION_MS) {
                throw new IOException(
                        "La vidéo est trop courte pour extraire huit angles"
                );
            }
            if (durationMs > MAXIMUM_DURATION_MS) {
                throw new IOException(
                        "La vidéo dépasse 2 minutes : garde seulement la rotation utile"
                );
            }

            int sourceWidth = readIntMetadata(
                    retriever,
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            );
            int sourceHeight = readIntMetadata(
                    retriever,
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            );
            int rotation = readIntMetadata(
                    retriever,
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
            );
            if (sourceWidth <= 0 || sourceHeight <= 0) {
                sourceWidth = 1920;
                sourceHeight = 1080;
            }

            long durationUs = durationMs * 1000L;
            long[] selectedTimesUs = new long[VIEW_COUNT];
            for (int viewIndex = 0; viewIndex < VIEW_COUNT; viewIndex++) {
                notifyProgress(listener, viewIndex + 1, VIEW_COUNT);
                long selectedUs = selectSharpestTime(
                        retriever,
                        durationUs,
                        sourceWidth,
                        sourceHeight,
                        viewIndex
                );
                selectedTimesUs[viewIndex] = selectedUs;
                Bitmap frame = readScaledFrame(
                        retriever,
                        selectedUs,
                        sourceWidth,
                        sourceHeight,
                        OUTPUT_MAX_SIDE
                );
                if (frame == null) {
                    throw new IOException(
                            "Impossible de lire la vue " + (viewIndex + 1)
                    );
                }
                frame = rotateIfNeeded(frame, rotation);
                output.add(ensureArgb(frame));
            }

            ensureRotationIsVisible(output);
            return new Result(output, selectedTimesUs, durationMs);
        } catch (RuntimeException error) {
            recycleAll(output);
            throw new IOException("Lecture de la vidéo impossible", error);
        } catch (IOException error) {
            recycleAll(output);
            throw error;
        } finally {
            retriever.release();
        }
    }

    private static long selectSharpestTime(
            MediaMetadataRetriever retriever,
            long durationUs,
            int sourceWidth,
            int sourceHeight,
            int viewIndex
    ) {
        long bestTimeUs = clampTime(
                Math.round(durationUs * TARGET_FRACTIONS[viewIndex]),
                durationUs
        );
        double bestScore = Double.NEGATIVE_INFINITY;
        for (double offset : CANDIDATE_OFFSETS) {
            long candidateUs = clampTime(
                    Math.round(durationUs * (
                            TARGET_FRACTIONS[viewIndex]
                                    + WINDOW_FRACTION * offset
                    )),
                    durationUs
            );
            Bitmap candidate = readScaledFrame(
                    retriever,
                    candidateUs,
                    sourceWidth,
                    sourceHeight,
                    SCORE_MAX_SIDE
            );
            if (candidate == null) {
                continue;
            }
            try {
                double score = sharpnessAndExposureScore(candidate);
                if (score > bestScore) {
                    bestScore = score;
                    bestTimeUs = candidateUs;
                }
            } finally {
                candidate.recycle();
            }
        }
        return bestTimeUs;
    }

    private static Bitmap readScaledFrame(
            MediaMetadataRetriever retriever,
            long timeUs,
            int sourceWidth,
            int sourceHeight,
            int maximumSide
    ) {
        int[] dimensions = fitInside(sourceWidth, sourceHeight, maximumSide);
        Bitmap frame;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            frame = retriever.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    dimensions[0],
                    dimensions[1]
            );
        } else {
            frame = retriever.getFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST
            );
            if (frame != null
                    && (frame.getWidth() > maximumSide
                    || frame.getHeight() > maximumSide)) {
                int[] actual = fitInside(
                        frame.getWidth(),
                        frame.getHeight(),
                        maximumSide
                );
                Bitmap scaled = Bitmap.createScaledBitmap(
                        frame,
                        actual[0],
                        actual[1],
                        true
                );
                if (scaled != frame) {
                    frame.recycle();
                }
                frame = scaled;
            }
        }
        return frame;
    }

    private static Bitmap rotateIfNeeded(Bitmap source, int rotation) {
        if (rotation != 90 && rotation != 180 && rotation != 270) {
            return source;
        }
        Matrix matrix = new Matrix();
        matrix.postRotate(rotation);
        Bitmap rotated = Bitmap.createBitmap(
                source,
                0,
                0,
                source.getWidth(),
                source.getHeight(),
                matrix,
                true
        );
        if (rotated != source) {
            source.recycle();
        }
        return rotated;
    }

    private static Bitmap ensureArgb(Bitmap source) {
        if (source.getConfig() == Bitmap.Config.ARGB_8888) {
            return source;
        }
        Bitmap converted = source.copy(Bitmap.Config.ARGB_8888, false);
        source.recycle();
        if (converted == null) {
            throw new IllegalStateException("Conversion de la trame impossible");
        }
        return converted;
    }

    private static double sharpnessAndExposureScore(Bitmap bitmap) {
        int step = Math.max(
                1,
                Math.max(bitmap.getWidth(), bitmap.getHeight()) / 150
        );
        double laplacianSum = 0.0;
        double laplacianSquareSum = 0.0;
        double luminanceSum = 0.0;
        int samples = 0;

        for (int y = step; y < bitmap.getHeight() - step; y += step) {
            for (int x = step; x < bitmap.getWidth() - step; x += step) {
                double center = luminance(bitmap.getPixel(x, y));
                double laplacian = 4.0 * center
                        - luminance(bitmap.getPixel(x - step, y))
                        - luminance(bitmap.getPixel(x + step, y))
                        - luminance(bitmap.getPixel(x, y - step))
                        - luminance(bitmap.getPixel(x, y + step));
                laplacianSum += laplacian;
                laplacianSquareSum += laplacian * laplacian;
                luminanceSum += center;
                samples++;
            }
        }
        if (samples == 0) {
            return 0.0;
        }
        double laplacianMean = laplacianSum / samples;
        double variance = Math.max(
                0.0,
                laplacianSquareSum / samples
                        - laplacianMean * laplacianMean
        );
        double meanLuminance = luminanceSum / samples;
        double exposure = 1.0
                - Math.min(1.0, Math.abs(meanLuminance - 138.0) / 138.0);
        return variance * (0.72 + 0.28 * exposure);
    }

    private static void ensureRotationIsVisible(List<Bitmap> frames)
            throws IOException {
        if (frames.size() != VIEW_COUNT) {
            throw new IOException("Les huit vues n'ont pas pu être extraites");
        }
        double maximumDifference = 0.0;
        Bitmap first = frames.get(0);
        for (int index = 1; index < frames.size(); index++) {
            maximumDifference = Math.max(
                    maximumDifference,
                    visualDifference(first, frames.get(index))
            );
        }
        if (maximumDifference < 3.0) {
            throw new IOException(
                    "La rotation n'est pas assez visible dans cette vidéo"
            );
        }
    }

    private static double visualDifference(Bitmap left, Bitmap right) {
        final int grid = 20;
        double sum = 0.0;
        for (int row = 0; row < grid; row++) {
            int leftY = Math.min(
                    left.getHeight() - 1,
                    Math.round((row + 0.5f) * left.getHeight() / grid)
            );
            int rightY = Math.min(
                    right.getHeight() - 1,
                    Math.round((row + 0.5f) * right.getHeight() / grid)
            );
            for (int column = 0; column < grid; column++) {
                int leftX = Math.min(
                        left.getWidth() - 1,
                        Math.round((column + 0.5f) * left.getWidth() / grid)
                );
                int rightX = Math.min(
                        right.getWidth() - 1,
                        Math.round((column + 0.5f) * right.getWidth() / grid)
                );
                sum += Math.abs(
                        luminance(left.getPixel(leftX, leftY))
                                - luminance(right.getPixel(rightX, rightY))
                );
            }
        }
        return sum / (grid * grid);
    }

    private static double luminance(int color) {
        return 0.2126 * ((color >>> 16) & 0xFF)
                + 0.7152 * ((color >>> 8) & 0xFF)
                + 0.0722 * (color & 0xFF);
    }

    private static int[] fitInside(int width, int height, int maximumSide) {
        float scale = Math.min(
                1.0f,
                maximumSide / (float) Math.max(width, height)
        );
        return new int[]{
                Math.max(1, Math.round(width * scale)),
                Math.max(1, Math.round(height * scale))
        };
    }

    private static long clampTime(long value, long durationUs) {
        long maximum = Math.max(1L, durationUs - 1_000L);
        return Math.max(0L, Math.min(maximum, value));
    }

    private static long readLongMetadata(
            MediaMetadataRetriever retriever,
            int key
    ) {
        String value = retriever.extractMetadata(key);
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static int readIntMetadata(
            MediaMetadataRetriever retriever,
            int key
    ) {
        long value = readLongMetadata(retriever, key);
        return value > Integer.MAX_VALUE || value < Integer.MIN_VALUE
                ? 0
                : (int) value;
    }

    private static void notifyProgress(
            ProgressListener listener,
            int current,
            int total
    ) {
        if (listener != null) {
            listener.onFrameSelected(current, total);
        }
    }

    private static void recycleAll(List<Bitmap> bitmaps) {
        for (Bitmap bitmap : bitmaps) {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
        bitmaps.clear();
    }

    public interface ProgressListener {
        void onFrameSelected(int current, int total);
    }

    public static final class Result implements AutoCloseable {
        private final List<Bitmap> frames;
        private final long[] selectedTimesUs;
        private final long durationMs;

        Result(
                List<Bitmap> frames,
                long[] selectedTimesUs,
                long durationMs
        ) {
            this.frames = Collections.unmodifiableList(
                    new ArrayList<>(frames)
            );
            this.selectedTimesUs = selectedTimesUs.clone();
            this.durationMs = durationMs;
        }

        public List<Bitmap> getFrames() {
            return frames;
        }

        public long[] getSelectedTimesUs() {
            return selectedTimesUs.clone();
        }

        public long getDurationMs() {
            return durationMs;
        }

        @Override
        public void close() {
            recycleAll(new ArrayList<>(frames));
        }
    }
}
