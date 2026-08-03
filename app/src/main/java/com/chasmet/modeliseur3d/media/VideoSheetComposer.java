package com.chasmet.modeliseur3d.media;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import java.util.List;

/** Assemble les huit trames vidéo en planche 4 x 2 pour le moteur multivue local. */
public final class VideoSheetComposer {
    private static final int COLUMNS = 4;
    private static final int ROWS = 2;
    private static final int CELL_SIZE = 512;
    private static final int CELL_MARGIN = 12;

    private VideoSheetComposer() {
    }

    public static Bitmap compose(List<Bitmap> frames) {
        if (frames == null || frames.size() != VideoFrameExtractor.VIEW_COUNT) {
            throw new IllegalArgumentException("Huit trames vidéo sont requises");
        }

        Bitmap sheet = Bitmap.createBitmap(
                COLUMNS * CELL_SIZE,
                ROWS * CELL_SIZE,
                Bitmap.Config.ARGB_8888
        );
        Canvas canvas = new Canvas(sheet);
        canvas.drawColor(Color.WHITE);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        for (int index = 0; index < frames.size(); index++) {
            Bitmap frame = frames.get(index);
            if (frame == null || frame.isRecycled()) {
                sheet.recycle();
                throw new IllegalArgumentException(
                        "Trame vidéo invalide à la position " + (index + 1)
                );
            }
            int column = index % COLUMNS;
            int row = index / COLUMNS;
            RectF cell = new RectF(
                    column * CELL_SIZE + CELL_MARGIN,
                    row * CELL_SIZE + CELL_MARGIN,
                    (column + 1) * CELL_SIZE - CELL_MARGIN,
                    (row + 1) * CELL_SIZE - CELL_MARGIN
            );
            RectF target = fitCenter(frame.getWidth(), frame.getHeight(), cell);
            canvas.drawBitmap(frame, null, target, paint);
        }
        return sheet;
    }

    private static RectF fitCenter(int width, int height, RectF bounds) {
        float scale = Math.min(
                bounds.width() / Math.max(1, width),
                bounds.height() / Math.max(1, height)
        );
        float targetWidth = Math.max(1.0f, width * scale);
        float targetHeight = Math.max(1.0f, height * scale);
        float left = bounds.centerX() - targetWidth * 0.5f;
        float top = bounds.centerY() - targetHeight * 0.5f;
        return new RectF(left, top, left + targetWidth, top + targetHeight);
    }
}
