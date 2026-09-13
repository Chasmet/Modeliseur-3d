package com.chasmet.modeliseur3d.model;

import java.util.Arrays;

/**
 * V9.3 topology pass for a rider/driver sitting inside a small vehicle.
 *
 * <p>The four-view visual hull tends to turn a kart + driver into one solid
 * block because the driver, seat, chassis and wheels overlap in projection.
 * This pass is deliberately subtractive: it never invents geometry. It opens
 * physically plausible gaps while preserving four wheel-corner supports, the
 * central chassis/seat and the upper driver volume.</p>
 */
public final class CompositeVehicleTopologyRefiner {
    private static final float ISO = 0.50f;

    private CompositeVehicleTopologyRefiner() {
    }

    public static Result refine(float[] source, int width, int height, int depth) {
        if (source == null || width < 16 || height < 24 || depth < 16
                || source.length != width * height * depth) {
            return Result.unchanged(source, countOccupied(source),
                    "V9.3 kart : champ invalide");
        }

        Bounds b = Bounds.from(source, width, height, depth);
        if (b.occupied < 256 || b.top < 0) {
            return Result.unchanged(source, b.occupied,
                    "V9.3 kart : volume insuffisant");
        }

        float[] out = Arrays.copyOf(source, source.length);
        float cx = (b.minX + b.maxX) * 0.5f;
        float cz = (b.minZ + b.maxZ) * 0.5f;
        float hx = Math.max(1.0f, (b.maxX - b.minX + 1) * 0.5f);
        float hz = Math.max(1.0f, (b.maxZ - b.minZ + 1) * 0.5f);
        int spanY = Math.max(1, b.bottom - b.top);

        int changed = 0;
        int wheelProtected = 0;
        int chassisProtected = 0;
        int pilotProtected = 0;

        for (int y = b.top; y <= b.bottom; y++) {
            float v = (y - b.top) / (float) spanY;
            for (int x = b.minX; x <= b.maxX; x++) {
                float nx = (x - cx) / hx;
                float ax = Math.abs(nx);
                for (int z = b.minZ; z <= b.maxZ; z++) {
                    int i = index(x, y, z, width, depth);
                    float value = out[i];
                    if (value <= 0.02f) continue;

                    float nz = (z - cz) / hz;
                    float az = Math.abs(nz);

                    boolean wheelCorner = v >= 0.62f
                            && ax >= 0.48f && az >= 0.42f;
                    boolean centralChassis = v >= 0.54f && v <= 0.78f
                            && ax <= 0.48f && az <= 0.58f;
                    boolean pilotCore = v <= 0.55f
                            && ax <= 0.48f && az <= 0.46f;

                    if (wheelCorner) {
                        wheelProtected++;
                        continue;
                    }
                    if (centralChassis) {
                        chassisProtected++;
                        continue;
                    }
                    if (pilotCore) {
                        pilotProtected++;
                        continue;
                    }

                    float factor = 1.0f;

                    // 1) Open the underbody between the four wheel corners.
                    // Keep the central chassis above it, but remove the solid
                    // visual-hull slab that incorrectly joins all wheels.
                    if (v >= 0.72f && ax <= 0.44f && az <= 0.64f) {
                        factor = Math.min(factor, 0.04f);
                    }

                    // 2) Open lateral wheel arches. This is the important gap
                    // seen from front/back around a kart's left/right wheels.
                    if (v >= 0.61f && v <= 0.88f
                            && ax >= 0.48f && ax <= 0.82f
                            && az <= 0.36f) {
                        factor = Math.min(factor, 0.10f);
                    }

                    // 3) Open longitudinal spaces between front/rear wheel
                    // groups as seen from the side views.
                    if (v >= 0.63f && v <= 0.90f
                            && az >= 0.44f && az <= 0.78f
                            && ax <= 0.34f) {
                        factor = Math.min(factor, 0.12f);
                    }

                    // 4) Separate shoulders/arms of the driver from the outer
                    // bodywork while preserving the torso/seat core.
                    if (v >= 0.27f && v <= 0.55f
                            && ax >= 0.48f && ax <= 0.78f
                            && az <= 0.50f) {
                        factor = Math.min(factor, 0.30f);
                    }

                    // 5) Thin the driver-to-body bridge around the seat rim.
                    // Do not cut the central seat support, so the model remains
                    // a coherent assembly rather than floating pieces.
                    if (v >= 0.50f && v <= 0.62f
                            && ax >= 0.42f && az >= 0.34f) {
                        factor = Math.min(factor, 0.24f);
                    }

                    if (factor < 0.9999f) {
                        float refined = value * factor;
                        if (refined < value - 1.0e-6f) {
                            out[i] = refined;
                            changed++;
                        }
                    }
                }
            }
        }

        int occupied = countOccupied(out);
        int minimumChanged = Math.max(24, b.occupied / 900);
        // Composite objects need stronger carving than a character, but a
        // collapse below 68% usually means the priors do not match the input.
        if (changed < minimumChanged || occupied < Math.round(b.occupied * 0.68f)) {
            return Result.unchanged(source, b.occupied,
                    "V9.3 kart : garde-fou anti-effondrement");
        }

        int removed = Math.max(0, b.occupied - occupied);
        int percent = Math.round(removed * 100.0f / Math.max(1, b.occupied));
        return new Result(out, true, changed, occupied,
                "V9.3 kart : pilote/châssis/4 roues séparés • "
                        + changed + " voxels affinés • retrait " + percent + "%"
                        + " • protections roues=" + wheelProtected
                        + " châssis=" + chassisProtected
                        + " pilote=" + pilotProtected);
    }

    private static int countOccupied(float[] density) {
        if (density == null) return 0;
        int count = 0;
        for (float v : density) if (v >= ISO) count++;
        return count;
    }

    private static int index(int x, int y, int z, int width, int depth) {
        return (y * width + x) * depth + z;
    }

    public static final class Result {
        public final float[] density;
        public final boolean applied;
        public final int changed;
        public final int occupied;
        public final String summary;

        Result(float[] density, boolean applied, int changed, int occupied, String summary) {
            this.density = density;
            this.applied = applied;
            this.changed = changed;
            this.occupied = occupied;
            this.summary = summary;
        }

        static Result unchanged(float[] density, int occupied, String summary) {
            return new Result(density, false, 0, occupied, summary);
        }
    }

    private static final class Bounds {
        int minX, maxX, top, bottom, minZ, maxZ, occupied;

        static Bounds from(float[] density, int width, int height, int depth) {
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
                        if (density[index(x, y, z, width, depth)] < ISO) continue;
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
            if (b.occupied == 0) {
                b.minX = b.maxX = b.top = b.bottom = b.minZ = b.maxZ = -1;
            }
            return b;
        }
    }
}
