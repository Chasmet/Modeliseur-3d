package com.chasmet.modeliseur3d.model;

import android.graphics.Bitmap;
import java.util.concurrent.CancellationException;

/** Rotated learned volumes fused under the four measured silhouettes, then photo-textured. */
public final class TripoSRFourViewVolume {
    private TripoSRFourViewVolume(){}
    public static float combine(TripoSRField[] fields,float x,float y,float z){
        if(fields==null||fields.length!=4)throw new IllegalArgumentException("Quatre champs IA requis.");
        float sum=0,weight=0,best=0;
        for(int view=0;view<4;view++){
            float visible=view==0?z:view==1?-z:view==2?x:-x,w=.35f+2*Math.max(0,visible);
            float p=fields[view].probability(view,x,y,z);sum+=w*p;weight+=w;best=Math.max(best,p);
        }
        // Preserve a surface supported by one photograph when another single-view estimate is uncertain.
        // Every view still contributes to the continuous average and the maximum.
        return .75f*best+.25f*sum/weight;
    }
    public static OfflineImageVolume.Result build(Bitmap[] images,TripoSRField[] fields,int requested,float profileScale)throws Exception{
        if(images==null||images.length!=4||fields==null||fields.length!=4)throw new IllegalArgumentException("Ajoute les quatre vues du même objet.");
        for(int i=0;i<4;i++)if(images[i]==null||images[i].isRecycled()||fields[i]==null)throw new IllegalArgumentException("Une forme IA est absente.");
        float fa=Math.max(images[0].getWidth()/(float)images[0].getHeight(),images[1].getWidth()/(float)images[1].getHeight())*1.08f;
        float sa=Math.max(images[2].getWidth()/(float)images[2].getHeight(),images[3].getWidth()/(float)images[3].getHeight())*1.08f;
        float scale=Math.max(.65f,Math.min(1.35f,profileScale));int h=Math.max(48,Math.min(112,requested));
        if(Runtime.getRuntime().maxMemory()<192L*1024*1024)h=Math.min(h,64);
        MeshData mesh=null;int w=0,d=0;
        for(int attempt=0;attempt<3;attempt++){
            if(Thread.currentThread().isInterrupted())throw new CancellationException();
            w=Math.max(16,Math.min(128,Math.round(h*fa)));d=Math.max(16,Math.min(128,Math.round(h*sa)));
            boolean[][] masks={OfflineFourViewVolume.silhouette(images[0],w,h,fa),OfflineFourViewVolume.silhouette(images[1],w,h,fa),OfflineFourViewVolume.silhouette(images[2],d,h,sa),OfflineFourViewVolume.silhouette(images[3],d,h,sa)};
            boolean[] hull=OfflineFourViewHull.intersect(masks,w,h,d);float[] values=new float[hull.length];
            for(int y=1;y<h-1;y++){
                if(Thread.currentThread().isInterrupted())throw new CancellationException();
                for(int x=1;x<w-1;x++)for(int z=1;z<d-1;z++){
                    int i=(y*w+x)*d+z;if(hull[i])values[i]=combine(fields,(2f*x/(w-1)-1)/.92f,(1-2f*y/(h-1))/.92f,(2f*z/(d-1)-1)/.92f);
                }
            }
            try{mesh=OfflineHullMesher.buildField(values,w,h,d);break;}
            catch(OfflineHullMesher.TooComplexException e){h=Math.max(32,Math.round(h*.72f));}
        }
        if(mesh==null)throw new IllegalArgumentException("Forme IA trop complexe. Réduis le détail.");
        if(mesh.getTriangleCount()<24)throw new IllegalArgumentException("Les formes IA ne correspondent pas suffisamment. Vérifie la même pose et les quatre détourages, ou choisis Silhouettes.");
        float sx=fa/(w/(float)h),sz=sa*scale/(d/(float)h);float[] p=mesh.getPositions(),n=mesh.getNormals();
        for(int i=0;i<p.length;i+=3){
            p[i]*=sx;p[i+2]*=sz;float nx=n[i]/sx,ny=n[i+1],nz=n[i+2]/sz,len=(float)Math.sqrt(nx*nx+ny*ny+nz*nz);
            n[i]=nx/len;n[i+1]=ny/len;n[i+2]=nz/len;
        }
        return OfflineFourViewVolume.texture(mesh,images,fa,sa,scale,"TripoSR IA 3D embarquée · 4 formes apprises alignées + 4 silhouettes + 4 textures réelles · reconstruction estimée · CPU local, sans serveur");
    }
}
