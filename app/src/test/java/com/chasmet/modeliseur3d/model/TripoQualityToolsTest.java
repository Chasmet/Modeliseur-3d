package com.chasmet.modeliseur3d.model;

import org.junit.Test;
import static org.junit.Assert.*;

public final class TripoQualityToolsTest {
    @Test public void continuousTextureDepthEliminatesNearestPixelStepsOnASlopingSurface(){
        int size=16;float[] depth=new float[size*size],normal=new float[depth.length],out=new float[2];
        for(int y=0;y<size;y++)for(int x=0;x<size;x++){depth[y*size+x]=.01f*x+.02f*y;normal[y*size+x]=.7f;}
        for(int i=1;i<100;i++){
            float u=i/101f,v=.371f;
            assertTrue(SurfaceDepthSampler.sample(depth,normal,size,u,v,.1f,out));
            assertEquals((.01f*u+.02f*v)*(size-1),out[0],1e-6f);assertEquals(.7f,out[1],1e-6f);
        }
    }
    @Test public void interpolationDoesNotAverageForegroundAndHiddenBackgroundLayers(){
        int size=16;float[] depth=new float[size*size],normal=new float[depth.length],out=new float[2];
        for(int y=0;y<size;y++)for(int x=0;x<size;x++){depth[y*size+x]=x<8?.8f:-.8f;normal[y*size+x]=1;}
        assertTrue(SurfaceDepthSampler.sample(depth,normal,size,.49f,.51f,.08f,out));assertEquals(.8f,out[0],1e-6);
        assertTrue(SurfaceDepthSampler.sample(depth,normal,size,.51f,.51f,.08f,out));assertEquals(-.8f,out[0],1e-6);
        java.util.Arrays.fill(depth,Float.NEGATIVE_INFINITY);
        assertFalse(SurfaceDepthSampler.sample(depth,normal,size,.5f,.5f,.1f,out));assertTrue(Float.isNaN(out[0]));
    }
    @Test public void detailAndAtlasRespectActualHeapRatherThanPhoneRam(){
        TripoQualityOptions options=TripoQualityOptions.defaults();
        assertEquals(512,options.effectiveTextureCell(128L*1024*1024));assertEquals(60000,options.triangleBudget(128L*1024*1024));
        assertEquals(768,options.effectiveTextureCell(256L*1024*1024));assertEquals(100000,options.triangleBudget(256L*1024*1024));
        assertEquals(240000,options.triangleBudget(512L*1024*1024));assertEquals(320000,options.triangleBudget(1024L*1024*1024));
        assertEquals(180000,new TripoQualityOptions(512,.5f,false).triangleBudget(1024L*1024*1024));
    }
    @Test public void meshValidationRejectsInvalidExportAndReportsDegenerateFaces(){
        float[] p={0,0,0,1,0,0,0,1,0};float[] n={0,0,1,0,0,1,0,0,1};float[] uv={0,0,1,0,0,1};
        MeshQualityReport report=MeshQualityReport.inspect(new MeshData(p,n,uv,new int[]{0,1,2,0,0,1}));
        assertEquals(1,report.degenerateTriangles);assertEquals(2,report.triangles);
        try{MeshQualityReport.inspect(new MeshData(p,n,uv,new int[]{0,1,9}));fail("Invalid vertex accepted");}catch(IllegalArgumentException expected){}
        uv[0]=Float.NaN;
        try{MeshQualityReport.inspect(new MeshData(p,n,uv,new int[]{0,1,2}));fail("Invalid UV accepted");}catch(IllegalArgumentException expected){}
    }
}
