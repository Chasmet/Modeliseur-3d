package com.chasmet.modeliseur3d.model;

import java.util.Locale;
import java.util.concurrent.CancellationException;

/** Checks exported arrays, including UV seams; no false 'holes' counted at UV splits. */
public final class MeshQualityReport {
    public final int vertices,triangles,degenerateTriangles;
    public final float[] minimum,maximum;
    private MeshQualityReport(int v,int t,int d,float[] min,float[] max){vertices=v;triangles=t;degenerateTriangles=d;minimum=min;maximum=max;}
    public static MeshQualityReport inspect(MeshData mesh){
        float[] p=mesh.getPositions(),n=mesh.getNormals(),uv=mesh.getTexCoords();int[] ids=mesh.getIndices();
        if(p.length==0||ids.length==0||ids.length%3!=0)throw new IllegalArgumentException("Maillage vide ou triangles incomplets.");
        float[] min={Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY},max={-Float.POSITIVE_INFINITY,-Float.POSITIVE_INFINITY,-Float.POSITIVE_INFINITY};
        for(int i=0;i<p.length;i++){
            if((i&8191)==0&&Thread.currentThread().isInterrupted())throw new CancellationException();
            if(!Float.isFinite(p[i])||!Float.isFinite(n[i]))throw new IllegalArgumentException("Position ou normale non finie.");
            min[i%3]=Math.min(min[i%3],p[i]);max[i%3]=Math.max(max[i%3],p[i]);
        }
        for(float value:uv)if(!Float.isFinite(value)||value<-.0001f||value>1.0001f)throw new IllegalArgumentException("UV hors texture.");
        int degenerate=0;
        for(int t=0;t<ids.length;t+=3){
            if((t&8191)==0&&Thread.currentThread().isInterrupted())throw new CancellationException();
            for(int k=0;k<3;k++)if(ids[t+k]<0||ids[t+k]>=p.length/3)throw new IllegalArgumentException("Indice de triangle invalide.");
            int a=ids[t]*3,b=ids[t+1]*3,c=ids[t+2]*3;
            double x=p[b]-p[a],y=p[b+1]-p[a+1],z=p[b+2]-p[a+2],xx=p[c]-p[a],yy=p[c+1]-p[a+1],zz=p[c+2]-p[a+2];
            double nx=y*zz-z*yy,ny=z*xx-x*zz,nz=x*yy-y*xx;
            if(nx*nx+ny*ny+nz*nz<1e-24)degenerate++;
        }
        return new MeshQualityReport(p.length/3,ids.length/3,degenerate,min,max);
    }
    public String summary(){return String.format(Locale.FRANCE,"Maillage vérifié : %,d sommets · %,d triangles · %d triangle(s) dégénéré(s) · dimensions %.3f × %.3f × %.3f (unités du modèle)",vertices,triangles,degenerateTriangles,maximum[0]-minimum[0],maximum[1]-minimum[1],maximum[2]-minimum[2]);}
}
