package com.chasmet.modeliseur3d.model;

import android.graphics.Bitmap;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Écrit un fichier GLB 2.0 autonome avec maillage, normales, UV et texture PNG intégrée. */
public final class GlbExporter {
    private static final int GLB_MAGIC = 0x46546C67;
    private static final int GLB_VERSION = 2;
    private static final int JSON_CHUNK_TYPE = 0x4E4F534A;
    private static final int BIN_CHUNK_TYPE = 0x004E4942;

    private GlbExporter() {
    }

    public static void write(File outputFile, MeshData mesh, Bitmap texture) throws IOException {
        byte[] png = encodePng(texture);
        float[] positions = mesh.getPositions();
        float[] normals = mesh.getNormals();
        float[] texCoords = mesh.getTexCoords();
        int[] indices = mesh.getIndices();

        int positionOffset = 0;
        int positionLength = positions.length * 4;
        int normalOffset = align4(positionOffset + positionLength);
        int normalLength = normals.length * 4;
        int texCoordOffset = align4(normalOffset + normalLength);
        int texCoordLength = texCoords.length * 4;
        int indexOffset = align4(texCoordOffset + texCoordLength);
        int indexLength = indices.length * 4;
        int imageOffset = align4(indexOffset + indexLength);
        int imageLength = png.length;
        int binaryLength = align4(imageOffset + imageLength);

        ByteBuffer binary = ByteBuffer.allocate(binaryLength).order(ByteOrder.LITTLE_ENDIAN);
        binary.position(positionOffset);
        for (float value : positions) {
            binary.putFloat(value);
        }
        binary.position(normalOffset);
        for (float value : normals) {
            binary.putFloat(value);
        }
        binary.position(texCoordOffset);
        for (float value : texCoords) {
            binary.putFloat(value);
        }
        binary.position(indexOffset);
        for (int value : indices) {
            binary.putInt(value);
        }
        binary.position(imageOffset);
        binary.put(png);

        float[] min = positionBounds(positions, true);
        float[] max = positionBounds(positions, false);
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
                min,
                max
        );
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        int paddedJsonLength = align4(jsonBytes.length);
        int paddedBinaryLength = align4(binaryLength);
        int totalLength = 12 + 8 + paddedJsonLength + 8 + paddedBinaryLength;

