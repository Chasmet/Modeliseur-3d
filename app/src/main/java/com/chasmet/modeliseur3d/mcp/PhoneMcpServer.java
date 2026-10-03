package com.chasmet.modeliseur3d.mcp;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.SSLContext;

/** Stateless MCP Streamable HTTP with bounded clients, inputs, private capability URL and optional TLS. */
public final class PhoneMcpServer implements AutoCloseable {
    private final Context context;
    private final PhoneMcpStore store;
    private final String token;
    private final Set<Socket> clients=ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor requests=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8));
    private final List<ServerSocket> listeners=new ArrayList<>();
    private volatile boolean closed;
    public PhoneMcpServer(Context c,PhoneMcpStore store) { context=c.getApplicationContext();this.store=store;token=PhoneMcpSettings.token(c); }
    public void start() throws Exception {
        try {
            listen(new ServerSocket(),PhoneMcpSettings.HTTP_PORT,false);
            SSLContext tls=PhoneMcpSettings.tls(context);
            if(tls!=null)listen(tls.getServerSocketFactory().createServerSocket(),PhoneMcpSettings.HTTPS_PORT,true);
        } catch(Exception error) { close();throw error; }
    }
    // Port 0 is useful for real socket integration tests and never changes the app's published ports.
    int listen(ServerSocket listener,int port,boolean tls) throws IOException {
        listener.setReuseAddress(true);listener.bind(new InetSocketAddress(port));listeners.add(listener);
        Thread accept=new Thread(()->{
            while(!closed) {
                try {
                    Socket socket=listener.accept();socket.setSoTimeout(20000);clients.add(socket);
                    try { requests.execute(()->{try { serve(socket,tls); } catch(Exception ignored) { } finally { clients.remove(socket);try {socket.close();}catch(IOException ignored){} } }); }
                    catch(RejectedExecutionException overload) {clients.remove(socket);socket.close();}
                } catch(IOException stopped) { if(closed)return; }
            }
        },"PhoneMcpAccept");accept.setDaemon(true);accept.start();return listener.getLocalPort();
    }
    private void serve(Socket socket,boolean tls) throws Exception {
        InputStream input=socket.getInputStream();OutputStream output=socket.getOutputStream();
        String request=line(input,4096);String[] parts=request.split(" ");
        if(parts.length!=3) { response(output,400,"application/json",new byte[0]);return; }
        String method=parts[0],path=parts[1];Map<String,String> headers=new HashMap<>();int size=request.length();
        while(true) { String header=line(input,8192);size+=header.length();if(size>16384) {response(output,431,"application/json",new byte[0]);return;}if(header.isEmpty())break;int colon=header.indexOf(':');if(colon<=0)throw new IOException("En-tête invalide.");String key=header.substring(0,colon).toLowerCase(Locale.ROOT);if(headers.put(key,header.substring(colon+1).trim())!=null)throw new IOException("En-tête dupliqué."); }
        String host=headers.get("host");
        if(!allowedHost(host) || !allowedOrigin(headers.get("origin"))) { response(output,403,"application/json",new byte[0]);return; }
        InetAddress peer=socket.getInetAddress();
        if(!tls && !(peer.isLoopbackAddress()||peer.isSiteLocalAddress()||peer.isLinkLocalAddress())) {response(output,403,"application/json",new byte[0]);return;}
        if(path.startsWith("/files/") && "GET".equals(method)) {
            String[] fields=path.split("/");
            try {
                if(fields.length!=4)throw new IOException("Lien invalide.");JSONObject job=store.get(fields[2]);
                if(!same(fields[3],job.getString("download_token"))) {response(output,403,"application/json",new byte[0]);return;}
                File file=store.model(fields[2]);if(!"ready".equals(job.optString("status"))||!file.isFile()){response(output,409,"application/json",new byte[0]);return;}
                head(output,200,"model/gltf-binary",file.length());try(InputStream bytes=new FileInputStream(file)){com.chasmet.modeliseur3d.cloud.CloudApi.copy(bytes,output);}return;
            } catch(Exception e) {response(output,404,"application/json",new byte[0]);return;}
        }
        if(!same(path,"/mcp/"+token)) {response(output,404,"application/json",new byte[0]);return;}
        if(!"POST".equals(method)) {response(output,405,"application/json",new byte[0]);return;}
        if(!headers.getOrDefault("content-type","").toLowerCase(Locale.ROOT).startsWith("application/json")) {response(output,415,"application/json",new byte[0]);return;}
        if(headers.containsKey("content-length") && headers.containsKey("transfer-encoding")) {response(output,400,"application/json",new byte[0]);return;}
        String protocol=headers.getOrDefault("mcp-protocol-version","2025-03-26");
        if (!protocol.equals("2025-03-26") && !protocol.equals("2025-06-18")) {response(output,400,"application/json",new byte[0]);return;}
        byte[] body;
        try { body=body(input,headers); } catch(IOException invalid) {response(output,413,"application/json",new byte[0]);return;}
        JSONObject call;
        try {call=new JSONObject(new String(body,StandardCharsets.UTF_8));}catch(JSONException invalid){response(output,400,"application/json",error(JSONObject.NULL,-32700,"JSON invalide.").toString().getBytes(StandardCharsets.UTF_8));return;}
        if(!"2.0".equals(call.optString("jsonrpc"))||!call.has("method")){response(output,400,"application/json",error(call.opt("id"),-32600,"Requête RPC invalide.").toString().getBytes(StandardCharsets.UTF_8));return;}
        context.getSharedPreferences("phone_mcp",0).edit().putLong("last_client",System.currentTimeMillis()).apply();
        if(!call.has("id")) {response(output,202,"application/json",new byte[0]);return;}
        JSONObject reply;
        try {reply=new JSONObject().put("jsonrpc","2.0").put("id",call.get("id")).put("result",dispatch(call));}
        catch(Exception exception) {reply=error(call.opt("id"),-32602,exception.getMessage()==null?"Commande MCP impossible.":exception.getMessage());}
        response(output,200,"application/json",reply.toString().getBytes(StandardCharsets.UTF_8));
    }
    private boolean allowedHost(String header) {
        if(header==null)return false;
        try {
            String host=new URI("http://"+header).getHost();if(host==null)return false;
            if("localhost".equalsIgnoreCase(host)||"127.0.0.1".equals(host)||"::1".equals(host)||PhoneMcpSettings.lanAddress().equals(host))return true;
            String base=PhoneMcpSettings.publicBase(context);return !base.isEmpty()&&host.equalsIgnoreCase(new URI(base).getHost());
        } catch(Exception e) {return false;}
    }
    private boolean allowedOrigin(String origin) {
        if(origin==null)return true;
        try {URI uri=new URI(origin);return ("http".equals(uri.getScheme())||"https".equals(uri.getScheme()))&&uri.getUserInfo()==null&&allowedHost(uri.getAuthority());}catch(Exception e){return false;}
    }
    JSONObject dispatch(JSONObject request) throws Exception {
        String method=request.getString("method");JSONObject params=request.optJSONObject("params");
        if("initialize".equals(method))return new JSONObject().put("protocolVersion",params!=null&&"2025-03-26".equals(params.optString("protocolVersion"))?"2025-03-26":"2025-06-18").put("capabilities",new JSONObject().put("tools",new JSONObject())).put("serverInfo",new JSONObject().put("name","Modéliseur 3D téléphone").put("version","6.3.10"));
        if("ping".equals(method))return new JSONObject();
        if("tools/list".equals(method))return tools();
        if(!"tools/call".equals(method)||params==null)throw new IOException("Méthode MCP inconnue.");
        String name=params.getString("name");JSONObject args=params.optJSONObject("arguments");if(args==null)args=new JSONObject();JSONObject result;
        switch(name) {
            case "application_status":result=new JSONObject().put("phone_online",true).put("transport","Android direct").put("generation_runs_on","Android phone / local engines").put("remote_gpu_generation",false).put("relay_required",false).put("public_https_configured",!PhoneMcpSettings.publicBase(context).isEmpty());break;
            case "application_capabilities":result=new JSONObject().put("engines",new JSONArray().put("TripoSR local").put("Silhouettes local").put("IS-Net local").put("Depth Anything V2 local")).put("default_quality","precise").put("pipeline","manual-workshop-v1").put("animation",false).put("skinning",false);break;
            case "list_models_and_images":result=store.listing();break;
            case "create_model_from_images":result=store.create(args.getJSONArray("images"),args.optString("engine","auto"),args.optString("quality","precise"),args.optBoolean("smoothing",true));break;
            case "model_status":result=store.get(args.getString("model_id"));result.remove("download_token");break;
            case "model_download":
                JSONObject job=store.get(args.getString("model_id"));result=new JSONObject().put("status",job.getString("status")).put("message",job.optString("message"));
                if("ready".equals(job.getString("status"))) {
                    String base=PhoneMcpSettings.publicBase(context);if(base.isEmpty())base="http://"+PhoneMcpSettings.lanAddress()+":"+PhoneMcpSettings.HTTP_PORT;
                    result.put("url",base+"/files/"+job.getString("id")+"/"+job.getString("download_token")).put("generated_on","Android phone");
                }break;
            default:throw new IOException("Outil MCP inconnu.");
        }
        return new JSONObject().put("content",new JSONArray().put(new JSONObject().put("type","text").put("text",result.toString()))).put("structuredContent",result).put("isError",false);
    }
    private static JSONObject tools() throws Exception {
        JSONArray list=new JSONArray();
        for(String name:new String[]{"application_status","application_capabilities","list_models_and_images","create_model_from_images","model_status","model_download"}) {
            JSONObject properties=new JSONObject(),schema=new JSONObject().put("type","object").put("properties",properties);String description;
            switch(name) {
                case "create_model_from_images":
                    properties.put("images",new JSONObject().put("type","array").put("items",new JSONObject().put("type","string")).put("minItems",1).put("maxItems",4));
                    properties.put("engine",new JSONObject().put("type","string").put("enum",new JSONArray().put("auto").put("triposr").put("silhouettes")).put("default","auto"));
                    properties.put("quality",new JSONObject().put("type","string").put("enum",new JSONArray().put("precise").put("balanced").put("fast")).put("default","precise"));
                    properties.put("smoothing",new JSONObject().put("type","boolean").put("default",true));schema.put("required",new JSONArray().put("images"));
                    description="Create GLB locally using the manual workshop pipeline. Default: TripoSR precise + IS-Net cutout. One image: front. Four: front, back, right, left. Input: HTTPS direct image or base64 PNG. No animation or rigging.";break;
                case "model_status":case "model_download":properties.put("model_id",new JSONObject().put("type","string"));schema.put("required",new JSONArray().put("model_id"));description="Read model status or retrieve the saved GLB from the phone.";break;
                default:description="Read the local Android application state and actual modeling capabilities.";
            }
            JSONObject annotations=new JSONObject()
                    .put("readOnlyHint",!"create_model_from_images".equals(name))
                    .put("destructiveHint",false)
                    .put("openWorldHint","create_model_from_images".equals(name)||"model_download".equals(name));
            list.put(new JSONObject().put("name",name).put("description",description)
                    .put("inputSchema",schema).put("annotations",annotations));
        }
        return new JSONObject().put("tools",list);
    }
    private static JSONObject error(Object id,int code,String message) throws JSONException {return new JSONObject().put("jsonrpc","2.0").put("id",id==null?JSONObject.NULL:id).put("error",new JSONObject().put("code",code).put("message",message));}
    private static boolean same(String a,String b) {return a!=null&&MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));}
    private static String line(InputStream input,int limit) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();int b;
        while((b=input.read())!=-1) {if(b=='\n')return out.toString("US-ASCII").replaceAll("\r$","");if(out.size()>=limit)throw new IOException("Ligne trop longue.");out.write(b);}throw new EOFException();
    }
    private static byte[] exact(InputStream in,int size) throws IOException {byte[] bytes=new byte[size];int offset=0,n;while(offset<size){n=in.read(bytes,offset,size-offset);if(n<0)throw new EOFException();offset+=n;}return bytes;}
    private static byte[] body(InputStream in,Map<String,String> headers) throws IOException {
        int max=48*1024*1024;
        if("chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) {
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            while(true) {
                int size;try{size=Integer.parseInt(line(in,128).split(";",2)[0].trim(),16);}catch(NumberFormatException e){throw new IOException("Taille invalide.");}
                if(size<0||size>max-out.size())throw new IOException("Requête trop grande.");
                if(size==0){int trailer=0;while(true){String l=line(in,2048);trailer+=l.length();if(trailer>4096)throw new IOException("En-tête trop long.");if(l.isEmpty())break;}return out.toByteArray();}
                out.write(exact(in,size));if(!line(in,2).isEmpty())throw new IOException("Trame HTTP invalide.");
            }
        }
        if(headers.containsKey("transfer-encoding"))throw new IOException("Encodage HTTP non supporté.");
        int length;try{length=Integer.parseInt(headers.getOrDefault("content-length","0"));}catch(NumberFormatException e){throw new IOException("Taille invalide.");}
        if(length<0||length>max)throw new IOException("Requête trop grande.");return exact(in,length);
    }
    private static void head(OutputStream out,int status,String type,long length) throws IOException {
        String header="HTTP/1.1 "+status+" Response\r\n"+(status==405?"Allow: POST\r\n":"")+"Content-Type: "+type+"\r\nContent-Length: "+length+"\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n\r\n";out.write(header.getBytes(StandardCharsets.US_ASCII));
    }
    private static void response(OutputStream out,int status,String type,byte[] bytes) throws IOException {head(out,status,type,bytes.length);out.write(bytes);out.flush();}
    @Override public void close() {closed=true;for(ServerSocket listener:listeners)try{listener.close();}catch(IOException ignored){}for(Socket client:clients)try{client.close();}catch(IOException ignored){}requests.shutdownNow();}
}
