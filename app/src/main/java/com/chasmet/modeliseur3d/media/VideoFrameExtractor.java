package com.chasmet.modeliseur3d.media;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Extrait huit vues d'une courte vidéo de rotation, entièrement hors ligne.
 *
 * Le fichier choisi est d'abord copié dans le cache privé. Cette étape évite
 * les échecs de décodage observés avec certains fournisseurs de documents
 * Android. Chaque angle est ensuite lu avec plusieurs timestamps et plusieurs
 * modes de MediaMetadataRetriever. Une vue manquante est remplacée par la vue
 * valide la plus proche au lieu d'annuler toute la reconstruction.
 */
public final class VideoFrameExtractor {
    public static final int VIEW_COUNT = 8;
    public static final long MAXIMUM_DURATION_MS = 120_000L;

    private static final long MINIMUM_DURATION_MS = 1_200L;
    private static final long MAXIMUM_CACHED_BYTES = 350_000_000L;
    private static final int SCORE_MAX_SIDE = 320;
    private static final int OUTPUT_MAX_SIDE = 720;

    private static final double[] TARGET_FRACTIONS = {
            0.035, 0.155, 0.275, 0.395,
            0.515, 0.635, 0.755, 0.875
    };
    private static final double[] SCORE_OFFSETS = {
            -2.0, -1.0, 0.0, 1.0, 2.0
    };
    private static final double SCORE_WINDOW_FRACTION = 0.012;

    private static final long[] RETRY_OFFSETS_US = {
            0L,
            -40_000L, 40_000L,
            -90_000L, 90_000L,
            -180_000L, 180_000L,
            -360_000L, 360_000L,
            -700_000L, 700_000L,
            -1_200_000L, 1_200_000L
    };

    private static final int[] FRAME_OPTIONS = {
            MediaMetadataRetriever.OPTION_CLOSEST,
            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            MediaMetadataRetriever.OPTION_PREVIOUS_SYNC,
            MediaMetadataRetriever.OPTION_NEXT_SYNC
    };

    private final Context context;

    public VideoFrameExtractor(Context context) {
        this.context = context.getApplicationContext();
    }

    public Result extract(Uri videoUri, ProgressListener listener)
            throws IOException {
        if (videoUri == null) {
            throw new IllegalArgumentException("Vidéo absente");
        }

        File cachedVideo = copyToPrivateCache(videoUri);
        List<Bitmap> frames = new ArrayList<>(VIEW_COUNT);
        boolean[] decoded = new boolean[VIEW_COUNT];
        long[] selectedTimesUs = new long[VIEW_COUNT];

        try {
            Metadata metadata = readMetadata(cachedVideo);
            validateDuration(metadata.durationMs);
            long durationUs = metadata.durationMs * 1000L;

            Bitmap previous = null;
            for (int viewIndex = 0; viewIndex < VIEW_COUNT; viewIndex++) {
                notifyProgress(listener, viewIndex + 1, VIEW_COUNT);
                FrameChoice choice = selectBestFrame(
                        cachedVideo,
                        metadata,
                        durationUs,
                        viewIndex,
                        previous
                );
                if (choice == null) {
                    frames.add(null);
                    selectedTimesUs[viewIndex] = targetTimeUs(
                            durationUs,
                            viewIndex
                    );
                    continue;
                }

                Bitmap frame = rotateIfNeeded(
                        choice.bitmap,
                        metadata.rotation
                );
                frame = ensureArgb(frame);
                frames.add(frame);
                decoded[viewIndex] = true;
                selectedTimesUs[viewIndex] = choice.timeUs;
                previous = frame;
            }

            int decodedCount = countTrue(decoded);
            if (decodedCount < 4) {
                throw new IOException(
                        "Le téléphone n'a pu décoder que " + decodedCount
                                + " vues sur 8. Réencode la vidéo en MP4 H.264 ou raccourcis-la."
                );
            }

            fillMissingFrames(frames, selectedTimesUs);
            ensureRotationIsVisible(frames);
            return new Result(
                    frames,
                    selectedTimesUs,
                    metadata.durationMs,
                    decodedCount
            );
        } catch (IOException error) {
            recycleAll(frames);
            throw error;
        } catch (RuntimeException error) {
            recycleAll(frames);
            throw new IOException("Lecture de la vidéo impossible", error);
        } finally {
            deleteQuietly(cachedVideo);
        }
    }

