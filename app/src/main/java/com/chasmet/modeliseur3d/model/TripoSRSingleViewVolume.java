package com.chasmet.modeliseur3d.model;

import android.graphics.Bitmap;
import java.util.concurrent.CancellationException;

/** Native one-image learned shape and RGB. No invented input views or silhouette extrusion. */
public final class TripoSRSingleViewVolume {
    private static final int TILE=6;
    private TripoSRSingleViewVolume(){}
    private static void check(){if(Thread.currentThread().isInterrupted())throw new CancellationException();}

    /** Detailed path retains native proportions; no silhouette extrusion or invented views. */
    public static OfflineImageVolume.Result buildDetailed(TripoSRRefinedField field,Bitmap photo,boolean smoothing){
        return buildDetailed(field,photo,smoothing,TripoQualityOptions.defaults());
    }
    public static OfflineImageVolume.Result buildDetailed(TripoSRRefinedField field,Bitmap photo,boolean smoothing,TripoQualityOptions options){
        return buildDetailed(field,photo,smoothing,options,null,null,0);
    }
    public static OfflineImageVolume.Result buildDetailed(TripoSRRefinedField field,Bitmap photo,boolean smoothing,TripoQualityOptions options,TripoDetailRegion region,OfflineDepthField detailDepth,float detailStrength){
        if(field==null||photo==null||photo.isRecycled())throw new IllegalArgumentException("Image et champ détaillé requis.");
        int w=field.ny,h=field.nz,d=field.nx;MeshData mesh=null;float[] b=field.bounds;
        boolean closeup=region!=null&&detailDepth!=null&&detailStrength>0;int baseBudget=options.baseTriangleBudget(closeup);
        for(int attempt=0;attempt<4;attempt++){
            float[] values=new float[w*h*d];
            for(int y=1;y<h-1;y++){
                check();float yy=b[5]-(b[5]-b[2])*y/(h-1);
                for(int x=1;x<w-1;x++)for(int z=1;z<d-1;z++)values[(y*w+x)*d+z]=field.probability(b[1]+(b[4]-b[1])*x/(w-1),yy,b[0]+(b[3]-b[0])*z/(d-1));
            }
            try{mesh=OfflineHullMesher.buildDetailedField(values,w,h,d,baseBudget);break;}
            catch(OfflineHullMesher.TooComplexException e){w=Math.max(8,Math.round(w*.8f));h=Math.max(8,Math.round(h*.8f));d=Math.max(8,Math.round(d*.8f));}
        }
        if(mesh==null||mesh.getTriangleCount()<24)throw new IllegalArgumentException("Image non exploitable par TripoSR. Vérifie le sujet entier.");
        float[] p=mesh.getPositions();for(int i=0;i<p.length;i+=3){p[i]=b[1]+(p[i]*h/w+1)*.5f*(b[4]-b[1]);p[i+1]=b[2]+(p[i+1]+1)*.5f*(b[5]-b[2]);p[i+2]=b[0]+(p[i+2]*h/d+1)*.5f*(b[3]-b[0]);}
        mesh=TripoSRFourViewVolume.removeTinyComponents(mesh);
        OfflineMeshFinisher.Result finished=OfflineMeshFinisher.finish(mesh,smoothing,.4f*(b[5]-b[2])/(h-1));
        MeshData detailed=finished.mesh;String detailMethod="";
        if(region!=null&&detailDepth!=null){TripoDetailRefiner.Result refinement=TripoDetailRefiner.refine(detailed,photo,region,detailDepth,detailStrength,options.triangleBudget());detailed=refinement.mesh;detailMethod=" · "+refinement.summary();}
        return TripoSRPhotoTexture.bake(detailed,field,photo,"TripoSR IA 3D · 1 image · grille centrée "+field.nx+" × "+field.ny+" × "+field.nz+" · maillage "+w+" × "+h+" × "+d+" · budget "+options.triangleBudget()+" triangles"+(closeup?" · réserve gros plan "+(options.triangleBudget()-baseBudget):"")+((w<field.ny||h<field.nz||d<field.nx)?" · grille réduite pour respecter le budget de triangles":" · détail intégral")+(options.maximumPower?" · puissance maximale":"")+" · CPU local"+(smoothing?" · lissage léger borné":" · sans lissage")+" · "+finished.components+" partie(s) séparée(s) · "+finished.boundaryEdges+" bord(s) ouverts"+detailMethod,options,region);
    }

