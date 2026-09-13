package com.chasmet.modeliseur3d.model;

/**
 * V9.5 memory-safe topology pass for kart + driver.
 *
 * <p>Même logique de sculpture que CompositeVehicleTopologyRefiner, mais sans
 * Arrays.copyOf(source, source.length). Une première passe valide le résultat
 * potentiel, puis une seconde l'applique directement dans la densité DA3.</p>
 */
public final class MemorySafeCompositeVehicleTopologyRefiner {
    private static final float ISO = 0.50f;

    private MemorySafeCompositeVehicleTopologyRefiner() {
    }

    public static Result refine(float[] density, int width, int height, int depth) {
        if (density == null || width < 16 || height < 24 || depth < 16
                || density.length != width * height * depth) {
            return Result.unchanged(density, countOccupied(density),
                    "V9.5 kart mémoire : champ invalide");
        }

        Bounds b = Bounds.from(density, width, height, depth);
        if (b.occupied < 256 || b.top < 0) {
            return Result.unchanged(density, b.occupied,
                    "V9.5 kart mémoire : volume insuffisant");
        }

        float cx = (b.minX + b.maxX) * 0.5f;
        float cz = (b.minZ + b.maxZ) * 0.5f;
        float hx = Math.max(1.0f, (b.maxX - b.minX + 1) * 0.5f);
        float hz = Math.max(1.0f, (b.maxZ - b.minZ + 1) * 0.5f);
        int spanY = Math.max(1, b.bottom - b.top);

        int changed = 0;
        int occupiedAfter = 0;
        int wheelProtected = 0;
        int chassisProtected = 0;
        int pilotProtected = 0;

        // Passe 1 : mesure sans écrire, pour conserver exactement le garde-fou.
        for (int y = b.top; y <= b.bottom; y++) {
            float v = (y - b.top) / (float) spanY;
            for (int x = b.minX; x <= b.maxX; x++) {
                float ax = Math.abs((x - cx) / hx);
                for (int z = b.minZ; z <= b.maxZ; z++) {
                    int i = index(x, y, z, width, depth);
                    float value = density[i];
                    if (value <= 0.02f) {
                        continue;
                    }
                    float az = Math.abs((z - cz) / hz);
                    boolean wheelCorner = isWheelCorner(v, ax, az);
                    boolean centralChassis = isCentralChassis(v, ax, az);
                    boolean pilotCore = isPilotCore(v, ax, az);
                    if (wheelCorner) {
                        wheelProtected++;
                    } else if (centralChassis) {
                        chassisProtected++;
                    } else if (pilotCore) {
                        pilotProtected++;
                    }
                    float factor = (wheelCorner || centralChassis || pilotCore)
                            ? 1.0f : carveFactor(v, ax, az);
                    float refined = factor >= 0.9999f ? value : value * factor;
                    if (refined < value - 1.0e-6f) {
                        changed++;
                    }
                    if (refined >= ISO) {
                        occupiedAfter++;
                    }
                }
            }
        }

        int minimumChanged = Math.max(24, b.occupied / 900);
        if (changed < minimumChanged
                || occupiedAfter < Math.round(b.occupied * 0.68f)) {
            return Result.unchanged(density, b.occupied,
                    "V9.5 kart mémoire : garde-fou anti-effondrement");
        }

        // Passe 2 : sculpture directe dans le même tableau.
        for (int y = b.top; y <= b.bottom; y++) {
            float v = (y - b.top) / (float) spanY;
            for (int x = b.minX; x <= b.maxX; x++) {
                float ax = Math.abs((x - cx) / hx);
                for (int z = b.minZ; z <= b.maxZ; z++) {
                    int i = index(x, y, z, width, depth);
                    float value = density[i];
                    if (value <= 0.02f) {
                        continue;
                    }
                    float az = Math.abs((z - cz) / hz);
                    if (isWheelCorner(v, ax, az)
                            || isCentralChassis(v, ax, az)
                            || isPilotCore(v, ax, az)) {
                        continue;
                    }
                    float factor = carveFactor(v, ax, az);
                    if (factor < 0.9999f) {
                        density[i] = value * factor;
                    }
                }
            }
        }

        int removed = Math.max(0, b.occupied - occupiedAfter);
        int percent = Math.round(removed * 100.0f / Math.max(1, b.occupied));
        return new Result(
                density,
                true,
                changed,
                occupiedAfter,
                "V9.5 kart mémoire : pilote/châssis/4 roues séparés en place • "
                        + changed + " voxels affinés • retrait " + percent + "%"
                        + " • protections roues=" + wheelProtected
                        + " châssis=" + chassisProtected
                        + " pilote=" + pilotProtected
        );
    }

    private static boolean isWheelCorner(float v, float ax, float az) {
        return v >= 0.62f && ax >= 0.48f && az >= 0.42f;
    }

    private static boolean isCentralChassis(float v, float ax, float az) {
        return v >= 0.54f && v <= 0.78f && ax <= 0.48f && az <= 0.58f;
    }

    private static boolean isPilotCore(float v, float ax, float az) {
        return v <= 0.55f && ax <= 0.48f && az <= 0.46f;
    }

    private static float carveFactor(float v, float ax, float az) {
        float factor = 1.0f;
        if (v >= 0.72f && ax <= 0.44f && az <= 0.64f) {
            factor = Math.min(factor, 0.04f);
        }
        if (v >= 0.61f && v <= 0.88f
                && ax >= 0.48f && ax <= 0.82f
                && az <= 0.36f) {
            factor = Math.min(factor, 0.10f);
        }
        if (v >= 0.63f && v <= 0.90f
                && az >= 0.44f && az <= 0.78f
                && ax <= 0.34f) {
            factor = Math.min(factor, 0.12f);
        }
        if (v >= 0.27f && v <= 0.55f
                && ax >= 0.48f && ax <= 0.78f
                && az <= 0.50f) {
            factor = Math.min(factor, 0.30f);
        }
        if (v >= 0.50f && v <= 0.62f
                && ax >= 0.42f && az >= 0.34f) {
            factor = Math.min(factor, 0.24f);
        }
        return factor;
    }

    private static int countOccupied(float[] density) {
        if (density == null) {
            return 0;
        }
        int count = 0;
        for (float value : density) {
            if (value >= ISO) {
                count++;
            }
        }
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
        int minX;
        int maxX;
        int top;
        int bottom;
        int minZ;
        int maxZ;
        int occupied;

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
                        if (density[index(x, y, z, width, depth)] < ISO) {
                            continue;
                        }
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