        try (FileOutputStream output = new FileOutputStream(outputFile)) {
            writeIntLE(output, GLB_MAGIC);
            writeIntLE(output, GLB_VERSION);
            writeIntLE(output, totalLength);

            writeIntLE(output, paddedJsonLength);
            writeIntLE(output, JSON_CHUNK_TYPE);
            output.write(jsonBytes);
            for (int i = jsonBytes.length; i < paddedJsonLength; i++) {
                output.write(0x20);
            }

            writeIntLE(output, paddedBinaryLength);
            writeIntLE(output, BIN_CHUNK_TYPE);
            output.write(binary.array(), 0, binaryLength);
            for (int i = binaryLength; i < paddedBinaryLength; i++) {
                output.write(0);
            }
        }
    }

    private static String buildJson(
            int binaryLength,
            int positionOffset,
            int positionLength,
            int normalOffset,
            int normalLength,
            int texCoordOffset,
            int texCoordLength,
            int indexOffset,
            int indexLength,
            int imageOffset,
            int imageLength,
            int vertexCount,
            int indexCount,
            float[] min,
            float[] max
    ) {
        StringBuilder json = new StringBuilder(1800);
        json.append('{');
        json.append("\"asset\":{\"version\":\"2.0\",\"generator\":\"Modeliseur 3D V4 Neural Android\"},");
        json.append("\"scene\":0,");
        json.append("\"scenes\":[{\"nodes\":[0]}],");
        json.append("\"nodes\":[{\"mesh\":0,\"name\":\"Personnage V4 Neural\"}],");
        json.append("\"meshes\":[{\"name\":\"Personnage V4 Neural\",\"primitives\":[{");
        json.append("\"attributes\":{\"POSITION\":0,\"NORMAL\":1,\"TEXCOORD_0\":2},");
        json.append("\"indices\":3,\"material\":0}]}],");
        json.append("\"materials\":[{\"name\":\"Texture multivue neuronale\",");
        json.append("\"pbrMetallicRoughness\":{\"baseColorTexture\":{\"index\":0},");
        json.append("\"metallicFactor\":0.0,\"roughnessFactor\":0.82},");
        json.append("\"doubleSided\":true,\"alphaMode\":\"MASK\",\"alphaCutoff\":0.05}],");
        json.append("\"textures\":[{\"sampler\":0,\"source\":0}],");
        json.append("\"samplers\":[{\"magFilter\":9729,\"minFilter\":9987,\"wrapS\":33071,\"wrapT\":33071}],");
        json.append("\"images\":[{\"bufferView\":4,\"mimeType\":\"image/png\",\"name\":\"texture_multivue_v4\"}],");
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
        json.append("{\"buffer\":0,\"byteOffset\":").append(imageOffset)
                .append(",\"byteLength\":").append(imageLength).append('}');
        json.append("],");
        json.append("\"accessors\":[");
        json.append("{\"bufferView\":0,\"componentType\":5126,\"count\":")
                .append(vertexCount)
                .append(",\"type\":\"VEC3\",\"min\":[")
                .append(number(min[0])).append(',').append(number(min[1])).append(',').append(number(min[2]))
                .append("],\"max\":[")
                .append(number(max[0])).append(',').append(number(max[1])).append(',').append(number(max[2]))
                .append("]},");
        json.append("{\"bufferView\":1,\"componentType\":5126,\"count\":")
                .append(vertexCount).append(",\"type\":\"VEC3\"},");
        json.append("{\"bufferView\":2,\"componentType\":5126,\"count\":")
                .append(vertexCount).append(",\"type\":\"VEC2\"},");
        json.append("{\"bufferView\":3,\"componentType\":5125,\"count\":")
                .append(indexCount).append(",\"type\":\"SCALAR\"}");
        json.append("]}");
        return json.toString();
    }

    private static void appendBufferView(StringBuilder json, int offset, int length, int target) {
        json.append("{\"buffer\":0,\"byteOffset\":").append(offset)
                .append(",\"byteLength\":").append(length)
                .append(",\"target\":").append(target).append('}');
    }

    private static float[] positionBounds(float[] positions, boolean minimum) {
        float[] result = new float[]{
                minimum ? Float.POSITIVE_INFINITY : Float.NEGATIVE_INFINITY,
                minimum ? Float.POSITIVE_INFINITY : Float.NEGATIVE_INFINITY,
                minimum ? Float.POSITIVE_INFINITY : Float.NEGATIVE_INFINITY
        };
        for (int i = 0; i < positions.length; i += 3) {
            for (int axis = 0; axis < 3; axis++) {
                if (minimum) {
                    result[axis] = Math.min(result[axis], positions[i + axis]);
                } else {
                    result[axis] = Math.max(result[axis], positions[i + axis]);
                }
            }
        }
        return result;
    }

    private static String number(float value) {
        if (value == -0.0f) {
            value = 0.0f;
        }
        return Float.toString(value);
    }

    private static byte[] encodePng(Bitmap texture) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!texture.compress(Bitmap.CompressFormat.PNG, 100, output)) {
            throw new IOException("Impossible d'encoder la texture PNG du GLB");
        }
        return output.toByteArray();
    }

    private static int align4(int value) {
        return (value + 3) & ~3;
    }

    private static void writeIntLE(FileOutputStream output, int value) throws IOException {
        output.write(value & 0xFF);
        output.write((value >>> 8) & 0xFF);
        output.write((value >>> 16) & 0xFF);
        output.write((value >>> 24) & 0xFF);
    }
}