    public static OfflineImageVolume.Result build(TripoSRField field,boolean smoothing){
        if(field==null||!field.hasColors())throw new IllegalArgumentException("Forme et couleurs TripoSR requises.");
        int side=field.side;
        if(Runtime.getRuntime().maxMemory()<192L*1024*1024)side=Math.min(side,64);
        MeshData mesh=null;
        for(int attempt=0;attempt<3;attempt++){
            float[] values=new float[side*side*side];
            for(int y=1;y<side-1;y++){
                check();float yy=1-2f*y/(side-1);
                for(int x=1;x<side-1;x++)for(int z=1;z<side-1;z++)
                    values[(y*side+x)*side+z]=field.singleProbability(2f*x/(side-1)-1,yy,2f*z/(side-1)-1);
            }
            try{mesh=OfflineHullMesher.buildField(values,side,side,side);break;}
            catch(OfflineHullMesher.TooComplexException e){side=Math.max(32,Math.round(side*.72f));}
        }
        if(mesh==null||mesh.getTriangleCount()<24)throw new IllegalArgumentException("Image non exploitable par TripoSR. Vérifie le détourage et le sujet entier.");
        mesh=TripoSRFourViewVolume.removeTinyComponents(mesh);
        OfflineMeshFinisher.Result finished=OfflineMeshFinisher.finish(mesh,smoothing,.8f/(side-1));
        return texture(finished.mesh,field,"TripoSR IA 3D · 1 image · forme et couleurs neuronales estimées · "+field.side+"³ · CPU local"+(smoothing?" · lissage léger borné":" · sans lissage")+" · "+finished.components+" partie(s) séparée(s)");
    }

    /** Small padded triangle tiles preserve learned vertex colors in the existing textured GLB path. */
    private static OfflineImageVolume.Result texture(MeshData mesh,TripoSRField field,String method){
        int[] original=mesh.getIndices();int triangles=original.length/3;
        int columns=(int)Math.ceil(Math.sqrt(triangles)),rows=(triangles+columns-1)/columns;
        int width=columns*TILE,height=rows*TILE;
        float[] sourceP=mesh.getPositions(),sourceN=mesh.getNormals();
        float[] p=new float[original.length*3],n=new float[p.length],uv=new float[original.length*2];int[] ids=new int[original.length];
        Bitmap atlas=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        try{
            int[] tile=new int[TILE*TILE],colors=new int[3];
            int[] tx={1,4,1},ty={1,1,4};
            for(int t=0;t<triangles;t++){
                if((t&255)==0)check();int x=t%columns*TILE,y=t/columns*TILE;
                for(int k=0;k<3;k++){
                    int dst=t*3+k,src=original[dst]*3;ids[dst]=dst;
                    System.arraycopy(sourceP,src,p,dst*3,3);System.arraycopy(sourceN,src,n,dst*3,3);
                    uv[dst*2]=(x+tx[k]+.5f)/width;uv[dst*2+1]=(y+ty[k]+.5f)/height;
                    colors[k]=field.singleColor(sourceP[src],sourceP[src+1],sourceP[src+2]);
                }
                for(int yy=0;yy<TILE;yy++)for(int xx=0;xx<TILE;xx++){
                    float wb=Math.max(0,(xx-1)/3f),wc=Math.max(0,(yy-1)/3f),wa=Math.max(0,1-wb-wc),sum=wa+wb+wc;
                    int r=Math.round((((colors[0]>>>16)&255)*wa+((colors[1]>>>16)&255)*wb+((colors[2]>>>16)&255)*wc)/sum);
                    int g=Math.round((((colors[0]>>>8)&255)*wa+((colors[1]>>>8)&255)*wb+((colors[2]>>>8)&255)*wc)/sum);
                    int b=Math.round(((colors[0]&255)*wa+(colors[1]&255)*wb+(colors[2]&255)*wc)/sum);
                    tile[yy*TILE+xx]=0xff000000|(r<<16)|(g<<8)|b;
                }
                atlas.setPixels(tile,0,TILE,x,y,TILE,TILE);
            }
            return new OfflineImageVolume.Result(new MeshData(p,n,uv,ids),atlas,method+" · texture RGB neuronale "+width+" × "+height);
        }catch(RuntimeException|Error e){atlas.recycle();throw e;}
    }
}
