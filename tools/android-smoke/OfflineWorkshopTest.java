package com.chasmet.modeliseur3d;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import com.chasmet.modeliseur3d.model.*;
import java.io.*;
import java.nio.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class OfflineWorkshopTest {
    private Bitmap character(boolean transparent){
        Bitmap image=Bitmap.createBitmap(96,128,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(image);
        c.drawColor(transparent?Color.TRANSPARENT:Color.WHITE);Paint p=new Paint();p.setColor(Color.BLUE);
        c.drawCircle(48,25,16,p);p.setColor(Color.RED);c.drawRect(31,41,65,90,p);
        c.drawRect(12,44,31,64,p);c.drawRect(65,44,83,64,p);p.setColor(Color.GREEN);
        c.drawRect(31,90,44,120,p);c.drawRect(52,90,65,120,p);return image;
    }
    @Test public void localImageCreatesRealGlbWithoutServerOrNativeOnnx() throws Exception {
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
