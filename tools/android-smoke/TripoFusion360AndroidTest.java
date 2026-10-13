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
            assertTrue(result.maximumMove<=.00301f);
            float[] positions=result.mesh.getPositions();for(float v:positions)assertTrue(Float.isFinite(v));
            assertTrue(result.summary().contains("Depth Anything V2"));
        }finally{recycle(images);}
    }
    @Test public void silhouetteBasedGeometryPreservesTwoLegsDespiteMissingNeuralLegs()throws Exception{
        // Neural test fixtures see a central torso but hallucinate NO limbs. Four
        // photographed silhouettes still contain two legs; fusion must not erase them.
        int side=32;float[] density=new float[side*side*side];
        for(int x=0;x<side;x++)for(int y=0;y<side;y++)for(int z=0;z<side;z++){
            float xx=2f*x/(side-1)-1,yy=2f*y/(side-1)-1,zz=2f*z/(side-1)-1;
            density[(x*side+y)*side+z]=(xx*xx+yy*yy+zz*zz<.16f)?
                    TripoSRField.ISO+7:TripoSRField.ISO-12;
        }
        TripoSRField[] learned=new TripoSRField[4];
        for(int i=0;i<4;i++)learned[i]=new TripoSRField(density,side);
        Bitmap[] images=new Bitmap[4];OfflineImageVolume.Result result=null;
        try{
            for(int view=0;view<4;view++){
                Bitmap bitmap=Bitmap.createBitmap(128,192,Bitmap.Config.ARGB_8888);
                android.graphics.Canvas canvas=new android.graphics.Canvas(bitmap);
                android.graphics.Paint paint=new android.graphics.Paint();
                paint.setColor(0xffb5a06c);
                if(view<2){
                    canvas.drawRect(31,46,97,129,paint);
                    canvas.drawRect(31,115,54,179,paint);
                    canvas.drawRect(76,115,98,179,paint);
                    canvas.drawRect(18,56,32,115,paint);
                    canvas.drawRect(96,56,112,115,paint);
                    canvas.drawOval(new android.graphics.RectF(48,12,80,53),paint);
                }else{
                    canvas.drawRect(38,46,90,131,paint);
                    canvas.drawRect(40,114,90,179,paint);
                    canvas.drawRect(31,56,42,115,paint);
                    canvas.drawOval(new android.graphics.RectF(48,12,80,53),paint);
                }
                images[view]=bitmap;
            }
            TripoQualityOptions options=new TripoQualityOptions(512,1,true,false,true);
            result=TripoSRFourViewVolume.buildFusion(images,learned,64,1,true,options,null);
            assertTrue("No photographed legs in model",result.mesh.getTriangleCount()>100);
            float[] vertices=result.mesh.getPositions();int legL=0,legR=0;
            for(int i=0;i<vertices.length;i+=3)if(vertices[i+1]<-.30f){
                if(vertices[i]<-.13f)legL++;if(vertices[i]>.13f)legR++;
            }
            System.out.println("FUSION LEGS: left="+legL+" right="+legR+" vertices="+vertices.length/3+" triangles="+result.mesh.getTriangleCount());
            assertTrue("Left photographed leg erased: "+legL,legL>20);
            assertTrue("Right photographed leg erased: "+legR,legR>20);
            assertTrue(result.method.contains("Tripo Fusion 360"));
        }finally{
            for(Bitmap image:images)if(image!=null)image.recycle();
            if(result!=null)result.texture.recycle();
        }
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
