package com.chasmet.modeliseur3d.model;

import android.graphics.Bitmap;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.CancellationException;

/** Four calibrated orthographic photo projections, with CPU depth visibility and seam blending.
 * Kept independent of both original modes and the fast silhouette texture path. */
public final class VisibilityPhotoTexture {
    private static final int DEPTH_SIZE=256;
    private VisibilityPhotoTexture(){}
    private static void check(){if(Thread.currentThread().isInterrupted())throw new CancellationException();}
    private static float clamp(float x){return Math.max(0,Math.min(1,x));}
    public static float u(int view,float x,float z,float fa,float sa){
        return view==0?.5f+x/(2*fa):view==1?.5f-x/(2*fa):view==2?.5f-z/(2*sa):.5f+z/(2*sa);
    }
    public static float depth(int view,float x,float z){return view==0?z:view==1?-z:view==2?x:-x;}
    private static float angle(int view,float nx,float nz){return Math.max(0,view==0?nz:view==1?-nz:view==2?nx:-nx);}

    /** Public small visibility helper also used by quantitative geometry tests. */
    public static final class DepthMaps {
        final float[][] depth=new float[4][],nx=new float[4][],nz=new float[4][];
        final int side;final float fa,sa;
        public DepthMaps(MeshData mesh,int side,float fa,float sa){
            if(side<16||side>512||fa<=0||sa<=0)throw new IllegalArgumentException("Projection invalide.");
            this.side=side;this.fa=fa;this.sa=sa;
            for(int view=0;view<4;view++){
                depth[view]=new float[side*side];Arrays.fill(depth[view],Float.NEGATIVE_INFINITY);
                nx[view]=new float[side*side];nz[view]=new float[side*side];raster(mesh,view);
            }
        }
        private void raster(MeshData mesh,int view){
            float[] p=mesh.getPositions(),n=mesh.getNormals();int[] ids=mesh.getIndices();
            for(int t=0;t<ids.length;t+=3){
                if((t&3071)==0)check();int a=ids[t]*3,b=ids[t+1]*3,c=ids[t+2]*3;
                float ax=u(view,p[a],p[a+2],fa,sa)*(side-1),ay=(1-p[a+1])*.5f*(side-1);
                float bx=u(view,p[b],p[b+2],fa,sa)*(side-1),by=(1-p[b+1])*.5f*(side-1);
                float cx=u(view,p[c],p[c+2],fa,sa)*(side-1),cy=(1-p[c+1])*.5f*(side-1);
                float den=(by-cy)*(ax-cx)+(cx-bx)*(ay-cy);if(Math.abs(den)<1e-8f)continue;
                int x0=Math.max(0,(int)Math.floor(Math.min(ax,Math.min(bx,cx)))),x1=Math.min(side-1,(int)Math.ceil(Math.max(ax,Math.max(bx,cx))));
                int y0=Math.max(0,(int)Math.floor(Math.min(ay,Math.min(by,cy)))),y1=Math.min(side-1,(int)Math.ceil(Math.max(ay,Math.max(by,cy))));
                for(int y=y0;y<=y1;y++)for(int x=x0;x<=x1;x++){
                    float wa=((by-cy)*(x-cx)+(cx-bx)*(y-cy))/den,wb=((cy-ay)*(x-cx)+(ax-cx)*(y-cy))/den,wc=1-wa-wb;
                    if(Math.min(wa,Math.min(wb,wc))<-.001f)continue;
                    float d=wa*depth(view,p[a],p[a+2])+wb*depth(view,p[b],p[b+2])+wc*depth(view,p[c],p[c+2]);int i=y*side+x;
                    if(d>depth[view][i]){depth[view][i]=d;nx[view][i]=wa*n[a]+wb*n[b]+wc*n[c];nz[view][i]=wa*n[a+2]+wb*n[b+2]+wc*n[c+2];}
                }
            }
        }
        public boolean visible(int view,float x,float y,float z){
            float uu=u(view,x,z,fa,sa),vv=(1-y)*.5f;if(uu<0||uu>1||vv<0||vv>1)return false;
            int ix=Math.round(uu*(side-1)),iy=Math.round(vv*(side-1));float d=depth(view,x,z),tolerance=3*Math.max(fa,sa)/side;
            // Neighbourhood covers raster cracks, but never accepts a point behind a measured surface.
            float nearest=Float.NEGATIVE_INFINITY;
            for(int yy=Math.max(0,iy-1);yy<=Math.min(side-1,iy+1);yy++)for(int xx=Math.max(0,ix-1);xx<=Math.min(side-1,ix+1);xx++)nearest=Math.max(nearest,depth[view][yy*side+xx]);
            return Float.isFinite(nearest)&&d>=nearest-tolerance;
        }
    }
    private static final class Photo {
        final int width,height;final int[] pixels,extended;
        Photo(Bitmap image){width=image.getWidth();height=image.getHeight();pixels=new int[width*height];image.getPixels(pixels,0,width,0,0,width,height);extended=OfflineFourViewVolume.extend(image);}
        int sample(float u,float v,boolean allowBorder){
            if(!allowBorder&&(u<0||u>1||v<0||v>1))return 0;
            float x=clamp(u)*(width-1),y=clamp(v)*(height-1);int ix=Math.min(width-2,(int)x),iy=Math.min(height-2,(int)y);x-=ix;y-=iy;
            int[] data=allowBorder?extended:pixels;int a=data[iy*width+ix],b=data[iy*width+ix+1],c=data[(iy+1)*width+ix],d=data[(iy+1)*width+ix+1];int out=0;
            for(int shift=0;shift<=24;shift+=8){float lo=((a>>>shift)&255)*(1-x)+((b>>>shift)&255)*x,hi=((c>>>shift)&255)*(1-x)+((d>>>shift)&255)*x;out|=Math.round(lo*(1-y)+hi*y)<<shift;}
            return out;
        }
    }
    private static int colour(Photo photo,int view,float uu,float vv,float aspect,FourViewCalibration calibration,boolean border){
        float sourceU=calibration.sourceU(view,uu),sourceV=calibration.sourceV(view,vv);
        sourceU=.5f+(sourceU-.5f)*aspect/(photo.width/(float)photo.height);sourceV=(sourceV-.04f)/.92f;
        return photo.sample(sourceU,sourceV,border);
    }
    public static OfflineImageVolume.Result bake(MeshData mesh,Bitmap[] images,float fa,float sa,float scale,FourViewCalibration calibration,String method){
        return bake(mesh,images,fa,sa,scale,calibration,method,TripoQualityOptions.defaults());
    }
    public static OfflineImageVolume.Result bake(MeshData mesh,Bitmap[] images,float fa,float sa,float scale,FourViewCalibration calibration,String method,TripoQualityOptions options){
        int cell=options.effectiveTextureCell();
        Photo[] photos=new Photo[4];for(int v=0;v<4;v++)photos[v]=new Photo(images[v]);
        DepthMaps maps=new DepthMaps(mesh,DEPTH_SIZE,fa,sa*scale);
        float[] p=mesh.getPositions(),n=mesh.getNormals();int[] original=mesh.getIndices();int max=original.length;
        float[] outP=new float[max*3],outN=new float[max*3],uv=new float[max*2];int[] ids=new int[max];int count=0;
        HashMap<Long,Integer> remap=new HashMap<>();
        for(int t=0;t<max;t+=3){
            if((t&3071)==0)check();float nx=0,nz=0,x=0,y=0,z=0;
            for(int k=0;k<3;k++){int i=original[t+k]*3;nx+=n[i];nz+=n[i+2];x+=p[i]/3;y+=p[i+1]/3;z+=p[i+2]/3;}
            int view=Math.abs(nz)>=Math.abs(nx)*.82f?(nz>=0?0:1):(nx>=0?2:3);
            int preferred=view;float best=-1;
            for(int v=0;v<4;v++)if(maps.visible(v,x,y,z)){
                float score=angle(v,nx,nz)+(v==preferred?.08f:0);if(score>best){best=score;view=v;}
            }
            for(int k=0;k<3;k++){
                int source=original[t+k],i=source*3;long key=((long)source<<3)|view;Integer found=remap.get(key);
                if(found!=null){ids[t+k]=found;continue;}
                int dst=count++;remap.put(key,dst);ids[t+k]=dst;System.arraycopy(p,i,outP,dst*3,3);System.arraycopy(n,i,outN,dst*3,3);
                float uu=clamp(u(view,p[i],p[i+2],fa,sa*scale)),vv=clamp((1-p[i+1])*.5f);
                uv[dst*2]=((view%2)*cell+.5f+uu*(cell-1))/(cell*2);uv[dst*2+1]=((view/2)*cell+.5f+vv*(cell-1))/(cell*2);
            }
        }
        Bitmap atlas=Bitmap.createBitmap(cell*2,cell*2,Bitmap.Config.ARGB_8888);
        try{
            int[] row=new int[cell];
            for(int view=0;view<4;view++)for(int py=0;py<cell;py++){
                check();float vv=py/(float)(cell-1);int dy=Math.round(vv*(DEPTH_SIZE-1));
                for(int px=0;px<cell;px++){
                    float uu=px/(float)(cell-1);int di=dy*DEPTH_SIZE+Math.round(uu*(DEPTH_SIZE-1));
                    int primary=colour(photos[view],view,uu,vv,view<2?fa:sa,calibration,true);float dd=maps.depth[view][di];
                    if(!Float.isFinite(dd)){row[px]=primary;continue;}
                    float x=view==0?(2*uu-1)*fa:view==1?(1-2*uu)*fa:view==2?dd:-dd;
                    float z=view==0?dd:view==1?-dd:view==2?(1-2*uu)*sa*scale:(2*uu-1)*sa*scale,y=1-2*vv;
                    float nx=maps.nx[view][di],nz=maps.nz[view][di],weights=0,red=0,green=0,blue=0;
                    for(int v=0;v<4;v++){
                        float a=angle(v,nx,nz);if(a<.10f||!maps.visible(v,x,y,z))continue;
                        int c=colour(photos[v],v,u(v,x,z,fa,sa*scale),vv,v<2?fa:sa,calibration,false);
                        if((c>>>24)<40)continue;
                        float w=a*a*a*a*((c>>>24)/255f)*(v==view?1.15f:1);weights+=w;red+=w*((c>>16)&255);green+=w*((c>>8)&255);blue+=w*(c&255);
                    }
                    row[px]=weights>1e-6f?0xff000000|(Math.round(red/weights)<<16)|(Math.round(green/weights)<<8)|Math.round(blue/weights):primary;
                }
                atlas.setPixels(row,0,cell,(view%2)*cell,(view/2)*cell+py,cell,1);
            }
            return new OfflineImageVolume.Result(new MeshData(Arrays.copyOf(outP,count*3),Arrays.copyOf(outN,count*3),Arrays.copyOf(uv,count*2),ids),atlas,method+" · projections visibles + raccords pondérés · atlas "+(cell*2)+"²");
        }catch(RuntimeException|Error e){atlas.recycle();throw e;}
    }
}
