package com.chasmet.modeliseur3d.model;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public final class OfflineMeshFinisherTest {
    private static MeshData sphere(){
        int s=32;float[] field=new float[s*s*s];
        for(int y=0;y<s;y++)for(int x=0;x<s;x++)for(int z=0;z<s;z++){
            float xx=2*x/(float)(s-1)-1,yy=1-2*y/(float)(s-1),zz=2*z/(float)(s-1)-1;
            double r=Math.sqrt(xx*xx+yy*yy+zz*zz);field[(y*s+x)*s+z]=(float)Math.max(0,Math.min(1,.5+(.7-r)*5));
        }
        return OfflineHullMesher.buildField(field,s,s,s);
    }
    private static double volume(MeshData mesh){
        double v=0;float[] p=mesh.getPositions();int[] ids=mesh.getIndices();
        for(int t=0;t<ids.length;t+=3){int a=3*ids[t],b=3*ids[t+1],c=3*ids[t+2];
            v+=(double)p[a]*(p[b+1]*p[c+2]-p[b+2]*p[c+1])+(double)p[a+1]*(p[b+2]*p[c]-p[b]*p[c+2])+(double)p[a+2]*(p[b]*p[c+1]-p[b+1]*p[c]);}
        return v/6;
    }
    private static void closedAndOriented(MeshData mesh){
        Map<Long,Integer> count=new HashMap<>(),direction=new HashMap<>();int[] ids=mesh.getIndices();
        for(int t=0;t<ids.length;t+=3)for(int k=0;k<3;k++){int a=ids[t+k],b=ids[t+(k+1)%3];long key=((long)Math.min(a,b)<<32)|Math.max(a,b);count.merge(key,1,Integer::sum);direction.merge(key,a<b?1:-1,Integer::sum);}
        for(long key:count.keySet()){assertEquals(2,(int)count.get(key));assertEquals(0,(int)direction.get(key));}
        assertTrue(volume(mesh)>0);
        float[] p=mesh.getPositions(),n=mesh.getNormals();
        for(int i=0;i<n.length;i+=3)assertEquals(1,Math.sqrt(n[i]*n[i]+n[i+1]*n[i+1]+n[i+2]*n[i+2]),1e-5);
        for(int t=0;t<ids.length;t+=3){int a=ids[t]*3,b=ids[t+1]*3,c=ids[t+2]*3;
            double nx=(p[b+1]-p[a+1])*(p[c+2]-p[a+2])-(p[b+2]-p[a+2])*(p[c+1]-p[a+1]);
            double ny=(p[b+2]-p[a+2])*(p[c]-p[a])-(p[b]-p[a])*(p[c+2]-p[a+2]);
            double nz=(p[b]-p[a])*(p[c+1]-p[a+1])-(p[b+1]-p[a+1])*(p[c]-p[a]);
            assertTrue(nx*(n[a]+n[b]+n[c])+ny*(n[a+1]+n[b+1]+n[c+1])+nz*(n[a+2]+n[b+2]+n[c+2])>0);
        }
    }
    @Test public void repairsAlternatingWindingAndRecalculatesOutwardNormals(){
        MeshData original=sphere();int[] broken=original.getIndices().clone();
        for(int t=0;t<broken.length;t+=6){int b=broken[t+1];broken[t+1]=broken[t+2];broken[t+2]=b;}
        MeshData source=new MeshData(original.getPositions(),new float[original.getPositions().length],original.getTexCoords(),broken);
        OfflineMeshFinisher.Result result=OfflineMeshFinisher.finish(source,false,.01f);
        assertEquals(1,result.components);assertEquals(0,result.boundaryEdges);assertTrue(result.repairedTriangles>0);
        assertArrayEquals(original.getPositions(),result.mesh.getPositions(),0);assertArrayEquals(broken,source.getIndices());closedAndOriented(result.mesh);
    }
    private static double radialError(float[] p){double error=0;for(int i=0;i<p.length;i+=3){double r=Math.sqrt(p[i]*p[i]+p[i+1]*p[i+1]+p[i+2]*p[i+2]);error+=(r-.7)*(r-.7);}return error;}
    private static float[] bounds(float[] p){float[] b={Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY};for(int i=0;i<p.length;i++){int a=i%3;b[a]=Math.min(b[a],p[i]);b[a+3]=Math.max(b[a+3],p[i]);}return b;}
    @Test public void smoothingReducesPerturbedSphereErrorWithinTheMovementBoundAndKeepsBounds(){
        MeshData mesh=sphere();float[] p=mesh.getPositions();Random random=new Random(2381);
        for(int i=0;i<p.length;i+=3){float scale=1+(random.nextFloat()-.5f)*.012f;for(int a=0;a<3;a++)p[i+a]*=scale;}
        float[] before=p.clone();float maximum=.012f;
        OfflineMeshFinisher.Result result=OfflineMeshFinisher.finish(mesh,true,maximum);float[] after=result.mesh.getPositions();
        assertArrayEquals(before,mesh.getPositions(),0);assertArrayEquals(bounds(before),bounds(after),1e-6f);
        assertTrue(radialError(after)<radialError(before));
        for(int i=0;i<p.length;i+=3){double d=0;for(int a=0;a<3;a++)d+=Math.pow(after[i+a]-before[i+a],2);assertTrue(Math.sqrt(d)<=maximum+1e-6);}
        assertEquals(mesh.getTriangleCount(),result.mesh.getTriangleCount());closedAndOriented(result.mesh);
    }
    @Test public void separatedPartsRemainSeparateAndOpenBoundariesStayPinned(){
        MeshData mesh=new MeshData(new float[]{0,0,0,1,0,0,0,1,0, 2,0,0,3,0,0,2,1,0},new float[18],new float[12],new int[]{0,1,2,3,4,5});
        OfflineMeshFinisher.Result result=OfflineMeshFinisher.finish(mesh,true,.3f);
        assertEquals(2,result.components);assertEquals(6,result.boundaryEdges);assertArrayEquals(mesh.getIndices(),result.mesh.getIndices());assertArrayEquals(mesh.getPositions(),result.mesh.getPositions(),0);
    }
    @Test public void rejectsNonManifoldEdgesWithoutExportingACorruptMesh(){
        MeshData mesh=new MeshData(new float[15],new float[15],new float[10],new int[]{0,1,2,1,0,3,0,1,4});
        assertThrows(IllegalArgumentException.class,()->OfflineMeshFinisher.finish(mesh,false,0));
    }
}
