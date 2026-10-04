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
    private final List<ServerSocket> listeners=new CopyOnWriteArrayList<>();
    private ServerSocket tlsListener;
    private volatile boolean closed;
    private volatile boolean httpsRunning;
    public boolean httpsRunning() { return httpsRunning; }
    public synchronized void pauseTls() {
        if(tlsListener!=null) {try {tlsListener.close();}catch(IOException ignored) { }listeners.remove(tlsListener);tlsListener=null;}
        httpsRunning=false;PhoneMcpSettings.prefs(context).edit().putBoolean("https_running",false).apply();
    }
    public synchronized void reloadTls() throws Exception {
        if(closed)throw new IOException("Serveur arrêté.");
        SSLContext tls=PhoneMcpSettings.tls(context);if(tls==null)throw new IOException("Certificat absent.");
        pauseTls();int port,previous=PhoneMcpSettings.httpsPort(context);
        int requested=PhoneMcpSettings.prefs(context).getBoolean("automatic_https",false)?PhoneMcpSettings.HTTPS_PORT:previous;
        try {port=listen(tls.getServerSocketFactory().createServerSocket(),requested,true);}
        catch(BindException busy) {
            if(previous!=requested)try {port=listen(tls.getServerSocketFactory().createServerSocket(),previous,true);}
            catch(BindException stillBusy) {port=listen(tls.getServerSocketFactory().createServerSocket(),0,true);}
            else port=listen(tls.getServerSocketFactory().createServerSocket(),0,true);
        }
        PhoneMcpSettings.prefs(context).edit().putInt("https_port",port).putBoolean("https_running",true).putString("tls_error","").apply();
    }
    // Optional in-process diagnostics. No access log or private URL is emitted.
    java.util.function.Consumer<Exception> clientErrors=error -> { };
    int clientTimeoutMs=20000;
    public PhoneMcpServer(Context c,PhoneMcpStore store) { context=c.getApplicationContext();this.store=store;token=PhoneMcpSettings.token(c); }
    public void start() throws Exception {
        try {
            int httpPort;
            try {httpPort=listen(new ServerSocket(),PhoneMcpSettings.httpPort(context),false);}
            catch(BindException busy) {httpPort=listen(new ServerSocket(),0,false);}
            PhoneMcpSettings.prefs(context).edit().putInt("http_port",httpPort).apply();
            // A missing/expired certificate must not stop local work and the durable queue.
            try {
                SSLContext tls=PhoneMcpSettings.tls(context);
                if(tls!=null) {
                    int httpsPort;
                    try {httpsPort=listen(tls.getServerSocketFactory().createServerSocket(),PhoneMcpSettings.httpsPort(context),true);}
                    catch(BindException busy) {httpsPort=listen(tls.getServerSocketFactory().createServerSocket(),0,true);}
                    PhoneMcpSettings.prefs(context).edit().putInt("https_port",httpsPort).apply();httpsRunning=true;
                }
                PhoneMcpSettings.prefs(context).edit().putBoolean("https_running",httpsRunning).putString("tls_error",tls==null?"Certificat HTTPS absent":"").apply();
            } catch(Exception tlsError) {
                PhoneMcpSettings.prefs(context).edit().putBoolean("https_running",false).putString("tls_error","HTTPS indisponible : certificat ou ouverture du port à vérifier").apply();
            }
        } catch(Exception error) { close();throw error; }
    }
    // Port 0 is useful for real socket integration tests and never changes the app's published ports.
    int listen(ServerSocket listener,int port,boolean tls) throws IOException {
        listener.setReuseAddress(true);
        try {listener.bind(tls?new InetSocketAddress(port):new InetSocketAddress(InetAddress.getByName("127.0.0.1"),port));}
        catch(IOException failure) {listener.close();throw failure;}
        listeners.add(listener);
        if(tls) {httpsRunning=true;tlsListener=listener;}
        Thread accept=new Thread(()->{
            while(!closed) {
                try {
                    Socket socket=listener.accept();socket.setSoTimeout(clientTimeoutMs);clients.add(socket);
                    try { requests.execute(()->{try { serve(socket,tls); } catch(Exception error) { clientErrors.accept(error); } finally { clients.remove(socket);try {socket.close();}catch(IOException ignored){} } }); }
                    catch(RejectedExecutionException overload) {clients.remove(socket);socket.close();}
                } catch(IOException stopped) { if(closed||listener.isClosed())return; }
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
        if(!tls && PhoneNetworkDiagnostics.global(peer)) {response(output,403,"application/json",new byte[0]);return;}
        if("GET".equals(method) && ("/health".equals(path)||"/apps/modeliseur3d/health".equals(path))) {
            JSONObject health=new JSONObject().put("status","ok").put("server","android").put("mcp",true)
                    .put("transport","streamable-http").put("https",httpsRunning).put("tools",6);
            response(output,200,"application/json",health.toString().getBytes(StandardCharsets.UTF_8));return;
        }
        boolean appMcp="/apps/modeliseur3d/mcp".equals(path);
        boolean appStatus="/apps/modeliseur3d/status".equals(path);
        boolean canonical="/mcp".equals(path)||"/status".equals(path)||appMcp||appStatus;
        boolean authorized=same(headers.get("authorization"),"Bearer "+token);
        boolean publicReadOnly=tls&&canonical&&!authorized&&!headers.containsKey("authorization");
        if(canonical && !authorized && !publicReadOnly) {response(output,401,"application/json",new byte[0]);return;}
        if("GET".equals(method) && ("/status".equals(path)||appStatus)) {
            response(output,200,"application/json",(publicReadOnly?publicStatus():status()).toString().getBytes(StandardCharsets.UTF_8));return;
        }
        if(path.startsWith("/files/") && "GET".equals(method)) {
            String[] fields=path.split("/");
            try {
                if(fields.length!=4)throw new IOException("Lien invalide.");JSONObject job=store.get(fields[2]);
                if(!same(fields[3],job.getString("download_token"))) {response(output,403,"application/json",new byte[0]);return;}
                File file=store.model(fields[2]);if(!"ready".equals(job.optString("status"))||!file.isFile()){response(output,409,"application/json",new byte[0]);return;}
                head(output,200,"model/gltf-binary",file.length());try(InputStream bytes=new FileInputStream(file)){com.chasmet.modeliseur3d.cloud.CloudApi.copy(bytes,output);}return;
            } catch(Exception e) {response(output,404,"application/json",new byte[0]);return;}
        }
        if(!"/mcp".equals(path) && !appMcp && !same(path,"/mcp/"+token)) {response(output,404,"application/json",new byte[0]);return;}
        if(!"POST".equals(method)) {response(output,405,"application/json",new byte[0]);return;}
        if(!headers.getOrDefault("content-type","").toLowerCase(Locale.ROOT).startsWith("application/json")) {response(output,415,"application/json",new byte[0]);return;}
        if(headers.containsKey("content-length") && headers.containsKey("transfer-encoding")) {response(output,400,"application/json",new byte[0]);return;}
        String protocol=headers.getOrDefault("mcp-protocol-version","2025-03-26");
        if (!supportedProtocol(protocol)) {response(output,400,"application/json",new byte[0]);return;}
        byte[] body;
        try { body=body(input,headers,publicReadOnly?8192:48*1024*1024); } catch(IOException invalid) {response(output,413,"application/json",new byte[0]);return;}
        JSONObject call;
        try {call=new JSONObject(new String(body,StandardCharsets.UTF_8));}catch(JSONException invalid){response(output,400,"application/json",error(JSONObject.NULL,-32700,"JSON invalide.").toString().getBytes(StandardCharsets.UTF_8));return;}
        if(!"2.0".equals(call.optString("jsonrpc"))||!call.has("method")){response(output,400,"application/json",error(call.opt("id"),-32600,"Requête RPC invalide.").toString().getBytes(StandardCharsets.UTF_8));return;}
        android.content.SharedPreferences.Editor seen=PhoneMcpSettings.prefs(context).edit().putLong("last_client",System.currentTimeMillis());
        if(tls && PhoneNetworkDiagnostics.global(peer)) seen.putLong("external_client",System.currentTimeMillis());
        seen.apply();
        if(!call.has("id")) {response(output,202,"application/json",new byte[0]);return;}
        JSONObject reply;
        try {
            JSONObject result=publicReadOnly?dispatchPublic(call):dispatch(call);
            reply=new JSONObject().put("jsonrpc","2.0").put("id",call.get("id")).put("result",result);
            if(tls&&PhoneNetworkDiagnostics.global(peer)&&"tools/call".equals(call.optString("method"))&&!result.optBoolean("isError"))
                PhoneMcpSettings.prefs(context).edit().putLong("public_mcp_client",System.currentTimeMillis()).apply();
        }
        catch(RpcError exception) {reply=error(call.opt("id"),exception.code,exception.getMessage());}
        catch(Exception exception) {reply=error(call.opt("id"),-32602,"Paramètres MCP invalides.");}
        response(output,200,"application/json",reply.toString().getBytes(StandardCharsets.UTF_8));
    }
    private boolean allowedHost(String header) {
        if(header==null)return false;
        try {
            String host=new URI("http://"+header).getHost();if(host==null)return false;
            if("localhost".equalsIgnoreCase(host)||PhoneMcpSettings.isLocalHost(host))return true;
            String base=PhoneMcpSettings.publicBase(context);return !base.isEmpty()&&host.equalsIgnoreCase(new URI(base).getHost());
        } catch(Exception e) {return false;}
    }
    private boolean allowedOrigin(String origin) {
        if(origin==null)return true;
        try {URI uri=new URI(origin);return ("http".equals(uri.getScheme())||"https".equals(uri.getScheme()))&&uri.getUserInfo()==null&&allowedHost(uri.getAuthority());}catch(Exception e){return false;}
    }
    JSONObject dispatch(JSONObject request) throws Exception {
        String method=request.getString("method");JSONObject params=request.optJSONObject("params");
        if("initialize".equals(method)) {
            String version=params==null?"":params.optString("protocolVersion");
            return new JSONObject().put("protocolVersion",supportedProtocol(version)?version:"2025-11-25")
                    .put("capabilities",new JSONObject().put("tools",new JSONObject()))
                    .put("serverInfo",new JSONObject().put("name","Modéliseur 3D téléphone")
                    .put("version",com.chasmet.modeliseur3d.update.UpdateManager.currentVersion(context)));
        }
        if("ping".equals(method))return new JSONObject();
        if("tools/list".equals(method))return tools();
        if(!"tools/call".equals(method))throw new RpcError(-32601,"Méthode MCP inconnue.");
        if(params==null || !(params.opt("name") instanceof String))throw new RpcError(-32602,"Nom d’outil requis.");
        String name=params.getString("name");JSONObject args=params.optJSONObject("arguments");if(args==null) { if(params.has("arguments"))throw new RpcError(-32602,"Arguments objet requis.");args=new JSONObject(); }
        validateArguments(name,args);JSONObject result;
        try { switch(name) {
            case "application_status":result=status();break;
            case "application_capabilities":result=new JSONObject().put("engines",new JSONArray().put("TripoSR local").put("Silhouettes local").put("IS-Net local").put("Depth Anything V2 local")).put("default_quality","precise").put("pipeline","manual-workshop-v1").put("animation",false).put("skinning",false);break;
            case "list_models_and_images":result=store.listing();break;
            case "create_model_from_images":result=store.create(args.getJSONArray("images"),args.optString("engine","auto"),args.optString("quality","precise"),args.optBoolean("smoothing",true));break;
            case "model_status":result=store.get(args.getString("model_id"));result.remove("download_token");break;
            case "model_download":
                JSONObject job=store.get(args.getString("model_id"));result=new JSONObject().put("status",job.getString("status")).put("message",job.optString("message"));
                if("ready".equals(job.getString("status"))) {
                    String base=PhoneMcpSettings.publicBase(context);if(base.isEmpty())base="http://127.0.0.1:"+PhoneMcpSettings.httpPort(context);
                    result.put("url",base+"/files/"+job.getString("id")+"/"+job.getString("download_token")).put("generated_on","Android phone");
                }break;
            default:throw new RpcError(-32602,"Outil MCP inconnu.");
        } } catch(RpcError error) { throw error; }
        catch(Exception failure) {
            String message=failure instanceof IOException?failure.getMessage():"L’outil local n’a pas pu terminer la commande.";
            return new JSONObject().put("content",new JSONArray().put(new JSONObject().put("type","text").put("text",message==null?"Échec de l’outil local.":message))).put("isError",true);
        }
        return new JSONObject().put("content",new JSONArray().put(new JSONObject().put("type","text").put("text",result.toString()))).put("structuredContent",result).put("isError",false);
    }
    private JSONObject publicStatus() throws Exception {
        return new JSONObject().put("server","android").put("application","Modéliseur 3D").put("online",true)
                .put("transport","streamable-http").put("generation_runs_on","Android phone").put("public_tools_read_only",true);
    }
    private JSONObject dispatchPublic(JSONObject call) throws Exception {
        String method=call.getString("method");
        if("initialize".equals(method)||"ping".equals(method))return dispatch(call);
        if("tools/list".equals(method)) {
            JSONArray full=tools().getJSONArray("tools"),safe=new JSONArray();
            for(int i=0;i<full.length();i++) {JSONObject tool=full.getJSONObject(i);if("application_status".equals(tool.getString("name"))||"application_capabilities".equals(tool.getString("name")))safe.put(tool);}
            return new JSONObject().put("tools",safe);
        }
        if(!"tools/call".equals(method))throw new RpcError(-32601,"Méthode MCP inconnue.");
        JSONObject params=call.optJSONObject("params");String name=params==null?"":params.optString("name");
        if(!"application_status".equals(name)&&!"application_capabilities".equals(name))throw new RpcError(-32602,"Outil privé indisponible sur la connexion publique sans authentification.");
        if("application_capabilities".equals(name))return dispatch(call);
        JSONObject args=params.optJSONObject("arguments");
        if(args==null&&params.has("arguments"))throw new RpcError(-32602,"Arguments objet requis.");
        validateArguments(name,args==null?new JSONObject():args);JSONObject result=publicStatus();
        return new JSONObject().put("content",new JSONArray().put(new JSONObject().put("type","text").put("text",result.toString()))).put("structuredContent",result).put("isError",false);
    }
    private static boolean supportedProtocol(String version) {
        return "2025-03-26".equals(version)||"2025-06-18".equals(version)||"2025-11-25".equals(version);
    }
    private static final class RpcError extends IOException {
        final int code;
        RpcError(int code,String message) {super(message);this.code=code;}
    }
    private static void validateArguments(String name,JSONObject args) throws Exception {
        Set<String> allowed;
        if("create_model_from_images".equals(name)) allowed=new HashSet<>(Arrays.asList("images","engine","quality","smoothing"));
        else if("model_status".equals(name)||"model_download".equals(name)) allowed=Collections.singleton("model_id");
        else if(Arrays.asList("application_status","application_capabilities","list_models_and_images").contains(name)) allowed=Collections.emptySet();
        else throw new RpcError(-32602,"Outil MCP inconnu.");
        Iterator<String> keys=args.keys();while(keys.hasNext())if(!allowed.contains(keys.next()))throw new RpcError(-32602,"Paramètre inconnu.");
        if(allowed.contains("model_id") && (!(args.opt("model_id") instanceof String)||!args.getString("model_id").matches("[a-f0-9]{32}")))throw new RpcError(-32602,"Identifiant de modèle invalide.");
        if(allowed.contains("images")) {
            JSONArray images=args.optJSONArray("images");
            if(images==null||images.length()<1||images.length()>4)throw new RpcError(-32602,"Fournis entre 1 et 4 images.");
            for(int i=0;i<images.length();i++)if(!(images.get(i) instanceof String)||images.getString(i).isEmpty())throw new RpcError(-32602,"Image invalide.");
            if(args.has("engine")&&(!(args.opt("engine") instanceof String)||!args.getString("engine").matches("auto|triposr|silhouettes")))throw new RpcError(-32602,"Moteur invalide.");
            if(args.has("quality")&&(!(args.opt("quality") instanceof String)||!args.getString("quality").matches("precise|balanced|fast")))throw new RpcError(-32602,"Qualité invalide.");
            if(args.has("smoothing")&&!(args.opt("smoothing") instanceof Boolean))throw new RpcError(-32602,"Lissage booléen requis.");
        }
    }
    private JSONObject status() throws Exception {
        android.content.SharedPreferences settings=PhoneMcpSettings.prefs(context);
        return new JSONObject().put("phone_online",true).put("transport","Android direct")
                .put("generation_runs_on","Android phone / local engines").put("remote_gpu_generation",false).put("relay_required",false)
                .put("public_https_configured",!PhoneMcpSettings.publicBase(context).isEmpty()).put("https_running",httpsRunning)
                .put("public_access_verified",settings.getLong("external_client",0)>0).put("last_external_client",settings.getLong("external_client",0))
                .put("last_client",settings.getLong("last_client",0)).put("network",PhoneNetworkDiagnostics.snapshot(context));
    }
    private static JSONObject tools() throws Exception {
        JSONArray list=new JSONArray();
        for(String name:new String[]{"application_status","application_capabilities","list_models_and_images","create_model_from_images","model_status","model_download"}) {
            JSONObject properties=new JSONObject(),schema=new JSONObject().put("type","object").put("properties",properties).put("additionalProperties",false);String description;
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
                    .put("inputSchema",schema).put("outputSchema",new JSONObject().put("type","object"))
                    .put("annotations",annotations));
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
    private static byte[] body(InputStream in,Map<String,String> headers,int max) throws IOException {
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
        String header="HTTP/1.1 "+status+" Response\r\n"+(status==405?"Allow: POST\r\n":"")+(status==401?"WWW-Authenticate: Bearer\r\n":"")+"Content-Type: "+type+"\r\nContent-Length: "+length+"\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n\r\n";out.write(header.getBytes(StandardCharsets.US_ASCII));
    }
    private static void response(OutputStream out,int status,String type,byte[] bytes) throws IOException {head(out,status,type,bytes.length);out.write(bytes);out.flush();}
    @Override public synchronized void close() {closed=true;httpsRunning=false;for(ServerSocket listener:listeners)try{listener.close();}catch(IOException ignored){}for(Socket client:clients)try{client.close();}catch(IOException ignored){}requests.shutdownNow();}
}
