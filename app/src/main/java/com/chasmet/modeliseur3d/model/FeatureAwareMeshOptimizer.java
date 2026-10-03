package com.chasmet.modeliseur3d.model;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Category-aware Taubin smoother that preserves sharp edges and thin parts.
 *
 * <p>Marching tetrahedra deliberately emits duplicated vertices at UV seams.
 * Vertices are therefore welded into geometric nodes before smoothing, while
 * the original UVs and triangle topology are kept intact. Neighbour influence
 * is filtered by the source normal angle so a roof edge, wheel rim, branch or
 * limb boundary cannot be averaged as if it were a soft blob.</p>
 */
public final class FeatureAwareMeshOptimizer {
    private FeatureAwareMeshOptimizer() {
    }

    public static MeshData optimize(MeshData source, int iterations) {
        return optimize(source, iterations, inferCategory(source));
    }

    static SubjectCategory inferCategory(MeshData source) {
        if (source == null || source.getVertexCount() == 0) {
            return SubjectCategory.CHARACTER;
        }
        Bounds bounds = Bounds.from(source.getPositions());
        float width = Math.max(1.0e-6f, bounds.maxX - bounds.minX);
        float height = Math.max(1.0e-6f, bounds.maxY - bounds.minY);
        float depth = Math.max(1.0e-6f, bounds.maxZ - bounds.minZ);
        float horizontal = Math.max(width, depth);
        float axisAligned = axisAlignedNormalRatio(source.getNormals());

        if (axisAligned >= 0.70f) {
            return SubjectCategory.ARCHITECTURE_OBJECT;
        }
        if (horizontal >= height * 1.08f) {
            return axisAligned >= 0.48f
                    ? SubjectCategory.COMPOSITE_VEHICLE
                    : SubjectCategory.ANIMAL;
        }
        if (height >= horizontal * 1.55f && axisAligned < 0.38f) {
            return SubjectCategory.PLANT;
        }
        return SubjectCategory.CHARACTER;
    }

    private static float axisAlignedNormalRatio(float[] normals) {
        int aligned = 0;
        int valid = 0;
        for (int p = 0; p + 2 < normals.length; p += 3) {
            float nx = normals[p];
            float ny = normals[p + 1];
            float nz = normals[p + 2];
            float length = length(nx, ny, nz);
            if (length <= 1.0e-8f) {
                continue;
            }
            valid++;
            float maximum = Math.max(Math.abs(nx), Math.max(Math.abs(ny), Math.abs(nz)));
            if (maximum / length >= 0.94f) {
                aligned++;
            }
        }
        return valid == 0 ? 0.0f : aligned / (float) valid;
    }

    public static MeshData optimize(
            MeshData source,
            int iterations,
            SubjectCategory category
    ) {
        if (source == null) {
            throw new IllegalArgumentException("Maillage absent");
        }
        int vertexCount = source.getVertexCount();
        if (vertexCount < 12 || iterations <= 0) {
            return source;
        }
        if (category == null || category == SubjectCategory.AUTO) {
            category = SubjectCategory.CHARACTER;
        }

        float[] sourcePositions = source.getPositions();
        float[] sourceNormals = source.getNormals();
        Bounds bounds = Bounds.from(sourcePositions);
        float maximumSize = Math.max(
                bounds.maxX - bounds.minX,
                Math.max(bounds.maxY - bounds.minY, bounds.maxZ - bounds.minZ)
        );
        float quantum = Math.max(1.0e-6f, maximumSize / 8192.0f);

        Map<PositionKey, Integer> nodeByPosition = new HashMap<>(vertexCount * 2);
        int[] vertexNode = new int[vertexCount];
        float[] nodeX = new float[vertexCount];
        float[] nodeY = new float[vertexCount];
        float[] nodeZ = new float[vertexCount];
        float[] nodeNx = new float[vertexCount];
        float[] nodeNy = new float[vertexCount];
        float[] nodeNz = new float[vertexCount];
        int[] nodeSamples = new int[vertexCount];
        int nodeCount = 0;

        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int p = vertex * 3;
            PositionKey key = PositionKey.from(
                    sourcePositions[p],
                    sourcePositions[p + 1],
                    sourcePositions[p + 2],
                    bounds,
                    quantum
            );
            Integer existing = nodeByPosition.get(key);
            int node;
            if (existing == null) {
                node = nodeCount++;
                nodeByPosition.put(key, node);
            } else {
                node = existing;
            }
            vertexNode[vertex] = node;
            nodeX[node] += sourcePositions[p];
            nodeY[node] += sourcePositions[p + 1];
            nodeZ[node] += sourcePositions[p + 2];
            nodeNx[node] += sourceNormals[p];
            nodeNy[node] += sourceNormals[p + 1];
            nodeNz[node] += sourceNormals[p + 2];
            nodeSamples[node]++;
        }

