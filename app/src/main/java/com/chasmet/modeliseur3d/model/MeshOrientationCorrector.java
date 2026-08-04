package com.chasmet.modeliseur3d.model;

import java.util.Arrays;

/**
 * Corrige le repère entre Bitmap Android et OpenGL.
 *
 * Les pixels Android commencent en haut alors que les coordonnées de texture
 * OpenGL commencent en bas. La V5.8 appliquait donc la texture verticalement à
 * l'envers. Le signe Y des normales est également aligné sur le repère du
 * maillage. Les positions restent inchangées : le personnage ne subit aucune
 * rotation artificielle dans le fichier GLB.
 */
public final class MeshOrientationCorrector {
    private MeshOrientationCorrector() {
    }

    public static MeshData correct(MeshData source) {
        if (source == null) {
            throw new IllegalArgumentException("Maillage absent");
        }
        float[] positions = Arrays.copyOf(
                source.getPositions(),
                source.getPositions().length
        );
        float[] normals = Arrays.copyOf(
                source.getNormals(),
                source.getNormals().length
        );
        float[] texCoords = Arrays.copyOf(
                source.getTexCoords(),
                source.getTexCoords().length
        );
        int[] indices = Arrays.copyOf(
                source.getIndices(),
                source.getIndices().length
        );

        for (int index = 1; index < normals.length; index += 3) {
            normals[index] = -normals[index];
        }
        for (int index = 1; index < texCoords.length; index += 2) {
            texCoords[index] = clamp01(1.0f - texCoords[index]);
        }
        return new MeshData(positions, normals, texCoords, indices);
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
