package com.chasmet.modeliseur3d.model;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Export GLB propre pour les visionneuses externes, avec écriture en streaming.
 *
 * <p>La géométrie n'est plus recopiée dans un énorme ByteBuffer contenant tout
 * le GLB et le PNG n'est plus conservé dans un byte[] en mémoire. Le PNG est
 * préparé dans un fichier temporaire, puis positions, normales, UV, indices et
 * texture sont écrits séquentiellement dans le GLB final.</p>
 */
public final class ExternalViewerGlbExporter {
    private static final int GLB_MAGIC = 0x46546C67;
    private static final int GLB_VERSION = 2;
    private static final int JSON_CHUNK_TYPE = 0x4E4F534A;
    private static final int BIN_CHUNK_TYPE = 0x004E4942;
    private static final int TRANSPARENT_ALPHA = 44;
    private static final int OPAQUE_ALPHA = 92;
    private static final int STREAM_BUFFER_BYTES = 1024 * 1024;
    private static final int NUMBER_BUFFER_BYTES = 64 * 1024;
    private static final long UINT32_MAX = 0xFFFF_FFFFL;

    private ExternalViewerGlbExporter() {
    }

    public static void write(File outputFile, MeshData mesh, Bitmap texture) throws IOException {
        if (outputFile == null || mesh == null || texture == null || texture.isRecycled()) {
            throw new IOException("Données GLB invalides");
        }

        File parent = outputFile.getAbsoluteFile().getParentFile();
        if (parent == null || (!parent.exists() && !parent.mkdirs()) || !parent.isDirectory()) {
            throw new IOException("Dossier d'export GLB indisponible");
        }

        File pngFile = File.createTempFile("modeliseur_texture_", ".png", parent);
        try {
            long imageLength = encodeViewerSafePngToFile(texture, pngFile);

            float[] positions = mesh.getPositions();
            float[] normals = mesh.getNormals();
            float[] texCoords = mesh.getTexCoords();
            int[] indices = mesh.getIndices();

            long positionOffset = 0L;
            long positionLength = bytesForFloatArray(positions);
            long normalOffset = align4(positionOffset + positionLength);
            long normalLength = bytesForFloatArray(normals);
            long texCoordOffset = align4(normalOffset + normalLength);
            long texCoordLength = bytesForFloatArray(texCoords);
            long indexOffset = align4(texCoordOffset + texCoordLength);
            long indexLength = bytesForIntArray(indices);
            long imageOffset = align4(indexOffset + indexLength);
            long binaryLength = align4(imageOffset + imageLength);

            ensureUint32("buffer binaire", binaryLength);

            float[] minimum = positionBounds(positions, true);
            float[] maximum = positionBounds(positions, false);
            String json = buildJson(
                    binaryLength,
                    positionOffset,
                    positionLength,
                    normalOffset,
                    normalLength,
                    texCoordOffset,
                    texCoordLength,
                    indexOffset,
                    indexLength,
                    imageOffset,
                    imageLength,
                    mesh.getVertexCount(),
                    indices.length,
                    minimum,
                    maximum
            );
            byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
            long paddedJsonLength = align4(jsonBytes.length);
            long paddedBinaryLength = align4(binaryLength);
            long totalLength = 12L + 8L + paddedJsonLength + 8L + paddedBinaryLength;
            ensureUint32("GLB final", totalLength);

            try (OutputStream output = new BufferedOutputStream(
                    new FileOutputStream(outputFile),
                    STREAM_BUFFER_BYTES
            )) {
                writeIntLE(output, GLB_MAGIC);
                writeIntLE(output, GLB_VERSION);
                writeUInt32LE(output, totalLength);

                writeUInt32LE(output, paddedJsonLength);
                writeIntLE(output, JSON_CHUNK_TYPE);
                output.write(jsonBytes);
                writePadding(output, paddedJsonLength - jsonBytes.length, 0x20);

                writeUInt32LE(output, paddedBinaryLength);
                writeIntLE(output, BIN_CHUNK_TYPE);

                long cursor = 0L;
                cursor = padTo(output, cursor, positionOffset);
                writeFloatsLE(output, positions);
                cursor += positionLength;

                cursor = padTo(output, cursor, normalOffset);
                writeFloatsLE(output, normals);
                cursor += normalLength;

                cursor = padTo(output, cursor, texCoordOffset);
                writeFloatsLE(output, texCoords);
                cursor += texCoordLength;

                cursor = padTo(output, cursor, indexOffset);
                writeIntsLE(output, indices);
                cursor += indexLength;

                cursor = padTo(output, cursor, imageOffset);
                copyFile(pngFile, output);
                cursor += imageLength;

                padTo(output, cursor, paddedBinaryLength);
            }
        } finally {
            if (pngFile.exists() && !pngFile.delete()) {
                pngFile.deleteOnExit();
            }
        }
    }