        for (int node = 0; node < nodeCount; node++) {
            float samples = Math.max(1, nodeSamples[node]);
            nodeX[node] /= samples;
            nodeY[node] /= samples;
            nodeZ[node] /= samples;
            normalizeNodeNormal(nodeNx, nodeNy, nodeNz, node);
        }

        Policy policy = Policy.forCategory(category);
        int passes = Math.max(1, Math.min(3, iterations));
        int[] indices = source.getIndices();
        for (int pass = 0; pass < passes; pass++) {
            smoothPass(
                    nodeX, nodeY, nodeZ,
                    nodeNx, nodeNy, nodeNz,
                    nodeCount, vertexNode, indices,
                    bounds, policy, policy.lambda
            );
            smoothPass(
                    nodeX, nodeY, nodeZ,
                    nodeNx, nodeNy, nodeNz,
                    nodeCount, vertexNode, indices,
                    bounds, policy, policy.mu
            );
        }

        float[] positions = Arrays.copyOf(sourcePositions, sourcePositions.length);
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int node = vertexNode[vertex];
            int p = vertex * 3;
            positions[p] = nodeX[node];
            positions[p + 1] = nodeY[node];
            positions[p + 2] = nodeZ[node];
        }

        float[] normals = rebuildNormals(
                positions,
                indices,
                vertexNode,
                nodeCount,
                sourceNormals
        );
        return new MeshData(
                positions,
                normals,
                Arrays.copyOf(source.getTexCoords(), source.getTexCoords().length),
                Arrays.copyOf(indices, indices.length)
        );
    }

    private static void smoothPass(
            float[] x,
            float[] y,
            float[] z,
            float[] nx,
            float[] ny,
            float[] nz,
            int nodeCount,
            int[] vertexNode,
            int[] indices,
            Bounds bounds,
            Policy policy,
            float amount
    ) {
        float[] sumX = new float[nodeCount];
        float[] sumY = new float[nodeCount];
        float[] sumZ = new float[nodeCount];
        float[] weight = new float[nodeCount];

        for (int triangle = 0; triangle + 2 < indices.length; triangle += 3) {
            int a = vertexNode[indices[triangle]];
            int b = vertexNode[indices[triangle + 1]];
            int c = vertexNode[indices[triangle + 2]];
            addNeighbour(a, b, x, y, z, nx, ny, nz, sumX, sumY, sumZ, weight, policy);
            addNeighbour(b, a, x, y, z, nx, ny, nz, sumX, sumY, sumZ, weight, policy);
            addNeighbour(b, c, x, y, z, nx, ny, nz, sumX, sumY, sumZ, weight, policy);
            addNeighbour(c, b, x, y, z, nx, ny, nz, sumX, sumY, sumZ, weight, policy);
            addNeighbour(c, a, x, y, z, nx, ny, nz, sumX, sumY, sumZ, weight, policy);
            addNeighbour(a, c, x, y, z, nx, ny, nz, sumX, sumY, sumZ, weight, policy);
        }

        float[] nextX = Arrays.copyOf(x, nodeCount);
        float[] nextY = Arrays.copyOf(y, nodeCount);
        float[] nextZ = Arrays.copyOf(z, nodeCount);
        float heightSpan = Math.max(1.0e-6f, bounds.maxY - bounds.minY);
        for (int node = 0; node < nodeCount; node++) {
            if (weight[node] <= 1.0e-6f) {
                continue;
            }
            float averageX = sumX[node] / weight[node];
            float averageY = sumY[node] / weight[node];
            float averageZ = sumZ[node] / weight[node];
            float normalizedY = clamp01((y[node] - bounds.minY) / heightSpan);
            float mobility = policy.mobility(normalizedY);
            float localAmount = amount * mobility;

            float dx = averageX - x[node];
            float dy = averageY - y[node];
            float dz = averageZ - z[node];
            float normalMotion = dx * nx[node] + dy * ny[node] + dz * nz[node];
            float tangentX = dx - normalMotion * nx[node];
            float tangentY = dy - normalMotion * ny[node];
            float tangentZ = dz - normalMotion * nz[node];

            float normalScale = policy.normalMotion;
            nextX[node] = x[node] + localAmount
                    * (tangentX + normalMotion * nx[node] * normalScale);
            nextY[node] = y[node] + localAmount
                    * (tangentY + normalMotion * ny[node] * normalScale);
            nextZ[node] = z[node] + localAmount
                    * (tangentZ + normalMotion * nz[node] * normalScale);
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
            float[] nx,
            float[] ny,
            float[] nz,
            float[] sumX,
            float[] sumY,
            float[] sumZ,
            float[] weight,
            Policy policy
    ) {
        if (target == neighbour) {
            return;
        }
        float dot = nx[target] * nx[neighbour]
                + ny[target] * ny[neighbour]
                + nz[target] * nz[neighbour];
        if (dot <= policy.edgeDotThreshold) {
            return;
        }
        float angularWeight = clamp01(
                (dot - policy.edgeDotThreshold)
                        / Math.max(1.0e-5f, 1.0f - policy.edgeDotThreshold)
        );
        angularWeight = 0.10f + 0.90f * angularWeight * angularWeight;
        float dx = x[neighbour] - x[target];
        float dy = y[neighbour] - y[target];
        float dz = z[neighbour] - z[target];
        float distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float distanceWeight = 1.0f / Math.max(1.0e-4f, distance);
        float w = angularWeight * distanceWeight;
        sumX[target] += x[neighbour] * w;
        sumY[target] += y[neighbour] * w;
        sumZ[target] += z[neighbour] * w;
        weight[target] += w;
    }

    private static float[] rebuildNormals(
            float[] positions,
            int[] indices,
            int[] vertexNode,
            int nodeCount,
            float[] fallback
    ) {
        float[] nodeNx = new float[nodeCount];
        float[] nodeNy = new float[nodeCount];
        float[] nodeNz = new float[nodeCount];
        for (int triangle = 0; triangle + 2 < indices.length; triangle += 3) {
            int va = indices[triangle];
            int vb = indices[triangle + 1];
            int vc = indices[triangle + 2];
            int pa = va * 3;
            int pb = vb * 3;
            int pc = vc * 3;
            float abX = positions[pb] - positions[pa];
            float abY = positions[pb + 1] - positions[pa + 1];
            float abZ = positions[pb + 2] - positions[pa + 2];
            float acX = positions[pc] - positions[pa];
            float acY = positions[pc + 1] - positions[pa + 1];
            float acZ = positions[pc + 2] - positions[pa + 2];
            float faceX = abY * acZ - abZ * acY;
            float faceY = abZ * acX - abX * acZ;
            float faceZ = abX * acY - abY * acX;
            float length = length(faceX, faceY, faceZ);
            if (length <= 1.0e-10f) {
                continue;
            }
            faceX /= length;
            faceY /= length;
            faceZ /= length;
            int a = vertexNode[va];
            int b = vertexNode[vb];
            int c = vertexNode[vc];
            nodeNx[a] += faceX;
            nodeNy[a] += faceY;
            nodeNz[a] += faceZ;
            nodeNx[b] += faceX;
            nodeNy[b] += faceY;
            nodeNz[b] += faceZ;
            nodeNx[c] += faceX;
            nodeNy[c] += faceY;
            nodeNz[c] += faceZ;
        }

        float[] normals = new float[positions.length];
        for (int vertex = 0; vertex < vertexNode.length; vertex++) {
            int node = vertexNode[vertex];
            int p = vertex * 3;
            float length = length(nodeNx[node], nodeNy[node], nodeNz[node]);
            if (length <= 1.0e-8f) {
                normals[p] = fallback[p];
                normals[p + 1] = fallback[p + 1];
                normals[p + 2] = fallback[p + 2];
            } else {
                normals[p] = nodeNx[node] / length;
                normals[p + 1] = nodeNy[node] / length;
                normals[p + 2] = nodeNz[node] / length;
            }
        }
        return normals;
    }

    private static void normalizeNodeNormal(
            float[] nx,
            float[] ny,
            float[] nz,
            int node
    ) {
        float length = length(nx[node], ny[node], nz[node]);
        if (length <= 1.0e-8f) {
            nx[node] = 0.0f;
            ny[node] = 1.0f;
            nz[node] = 0.0f;
        } else {
            nx[node] /= length;
            ny[node] /= length;
            nz[node] /= length;
        }
    }

    private static float length(float x, float y, float z) {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private static final class Policy {
        final float lambda;
        final float mu;
        final float edgeDotThreshold;
        final float normalMotion;
        final SubjectCategory category;

        Policy(
                float lambda,
                float mu,
                float edgeDotThreshold,
                float normalMotion,
                SubjectCategory category
        ) {
            this.lambda = lambda;
            this.mu = mu;
            this.edgeDotThreshold = edgeDotThreshold;
            this.normalMotion = normalMotion;
            this.category = category;
        }

        float mobility(float normalizedY) {
            if (category == SubjectCategory.COMPOSITE_VEHICLE) {
                return normalizedY < 0.48f ? 0.22f : 0.78f;
            }
            if (category == SubjectCategory.ANIMAL) {
                return normalizedY < 0.38f ? 0.42f : 0.78f;
            }
            if (category == SubjectCategory.PLANT) {
                return normalizedY < 0.48f ? 0.38f : 0.72f;
            }
            if (category == SubjectCategory.ARCHITECTURE_OBJECT) {
                return 0.24f;
            }
            return normalizedY < 0.36f ? 0.58f : 0.82f;
        }

        static Policy forCategory(SubjectCategory category) {
            if (category == SubjectCategory.COMPOSITE_VEHICLE) {
                return new Policy(0.145f, -0.151f, 0.86f, 0.34f, category);
            }
            if (category == SubjectCategory.ARCHITECTURE_OBJECT) {
                return new Policy(0.075f, -0.078f, 0.94f, 0.18f, category);
            }
            if (category == SubjectCategory.ANIMAL) {
                return new Policy(0.135f, -0.141f, 0.80f, 0.40f, category);
            }
            if (category == SubjectCategory.PLANT) {
                return new Policy(0.110f, -0.115f, 0.84f, 0.30f, category);
            }
            return new Policy(0.155f, -0.162f, 0.76f, 0.46f, category);
        }
    }

    private static final class Bounds {
        final float minX;
        final float minY;
        final float minZ;
        final float maxX;
        final float maxY;
        final float maxZ;

        Bounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
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
            for (int p = 0; p < positions.length; p += 3) {
                minX = Math.min(minX, positions[p]);
                minY = Math.min(minY, positions[p + 1]);
                minZ = Math.min(minZ, positions[p + 2]);
                maxX = Math.max(maxX, positions[p]);
                maxY = Math.max(maxY, positions[p + 1]);
                maxZ = Math.max(maxZ, positions[p + 2]);
            }
            return new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }

    private static final class PositionKey {
        final int x;
        final int y;
        final int z;

        PositionKey(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        static PositionKey from(
                float x,
                float y,
                float z,
                Bounds bounds,
                float quantum
        ) {
            return new PositionKey(
                    Math.round((x - bounds.minX) / quantum),
                    Math.round((y - bounds.minY) / quantum),
                    Math.round((z - bounds.minZ) / quantum)
            );
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof PositionKey)) {
                return false;
            }
            PositionKey key = (PositionKey) other;
            return x == key.x && y == key.y && z == key.z;
        }

        @Override
        public int hashCode() {
            int result = x;
            result = 31 * result + y;
            result = 31 * result + z;
            return result;
        }
    }
}
