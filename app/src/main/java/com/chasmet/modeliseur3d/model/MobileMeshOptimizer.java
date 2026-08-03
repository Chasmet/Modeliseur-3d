package com.chasmet.modeliseur3d.model;

import java.util.Arrays;

/**
 * Produit une copie légère du maillage pour les jeux mobiles.
 * Le GLB HD conserve toujours le maillage original complet.
 */
public final class MobileMeshOptimizer {
    private MobileMeshOptimizer() {
    }

    public static MeshData simplify(MeshData source, int targetTriangles) {
        if (source == null) {
            throw new IllegalArgumentException("Maillage absent");
        }
        int totalTriangles = source.getTriangleCount();
        if (totalTriangles <= 0) {
            throw new IllegalArgumentException("Maillage sans triangle");
        }
        int requested = Math.max(1, Math.min(totalTriangles, targetTriangles));

        float[] sourcePositions = source.getPositions();
        float[] sourceNormals = source.getNormals();
        float[] sourceTexCoords = source.getTexCoords();
        int[] sourceIndices = source.getIndices();
        int sourceVertexCount = source.getVertexCount();

        int[] remap = new int[sourceVertexCount];
        Arrays.fill(remap, -1);

        int maximumVertices = Math.min(sourceVertexCount, requested * 3);
        float[] positions = new float[maximumVertices * 3];
        float[] normals = new float[maximumVertices * 3];
        float[] texCoords = new float[maximumVertices * 2];
        int[] indices = new int[requested * 3];

        int outputVertices = 0;
        int outputIndices = 0;
        int previousTriangle = -1;

        for (int sample = 0; sample < requested; sample++) {
            int triangle = (int) Math.min(
                    totalTriangles - 1L,
                    ((2L * sample + 1L) * totalTriangles)
                            / (2L * requested)
            );
            if (triangle == previousTriangle && triangle + 1 < totalTriangles) {
                triangle++;
            }
            previousTriangle = triangle;

            int sourceOffset = triangle * 3;
            int a = sourceIndices[sourceOffset];
            int b = sourceIndices[sourceOffset + 1];
            int c = sourceIndices[sourceOffset + 2];
            if (!validVertex(a, sourceVertexCount)
                    || !validVertex(b, sourceVertexCount)
                    || !validVertex(c, sourceVertexCount)
                    || a == b || b == c || a == c) {
                continue;
            }

            int mappedA = remap[a];
            if (mappedA < 0) {
                mappedA = outputVertices++;
                remap[a] = mappedA;
                copyVertex(
                        a,
                        mappedA,
                        sourcePositions,
                        sourceNormals,
                        sourceTexCoords,
                        positions,
                        normals,
                        texCoords
                );
            }
            int mappedB = remap[b];
            if (mappedB < 0) {
                mappedB = outputVertices++;
                remap[b] = mappedB;
                copyVertex(
                        b,
                        mappedB,
                        sourcePositions,
                        sourceNormals,
                        sourceTexCoords,
                        positions,
                        normals,
                        texCoords
                );
            }
            int mappedC = remap[c];
            if (mappedC < 0) {
                mappedC = outputVertices++;
                remap[c] = mappedC;
                copyVertex(
                        c,
                        mappedC,
                        sourcePositions,
                        sourceNormals,
                        sourceTexCoords,
                        positions,
                        normals,
                        texCoords
                );
            }

            indices[outputIndices++] = mappedA;
            indices[outputIndices++] = mappedB;
            indices[outputIndices++] = mappedC;
        }

        if (outputIndices < 3 || outputVertices < 3) {
            throw new IllegalArgumentException(
                    "La simplification n'a conservé aucun triangle valide"
            );
        }

        return new MeshData(
                Arrays.copyOf(positions, outputVertices * 3),
                Arrays.copyOf(normals, outputVertices * 3),
                Arrays.copyOf(texCoords, outputVertices * 2),
                Arrays.copyOf(indices, outputIndices)
        );
    }

    private static boolean validVertex(int index, int vertexCount) {
        return index >= 0 && index < vertexCount;
    }

    private static void copyVertex(
            int sourceIndex,
            int targetIndex,
            float[] sourcePositions,
            float[] sourceNormals,
            float[] sourceTexCoords,
            float[] positions,
            float[] normals,
            float[] texCoords
    ) {
        int sourcePositionOffset = sourceIndex * 3;
        int targetPositionOffset = targetIndex * 3;
        positions[targetPositionOffset] = sourcePositions[sourcePositionOffset];
        positions[targetPositionOffset + 1] = sourcePositions[sourcePositionOffset + 1];
        positions[targetPositionOffset + 2] = sourcePositions[sourcePositionOffset + 2];
        normals[targetPositionOffset] = sourceNormals[sourcePositionOffset];
        normals[targetPositionOffset + 1] = sourceNormals[sourcePositionOffset + 1];
        normals[targetPositionOffset + 2] = sourceNormals[sourcePositionOffset + 2];

        int sourceUvOffset = sourceIndex * 2;
        int targetUvOffset = targetIndex * 2;
        texCoords[targetUvOffset] = sourceTexCoords[sourceUvOffset];
        texCoords[targetUvOffset + 1] = sourceTexCoords[sourceUvOffset + 1];
    }
}
