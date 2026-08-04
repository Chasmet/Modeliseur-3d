package com.chasmet.modeliseur3d.model;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Simplification dédiée à l'export 3D rapide.
 *
 * L'optimiseur historique teste 29 grilles successives. Cette variante fait
 * une recherche binaire en huit passes maximum et conserve les coutures UV et
 * les régions de normales. Le moteur et l'export 2.5D ne sont pas modifiés.
 */
public final class FastMobileMeshOptimizer {
    private static final int MINIMUM_RESOLUTION = 2;
    private static final int MAXIMUM_RESOLUTION = 224;
    private static final int SEARCH_PASSES = 8;

    private FastMobileMeshOptimizer() {
    }

    public static MeshData simplify(MeshData source, int targetTriangles) {
        if (source == null || source.getTriangleCount() <= 0) {
            throw new IllegalArgumentException("Maillage 3D absent ou vide");
        }
        int target = Math.max(1, Math.min(
                source.getTriangleCount(),
                targetTriangles
        ));
        if (source.getTriangleCount() <= target) {
            return source;
        }

        int low = MINIMUM_RESOLUTION;
        int high = MAXIMUM_RESOLUTION;
        MeshData bestUnderBudget = null;
        MeshData closestAboveBudget = source;
        int previousResolution = -1;

        for (int pass = 0; pass < SEARCH_PASSES && low <= high; pass++) {
            int resolution = low + (high - low) / 2;
            if (resolution == previousResolution) {
                break;
            }
            previousResolution = resolution;
            MeshData candidate = cluster(source, resolution);
            if (candidate == null || candidate.getTriangleCount() <= 0) {
                high = resolution - 1;
                continue;
            }
            int triangles = candidate.getTriangleCount();
            if (triangles <= target) {
                if (bestUnderBudget == null
                        || triangles > bestUnderBudget.getTriangleCount()) {
                    bestUnderBudget = candidate;
                }
                low = resolution + 1;
            } else {
                if (triangles < closestAboveBudget.getTriangleCount()) {
                    closestAboveBudget = candidate;
                }
                high = resolution - 1;
            }
        }

        if (bestUnderBudget != null) {
            return bestUnderBudget;
        }
        return sampleTriangles(closestAboveBudget, target);
    }

    private static MeshData cluster(MeshData source, int resolution) {
        float[] positions = source.getPositions();
        float[] normals = source.getNormals();
        float[] uvs = source.getTexCoords();
        int[] sourceIndices = source.getIndices();
        int vertexCount = source.getVertexCount();
        Bounds bounds = Bounds.from(positions);

        Map<Long, Integer> clusterByKey = new HashMap<>(
                Math.max(32, vertexCount)
        );
        Cluster[] clusters = new Cluster[vertexCount];
        int[] vertexCluster = new int[vertexCount];
        int clusterCount = 0;

        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int positionOffset = vertex * 3;
            int uvOffset = vertex * 2;
            int qx = quantize(
                    positions[positionOffset],
                    bounds.minX,
                    bounds.maxX,
                    resolution
            );
            int qy = quantize(
                    positions[positionOffset + 1],
                    bounds.minY,
                    bounds.maxY,
                    resolution
            );
            int qz = quantize(
                    positions[positionOffset + 2],
                    bounds.minZ,
                    bounds.maxZ,
                    resolution
            );
            int uvRegion = uvRegion(uvs[uvOffset], uvs[uvOffset + 1]);
            int normalRegion = normalRegion(
                    normals[positionOffset],
                    normals[positionOffset + 1],
                    normals[positionOffset + 2]
            );
            long key = pack(qx, qy, qz, uvRegion, normalRegion);
            Integer existing = clusterByKey.get(key);
            int clusterIndex;
            if (existing == null) {
                clusterIndex = clusterCount++;
                clusterByKey.put(key, clusterIndex);
                clusters[clusterIndex] = new Cluster();
            } else {
                clusterIndex = existing;
            }
            vertexCluster[vertex] = clusterIndex;
            clusters[clusterIndex].add(
                    positions[positionOffset],
                    positions[positionOffset + 1],
                    positions[positionOffset + 2],
                    normals[positionOffset],
                    normals[positionOffset + 1],
                    normals[positionOffset + 2],
                    uvs[uvOffset],
                    uvs[uvOffset + 1]
            );
        }

