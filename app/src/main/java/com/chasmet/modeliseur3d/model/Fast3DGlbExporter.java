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
 * Export GLB qualité V8.
 *
 * Aucun triangle n'est supprimé et aucune texture n'est dégradée. V8 retire
 * uniquement les doublons de sommets dont POSITION + NORMAL + UV sont
 * strictement identiques, puis remappe les indices. Les coutures UV restent
 * donc intactes et le rendu ne change pas.
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
        notifyProgress(listener, Stage.SIMPLIFYING, 0, 1);

        int sourceVertexCount = source.getVertexCount();
        MeshData exportMesh = source;
        try {
            MeshData welded = weldExactVertices(source);
            if (welded != null
                    && welded.getTriangleCount() == source.getTriangleCount()
                    && welded.getVertexCount() <= sourceVertexCount) {
                exportMesh = welded;
            }
        } catch (RuntimeException | OutOfMemoryError ignored) {
            exportMesh = source;
            Runtime.getRuntime().gc();
        }
        validateMesh(exportMesh);
        notifyProgress(listener, Stage.SIMPLIFYING, 1, 1);

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
                "Modeliseur3D/Modele_3D_V8_Modulaire_" + stamp
        );
        if (!directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Impossible de créer le dossier GLB");
        }

        File temporary = new File(directory, "modele_3d_v8_modulaire.tmp");
        File output = new File(directory, "modele_3d_v8_modulaire_da3.glb");
        deleteQuietly(temporary);
        deleteQuietly(output);

        notifyProgress(listener, Stage.ENCODING, 1, 1);
        try {
            ExternalViewerGlbExporter.write(temporary, exportMesh, texture);
            long size = temporary.length();
            if (size <= 0L) {
                throw new IOException("Le fichier GLB généré est vide");
            }
            if (!temporary.renameTo(output)) {
                throw new IOException("Impossible de finaliser le fichier GLB externe");
            }
            return new PreparedExport(
                    output,
                    size,
                    exportMesh.getTriangleCount(),
                    exportMesh.getVertexCount(),
                    sourceVertexCount,
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

    /**
     * Soudure strictement sans perte. Deux sommets ne sont fusionnés que si
     * leurs huit valeurs flottantes sont identiques bit à bit.
     */
    private static MeshData weldExactVertices(MeshData source) {
        int vertexCount = source.getVertexCount();
        if (vertexCount < 4) {
            return source;
        }
        float[] positions = source.getPositions();
        float[] normals = source.getNormals();
        float[] texCoords = source.getTexCoords();
        int[] sourceIndices = source.getIndices();

        int capacity = 16;
        long wanted = Math.max(16L, (long) vertexCount * 2L);
        while (capacity < wanted && capacity < (1 << 30)) {
            capacity <<= 1;
        }
        if (capacity <= 0 || capacity < vertexCount) {
            return source;
        }

        int[] table = new int[capacity];
        int[] remap = new int[vertexCount];
        int[] uniqueOriginal = new int[vertexCount];
        int tableMask = capacity - 1;
        int uniqueCount = 0;

        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int slot = vertexHash(vertex, positions, normals, texCoords) & tableMask;
            while (true) {
                int stored = table[slot] - 1;
                if (stored < 0) {
                    table[slot] = vertex + 1;
                    remap[vertex] = uniqueCount;
                    uniqueOriginal[uniqueCount++] = vertex;
                    break;
                }
                if (sameVertex(vertex, stored, positions, normals, texCoords)) {
                    remap[vertex] = remap[stored];
                    break;
                }
                slot = (slot + 1) & tableMask;
            }
        }

        if (uniqueCount == vertexCount) {
            return source;
        }

        float[] compactPositions = new float[uniqueCount * 3];
        float[] compactNormals = new float[uniqueCount * 3];
        float[] compactTexCoords = new float[uniqueCount * 2];
        for (int unique = 0; unique < uniqueCount; unique++) {
            int original = uniqueOriginal[unique];
            int source3 = original * 3;
            int target3 = unique * 3;
            compactPositions[target3] = positions[source3];
            compactPositions[target3 + 1] = positions[source3 + 1];
            compactPositions[target3 + 2] = positions[source3 + 2];
            compactNormals[target3] = normals[source3];
            compactNormals[target3 + 1] = normals[source3 + 1];
            compactNormals[target3 + 2] = normals[source3 + 2];

            int source2 = original * 2;
            int target2 = unique * 2;
            compactTexCoords[target2] = texCoords[source2];
            compactTexCoords[target2 + 1] = texCoords[source2 + 1];
        }

        int[] compactIndices = new int[sourceIndices.length];
        for (int index = 0; index < sourceIndices.length; index++) {
            int sourceVertex = sourceIndices[index];
            if (sourceVertex < 0 || sourceVertex >= vertexCount) {
                throw new IllegalArgumentException("Indice invalide avant soudure V8");
            }
            compactIndices[index] = remap[sourceVertex];
        }
        return new MeshData(
                compactPositions,
                compactNormals,
                compactTexCoords,
                compactIndices
        );
    }

    private static int vertexHash(
            int vertex,
            float[] positions,
            float[] normals,
            float[] texCoords
    ) {
        int p = vertex * 3;
        int uv = vertex * 2;
        int hash = 17;
        hash = hash * 31 + Float.floatToIntBits(positions[p]);
        hash = hash * 31 + Float.floatToIntBits(positions[p + 1]);
        hash = hash * 31 + Float.floatToIntBits(positions[p + 2]);
        hash = hash * 31 + Float.floatToIntBits(normals[p]);
        hash = hash * 31 + Float.floatToIntBits(normals[p + 1]);
        hash = hash * 31 + Float.floatToIntBits(normals[p + 2]);
        hash = hash * 31 + Float.floatToIntBits(texCoords[uv]);
        hash = hash * 31 + Float.floatToIntBits(texCoords[uv + 1]);
        return hash ^ (hash >>> 16);
    }

    private static boolean sameVertex(
            int first,
            int second,
            float[] positions,
            float[] normals,
            float[] texCoords
    ) {
        int first3 = first * 3;
        int second3 = second * 3;
        for (int axis = 0; axis < 3; axis++) {
            if (Float.floatToIntBits(positions[first3 + axis])
                    != Float.floatToIntBits(positions[second3 + axis])) {
                return false;
            }
            if (Float.floatToIntBits(normals[first3 + axis])
                    != Float.floatToIntBits(normals[second3 + axis])) {
                return false;
            }
        }
        int first2 = first * 2;
        int second2 = second * 2;
        return Float.floatToIntBits(texCoords[first2])
                == Float.floatToIntBits(texCoords[second2])
                && Float.floatToIntBits(texCoords[first2 + 1])
                == Float.floatToIntBits(texCoords[second2 + 1]);
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
        /** Conservé pour compatibilité : V8 y effectue la soudure exacte. */
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
        private final int sourceVertexCount;
        private final int textureMaximumSide;
        private final long durationMs;

        PreparedExport(
                File file,
                long sizeBytes,
                int triangleCount,
                int vertexCount,
                int sourceVertexCount,
                int textureMaximumSide,
                long durationMs
        ) {
            this.file = file;
            this.sizeBytes = sizeBytes;
            this.triangleCount = triangleCount;
            this.vertexCount = vertexCount;
            this.sourceVertexCount = sourceVertexCount;
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

        public int getSourceVertexCount() {
            return sourceVertexCount;
        }

        public int getWeldedVertexCount() {
            return Math.max(0, sourceVertexCount - vertexCount);
        }

        public int getTextureMaximumSide() {
            return textureMaximumSide;
        }

        public long getDurationMs() {
            return durationMs;
        }
    }
}
