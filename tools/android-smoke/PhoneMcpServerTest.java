package com.chasmet.modeliseur3d.mcp;

import android.graphics.*;
import android.util.Base64;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.net.ssl.*;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Actual MCP HTTP requests and durable queue. No Render API or inference mock is needed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PhoneMcpServerTest {
    private PhoneMcpStore store;private PhoneMcpServer server;private int port;private String path;
    @Before public void start() throws Exception {
        PhoneSecretTestProvider.install();
        var app=RuntimeEnvironment.getApplication();app.getSharedPreferences("phone_mcp",0).edit().clear().commit();
        File inputs=new File(app.getFilesDir(),"mcp_inputs");File[] dirs=inputs.listFiles();
        if(dirs!=null)for(File dir:dirs)if(new File(dir,"phone-command.json").isFile()) {
            File[] children=dir.listFiles();if(children!=null)for(File child:children)child.delete();
            dir.delete();new File(app.getFilesDir(),"cloud_models/"+dir.getName()+".glb").delete();
        }
        store=new PhoneMcpStore(app);server=new PhoneMcpServer(app,store);
        port=server.listen(new ServerSocket(),0,false);path="/mcp/"+PhoneMcpSettings.token(app);
    }
    @After public void stop() {server.close();}
    private byte[] request(String verb,String path,String body,String extra,boolean chunked) throws Exception {
        return requestOver(new Socket("127.0.0.1",port),verb,path,body,extra,chunked);
    }
    private byte[] requestOver(Socket client,String verb,String path,String body,String extra,boolean chunked) throws Exception {
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
        try(client) {
            client.setSoTimeout(10000);OutputStream out=client.getOutputStream();
            String headers=verb+" "+path+" HTTP/1.1\r\nHost: localhost:"+port+"\r\nAccept: application/json, text/event-stream\r\nContent-Type: application/json\r\n"+extra
                    +(chunked?"Transfer-Encoding: chunked\r\n":"Content-Length: "+bytes.length+"\r\n")+"\r\n";
            out.write(headers.getBytes(StandardCharsets.US_ASCII));
            if(chunked) {out.write((Integer.toHexString(bytes.length)+"\r\n").getBytes(StandardCharsets.US_ASCII));out.write(bytes);out.write("\r\n0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));}
            else out.write(bytes);out.flush();
            ByteArrayOutputStream response=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=client.getInputStream().read(b))!=-1)response.write(b,0,n);return response.toByteArray();
        }
    }
    private JSONObject rpc(String method,JSONObject params) throws Exception {
        String response=new String(request("POST",path,new JSONObject().put("jsonrpc","2.0").put("id",1).put("method",method).put("params",params).toString(),"MCP-Protocol-Version: 2025-06-18\r\n",false),StandardCharsets.UTF_8);
        assertTrue(response,response.startsWith("HTTP/1.1 200"));JSONObject reply=new JSONObject(response.substring(response.indexOf("\r\n\r\n")+4));assertFalse(reply.toString(),reply.has("error"));return reply.getJSONObject("result");
    }
    private JSONObject tool(String name,JSONObject arguments) throws Exception {return rpc("tools/call",new JSONObject().put("name",name).put("arguments",arguments)).getJSONObject("structuredContent");}
    private String image() throws Exception {
        Bitmap bitmap=Bitmap.createBitmap(40,60,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);Paint p=new Paint();p.setColor(Color.RED);canvas.drawRect(10,5,30,55,p);
        ByteArrayOutputStream out=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,out);bitmap.recycle();return "data:image/png;base64,"+Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP);
    }
    @Test public void closeupOptionsArePersistedAndInvalidRegionsNeverEnqueueAJob()throws Exception{
        JSONArray region=new JSONArray().put(.25).put(.15).put(.75).put(.45);
        JSONObject created=tool("create_model_from_images",new JSONObject().put("images",new JSONArray().put(image())).put("detail_region",region).put("detail_strength",.65));
        JSONObject options=store.get(created.getString("primary_model_id")).getJSONObject("options");assertEquals(region.toString(),options.getJSONArray("detail_region").toString());assertEquals(.65,options.getDouble("detail_strength"),1e-6);
        int before=store.listing().getJSONArray("commands").length();
        JSONObject args=new JSONObject().put("images",new JSONArray().put(image())).put("detail_region",new JSONArray().put(.8).put(.2).put(.3).put(.5));
        String call=new JSONObject().put("jsonrpc","2.0").put("id",1).put("method","tools/call").put("params",new JSONObject().put("name","create_model_from_images").put("arguments",args)).toString();
        assertTrue(new String(request("POST",path,call,"",false),StandardCharsets.UTF_8).contains("-32602"));assertEquals(before,store.listing().getJSONArray("commands").length());
    }
    @Test public void localMcpDefaultsToTheManualPrecisePipelineAndKeepsPngAlpha() throws Exception {
        assertEquals("2025-06-18",rpc("initialize",new JSONObject().put("protocolVersion","2025-06-18")).getString("protocolVersion"));
        assertTrue(tool("application_status",new JSONObject()).getBoolean("phone_online"));
        assertFalse(tool("application_status",new JSONObject()).getBoolean("relay_required"));
        JSONObject created=tool("create_model_from_images",new JSONObject().put("images",new JSONArray().put(image())));
        assertEquals("precise",created.getString("quality"));String id=created.getString("primary_model_id");
        JSONObject command=store.get(id);assertEquals("triposr_single",command.getString("mode"));assertEquals("manual-workshop-v1",command.getJSONObject("options").getString("pipeline"));
        Bitmap bitmap=BitmapFactory.decodeFile(store.image(id,command.getJSONArray("references").getString(0)).getPath());
        try {assertEquals(0,Color.alpha(bitmap.getPixel(0,0)));assertEquals(255,Color.alpha(bitmap.getPixel(20,20)));}finally{bitmap.recycle();}
        assertEquals(1,store.poll().getJSONArray("local_commands").length());
        store.update(id,"running","Test local");assertEquals(0,store.poll().getJSONArray("local_commands").length());
        store=new PhoneMcpStore(RuntimeEnvironment.getApplication());assertEquals("pending",store.get(id).getString("status"));
        PhoneMcpApi api=new PhoneMcpApi(store);assertEquals(id,api.json("/api/poll",null).getJSONArray("local_commands").getJSONObject(0).getString("id"));
    }
    @Test public void savedGlbDownloadsDirectlyAndStopsAcceptingForgedOriginsAndUrls() throws Exception {
        JSONObject created=tool("create_model_from_images",new JSONObject().put("images",new JSONArray().put(image())));String id=created.getString("primary_model_id");
        File file=store.model(id);file.getParentFile().mkdirs();byte[] glb=new byte[]{'g','l','T','F',2,0,0,0,12,0,0,0};try(OutputStream out=new FileOutputStream(file)){out.write(glb);}new PhoneMcpApi(store).uploadLocalResult(id,file);
        String url=tool("model_download",new JSONObject().put("model_id",id)).getString("url");String filePath=new URI(url).getPath();
        byte[] response=request("GET",filePath,"","",false);String text=new String(response,StandardCharsets.ISO_8859_1);assertTrue(text.startsWith("HTTP/1.1 200"));assertArrayEquals(glb,java.util.Arrays.copyOfRange(response,text.indexOf("\r\n\r\n")+4,response.length));
        assertTrue(new String(request("POST",path,"{}","Origin: https://evil.example\r\n",false),StandardCharsets.UTF_8).startsWith("HTTP/1.1 403"));
        assertTrue(new String(request("POST","/mcp/wrong","{}","",false),StandardCharsets.UTF_8).startsWith("HTTP/1.1 404"));
        assertTrue(new String(request("GET",filePath+"wrong","","",false),StandardCharsets.UTF_8).startsWith("HTTP/1.1 403"));
        assertTrue(new String(request("GET",path,"","",false),StandardCharsets.UTF_8).startsWith("HTTP/1.1 405"));
    }
    @Test public void chunkedTransportAndNotificationsFollowStreamableHttpRules() throws Exception {
        String response=new String(request("POST",path,new JSONObject().put("jsonrpc","2.0").put("id",1).put("method","tools/list").toString(),"",true),StandardCharsets.UTF_8);
        assertTrue(response.startsWith("HTTP/1.1 200"));JSONArray tools=new JSONObject(response.substring(response.indexOf("\r\n\r\n")+4)).getJSONObject("result").getJSONArray("tools");assertEquals(6,tools.length());
        assertTrue(new String(request("POST",path,"{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}","",false),StandardCharsets.UTF_8).startsWith("HTTP/1.1 202"));
        assertTrue(new String(request("POST",path,"{}","MCP-Protocol-Version: unsupported\r\n",false),StandardCharsets.UTF_8).startsWith("HTTP/1.1 400"));
    }
    @Test public void importedCertificateServesActualTlsAndRejectsWrongPasswordWithoutReplacingIt() throws Exception {
        var app=RuntimeEnvironment.getApplication();File generated=File.createTempFile("phone-mcp-test-",".p12");generated.delete();
        String password="synthetic-test-only";
        File log=File.createTempFile("phone-mcp-keytool-",".log");
        Process keytool=new ProcessBuilder(System.getProperty("java.home")+"/bin/keytool","-genkeypair","-alias","phone","-keyalg","RSA","-keysize","2048","-validity","2","-dname","CN=localhost","-ext","SAN=dns:localhost","-storetype","PKCS12","-keystore",generated.getPath(),"-storepass",password,"-keypass",password,"-noprompt")
                .redirectErrorStream(true).redirectOutput(log).start();
        try {
            assertTrue("Test certificate generation timed out",keytool.waitFor(30,TimeUnit.SECONDS));assertEquals(0,keytool.exitValue());
            try(InputStream in=new FileInputStream(generated)) {PhoneMcpSettings.importCertificate(app,in,password);}
            long size=PhoneMcpSettings.certificate(app).length();
            assertThrows(Exception.class,()->{try(InputStream in=new FileInputStream(generated)){PhoneMcpSettings.importCertificate(app,in,"wrong-password");}});
            assertEquals(size,PhoneMcpSettings.certificate(app).length());
            java.util.concurrent.atomic.AtomicReference<Exception> failure=new java.util.concurrent.atomic.AtomicReference<>();server.clientErrors=failure::set;
            SSLContext serverTls=PhoneMcpSettings.tls(app);assertNotNull(serverTls);
            port=server.listen(serverTls.getServerSocketFactory().createServerSocket(),0,true);
            KeyStore trust=KeyStore.getInstance("PKCS12");try(InputStream in=new FileInputStream(generated)){trust.load(in,password.toCharArray());}
            TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(trust);
            SSLContext clientTls=SSLContext.getInstance("TLS");clientTls.init(null,tm.getTrustManagers(),null);
            String reply=new String(requestOver(clientTls.getSocketFactory().createSocket("localhost",port),"POST",path,"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}","",false),StandardCharsets.UTF_8);
            assertTrue(reply+"; transport failure: "+failure.get(),reply.startsWith("HTTP/1.1 200"));assertTrue(reply.contains("create_model_from_images"));
            reply=new String(requestOver(clientTls.getSocketFactory().createSocket("localhost",port),"POST","/apps/modeliseur3d/mcp","{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}","",false),StandardCharsets.UTF_8);
            JSONArray safe=new JSONObject(reply.substring(reply.indexOf("\r\n\r\n")+4)).getJSONObject("result").getJSONArray("tools");
            assertEquals(2,safe.length());assertFalse(reply.contains("create_model_from_images"));assertFalse(reply.contains("list_models_and_images"));
            String publicCall="{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"application_status\",\"arguments\":{}}}";
            reply=new String(requestOver(clientTls.getSocketFactory().createSocket("localhost",port),"POST","/apps/modeliseur3d/mcp",publicCall,"",false),StandardCharsets.UTF_8);
            assertTrue(reply.contains("\"isError\":false"));assertFalse(reply.contains("network"));assertFalse(reply.contains("last_client"));assertFalse(reply.contains(PhoneMcpSettings.token(app)));
            reply=new String(requestOver(clientTls.getSocketFactory().createSocket("localhost",port),"POST","/mcp",publicCall.replace("application_status","create_model_from_images"),"",false),StandardCharsets.UTF_8);
            assertTrue(reply.contains("-32602"));assertEquals(0,store.poll().getJSONArray("local_commands").length());
            reply=new String(requestOver(clientTls.getSocketFactory().createSocket("localhost",port),"POST","/mcp",publicCall,"Authorization: Bearer invalid\r\n",false),StandardCharsets.UTF_8);
            assertTrue(reply.startsWith("HTTP/1.1 401"));
            server.pauseTls();assertFalse(server.httpsRunning());
            PhoneMcpSettings.prefs(app).edit().putInt("https_port",port).commit();server.reloadTls();assertTrue(server.httpsRunning());
            reply=new String(requestOver(clientTls.getSocketFactory().createSocket("localhost",PhoneMcpSettings.httpsPort(app)),"GET","/health","","",false),StandardCharsets.UTF_8);
            assertTrue(reply.startsWith("HTTP/1.1 200"));
        } finally {if(keytool.isAlive())keytool.destroyForcibly();generated.delete();log.delete();PhoneMcpSettings.certificate(app).delete();}
    }
    @Test public void healthStatusBearerAndLatestProtocolHaveDistinctAccessRules() throws Exception {
        String health=new String(request("GET","/health","","",false),StandardCharsets.UTF_8);
        assertTrue(health.startsWith("HTTP/1.1 200"));assertFalse(health.contains(PhoneMcpSettings.token(RuntimeEnvironment.getApplication())));
        assertTrue(new String(request("GET","/status","","",false),StandardCharsets.UTF_8).startsWith("HTTP/1.1 401"));
        String authorization="Authorization: Bearer "+PhoneMcpSettings.token(RuntimeEnvironment.getApplication())+"\r\n";
        String status=new String(request("GET","/status","",authorization,false),StandardCharsets.UTF_8);
        assertTrue(status.startsWith("HTTP/1.1 200"));assertTrue(status.contains("\"public_access_verified\":false"));
        String initialize="{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\"}}";
        String canonical=new String(request("POST","/mcp",initialize,authorization+"MCP-Protocol-Version: 2025-11-25\r\n",false),StandardCharsets.UTF_8);
        assertTrue(canonical.startsWith("HTTP/1.1 200"));assertTrue(canonical.contains("2025-11-25"));
    }
    @Test public void rejectsUnexpectedTypesAndUnknownArgumentsBeforeQueuingAJob() throws Exception {
        String call="{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"create_model_from_images\",\"arguments\":{\"images\":[5]}}}";
        String response=new String(request("POST",path,call,"",false),StandardCharsets.UTF_8);
        assertTrue(response.contains("-32602"));assertEquals(0,store.poll().getJSONArray("local_commands").length());
        call="{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"application_status\",\"arguments\":{\"shell\":\"x\"}}}";
        assertTrue(new String(request("POST",path,call,"",false),StandardCharsets.UTF_8).contains("-32602"));
        assertThrows(Exception.class,()->PhoneMcpSettings.validatePublicBase("https://192.168.1.20:8443"));
        assertThrows(Exception.class,()->PhoneMcpSettings.validatePublicBase("https://[fd01::1]:8443"));
    }
    @Test public void migratesExistingTokenWithoutChangingTheUrlAndDetectsTamperedCiphertext() throws Exception {
        var app=RuntimeEnvironment.getApplication();
        PhoneMcpSettings.prefs(app).edit().remove("token_encrypted").putString("token","existing-private-token").commit();
        assertEquals("existing-private-token",PhoneMcpSettings.token(app));
        assertFalse(PhoneMcpSettings.prefs(app).contains("token"));
        assertEquals("existing-private-token",PhoneMcpSettings.token(app));
        byte[] bytes=PhoneSecretStorage.encrypt(app,"private".getBytes(StandardCharsets.UTF_8));bytes[bytes.length-1]^=1;
        assertThrows(Exception.class,()->PhoneSecretStorage.decrypt(app,bytes));
        assertArrayEquals(new byte[0],PhoneSecretStorage.decrypt(app,PhoneSecretStorage.encrypt(app,new byte[0])));
    }
    @Test public void portMappingNonceSurvivesProcessStateAndIsBoundToTheGatewayAndPorts() throws Exception {
        var app=RuntimeEnvironment.getApplication();
        var local=InetAddress.getByName("192.168.50.20");var gateway=InetAddress.getByName("192.168.50.1");
        byte[] first=new PhonePortMapper(app,local,gateway).nonce(8443,8443);
        assertEquals(12,first.length);assertArrayEquals(first,new PhonePortMapper(app,local,gateway).nonce(8443,8443));
        assertFalse(java.util.Arrays.equals(first,new PhonePortMapper(app,local,gateway).nonce(8080,80)));
        assertFalse(java.util.Arrays.equals(first,new PhonePortMapper(app,local,InetAddress.getByName("192.168.50.2")).nonce(8443,8443)));
    }
    @Test public void handlesSimultaneousPingsTimesOutIncompleteHeadersAndRestarts() throws Exception {
        server.clientTimeoutMs=250;
        java.util.concurrent.ExecutorService callers=java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            java.util.List<java.util.concurrent.Future<String>> replies=new java.util.ArrayList<>();
            for(int i=0;i<4;i++)replies.add(callers.submit(()->new String(request("POST",path,"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}","",false),StandardCharsets.UTF_8)));
            for(var reply:replies)assertTrue(reply.get(10,TimeUnit.SECONDS).startsWith("HTTP/1.1 200"));
            try(Socket slow=new Socket("127.0.0.1",port)) {
                slow.setSoTimeout(2000);slow.getOutputStream().write("POST /mcp HTTP/1.1\r\n".getBytes(StandardCharsets.US_ASCII));slow.getOutputStream().flush();
                assertEquals(-1,slow.getInputStream().read());
            }
            int previous=port;server.close();server=new PhoneMcpServer(RuntimeEnvironment.getApplication(),store);
            port=server.listen(new ServerSocket(),previous,false);
            assertEquals(previous,port);
            assertTrue(new String(request("GET","/health","","",false),StandardCharsets.UTF_8).startsWith("HTTP/1.1 200"));
        } finally {callers.shutdownNow();}
    }
    @Test public void choosesAFreePersistedLoopbackPortWhenThePreferredPortIsBusy() throws Exception {
        var app=RuntimeEnvironment.getApplication();
        try(ServerSocket occupied=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
            PhoneMcpSettings.prefs(app).edit().putInt("http_port",occupied.getLocalPort()).commit();
            server.close();server=new PhoneMcpServer(app,store);server.start();port=PhoneMcpSettings.httpPort(app);
            assertNotEquals(occupied.getLocalPort(),port);
            assertTrue(PhoneMcpSettings.localUrl(app).startsWith("http://127.0.0.1:"+port+"/mcp/"));
            assertTrue(new String(request("GET","/health","","",false),StandardCharsets.UTF_8).startsWith("HTTP/1.1 200"));
        }
    }
    @Test public void startupSelfTestUsesRealMcpSocketsAndRemovesEvidenceWhenServerStops() throws Exception {
        var app=RuntimeEnvironment.getApplication();server.close();server=new PhoneMcpServer(app,store);
        PhoneMcpSettings.prefs(app).edit().putInt("http_port",0).commit();server.start();server.verifyLocal();
        assertTrue(PhoneMcpSettings.prefs(app).getLong("local_test_at",0)>0);
        assertEquals(0,store.poll().getJSONArray("local_commands").length());
        server.close();assertThrows(Exception.class,server::verifyLocal);
        assertEquals(0,PhoneMcpSettings.prefs(app).getLong("local_test_at",0));
    }
    @Test public void fourViewsAreOneOrderedCommandAndBadInputsDoNotLeavePartialJobs() throws Exception {
        String image=image();JSONArray sources=new JSONArray().put(image).put(image).put(image).put(image);
        JSONObject created=tool("create_model_from_images",new JSONObject().put("images",sources));assertEquals(1,created.getInt("count"));assertEquals("triposr_four",store.get(created.getString("primary_model_id")).getString("mode"));
        int before=store.listing().getJSONArray("commands").length();
        assertThrows(Exception.class,()->store.create(new JSONArray().put(image).put("bad"),"triposr","precise",true));assertEquals(before,store.listing().getJSONArray("commands").length());
        assertThrows(Exception.class,()->PhoneMcpSettings.validatePublicBase("http://public.example"));
        assertEquals("https://phone.example:8443",PhoneMcpSettings.validatePublicBase("https://phone.example:8443/"));
        assertFalse(tool("application_capabilities",new JSONObject()).getBoolean("animation"));
    }
}
