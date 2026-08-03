package com.chasmet.modeliseur3d.model;

public final class VolumeTopologyCleanerSelfTest {
    private VolumeTopologyCleanerSelfTest() {
    }

    public static void main(String[] args) {
        int width = 12;
        int height = 16;
        int depth = 12;
        boolean[] volume = new boolean[width * height * depth];

        for (int y = 3; y <= 12; y++) {
            for (int x = 3; x <= 8; x++) {
                for (int z = 3; z <= 8; z++) {
                    volume[index(x, y, z, width, depth)] = true;
                }
            }
        }
        volume[index(5, 8, 5, width, depth)] = false;

        volume[index(0, 0, 0, width, depth)] = true;
        volume[index(0, 0, 1, width, depth)] = true;
        volume[index(11, 15, 11, width, depth)] = true;

        VolumeTopologyCleaner.bridgeSingleVoxelGaps(
                volume,
                width,
                height,
                depth
        );
        require(volume[index(5, 8, 5, width, depth)],
                "Le trou d'un voxel doit être rebouché");

        int kept = VolumeTopologyCleaner.keepLargestComponent(
                volume,
                width,
                height,
                depth
        );
        require(kept == 360, "La composante principale doit contenir 360 voxels");
        require(!volume[index(0, 0, 0, width, depth)],
                "Le fragment extérieur doit être supprimé");
        require(volume[index(6, 8, 6, width, depth)],
                "Le personnage principal doit être conservé");

        System.out.println(
                "VolumeTopologyCleanerSelfTest : OK • voxels=" + kept
        );
    }

    private static int index(
            int x,
            int y,
            int z,
            int width,
            int depth
    ) {
        return (y * width + x) * depth + z;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
