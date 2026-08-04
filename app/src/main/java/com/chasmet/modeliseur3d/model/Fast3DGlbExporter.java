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
 * Export GLB qualité réservé au mode 3D quatre vues.
 *
 * Le fichier contient exactement le maillage, les normales, les UV et la
 * texture affichés dans l'application. Aucune limite de taille, aucune
 * simplification automatique et aucune compression destructive ne sont
 * appliquées. L'utilisateur pourra compresser le GLB séparément si nécessaire.
 *
 * Le moteur 2.5D conserve volontairement son exporteur historique séparé.
 */
public final class Fast3DGlbExporter {
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
        notifyProgress(listener, Stage.SIMPLIFYING, 1, 1);
        validateMesh(source);

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
                "Modeliseur3D/Personnage_3D_V5_9_3_" + stamp
        );
        if (!directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Impossible de créer le dossier GLB");
        }

        File temporary = new File(directory, "personnage_3d_v5_9_3.tmp");
        File output = new File(directory, "personnage_3d_v5_9_3_original.glb");
        deleteQuietly(temporary);
        deleteQuietly(output);

        notifyProgress(listener, Stage.ENCODING, 1, 1);
        try {
            GlbExporter.write(temporary, source, texture);
            long size = temporary.length();
            if (size <= 0L) {
                throw new IOException("Le fichier GLB généré est vide");
            }
            if (!temporary.renameTo(output)) {
                throw new IOException("Impossible de finaliser le fichier GLB original");
            }
            return new PreparedExport(
                    output,
                    size,
                    source.getTriangleCount(),
                    source.getVertexCount(),
                    Math.max(texture.getWidth(), texture.getHeight()),
                    SystemClock.elapsedRealtime() - started
            );
        } catch (IOException | RuntimeException error) {
            deleteQuietly(temporary);
            deleteQuietly(output);
            if (error instanceof IOException) {
                throw (IOException) error;
            }
            throw new IOException(error.getMessage(), error);
        }
    }

    private static void validateMesh(MeshData mesh) throws IOException {
        if (mesh.getVertexCount() < 3 || mesh.getTriangleCount() < 1) {
            throw new IOException("Le maillage 3D est vide");
        }
        int vertexCount = mesh.getVertexCount();
        int[] indices = mesh.getIndices();
        for (int index : indices) {
            if (index < 0 || index >= vertexCount) {
                throw new IOException("Indice de triangle invalide dans le maillage 3D");
            }
        }
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
        /** Conservé pour compatibilité : correspond désormais au contrôle du maillage. */
        SIMPLIFYING,
        ENCODING
    }

    public interface ProgressListener {
        void onProgress(Stage stage, int current, int total);
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
