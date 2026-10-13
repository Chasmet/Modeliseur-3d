package com.chasmet.modeliseur3d;

import android.graphics.Bitmap;
import com.chasmet.modeliseur3d.model.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class TripoFusion360AndroidTest {
    private static MeshData sampleMesh(){
        int side=22;float[] probability=new float[side*side*side];
        for(int y=0;y<side;y++)for(int x=0;x<side;x++)for(int z=0;z<side;z++){
            float xx=(x/(float)(side-1)-.5f)*2,yy=(y/(float)(side-1)-.5f)*2,zz=(z/(float)(side-1)-.5f)*2;
            probability[(y*side+x)*side+z]=(xx*xx+yy*yy+zz*zz<.32f)?.94f:.06f;
        }
        return OfflineHullMesher.buildField(probability,side,side,side);
    }
    private static FourViewCalibration calibration(){
        boolean[][] masks=new boolean[4][96*96];
        for(int y=0;y<96;y++)for(int x=0;x<96;x++)if(Math.pow((x-47.5)/30,2)+Math.pow((y-47.5)/35,2)<1)
            for(int v=0;v<4;v++)masks[v][y*96+x]=true;
        return new FourViewCalibration(masks,96);
    }
    private static Bitmap[] bitmaps(){
        Bitmap[] result=new Bitmap[4];for(int v=0;v<4;v++)result[v]=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);
        return result;
    }
    private static void recycle(Bitmap[] images){for(Bitmap image:images)image.recycle();}
    @Test public void flatDepthNeverChangesTripoSRShape(){
        MeshData mesh=sampleMesh();Bitmap[] images=bitmaps();
        float[] flat=new float[128*128];java.util.Arrays.fill(flat,.5f);
        OfflineDepthField[] maps=new OfflineDepthField[4];
        for(int i=0;i<4;i++)maps[i]=new OfflineDepthField(flat,128,128);
        try{
            TripoFusionDepthRefiner.Result result=TripoFusionDepthRefiner.refine(mesh,maps,images,calibration(),1,1,1);
            assertSame("No depth contrast must not invent geometry",mesh,result.mesh);
            assertEquals(0,result.movedVertices);
            assertEquals(0f,result.maximumMove,0);
        }finally{recycle(images);}
    }
    @Test public void fourDepthMapsNeverMoveVerticesBeyondSafetyLimit(){
        MeshData mesh=sampleMesh();Bitmap[] images=bitmaps();
        float[] gradient=new float[128*128];for(int y=0;y<128;y++)for(int x=0;x<128;x++)
            gradient[y*128+x]=.35f+.3f*x/127f+.1f*(float)Math.sin(x*.2);
        OfflineDepthField[] maps=new OfflineDepthField[4];
        for(int i=0;i<4;i++)maps[i]=new OfflineDepthField(gradient,128,128);
        try{
            TripoFusionDepthRefiner.Result result=TripoFusionDepthRefiner.refine(mesh,maps,images,calibration(),1,1,1);
            assertEquals(mesh.getTriangleCount(),result.mesh.getTriangleCount());
            assertTrue(result.maximumMove<=.01001f);
            float[] positions=result.mesh.getPositions();for(float v:positions)assertTrue(Float.isFinite(v));
            assertTrue(result.summary().contains("Depth Anything V2"));
        }finally{recycle(images);}
    }
    @Test public void invalidDepthInputIsRejectedWithoutAlteringSource(){
        MeshData mesh=sampleMesh();Bitmap[] images=bitmaps();
        try{
            try{TripoFusionDepthRefiner.refine(mesh,new OfflineDepthField[4],images,calibration(),1,1,1);fail("Missing depth map accepted");}
            catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("carte"));}
            assertTrue(mesh.getTriangleCount()>100);
        }finally{recycle(images);}
    }
}
