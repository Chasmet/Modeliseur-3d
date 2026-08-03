package com.chasmet.modeliseur3d.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Sélectionne un seul sujet principal dans un masque pouvant contenir une
 * planche de références. Classe sans dépendance Android pour être testable en CI.
 */
public final class SingleSubjectSelector {
    private static final int[] DX = {-1, 0, 1, -1, 1, -1, 0, 1};
    private static final int[] DY = {-1, -1, -1, 0, 0, 1, 1, 1};

    private SingleSubjectSelector() {
    }

    public static Selection select(boolean[] input, int width, int height) {
        if (input == null || input.length != width * height
                || width < 2 || height < 2) {
            throw new IllegalArgumentException("Masque de sujets invalide");
        }
        boolean[] cleaned = Arrays.copyOf(input, input.length);
        closeSmallGaps(cleaned, width, height);
        removeIsolatedPixels(cleaned, width, height);

        int[] labels = new int[cleaned.length];
        Arrays.fill(labels, -1);
        List<Component> components = label(cleaned, labels, width, height);
        if (components.isEmpty()) {
            throw new IllegalArgumentException("Aucun personnage détecté");
        }

        Component primary = choosePrimary(components, width, height);
        boolean[] accepted = new boolean[components.size()];
        accepted[primary.id] = true;

        int mergeDistance = Math.max(
                3,
                Math.round(Math.max(primary.width(), primary.height()) * 0.075f)
        );
        boolean changed;
        do {
            changed = false;
            Bounds current = boundsForAccepted(components, accepted, primary);
            for (Component component : components) {
                if (accepted[component.id]
                        || component.area < Math.max(6, primary.area / 180)) {
                    continue;
                }
                if (distance(current, component) <= mergeDistance
                        && component.area <= primary.area * 0.55f) {
                    accepted[component.id] = true;
                    changed = true;
                }
            }
        } while (changed);

        boolean[] selected = new boolean[cleaned.length];
        int selectedCount = 0;
        for (int index = 0; index < labels.length; index++) {
            int label = labels[index];
            if (label >= 0 && accepted[label]) {
                selected[index] = true;
                selectedCount++;
            }
        }
        if (selectedCount < Math.max(12, width * height / 5000)) {
            throw new IllegalArgumentException("Personnage principal trop petit");
        }

        Bounds selectedBounds = boundsForMask(selected, width, height);
        int significantSubjects = 0;
        int significance = Math.max(12, primary.area / 12);
        for (Component component : components) {
            if (component.area >= significance) {
                significantSubjects++;
            }
        }
        return new Selection(
                selected,
                selectedBounds.left,
                selectedBounds.top,
                selectedBounds.right,
                selectedBounds.bottom,
                Math.max(1, significantSubjects),
                primary.area,
                selectedCount
        );
    }

