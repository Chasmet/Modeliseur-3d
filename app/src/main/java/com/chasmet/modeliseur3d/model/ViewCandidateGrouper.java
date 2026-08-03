package com.chasmet.modeliseur3d.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Regroupe les morceaux détourés qui appartiennent à une même vue.
 *
 * Une main, une jambe fine ou une patte d'animal peuvent être séparées par le
 * masque neuronal. Elles ne doivent jamais être comptées comme une seconde
 * vue. À l'inverse, deux personnages complets placés côte à côte dans une
 * planche de rotation doivent rester deux candidats distincts.
 *
 * Cette classe ne dépend pas d'Android afin de pouvoir être testée sur la JVM.
 */
final class ViewCandidateGrouper {
    private ViewCandidateGrouper() {
    }

    static List<Group> group(
            List<Piece> input,
            int imageWidth,
            int imageHeight
    ) {
        if (input == null || input.isEmpty()) {
            return Collections.emptyList();
        }

        int imageArea = Math.max(1, imageWidth * imageHeight);
        int dustPixels = Math.max(6, imageArea / 260_000);
        List<Piece> pieces = new ArrayList<>();
        for (Piece piece : input) {
            if (piece != null
                    && piece.pixelCount >= dustPixels
                    && piece.width() > 0
                    && piece.height() > 0) {
                pieces.add(piece);
            }
        }
        if (pieces.isEmpty()) {
            Piece largest = largestPiece(input);
            if (largest == null) {
                return Collections.emptyList();
            }
            return Collections.singletonList(Group.from(largest));
        }
        if (pieces.size() > 256) {
            Collections.sort(pieces, new Comparator<Piece>() {
                @Override
                public int compare(Piece first, Piece second) {
                    return Integer.compare(
                            second.pixelCount,
                            first.pixelCount
                    );
                }
            });
            pieces = new ArrayList<>(pieces.subList(0, 256));
        }

        int largestArea = 1;
        int largestHeight = 1;
        int largestWidth = 1;
        for (Piece piece : pieces) {
            largestArea = Math.max(largestArea, piece.pixelCount);
            largestHeight = Math.max(largestHeight, piece.height());
            largestWidth = Math.max(largestWidth, piece.width());
        }

        UnionFind union = new UnionFind(pieces.size());
        for (int first = 0; first < pieces.size(); first++) {
            for (int second = first + 1; second < pieces.size(); second++) {
                if (belongTogether(
                        pieces.get(first),
                        pieces.get(second),
                        largestArea,
                        largestHeight,
                        largestWidth,
                        imageWidth,
                        imageHeight
                )) {
                    union.join(first, second);
                }
            }
        }

        List<Group> groups = collectGroups(pieces, union);
        attachSatellites(groups, imageWidth, imageHeight);
        filterNoise(groups, imageArea, imageWidth, imageHeight);
        if (groups.isEmpty()) {
            return Collections.singletonList(Group.from(largestPiece(pieces)));
        }

        Collections.sort(groups, new Comparator<Group>() {
            @Override
            public int compare(Group first, Group second) {
                int rowTolerance = Math.max(
                        6,
                        Math.min(first.height(), second.height()) / 4
                );
                if (Math.abs(first.top - second.top) > rowTolerance) {
                    return Integer.compare(first.top, second.top);
                }
                return Integer.compare(first.left, second.left);
            }
        });
        return groups;
    }

    private static boolean belongTogether(
            Piece first,
            Piece second,
            int largestArea,
            int largestHeight,
            int largestWidth,
            int imageWidth,
            int imageHeight
    ) {
        int gapX = intervalGap(first.left, first.right, second.left, second.right);
        int gapY = intervalGap(first.top, first.bottom, second.top, second.bottom);
        int overlapX = intervalOverlap(
                first.left, first.right, second.left, second.right
        );
        int overlapY = intervalOverlap(
                first.top, first.bottom, second.top, second.bottom
        );
        float overlapXRatio = overlapX / (float) Math.max(
                1,
                Math.min(first.width(), second.width())
        );
        float overlapYRatio = overlapY / (float) Math.max(
                1,
                Math.min(first.height(), second.height())
        );

        boolean firstFull = isFullViewPiece(
                first, largestArea, largestHeight, largestWidth
        );
        boolean secondFull = isFullViewPiece(
                second, largestArea, largestHeight, largestWidth
        );
        float centerDistanceX = Math.abs(first.centerX() - second.centerX());
        float centerDistanceY = Math.abs(first.centerY() - second.centerY());
        float referenceHeight = Math.max(first.height(), second.height());
        float referenceWidth = Math.max(first.width(), second.width());

        if (firstFull && secondFull) {
            boolean separateColumns = overlapXRatio < 0.16f
                    && overlapYRatio > 0.34f
                    && centerDistanceX > referenceHeight * 0.34f;
            boolean separateRows = overlapYRatio < 0.16f
                    && overlapXRatio > 0.30f
                    && centerDistanceY > referenceHeight * 0.42f;
            if (separateColumns || separateRows) {
                return false;
            }
        }

        int baseGap = Math.max(
                3,
                Math.round(Math.max(imageWidth, imageHeight) * 0.008f)
        );
        float partScale = Math.max(
                12.0f,
                Math.min(
                        Math.max(first.height(), first.width()),
                        Math.max(second.height(), second.width())
                )
        );
        int verticalJoin = Math.max(baseGap, Math.round(partScale * 0.18f));
        int horizontalJoin = Math.max(baseGap, Math.round(partScale * 0.16f));

        if (overlapXRatio >= 0.18f && gapY <= verticalJoin) {
            return true;
        }
        if (overlapYRatio >= 0.20f && gapX <= horizontalJoin) {
            return true;
        }

        boolean oneIsSatellite = first.pixelCount < largestArea * 0.28f
                || second.pixelCount < largestArea * 0.28f
                || first.height() < largestHeight * 0.46f
                || second.height() < largestHeight * 0.46f;
        if (!oneIsSatellite) {
            return false;
        }

        float diagonal = (float) Math.sqrt(
                gapX * (double) gapX + gapY * (double) gapY
        );
        float diagonalLimit = Math.max(
                baseGap * 1.5f,
                Math.max(referenceHeight, referenceWidth) * 0.14f
        );
        return diagonal <= diagonalLimit;
    }

