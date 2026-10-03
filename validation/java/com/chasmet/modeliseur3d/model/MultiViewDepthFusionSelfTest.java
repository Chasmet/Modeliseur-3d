package com.chasmet.modeliseur3d.model;

import java.util.Arrays;

/** Pure-Java regression checks for the component-aware DA3/hull fusion. */
public final class MultiViewDepthFusionSelfTest {
    private static final int WIDTH = 16;
    private static final int HEIGHT = 20;
    private static final int DEPTH = 14;

    private MultiViewDepthFusionSelfTest() {
    }

    public static void main(String[] args) {
        refinesAValidFourViewVolume();
        rejectsFlatNeuralMaps();
        neverCreatesGeometryOutsideTheHull();
        activatesTheCollapseGuard();
        preservesNearerSurfaceDetails();
        usesStrongerDriverThanVehicleCarving();
        preservesAnimalTorsoAndSeparatesLegs();
        keepsArchitectureMoreRigidThanCharacter();
        preservesDisconnectedRayComponents();
        System.out.println("MultiViewDepthFusionSelfTest component-aware: OK");
    }

    private static void refinesAValidFourViewVolume() {
        Fixture fixture = Fixture.gradient();
        MultiViewDepthFusion.Result result = fixture.refine();
        check(result.isApplied(), "Four valid DA3 views must be applied");
        check(result.getValidViews() == 4, "All four neural views must remain valid");
        check(result.getChangedVoxels() > 100, "Depth must alter a measurable surface");
        check(result.getMeanSurfaceInset() > 0.10, "Depth must create real relief");
        check(result.getReason().contains("multi-experts"),
                "Applied depth must identify the multi-expert modeler");
        check(result.getOccupiedVoxels() > fixture.baseOccupied * 0.55,
                "Collapse guard must preserve most of the verified hull");
    }

    private static void rejectsFlatNeuralMaps() {
        Fixture fixture = Fixture.gradient();
        for (float[] view : fixture.depthMaps) {
            Arrays.fill(view, 4.0f);
        }
        MultiViewDepthFusion.Result result = fixture.refine();
        check(!result.isApplied(), "A constant depth map contains no usable relief");
        check(result.getReason().contains("plate ou insuffisante"),
                "Fallback must explain why DA3 was not applied");
        check(result.getDensity() == fixture.baseDensity,
                "Invalid depth must return the original hull without copying it");
    }

    private static void neverCreatesGeometryOutsideTheHull() {
        Fixture fixture = Fixture.gradient();
        float[] refined = fixture.refine().getDensity();
        for (int index = 0; index < refined.length; index++) {
            if (fixture.baseDensity[index] == 0.0f) {
                check(refined[index] == 0.0f,
                        "Neural depth must not invent voxels outside silhouettes");
            }
        }
    }

    private static void activatesTheCollapseGuard() {
        Fixture fixture = Fixture.gradient();
        for (int index = 0; index < fixture.baseDensity.length; index++) {
            if (fixture.baseDensity[index] > 0.0f) {
                fixture.baseDensity[index] = 0.51f;
            }
        }
        MultiViewDepthFusion.Result result = fixture.refine();
        check(result.isApplied(), "Guarded depth must still produce a refinement");
        check(result.isCollapseGuardUsed(),
                "Aggressive carving must activate the anti-collapse guard");
        check(result.getOccupiedVoxels() > 0,
                "The collapse guard must keep verified geometry alive");
    }

    private static void preservesNearerSurfaceDetails() {
        Fixture fixture = Fixture.gradient();
        Arrays.fill(fixture.depthMaps[StylizedFourViewProjector.RIGHT], 3.0f);
        Arrays.fill(fixture.depthMaps[StylizedFourViewProjector.LEFT], 3.0f);
        float[] refined = fixture.refine().getDensity();
        int near = index(4, HEIGHT / 2, 2);
        int far = index(11, HEIGHT / 2, 2);
        check(refined[near] > refined[far] + 0.08f,
                "A nearer DA3 surface must project farther than a distant one");
    }

    private static void usesStrongerDriverThanVehicleCarving() {
        float driver = MultiViewDepthFusion.debugInsetFraction(
                SubjectCategory.COMPOSITE_VEHICLE, 0.30f, 0.48f, 0.42f
        );
        float chassis = MultiViewDepthFusion.debugInsetFraction(
                SubjectCategory.COMPOSITE_VEHICLE, 0.72f, 0.95f, 0.90f
        );
        check(driver > chassis * 2.2f,
                "Driver limbs must be sculpted much more strongly than the chassis");
    }

    private static void preservesAnimalTorsoAndSeparatesLegs() {
        float torso = MultiViewDepthFusion.debugInsetFraction(
                SubjectCategory.ANIMAL, 0.42f, 0.90f, 0.88f
        );
        float legs = MultiViewDepthFusion.debugInsetFraction(
                SubjectCategory.ANIMAL, 0.82f, 0.38f, 0.34f
        );
        check(legs > torso * 2.3f,
                "Quadruped legs must be separated without flattening the torso");
    }

