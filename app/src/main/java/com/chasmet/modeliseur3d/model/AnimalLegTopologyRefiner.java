package com.chasmet.modeliseur3d.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * V9 quadruped topology pass.
 *
 * Four orthographic silhouettes can create phantom cross-combinations between
 * front/back leg runs. This pass measures persistent vertical supports in the
 * lower body, keeps the four strongest separated supports and carves only the
 * lower leg volume around those four anchors. The body, neck and head remain
 * untouched.
 */
final class AnimalLegTopologyRefiner {
    private static final float ISO = 0.50f;

    private AnimalLegTopologyRefiner() {
    }

    static Result refine(float[] source, int width, int height, int depth) {
        if (source == null || source.length != width * height * depth
                || width < 32 || height < 48 || depth < 32) {
            return Result.unchanged(source, "V9 quadrupède : volume invalide");
        }

        Bounds b = Bounds.from(source, width, height, depth);
        if (b.occupied < 700 || b.bottom <= b.top) {
            return Result.unchanged(source, "V9 quadrupède : volume trop petit");
        }

        int spanY = Math.max(1, b.bottom - b.top);
        int legTop = b.top + Math.round(spanY * 0.58f);
        int supportTop = b.top + Math.round(spanY * 0.64f);
        int[] support = new int[width * depth];
        int maximum = 0;
        for (int x = b.minX; x <= b.maxX; x++) {
            for (int z = b.minZ; z <= b.maxZ; z++) {
                int count = 0;
                for (int y = supportTop; y <= b.bottom; y++) {
                    if (source[index(x, y, z, width, depth)] >= ISO) {
                        count++;
                    }
                }
                support[x * depth + z] = count;
                maximum = Math.max(maximum, count);
            }
        }
        if (maximum < Math.max(4, spanY / 12)) {
            return Result.unchanged(source, "V9 quadrupède : appuis non détectés");
        }

        int[] smooth = smoothSupport(support, width, depth, b);
        List<Peak> candidates = new ArrayList<>();
        int threshold = Math.max(3, Math.round(maximum * 0.20f));
        for (int x = b.minX + 1; x < b.maxX; x++) {
            for (int z = b.minZ + 1; z < b.maxZ; z++) {
                int value = smooth[x * depth + z];
                if (value < threshold || !localMaximum(smooth, width, depth, x, z, value)) {
                    continue;
                }
                candidates.add(new Peak(x, z, value));
            }
        }
        candidates.sort(Comparator.comparingInt((Peak p) -> p.score).reversed());

        float spanX = Math.max(1.0f, b.maxX - b.minX + 1.0f);
        float spanZ = Math.max(1.0f, b.maxZ - b.minZ + 1.0f);
        float minSeparation = Math.max(3.0f, Math.min(spanX, spanZ) * 0.105f);
        List<Peak> anchors = new ArrayList<>(4);
        for (Peak candidate : candidates) {
            boolean separated = true;
            for (Peak accepted : anchors) {
                if (distance(candidate.x, candidate.z, accepted.x, accepted.z) < minSeparation) {
                    separated = false;
                    break;
                }
            }
            if (separated) {
                anchors.add(candidate);
                if (anchors.size() == 4) {
                    break;
                }
            }
        }
        if (anchors.size() < 4) {
            return Result.unchanged(
                    source,
                    "V9 quadrupède : seulement " + anchors.size() + " appuis fiables"
            );
        }

        float[] out = Arrays.copyOf(source, source.length);
        int changed = 0;
        for (int y = legTop; y <= b.bottom; y++) {
            float p = (y - legTop) / (float) Math.max(1, b.bottom - legTop);
            float radius = Math.max(
                    2.4f,
                    Math.min(spanX, spanZ) * (0.175f - 0.085f * p)
            );
            float radiusSquared = radius * radius;
            for (int x = b.minX; x <= b.maxX; x++) {
                for (int z = b.minZ; z <= b.maxZ; z++) {
                    int voxel = index(x, y, z, width, depth);
                    float value = out[voxel];
                    if (value <= 0.02f) {
                        continue;
                    }
                    float nearest = Float.POSITIVE_INFINITY;
                    for (Peak anchor : anchors) {
                        float dx = x - anchor.x;
                        float dz = z - anchor.z;
                        nearest = Math.min(nearest, dx * dx + dz * dz);
                    }
                    if (nearest > radiusSquared) {
                        float factor = p >= 0.55f ? 0.02f : 0.18f;
                        float refined = value * factor;
                        if (refined < value - 1.0e-6f) {
                            out[voxel] = refined;
                            changed++;
                        }
                    }
                }
            }
        }

        int occupied = countOccupied(out);
        if (changed < Math.max(24, b.occupied / 1500)
                || occupied < Math.round(b.occupied * 0.68f)) {
            return Result.unchanged(source, "V9 quadrupède : garde-fou 4 appuis");
        }
        return new Result(
                out,
                true,
                changed,
                occupied,
                "V9 quadrupède : 4 appuis persistants • " + changed
                        + " voxels fantômes reclassés"
        );
    }

