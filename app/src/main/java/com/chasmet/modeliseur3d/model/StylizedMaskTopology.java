package com.chasmet.modeliseur3d.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Nettoyage léger des silhouettes de personnages fictifs.
 *
 * Contrairement à une dilatation globale, ce traitement conserve plusieurs
 * composantes séparées : jambes, bras décollés, ailes, cheveux et accessoires.
 */
public final class StylizedMaskTopology {
    private StylizedMaskTopology() {
    }

    public static boolean[] clean(
            boolean[] source,
            int width,
            int height,
            int maximumComponents
    ) {
        if (source == null || source.length != width * height) {
            throw new IllegalArgumentException("Silhouette invalide");
        }
        if (width < 4 || height < 4) {
            throw new IllegalArgumentException("Silhouette trop petite");
        }
        int componentLimit = Math.max(1, maximumComponents);
        boolean[] visited = new boolean[source.length];
        int[] queue = new int[source.length];
        List<Component> components = new ArrayList<>();

        for (int start = 0; start < source.length; start++) {
            if (!source[start] || visited[start]) {
                continue;
            }
            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            visited[start] = true;
            IntCollector pixels = new IntCollector(128);
            while (head < tail) {
                int current = queue[head++];
                pixels.add(current);
                int x = current % width;
                int y = current / width;
                if (x > 0) {
                    tail = enqueue(source, visited, queue, tail, current - 1);
                }
                if (x + 1 < width) {
                    tail = enqueue(source, visited, queue, tail, current + 1);
                }
                if (y > 0) {
                    tail = enqueue(source, visited, queue, tail, current - width);
                }
                if (y + 1 < height) {
                    tail = enqueue(source, visited, queue, tail, current + width);
                }
            }
            components.add(new Component(pixels.toArray()));
        }

        components.sort(Comparator.comparingInt(Component::size).reversed());
        int minimumPixels = Math.max(3, source.length / 60_000);
        boolean[] output = new boolean[source.length];
        int retained = 0;
        for (Component component : components) {
            if (retained >= componentLimit) {
                break;
            }
            if (component.size() < minimumPixels && retained > 0) {
                continue;
            }
            for (int pixel : component.pixels) {
                output[pixel] = true;
            }
            retained++;
        }

        closeSinglePixelHoles(output, width, height);
        return output;
    }

    public static int countComponents(boolean[] mask, int width, int height) {
        if (mask == null || mask.length != width * height) {
            throw new IllegalArgumentException("Silhouette invalide");
        }
        boolean[] visited = new boolean[mask.length];
        int[] queue = new int[mask.length];
        int count = 0;
        for (int start = 0; start < mask.length; start++) {
            if (!mask[start] || visited[start]) {
                continue;
            }
            count++;
            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            visited[start] = true;
            while (head < tail) {
                int current = queue[head++];
                int x = current % width;
                int y = current / width;
                if (x > 0) {
                    tail = enqueue(mask, visited, queue, tail, current - 1);
                }
                if (x + 1 < width) {
                    tail = enqueue(mask, visited, queue, tail, current + 1);
                }
                if (y > 0) {
                    tail = enqueue(mask, visited, queue, tail, current - width);
                }
                if (y + 1 < height) {
                    tail = enqueue(mask, visited, queue, tail, current + width);
                }
            }
        }
        return count;
    }

    private static int enqueue(
            boolean[] mask,
            boolean[] visited,
            int[] queue,
            int tail,
            int index
    ) {
        if (mask[index] && !visited[index]) {
            visited[index] = true;
            queue[tail++] = index;
        }
        return tail;
    }

    private static void closeSinglePixelHoles(boolean[] mask, int width, int height) {
        boolean[] source = Arrays.copyOf(mask, mask.length);
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int index = y * width + x;
                if (!source[index]
                        && source[index - 1]
                        && source[index + 1]
                        && source[index - width]
                        && source[index + width]) {
                    mask[index] = true;
                }
            }
        }
    }

    private static final class Component {
        final int[] pixels;

        Component(int[] pixels) {
            this.pixels = pixels;
        }

        int size() {
            return pixels.length;
        }
    }

    private static final class IntCollector {
        private int[] values;
        private int size;

        IntCollector(int capacity) {
            values = new int[Math.max(8, capacity)];
        }

        void add(int value) {
            if (size == values.length) {
                values = Arrays.copyOf(values, values.length * 2);
            }
            values[size++] = value;
        }

        int[] toArray() {
            return Arrays.copyOf(values, size);
        }
    }
}
