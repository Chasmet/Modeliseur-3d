package com.chasmet.modeliseur3d.model;

/** Regression tests for component-aware V7.3 structural hull. */
public final class ContinuousVisualHullSelfTest {
    public static void main(String[] args) {
        roundedBodyKeepsSilhouettes();
        quadrupedLegsRemainFour3DComponents();
        temporaryLegMergeIsReopenedBeforeHull();
        characterLegGapStaysEmpty();
        compositeDriverDoesNotFillVehicleDepth();
        adaptiveRecoveryCannotBridgeRealComponentGaps();
        System.out.println("ContinuousVisualHullSelfTest component-aware: OK");
    }

    private static void roundedBodyKeepsSilhouettes() {
        int w = 25, h = 32, d = 21;
        boolean[][] m = masks(w, h, d);
        fill(m[0], w, 5, 3, 19, 28);
        mirror(m[2], w, 5, 3, 19, 28);
        fill(m[1], d, 4, 3, 16, 28);
        mirror(m[3], d, 4, 3, 16, 28);
        ContinuousVisualHull.Result r = ContinuousVisualHull.build(
                confidence(m), m, w, h, d, false, SubjectCategory.CHARACTER
        );
        check(r.getDensity()[index(12, 16, 10, w, d)] > 0.90f,
                "centre body too weak");
        check(r.getDensity()[index(5, 16, 4, w, d)] < 0.50f,
                "rectangular artificial corner survived");
        check(r.getRoundedVoxels() > 0, "no structural rounding measured");
        check(r.getFractionalSamples() > 0, "continuous field lost sub-pixel values");
        check(r.getSilhouetteScore() >= 0.90, "silhouette score regressed");
    }

    private static void quadrupedLegsRemainFour3DComponents() {
        int w = 32, h = 42, d = 28;
        boolean[][] m = masks(w, h, d);
        fill(m[0], w, 6, 5, 25, 22);
        mirror(m[2], w, 6, 5, 25, 22);
        fill(m[1], d, 4, 5, 23, 22);
        mirror(m[3], d, 4, 5, 23, 22);
        fill(m[0], w, 8, 23, 11, 38);
        fill(m[0], w, 20, 23, 23, 38);
        mirror(m[2], w, 8, 23, 11, 38);
        mirror(m[2], w, 20, 23, 23, 38);
        fill(m[1], d, 6, 23, 9, 38);
        fill(m[1], d, 18, 23, 21, 38);
        mirror(m[3], d, 6, 23, 9, 38);
        mirror(m[3], d, 18, 23, 21, 38);
        ContinuousVisualHull.Result r = ContinuousVisualHull.build(
                confidence(m), m, w, h, d, false, SubjectCategory.ANIMAL
        );
        boolean[] volume = r.getOccupancy();
        check(componentCount(volume, w, d, 30) == 4,
                "horse legs are still merged/extruded instead of four 3D parts");
        check(!volume[index(9, 30, 14, w, d)],
                "front leg was extruded through the side-view gap");
        check(!volume[index(16, 30, 7, w, d)],
                "side leg was extruded through the front-view gap");
        check(r.getSilhouetteScore() >= 0.90,
                "four-leg separation damaged silhouettes");
    }

    private static void temporaryLegMergeIsReopenedBeforeHull() {
        int w = 40, h = 48, d = 50;
        boolean[][] m = masks(w, h, d);
        fill(m[0], w, 10, 8, 29, 28);
        mirror(m[2], w, 10, 8, 29, 28);
        fill(m[1], d, 8, 8, 41, 28);
        mirror(m[3], d, 8, 8, 41, 28);

        fill(m[0], w, 11, 29, 15, 43);
        fill(m[0], w, 24, 29, 28, 43);
        mirror(m[2], w, 11, 29, 15, 43);
        mirror(m[2], w, 24, 29, 28, 43);
        fill(m[1], d, 10, 29, 15, 43);
        fill(m[1], d, 34, 29, 39, 43);
        mirror(m[3], d, 10, 29, 15, 43);
        mirror(m[3], d, 34, 29, 39, 43);

        // Défaut vu sur la vidéo : deux lignes de segmentation soudent les membres.
        fill(m[0], w, 11, 35, 28, 36);
        mirror(m[2], w, 11, 35, 28, 36);
        fill(m[1], d, 10, 35, 39, 36);
        mirror(m[3], d, 10, 35, 39, 36);

        ContinuousVisualHull.Result r = ContinuousVisualHull.build(
                confidence(m), m, w, h, d, true, SubjectCategory.ANIMAL
        );
        check(componentCount(r.getOccupancy(), w, d, 35) >= 4,
                "temporary silhouette merge still creates one 3D leg wall");
        check(!r.getOccupancy()[index(20, 35, 25, w, d)],
                "vertical component tracking did not reopen the central leg void");
    }

    private static void characterLegGapStaysEmpty() {
        int w = 32, h = 42, d = 24;
        boolean[][] m = masks(w, h, d);
        fill(m[0], w, 10, 4, 21, 24);
        mirror(m[2], w, 10, 4, 21, 24);
        fill(m[0], w, 10, 25, 14, 38);
        fill(m[0], w, 17, 25, 21, 38);
        mirror(m[2], w, 10, 25, 14, 38);
        mirror(m[2], w, 17, 25, 21, 38);
        fill(m[1], d, 5, 4, 18, 38);
        mirror(m[3], d, 5, 4, 18, 38);
        ContinuousVisualHull.Result r = ContinuousVisualHull.build(
                confidence(m), m, w, h, d, false, SubjectCategory.CHARACTER
        );
        check(depthCount(r.getOccupancy(), w, d, 15, 31) == 0,
                "space between human legs was filled");
        check(depthCount(r.getOccupancy(), w, d, 16, 31) == 0,
                "space between human legs was filled");
        int leg = depthCount(r.getOccupancy(), w, d, 12, 31);
        int torso = depthCount(r.getOccupancy(), w, d, 15, 16);
        check(leg > 0 && leg < torso, "leg still uses torso depth");
    }

