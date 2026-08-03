package com.chasmet.modeliseur3d.media;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Extrait quatre vues nettes d'une rotation 360 degres deja decoupee.
 *
 * Quatre images sont prises autour du debut, de 25, 50 et 75 % de la video.
 * L'utilisateur indique laquelle montre la face et le sens de rotation : la
 * video peut donc commencer sous n'importe quel angle. Dans chaque zone,
 * plusieurs instants sont compares et la trame la plus nette est conservee.
 * Le MP4 n'est jamais envoye au cloud.
 */
public final class VideoFrameExtractor {
    public static final long MAXIMUM_DURATION_MS = 120_000L;

    private static final long MINIMUM_DURATION_MS = 1_200L;
    private static final int SCORE_MAX_SIDE = 480;
    private static final int OUTPUT_MAX_SIDE = 1536;
    private static final double[] TARGET_FRACTIONS = {
            0.025, 0.25, 0.50, 0.75
    };
    private static final double[] WINDOW_FRACTIONS = {
            0.020, 0.055, 0.055, 0.055
    };
    private static final double[] CANDIDATE_OFFSETS = {
            -1.0, -0.50, 0.0, 0.50, 1.0
    };

    private final Context context;

    public VideoFrameExtractor(Context context) {
        this.context = context.getApplicationContext();
    }

    public Result extract(
            Uri videoUri,
            int frontSlot,
            boolean nextQuarterIsLeft,
            ProgressListener listener
    ) throws IOException {
        if (videoUri == null) {
            throw new IllegalArgumentException("Video absente");
        }
        VideoViewPlan.roles(frontSlot, nextQuarterIsLeft);

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        List<Bitmap> output = new ArrayList<>(4);
        try {
            retriever.setDataSource(context, videoUri);
            long durationMs = readLongMetadata(
                    retriever,
                    MediaMetadataRetriever.METADATA_KEY_DURATION
            );
            if (durationMs < MINIMUM_DURATION_MS) {
                throw new IOException(
                        "La video est trop courte pour contenir quatre angles"
                );
            }
            if (durationMs > MAXIMUM_DURATION_MS) {
                throw new IOException(
                        "La video depasse 2 minutes ; decoupe seulement la rotation utile"
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
            if (rotation == 90 || rotation == 270) {
                int swap = sourceWidth;
                sourceWidth = sourceHeight;
                sourceHeight = swap;
            }
            if (sourceWidth <= 0 || sourceHeight <= 0) {
                sourceWidth = 1920;
                sourceHeight = 1080;
            }

            long durationUs = durationMs * 1000L;
            long[] selectedTimesUs = new long[4];
            for (int viewIndex = 0; viewIndex < 4; viewIndex++) {
                notifyProgress(listener, viewIndex + 1, 4);
                selectedTimesUs[viewIndex] = selectSharpestTime(
                        retriever,
                        durationUs,
                        sourceWidth,
                        sourceHeight,
                        viewIndex
                );
                Bitmap frame = readScaledFrame(
                        retriever,
                        selectedTimesUs[viewIndex],
                        sourceWidth,
                        sourceHeight,
                        OUTPUT_MAX_SIDE
                );
                if (frame == null) {
                    throw new IOException(
                            "Impossible de lire la vue " + (viewIndex + 1)
                    );
                }
                output.add(ensureArgb(frame));
            }

            ensureRotationIsVisible(output);
            List<String> roles = VideoViewPlan.roles(
                    frontSlot,
                    nextQuarterIsLeft
            );
            return new Result(output, roles, selectedTimesUs, durationMs);
        } catch (RuntimeException error) {
            recycleAll(output);
            throw new IOException("Lecture de la video impossible", error);
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
                                    + WINDOW_FRACTIONS[viewIndex] * offset
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
        int[] dimensions = fitInside(
                sourceWidth,
                sourceHeight,
                maximumSide
        );
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
        int step = Math.max(1, Math.max(
                bitmap.getWidth(),
                bitmap.getHeight()
        ) / 180);
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
        if (frames.size() != 4) {
            throw new IOException("Quatre vues n'ont pas pu etre extraites");
        }
        double maximumDifference = 0.0;
        Bitmap first = frames.get(0);
        for (int index = 1; index < frames.size(); index++) {
            maximumDifference = Math.max(
                    maximumDifference,
                    visualDifference(first, frames.get(index))
            );
        }
        if (maximumDifference < 3.2) {
            throw new IOException(
                    "La rotation n'est pas assez visible dans cette video"
            );
        }
    }

    private static double visualDifference(Bitmap left, Bitmap right) {
        final int grid = 24;
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
        private final List<String> roles;
        private final long[] selectedTimesUs;
        private final long durationMs;

        Result(
                List<Bitmap> frames,
                List<String> roles,
                long[] selectedTimesUs,
                long durationMs
        ) {
            this.frames = Collections.unmodifiableList(
                    new ArrayList<>(frames)
            );
            this.roles = Collections.unmodifiableList(
                    new ArrayList<>(roles)
            );
            this.selectedTimesUs = selectedTimesUs.clone();
            this.durationMs = durationMs;
        }

        public List<Bitmap> getFrames() {
            return frames;
        }

        public List<String> getRoles() {
            return roles;
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