    private static String buildJson(
            long binaryLength,
            long positionOffset,
            long positionLength,
            long normalOffset,
            long normalLength,
            long texCoordOffset,
            long texCoordLength,
            long indexOffset,
            long indexLength,
            long imageOffset,
            long imageLength,
            int vertexCount,
            int indexCount,
            float[] minimum,
            float[] maximum
    ) {
        StringBuilder json = new StringBuilder(2200);
        json.append('{');
        json.append("\"asset\":{\"version\":\"2.0\",\"generator\":\"Modeliseur 3D Android Streaming GLB\"},");
        json.append("\"extensionsUsed\":[\"KHR_materials_unlit\"],");
        json.append("\"scene\":0,");
        json.append("\"scenes\":[{\"nodes\":[0]}],");
        json.append("\"nodes\":[{\"mesh\":0,\"name\":\"Reconstruction 3D\"}],");
        json.append("\"meshes\":[{\"name\":\"Reconstruction 3D\",\"primitives\":[{");
        json.append("\"attributes\":{\"POSITION\":0,\"NORMAL\":1,\"TEXCOORD_0\":2},");
        json.append("\"indices\":3,\"material\":0,\"mode\":4}]}],");
        json.append("\"materials\":[{\"name\":\"Texture multivue\",");
        json.append("\"pbrMetallicRoughness\":{");
        json.append("\"baseColorFactor\":[1.0,1.0,1.0,1.0],");
        json.append("\"baseColorTexture\":{\"index\":0},");
        json.append("\"metallicFactor\":0.0,\"roughnessFactor\":1.0},");
        json.append("\"extensions\":{\"KHR_materials_unlit\":{}},");
        json.append("\"doubleSided\":false,");
        json.append("\"alphaMode\":\"MASK\",\"alphaCutoff\":0.24}],");
        json.append("\"textures\":[{\"sampler\":0,\"source\":0}],");
        json.append("\"samplers\":[{\"magFilter\":9729,\"minFilter\":9729,\"wrapS\":33071,\"wrapT\":33071}],");
        json.append("\"images\":[{\"bufferView\":4,\"mimeType\":\"image/png\",\"name\":\"texture_multivue\"}],");
        json.append("\"buffers\":[{\"byteLength\":").append(binaryLength).append("}],");
        json.append("\"bufferViews\":[");
        appendBufferView(json, positionOffset, positionLength, 34962);
        json.append(',');
        appendBufferView(json, normalOffset, normalLength, 34962);
        json.append(',');
        appendBufferView(json, texCoordOffset, texCoordLength, 34962);
        json.append(',');
        appendBufferView(json, indexOffset, indexLength, 34963);
        json.append(',');
        json.append("{\"buffer\":0,\"byteOffset\":")
                .append(imageOffset)
                .append(",\"byteLength\":")
                .append(imageLength)
                .append('}');
        json.append("],");
        json.append("\"accessors\":[");
        json.append("{\"bufferView\":0,\"componentType\":5126,\"count\":")
                .append(vertexCount)
                .append(",\"type\":\"VEC3\",\"min\":[")
                .append(number(minimum[0])).append(',')
                .append(number(minimum[1])).append(',')
                .append(number(minimum[2]))
                .append("],\"max\":[")
                .append(number(maximum[0])).append(',')
                .append(number(maximum[1])).append(',')
                .append(number(maximum[2])).append("]},");
        json.append("{\"bufferView\":1,\"componentType\":5126,\"count\":")
                .append(vertexCount).append(",\"type\":\"VEC3\"},");
        json.append("{\"bufferView\":2,\"componentType\":5126,\"count\":")
                .append(vertexCount).append(",\"type\":\"VEC2\"},");
        json.append("{\"bufferView\":3,\"componentType\":5125,\"count\":")
                .append(indexCount).append(",\"type\":\"SCALAR\"}");
        json.append("]}");
        return json.toString();
    }

    private static long encodeViewerSafePngToFile(Bitmap source, File target) throws IOException {
        int width = source.getWidth();
        int height = source.getHeight();
        Bitmap cleaned;
        try {
            cleaned = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError error) {
            throw new IOException("Mémoire insuffisante pour préparer la texture GLB", error);
        }

        int[] row = new int[Math.max(1, width)];
        try {
            for (int y = 0; y < height; y++) {
                source.getPixels(row, 0, width, 0, y, width, 1);
                for (int x = 0; x < width; x++) {
                    row[x] = cleanAlpha(row[x]);
                }
                cleaned.setPixels(row, 0, width, 0, y, width, 1);
            }

            try (OutputStream output = new BufferedOutputStream(
                    new FileOutputStream(target),
                    STREAM_BUFFER_BYTES
            )) {
                if (!cleaned.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw new IOException("Impossible d'encoder la texture externe du GLB");
                }
            }
        } finally {
            cleaned.recycle();
        }

        long size = target.length();
        if (size <= 0L) {
            throw new IOException("Texture PNG GLB vide");
        }
        ensureUint32("texture PNG", size);
        return size;
    }