    private static void compositeDriverDoesNotFillVehicleDepth() {
        int w = 40, h = 48, d = 32;
        boolean[][] m = masks(w, h, d);
        fill(m[0], w, 15, 4, 24, 23);
        fill(m[0], w, 10, 11, 12, 18);
        fill(m[0], w, 27, 11, 29, 18);
        fill(m[0], w, 3, 24, 10, 43);
        fill(m[0], w, 12, 24, 27, 43);
        fill(m[0], w, 29, 24, 36, 43);
        mirror(m[2], w, 15, 4, 24, 23);
        mirror(m[2], w, 10, 11, 12, 18);
        mirror(m[2], w, 27, 11, 29, 18);
        mirror(m[2], w, 3, 24, 10, 43);
        mirror(m[2], w, 12, 24, 27, 43);
        mirror(m[2], w, 29, 24, 36, 43);
        fill(m[1], d, 4, 4, 27, 43);
        mirror(m[3], d, 4, 4, 27, 43);
        ContinuousVisualHull.Result r = ContinuousVisualHull.build(
                confidence(m), m, w, h, d, false,
                SubjectCategory.COMPOSITE_VEHICLE
        );
        int arm = depthCount(r.getOccupancy(), w, d, 11, 15);
        int chassis = depthCount(r.getOccupancy(), w, d, 20, 34);
        check(r.isComplexShapeMode(), "composite mode flag missing");
        check(arm > 0 && arm < chassis * 0.75f,
                "driver arm still occupies vehicle depth");
        check(chassis >= d * 0.55f, "vehicle chassis became a thin sheet");
    }

    private static void adaptiveRecoveryCannotBridgeRealComponentGaps() {
        int w = 32, h = 42, d = 28;
        boolean[][] m = masks(w, h, d);
        fill(m[0], w, 8, 5, 23, 20);
        mirror(m[2], w, 8, 5, 23, 20);
        fill(m[1], d, 5, 5, 22, 20);
        mirror(m[3], d, 5, 5, 22, 20);
        fill(m[0], w, 8, 21, 11, 38);
        fill(m[0], w, 20, 21, 23, 38);
        mirror(m[2], w, 8, 21, 11, 38);
        mirror(m[2], w, 20, 21, 23, 38);
        fill(m[1], d, 6, 21, 9, 38);
        fill(m[1], d, 18, 21, 21, 38);
        mirror(m[3], d, 6, 21, 9, 38);
        mirror(m[3], d, 18, 21, 21, 38);
        ContinuousVisualHull.Result r = ContinuousVisualHull.build(
                confidence(m), m, w, h, d, true, SubjectCategory.ANIMAL
        );
        check(!r.getOccupancy()[index(9, 30, 14, w, d)],
                "adaptive mode bridged the side gap between legs");
        check(componentCount(r.getOccupancy(), w, d, 30) == 4,
                "adaptive mode merged orthogonal components");
    }

    private static boolean[][] masks(int w, int h, int d) {
        return new boolean[][]{
                new boolean[w * h], new boolean[d * h],
                new boolean[w * h], new boolean[d * h]
        };
    }

    private static float[][] confidence(boolean[][] m) {
        float[][] c = new float[4][];
        for (int v = 0; v < 4; v++) {
            c[v] = new float[m[v].length];
            for (int i = 0; i < m[v].length; i++) {
                c[v][i] = m[v][i] ? 1.0f : 0.0f;
            }
        }
        return c;
    }

    private static void fill(boolean[] m, int w, int l, int t, int r, int b) {
        for (int y = t; y <= b; y++) {
            for (int x = l; x <= r; x++) {
                m[y * w + x] = true;
            }
        }
    }

    private static void mirror(boolean[] m, int w, int l, int t, int r, int b) {
        for (int y = t; y <= b; y++) {
            for (int x = l; x <= r; x++) {
                m[y * w + (w - 1 - x)] = true;
            }
        }
    }

    private static int depthCount(boolean[] v, int w, int d, int x, int y) {
        int count = 0;
        int base = (y * w + x) * d;
        for (int z = 0; z < d; z++) {
            if (v[base + z]) count++;
        }
        return count;
    }

    private static int componentCount(boolean[] v, int w, int d, int y) {
        boolean[] seen = new boolean[w * d];
        int[] queue = new int[w * d];
        int components = 0;
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                int start = x * d + z;
                if (seen[start] || !v[index(x, y, z, w, d)]) continue;
                components++;
                int read = 0, write = 0;
                queue[write++] = start;
                seen[start] = true;
                while (read < write) {
                    int p = queue[read++];
                    int px = p / d, pz = p % d;
                    int[] xs = {px + 1, px - 1, px, px};
                    int[] zs = {pz, pz, pz + 1, pz - 1};
                    for (int k = 0; k < 4; k++) {
                        if (xs[k] < 0 || xs[k] >= w || zs[k] < 0 || zs[k] >= d) continue;
                        int q = xs[k] * d + zs[k];
                        if (!seen[q] && v[index(xs[k], y, zs[k], w, d)]) {
                            seen[q] = true;
                            queue[write++] = q;
                        }
                    }
                }
            }
        }
        return components;
    }

    private static int index(int x, int y, int z, int w, int d) {
        return (y * w + x) * d + z;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
