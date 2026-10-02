package com.chasmet.modeliseur3d.model;

import java.util.concurrent.CancellationException;

/** Bounded 2D registration of opposite orthographic silhouettes, entirely on CPU.
 * This is image-plane calibration, not a recovered perspective camera. */
public final class FourViewCalibration {
    private final int size;
    private final boolean[][] masks;
    private final float[] sx={1,1,1,1},sy={1,1,1,1},dx=new float[4],dy=new float[4];
    private final float[][] bounds=new float[4][4];
    public final float frontAgreement,profileAgreement;

    public FourViewCalibration(boolean[][] input,int size){
        if(size<16||size>256||input==null||input.length!=4)throw new IllegalArgumentException("Quatre silhouettes requises.");
        this.size=size;masks=new boolean[4][];
        for(int view=0;view<4;view++){
            if(input[view]==null||input[view].length!=size*size)throw new IllegalArgumentException("Silhouette invalide.");
            masks[view]=input[view].clone();int left=size,top=size,right=-1,bottom=-1;
            for(int y=0;y<size;y++)for(int x=0;x<size;x++)if(masks[view][y*size+x]){
                left=Math.min(left,x);right=Math.max(right,x);top=Math.min(top,y);bottom=Math.max(bottom,y);
            }
            if(right-left<2||bottom-top<2)throw new IllegalArgumentException("Une silhouette est trop fine.");
            bounds[view]=new float[]{left/(float)(size-1),top/(float)(size-1),right/(float)(size-1),bottom/(float)(size-1)};
        }
        frontAgreement=register(0,1);profileAgreement=register(2,3);
    }
    public float sourceU(int view,float u){return .5f+(u-.5f-dx[view])/sx[view];}
    public float sourceV(int view,float v){return .5f+(v-.5f-dy[view])/sy[view];}
    public float fieldU(int view,float u){float[] b=bounds[view];return 2*(sourceU(view,u)-b[0])/(b[2]-b[0])-1;}
    public float fieldY(int view,float v){float[] b=bounds[view];return 1-2*(sourceV(view,v)-b[1])/(b[3]-b[1]);}
    private boolean sample(int view,float u,float v){
        if(u<0||u>1||v<0||v>1)return false;
        return masks[view][Math.round(v*(size-1))*size+Math.round(u*(size-1))];
    }
    private float score(int a,int b,float scaleX,float scaleY,float shiftX,float shiftY){
        int intersection=0,union=0;
        for(int y=0;y<size;y+=2)for(int x=0;x<size;x+=2){
            boolean first=masks[a][y*size+x];
            float u=1-x/(float)(size-1),v=y/(float)(size-1);
            boolean second=sample(b,.5f+(u-.5f-shiftX)/scaleX,.5f+(v-.5f-shiftY)/scaleY);
            if(first&&second)intersection++;if(first||second)union++;
        }
        return union==0?0:intersection/(float)union;
    }
    private float register(int a,int b){
        float baseline=score(a,b,1,1,0,0),best=baseline,penalized=baseline;
        // Small search only. Never stretch a poor or incompatible pose into agreement.
        for(int scale=-2;scale<=2;scale++)for(int y=-4;y<=4;y++)for(int x=-4;x<=4;x++){
            if(Thread.currentThread().isInterrupted())throw new CancellationException();
            float s=1+scale*.02f,xx=x/(float)size,yy=y/(float)size;
            float raw=score(a,b,s,s,xx,yy),value=raw-.003f*(Math.abs(x)+Math.abs(y)+Math.abs(scale));
            if(value>penalized){penalized=value;best=raw;sx[b]=sy[b]=s;dx[b]=xx;dy[b]=yy;}
        }
        if(best-baseline<.012f){sx[b]=sy[b]=1;dx[b]=dy[b]=0;return baseline;}
        return best;
    }
    public boolean[] alignedMask(int view,int width,int height){
        boolean[] result=new boolean[width*height];
        for(int y=1;y<height-1;y++)for(int x=1;x<width-1;x++)result[y*width+x]=sample(view,sourceU(view,x/(float)(width-1)),sourceV(view,y/(float)(height-1)));
        return result;
    }
    /** Signed approximate Euclidean distance in pixels, positive inside the contour. */
    public static float[] distances(boolean[] mask,int width,int height){
        if(mask==null||mask.length!=width*height)throw new IllegalArgumentException("Masque invalide.");
        float[] toInside=distance(mask,width,height,true),toOutside=distance(mask,width,height,false);
        float[] out=new float[mask.length];for(int i=0;i<out.length;i++)out[i]=mask[i]?toOutside[i]-.5f:.5f-toInside[i];
        return out;
    }
    private static float[] distance(boolean[] mask,int w,int h,boolean target){
        float[] d=new float[mask.length];for(int i=0;i<d.length;i++)d[i]=mask[i]==target?0:w+h;
        float diagonal=1.41421356f;
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            int i=y*w+x;if(x>0)d[i]=Math.min(d[i],d[i-1]+1);if(y>0)d[i]=Math.min(d[i],d[i-w]+1);
            if(x>0&&y>0)d[i]=Math.min(d[i],d[i-w-1]+diagonal);if(x+1<w&&y>0)d[i]=Math.min(d[i],d[i-w+1]+diagonal);
        }
        for(int y=h-1;y>=0;y--)for(int x=w-1;x>=0;x--){
            int i=y*w+x;if(x+1<w)d[i]=Math.min(d[i],d[i+1]+1);if(y+1<h)d[i]=Math.min(d[i],d[i+w]+1);
            if(x+1<w&&y+1<h)d[i]=Math.min(d[i],d[i+w+1]+diagonal);if(x>0&&y+1<h)d[i]=Math.min(d[i],d[i+w-1]+diagonal);
        }
        return d;
    }
    public static float interpolate(float[] values,int w,int h,float u,float v){
        if(u<0||u>1||v<0||v>1)return -4;
        float x=u*(w-1),y=v*(h-1);int ix=Math.min(w-2,(int)x),iy=Math.min(h-2,(int)y);x-=ix;y-=iy;
        return (values[iy*w+ix]*(1-x)+values[iy*w+ix+1]*x)*(1-y)+(values[(iy+1)*w+ix]*(1-x)+values[(iy+1)*w+ix+1]*x)*y;
    }
    public float probability(TripoSRField field,int view,float x,float y,float z){
        float u=view==0?(x+1)*.5f:view==1?(1-x)*.5f:view==2?(1-z)*.5f:(z+1)*.5f;
        float horizontal=fieldU(view,u),vertical=fieldY(view,(1-y)*.5f);
        switch(view){
            case 0:return field.probability(view,horizontal,vertical,z/.92f);
            case 1:return field.probability(view,-horizontal,vertical,z/.92f);
            case 2:return field.probability(view,x/.92f,vertical,-horizontal);
            default:return field.probability(view,x/.92f,vertical,horizontal);
        }
    }
}
