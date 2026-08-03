package com.chasmet.modeliseur3d.model;

import java.util.Arrays;

/** Ajuste uniquement la profondeur d'un maillage Face/Dos sans déformer ses UV. */
public final class MeshDepthScaler {
    private static final float EPSILON = 1.0e-7f;

    private MeshDepthScaler() {
    }

    public static MeshData scaleDepth(MeshData source, float multiplier) {
        if (source == null) {
            throw new IllegalArgumentException("Maillage absent");
        }
        float safeMultiplier = Math.max(0.50f, Math.min(2.00f, multiplier));
        float[] positions = Arrays.copyOf(
                source.getPositions(),
                source.getPositions().length
        );
        for (int offset = 2; offset < positions.length; offset += 3) {
            positions[offset] *= safeMultiplier;
        }

        int[] indices = Arrays.copyOf(
                source.getIndices(),
                source.getIndices().length
        );
        float[] normals = new float[positions.length];
        computeNormals(positions, indices, normals);
        return new MeshData(
                positions,
                normals,
                Arrays.copyOf(
                        source.getTexCoords(),
                        source.getTexCoords().length
                ),
                indices
        );
    }

    private static void computeNormals(
            float[] positions,
            int[] indices,
            float[] normals
    ) {
        for (int index = 0; index + 2 < indices.length; index += 3) {
            int a = indices[index];
            int b = indices[index + 1];
            int c = indices[index + 2];
            int ao = a * 3;
            int bo = b * 3;
            int co = c * 3;

            float abx = positions[bo] - positions[ao];
            float aby = positions[bo + 1] - positions[ao + 1];
            float abz = positions[bo + 2] - positions[ao + 2];
            float acx = positions[co] - positions[ao];
            float acy = positions[co + 1] - positions[ao + 1];
            float acz = positions[co + 2] - positions[ao + 2];

            float nx = aby * acz - abz * acy;
            float ny = abz * acx - abx * acz;
            float nz = abx * acy - aby * acx;
            add(normals, a, nx, ny, nz);
            add(normals, b, nx, ny, nz);
            add(normals, c, nx, ny, nz);
        }
        for (int vertex = 0; vertex < normals.length / 3; vertex++) {
            int offset = vertex * 3;
            float x = normals[offset];
            float y = normals[offset + 1];
            float z = normals[offset + 2];
            float length = (float) Math.sqrt(x * x + y * y + z * z);
            if (length <= EPSILON) {
                normals[offset + 2] = 1.0f;
            } else {
                normals[offset] = x / length;
                normals[offset + 1] = y / length;
                normals[offset + 2] = z / length;
            }
        }
    }

    private static void add(
            float[] normals,
            int vertex,
            float x,
            float y,
            float z
    ) {
        int offset = vertex * 3;
        normals[offset] += x;
        normals[offset + 1] += y;
        normals[offset + 2] += z;
    }
}