        int[] remappedTriangles = new int[sourceIndices.length];
        int remappedCount = 0;
        boolean[] used = new boolean[clusterCount];
        for (int triangle = 0; triangle + 2 < sourceIndices.length; triangle += 3) {
            int sourceA = sourceIndices[triangle];
            int sourceB = sourceIndices[triangle + 1];
            int sourceC = sourceIndices[triangle + 2];
            if (!validVertex(sourceA, vertexCount)
                    || !validVertex(sourceB, vertexCount)
                    || !validVertex(sourceC, vertexCount)) {
                continue;
            }
            int a = vertexCluster[sourceA];
            int b = vertexCluster[sourceB];
            int c = vertexCluster[sourceC];
            if (a == b || b == c || a == c) {
                continue;
            }
            Cluster ca = clusters[a];
            Cluster cb = clusters[b];
            Cluster cc = clusters[c];
            if (triangleAreaSquared(ca, cb, cc) < 1.0e-12f) {
                continue;
            }
            remappedTriangles[remappedCount++] = a;
            remappedTriangles[remappedCount++] = b;
            remappedTriangles[remappedCount++] = c;
            used[a] = true;
            used[b] = true;
            used[c] = true;
        }
        if (remappedCount < 3) {
            return null;
        }

        int[] compact = new int[clusterCount];
        Arrays.fill(compact, -1);
        int outputVertexCount = 0;
        for (int index = 0; index < clusterCount; index++) {
            if (used[index]) {
                compact[index] = outputVertexCount++;
            }
        }
        float[] outputPositions = new float[outputVertexCount * 3];
        float[] outputNormals = new float[outputVertexCount * 3];
        float[] outputUvs = new float[outputVertexCount * 2];
        for (int index = 0; index < clusterCount; index++) {
            int target = compact[index];
            if (target >= 0) {
                clusters[index].write(
                        outputPositions,
                        outputNormals,
                        outputUvs,
                        target
                );
            }
        }