    private static boolean isFullViewPiece(
            Piece piece,
            int largestArea,
            int largestHeight,
            int largestWidth
    ) {
        boolean substantialArea = piece.pixelCount >= largestArea * 0.24f;
        boolean substantialHeight = piece.height() >= largestHeight * 0.64f;
        boolean substantialWidth = piece.width() >= largestWidth * 0.42f;
        return substantialArea && substantialHeight && substantialWidth;
    }

    private static List<Group> collectGroups(
            List<Piece> pieces,
            UnionFind union
    ) {
        List<Group> groups = new ArrayList<>();
        int[] roots = new int[pieces.size()];
        java.util.Arrays.fill(roots, -1);
        for (int index = 0; index < pieces.size(); index++) {
            int root = union.find(index);
            int groupIndex = roots[root];
            if (groupIndex < 0) {
                groupIndex = groups.size();
                roots[root] = groupIndex;
                groups.add(new Group());
            }
            groups.get(groupIndex).add(pieces.get(index));
        }
        return groups;
    }

    private static void attachSatellites(
            List<Group> groups,
            int imageWidth,
            int imageHeight
    ) {
        if (groups.size() < 2) {
            return;
        }

        int largestPixels = 1;
        int largestHeight = 1;
        for (Group group : groups) {
            largestPixels = Math.max(largestPixels, group.pixelCount);
            largestHeight = Math.max(largestHeight, group.height());
        }

        List<Group> anchors = new ArrayList<>();
        List<Group> satellites = new ArrayList<>();
        for (Group group : groups) {
            boolean anchor = group.pixelCount >= largestPixels * 0.16f
                    && group.height() >= largestHeight * 0.38f;
            if (anchor) {
                anchors.add(group);
            } else {
                satellites.add(group);
            }
        }
        if (anchors.isEmpty()) {
            Group largest = groups.get(0);
            for (Group group : groups) {
                if (group.pixelCount > largest.pixelCount) {
                    largest = group;
                }
            }
            anchors.add(largest);
            satellites.remove(largest);
        }

        int baseGap = Math.max(
                6,
                Math.round(Math.max(imageWidth, imageHeight) * 0.012f)
        );
        for (Group satellite : new ArrayList<>(satellites)) {
            Group nearest = null;
            float nearestScore = Float.POSITIVE_INFINITY;
            for (Group anchor : anchors) {
                int gapX = intervalGap(
                        satellite.left, satellite.right,
                        anchor.left, anchor.right
                );
                int gapY = intervalGap(
                        satellite.top, satellite.bottom,
                        anchor.top, anchor.bottom
                );
                float distance = (float) Math.sqrt(
                        gapX * (double) gapX + gapY * (double) gapY
                );
                float scale = Math.max(24.0f, anchor.height());
                float score = distance / scale;
                if (score < nearestScore) {
                    nearestScore = score;
                    nearest = anchor;
                }
            }
            float absoluteLimit = Math.max(
                    baseGap,
                    nearest == null ? 0.0f : nearest.height() * 0.30f
            );
            if (nearest != null
                    && nearestScore <= 0.30f
                    && boxDistance(satellite, nearest) <= absoluteLimit) {
                nearest.merge(satellite);
                groups.remove(satellite);
            }
        }
    }

