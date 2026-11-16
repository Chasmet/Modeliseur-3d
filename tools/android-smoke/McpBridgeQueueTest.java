package com.chasmet.modeliseur3d.mcp;

import android.app.Activity;
import android.content.Intent;
import android.os.Looper;
import com.chasmet.modeliseur3d.Offline3DActivity;
import com.chasmet.modeliseur3d.cloud.CloudApi;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Delivery checks use a fake transport; the actual Android activity intent is exercised. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
@LooperMode(LooperMode.Mode.PAUSED)
public class McpBridgeQueueTest {
    private static final String ID="12345678901234567890123456789012";
    private static final String REF="abcdefabcdefabcdefabcdefabcdefab";
    private static class Transport extends CloudApi {
        volatile String status="";int images;boolean fail;Runnable duringDownload;
        CountDownLatch returned=new CountDownLatch(1);
        Transport()throws Exception{super("https://relay.example","");}
        @Override public JSONObject updateLocalStatus(String command,String state,String message)throws Exception{
            status=state;if("pending".equals(state))returned.countDown();return new JSONObject();
        }
        @Override public void downloadLocalImage(String command,String reference,File output)throws Exception{
            if(fail)throw new IOException("Image invalide");
            images++;output.getParentFile().mkdirs();Files.write(output.toPath(),new byte[]{1});
            if(duringDownload!=null)duringDownload.run();
        }
    }
    private JSONObject command()throws Exception{return new JSONObject().put("id",ID).put("mode","triposr_single")
        .put("references",new JSONArray().put(REF)).put("options",new JSONObject().put("quality","precise").put("smoothing",true));}
    @Test public void nextCommandLaunchesOnlyWhenTheLocalEngineIsIdle()throws Exception{
        try(var controller=Robolectric.buildActivity(Activity.class).setup()){
            Activity activity=controller.get();AtomicBoolean idle=new AtomicBoolean(false);
            try(McpBridgeSession bridge=new McpBridgeSession(activity,null,idle::get)){
                ReflectionHelpers.setField(bridge,"active",true);Transport transport=new Transport();
                bridge.prepareAndLaunch(transport,command());assertEquals(0,transport.images);
                assertNull(shadowOf(activity).getNextStartedActivity());
                idle.set(true);bridge.prepareAndLaunch(transport,command());shadowOf(Looper.getMainLooper()).idle();
                Intent intent=shadowOf(activity).getNextStartedActivity();assertNotNull(intent);
                assertEquals(Offline3DActivity.class.getName(),intent.getComponent().getClassName());
                assertEquals(ID,intent.getStringExtra(Offline3DActivity.EXTRA_MCP_COMMAND_ID));
                assertEquals("precise",intent.getStringExtra(Offline3DActivity.EXTRA_MCP_QUALITY));
                assertEquals("running",transport.status);
                assertFalse(ReflectionHelpers.<Boolean>getField(bridge,"active"));
            }
        }
    }
    @Test public void leavingTheScreenDuringDownloadReturnsTheCommandToTheQueue()throws Exception{
        try(var controller=Robolectric.buildActivity(Activity.class).setup()){
            Activity activity=controller.get();
            try(McpBridgeSession bridge=new McpBridgeSession(activity,null,true)){
                ReflectionHelpers.setField(bridge,"active",true);Transport transport=new Transport();
                transport.duringDownload=bridge::stop;
                bridge.prepareAndLaunch(transport,command());shadowOf(Looper.getMainLooper()).idle();
                assertTrue(transport.returned.await(5,TimeUnit.SECONDS));assertEquals("pending",transport.status);
                assertNull(shadowOf(activity).getNextStartedActivity());
            }
        }
    }
    @Test public void malformedInputsReportFailureAndDoNotOpenTheEngine()throws Exception{
        try(var controller=Robolectric.buildActivity(Activity.class).setup()){
            Activity activity=controller.get();
            try(McpBridgeSession bridge=new McpBridgeSession(activity,null,true)){
                ReflectionHelpers.setField(bridge,"active",true);Transport transport=new Transport();transport.fail=true;
                assertThrows(IOException.class,()->bridge.prepareAndLaunch(transport,command()));
                assertEquals("error",transport.status);assertNull(shadowOf(activity).getNextStartedActivity());
            }
        }
    }
}
