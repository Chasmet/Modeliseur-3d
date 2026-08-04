package com.chasmet.modeliseur3d.model;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Export GLB réservé au mode 3D et optimisé pour les visionneuses externes.
 *
 * Le maillage, les triangles, les normales, les UV et la résolution de la
 * texture restent strictement identiques à l'aperçu. Seule la manière dont le
 * matériau est déclaré change afin d'éviter les copies fantômes visibles dans
 * Google Scene Viewer :
 *
 * - aucun mipmap sur l'atlas multivue, donc aucune contamination entre face,
 *   dos et profils ;
 * - alpha de bord nettoyé avant l'encodage PNG ;
 * - faces arrière masquées pour empêcher la texture opposée de traverser le
 *   personnage ;
 * - matériau non éclairé afin de conserver les couleurs de l'aperçu Android.
 *
 * Le moteur 2.5D continue d'utiliser GlbExporter sans aucune modification.
 */
public final class ExternalViewerGlbExporter {
    private static final int GLB_MAGIC = 0x46546C67;
    private static final int GLB_VERSION = 2;
    private static final int JSON_CHUNK_TYPE = 0x4E4F534A;
    private static final int BIN_CHUNK_TYPE = 0x004E4942;

    /** En dessous de cette valeur, il s'agit essentiellement du halo IS-Net. */
    private static final int TRANSPARENT_ALPHA = 44;
    /** Au-dessus, le pixel devient totalement opaque dans le matériau MASK. */
    private static final int OPAQUE_ALPHA = 92;

    private ExternalViewerGlbExporter() {
    }

    public static void write(
            File outputFile,
            MeshData mesh,
            Bitmap texture
    ) throws IOException {
        byte[] png = encodeViewerSafePng(texture);
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

        ByteBuffer binary = ByteBuffer.allocate(binaryLength)
                .order(ByteOrder.LITTLE_ENDIAN);
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
            for (int index = jsonBytes.length; index < paddedJsonLength; index++) {
                output.write(0x20);
            }

            writeIntLE(output, paddedBinaryLength);
            writeIntLE(output, BIN_CHUNK_TYPE);
            output.write(binary.array(), 0, binaryLength);
            for (int index = binaryLength; index < paddedBinaryLength; index++) {
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
            float[] minimum,
            float[] maximum
    ) {
        StringBuilder json = new StringBuilder(2100);
        json.append('{');
        json.append("\"asset\":{\"version\":\"2.0\",\"generator\":\"Modeliseur 3D V5.9.5 Android\"},");
        json.append("\"extensionsUsed\":[\"KHR_materials_unlit\"],");
        json.append("\"scene\":0,");
        json.append("\"scenes\":[{\"nodes\":[0]}],");
        json.append("\"nodes\":[{\"mesh\":0,\"name\":\"Personnage 3D\"}],");
        json.append("\"meshes\":[{\"name\":\"Personnage 3D\",\"primitives\":[{");
        json.append("\"attributes\":{\"POSITION\":0,\"NORMAL\":1,\"TEXCOORD_0\":2},");
        json.append("\"indices\":3,\"material\":0,\"mode\":4}]}],");
        json.append("\"materials\":[{\"name\":\"Texture multivue propre\",");
        json.append("\"pbrMetallicRoughness\":{");
        json.append("\"baseColorFactor\":[1.0,1.0,1.0,1.0],");
        json.append("\"baseColorTexture\":{\"index\":0},");
        json.append("\"metallicFactor\":0.0,\"roughnessFactor\":1.0},");
        json.append("\"extensions\":{\"KHR_materials_unlit\":{}},");
        json.append("\"doubleSided\":false,");
        json.append("\"alphaMode\":\"MASK\",\"alphaCutoff\":0.24}],");
        json.append("\"textures\":[{\"sampler\":0,\"source\":0}],");
        // GL_LINEAR sans mipmap : l'atlas ne peut plus mélanger les quatre vues.
        json.append("\"samplers\":[{\"magFilter\":9729,\"minFilter\":9729,");
        json.append("\"wrapS\":33071,\"wrapT\":33071}],");
        json.append("\"images\":[{\"bufferView\":4,\"mimeType\":\"image/png\",");
        json.append("\"name\":\"texture_multivue_sans_mipmap\"}],");
        json.append("\"buffers\":[{\"byteLength\":")
                .append(binaryLength).append("}],");
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
                .append(number(maximum[2]))
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

    private static byte[] encodeViewerSafePng(Bitmap source) throws IOException {
        int width = source.getWidth();
        int height = source.getHeight();
        int[] pixels = new int[width * height];
        source.getPixels(pixels, 0, width, 0, 0, width, height);

        for (int index = 0; index < pixels.length; index++) {
            int pixel = pixels[index];
            int alpha = Color.alpha(pixel);
            if (alpha <= TRANSPARENT_ALPHA) {
                pixels[index] = Color.TRANSPARENT;
            } else if (alpha >= OPAQUE_ALPHA) {
                pixels[index] = 0xFF000000 | (pixel & 0x00FFFFFF);
            } else {
                float amount = (alpha - TRANSPARENT_ALPHA)
                        / (float) (OPAQUE_ALPHA - TRANSPARENT_ALPHA);
                amount = amount * amount * (3.0f - 2.0f * amount);
                int cleanedAlpha = Math.max(1, Math.min(255, Math.round(amount * 255.0f)));
                pixels[index] = (cleanedAlpha << 24) | (pixel & 0x00FFFFFF);
            }
        }

        Bitmap cleaned = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        cleaned.setPixels(pixels, 0, width, 0, 0, width, height);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!cleaned.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IOException("Impossible d'encoder la texture externe du GLB");
            }
            return output.toByteArray();
        } finally {
            cleaned.recycle();
        }
    }

    private static void appendBufferView(
            StringBuilder json,
            int offset,
            int length,
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
        float[] result = new float[]{
                minimum ? Float.POSITIVE_INFINITY : Float.NEGATIVE_INFINITY,
                minimum ? Float.POSITIVE_INFINITY : Float.NEGATIVE_INFINITY,
                minimum ? Float.POSITIVE_INFINITY : Float.NEGATIVE_INFINITY
        };
        for (int index = 0; index < positions.length; index += 3) {
            for (int axis = 0; axis < 3; axis++) {
                if (minimum) {
                    result[axis] = Math.min(result[axis], positions[index + axis]);
                } else {
                    result[axis] = Math.max(result[axis], positions[index + axis]);
                }
            }
        }
        return result;
    }

    private static String number(float value) {
        return Float.toString(value == -0.0f ? 0.0f : value);
    }

    private static int align4(int value) {
        return (value + 3) & ~3;
    }

    private static void writeIntLE(FileOutputStream output, int value)
            throws IOException {
        output.write(value & 0xFF);
        output.write((value >>> 8) & 0xFF);
        output.write((value >>> 16) & 0xFF);
        output.write((value >>> 24) & 0xFF);
    }
}
