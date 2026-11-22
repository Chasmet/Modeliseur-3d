package com.chasmet.modeliseur3d;

import android.content.Context;
import android.graphics.*;
import android.view.MotionEvent;
import android.view.View;
import com.chasmet.modeliseur3d.model.TripoDetailRegion;

/** Finger-sized handles and dragging; normalized coordinates refer to the displayed cutout. */
public final class TripoDetailSelector extends View {
    private final Bitmap image;private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final RectF display=new RectF(),selection;private float anchorX,anchorY,lastX,lastY;private int action;
    public TripoDetailSelector(Context context,Bitmap image,TripoDetailRegion region){
        super(context);this.image=image;selection=region==null?new RectF(.28f,.08f,.72f,.28f):new RectF(region.left,region.top,region.right,region.bottom);
        setContentDescription("Encadrer le visage ou le détail : glisser le cadre ou ses coins.");setFocusable(true);
    }
    public TripoDetailRegion region(){return new TripoDetailRegion(selection.left,selection.top,selection.right,selection.bottom);}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);canvas.drawColor(0xffe2e6ec);float scale=Math.min(getWidth()/(float)image.getWidth(),getHeight()/(float)image.getHeight());
        float w=image.getWidth()*scale,h=image.getHeight()*scale;display.set((getWidth()-w)/2,(getHeight()-h)/2,(getWidth()+w)/2,(getHeight()+h)/2);canvas.drawBitmap(image,null,display,paint);
        RectF r=new RectF(display.left+selection.left*w,display.top+selection.top*h,display.left+selection.right*w,display.top+selection.bottom*h);
        paint.setStyle(Paint.Style.FILL);paint.setColor(0x88000000);canvas.drawRect(display.left,display.top,display.right,r.top,paint);canvas.drawRect(display.left,r.bottom,display.right,display.bottom,paint);canvas.drawRect(display.left,r.top,r.left,r.bottom,paint);canvas.drawRect(r.right,r.top,display.right,r.bottom,paint);
        paint.setColor(0xff146fe8);paint.setStrokeWidth(3*getResources().getDisplayMetrics().density);paint.setStyle(Paint.Style.STROKE);canvas.drawRect(r,paint);paint.setStyle(Paint.Style.FILL);
        float radius=7*getResources().getDisplayMetrics().density;for(float x:new float[]{r.left,r.right})for(float y:new float[]{r.top,r.bottom})canvas.drawCircle(x,y,radius,paint);
    }
    private static float clamp(float v){return Math.max(0,Math.min(1,v));}
    @Override public boolean onTouchEvent(MotionEvent event){
        if(display.width()<=0||display.height()<=0)return false;
        float x=clamp((event.getX()-display.left)/display.width()),y=clamp((event.getY()-display.top)/display.height());
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN){
            getParent().requestDisallowInterceptTouchEvent(true);lastX=x;lastY=y;
            float tx=32*getResources().getDisplayMetrics().density/display.width(),ty=32*getResources().getDisplayMetrics().density/display.height();action=0;
            if(Math.abs(x-selection.left)<tx&&Math.abs(y-selection.top)<ty)action=1;
            else if(Math.abs(x-selection.right)<tx&&Math.abs(y-selection.top)<ty)action=2;
            else if(Math.abs(x-selection.right)<tx&&Math.abs(y-selection.bottom)<ty)action=3;
            else if(Math.abs(x-selection.left)<tx&&Math.abs(y-selection.bottom)<ty)action=4;
            else if(selection.contains(x,y))action=5;
            else{action=6;anchorX=x;anchorY=y;selection.set(x,y,x,y);}invalidate();return true;
        }
        if(event.getActionMasked()==MotionEvent.ACTION_MOVE){
            if(action==5){float dx=Math.max(-selection.left,Math.min(1-selection.right,x-lastX)),dy=Math.max(-selection.top,Math.min(1-selection.bottom,y-lastY));selection.offset(dx,dy);}
            else if(action==6)selection.set(Math.min(anchorX,x),Math.min(anchorY,y),Math.max(anchorX,x),Math.max(anchorY,y));
            else{if(action==1||action==4)selection.left=Math.min(x,selection.right-.02f);if(action==2||action==3)selection.right=Math.max(x,selection.left+.02f);if(action==1||action==2)selection.top=Math.min(y,selection.bottom-.02f);if(action==3||action==4)selection.bottom=Math.max(y,selection.top+.02f);}
            lastX=x;lastY=y;invalidate();return true;
        }
        if(event.getActionMasked()==MotionEvent.ACTION_UP){getParent().requestDisallowInterceptTouchEvent(false);performClick();return true;}
        if(event.getActionMasked()==MotionEvent.ACTION_CANCEL){getParent().requestDisallowInterceptTouchEvent(false);return true;}return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
}
