package com.chasmet.modeliseur3d.mcp;

import android.content.*;
import android.os.*;
import com.chasmet.modeliseur3d.cloud.CloudApi;
import com.chasmet.modeliseur3d.HomeActivity;
import com.chasmet.modeliseur3d.update.UpdateManager;
import com.chasmet.modeliseur3d.model.TripoSREngine;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ServiceController;
import java.io.File;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
@LooperMode(LooperMode.Mode.PAUSED)
public class McpBackgroundServiceTest {
    private static final String ID="12345678901234567890123456789012";
    private static final String REF="abcdefabcdefabcdefabcdefabcdefab";
    private static Transport transport;
    private static CountDownLatch generating,finish;
    public static class Background extends McpConnectionService {
        @Override protected CloudApi connect() { return transport; }
        @Override protected File generate(JSONObject command,TripoSREngine.Progress callback) throws Exception {
            generating.countDown();
            while (!finish.await(50,TimeUnit.MILLISECONDS)) callback.check();
            callback.check();
            File file=new File(getFilesDir(),"cloud_models/"+ID+".glb");
            file.getParentFile().mkdirs();java.nio.file.Files.write(file.toPath(),new byte[]{1});return file;
        }
    }
    private static class Transport extends CloudApi {
        final AtomicInteger polls=new AtomicInteger();
        final CountDownLatch uploaded=new CountDownLatch(1),disconnected=new CountDownLatch(1),expired=new CountDownLatch(1);
        volatile boolean sent,failUpload,expireCommand;
        Transport() throws Exception { super("https://relay.example",""); }
        @Override public synchronized JSONObject json(String path,JSONObject body) throws Exception {
            if (path.equals("/api/disconnect")) { disconnected.countDown();return new JSONObject(); }
            if (path.equals("/api/poll")) {
                polls.incrementAndGet();JSONArray queue=new JSONArray();
                if (!sent) queue.put(new JSONObject().put("id",ID).put("mode","triposr_single")
                        .put("references",new JSONArray().put(REF)).put("options",new JSONObject()));
                return new JSONObject().put("local_commands",queue);
            }
            return new JSONObject();
        }
        @Override public JSONObject updateLocalStatus(String id,String state,String message) throws Exception {
            sent=true;
            if (expireCommand) { expired.countDown();throw new CloudApi.HttpFailure(404,"Lost relay queue"); }
            return new JSONObject();
        }
        @Override public void downloadLocalImage(String id,String ref,File file) throws Exception {
            file.getParentFile().mkdirs(); java.nio.file.Files.write(file.toPath(),new byte[]{1});
        }
        @Override public JSONObject uploadLocalResult(String id,File file) throws Exception {
            if (failUpload) { failUpload=false;throw new java.io.IOException("Network interrupted"); }
            uploaded.countDown();return new JSONObject();
        }
    }
    @Before public void setup() throws Exception {
        var app=RuntimeEnvironment.getApplication();
        UpdateManager.prefs(app).edit().putBoolean("automatic",false).commit();
        try { androidx.work.WorkManager.getInstance(app); }
        catch (IllegalStateException e) { androidx.work.WorkManager.initialize(app,new androidx.work.Configuration.Builder().build()); }
        app.getSharedPreferences("mcp_background",0).edit().clear().putBoolean("enabled",true).commit();
        File model=new File(app.getFilesDir(),"cloud_models/"+ID+".glb");model.delete();
        generating=new CountDownLatch(1);finish=new CountDownLatch(1);transport=new Transport();
    }
    @After public void cleanup() { RuntimeEnvironment.getApplication().getSharedPreferences("mcp_background",0).edit().clear().commit(); }
    @Test public void heartbeatAndCalculationSurviveClosingTheScreenAndRemovingTheTask() throws Exception {
        ServiceController<Background> controller=Robolectric.buildService(Background.class).create();
        Background service=controller.get();
        try {
            assertEquals(android.app.Service.START_STICKY,service.onStartCommand(new Intent(),0,1));
            assertTrue(generating.await(5,TimeUnit.SECONDS));
            try (var screen=Robolectric.buildActivity(HomeActivity.class).setup()) {
                screen.pause().stop().destroy();
                service.onTaskRemoved(new Intent());
                assertTrue(McpConnectionService.enabled(service));
                int before=transport.polls.get(); service.pollOnce();assertTrue(transport.polls.get()>before);
                assertTrue(McpConnectionService.status(service).contains("téléphone en ligne"));
                assertFalse(McpConnectionService.status(service).contains("ChatGPT MCP : connecté"));
                assertNotNull(shadowOf(service).getLastForegroundNotification());
                finish.countDown();assertTrue(transport.uploaded.await(5,TimeUnit.SECONDS));
            }
        } finally { controller.destroy(); }
    }
    @Test public void disconnectStopsForegroundAndRetainsInterruptedCommand() throws Exception {
        ServiceController<Background> controller=Robolectric.buildService(Background.class).create();
        Background service=controller.get();
        try {
            service.onStartCommand(new Intent(),0,1); assertTrue(generating.await(5,TimeUnit.SECONDS));
            assertEquals(android.app.Service.START_NOT_STICKY,service.onStartCommand(new Intent().setAction(McpConnectionService.DISCONNECT),0,2));
            assertFalse(McpConnectionService.enabled(service));
            assertTrue(transport.disconnected.await(5,TimeUnit.SECONDS));
            assertFalse(service.getSharedPreferences("mcp_background",0).getString("command","").isEmpty());
            assertEquals("ChatGPT MCP : déconnecté",McpConnectionService.status(service));
            assertEquals(1,transport.uploaded.getCount());
        } finally { controller.destroy(); }
    }
    @Test public void savedGlbIsRetriedWithoutReconstructionAfterNetworkLoss() throws Exception {
        var app=RuntimeEnvironment.getApplication();
        File model=new File(app.getFilesDir(),"cloud_models/"+ID+".glb");model.getParentFile().mkdirs();
        java.nio.file.Files.write(model.toPath(),new byte[]{1});transport.failUpload=true;
        ServiceController<Background> controller=Robolectric.buildService(Background.class).create();
        Background service=controller.get();
        try {
            service.onStartCommand(new Intent(),0,1);
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while (transport.failUpload && System.nanoTime()<until) Thread.yield();
            assertFalse(transport.failUpload);
            // Wait for failed attempt to release ownership, then trigger the next poll.
            until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while (transport.uploaded.getCount()!=0 && System.nanoTime()<until) { service.pollOnce();Thread.yield(); }
            assertTrue(transport.uploaded.await(1,TimeUnit.SECONDS));
            assertEquals(1,generating.getCount()); assertTrue(model.isFile());
        } finally { controller.destroy();model.delete(); }
    }
    @Test public void lostRelayCommandDoesNotBlockNewWorkAndKeepsTheSavedGlb() throws Exception {
        var app=RuntimeEnvironment.getApplication();
        File model=new File(app.getFilesDir(),"cloud_models/"+ID+".glb");model.getParentFile().mkdirs();
        java.nio.file.Files.write(model.toPath(),new byte[]{1});transport.expireCommand=true;
        ServiceController<Background> controller=Robolectric.buildService(Background.class).create();
        Background service=controller.get();
        try {
            service.onStartCommand(new Intent(),0,1);assertTrue(transport.expired.await(5,TimeUnit.SECONDS));
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while (!service.getSharedPreferences("mcp_background",0).getString("command","").isEmpty()
                    && System.nanoTime()<until) Thread.yield();
            assertEquals("",service.getSharedPreferences("mcp_background",0).getString("command",""));
            assertTrue(model.isFile());assertEquals(1,generating.getCount());
        } finally { controller.destroy();model.delete(); }
    }

}
