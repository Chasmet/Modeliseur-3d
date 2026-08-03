package com.chasmet.modeliseur3d.model;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import java.util.Arrays;

/**
 * Construit un atlas compact de huit vues et attribue à chaque triangle la vue
 * la plus proche de sa normale. Les sommets sont dupliqués par triangle afin
 * que les coutures UV ne déforment jamais le maillage.
 */
public final class MultiViewTextureMapper {
    private static final int VIEW_COUNT = 8;
    private static final int COLUMNS = 4;
    private static final int ROWS = 2;
    private static final int ALPHA_THRESHOLD = 20;

    private MultiViewTextureMapper() {
    }

    public static AtlasResult buildAtlas(Bitmap[] orderedViews, int cellHeight) {
        if (orderedViews == null || orderedViews.length != VIEW_COUNT) {
            throw new IllegalArgumentException("Huit textures ordonnées sont requises");
        }
        Bitmap first = orderedViews[0];
        if (first == null || first.isRecycled()) {
            throw new IllegalArgumentException("Première texture vidéo absente");
        }
        for (Bitmap view : orderedViews) {
            if (view == null || view.isRecycled()) {
                throw new IllegalArgumentException("Une texture vidéo est absente");
            }
        }

        int safeCellHeight = Math.max(320, Math.min(768, cellHeight));
        float aspect = first.getWidth() / (float) Math.max(1, first.getHeight());
        int cellWidth = Math.max(
                192,
                Math.round(safeCellHeight * clamp(aspect, 0.52f, 0.88f))
        );
        AtlasLayout layout = new AtlasLayout(cellWidth, safeCellHeight, 6);
        Bitmap atlas = Bitmap.createBitmap(
                layout.atlasWidth,
                layout.atlasHeight,
                Bitmap.Config.ARGB_8888
        );
        Canvas canvas = new Canvas(atlas);
        canvas.drawColor(Color.rgb(38, 38, 42));
        Paint paint = new Paint(
                Paint.ANTI_ALIAS_FLAG
                        | Paint.FILTER_BITMAP_FLAG
                        | Paint.DITHER_FLAG
        );

        for (int viewIndex = 0; viewIndex < VIEW_COUNT; viewIndex++) {
            Bitmap cell = drawConsistentCell(
                    orderedViews[viewIndex],
                    layout.cellWidth,
                    layout.cellHeight
            );
            try {
                RectF target = layout.cellRect(viewIndex);
                canvas.drawBitmap(cell, null, target, paint);
            } finally {
                cell.recycle();
            }
        }
        return new AtlasResult(atlas, layout);
    }

