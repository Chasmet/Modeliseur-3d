package com.chasmet.modeliseur3d.gl;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLSurfaceView;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import com.chasmet.modeliseur3d.model.MeshData;

/** Surface OpenGL V5.2 avec rotation manuelle et automatique. */
public final class ModelGLSurfaceViewV52 extends GLSurfaceView {
    private final ModelRendererV52 renderer;
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private float previousX;
    private float previousY;
    private boolean autoRotation;

    public ModelGLSurfaceViewV52(Context context) {
        super(context);
        setEGLContextClientVersion(3);
        setPreserveEGLContextOnPause(true);
        renderer = new ModelRendererV52();
        setRenderer(renderer);
        setRenderMode(RENDERMODE_WHEN_DIRTY);

        scaleDetector = new ScaleGestureDetector(
                context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        renderer.scale(detector.getScaleFactor());
                        requestRender();
                        return true;
                    }
                }
        );
        gestureDetector = new GestureDetector(
                context,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDoubleTap(MotionEvent event) {
                        resetView();
                        return true;
                    }
                }
        );
    }

    public void setModel(MeshData mesh, Bitmap texture) {
        queueEvent(() -> renderer.setModel(mesh, texture));
        requestRender();
    }

    public void resetView() {
        renderer.resetView();
        requestRender();
    }

    public boolean toggleAutoRotation() {
        autoRotation = !autoRotation;
        renderer.setAutoRotation(autoRotation);
        setRenderMode(autoRotation
                ? RENDERMODE_CONTINUOUSLY
                : RENDERMODE_WHEN_DIRTY);
        requestRender();
        return autoRotation;
    }

    public void stopAutoRotation() {
        autoRotation = false;
        renderer.setAutoRotation(false);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        requestRender();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);
        if (event.getPointerCount() == 1 && !scaleDetector.isInProgress()) {
            float x = event.getX();
            float y = event.getY();
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                stopAutoRotation();
                renderer.rotate(x - previousX, y - previousY);
                requestRender();
            }
            previousX = x;
            previousY = y;
        }
        return true;
    }
}
