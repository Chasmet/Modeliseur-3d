package com.chasmet.modeliseur3d.model;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class OfflineVolumeMesherTest {
    @Test public void depthChangesFrontGeometryAndItsCacheRejectsCorruption()throws Exception{
        boolean[] mask=new boolean[20*30];Arrays.fill(mask,true);float[] values={0,1,0,1};OfflineDepthField field=new OfflineDepthField(values,2,2);
        MeshData mesh=OfflineVolumeMesher.build(mask,20,30,1,.2f,false,field);float min=1,max=0;int front=mesh.getVertexCount()/2;
        for(int i=0;i<front;i++){min=Math.min(min,mesh.getPositions()[i*3+2]);max=Math.max(max,mesh.getPositions()[i*3+2]);}
        assertTrue(max-min>.1f);assertEquals(.5f,field.sample(.5f,.5f),1e-5);
        java.io.File cache=java.io.File.createTempFile("depth-cache",".bin");
        try{field.write(cache);OfflineDepthField saved=OfflineDepthField.read(cache);assertEquals(.5f,saved.sample(.5f,.5f),1e-5);
            try(java.io.RandomAccessFile out=new java.io.RandomAccessFile(cache,"rw")){out.seek(12);out.writeFloat(Float.NaN);}
            assertThrows(java.io.IOException.class,()->OfflineDepthField.read(cache));
        }finally{cache.delete();}
    }
    @Test public void revolutionIsClosedAfterWeldingItsSeamAndKeepsTheBottleNeck(){
        int w=80,h=120;boolean[] mask=new boolean[w*h];
        for(int y=3;y<116;y++)for(int x=(y<35?32:12);x<(y<35?48:68);x++)mask[y*w+x]=true;
        MeshData mesh=OfflineRevolutionMesher.build(mask,w,h,w/(float)h,48);
        assertTrue(mesh.getTriangleCount()<16000);float[] p=mesh.getPositions(),n=mesh.getNormals();int[] welded=new int[mesh.getVertexCount()];
        Map<String,Integer> ids=new HashMap<>();for(int i=0;i<welded.length;i++){
            String key=Float.floatToIntBits(p[i*3])+":"+Float.floatToIntBits(p[i*3+1])+":"+Float.floatToIntBits(p[i*3+2]);
            welded[i]=ids.computeIfAbsent(key,k->ids.size());
            double length=Math.sqrt(n[i*3]*n[i*3]+n[i*3+1]*n[i*3+1]+n[i*3+2]*n[i*3+2]);assertEquals(1,length,1e-5);
        }
        Map<Long,Integer> counts=new HashMap<>(),directions=new HashMap<>();int[] indices=mesh.getIndices();double volume=0;
        for(int i=0;i<indices.length;i+=3){
            for(int e=0;e<3;e++){int a=welded[indices[i+e]],b=welded[indices[i+(e+1)%3]];assertNotEquals(a,b);
                long key=((long)Math.min(a,b)<<32)|Math.max(a,b);counts.merge(key,1,Integer::sum);directions.merge(key,a<b?1:-1,Integer::sum);}
            int a=indices[i]*3,b=indices[i+1]*3,c=indices[i+2]*3;
            volume+=(p[a]*(p[b+1]*p[c+2]-p[b+2]*p[c+1])+p[a+1]*(p[b+2]*p[c]-p[b]*p[c+2])+p[a+2]*(p[b]*p[c+1]-p[b+1]*p[c]))/6;
        }
        for(int count:counts.values())assertEquals(2,count);for(int direction:directions.values())assertEquals(0,direction);assertTrue(volume>0);
        float neck=0,body=0;for(int i=0;i<p.length;i+=3){if(p[i+1]>.55f)neck=Math.max(neck,Math.abs(p[i]));if(p[i+1]<0)body=Math.max(body,Math.abs(p[i]));}
        assertTrue(neck<body*.4f);
        assertThrows(IllegalArgumentException.class,()->OfflineRevolutionMesher.build(new boolean[w*h],w,h,1,48));
    }
    @Test public void closedVolumePreservesHolesAndHasConsistentWinding(){
        int w=20,h=30;boolean[] mask=new boolean[w*h];
        for(int y=2;y<28;y++)for(int x=2;x<18;x++)mask[y*w+x]=!(x>7&&x<12&&y>10&&y<20);
        MeshData mesh=OfflineVolumeMesher.build(mask,w,h,w/(float)h,.2f,true);
        Map<Long,Integer> counts=new HashMap<>(),orientation=new HashMap<>();int[] indices=mesh.getIndices();
        float[] p=mesh.getPositions(),normals=mesh.getNormals();double volume=0;
        for(int i=0;i<indices.length;i+=3){
            for(int e=0;e<3;e++){int a=indices[i+e],b=indices[i+(e+1)%3];long key=((long)Math.min(a,b)<<32)|Math.max(a,b);
                counts.merge(key,1,Integer::sum);orientation.merge(key,a<b?1:-1,Integer::sum);}
            int a=indices[i]*3,b=indices[i+1]*3,c=indices[i+2]*3;
            volume+=(p[a]*(p[b+1]*p[c+2]-p[b+2]*p[c+1])+p[a+1]*(p[b+2]*p[c]-p[b]*p[c+2])+p[a+2]*(p[b]*p[c+1]-p[b+1]*p[c]))/6;
        }
        for(int count:counts.values())assertEquals(2,count);
        for(int direction:orientation.values())assertEquals(0,direction);
        assertTrue(volume>0);for(float n:normals)assertTrue(Float.isFinite(n));
        // No faces may fill the central hole: its midpoint is absent on the front/back.
        for(int i=0;i<p.length;i+=3)assertFalse(Math.abs(p[i])<.01&&Math.abs(p[i+1])<.1);
    }
    @Test public void maximumMobileGridIsBoundedAndRejectsInvalidInputs(){
        boolean[] mask=new boolean[144*144];Arrays.fill(mask,true);
        MeshData mesh=OfflineVolumeMesher.build(mask,144,144,1,.2f,false);
        assertTrue(mesh.getVertexCount()<43000);assertTrue(mesh.getTriangleCount()<85000);
        assertThrows(IllegalArgumentException.class,()->OfflineVolumeMesher.build(new boolean[1],1,1,1,.2f,true));
        assertThrows(IllegalArgumentException.class,()->OfflineVolumeMesher.build(mask,144,144,Float.NaN,.2f,true));
    }
    @Test public void allFourSilhouettesConstrainTheHullAndOppositeViewsAreMirrored(){
        int w=12,h=16,d=10;boolean[][] masks={new boolean[w*h],new boolean[w*h],new boolean[d*h],new boolean[d*h]};
        for(boolean[] mask:masks)Arrays.fill(mask,true);
        boolean[] original=OfflineFourViewHull.intersect(masks,w,h,d);
        for(int view=0;view<4;view++){
            int span=view<2?w:d,index=7*span+3;masks[view][index]=false;
            boolean[] carved=OfflineFourViewHull.intersect(masks,w,h,d);int removed=0;
            for(int k=0;k<original.length;k++)if(original[k]&&!carved[k])removed++;
            assertEquals(view<2?d-2:w-2,removed);
            int x=view==0?3:view==1?w-1-3:4,z=view==2?d-1-3:view==3?3:4;
            assertFalse(carved[(7*w+x)*d+z]);masks[view][index]=true;
        }
        Arrays.fill(masks[2],false);
        assertThrows(IllegalArgumentException.class,()->OfflineFourViewHull.intersect(masks,w,h,d));
        assertThrows(IllegalArgumentException.class,()->OfflineFourViewHull.intersect(masks,129,h,d));
    }
}