    public static MeshData remap(
            MeshData source,
            int gridWidth,
            int gridHeight,
            int gridDepth,
            AtlasLayout atlas
    ) {
        if (source == null || atlas == null) {
            throw new IllegalArgumentException("Maillage ou atlas absent");
        }
        float[] sourcePositions = source.getPositions();
        float[] sourceNormals = source.getNormals();
        int[] sourceIndices = source.getIndices();
        int triangleCount = sourceIndices.length / 3;
        float[] positions = new float[triangleCount * 9];
        float[] normals = new float[triangleCount * 9];
        float[] texCoords = new float[triangleCount * 6];
        int[] indices = new int[triangleCount * 3];

        float halfWidth = gridWidth / (float) Math.max(1, gridHeight);
        float halfDepth = gridDepth / (float) Math.max(1, gridHeight);
        int outputVertex = 0;
        for (int triangle = 0; triangle < triangleCount; triangle++) {
            int sourceOffset = triangle * 3;
            int a = sourceIndices[sourceOffset];
            int b = sourceIndices[sourceOffset + 1];
            int c = sourceIndices[sourceOffset + 2];
            if (!validVertex(a, source.getVertexCount())
                    || !validVertex(b, source.getVertexCount())
                    || !validVertex(c, source.getVertexCount())) {
                continue;
            }

            float nx = normalX(sourceNormals, a)
                    + normalX(sourceNormals, b)
                    + normalX(sourceNormals, c);
            float nz = normalZ(sourceNormals, a)
                    + normalZ(sourceNormals, b)
                    + normalZ(sourceNormals, c);
            if (nx * nx + nz * nz < 0.015f) {
                nx = positionX(sourcePositions, a)
                        + positionX(sourcePositions, b)
                        + positionX(sourcePositions, c);
                nz = positionZ(sourcePositions, a)
                        + positionZ(sourcePositions, b)
                        + positionZ(sourcePositions, c);
            }
            int view = nearestView(nx, nz);
            double angle = view * Math.PI * 2.0 / VIEW_COUNT;
            float cosine = (float) Math.cos(angle);
            float sine = (float) Math.sin(angle);

            int[] triangleVertices = {a, b, c};
            for (int vertex : triangleVertices) {
                int sourcePosition = vertex * 3;
                int destinationPosition = outputVertex * 3;
                positions[destinationPosition] = sourcePositions[sourcePosition];
                positions[destinationPosition + 1] = sourcePositions[sourcePosition + 1];
                positions[destinationPosition + 2] = sourcePositions[sourcePosition + 2];
                normals[destinationPosition] = sourceNormals[sourcePosition];
                normals[destinationPosition + 1] = sourceNormals[sourcePosition + 1];
                normals[destinationPosition + 2] = sourceNormals[sourcePosition + 2];

                float normalizedX = sourcePositions[sourcePosition]
                        / Math.max(0.0001f, halfWidth);
                float normalizedZ = sourcePositions[sourcePosition + 2]
                        / Math.max(0.0001f, halfDepth);
                float projected = normalizedX * cosine + normalizedZ * sine;
                float localU = clamp01(projected * 0.5f + 0.5f);
                float localV = clamp01(
                        sourcePositions[sourcePosition + 1] * 0.5f + 0.5f
                );
                int uvOffset = outputVertex * 2;
                texCoords[uvOffset] = atlas.u(view, localU);
                texCoords[uvOffset + 1] = atlas.v(view, localV);
                indices[outputVertex] = outputVertex;
                outputVertex++;
            }
        }

        if (outputVertex < 3) {
            throw new IllegalArgumentException("Aucun triangle texturable");
        }
        return new MeshData(
                Arrays.copyOf(positions, outputVertex * 3),
                Arrays.copyOf(normals, outputVertex * 3),
                Arrays.copyOf(texCoords, outputVertex * 2),
                Arrays.copyOf(indices, outputVertex)
        );
    }

    private static Bitmap drawConsistentCell(
            Bitmap source,
            int targetWidth,
            int targetHeight
    ) {
        Bitmap output = Bitmap.createBitmap(
                targetWidth,
                targetHeight,
                Bitmap.Config.ARGB_8888
        );
        Canvas canvas = new Canvas(output);
        canvas.drawColor(Color.TRANSPARENT);
        Paint paint = new Paint(
                Paint.ANTI_ALIAS_FLAG
                        | Paint.FILTER_BITMAP_FLAG
                        | Paint.DITHER_FLAG
        );
        canvas.drawBitmap(
                source,
                null,
                new RectF(0.0f, 0.0f, targetWidth, targetHeight),
                paint
        );
        fillTransparentPixels(output);
        return output;
    }