    private File copyToPrivateCache(Uri videoUri) throws IOException {
        File directory = new File(context.getCacheDir(), "video_import");
        if (!directory.exists()
                && !directory.mkdirs()
                && !directory.isDirectory()) {
            throw new IOException("Impossible de préparer le cache vidéo privé");
        }

        File output = File.createTempFile("rotation_", ".video", directory);
        long total = 0L;
        try (InputStream raw = context.getContentResolver()
                .openInputStream(videoUri)) {
            if (raw == null) {
                throw new IOException(
                        "Le fichier vidéo sélectionné est inaccessible"
                );
            }
            try (BufferedInputStream input = new BufferedInputStream(
                    raw,
                    1024 * 1024
            ); BufferedOutputStream destination = new BufferedOutputStream(
                    new FileOutputStream(output),
                    1024 * 1024
            )) {
                byte[] buffer = new byte[1024 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > MAXIMUM_CACHED_BYTES) {
                        throw new IOException(
                                "La vidéo dépasse 350 Mo : garde uniquement la rotation utile"
                        );
                    }
                    destination.write(buffer, 0, read);
                }
            }
        } catch (IOException error) {
            deleteQuietly(output);
            throw error;
        }

        if (total < 16_384L) {
            deleteQuietly(output);
            throw new IOException("Le fichier vidéo est vide ou incomplet");
        }
        return output;
    }

    private static Metadata readMetadata(File file) throws IOException {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            long durationMs = readLongMetadata(
                    retriever,
                    MediaMetadataRetriever.METADATA_KEY_DURATION
            );
            int width = readIntMetadata(
                    retriever,
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            );
            int height = readIntMetadata(
                    retriever,
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            );
            int rotation = readIntMetadata(
                    retriever,
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
            );
            if (durationMs <= 0L) {
                throw new IOException("Durée vidéo illisible");
            }
            if (width <= 0 || height <= 0) {
                width = 1280;
                height = 720;
            }
            return new Metadata(durationMs, width, height, rotation);
        } catch (RuntimeException error) {
            throw new IOException("Métadonnées vidéo illisibles", error);
        } finally {
            releaseRetriever(retriever);
        }
    }

    private static void validateDuration(long durationMs) throws IOException {
        if (durationMs < MINIMUM_DURATION_MS) {
            throw new IOException(
                    "La vidéo est trop courte pour extraire plusieurs angles"
            );
        }
        if (durationMs > MAXIMUM_DURATION_MS) {
            throw new IOException(
                    "La vidéo dépasse 2 minutes : garde seulement la rotation utile"
            );
        }
    }

    private static FrameChoice selectBestFrame(
            File file,
            Metadata metadata,
            long durationUs,
            int viewIndex,
            Bitmap previous
    ) {
        long targetUs = targetTimeUs(durationUs, viewIndex);
        long bestTimeUs = -1L;
        Bitmap bestPreview = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (double offset : SCORE_OFFSETS) {
            long candidateUs = clampTime(
                    Math.round(durationUs * (
                            TARGET_FRACTIONS[viewIndex]
                                    + SCORE_WINDOW_FRACTION * offset
                    )),
                    durationUs
            );
            Bitmap preview = readFrameWithFallback(
                    file,
                    candidateUs,
                    metadata.width,
                    metadata.height,
                    SCORE_MAX_SIDE,
                    durationUs
            );
            if (!isUsable(preview)) {
                recycle(preview);
                continue;
            }

            double score = sharpnessAndExposureScore(preview);
            if (previous != null) {
                score *= 0.92 + Math.min(
                        0.22,
                        visualDifference(previous, preview) / 90.0
                );
            }
            if (score > bestScore) {
                recycle(bestPreview);
                bestPreview = preview;
                bestTimeUs = candidateUs;
                bestScore = score;
            } else {
                recycle(preview);
            }
        }

        if (bestTimeUs < 0L) {
            Bitmap recovered = readFrameWithFallback(
                    file,
                    targetUs,
                    metadata.width,
                    metadata.height,
                    OUTPUT_MAX_SIDE,
                    durationUs
            );
            return isUsable(recovered)
                    ? new FrameChoice(recovered, targetUs)
                    : null;
        }

        recycle(bestPreview);
        Bitmap full = readFrameWithFallback(
                file,
                bestTimeUs,
                metadata.width,
                metadata.height,
                OUTPUT_MAX_SIDE,
                durationUs
        );
        return isUsable(full)
                ? new FrameChoice(full, bestTimeUs)
                : null;
    }

    private static Bitmap readFrameWithFallback(
            File file,
            long targetUs,
            int sourceWidth,
            int sourceHeight,
            int maximumSide,
            long durationUs
    ) {
        for (long retryOffset : RETRY_OFFSETS_US) {
            long timeUs = clampTime(targetUs + retryOffset, durationUs);
            for (int option : FRAME_OPTIONS) {
                Bitmap frame = readSingleFrame(
                        file,
                        timeUs,
                        sourceWidth,
                        sourceHeight,
                        maximumSide,
                        option,
                        true
                );
                if (isUsable(frame)) {
                    return frame;
                }
                recycle(frame);

                frame = readSingleFrame(
                        file,
                        timeUs,
                        sourceWidth,
                        sourceHeight,
                        maximumSide,
                        option,
                        false
                );
                if (isUsable(frame)) {
                    return frame;
                }
                recycle(frame);
            }
        }
        return null;
    }

    private static Bitmap readSingleFrame(
            File file,
            long timeUs,
            int sourceWidth,
            int sourceHeight,
            int maximumSide,
            int option,
            boolean scaledFirst
    ) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            Bitmap frame = null;
            if (scaledFirst
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                int[] dimensions = fitInside(
                        sourceWidth,
                        sourceHeight,
                        maximumSide
                );
                try {
                    frame = retriever.getScaledFrameAtTime(
                            timeUs,
                            option,
                            dimensions[0],
                            dimensions[1]
                    );
                } catch (RuntimeException ignored) {
                    frame = null;
                }
            }
            if (frame == null) {
                try {
                    frame = retriever.getFrameAtTime(timeUs, option);
                } catch (RuntimeException ignored) {
                    frame = null;
                }
            }
            return scaleDown(frame, maximumSide);
        } catch (RuntimeException ignored) {
            return null;
        } finally {
            releaseRetriever(retriever);
        }
    }

    private static void releaseRetriever(MediaMetadataRetriever retriever) {
        try {
            retriever.release();
        } catch (IOException | RuntimeException ignored) {
            // La fermeture ne doit jamais annuler une vue déjà décodée.
        }
    }

    private static Bitmap scaleDown(Bitmap frame, int maximumSide) {
        if (frame == null) {
            return null;
        }
        if (frame.getWidth() <= maximumSide
                && frame.getHeight() <= maximumSide) {
            return frame;
        }
        int[] dimensions = fitInside(
                frame.getWidth(),
                frame.getHeight(),
                maximumSide
        );
        Bitmap scaled = Bitmap.createScaledBitmap(
                frame,
                dimensions[0],
                dimensions[1],
                true
        );
        if (scaled != frame) {
            frame.recycle();
        }
        return scaled;
    }

    private static void fillMissingFrames(
            List<Bitmap> frames,
            long[] selectedTimesUs
    ) throws IOException {
        for (int index = 0; index < VIEW_COUNT; index++) {
            if (frames.get(index) != null) {
                continue;
            }
            int nearest = findNearestValid(frames, index);
            if (nearest < 0) {
                throw new IOException("Aucune vue vidéo exploitable");
            }
            Bitmap copy = frames.get(nearest).copy(
                    Bitmap.Config.ARGB_8888,
                    false
            );
            if (copy == null) {
                throw new IOException(
                        "Duplication d'une vue vidéo impossible"
                );
            }
            frames.set(index, copy);
            selectedTimesUs[index] = selectedTimesUs[nearest];
        }
    }

    private static int findNearestValid(List<Bitmap> frames, int target) {
        for (int distance = 1; distance < VIEW_COUNT; distance++) {
            int before = target - distance;
            if (before >= 0 && frames.get(before) != null) {
                return before;
            }
            int after = target + distance;
            if (after < VIEW_COUNT && frames.get(after) != null) {
                return after;
            }
        }
        return -1;
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
            throw new IllegalStateException(
                    "Conversion de la trame impossible"
            );
        }
        return converted;
    }

    private static void ensureRotationIsVisible(List<Bitmap> frames)
            throws IOException {
        if (frames.size() != VIEW_COUNT) {
            throw new IOException("Les huit vues n'ont pas pu être préparées");
        }
        double maximumDifference = 0.0;
        Bitmap first = frames.get(0);
        for (int index = 1; index < VIEW_COUNT; index++) {
            maximumDifference = Math.max(
                    maximumDifference,
                    visualDifference(first, frames.get(index))
            );
        }
        if (maximumDifference < 2.2) {
            throw new IOException(
                    "La rotation n'est pas assez visible dans cette vidéo"
            );
        }
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
        double exposure = 1.0 - Math.min(
                1.0,
                Math.abs(meanLuminance - 138.0) / 138.0
        );
        return variance * (0.72 + 0.28 * exposure);
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

    private static boolean isUsable(Bitmap bitmap) {
        return bitmap != null
                && !bitmap.isRecycled()
                && bitmap.getWidth() >= 32
                && bitmap.getHeight() >= 32;
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

    private static long targetTimeUs(long durationUs, int viewIndex) {
        return clampTime(
                Math.round(durationUs * TARGET_FRACTIONS[viewIndex]),
                durationUs
        );
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

    private static int countTrue(boolean[] values) {
        int count = 0;
        for (boolean value : values) {
            if (value) {
                count++;
            }
        }
        return count;
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

    private static void deleteQuietly(File file) {
        if (file != null && file.exists() && !file.delete()) {
            file.deleteOnExit();
        }
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }

    private static void recycleAll(List<Bitmap> bitmaps) {
        for (Bitmap bitmap : bitmaps) {
            recycle(bitmap);
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
        private final int decodedFrameCount;

        Result(
                List<Bitmap> frames,
                long[] selectedTimesUs,
                long durationMs,
                int decodedFrameCount
        ) {
            this.frames = Collections.unmodifiableList(
                    new ArrayList<>(frames)
            );
            this.selectedTimesUs = selectedTimesUs.clone();
            this.durationMs = durationMs;
            this.decodedFrameCount = decodedFrameCount;
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

        public int getDecodedFrameCount() {
            return decodedFrameCount;
        }

        @Override
        public void close() {
            recycleAll(new ArrayList<>(frames));
        }
    }

    private static final class FrameChoice {
        final Bitmap bitmap;
        final long timeUs;

        FrameChoice(Bitmap bitmap, long timeUs) {
            this.bitmap = bitmap;
            this.timeUs = timeUs;
        }
    }

    private static final class Metadata {
        final long durationMs;
        final int width;
        final int height;
        final int rotation;

        Metadata(long durationMs, int width, int height, int rotation) {
            this.durationMs = durationMs;
            this.width = width;
            this.height = height;
            this.rotation = rotation;
        }
    }
}