    private static List<Component> label(
            boolean[] mask,
            int[] labels,
            int width,
            int height
    ) {
        List<Component> components = new ArrayList<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int start = 0; start < mask.length; start++) {
            if (!mask[start] || labels[start] >= 0) {
                continue;
            }
            int id = components.size();
            int sx = start % width;
            int sy = start / width;
            Component component = new Component(id, sx, sy);
            labels[start] = id;
            queue.add(start);
            while (!queue.isEmpty()) {
                int current = queue.removeFirst();
                int x = current % width;
                int y = current / width;
                component.add(x, y, width, height);
                for (int direction = 0; direction < DX.length; direction++) {
                    int nx = x + DX[direction];
                    int ny = y + DY[direction];
                    if (nx < 0 || ny < 0 || nx >= width || ny >= height) {
                        continue;
                    }
                    int next = ny * width + nx;
                    if (mask[next] && labels[next] < 0) {
                        labels[next] = id;
                        queue.addLast(next);
                    }
                }
            }
            if (component.area >= 3) {
                components.add(component);
            } else {
                for (int index = 0; index < labels.length; index++) {
                    if (labels[index] == id) {
                        labels[index] = -1;
                    }
                }
            }
        }
        return components;
    }

    private static Component choosePrimary(
            List<Component> components,
            int width,
            int height
    ) {
        Component best = components.get(0);
        double bestScore = Double.NEGATIVE_INFINITY;
        double centerX = (width - 1) * 0.5;
        double centerY = (height - 1) * 0.5;
        double diagonal = Math.max(1.0, Math.hypot(width, height));
        for (Component component : components) {
            double componentX = component.sumX / Math.max(1.0, component.area);
            double componentY = component.sumY / Math.max(1.0, component.area);
            double centerDistance = Math.hypot(
                    componentX - centerX,
                    componentY - centerY
            ) / diagonal;
            double centerBonus = Math.max(0.20, 1.0 - centerDistance * 1.65);
            double heightRatio = component.height() / (double) height;
            double widthRatio = component.width() / (double) width;
            double completeness = Math.min(1.0, heightRatio * 1.45 + widthRatio * 0.45);
            double borderPenalty = component.touchesBorder ? 0.82 : 1.0;
            double score = component.area
                    * (0.62 + centerBonus * 0.78)
                    * (0.72 + completeness * 0.48)
                    * borderPenalty;
            if (score > bestScore) {
                bestScore = score;
                best = component;
            }
        }
        return best;
    }

    private static Bounds boundsForAccepted(
            List<Component> components,
            boolean[] accepted,
            Component fallback
    ) {
        int left = Integer.MAX_VALUE;
        int top = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE;
        int bottom = Integer.MIN_VALUE;
        for (Component component : components) {
            if (!accepted[component.id]) {
                continue;
            }
            left = Math.min(left, component.left);
            top = Math.min(top, component.top);
            right = Math.max(right, component.right);
            bottom = Math.max(bottom, component.bottom);
        }
        if (left == Integer.MAX_VALUE) {
            return new Bounds(
                    fallback.left,
                    fallback.top,
                    fallback.right,
                    fallback.bottom
            );
        }
        return new Bounds(left, top, right, bottom);
    }

    private static Bounds boundsForMask(boolean[] mask, int width, int height) {
        int left = width;
        int top = height;
        int right = -1;
        int bottom = -1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (!mask[y * width + x]) {
                    continue;
                }
                left = Math.min(left, x);
                top = Math.min(top, y);
                right = Math.max(right, x);
                bottom = Math.max(bottom, y);
            }
        }
        if (right < left || bottom < top) {
            throw new IllegalArgumentException("Sélection de sujet vide");
        }
        return new Bounds(left, top, right, bottom);
    }

    private static int distance(Bounds first, Component second) {
        int dx = 0;
        if (second.right < first.left) {
            dx = first.left - second.right;
        } else if (second.left > first.right) {
            dx = second.left - first.right;
        }
        int dy = 0;
        if (second.bottom < first.top) {
            dy = first.top - second.bottom;
        } else if (second.top > first.bottom) {
            dy = second.top - first.bottom;
        }
        return Math.max(dx, dy);
    }

    private static void closeSmallGaps(boolean[] mask, int width, int height) {
        boolean[] source = Arrays.copyOf(mask, mask.length);
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int index = y * width + x;
                if (source[index]) {
                    continue;
                }
                int neighbors = 0;
                for (int oy = -1; oy <= 1; oy++) {
                    for (int ox = -1; ox <= 1; ox++) {
                        if (source[(y + oy) * width + x + ox]) {
                            neighbors++;
                        }
                    }
                }
                if (neighbors >= 5) {
                    mask[index] = true;
                }
            }
        }
    }

    private static void removeIsolatedPixels(boolean[] mask, int width, int height) {
        boolean[] source = Arrays.copyOf(mask, mask.length);
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int index = y * width + x;
                if (!source[index]) {
                    continue;
                }
                int neighbors = 0;
                for (int oy = -1; oy <= 1; oy++) {
                    for (int ox = -1; ox <= 1; ox++) {
                        if ((ox != 0 || oy != 0)
                                && source[(y + oy) * width + x + ox]) {
                            neighbors++;
                        }
                    }
                }
                if (neighbors <= 1) {
                    mask[index] = false;
                }
            }
        }
    }

    public static final class Selection {
        private final boolean[] mask;
        private final int left;
        private final int top;
        private final int right;
        private final int bottom;
        private final int detectedSubjectCount;
        private final int primaryArea;
        private final int selectedArea;

        Selection(
                boolean[] mask,
                int left,
                int top,
                int right,
                int bottom,
                int detectedSubjectCount,
                int primaryArea,
                int selectedArea
        ) {
            this.mask = mask;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.detectedSubjectCount = detectedSubjectCount;
            this.primaryArea = primaryArea;
            this.selectedArea = selectedArea;
        }

        public boolean[] getMask() {
            return mask;
        }

        public int getLeft() {
            return left;
        }

        public int getTop() {
            return top;
        }

        public int getRight() {
            return right;
        }

        public int getBottom() {
            return bottom;
        }

        public int getDetectedSubjectCount() {
            return detectedSubjectCount;
        }

        public int getPrimaryArea() {
            return primaryArea;
        }

        public int getSelectedArea() {
            return selectedArea;
        }
    }

    private static final class Component {
        final int id;
        int area;
        int left;
        int top;
        int right;
        int bottom;
        long sumX;
        long sumY;
        boolean touchesBorder;

        Component(int id, int x, int y) {
            this.id = id;
            left = right = x;
            top = bottom = y;
        }

        void add(int x, int y, int width, int height) {
            area++;
            left = Math.min(left, x);
            top = Math.min(top, y);
            right = Math.max(right, x);
            bottom = Math.max(bottom, y);
            sumX += x;
            sumY += y;
            touchesBorder |= x == 0 || y == 0 || x == width - 1 || y == height - 1;
        }

        int width() {
            return right - left + 1;
        }

        int height() {
            return bottom - top + 1;
        }
    }

    private static final class Bounds {
        final int left;
        final int top;
        final int right;
        final int bottom;

        Bounds(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }
}
