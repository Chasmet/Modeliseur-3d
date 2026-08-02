package com.chasmet.modeliseur3d.model;

import java.util.Arrays;

/**
 * Soudure géométrique et lissage du maillage sans toucher aux coutures UV.
 */
public final class MeshSurfaceOptimizer {
    private MeshSurfaceOptimizer() {
    }

    public static MeshData optimize(MeshData source, int iterations) {
        float[] sourcePositions = source.getPositions();
        int vertexCount = source.getVertexCount();
        if (vertexCount < 12 || iterations <= 0) {
            return source;
        }

        Bounds bounds = Bounds.from(sourcePositions);
        float maximumSize = Math.max(
                bounds.maxX - bounds.minX,
                Math.max(
                        bounds.maxY - bounds.minY,
                        bounds.maxZ - bounds.minZ
                )
        );
        float quantum = Math.max(1.0e-5f, maximumSize / 4096.0f);

        LongIntMap nodesByPosition = new LongIntMap(vertexCount * 2);
        int[] vertexNode = new int[vertexCount];
        float[] nodeX = new float[vertexCount];
        float[] nodeY = new float[vertexCount];
        float[] nodeZ = new float[vertexCount];
        int[] nodeSamples = new int[vertexCount];
        int nodeCount = 0;

        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int position = vertex * 3;
            float x = sourcePositions[position];
            float y = sourcePositions[position + 1];
            float z = sourcePositions[position + 2];
            long key = positionKey(x, y, z, bounds, quantum);
            int existing = nodesByPosition.get(key);
            int node;
            if (existing < 0) {
                node = nodeCount++;
                nodesByPosition.put(key, node);
            } else {
                node = existing;
            }
            vertexNode[vertex] = node;
            nodeX[node] += x;
            nodeY[node] += y;
            nodeZ[node] += z;
            nodeSamples[node]++;
        }

        for (int node = 0; node < nodeCount; node++) {
            float divisor = Math.max(1, nodeSamples[node]);
            nodeX[node] /= divisor;
            nodeY[node] /= divisor;
            nodeZ[node] /= divisor;
        }

        int[] indices = source.getIndices();
        int passes = Math.max(1, Math.min(4, iterations));
        for (int iteration = 0; iteration < passes; iteration++) {
            smoothPass(
                    nodeX,
                    nodeY,
                    nodeZ,
                    nodeCount,
                    vertexNode,
                    indices,
                    0.22f
            );
            smoothPass(
                    nodeX,
                    nodeY,
                    nodeZ,
                    nodeCount,
                    vertexNode,
                    indices,
                    -0.235f
            );
        }

