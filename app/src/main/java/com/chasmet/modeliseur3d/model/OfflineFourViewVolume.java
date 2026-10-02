package com.chasmet.modeliseur3d.model;

import android.graphics.Bitmap;
import java.util.Arrays;
import java.util.concurrent.CancellationException;

/** Offline four-view approximation, independent of the original character engine. */
public final class OfflineFourViewVolume {
    private static final int CELL=512;
    private OfflineFourViewVolume() {}
    private static void check(){if(Thread.currentThread().isInterrupted())throw new CancellationException();}
    private static float clamp(float v){return Math.max(0,Math.min(1,v));}
    private static float sourceU(float u,float canvasAspect,Bitmap image){return .5f+(u-.5f)*canvasAspect/(image.getWidth()/(float)image.getHeight());}
    private static float sourceV(float v){return (v-.04f)/.92f;}
    private static boolean[] silhouette(Bitmap image,int width,int height,float aspect) {
        boolean[] mask=new boolean[width*height];int iw=image.getWidth(),ih=image.getHeight();
        int[] pixels=new int[iw*ih];image.getPixels(pixels,0,iw,0,0,iw,ih);
        for(int y=1;y<height-1;y++)for(int x=1;x<width-1;x++) {
            float u=sourceU(x/(float)(width-1),aspect,image),v=sourceV(y/(float)(height-1));
            if(u<0||u>1||v<0||v>1)continue;
            int sx=Math.round(u*(iw-1)),sy=Math.round(v*(ih-1));
            mask[y*width+x]=(pixels[sy*iw+sx]>>>24)>40;
        }
        return mask;
    }
    // Extend photograph colours into the transparent border to avoid dark seams.
    private static int[] extend(Bitmap image) {
        int w=image.getWidth(),h=image.getHeight();int[] p=new int[w*h],queue=new int[w*h];
        boolean[] seen=new boolean[p.length];image.getPixels(p,0,w,0,0,w,h);int end=0;
        for(int i=0;i<p.length;i++)if((p[i]>>>24)>40){seen[i]=true;p[i]|=0xff000000;queue[end++]=i;}
        if(end==0)throw new IllegalArgumentException("Une vue ne contient aucun objet détouré.");
        for(int start=0;start<end;start++) {
            int i=queue[start],x=i%w,y=i/w;
            if(x>0&&!seen[i-1]){seen[i-1]=true;p[i-1]=p[i];queue[end++]=i-1;}
            if(x<w-1&&!seen[i+1]){seen[i+1]=true;p[i+1]=p[i];queue[end++]=i+1;}
            if(y>0&&!seen[i-w]){seen[i-w]=true;p[i-w]=p[i];queue[end++]=i-w;}
            if(y<h-1&&!seen[i+w]){seen[i+w]=true;p[i+w]=p[i];queue[end++]=i+w;}
        }
        return p;
    }
    private static Bitmap atlas(Bitmap[] images,float frontAspect,float sideAspect) {
        Bitmap texture=Bitmap.createBitmap(CELL*2,CELL*2,Bitmap.Config.ARGB_8888);
        try {
            int[] row=new int[CELL];
            for(int view=0;view<4;view++) {
                check();Bitmap source=images[view];int sw=source.getWidth(),sh=source.getHeight();int[] colours=extend(source);
                for(int y=0;y<CELL;y++) {
                    float v=clamp(sourceV(y/(float)(CELL-1)));int sy=Math.round(v*(sh-1));
                    for(int x=0;x<CELL;x++) {
                        float u=clamp(sourceU(x/(float)(CELL-1),view<2?frontAspect:sideAspect,source));
                        row[x]=colours[sy*sw+Math.round(u*(sw-1))];
                    }
                    texture.setPixels(row,0,CELL,(view%2)*CELL,(view/2)*CELL+y,CELL,1);
                }
            }
            return texture;
        }catch(RuntimeException|Error e){texture.recycle();throw e;}
    }
    private static float detail(OfflineDepthField field,Bitmap image,float u,float v,float aspect){
        if(field==null)return 1;
        return .985f+.03f*field.sample(clamp(sourceU(u,aspect,image)),clamp(sourceV(v)));
    }
    public static OfflineImageVolume.Result build(Bitmap[] images,int requested,float profileScale,OfflineDepthField[] fields)throws Exception {
        if(images==null||images.length!=4)throw new IllegalArgumentException("Ajoute face, dos, profil droit et profil gauche.");
        if(fields!=null&&fields.length!=4)throw new IllegalArgumentException("Profondeurs invalides.");
        for(Bitmap image:images)if(image==null||image.isRecycled()||image.getWidth()<4||image.getHeight()<4)
            throw new IllegalArgumentException("Une des quatre vues est illisible.");
        // Normalize to a common object height; retain the photographed width/height ratios.
        float fa=Math.max(images[0].getWidth()/(float)images[0].getHeight(),images[1].getWidth()/(float)images[1].getHeight())*1.08f;
        float sa=Math.max(images[2].getWidth()/(float)images[2].getHeight(),images[3].getWidth()/(float)images[3].getHeight())*1.08f;
        float scale=Math.max(.65f,Math.min(1.35f,profileScale));
        int height=Math.max(48,Math.min(112,requested));if(Runtime.getRuntime().maxMemory()<192L*1024*1024)height=Math.min(64,height);
        MeshData mesh=null;int width=0,depth=0;
        for(int attempt=0;attempt<3;attempt++) {
            check();width=Math.max(12,Math.min(128,Math.round(height*fa)));depth=Math.max(12,Math.min(128,Math.round(height*sa)));
            boolean[][] masks={silhouette(images[0],width,height,fa),silhouette(images[1],width,height,fa),silhouette(images[2],depth,height,sa),silhouette(images[3],depth,height,sa)};
            boolean[] occupied=OfflineFourViewHull.intersect(masks,width,height,depth);
            mesh=SmoothHullMesher.build(occupied,width,height,depth,SmoothHullMesher.AtlasLayout.create(width,height,depth,128),3);
            if(mesh.getTriangleCount()<=120000)break;
            mesh=null;height=Math.max(32,Math.round(height*.72f));
        }
        if(mesh==null)throw new IllegalArgumentException("Objet trop complexe. Réduis le détail du modèle.");
        if(mesh.getTriangleCount()<24)throw new IllegalArgumentException("Silhouettes trop fines ou différentes. Vérifie les quatre détourages.");
        float[] p=mesh.getPositions(),n=mesh.getNormals();float sx=fa/(width/(float)height),sz=sa*scale/(depth/(float)height);
        for(int i=0;i<p.length;i+=3) {
            float x=p[i]*sx,y=p[i+1],z=p[i+2]*sz;
            float u=clamp(.5f+x/(2*fa)),v=clamp((1-y)/2),zu=clamp(.5f+z/(2*sa*scale));
            if(fields!=null) {
                // Each photographed surface has its own depth field. Small coordinate-based
                // displacement keeps coincident vertices together and preserves the hull.
                z*=detail(fields[z>=0?0:1],images[z>=0?0:1],z>=0?u:1-u,v,fa);
                x*=detail(fields[x>=0?2:3],images[x>=0?2:3],x>=0?1-zu:zu,v,sa);
            }
            p[i]=x;p[i+1]=y;p[i+2]=z;
            float nx=n[i]/sx,ny=n[i+1],nz=n[i+2]/sz;float length=(float)Math.sqrt(nx*nx+ny*ny+nz*nz);
            n[i]=nx/length;n[i+1]=ny/length;n[i+2]=nz/length;
        }
        // A triangle uses one photograph, with separate UV seams and GLTF top-origin V.
        int[] original=mesh.getIndices();float[] outP=new float[original.length*3],outN=new float[outP.length],uv=new float[original.length*2];int[] indices=new int[original.length];
        for(int t=0;t<original.length;t+=3) {
            float nx=0,nz=0;for(int k=0;k<3;k++){nx+=n[original[t+k]*3];nz+=n[original[t+k]*3+2];}
            int view=Math.abs(nz)>=Math.abs(nx)*.82f?(nz>=0?0:1):(nx>=0?2:3);
            for(int k=0;k<3;k++) {
                int j=t+k,i=original[j]*3;System.arraycopy(p,i,outP,j*3,3);System.arraycopy(n,i,outN,j*3,3);indices[j]=j;
                float x=clamp(.5f+p[i]/(2*fa)),z=clamp(.5f+p[i+2]/(2*sa*scale));
                float u=view==0?x:view==1?1-x:view==2?1-z:z,v=clamp((1-p[i+1])/2);
                // Half-texel inset prevents neighbouring cells from bleeding into seams.
                uv[j*2]=((view%2)*CELL+.5f+u*(CELL-1))/(CELL*2);
                uv[j*2+1]=((view/2)*CELL+.5f+v*(CELL-1))/(CELL*2);
            }
        }
        check();Bitmap texture=atlas(images,fa,sa);
        return new OfflineImageVolume.Result(new MeshData(outP,outN,uv,indices),texture,
            "4 silhouettes et 4 textures réelles • enveloppe approximative"+(fields!=null?" + profondeur IA locale sur les 4 vues":"")+" • CPU local, sans serveur");
    }
}
