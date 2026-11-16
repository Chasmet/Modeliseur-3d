package com.chasmet.modeliseur3d;

import android.graphics.*;
import com.chasmet.modeliseur3d.model.*;
import java.io.*;
import java.security.Permission;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class TripoSRQualityTest {
    @SuppressWarnings("removal") private static final class NoNetwork implements AutoCloseable {
        private final SecurityManager old=System.getSecurityManager();
        NoNetwork(){System.setSecurityManager(new SecurityManager(){public void checkPermission(Permission permission){}public void checkConnect(String host,int port){throw new AssertionError("Refined TripoSR network forbidden: "+host+":"+port);}});}
        public void close(){System.setSecurityManager(old);}
    }
    private static TripoSREngine.Progress progress(){return new TripoSREngine.Progress(){public void update(String s){System.out.println(s);}public void check(){}};}
    @Test public void fineGridRoundTripKeepsProportionsAndSourcePhotoNeverPaintsTheBack()throws Exception{
        int nx=30,ny=48,nz=64;float[] bounds={-.3f,-.45f,-.6f,.3f,.45f,.6f};float[] density=new float[nx*ny*nz];int[] colors=new int[density.length];
        for(int x=0;x<nx;x++)for(int y=0;y<ny;y++)for(int z=0;z<nz;z++){
            float xx=-.3f+.6f*x/(nx-1),yy=-.45f+.9f*y/(ny-1),zz=-.6f+1.2f*z/(nz-1);int i=(x*ny+y)*nz+z;
            density[i]=TripoSRField.ISO+20*(1-(float)Math.sqrt(xx*xx/.04f+yy*yy/.1225f+zz*zz/.25f));colors[i]=0xff2040e0;
        }
        TripoSRRefinedField field=new TripoSRRefinedField(nx,ny,nz,bounds,density,colors);File file=new File(RuntimeEnvironment.getApplication().getCacheDir(),"quality-grid.bin");field.write(file);field=TripoSRRefinedField.read(file);
        assertEquals(nx,field.nx);assertEquals(ny,field.ny);assertEquals(nz,field.nz);assertArrayEquals(bounds,field.bounds,0);assertEquals(0xff2040e0,field.color(0,0,0));assertTrue(field.probability(0,0,0)>.99);
        Bitmap photo=Bitmap.createBitmap(160,240,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(photo);Paint paint=new Paint();paint.setColor(Color.RED);canvas.drawOval(new RectF(8,8,152,232),paint);paint.setColor(Color.WHITE);canvas.drawRect(50,60,110,90,paint);
        OfflineImageVolume.Result model=null;
        try{
            model=TripoSRSingleViewVolume.buildDetailed(field,photo,true);assertTrue(model.mesh.getTriangleCount()>100);assertTrue(model.texture.getWidth()>=2304);
            float[] p=model.mesh.getPositions(),uv=model.mesh.getTexCoords();float xmin=1,xmax=-1,ymin=1,ymax=-1,zmin=1,zmax=-1;int red=0,blue=0;
            for(int i=0;i<p.length;i+=3){xmin=Math.min(xmin,p[i]);xmax=Math.max(xmax,p[i]);ymin=Math.min(ymin,p[i+1]);ymax=Math.max(ymax,p[i+1]);zmin=Math.min(zmin,p[i+2]);zmax=Math.max(zmax,p[i+2]);
                int vertex=i/3;int c=model.texture.getPixel(Math.min(model.texture.getWidth()-1,(int)(uv[vertex*2]*model.texture.getWidth())),Math.min(model.texture.getHeight()-1,(int)(uv[vertex*2+1]*model.texture.getHeight())));
                if(p[i+2]>.17&&Math.abs(p[i])<.08&&Math.abs(p[i+1])<.2){assertTrue("Photo detail missing on visible front",Color.red(c)>150);red++;}
                if(p[i+2]<-.17&&Math.abs(p[i])<.08&&Math.abs(p[i+1])<.2){assertTrue("Source photo incorrectly painted on hidden back",Color.blue(c)>180&&Color.red(c)<70);blue++;}
            }
            assertTrue(red>20&&blue>20);assertEquals(.7f,xmax-xmin,.04);assertEquals(1f,ymax-ymin,.04);assertEquals(.4f,zmax-zmin,.04);
            java.nio.file.Files.write(file.toPath(),new byte[]{1,2,3});try{TripoSRRefinedField.read(file);fail("Corrupt cache accepted");}catch(IOException expected){}
        }finally{photo.recycle();if(model!=null)model.texture.recycle();file.delete();}
    }
    @Test public void actualSingleImageRefinesTheDecoderAndReusesOneEncoderCache()throws Exception{
        Bitmap image=Bitmap.createBitmap(96,160,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(image);Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setColor(0xffdb4832);c.drawRoundRect(new RectF(24,40,72,150),15,15,p);c.drawRect(39,9,57,50,p);p.setColor(Color.WHITE);c.drawRect(31,70,65,93,p);
        var app=RuntimeEnvironment.getApplication();File cache=new File(app.getCacheDir(),"refined-real.bin");String key="quality-real-"+System.nanoTime();List<String> messages=new ArrayList<>();
        TripoSREngine.Progress callback=new TripoSREngine.Progress(){public void update(String s){messages.add(s);System.out.println(s);}public void check(){}};OfflineImageVolume.Result model=null;
        try{
            TripoSRRefinedField field;
            try(NoNetwork forbidden=new NoNetwork()){
                field=TripoSREngine.reconstructSingleDetailed(app,image,cache,key,128,callback);
            }
            assertEquals(1,messages.stream().filter(s->s.contains("encodage CPU")).count());
            assertEquals(128,Math.max(field.nx,Math.max(field.ny,field.nz)));long modified=cache.lastModified();messages.clear();TripoSRRefinedField again=TripoSREngine.reconstructSingleDetailed(app,image,cache,key,128,callback);
            assertEquals(modified,cache.lastModified());assertEquals(1,messages.size());assertTrue(messages.get(0).contains("cache local"));assertEquals(field.nx,again.nx);
            model=TripoSRSingleViewVolume.buildDetailed(again,image,true);assertTrue(model.mesh.getTriangleCount()>100);for(float f:model.mesh.getPositions())assertTrue(Float.isFinite(f));
            File folder=new File("build/offline-fixture");assertTrue(folder.isDirectory()||folder.mkdirs());ExternalViewerGlbExporter.write(new File(folder,"00000000000000000000000000000007.glb"),model.mesh,model.texture,new org.json.JSONObject().put("appVersion","6.3.3").put("engine","TripoSR").put("inputViews",1).put("localOnly",true).put("textureMode","visible-photo-neural-hidden"));
        }finally{image.recycle();if(model!=null)model.texture.recycle();}
    }
    /** Local-only visual comparison: never commits or uploads the user's image. */
    @Test public void localVisualComparisonWithProvidedImage()throws Exception{
        String path=System.getenv("MODELISEUR_VISUAL_IMAGE");Assume.assumeTrue(path!=null&&new File(path).isFile());Bitmap original=BitmapFactory.decodeFile(path);assertNotNull(original);float scale=Math.min(1,1024f/Math.max(original.getWidth(),original.getHeight()));Bitmap resized=Bitmap.createScaledBitmap(original,Math.round(original.getWidth()*scale),Math.round(original.getHeight()*scale),true);if(resized!=original)original.recycle();
        File cache=new File("build/local-visual/subject-triplanes.bin");assertTrue(cache.getParentFile().isDirectory()||cache.getParentFile().mkdirs());String key=TripoSREngine.CACHE_VERSION+":local-subject-v1:"+Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(new File(path).toPath())));
        try(OfflineImageVolume.Prepared cutout=OfflineImageVolume.prepare(resized,40,null)){
            TripoSRRefinedField field=TripoSREngine.reconstructSingleDetailed(RuntimeEnvironment.getApplication(),cutout.bitmap,cache,key,256,progress());
            OfflineImageVolume.Result model=TripoSRSingleViewVolume.buildDetailed(field,cutout.bitmap,true);
            try{ExternalViewerGlbExporter.write(new File(cache.getParentFile(),"subject-6.3.3.glb"),model.mesh,model.texture,new org.json.JSONObject().put("appVersion","6.3.3").put("inputViews",1).put("textureMode","visible-photo-neural-hidden").put("method",model.method));System.out.println("VISUAL SUBJECT OK: "+model.mesh.getTriangleCount()+" triangles, "+model.method);}finally{model.texture.recycle();}
        }finally{resized.recycle();}
    }
}