        float[] positions = Arrays.copyOf(
                sourcePositions,
                sourcePositions.length
        );
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int node = vertexNode[vertex];
            int position = vertex * 3;
            positions[position] = nodeX[node];
            positions[position + 1] = nodeY[node];
            positions[position + 2] = nodeZ[node];
        }

        float[] nodeNx = new float[nodeCount];
        float[] nodeNy = new float[nodeCount];
        float[] nodeNz = new float[nodeCount];
        for (int triangle = 0; triangle + 2 < indices.length; triangle += 3) {
            int va = indices[triangle];
            int vb = indices[triangle + 1];
            int vc = indices[triangle + 2];
            int a = vertexNode[va];
            int b = vertexNode[vb];
            int c = vertexNode[vc];

            float abX = nodeX[b] - nodeX[a];
            float abY = nodeY[b] - nodeY[a];
            float abZ = nodeZ[b] - nodeZ[a];
            float acX = nodeX[c] - nodeX[a];
            float acY = nodeY[c] - nodeY[a];
            float acZ = nodeZ[c] - nodeZ[a];

            float nx = abY * acZ - abZ * acY;
            float ny = abZ * acX - abX * acZ;
            float nz = abX * acY - abY * acX;
            float length = length(nx, ny, nz);
            if (length < 1.0e-10f) {
                continue;
            }
            nx /= length;
            ny /= length;
            nz /= length;
            nodeNx[a] += nx;
            nodeNy[a] += ny;
            nodeNz[a] += nz;
            nodeNx[b] += nx;
            nodeNy[b] += ny;
            nodeNz[b] += nz;
            nodeNx[c] += nx;
            nodeNy[c] += ny;
            nodeNz[c] += nz;
        }

        float[] normals = new float[positions.length];
        float[] fallbackNormals = source.getNormals();
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int node = vertexNode[vertex];
            int position = vertex * 3;
            float nx = nodeNx[node];
            float ny = nodeNy[node];
            float nz = nodeNz[node];
            float normalLength = length(nx, ny, nz);
            if (normalLength < 1.0e-8f) {
                normals[position] = fallbackNormals[position];
                normals[position + 1] = fallbackNormals[position + 1];
                normals[position + 2] = fallbackNormals[position + 2];
            } else {
                normals[position] = nx / normalLength;
                normals[position + 1] = ny / normalLength;
                normals[position + 2] = nz / normalLength;
            }
        }

        return new MeshData(
                positions,
                normals,
                Arrays.copyOf(
                        source.getTexCoords(),
                        source.getTexCoords().length
                ),
                Arrays.copyOf(indices, indices.length)
        );
    }

    private static void smoothPass(
            float[] x,
            float[] y,
            float[] z,
            int nodeCount,
            int[] vertexNode,
            int[] indices,
            float amount
    ) {
        float[] sumX = new float[nodeCount];
        float[] sumY = new float[nodeCount];
        float[] sumZ = new float[nodeCount];
        int[] count = new int[nodeCount];

        for (int triangle = 0; triangle + 2 < indices.length; triangle += 3) {
            int a = vertexNode[indices[triangle]];
            int b = vertexNode[indices[triangle + 1]];
            int c = vertexNode[indices[triangle + 2]];
            addNeighbour(a, b, x, y, z, sumX, sumY, sumZ, count);
            addNeighbour(b, a, x, y, z, sumX, sumY, sumZ, count);
            addNeighbour(b, c, x, y, z, sumX, sumY, sumZ, count);
            addNeighbour(c, b, x, y, z, sumX, sumY, sumZ, count);
            addNeighbour(c, a, x, y, z, sumX, sumY, sumZ, count);
            addNeighbour(a, c, x, y, z, sumX, sumY, sumZ, count);
        }

        float[] nextX = Arrays.copyOf(x, nodeCount);
        float[] nextY = Arrays.copyOf(y, nodeCount);
        float[] nextZ = Arrays.copyOf(z, nodeCount);
        for (int node = 0; node < nodeCount; node++) {
            if (count[node] == 0) {
                continue;
            }
            float averageX = sumX[node] / count[node];
            float averageY = sumY[node] / count[node];
            float averageZ = sumZ[node] / count[node];
            nextX[node] = x[node] + (averageX - x[node]) * amount;
            nextY[node] = y[node] + (averageY - y[node]) * amount;
            nextZ[node] = z[node] + (averageZ - z[node]) * amount;
        }

        System.arraycopy(nextX, 0, x, 0, nodeCount);
        System.arraycopy(nextY, 0, y, 0, nodeCount);
        System.arraycopy(nextZ, 0, z, 0, nodeCount);
    }

    private static void addNeighbour(
            int target,
            int neighbour,
            float[] x,
            float[] y,
            float[] z,
            float[] sumX,
            float[] sumY,
            float[] sumZ,
            int[] count
    ) {
        if (target == neighbour) {
            return;
        }
        sumX[target] += x[neighbour];
        sumY[target] += y[neighbour];
        sumZ[target] += z[neighbour];
        count[target]++;
    }

    private static long positionKey(
            float x,
            float y,
            float z,
            Bounds bounds,
            float quantum
    ) {
        int qx = Math.round((x - bounds.minX) / quantum);
        int qy = Math.round((y - bounds.minY) / quantum);
        int qz = Math.round((z - bounds.minZ) / quantum);
        long key = ((long) qx & 0x1FFFFFL) << 42
                | ((long) qy & 0x1FFFFFL) << 21
                | ((long) qz & 0x1FFFFFL);
        return key + 1L;
    }

    private static float length(float x, float y, float z) {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    private static final class LongIntMap {
        private long[] keys;
        private int[] values;
        private int mask;
        private int size;
        private int resizeAt;

        LongIntMap(int expectedSize) {
            int capacity = 16;
            int target = Math.max(16, expectedSize);
            while (capacity < target && capacity < (1 << 25)) {
                capacity <<= 1;
            }
            keys = new long[capacity];
            values = new int[capacity];
            mask = capacity - 1;
            resizeAt = Math.round(capacity * 0.68f);
        }

        int get(long key) {
            int slot = slot(key);
            while (true) {
                long stored = keys[slot];
                if (stored == 0L) {
                    return -1;
                }
                if (stored == key) {
                    return values[slot];
                }
                slot = (slot + 1) & mask;
            }
        }

        void put(long key, int value) {
            if (size >= resizeAt) {
                resize();
            }
            int slot = slot(key);
            while (keys[slot] != 0L && keys[slot] != key) {
                slot = (slot + 1) & mask;
            }
            if (keys[slot] == 0L) {
                keys[slot] = key;
                values[slot] = value;
                size++;
            } else {
                values[slot] = value;
            }
        }

        private int slot(long key) {
            long mixed = key;
            mixed ^= mixed >>> 33;
            mixed *= 0xff51afd7ed558ccdL;
            mixed ^= mixed >>> 33;
            mixed *= 0xc4ceb9fe1a85ec53L;
            mixed ^= mixed >>> 33;
            return (int) mixed & mask;
        }

        private void resize() {
            long[] oldKeys = keys;
            int[] oldValues = values;
            int newCapacity = oldKeys.length << 1;
            if (newCapacity <= 0 || newCapacity > (1 << 26)) {
                resizeAt = Integer.MAX_VALUE;
                return;
            }
            keys = new long[newCapacity];
            values = new int[newCapacity];
            mask = newCapacity - 1;
            size = 0;
            resizeAt = Math.round(newCapacity * 0.68f);
            for (int i = 0; i < oldKeys.length; i++) {
                if (oldKeys[i] != 0L) {
                    put(oldKeys[i], oldValues[i]);
                }
            }
        }
    }

    private static final class Bounds {
        final float minX;
        final float minY;
        final float minZ;
        final float maxX;
        final float maxY;
        final float maxZ;

        Bounds(
                float minX,
                float minY,
                float minZ,
                float maxX,
                float maxY,
                float maxZ
        ) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }

        static Bounds from(float[] positions) {
            float minX = Float.POSITIVE_INFINITY;
            float minY = Float.POSITIVE_INFINITY;
            float minZ = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY;
            float maxY = Float.NEGATIVE_INFINITY;
            float maxZ = Float.NEGATIVE_INFINITY;
            for (int index = 0; index < positions.length; index += 3) {
                minX = Math.min(minX, positions[index]);
                minY = Math.min(minY, positions[index + 1]);
                minZ = Math.min(minZ, positions[index + 2]);
                maxX = Math.max(maxX, positions[index]);
                maxY = Math.max(maxY, positions[index + 1]);
                maxZ = Math.max(maxZ, positions[index + 2]);
            }
            return new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }
}
