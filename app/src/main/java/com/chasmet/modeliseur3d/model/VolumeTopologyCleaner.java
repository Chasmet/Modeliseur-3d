package com.chasmet.modeliseur3d.model;

import java.util.Arrays;

/** Nettoyage topologique léger d'un volume binaire, adapté à la mémoire Android. */
public final class VolumeTopologyCleaner {
    private VolumeTopologyCleaner() {
    }

    public static int keepLargestComponent(
            boolean[] volume,
            int width,
            int height,
            int depth
    ) {
        validate(volume, width, height, depth);
        int[] labels = new int[volume.length];
        Arrays.fill(labels, -1);
        int[] queue = new int[volume.length];
        int component = 0;
        int largestLabel = -1;
        int largestSize = 0;

        for (int start = 0; start < volume.length; start++) {
            if (!volume[start] || labels[start] >= 0) {
                continue;
            }
            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            labels[start] = component;
            int size = 0;
            while (head < tail) {
                int current = queue[head++];
                size++;
                int z = current % depth;
                int xy = current / depth;
                int x = xy % width;
                int y = xy / width;

                if (x > 0) {
                    tail = enqueue(volume, labels, queue, tail,
                            current - depth, component);
                }
                if (x + 1 < width) {
                    tail = enqueue(volume, labels, queue, tail,
                            current + depth, component);
                }
                if (y > 0) {
                    tail = enqueue(volume, labels, queue, tail,
                            current - width * depth, component);
                }
                if (y + 1 < height) {
                    tail = enqueue(volume, labels, queue, tail,
                            current + width * depth, component);
                }
                if (z > 0) {
                    tail = enqueue(volume, labels, queue, tail,
                            current - 1, component);
                }
                if (z + 1 < depth) {
                    tail = enqueue(volume, labels, queue, tail,
                            current + 1, component);
                }
            }
            if (size > largestSize) {
                largestSize = size;
                largestLabel = component;
            }
            component++;
        }

        if (largestLabel < 0) {
            return 0;
        }
        for (int index = 0; index < volume.length; index++) {
            volume[index] = labels[index] == largestLabel;
        }
        return largestSize;
    }

    public static void bridgeSingleVoxelGaps(
            boolean[] volume,
            int width,
            int height,
            int depth
    ) {
        validate(volume, width, height, depth);
        boolean[] source = Arrays.copyOf(volume, volume.length);
        int slice = width * depth;
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                for (int z = 1; z < depth - 1; z++) {
                    int index = (y * width + x) * depth + z;
                    if (source[index]) {
                        continue;
                    }
                    int axisPairs = 0;
                    if (source[index - depth] && source[index + depth]) {
                        axisPairs++;
                    }
                    if (source[index - slice] && source[index + slice]) {
                        axisPairs++;
                    }
                    if (source[index - 1] && source[index + 1]) {
                        axisPairs++;
                    }
                    if (axisPairs >= 1) {
                        volume[index] = true;
                    }
                }
            }
        }
    }

    private static int enqueue(
            boolean[] volume,
            int[] labels,
            int[] queue,
            int tail,
            int candidate,
            int component
    ) {
        if (volume[candidate] && labels[candidate] < 0) {
            labels[candidate] = component;
            queue[tail++] = candidate;
        }
        return tail;
    }

    private static void validate(
            boolean[] volume,
            int width,
            int height,
            int depth
    ) {
        if (volume == null
                || width < 2
                || height < 2
                || depth < 2
                || volume.length != width * height * depth) {
            throw new IllegalArgumentException("Volume 3D invalide");
        }
    }
}
