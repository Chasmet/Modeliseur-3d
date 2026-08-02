package com.chasmet.modeliseur3d.model;

import android.graphics.Bitmap;

import java.util.Arrays;

/** Corrige les vues de l'atlas puis ajoute un relief neuronal volontairement borné. */
final class NeuralMeshRefiner {
    private static final int GAP = 8;
    private static final float FRONT_BACK = 0.038f;
    private static final float SIDE = 0.028f;
    private static final float NORMAL = 0.009f;

    private NeuralMeshRefiner() {}

    static Views cropViews(Bitmap atlas) {
        int height = atlas.getHeight();
        int frontWidth = Math.max(128, Math.round(height * 0.5f));
        int remaining = atlas.getWidth() - GAP * 5 - frontWidth * 2;
        int sideWidth = Math.max(96, remaining / 2);
        int frontStart = GAP;
        int backStart = frontStart + frontWidth + GAP;
        int sideStart = backStart + frontWidth + GAP;
        if (sideStart + sideWidth > atlas.getWidth()) {
            throw new IllegalArgumentException("Atlas V4.1 incompatible");
        }
        return new Views(
                Bitmap.createBitmap(atlas, frontStart, 0, frontWidth, height),
                Bitmap.createBitmap(atlas, backStart, 0, frontWidth, height),
                Bitmap.createBitmap(atlas, sideStart, 0, sideWidth, height)
        );
    }

    static MeshData refine(
            MeshData source,
            NeuralDepthEngine.DepthMap front,
            NeuralDepthEngine.DepthMap back,
            NeuralDepthEngine.DepthMap side
    ) {
        float[] positions = Arrays.copyOf(source.getPositions(), source.getPositions().length);
        float[] originalNormals = source.getNormals();
        float[] normals = Arrays.copyOf(originalNormals, originalNormals.length);
        float[] uv = Arrays.copyOf(source.getTexCoords(), source.getTexCoords().length);
        int[] indices = Arrays.copyOf(source.getIndices(), source.getIndices().length);
        Bounds bounds = Bounds.from(positions);
        float width = Math.max(1.0e-6f, bounds.maxX - bounds.minX);
        float height = Math.max(1.0e-6f, bounds.maxY - bounds.minY);
        float depth = Math.max(1.0e-6f, bounds.maxZ - bounds.minZ);

        for (int vertex = 0; vertex < positions.length / 3; vertex++) {
            int i = vertex * 3;
            float x = positions[i];
            float y = positions[i + 1];
            float z = positions[i + 2];
            float nx = originalNormals[i];
            float ny = originalNormals[i + 1];
            float nz = originalNormals[i + 2];
            float u = clamp01((x - bounds.minX) / width);
            float v = clamp01((bounds.maxY - y) / height);
            float w = clamp01((z - bounds.minZ) / depth);

            float fv = centered(front.sample(u, v));
            float bv = centered(back.sample(1.0f - u, v));
            float rv = centered(side.sample(w, v));
            float lv = centered(side.sample(1.0f - w, v));
            float fw = square(Math.max(0.0f, nz));
            float bw = square(Math.max(0.0f, -nz));
            float rw = square(Math.max(0.0f, nx));
            float lw = square(Math.max(0.0f, -nx));
            float total = Math.max(1.0e-5f, fw + bw + rw + lw);
            float dx = (rv * rw - lv * lw) / total * SIDE;
            float dz = (fv * fw - bv * bw) / total * FRONT_BACK;
            float relief = (fv * fw + bv * bw + rv * rw + lv * lw) / total;
            float gate = 0.30f + 0.70f * clamp01(Math.max(Math.abs(nx), Math.abs(nz)));
            dx = clamp(dx * gate, -SIDE * 0.42f, SIDE * 0.42f);
            dz = clamp(dz * gate, -FRONT_BACK * 0.42f, FRONT_BACK * 0.42f);
            float dn = clamp(relief * NORMAL, -NORMAL * 0.50f, NORMAL * 0.50f);
            positions[i] = x + dx + nx * dn;
            positions[i + 1] = y + ny * dn * 0.20f;
            positions[i + 2] = z + dz + nz * dn;
        }
        rebuildNormals(positions, originalNormals, indices, normals);
        return new MeshData(positions, normals, uv, indices);
    }

    private static void rebuildNormals(
            float[] positions, float[] original, int[] indices, float[] output
    ) {
        Arrays.fill(output, 0.0f);
        for (int t = 0; t + 2 < indices.length; t += 3) {
            int ai = indices[t] * 3;
            int bi = indices[t + 1] * 3;
            int ci = indices[t + 2] * 3;
            float abx = positions[bi] - positions[ai];
            float aby = positions[bi + 1] - positions[ai + 1];
            float abz = positions[bi + 2] - positions[ai + 2];
            float acx = positions[ci] - positions[ai];
            float acy = positions[ci + 1] - positions[ai + 1];
            float acz = positions[ci + 2] - positions[ai + 2];
            float nx = aby * acz - abz * acy;
            float ny = abz * acx - abx * acz;
            float nz = abx * acy - aby * acx;
            float length = length(nx, ny, nz);
            if (length < 1.0e-8f) continue;
            nx /= length;
            ny /= length;
            nz /= length;
            blend(output, original, ai, nx, ny, nz);
            blend(output, original, bi, nx, ny, nz);
            blend(output, original, ci, nx, ny, nz);
        }
        for (int i = 0; i < output.length; i += 3) {
            float length = length(output[i], output[i + 1], output[i + 2]);
            if (length < 1.0e-8f) {
                output[i] = original[i];
                output[i + 1] = original[i + 1];
                output[i + 2] = original[i + 2];
            } else {
                output[i] /= length;
                output[i + 1] /= length;
                output[i + 2] /= length;
            }
        }
    }

    private static void blend(
            float[] output, float[] original, int i, float nx, float ny, float nz
    ) {
        output[i] += original[i] * 0.60f + nx * 0.40f;
        output[i + 1] += original[i + 1] * 0.60f + ny * 0.40f;
        output[i + 2] += original[i + 2] * 0.60f + nz * 0.40f;
    }

    private static float centered(float value) {
        float c = (value - 0.5f) * 2.0f;
        return clamp(Math.signum(c) * (float) Math.pow(Math.abs(c), 0.88f), -1.0f, 1.0f);
    }

    private static float square(float value) { return value * value; }
    private static float length(float x, float y, float z) {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }
    private static float clamp01(float value) { return clamp(value, 0.0f, 1.0f); }
    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    static final class Views implements AutoCloseable {
        final Bitmap front;
        final Bitmap back;
        final Bitmap side;
        Views(Bitmap front, Bitmap back, Bitmap side) {
            this.front = front;
            this.back = back;
            this.side = side;
        }
        @Override public void close() {
            if (!front.isRecycled()) front.recycle();
            if (!back.isRecycled()) back.recycle();
            if (!side.isRecycled()) side.recycle();
        }
    }

    private static final class Bounds {
        final float minX, minY, minZ, maxX, maxY, maxZ;
        Bounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }
        static Bounds from(float[] p) {
            float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
            float minZ = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
            float maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < p.length; i += 3) {
                minX = Math.min(minX, p[i]);
                minY = Math.min(minY, p[i + 1]);
                minZ = Math.min(minZ, p[i + 2]);
                maxX = Math.max(maxX, p[i]);
                maxY = Math.max(maxY, p[i + 1]);
                maxZ = Math.max(maxZ, p[i + 2]);
            }
            return new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }
}