    private static void keepsArchitectureMoreRigidThanCharacter() {
        float rigid = MultiViewDepthFusion.debugInsetFraction(
                SubjectCategory.ARCHITECTURE_OBJECT, 0.50f, 0.90f, 0.90f
        );
        float characterLimb = MultiViewDepthFusion.debugInsetFraction(
                SubjectCategory.CHARACTER, 0.80f, 0.38f, 0.34f
        );
        check(rigid < characterLimb * 0.35f,
                "Rigid buildings must keep planar volume while limbs can be carved");
    }

    private static void preservesDisconnectedRayComponents() {
        final int w = 18;
        final int h = 24;
        final int d = 18;
        float[] base = new float[w * h * d];
        boolean[][] masks = new boolean[][]{
                new boolean[w * h], new boolean[d * h],
                new boolean[w * h], new boolean[d * h]
        };
        float[][] maps = new float[][]{
                new float[w * h], new float[d * h],
                new float[w * h], new float[d * h]
        };
        float[][] confidence = new float[][]{
                new float[w * h], new float[d * h],
                new float[w * h], new float[d * h]
        };

        for (int y = 3; y <= 20; y++) {
            for (int x = 4; x <= 13; x++) {
                masks[0][y * w + x] = true;
                masks[2][y * w + (w - 1 - x)] = true;
            }
            for (int z = 2; z <= 6; z++) {
                masks[1][y * d + z] = true;
                masks[3][y * d + (d - 1 - z)] = true;
            }
            for (int z = 11; z <= 15; z++) {
                masks[1][y * d + z] = true;
                masks[3][y * d + (d - 1 - z)] = true;
            }
        }

        for (int y = 3; y <= 20; y++) {
            for (int x = 4; x <= 13; x++) {
                for (int z = 2; z <= 6; z++) {
                    base[(y * w + x) * d + z] = 1.0f;
                }
                for (int z = 11; z <= 15; z++) {
                    base[(y * w + x) * d + z] = 1.0f;
                }
            }
        }

        int[] widths = {w, d, w, d};
        for (int view = 0; view < 4; view++) {
            int vw = widths[view];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < vw; x++) {
                    int i = y * vw + x;
                    if (masks[view][i]) {
                        maps[view][i] = x * 0.12f + y * 0.01f + view * 0.03f;
                        confidence[view][i] = 0.85f;
                    }
                }
            }
        }

        MultiViewDepthFusion.Result result = MultiViewDepthFusion.refine(
                base, masks, maps, confidence, w, h, d, SubjectCategory.ANIMAL
        );
        check(result.isApplied(), "Component-aware DA3 refinement was not applied");
        check(result.getReason().contains("composantes"),
                "Component-aware backend is not reported");
        float[] refined = result.getDensity();
        for (int y = 3; y <= 20; y++) {
            for (int x = 4; x <= 13; x++) {
                for (int z = 7; z <= 10; z++) {
                    check(refined[(y * w + x) * d + z] == 0.0f,
                            "DA3 filled a true gap between disconnected components");
                }
            }
        }
    }

    private static int index(int x, int y, int z) {
        return (y * WIDTH + x) * DEPTH + z;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Fixture {
        final float[] baseDensity;
        final boolean[][] masks;
        final float[][] depthMaps;
        final float[][] confidence;
        final int baseOccupied;

        Fixture(float[] baseDensity, boolean[][] masks, float[][] depthMaps,
                float[][] confidence, int baseOccupied) {
            this.baseDensity = baseDensity;
            this.masks = masks;
            this.depthMaps = depthMaps;
            this.confidence = confidence;
            this.baseOccupied = baseOccupied;
        }

        MultiViewDepthFusion.Result refine() {
            return MultiViewDepthFusion.refine(
                    baseDensity, masks, depthMaps, confidence,
                    WIDTH, HEIGHT, DEPTH
            );
        }

        static Fixture gradient() {
            float[] base = new float[WIDTH * HEIGHT * DEPTH];
            int occupied = 0;
            for (int y = 2; y <= 17; y++) {
                for (int x = 3; x <= 12; x++) {
                    for (int z = 2; z <= 11; z++) {
                        base[index(x, y, z)] = 1.0f;
                        occupied++;
                    }
                }
            }

            int[] widths = {WIDTH, DEPTH, WIDTH, DEPTH};
            boolean[][] masks = new boolean[4][];
            float[][] maps = new float[4][];
            float[][] confidence = new float[4][];
            for (int view = 0; view < 4; view++) {
                int viewWidth = widths[view];
                int size = viewWidth * HEIGHT;
                masks[view] = new boolean[size];
                maps[view] = new float[size];
                confidence[view] = new float[size];
                for (int y = 2; y <= 17; y++) {
                    for (int x = 0; x < viewWidth; x++) {
                        boolean inside = viewWidth == WIDTH
                                ? x >= 3 && x <= 12
                                : x >= 2 && x <= 11;
                        int index = y * viewWidth + x;
                        masks[view][index] = inside;
                        maps[view][index] = x + y * 0.015f + view * 0.07f;
                        confidence[view][index] = inside ? 0.75f + x * 0.01f : 0.0f;
                    }
                }
            }
            return new Fixture(base, masks, maps, confidence, occupied);
        }
    }
}