    private static int[] smoothSupport(int[] source, int width, int depth, Bounds b) {
        int[] out = new int[source.length];
        for (int x = b.minX; x <= b.maxX; x++) {
            for (int z = b.minZ; z <= b.maxZ; z++) {
                int sum = 0;
                int weight = 0;
                for (int dx = -2; dx <= 2; dx++) {
                    int xx = x + dx;
                    if (xx < 0 || xx >= width) continue;
                    for (int dz = -2; dz <= 2; dz++) {
                        int zz = z + dz;
                        if (zz < 0 || zz >= depth) continue;
                        int w = 3 - Math.max(Math.abs(dx), Math.abs(dz));
                        sum += source[xx * depth + zz] * w;
                        weight += w;
                    }
                }
                out[x * depth + z] = weight == 0 ? 0 : Math.round(sum / (float) weight);
            }
        }
        return out;
    }

    private static boolean localMaximum(
            int[] map, int width, int depth, int x, int z, int value
    ) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                int xx = x + dx;
                int zz = z + dz;
                if (xx >= 0 && xx < width && zz >= 0 && zz < depth
                        && map[xx * depth + zz] > value) {
                    return false;
                }
            }
        }
        return true;
    }

    private static float distance(float x0, float z0, float x1, float z1) {
        float dx = x0 - x1;
        float dz = z0 - z1;
        return (float) Math.sqrt(dx * dx + dz * dz);
    }

    private static int countOccupied(float[] density) {
        int count = 0;
        for (float value : density) if (value >= ISO) count++;
        return count;
    }

    private static int index(int x, int y, int z, int width, int depth) {
        return (y * width + x) * depth + z;
    }

    static final class Result {
        final float[] density;
        final boolean applied;
        final int changed;
        final int occupied;
        final String summary;

        Result(float[] density, boolean applied, int changed, int occupied, String summary) {
            this.density = density;
            this.applied = applied;
            this.changed = changed;
            this.occupied = occupied;
            this.summary = summary;
        }

        static Result unchanged(float[] density, String summary) {
            return new Result(density, false, 0, countOccupied(density), summary);
        }
    }

    private static final class Peak {
        final int x;
        final int z;
        final int score;
        Peak(int x, int z, int score) {
            this.x = x;
            this.z = z;
            this.score = score;
        }
    }

    private static final class Bounds {
        int minX, maxX, top, bottom, minZ, maxZ, occupied;

        static Bounds from(float[] d, int width, int height, int depth) {
            Bounds b = new Bounds();
            b.minX = width;
            b.maxX = -1;
            b.top = height;
            b.bottom = -1;
            b.minZ = depth;
            b.maxZ = -1;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    for (int z = 0; z < depth; z++) {
                        if (d[index(x, y, z, width, depth)] < ISO) continue;
                        b.occupied++;
                        b.minX = Math.min(b.minX, x);
                        b.maxX = Math.max(b.maxX, x);
                        b.top = Math.min(b.top, y);
                        b.bottom = Math.max(b.bottom, y);
                        b.minZ = Math.min(b.minZ, z);
                        b.maxZ = Math.max(b.maxZ, z);
                    }
                }
            }
            return b;
        }
    }
}