    /**
     * Étend les couleurs du sujet dans toute la cellule. Le rendu OpenGL reste
     * ainsi coloré même lorsqu'une surface oblique déborde légèrement de la
     * silhouette de la vue choisie, au lieu d'afficher des bandes noires.
     */
    private static void fillTransparentPixels(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        int[] queue = new int[pixels.length];
        boolean[] visited = new boolean[pixels.length];
        int head = 0;
        int tail = 0;
        for (int index = 0; index < pixels.length; index++) {
            if (Color.alpha(pixels[index]) > ALPHA_THRESHOLD) {
                pixels[index] = 0xFF000000 | (pixels[index] & 0x00FFFFFF);
                visited[index] = true;
                queue[tail++] = index;
            }
        }
        if (tail == 0) {
            Arrays.fill(pixels, Color.rgb(112, 112, 116));
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
            return;
        }

        while (head < tail) {
            int current = queue[head++];
            int x = current % width;
            int y = current / width;
            tail = propagate(pixels, visited, queue, tail,
                    current, x - 1, y, width, height);
            tail = propagate(pixels, visited, queue, tail,
                    current, x + 1, y, width, height);
            tail = propagate(pixels, visited, queue, tail,
                    current, x, y - 1, width, height);
            tail = propagate(pixels, visited, queue, tail,
                    current, x, y + 1, width, height);
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
    }

    private static int propagate(
            int[] pixels,
            boolean[] visited,
            int[] queue,
            int tail,
            int source,
            int x,
            int y,
            int width,
            int height
    ) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            return tail;
        }
        int target = y * width + x;
        if (!visited[target]) {
            visited[target] = true;
            pixels[target] = pixels[source];
            queue[tail++] = target;
        }
        return tail;
    }

    private static int nearestView(float nx, float nz) {
        if (Math.abs(nx) + Math.abs(nz) < 0.0001f) {
            return 0;
        }
        double angle = Math.atan2(nx, nz);
        int view = (int) Math.round(angle / (Math.PI * 2.0) * VIEW_COUNT);
        view %= VIEW_COUNT;
        if (view < 0) {
            view += VIEW_COUNT;
        }
        return view;
    }

    private static boolean validVertex(int vertex, int vertexCount) {
        return vertex >= 0 && vertex < vertexCount;
    }

    private static float positionX(float[] values, int vertex) {
        return values[vertex * 3];
    }

    private static float positionZ(float[] values, int vertex) {
        return values[vertex * 3 + 2];
    }

    private static float normalX(float[] values, int vertex) {
        return values[vertex * 3];
    }

    private static float normalZ(float[] values, int vertex) {
        return values[vertex * 3 + 2];
    }

    private static float clamp01(float value) {
        return clamp(value, 0.0f, 1.0f);
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public static final class AtlasResult {
        private final Bitmap bitmap;
        private final AtlasLayout layout;

        AtlasResult(Bitmap bitmap, AtlasLayout layout) {
            this.bitmap = bitmap;
            this.layout = layout;
        }

        public Bitmap getBitmap() {
            return bitmap;
        }

        public AtlasLayout getLayout() {
            return layout;
        }
    }

    public static final class AtlasLayout {
        public final int cellWidth;
        public final int cellHeight;
        public final int gap;
        public final int atlasWidth;
        public final int atlasHeight;

        AtlasLayout(int cellWidth, int cellHeight, int gap) {
            this.cellWidth = cellWidth;
            this.cellHeight = cellHeight;
            this.gap = gap;
            this.atlasWidth = gap + COLUMNS * (cellWidth + gap);
            this.atlasHeight = gap + ROWS * (cellHeight + gap);
        }

        RectF cellRect(int view) {
            int column = view % COLUMNS;
            int row = view / COLUMNS;
            float left = gap + column * (cellWidth + gap);
            float top = gap + row * (cellHeight + gap);
            return new RectF(left, top, left + cellWidth, top + cellHeight);
        }

        float u(int view, float localU) {
            RectF cell = cellRect(view);
            float padding = 2.0f;
            return (cell.left + padding
                    + clamp01(localU) * Math.max(1.0f, cellWidth - padding * 2.0f))
                    / atlasWidth;
        }

        float v(int view, float localV) {
            RectF cell = cellRect(view);
            float padding = 2.0f;
            return (cell.top + padding
                    + clamp01(localV) * Math.max(1.0f, cellHeight - padding * 2.0f))
                    / atlasHeight;
        }
    }
}
