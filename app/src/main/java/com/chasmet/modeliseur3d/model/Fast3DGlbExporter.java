package com.chasmet.modeliseur3d.model;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Environment;
import android.os.SystemClock;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Export GLB rapide réservé au mode 3D quatre vues.
 *
 * La V5.9 exportait successivement un GLB HD, jusqu'à onze variantes mobiles,
 * un OBJ, un MTL, une texture PNG et un fichier texte. Sur mobile, cette chaîne
 * pouvait durer plusieurs dizaines de secondes. La V5.9.1 prépare en arrière-
 * plan un seul GLB autonome inférieur ou égal à 200 ko, puis le partage
 * immédiatement lorsque l'utilisateur appuie sur le bouton.
 *
 * Le moteur 2.5D conserve volontairement son exporteur historique.
 */
public final class Fast3DGlbExporter {
    public static final long MAXIMUM_GLB_BYTES = 200_000L;

    private static final Attempt[] ATTEMPTS = {
            new Attempt(1_300, 192),
            new Attempt(1_050, 160),
            new Attempt(820, 144),
            new Attempt(620, 128),
            new Attempt(450, 96)
    };

    private Fast3DGlbExporter() {
    }

    public static PreparedExport prepare(
            Context context,
            MeshData source,
            Bitmap texture,
            ProgressListener listener
    ) throws IOException {
        if (context == null || source == null || texture == null || texture.isRecycled()) {
            throw new IOException("Données 3D invalides pour l'export");
        }
        long started = SystemClock.elapsedRealtime();
        File documents = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (documents == null) {
            throw new IOException("Stockage externe indisponible");
        }

        String stamp = new SimpleDateFormat(
                "yyyyMMdd_HHmmss",
                Locale.FRANCE
        ).format(new Date());
        File directory = new File(
                documents,
                "Modeliseur3D/Personnage_3D_V5_9_1_" + stamp
        );
        if (!directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Impossible de créer le dossier GLB");
        }

        File temporary = new File(directory, "personnage_3d_v5_9_1.tmp");
        File output = new File(directory, "personnage_3d_v5_9_1_200ko.glb");
        MeshData current = source;
        IOException lastError = null;

        for (int index = 0; index < ATTEMPTS.length; index++) {
            Attempt attempt = ATTEMPTS[index];
            notifyProgress(listener, Stage.SIMPLIFYING, index + 1, ATTEMPTS.length);
            try {
                if (current.getTriangleCount() > attempt.triangleBudget) {
                    current = FastMobileMeshOptimizer.simplify(
                            current,
                            attempt.triangleBudget
                    );
                }
                notifyProgress(listener, Stage.ENCODING, index + 1, ATTEMPTS.length);
                MobileGlbExporter.write(
                        temporary,
                        current,
                        texture,
                        attempt.textureMaximumSide,
                        100
                );
                long size = temporary.length();
                if (size > 0L && size <= MAXIMUM_GLB_BYTES) {
                    if (output.exists() && !output.delete()) {
                        throw new IOException("Ancien export GLB impossible à remplacer");
                    }
                    if (!temporary.renameTo(output)) {
                        throw new IOException("Impossible de finaliser le fichier GLB");
                    }
                    return new PreparedExport(
                            output,
                            size,
                            current.getTriangleCount(),
                            current.getVertexCount(),
                            attempt.textureMaximumSide,
                            SystemClock.elapsedRealtime() - started
                    );
                }
            } catch (IOException | RuntimeException error) {
                lastError = error instanceof IOException
                        ? (IOException) error
                        : new IOException(error.getMessage(), error);
            }
        }

        deleteQuietly(temporary);
        throw new IOException(
                "Impossible de préparer un GLB inférieur ou égal à 200 ko",
                lastError
        );
    }

    private static void notifyProgress(
            ProgressListener listener,
            Stage stage,
            int current,
            int total
    ) {
        if (listener != null) {
            listener.onProgress(stage, current, total);
        }
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists() && !file.delete()) {
            file.deleteOnExit();
        }
    }

    public enum Stage {
        SIMPLIFYING,
        ENCODING
    }

    public interface ProgressListener {
        void onProgress(Stage stage, int current, int total);
    }

    private static final class Attempt {
        final int triangleBudget;
        final int textureMaximumSide;

        Attempt(int triangleBudget, int textureMaximumSide) {
            this.triangleBudget = triangleBudget;
            this.textureMaximumSide = textureMaximumSide;
        }
    }

    public static final class PreparedExport {
        private final File file;
        private final long sizeBytes;
        private final int triangleCount;
        private final int vertexCount;
        private final int textureMaximumSide;
        private final long durationMs;

        PreparedExport(
                File file,
                long sizeBytes,
                int triangleCount,
                int vertexCount,
                int textureMaximumSide,
                long durationMs
        ) {
            this.file = file;
            this.sizeBytes = sizeBytes;
            this.triangleCount = triangleCount;
            this.vertexCount = vertexCount;
            this.textureMaximumSide = textureMaximumSide;
            this.durationMs = durationMs;
        }

        public File getFile() {
            return file;
        }

        public long getSizeBytes() {
            return sizeBytes;
        }

        public int getTriangleCount() {
            return triangleCount;
        }

        public int getVertexCount() {
            return vertexCount;
        }

        public int getTextureMaximumSide() {
            return textureMaximumSide;
        }

        public long getDurationMs() {
            return durationMs;
        }
    }
}
