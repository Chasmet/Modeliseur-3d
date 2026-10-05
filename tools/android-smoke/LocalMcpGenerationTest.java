package com.chasmet.modeliseur3d.mcp;

import android.graphics.*;
import com.chasmet.modeliseur3d.model.TripoSREngine;
import java.io.*;
import java.nio.*;
import java.security.Permission;
import java.util.concurrent.CancellationException;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LocalMcpGenerationTest {
    @SuppressWarnings("removal") private static class NoNetwork implements AutoCloseable {
        private final SecurityManager old=System.getSecurityManager();
        NoNetwork() { System.setSecurityManager(new SecurityManager() {
            public void checkPermission(Permission permission) { }
            public void checkConnect(String host,int port) { throw new AssertionError("Local MCP inference accessed network: "+host); }
        }); }
        public void close() { System.setSecurityManager(old); }
    }
    @Test public void contextOnlyRunnerCreatesValidGlbOfflineAndReusesFinishedFile() throws Exception {
        String id="123456789012345678901234567890ab";
        var context=RuntimeEnvironment.getApplication();
        File folder=new File(context.getFilesDir(),"mcp_inputs/"+id);folder.mkdirs();
        File source=new File(folder,"image-0.png");
        File output=new File(context.getFilesDir(),"cloud_models/"+id+".glb");output.delete();
        Bitmap photo=Bitmap.createBitmap(96,160,Bitmap.Config.ARGB_8888);
        Paint paint=new Paint();paint.setColor(Color.RED);Canvas canvas=new Canvas(photo);
        canvas.drawRoundRect(new RectF(22,38,74,152),12,12,paint);canvas.drawOval(new RectF(35,8,61,40),paint);
        try(OutputStream stream=new FileOutputStream(source)) { assertTrue(photo.compress(Bitmap.CompressFormat.PNG,100,stream)); }
        photo.recycle();
        JSONObject command=new JSONObject().put("id",id).put("mode","triposr_single")
                .put("options",new JSONObject().put("quality","fast").put("smoothing",true));
        try {
            try(NoNetwork guard=new NoNetwork()) {
                assertEquals(output,LocalMcpGeneration.generate(context,command,new TripoSREngine.Progress() {
                    public void update(String message) { System.out.println(message); }
                    public void check() { }
                }));
            }
            assertTrue(output.length()>1000);
            try(RandomAccessFile input=new RandomAccessFile(output,"r")) {
                byte[] header=new byte[12];input.readFully(header);ByteBuffer bytes=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
                assertEquals(0x46546c67,bytes.getInt());assertEquals(2,bytes.getInt());assertEquals(output.length(),Integer.toUnsignedLong(bytes.getInt()));
            }
            assertEquals(id,context.getSharedPreferences("offline_workshop",0).getString("last",""));
            source.delete();long modified=output.lastModified();
            assertEquals(output,LocalMcpGeneration.generate(context,command,new TripoSREngine.Progress() {
                public void update(String message) { fail("Finished file was regenerated"); }
                public void check() { fail("Finished file was regenerated"); }
            }));assertEquals(modified,output.lastModified());
        } finally { output.delete(); }
    }
    @Test public void cancellationBeforePreparationDoesNotCreateModel() throws Exception {
        var context=RuntimeEnvironment.getApplication();String id="abcdefabcdefabcdefabcdefabcd0001";
        JSONObject command=new JSONObject().put("id",id).put("mode","triposr_single");
        assertThrows(CancellationException.class,()->LocalMcpGeneration.generate(context,command,new TripoSREngine.Progress() {
            public void update(String value) { }
            public void check() { throw new CancellationException(); }
        }));assertFalse(new File(context.getFilesDir(),"cloud_models/"+id+".glb").exists());
    }
}
