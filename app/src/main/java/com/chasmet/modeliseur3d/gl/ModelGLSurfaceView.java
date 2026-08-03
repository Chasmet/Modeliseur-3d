package com.chasmet.modeliseur3d.gl;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLSurfaceView;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import com.chasmet.modeliseur3d.model.MeshData;

public final class ModelGLSurfaceView extends GLSurfaceView {
    private final ModelRenderer modelRenderer;
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private float previousX;
    private float previousY;
    private boolean autoRotation;

    public ModelGLSurfaceView(Context context) {
        super(context);
        setEGLContextClientVersion(3);
        setPreserveEGLContextOnPause(true);
        modelRenderer = new ModelRenderer();
        setRenderer(modelRenderer);
        setRenderMode(RENDERMODE_WHEN_DIRTY);

        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        modelRenderer.scale(detector.getScaleFactor());
                        requestRender();
                        return true;
                    }
                });

        gestureDetector = new GestureDetector(context,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDoubleTap(MotionEvent event) {
                        resetView();
                        return true;
                    }
                });
    }

    public void setModel(MeshData mesh, Bitmap texture) {
        queueEvent(() -> modelRenderer.setModel(mesh, texture));
        requestRender();
    }

    public void resetView() {
        queueEvent(modelRenderer::resetView);
        requestRender();
    }

    public boolean toggleAutoRotation() {
        setAutoRotationEnabled(!autoRotation);
        return autoRotation;
    }

    public void stopAutoRotation() {
        setAutoRotationEnabled(false);
    }

    private void setAutoRotationEnabled(boolean enabled) {
        autoRotation = enabled;
        queueEvent(() -> modelRenderer.setAutoRotation(enabled));
        setRenderMode(enabled
                ? RENDERMODE_CONTINUOUSLY
                : RENDERMODE_WHEN_DIRTY);
        requestRender();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);

        if (event.getPointerCount() == 1 && !scaleDetector.isInProgress()) {
            float x = event.getX();
            float y = event.getY();
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                previousX = x;
                previousY = y;
            } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float dx = x - previousX;
                float dy = y - previousY;
                modelRenderer.rotate(dx, dy);
                requestRender();
                previousX = x;
                previousY = y;
            }
        }
        return true;
    }
}
