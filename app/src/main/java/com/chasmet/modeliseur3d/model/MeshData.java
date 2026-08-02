package com.chasmet.modeliseur3d.model;

import java.util.Objects;

public final class MeshData {
    private final float[] positions;
    private final float[] normals;
    private final float[] texCoords;
    private final int[] indices;

    public MeshData(float[] positions, float[] normals, float[] texCoords, int[] indices) {
        this.positions = Objects.requireNonNull(positions, "positions");
        this.normals = Objects.requireNonNull(normals, "normals");
        this.texCoords = Objects.requireNonNull(texCoords, "texCoords");
        this.indices = Objects.requireNonNull(indices, "indices");

        int vertexCount = positions.length / 3;
        if (positions.length % 3 != 0 || normals.length != positions.length) {
            throw new IllegalArgumentException("Positions ou normales invalides");
        }
        if (texCoords.length != vertexCount * 2) {
            throw new IllegalArgumentException("Coordonnées UV invalides");
        }
    }

    public float[] getPositions() {
        return positions;
    }

    public float[] getNormals() {
        return normals;
    }

    public float[] getTexCoords() {
        return texCoords;
    }

    public int[] getIndices() {
        return indices;
    }

    public int getVertexCount() {
        return positions.length / 3;
    }

    public int getTriangleCount() {
        return indices.length / 3;
    }
}