        int[] outputIndices = new int[remappedCount];
        for (int index = 0; index < remappedCount; index++) {
            outputIndices[index] = compact[remappedTriangles[index]];
        }
        return new MeshData(
                outputPositions,
                outputNormals,
                outputUvs,
                outputIndices
        );
    }

    private static MeshData sampleTriangles(MeshData source, int target) {
        int total = source.getTriangleCount();
        int requested = Math.max(1, Math.min(total, target));
        float[] sourcePositions = source.getPositions();
        float[] sourceNormals = source.getNormals();
        float[] sourceUvs = source.getTexCoords();
        int[] sourceIndices = source.getIndices();
        int sourceVertexCount = source.getVertexCount();

        int[] remap = new int[sourceVertexCount];
        Arrays.fill(remap, -1);
        float[] positions = new float[Math.min(sourceVertexCount, requested * 3) * 3];
        float[] normals = new float[positions.length];
        float[] uvs = new float[positions.length / 3 * 2];
        int[] indices = new int[requested * 3];
        int outputVertices = 0;
        int outputIndices = 0;

        for (int sample = 0; sample < requested; sample++) {
            int triangle = (int) Math.min(
                    total - 1L,
                    ((2L * sample + 1L) * total) / (2L * requested)
            );
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
            int[] triangleVertices = {a, b, c};
            for (int sourceVertex : triangleVertices) {
                int mapped = remap[sourceVertex];
                if (mapped < 0) {
                    mapped = outputVertices++;
                    remap[sourceVertex] = mapped;
                    copyVertex(
                            sourceVertex,
                            mapped,
                            sourcePositions,
                            sourceNormals,
                            sourceUvs,
                            positions,
                            normals,
                            uvs
                    );
                }
                indices[outputIndices++] = mapped;
            }
        }
        if (outputIndices < 3) {
            throw new IllegalArgumentException("Aucun triangle mobile valide");
        }
        return new MeshData(
                Arrays.copyOf(positions, outputVertices * 3),
                Arrays.copyOf(normals, outputVertices * 3),
                Arrays.copyOf(uvs, outputVertices * 2),
                Arrays.copyOf(indices, outputIndices)
        );
    }

    private static int quantize(
            float value,
            float minimum,
            float maximum,
            int resolution
    ) {
        float range = Math.max(1.0e-8f, maximum - minimum);
        float normalized = Math.max(0.0f, Math.min(
                1.0f,
                (value - minimum) / range
        ));
        return Math.max(0, Math.min(
                1023,
                Math.round(normalized * Math.max(1, resolution - 1))
        ));
    }

    private static int uvRegion(float u, float v) {
        int horizontal = Math.max(0, Math.min(
                7,
                (int) Math.floor(Math.max(0.0f, Math.min(0.9999f, u)) * 8.0f)
        ));
        int vertical = v >= 0.5f ? 1 : 0;
        return horizontal * 2 + vertical;
    }

    private static int normalRegion(float x, float y, float z) {
        float ax = Math.abs(x);
        float ay = Math.abs(y);
        float az = Math.abs(z);
        int dominant = ax >= ay && ax >= az ? 0 : (ay >= az ? 1 : 2);
        int signs = (x >= 0.0f ? 1 : 0)
                | (y >= 0.0f ? 2 : 0)
                | (z >= 0.0f ? 4 : 0);
        return dominant * 8 + signs;
    }

    private static long pack(
            int x,
            int y,
            int z,
            int uvRegion,
            int normalRegion
    ) {
        return ((long) x & 0x3FFL)
                | (((long) y & 0x3FFL) << 10)
                | (((long) z & 0x3FFL) << 20)
                | (((long) uvRegion & 0x1FL) << 30)
                | (((long) normalRegion & 0x1FL) << 35);
    }

    private static float triangleAreaSquared(Cluster a, Cluster b, Cluster c) {
        float abx = b.x() - a.x();
        float aby = b.y() - a.y();
        float abz = b.z() - a.z();
        float acx = c.x() - a.x();
        float acy = c.y() - a.y();
        float acz = c.z() - a.z();
        float nx = aby * acz - abz * acy;
        float ny = abz * acx - abx * acz;
        float nz = abx * acy - aby * acx;
        return nx * nx + ny * ny + nz * nz;
    }

    private static boolean validVertex(int index, int vertexCount) {
        return index >= 0 && index < vertexCount;
    }

    private static void copyVertex(
            int sourceIndex,
            int targetIndex,
            float[] sourcePositions,
            float[] sourceNormals,
            float[] sourceUvs,
            float[] positions,
            float[] normals,
            float[] uvs
    ) {
        int sourcePosition = sourceIndex * 3;
        int targetPosition = targetIndex * 3;
        positions[targetPosition] = sourcePositions[sourcePosition];
        positions[targetPosition + 1] = sourcePositions[sourcePosition + 1];
        positions[targetPosition + 2] = sourcePositions[sourcePosition + 2];
        normals[targetPosition] = sourceNormals[sourcePosition];
        normals[targetPosition + 1] = sourceNormals[sourcePosition + 1];
        normals[targetPosition + 2] = sourceNormals[sourcePosition + 2];
        int sourceUv = sourceIndex * 2;
        int targetUv = targetIndex * 2;
        uvs[targetUv] = sourceUvs[sourceUv];
        uvs[targetUv + 1] = sourceUvs[sourceUv + 1];
    }

    private static final class Cluster {
        float sumX;
        float sumY;
        float sumZ;
        float sumNx;
        float sumNy;
        float sumNz;
        float sumU;
        float sumV;
        int count;

        void add(
                float x,
                float y,
                float z,
                float nx,
                float ny,
                float nz,
                float u,
                float v
        ) {
            sumX += x;
            sumY += y;
            sumZ += z;
            sumNx += nx;
            sumNy += ny;
            sumNz += nz;
            sumU += u;
            sumV += v;
            count++;
        }

        float x() {
            return sumX / Math.max(1, count);
        }

        float y() {
            return sumY / Math.max(1, count);
        }

        float z() {
            return sumZ / Math.max(1, count);
        }

        void write(
                float[] positions,
                float[] normals,
                float[] uvs,
                int targetIndex
        ) {
            int position = targetIndex * 3;
            positions[position] = x();
            positions[position + 1] = y();
            positions[position + 2] = z();
            float nx = sumNx / Math.max(1, count);
            float ny = sumNy / Math.max(1, count);
            float nz = sumNz / Math.max(1, count);
            float length = Math.max(
                    1.0e-8f,
                    (float) Math.sqrt(nx * nx + ny * ny + nz * nz)
            );
            normals[position] = nx / length;
            normals[position + 1] = ny / length;
            normals[position + 2] = nz / length;
            int uv = targetIndex * 2;
            uvs[uv] = sumU / Math.max(1, count);
            uvs[uv + 1] = sumV / Math.max(1, count);
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