    private static int cleanAlpha(int pixel) {
        int alpha = Color.alpha(pixel);
        if (alpha <= TRANSPARENT_ALPHA) {
            return Color.TRANSPARENT;
        }
        if (alpha >= OPAQUE_ALPHA) {
            return 0xFF000000 | (pixel & 0x00FFFFFF);
        }
        float amount = (alpha - TRANSPARENT_ALPHA)
                / (float) (OPAQUE_ALPHA - TRANSPARENT_ALPHA);
        amount = amount * amount * (3.0f - 2.0f * amount);
        int cleanedAlpha = Math.max(1, Math.min(255, Math.round(amount * 255.0f)));
        return (cleanedAlpha << 24) | (pixel & 0x00FFFFFF);
    }

    private static void writeFloatsLE(OutputStream output, float[] values) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(NUMBER_BUFFER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : values) {
            if (buffer.remaining() < Float.BYTES) {
                flushNumberBuffer(output, buffer);
            }
            buffer.putFloat(value);
        }
        flushNumberBuffer(output, buffer);
    }

    private static void writeIntsLE(OutputStream output, int[] values) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(NUMBER_BUFFER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int value : values) {
            if (buffer.remaining() < Integer.BYTES) {
                flushNumberBuffer(output, buffer);
            }
            buffer.putInt(value);
        }
        flushNumberBuffer(output, buffer);
    }

    private static void flushNumberBuffer(OutputStream output, ByteBuffer buffer) throws IOException {
        int length = buffer.position();
        if (length > 0) {
            output.write(buffer.array(), 0, length);
            buffer.clear();
        }
    }

    private static void copyFile(File source, OutputStream output) throws IOException {
        byte[] buffer = new byte[STREAM_BUFFER_BYTES];
        try (BufferedInputStream input = new BufferedInputStream(
                new FileInputStream(source),
                STREAM_BUFFER_BYTES
        )) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
    }

    private static long padTo(OutputStream output, long cursor, long target) throws IOException {
        if (target < cursor) {
            throw new IOException("Décalage GLB invalide");
        }
        long padding = target - cursor;
        writePadding(output, padding, 0);
        return target;
    }

    private static void writePadding(OutputStream output, long count, int value) throws IOException {
        for (long index = 0L; index < count; index++) {
            output.write(value);
        }
    }

    private static void appendBufferView(
            StringBuilder json,
            long offset,
            long length,
            int target
    ) {
        json.append("{\"buffer\":0,\"byteOffset\":")
                .append(offset)
                .append(",\"byteLength\":")
                .append(length)
                .append(",\"target\":")
                .append(target)
                .append('}');
    }

    private static float[] positionBounds(float[] positions, boolean minimum) {
        float[] result = {
                minimum ? Float.POSITIVE_INFINITY : Float.NEGATIVE_INFINITY,
                minimum ? Float.POSITIVE_INFINITY : Float.NEGATIVE_INFINITY,
                minimum ? Float.POSITIVE_INFINITY : Float.NEGATIVE_INFINITY
        };
        for (int index = 0; index < positions.length; index += 3) {
            for (int axis = 0; axis < 3; axis++) {
                result[axis] = minimum
                        ? Math.min(result[axis], positions[index + axis])
                        : Math.max(result[axis], positions[index + axis]);
            }
        }
        return result;
    }

    private static String number(float value) {
        return Float.toString(value == -0.0f ? 0.0f : value);
    }

    private static long bytesForFloatArray(float[] values) throws IOException {
        return checkedMultiply(values.length, Float.BYTES, "tableau float GLB");
    }

    private static long bytesForIntArray(int[] values) throws IOException {
        return checkedMultiply(values.length, Integer.BYTES, "tableau index GLB");
    }

    private static long checkedMultiply(long count, long size, String label) throws IOException {
        if (count < 0L || size < 0L || count > Long.MAX_VALUE / Math.max(1L, size)) {
            throw new IOException(label + " trop grand");
        }
        return count * size;
    }

    private static long align4(long value) throws IOException {
        if (value < 0L || value > Long.MAX_VALUE - 3L) {
            throw new IOException("Taille GLB invalide");
        }
        return (value + 3L) & ~3L;
    }

    private static void ensureUint32(String label, long value) throws IOException {
        if (value < 0L || value > UINT32_MAX) {
            throw new IOException(label + " dépasse la limite GLB 2.0 de 4 Gio");
        }
    }

    private static void writeUInt32LE(OutputStream output, long value) throws IOException {
        ensureUint32("entier GLB", value);
        writeIntLE(output, (int) value);
    }

    private static void writeIntLE(OutputStream output, int value) throws IOException {
        output.write(value & 0xFF);
        output.write((value >>> 8) & 0xFF);
        output.write((value >>> 16) & 0xFF);
        output.write((value >>> 24) & 0xFF);
    }
}
