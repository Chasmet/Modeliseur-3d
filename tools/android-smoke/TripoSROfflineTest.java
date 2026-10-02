package com.chasmet.modeliseur3d;

import android.graphics.*;
import android.widget.*;
import com.chasmet.modeliseur3d.model.*;
import com.chasmet.modeliseur3d.util.OfflineImageImporter;
import java.io.*;
import java.security.Permission;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class TripoSROfflineTest {
    @SuppressWarnings("removal") private static final class NoNetwork implements AutoCloseable {
        private final SecurityManager old=System.getSecurityManager();
        NoNetwork(){System.setSecurityManager(new SecurityManager(){public void checkPermission(Permission permission){}public void checkConnect(String host,int port){throw new AssertionError("TripoSR network forbidden: "+host+":"+port);}});}
        public void close(){System.setSecurityManager(old);}
    }
    /** Four views of a labelled bottle, rendered locally; no user photographs in the repository. */
    private static Bitmap bottle(int view){
        Bitmap image=Bitmap.createBitmap(96,160,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(image);Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        int color=new int[]{0xffdf4138,0xff374bc9,0xff29ad65,0xffe6b22b}[view];
        p.setShader(new LinearGradient(21,0,75,0,new int[]{0xff172c45,color,0xffeff7ff},null,Shader.TileMode.CLAMP));
        c.drawRoundRect(new RectF(20,50,76,151),18,18,p);c.drawOval(new RectF(20,40,76,75),p);c.drawRoundRect(new RectF(37,9,59,65),5,5,p);
        p.setShader(null);p.setColor(0xffdddddd);c.drawRoundRect(new RectF(35,8,61,22),3,3,p);
        p.setColor(color);c.drawRect(26,78,70,112,p);p.setColor(0xfff7f0e0);c.drawCircle(48,95,8+view*2,p);return image;
    }
    @Test public void realFourViewEncoderDecoderAndGlbRunWithAllSocketsForbiddenAndCacheIsReused()throws Exception{
        var app=RuntimeEnvironment.getApplication();Bitmap[] images=new Bitmap[4];File[] caches=new File[4];String[] keys=new String[4];List<String> progress=new ArrayList<>();
        TripoSREngine.Progress callback=new TripoSREngine.Progress(){public void update(String value){progress.add(value);System.out.println(value);}public void check(){}};
        OfflineImageVolume.Result result=null,silhouettes=null;
        try(NoNetwork forbidden=new NoNetwork()){
            for(int i=0;i<4;i++){
                Bitmap raw=bottle(i);try(OfflineImageVolume.Prepared prepared=OfflineImageVolume.prepare(raw,40,null)){images[i]=prepared.bitmap.copy(Bitmap.Config.ARGB_8888,false);}raw.recycle();
                caches[i]=new File(app.getFilesDir(),"triposr-test-"+i+".bin");caches[i].delete();new File(caches[i].getPath()+".key").delete();keys[i]=TripoSREngine.CACHE_VERSION+":bottle:"+i;
            }
            long started=System.nanoTime();TripoSRField[] fields=TripoSREngine.reconstruct(app,images,caches,keys,callback);
            assertEquals(4,progress.stream().filter(s->s.contains("TripoSR IA 3D")).count());
            for(int i=0;i<4;i++){
                assertEquals(64,fields[i].side);assertEquals(8+3L*40*64*64*4,caches[i].length());assertTrue(new File(caches[i].getPath()+".field-64").isFile());
                float min=1,max=0,difference=0;
                for(int x=-4;x<=4;x++)for(int y=-4;y<=4;y++)for(int z=-4;z<=4;z++){
                    float p=fields[i].probability(i,x/5f,y/5f,z/5f);assertTrue(Float.isFinite(p)&&p>=0&&p<=1);min=Math.min(min,p);max=Math.max(max,p);
                    difference+=Math.abs(fields[i].sample(x/5f,y/5f,z/5f)-fields[(i+1)%4].sample(x/5f,y/5f,z/5f));
                }
                assertTrue("No learned inside/outside density: view "+i,max-min>.1f);assertTrue("Photograph ignored by encoder: view "+i,difference>.01f);
            }
            result=TripoSRFourViewVolume.build(images,fields,64,1);assertTrue(result.method.contains("TripoSR"));
            assertTrue(result.mesh.getTriangleCount()>100&&result.mesh.getTriangleCount()<=120000);
            silhouettes=OfflineFourViewVolume.build(images,64,1,null);
            assertFalse("Learned model merely returns the old silhouette hull",Arrays.equals(result.mesh.getPositions(),silhouettes.mesh.getPositions()));
            boolean[] seen=new boolean[4];float[] uv=result.mesh.getTexCoords();
            for(int j=0;j<uv.length;j+=2){assertTrue(uv[j]>=0&&uv[j]<=1&&uv[j+1]>=0&&uv[j+1]<=1);seen[(uv[j]<.5f?0:1)+(uv[j+1]<.5f?0:2)]=true;}
            for(boolean used:seen)assertTrue("One photograph absent from texture",used);
            for(float f:result.mesh.getPositions())assertTrue(Float.isFinite(f));for(float f:result.mesh.getNormals())assertTrue(Float.isFinite(f));
            File folder=new File("build/offline-fixture");assertTrue(folder.isDirectory()||folder.mkdirs());
            ExternalViewerGlbExporter.write(new File(folder,"00000000000000000000000000000005.glb"),result.mesh,result.texture);
            // Cached reuse must perform neither encoder nor decoder work and preserve each file.
            long[] times=new long[4];for(int i=0;i<4;i++)times[i]=caches[i].lastModified();progress.clear();
            TripoSRField[] restored=TripoSREngine.reconstruct(app,images,caches,keys,callback);assertEquals(1,progress.size());assertTrue(progress.get(0).contains("cache local"));
            for(int i=0;i<4;i++)assertEquals(times[i],caches[i].lastModified());
            progress.clear();
            TripoSRField[] high=TripoSREngine.reconstruct(app,images,caches,keys,112,callback);
            for(int i=0;i<4;i++){assertEquals(112,high[i].side);assertEquals(times[i],caches[i].lastModified());assertTrue(new File(caches[i].getPath()+".field-112").isFile());}
            assertTrue(progress.stream().anyMatch(s->s.contains("112³")));
            OfflineImageVolume.Result adjusted=TripoSRFourViewVolume.build(images,high,112,1.1f);adjusted.texture.recycle();
            // One missing grid plus a damaged scene previously returned a null field
            // while the three unaffected views were already cached.
            assertTrue(new File(caches[0].getPath()+".field-64").delete());
            try(FileOutputStream damaged=new FileOutputStream(caches[0])){damaged.write(new byte[]{1,2,3});}
            progress.clear();
            TripoSRField[] repaired=TripoSREngine.reconstruct(app,images,caches,keys,callback);
            for(TripoSRField field:repaired)assertNotNull(field);
            assertEquals(1,progress.stream().filter(s->s.contains("TripoSR IA 3D")).count());
            for(int i=1;i<4;i++)assertEquals(times[i],caches[i].lastModified());
            boolean[][] masks=new boolean[4][96*96];
            for(boolean[] mask:masks)for(int yy=5;yy<91;yy++)for(int xx=15;xx<81;xx++)mask[yy*96+xx]=true;
            FourViewCalibration calibration=new FourViewCalibration(masks,96);
            // Each actually inferred view influences fusion even if it is not the maximum here.
            for(int view=0;view<4;view++){
                float[] values=new float[32*32*32];
                for(int x=0;x<32;x++)for(int y=0;y<32;y++)for(int z=0;z<32;z++)values[(x*32+y)*32+z]=TripoSRField.ISO+(.48f-(float)Math.sqrt(Math.pow(x/15.5-1,2)+Math.pow(y/15.5-1,2)+Math.pow(z/15.5-1,2)))*20;
                TripoSRField[] changed=fields.clone();changed[view]=new TripoSRField(values,32);double delta=0;
                for(int x=-4;x<=4;x++)for(int y=-4;y<=4;y++)for(int z=-4;z<=4;z++)delta+=Math.abs(TripoSRFourViewVolume.combineCalibrated(fields,calibration,x/5f,y/5f,z/5f)-TripoSRFourViewVolume.combineCalibrated(changed,calibration,x/5f,y/5f,z/5f));
                assertTrue("View has no effect on fusion: "+view,delta>.01);
            }
            System.out.println("REAL TRIPOSR FOUR-VIEW OFFLINE OK: "+((System.nanoTime()-started)/1_000_000_000L)+" s, "+result.mesh.getTriangleCount()+" triangles");
        }finally{for(Bitmap image:images)if(image!=null)image.recycle();if(result!=null)result.texture.recycle();if(silhouettes!=null)silhouettes.texture.recycle();}
    }
    @Test public void defaultThirdTabChoosesEmbeddedAiAndRequiresFourPhotos(){
        RuntimeEnvironment.getApplication().getSharedPreferences("offline_workshop",0).edit().clear().commit();
        try(NoNetwork forbidden=new NoNetwork();var controller=Robolectric.buildActivity(Offline3DActivity.class).setup()){
            Offline3DActivity activity=controller.get();Spinner engine=ReflectionHelpers.getField(activity,"engine");CheckBox four=ReflectionHelpers.getField(activity,"fourViews");
            assertEquals(0,engine.getSelectedItemPosition());assertTrue(engine.getSelectedItem().toString().contains("TripoSR"));assertTrue(four.isChecked());assertFalse(four.isEnabled());
            engine.setSelection(1);org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertTrue(four.isEnabled());
        }
    }
    @Test public void screenRotationKeepsTheSameWorkshopAndProjectName(){
        try(NoNetwork forbidden=new NoNetwork();var controller=Robolectric.buildActivity(Offline3DActivity.class).setup()){
            Offline3DActivity activity=controller.get();EditText project=ReflectionHelpers.getField(activity,"projectName");project.setText("Projet conservé");
            var worker=ReflectionHelpers.getField(activity,"worker");
            android.content.res.Configuration config=new android.content.res.Configuration(activity.getResources().getConfiguration());
            config.orientation=android.content.res.Configuration.ORIENTATION_LANDSCAPE;controller.configurationChange(config);
            assertSame(activity,controller.get());assertSame(worker,ReflectionHelpers.getField(controller.get(),"worker"));
            assertEquals("Projet conservé",((EditText)ReflectionHelpers.getField(controller.get(),"projectName")).getText().toString());
        }
    }
    @Test public void bilinearSamplingHasCorrectPlanesChannelsAndZeroBorder(){
        float[] scene=new float[3*40*64*64];for(int p=0;p<3;p++)for(int c=0;c<40;c++)for(int y=0;y<64;y++)for(int x=0;x<64;x++)scene[(p*40+c)*4096+y*64+x]=p*1000+c*10+x+y*2;
        float[] out=new float[120];for(int p=0;p<3;p++)TripoSREngine.samplePlane(scene,p,0,0,out,p*40);
        for(int p=0;p<3;p++)for(int c=0;c<40;c++)assertEquals(p*1000+c*10+94.5f,out[p*40+c],.0001f);
        TripoSREngine.samplePlane(scene,1,-1,-1,out,0);assertEquals(250,out[0],.0001f);
        TripoSREngine.samplePlane(scene,1,2,2,out,0);assertEquals(0,out[0],0);
    }
    @Test public void allExifOrientationsRetainTheSixReferencePixels(){
        int[][] expected={{1,2,3,4,5,6},{3,2,1,6,5,4},{6,5,4,3,2,1},{4,5,6,1,2,3},
            {1,4,2,5,3,6},{4,1,5,2,6,3},{6,3,5,2,4,1},{3,6,2,5,1,4}};
        for(int orientation=1;orientation<=8;orientation++){
            Bitmap original=Bitmap.createBitmap(3,2,Bitmap.Config.ARGB_8888);
            int[] pixels=new int[6];for(int i=0;i<6;i++)pixels[i]=0xff000000|(i+1);original.setPixels(pixels,0,3,0,0,3,2);
            Bitmap corrected=OfflineImageImporter.orient(original,orientation);
            assertEquals(orientation>=5?2:3,corrected.getWidth());assertEquals(orientation>=5?3:2,corrected.getHeight());
            corrected.getPixels(pixels,0,corrected.getWidth(),0,0,corrected.getWidth(),corrected.getHeight());
            for(int i=0;i<6;i++)assertEquals("EXIF "+orientation+" pixel "+i,expected[orientation-1][i],pixels[i]&255);
            corrected.recycle();
        }
    }
    @Test public void transparentCutoutKeepsSoftAlphaAndAnIsolatedPixelDoesNotSkipSegmentation(){
        Bitmap image=Bitmap.createBitmap(32,40,Bitmap.Config.ARGB_8888);
        for(int y=5;y<35;y++)for(int x=5;x<27;x++)image.setPixel(x,y,0xff446688);
        image.setPixel(5,10,0x80446688);assertTrue(OfflineImageVolume.hasUsefulTransparency(image));
        try(OfflineImageVolume.Prepared prepared=OfflineImageVolume.prepare(image,40,null)){
            assertEquals(128,prepared.bitmap.getPixel(2,7)>>>24);
        }finally{image.recycle();}
        Bitmap opaque=Bitmap.createBitmap(32,40,Bitmap.Config.ARGB_8888);opaque.eraseColor(Color.WHITE);opaque.setPixel(0,0,0);
        assertFalse(OfflineImageVolume.hasUsefulTransparency(opaque));opaque.recycle();
    }
    @Test public void projectAndEngineAreStoredInsideTheGlb()throws Exception{
        Bitmap texture=Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888);texture.eraseColor(Color.BLUE);
        MeshData mesh=new MeshData(new float[]{0,0,0,1,0,0,0,1,0},new float[]{0,0,1,0,0,1,0,0,1},new float[]{0,0,1,0,0,1},new int[]{0,1,2});
        File file=new File(RuntimeEnvironment.getApplication().getCacheDir(),"provenance.glb");
        try{
            ExternalViewerGlbExporter.write(file,mesh,texture,new org.json.JSONObject().put("appVersion","6.3.0").put("engine","TripoSR").put("projectName","Objet témoin").put("inputViews",4).put("localOnly",true));
            byte[] bytes=java.nio.file.Files.readAllBytes(file.toPath());java.nio.ByteBuffer buffer=java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            int length=buffer.getInt(12);org.json.JSONObject gltf=new org.json.JSONObject(new String(bytes,20,length,java.nio.charset.StandardCharsets.UTF_8));
            assertTrue(gltf.getJSONObject("asset").getString("generator").contains("6.3.0"));
            assertEquals("Objet témoin",gltf.getJSONObject("extras").getString("projectName"));assertEquals(4,gltf.getJSONObject("extras").getInt("inputViews"));
        }finally{texture.recycle();file.delete();}
    }
}
