package com.chasmet.modeliseur3d;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import com.chasmet.modeliseur3d.model.*;
import java.io.*;
import java.nio.*;
import java.security.Permission;
import org.robolectric.util.ReflectionHelpers;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class OfflineWorkshopTest {
    /** Reject outbound sockets while exercising local production code. */
    @SuppressWarnings("removal")
    private static final class NoNetwork implements AutoCloseable {
        private final SecurityManager old=System.getSecurityManager();
        NoNetwork(){System.setSecurityManager(new SecurityManager(){
            @Override public void checkPermission(Permission permission){}
            @Override public void checkConnect(String host,int port){throw new AssertionError("Network forbidden: "+host+":"+port);}
        });}
        @Override public void close(){System.setSecurityManager(old);}
    }
    private Bitmap character(boolean transparent){
        Bitmap image=Bitmap.createBitmap(96,128,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(image);
        c.drawColor(transparent?Color.TRANSPARENT:Color.WHITE);Paint p=new Paint();p.setColor(Color.BLUE);
        c.drawCircle(48,25,16,p);p.setColor(Color.RED);c.drawRect(31,41,65,90,p);
        c.drawRect(12,44,31,64,p);c.drawRect(65,44,83,64,p);p.setColor(Color.GREEN);
        c.drawRect(31,90,44,120,p);c.drawRect(52,90,65,120,p);return image;
    }
    @Test public void localImageCreatesRealGlbWithoutServerOrNativeOnnx() throws Exception {
        try(NoNetwork forbidden=new NoNetwork()){
        Bitmap image=character(true);OfflineImageVolume.Result result=OfflineImageVolume.generate(image,112,40,.2f,true,null);
        assertTrue(result.mesh.getTriangleCount()>100);assertTrue(result.mesh.getTriangleCount()<85000);
        assertTrue(result.method.contains("Transparence"));
        // Native PNG export + standard GLB validated/rendered by the following CI job.
        File folder=new File("build/offline-fixture");assertTrue(folder.isDirectory()||folder.mkdirs());
        File glb=new File(folder,"00000000000000000000000000000001.glb");ExternalViewerGlbExporter.write(glb,result.mesh,result.texture);
        try(RandomAccessFile f=new RandomAccessFile(glb,"r")){byte[] header=new byte[12];f.readFully(header);ByteBuffer b=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(0x46546c67,b.getInt());assertEquals(2,b.getInt());assertEquals(glb.length(),b.getInt());}
        assertTrue(glb.length()<8*1024*1024);image.recycle();result.texture.recycle();
        }
    }
    @Test public void bundledAiReallyRunsOfflineOnCpu() throws Exception {
        Bitmap image=character(false);
        try(NoNetwork forbidden=new NoNetwork();AnimeSegmentationEngine engine=new AnimeSegmentationEngine(RuntimeEnvironment.getApplication(),2)){
            AnimeSegmentationEngine.Mask mask=engine.segment(image);
            for(int y=0;y<8;y++)for(int x=0;x<8;x++){
                float probability=mask.sampleNormalized(x/7f,y/7f);assertTrue(Float.isFinite(probability));assertTrue(probability>=0&&probability<=1);
            }
        }finally{image.recycle();}
    }
    @Test public void cutoutCanBeReusedForAllThreeLocalShapesWithoutNetwork() throws Exception {
        Bitmap image=character(false);
        try(OfflineImageVolume.Prepared prepared=OfflineImageVolume.prepare(image,40,null);NoNetwork forbidden=new NoNetwork()){
            for(int shape=0;shape<3;shape++){
                OfflineImageVolume.Result result=OfflineImageVolume.buildPrepared(prepared.bitmap,112,.2f,shape,prepared.method);
                assertTrue(result.mesh.getTriangleCount()>100);assertTrue(result.mesh.getTriangleCount()<85000);
                if(shape==2){File folder=new File("build/offline-fixture");assertTrue(folder.isDirectory()||folder.mkdirs());
                    ExternalViewerGlbExporter.write(new File(folder,"00000000000000000000000000000002.glb"),result.mesh,result.texture);}
                result.texture.recycle();
            }
        }finally{image.recycle();}
    }
    private static Button button(View root,String title){
        if(root instanceof Button&&((Button)root).getText().toString().contains(title))return (Button)root;
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){
            Button found=button(((ViewGroup)root).getChildAt(i),title);if(found!=null)return found;
        }return null;
    }
    private static void finishWork(Offline3DActivity activity)throws Exception{
        java.util.concurrent.ExecutorService worker=ReflectionHelpers.getField(activity,"worker");
        worker.submit(()->{}).get(120,java.util.concurrent.TimeUnit.SECONDS);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }
    @Test public void imageCutoutGenerationGalleryAndRestartRemainUsableWithNoNetwork()throws Exception{
        android.app.Application app=RuntimeEnvironment.getApplication();Bitmap image=character(false);
        File source=new File(app.getFilesDir(),"offline-workshop-image.png");
        try(FileOutputStream out=new FileOutputStream(source)){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));}image.recycle();
        String id;
        try(NoNetwork forbidden=new NoNetwork();var controller=Robolectric.buildActivity(Offline3DActivity.class).setup()){
            Offline3DActivity activity=controller.get();View root=activity.getWindow().getDecorView();
            button(root,"Vérifier le détourage").performClick();finishWork(activity);
            File cutout=new File(app.getFilesDir(),"offline-workshop-cutout.png");assertTrue(cutout.length()>0);long modified=cutout.lastModified();
            button(root,"Générer sur ce téléphone").performClick();finishWork(activity);
            id=app.getSharedPreferences("offline_workshop",0).getString("last","");assertTrue(id.matches("[a-f0-9]{32}"));
            assertTrue(new File(app.getFilesDir(),"cloud_models/"+id+".glb").length()>0);assertEquals(modified,cutout.lastModified());
            assertTrue(button(root,"Exporter mon GLB").isEnabled());assertTrue(button(root,"Ouvrir mon modèle").isEnabled());
        }
        try(NoNetwork forbidden=new NoNetwork();var controller=Robolectric.buildActivity(Offline3DActivity.class).setup()){
            assertEquals(id,ReflectionHelpers.getField(controller.get(),"lastId"));
            button(controller.get().getWindow().getDecorView(),"Ouvrir mon modèle").performClick();
            assertEquals(id,org.robolectric.Shadows.shadowOf(controller.get()).getNextStartedActivity().getStringExtra("job"));
        }
    }
    @Test public void viewerServesBundledCodeAndPrivateGlbLocallyAndBlocksExternalUrls()throws Exception{
        android.app.Application app=RuntimeEnvironment.getApplication();String id="00000000000000000000000000000009";
        File folder=new File(app.getFilesDir(),"cloud_models");assertTrue(folder.isDirectory()||folder.mkdirs());
        try(FileOutputStream out=new FileOutputStream(new File(folder,id+".glb"))){out.write(new byte[]{1,2,3});}
        android.content.Intent intent=new android.content.Intent(app,CloudModelViewerActivity.class).putExtra("job",id);
        try(NoNetwork forbidden=new NoNetwork();var controller=Robolectric.buildActivity(CloudModelViewerActivity.class,intent).setup()){
            android.webkit.WebView viewer=ReflectionHelpers.getField(controller.get(),"view");
            android.webkit.WebViewClient client=org.robolectric.Shadows.shadowOf(viewer).getWebViewClient();
            for(String path:new String[]{"/viewer/index.html","/viewer/viewer.js","/model/"+id+".glb"}){
                android.webkit.WebResourceResponse response=client.shouldInterceptRequest(viewer,request("https://modeliseur.local"+path));
                assertNotNull(response);assertTrue(response.getData().read()>=0);response.getData().close();
            }
            assertEquals(403,client.shouldInterceptRequest(viewer,request("https://modeliseur-trellis-mcp.onrender.com/health")).getStatusCode());
        }
    }
    private static android.webkit.WebResourceRequest request(String url){return new android.webkit.WebResourceRequest(){
        public android.net.Uri getUrl(){return android.net.Uri.parse(url);}public boolean isForMainFrame(){return false;}
        public boolean isRedirect(){return false;}public boolean hasGesture(){return false;}public String getMethod(){return "GET";}
        public java.util.Map<String,String> getRequestHeaders(){return java.util.Collections.emptyMap();}
    };}
    @Test public void plainBackgroundAndThicknessWorkLocallyAndBlankInputIsRejected(){
        Bitmap image=character(false);OfflineImageVolume.Result relief=OfflineImageVolume.generate(image,80,40,.04f,false,null);
        OfflineImageVolume.Result volume=OfflineImageVolume.generate(image,112,40,.3f,true,null);
        float thin=0,deep=0;for(float v:relief.mesh.getPositions())assertTrue(Float.isFinite(v));
        for(int i=2;i<relief.mesh.getPositions().length;i+=3)thin=Math.max(thin,Math.abs(relief.mesh.getPositions()[i]));
        for(int i=2;i<volume.mesh.getPositions().length;i+=3)deep=Math.max(deep,Math.abs(volume.mesh.getPositions()[i]));
        assertTrue(deep>thin*2);assertTrue(volume.method.contains("Fond uni"));
        Bitmap blank=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888);blank.eraseColor(Color.WHITE);
        assertThrows(IllegalArgumentException.class,()->OfflineImageVolume.generate(blank,80,40,.2f,true,null));
        image.recycle();blank.recycle();relief.texture.recycle();volume.texture.recycle();
    }
    @Test public void offlineScreenRetainsSavedModelsAndOriginalFourImageLayout(){
        View original=LayoutInflater.from(RuntimeEnvironment.getApplication()).inflate(R.layout.activity_manual_3d,null);
        for(int id:new int[]{R.id.frontCard,R.id.backCard,R.id.rightCard,R.id.leftCard})assertNotNull(original.findViewById(id));
        try(var controller=Robolectric.buildActivity(Offline3DActivity.class).setup()){
            assertNotNull(controller.get());
            assertFalse(RuntimeEnvironment.getApplication().getFileStreamPath("cloud-connection.json").isFile());
        }
    }
}