    private static void filterNoise(
            List<Group> groups,
            int imageArea,
            int imageWidth,
            int imageHeight
    ) {
        if (groups.isEmpty()) {
            return;
        }
        Group largest = groups.get(0);
        for (Group group : groups) {
            if (group.pixelCount > largest.pixelCount) {
                largest = group;
            }
        }

        int minimumPixels = Math.max(36, imageArea / 28_000);
        int minimumHeight = Math.max(8, Math.round(imageHeight * 0.055f));
        int minimumWidth = Math.max(3, Math.round(imageWidth * 0.004f));
        for (int index = groups.size() - 1; index >= 0; index--) {
            Group group = groups.get(index);
            if (group == largest) {
                continue;
            }
            boolean tooSmall = group.pixelCount < minimumPixels
                    || group.height() < minimumHeight
                    || group.width() < minimumWidth;
            boolean negligible = group.pixelCount < largest.pixelCount * 0.012f
                    && group.height() < largest.height() * 0.20f;
            if (tooSmall || negligible) {
                groups.remove(index);
            }
        }
    }

    private static Piece largestPiece(List<Piece> pieces) {
        Piece largest = null;
        if (pieces == null) {
            return null;
        }
        for (Piece piece : pieces) {
            if (piece != null
                    && (largest == null
                    || piece.pixelCount > largest.pixelCount)) {
                largest = piece;
            }
        }
        return largest;
    }

    private static int intervalGap(
            int firstStart,
            int firstEnd,
            int secondStart,
            int secondEnd
    ) {
        if (firstEnd < secondStart) {
            return secondStart - firstEnd;
        }
        if (secondEnd < firstStart) {
            return firstStart - secondEnd;
        }
        return 0;
    }

    private static int intervalOverlap(
            int firstStart,
            int firstEnd,
            int secondStart,
            int secondEnd
    ) {
        return Math.max(
                0,
                Math.min(firstEnd, secondEnd) - Math.max(firstStart, secondStart)
        );
    }

    private static float boxDistance(Group first, Group second) {
        int gapX = intervalGap(
                first.left, first.right, second.left, second.right
        );
        int gapY = intervalGap(
                first.top, first.bottom, second.top, second.bottom
        );
        return (float) Math.sqrt(
                gapX * (double) gapX + gapY * (double) gapY
        );
    }

    static final class Piece {
        final int id;
        final int pixelCount;
        final int left;
        final int top;
        final int right;
        final int bottom;

        Piece(
                int id,
                int pixelCount,
                int left,
                int top,
                int right,
                int bottom
        ) {
            this.id = id;
            this.pixelCount = pixelCount;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        int width() {
            return Math.max(0, right - left);
        }

        int height() {
            return Math.max(0, bottom - top);
        }

        float centerX() {
            return (left + right) * 0.5f;
        }

        float centerY() {
            return (top + bottom) * 0.5f;
        }
    }

    static final class Group {
        int pixelCount;
        int left = Integer.MAX_VALUE;
        int top = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE;
        int bottom = Integer.MIN_VALUE;
        private final Set<Integer> pieceIds = new LinkedHashSet<>();

        static Group from(Piece piece) {
            Group group = new Group();
            group.add(piece);
            return group;
        }

        void add(Piece piece) {
            if (pieceIds.add(piece.id)) {
                pixelCount += piece.pixelCount;
                left = Math.min(left, piece.left);
                top = Math.min(top, piece.top);
                right = Math.max(right, piece.right);
                bottom = Math.max(bottom, piece.bottom);
            }
        }

        void merge(Group other) {
            if (other == null || other == this) {
                return;
            }
            pixelCount += other.pixelCount;
            left = Math.min(left, other.left);
            top = Math.min(top, other.top);
            right = Math.max(right, other.right);
            bottom = Math.max(bottom, other.bottom);
            pieceIds.addAll(other.pieceIds);
        }

        boolean containsPiece(int pieceId) {
            return pieceIds.contains(pieceId);
        }

        void markPieces(boolean[] membership) {
            if (membership == null) {
                return;
            }
            for (int pieceId : pieceIds) {
                if (pieceId >= 0 && pieceId < membership.length) {
                    membership[pieceId] = true;
                }
            }
        }

        int pieceCount() {
            return pieceIds.size();
        }

        int width() {
            return Math.max(0, right - left);
        }

        int height() {
            return Math.max(0, bottom - top);
        }

        float aspectRatio() {
            return width() / (float) Math.max(1, height());
        }

        float centerX() {
            return (left + right) * 0.5f;
        }

        float centerY() {
            return (top + bottom) * 0.5f;
        }
    }

    private static final class UnionFind {
        private final int[] parent;
        private final byte[] rank;

        UnionFind(int size) {
            parent = new int[size];
            rank = new byte[size];
            for (int index = 0; index < size; index++) {
                parent[index] = index;
            }
        }

        int find(int value) {
            int root = value;
            while (root != parent[root]) {
                root = parent[root];
            }
            while (value != root) {
                int next = parent[value];
                parent[value] = root;
                value = next;
            }
            return root;
        }

        void join(int first, int second) {
            int firstRoot = find(first);
            int secondRoot = find(second);
            if (firstRoot == secondRoot) {
                return;
            }
            if (rank[firstRoot] < rank[secondRoot]) {
                parent[firstRoot] = secondRoot;
            } else if (rank[firstRoot] > rank[secondRoot]) {
                parent[secondRoot] = firstRoot;
            } else {
                parent[secondRoot] = firstRoot;
                rank[firstRoot]++;
            }
        }
    }
}
