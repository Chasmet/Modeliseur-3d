package com.chasmet.modeliseur3d.model;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Environment;
import android.os.SystemClock;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Export GLB qualité sans dégradation.
 *
 * <p>Aucun triangle n'est supprimé et aucune texture n'est réduite. Une soudure
 * strictement sans perte des sommets identiques peut être effectuée lorsqu'il
 * reste assez de mémoire. L'écriture finale est ensuite déléguée à
 * ExternalViewerGlbExporter, qui écrit désormais le GLB en streaming.</p>
 */
public final class Fast3DGlbExporter {
    private static final long MINIMUM_WELD_HEADROOM_BYTES = 96L * 1024L * 1024L;
    private static final int GLB_MAGIC = 0x46546C67;
    private static final int GLB_VERSION = 2;
    private static final int JSON_CHUNK_TYPE = 0x4E4F534A;
    private static final int BIN_CHUNK_TYPE = 0x004E4942;

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
        MemoryDiagnostics.mark("EXPORT PREPARATION");
        notifyProgress(listener, Stage.SIMPLIFYING, 0, 1);

        int sourceVertexCount = source.getVertexCount();
        MeshData exportMesh = source;
        if (hasMemoryForExactWeld(source)) {
            try {
                MeshData welded = weldExactVertices(source);
                if (welded != null
                        && welded.getTriangleCount() == source.getTriangleCount()
                        && welded.getVertexCount() <= sourceVertexCount) {
                    exportMesh = welded;
                }
            } catch (RuntimeException | OutOfMemoryError ignored) {
                exportMesh = source;
                MemoryDiagnostics.mark("EXPORT SOUDURE IGNORÉE");
                Runtime.getRuntime().gc();
            }
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
                "Modeliseur3D/Modele_3D_Qualite_" + stamp
        );
        if (!directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Impossible de créer le dossier GLB");
        }

        File temporary = new File(directory, "modele_3d_qualite.tmp");
        File output = new File(directory, "modele_3d_qualite.glb");
        deleteQuietly(temporary);
        deleteQuietly(output);

        notifyProgress(listener, Stage.ENCODING, 0, 1);
        MemoryDiagnostics.mark("EXPORT GLB STREAMING");
        try {
            ExternalViewerGlbExporter.write(temporary, exportMesh, texture);
            validateWrittenGlb(temporary);
            long size = temporary.length();
            if (!temporary.renameTo(output)) {
                throw new IOException("Impossible de finaliser le fichier GLB externe");
            }
            notifyProgress(listener, Stage.ENCODING, 1, 1);
            MemoryDiagnostics.mark("EXPORT GLB TERMINÉ");
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
            MemoryDiagnostics.mark("EXPORT GLB ÉCHEC");
            if (error instanceof IOException) {
                throw (IOException) error;
            }
            throw new IOException(error.getMessage(), error);
        }
    }

    /**
     * Évite de lancer la soudure si ses tables temporaires risquent de pousser
     * inutilement le heap Java vers un OOM. Dans ce cas le maillage original,
     * déjà valide, est exporté directement sans perte de qualité.
     */
    private static boolean hasMemoryForExactWeld(MeshData source) {
        int vertexCount = Math.max(0, source.getVertexCount());
        int indexCount = source.getIndices() == null ? 0 : source.getIndices().length;

        long estimatedWorkingSet = 0L;
        estimatedWorkingSet += (long) vertexCount * 8L;
        estimatedWorkingSet += (long) vertexCount * 24L;
        estimatedWorkingSet += (long) indexCount * 4L;

        long tableEntries = 16L;
        long wanted = Math.max(16L, (long) vertexCount * 2L);
        while (tableEntries < wanted && tableEntries < (1L << 30)) {
            tableEntries <<= 1;
        }
        estimatedWorkingSet += tableEntries * 4L;
        estimatedWorkingSet += 16L * 1024L * 1024L;

        Runtime runtime = Runtime.getRuntime();
        long used = Math.max(0L, runtime.totalMemory() - runtime.freeMemory());
        long headroom = Math.max(0L, runtime.maxMemory() - used);
        long required = Math.max(
                MINIMUM_WELD_HEADROOM_BYTES,
                estimatedWorkingSet + estimatedWorkingSet / 3L
        );
        return headroom >= required;
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
                throw new IllegalArgumentException("Indice invalide avant soudure");
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

    /** Vérifie le conteneur GLB avant de renommer le fichier temporaire. */
    private static void validateWrittenGlb(File file) throws IOException {
        if (file == null || !file.isFile() || file.length() < 28L) {
            throw new IOException("GLB final incomplet");
        }
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            int magic = readIntLE(input);
            int version = readIntLE(input);
            long declaredLength = readUInt32LE(input);
            if (magic != GLB_MAGIC || version != GLB_VERSION) {
                throw new IOException("En-tête GLB 2.0 invalide");
            }
            if (declaredLength != file.length()) {
                throw new IOException("Longueur GLB incohérente");
            }

            long jsonLength = readUInt32LE(input);
            int jsonType = readIntLE(input);
            if (jsonLength <= 0L || jsonType != JSON_CHUNK_TYPE) {
                throw new IOException("Chunk JSON GLB invalide");
            }
            long binaryHeaderOffset = 20L + jsonLength;
            if (binaryHeaderOffset + 8L > file.length()) {
                throw new IOException("Chunk BIN GLB absent");
            }
            input.seek(binaryHeaderOffset);
            long binaryLength = readUInt32LE(input);
            int binaryType = readIntLE(input);
            if (binaryLength <= 0L || binaryType != BIN_CHUNK_TYPE) {
                throw new IOException("Chunk BIN GLB invalide");
            }
            long expectedEnd = binaryHeaderOffset + 8L + binaryLength;
            if (expectedEnd != file.length()) {
                throw new IOException("Taille du chunk BIN GLB incohérente");
            }
        }
    }

    private static int readIntLE(RandomAccessFile input) throws IOException {
        int b0 = input.read();
        int b1 = input.read();
        int b2 = input.read();
        int b3 = input.read();
        if ((b0 | b1 | b2 | b3) < 0) {
            throw new IOException("Fin de fichier GLB inattendue");
        }
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    private static long readUInt32LE(RandomAccessFile input) throws IOException {
        return readIntLE(input) & 0xFFFF_FFFFL;
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
        /** Conservé pour compatibilité : contrôle/soudure exacte du maillage. */
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
