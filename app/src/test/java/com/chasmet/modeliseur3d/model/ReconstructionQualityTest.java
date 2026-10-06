package com.chasmet.modeliseur3d.model;

import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

/** Quantitative references for registration, thin gaps, visibility and meshing. */
public final class ReconstructionQualityTest {
    private static boolean[] character(int size,int shiftX,int shiftY,boolean mirror){
        boolean[] result=new boolean[size*size];
        for(int y=0;y<size;y++)for(int x=0;x<size;x++){
            int xx=mirror?size-1-x:x;xx-=shiftX;int yy=y-shiftY;
            boolean torso=xx>=22&&xx<=41&&yy>=19&&yy<=42;
            boolean head=(xx-31)*(xx-31)+(yy-13)*(yy-13)<=36;
            boolean leftLeg=xx>=23&&xx<=29&&yy>=39&&yy<=57,rightLeg=xx>=35&&xx<=40&&yy>=39&&yy<=57;
            boolean leftArm=xx>=14&&xx<=21&&yy>=23&&yy<=38,rightArm=xx>=42&&xx<=47&&yy>=24&&yy<=40;
            result[y*size+x]=torso||head||leftLeg||rightLeg||leftArm||rightArm;
        }
        return result;
    }
    private static float iou(boolean[] a,boolean[] b){int union=0,intersection=0;for(int i=0;i<a.length;i++){if(a[i]||b[i])union++;if(a[i]&&b[i])intersection++;}return intersection/(float)union;}
    @Test public void maximumGridRemainsClosedAndCacheRoundTripPreservesItsDimensions()throws Exception{
        int nx=320,ny=12,nz=16;float[] density=new float[nx*ny*nz];int[] colors=new int[density.length];
        for(int x=0;x<nx;x++)for(int y=0;y<ny;y++)for(int z=0;z<nz;z++){
            float xx=2f*x/(nx-1)-1,yy=2f*y/(ny-1)-1,zz=2f*z/(nz-1)-1;int i=(x*ny+y)*nz+z;
            density[i]=TripoSRField.ISO+20*(.7f-(float)Math.sqrt(xx*xx+yy*yy+zz*zz));colors[i]=0xff2040e0;
        }
        TripoSRRefinedField field=new TripoSRRefinedField(nx,ny,nz,new float[]{-1,-1,-1,1,1,1},density,colors);
        java.io.File cache=java.io.File.createTempFile("maximum-grid", ".bin");
        try{field.write(cache);TripoSRRefinedField loaded=TripoSRRefinedField.read(cache);assertEquals(320,loaded.nx);assertEquals(field.probability(0,0,0),loaded.probability(0,0,0),0);}
        finally{cache.delete();}
        float[] values=new float[nx*ny*nz];for(int y=0;y<ny;y++)for(int x=0;x<nx;x++)for(int z=0;z<nz;z++)values[(y*nx+x)*nz+z]=field.probability(2f*y/(ny-1)-1,2f*z/(nz-1)-1,2f*x/(nx-1)-1);
        MeshData mesh=OfflineHullMesher.buildDetailedField(values,nx,ny,nz,384000);assertTrue(mesh.getTriangleCount()>24);assertEquals(0,OfflineMeshFinisher.finish(mesh,false,0).boundaryEdges);
    }
    @Test public void smallOppositeViewMisalignmentImprovesSilhouetteIouWithoutInventingTheLegGap(){
        int s=64;boolean[] face=character(s,0,0,false),back=character(s,3,-2,true);
        FourViewCalibration calibration=new FourViewCalibration(new boolean[][]{face,back,face,back},s);
        boolean[] aligned=calibration.alignedMask(1,s,s),mirrored=new boolean[s*s];
        for(int y=0;y<s;y++)for(int x=0;x<s;x++)mirrored[y*s+x]=aligned[y*s+s-1-x];
        assertTrue(calibration.frontAgreement>.94f);assertTrue(iou(face,mirrored)>.94f);
        assertFalse(mirrored[51*s+32]);assertTrue(mirrored[51*s+26]);
    }
    @Test public void signedContourDistancePreservesAnEnclosedOpening(){
        int s=40;boolean[] mask=new boolean[s*s];
        for(int y=5;y<35;y++)for(int x=5;x<35;x++)mask[y*s+x]=!(x>=17&&x<=22&&y>=15&&y<=25);
        float[] distance=FourViewCalibration.distances(mask,s,s);
        assertTrue(distance[20*s+19]<-1.5f);assertTrue(distance[20*s+10]>3);
        assertEquals(distance[20*s+19],FourViewCalibration.interpolate(distance,s,s,19/39f,20/39f),1e-4f);
    }
    @Test public void frontCameraRejectsASurfaceHiddenBehindAnotherSurface(){
        float[] p={-.6f,-.6f,.5f,.6f,-.6f,.5f,.6f,.6f,.5f,-.6f,.6f,.5f,
                   -.6f,-.6f,-.5f,.6f,-.6f,-.5f,.6f,.6f,-.5f,-.6f,.6f,-.5f};
        float[] n=new float[p.length];for(int i=0;i<n.length;i+=3)n[i+2]=i<12?1:-1;
        MeshData mesh=new MeshData(p,n,new float[16],new int[]{0,1,2,0,2,3,4,6,5,4,7,6});
        VisibilityPhotoTexture.DepthMaps maps=new VisibilityPhotoTexture.DepthMaps(mesh,64,1,1);
        assertTrue(maps.visible(0,.1f,.1f,.5f));assertFalse(maps.visible(0,.1f,.1f,-.5f));
        assertTrue(maps.visible(1,.1f,.1f,-.5f));assertFalse(maps.visible(1,.1f,.1f,.5f));
    }
    @Test public void continuousEllipsoidHasClosedSurfaceAndMeasuredProportions(){
        int s=48;float[] field=new float[s*s*s];
        for(int y=0;y<s;y++)for(int x=0;x<s;x++)for(int z=0;z<s;z++){
            float xx=2*x/(float)(s-1)-1,yy=1-2*y/(float)(s-1),zz=2*z/(float)(s-1)-1;
            double q=Math.sqrt(xx*xx/.36+yy*yy/.64+zz*zz/.09);field[(y*s+x)*s+z]=(float)Math.max(0,Math.min(1,.5+(1-q)*5));
        }
        MeshData mesh=OfflineHullMesher.buildField(field,s,s,s);float[] p=mesh.getPositions();
        float[] min={10,10,10},max={-10,-10,-10};for(int i=0;i<p.length;i++) {int a=i%3;min[a]=Math.min(min[a],p[i]);max[a]=Math.max(max[a],p[i]);}
        assertEquals(1.2,max[0]-min[0],.04);assertEquals(1.6,max[1]-min[1],.04);assertEquals(.6,max[2]-min[2],.04);
        Map<Long,Integer> edges=new HashMap<>();int[] ids=mesh.getIndices();
        for(int t=0;t<ids.length;t+=3)for(int k=0;k<3;k++){int a=ids[t+k],b=ids[t+(k+1)%3];edges.merge(((long)Math.min(a,b)<<32)|Math.max(a,b),1,Integer::sum);}
        for(int count:edges.values())assertEquals(2,count);
    }
}
