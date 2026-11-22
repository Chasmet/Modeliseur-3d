package com.chasmet.modeliseur3d.model;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.media.FaceDetector;
import java.util.concurrent.CancellationException;

/**
 * Optional Android system face landmark detection, no remote service or model download.
 * Designed for forward-facing real human portraits; stylized/anime faces may require
 * the existing manual TripoDetailSelector instead.
 */
public final class TripoFaceLocator {
    private TripoFaceLocator() { }

    public static TripoDetailRegion locate(Bitmap image) {
        if (image == null || image.isRecycled()) throw new IllegalArgumentException("Image visage absente.");
        if (Thread.currentThread().isInterrupted()) throw new CancellationException();
        int width = Math.max(2, image.getWidth() & ~1), height = image.getHeight();
        if (height < 4 || width < 4) return null;
        Bitmap prepared = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
        try {
            Canvas canvas = new Canvas(prepared);
            canvas.drawColor(Color.rgb(128,128,128));
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            canvas.drawBitmap(image, new Rect(0,0,image.getWidth(),height), new Rect(0,0,width,height), paint);
            FaceDetector.Face[] found = new FaceDetector.Face[1];
            try {
                int count = new FaceDetector(width,height,1).findFaces(prepared,found);
                if (count <= 0 || found[0] == null) return null;
                PointF middle = new PointF();
                found[0].getMidPoint(middle);
                return fromEyes(middle.x,middle.y,found[0].eyesDistance(),width,height);
            } catch (RuntimeException | LinkageError unsupported) {
                // Some OEM builds omit native FaceDetector. Manual region selection still works.
                return null;
            }
        } finally { prepared.recycle(); }
    }

    /** Stable, bounded eyes-to-face estimate suitable for a hand-adjustable detail rectangle. */
    public static TripoDetailRegion fromEyes(float x,float y,float distance,int width,int height) {
        if (!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(distance)
                ||distance < 2f||width < 4||height < 4) return null;
        float left=clamp((x-1.45f*distance)/width);
        float right=clamp((x+1.45f*distance)/width);
        float top=clamp((y-.85f*distance)/height);
        float bottom=clamp((y+2.10f*distance)/height);
        return right-left >= .04f && bottom-top >= .04f
                ? new TripoDetailRegion(left,top,right,bottom) : null;
    }
    private static float clamp(float x) { return Math.max(0f,Math.min(1f,x)); }
}
