package com.chasmet.modeliseur3d.mcp;

import android.content.Context;
import android.graphics.*;
import android.util.Base64;
import android.net.Uri;
import com.chasmet.modeliseur3d.util.OfflineImageImporter;
import com.chasmet.modeliseur3d.cloud.CloudApi;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Durable queue and inputs live on the phone. No registration or cloud relay is used. */
public final class PhoneMcpStore {
    private final Context context;
    private final Object creation=new Object();
    public PhoneMcpStore(Context context) throws Exception {
        this.context=context.getApplicationContext();
        for (JSONObject command:commands()) if("running".equals(command.optString("status"))) update(command.getString("id"),"pending","Reprise après interruption Android.");
    }
    private File record(String id) throws Exception { return new File(context.getFilesDir(),"mcp_inputs/"+CloudApi.id(id)+"/phone-command.json"); }
    private static String id() { return UUID.randomUUID().toString().replace("-",""); }
    public synchronized JSONObject get(String id) throws Exception {
        File record=record(id);if(!record.isFile())throw new CloudApi.HttpFailure(404,"Commande directe introuvable.");
        try(InputStream in=new FileInputStream(record);ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            CloudApi.copy(in,out);if(out.size()>65536)throw new IOException("Commande invalide.");return new JSONObject(out.toString("UTF-8"));
        }
    }
    private void save(JSONObject command) throws Exception {
        File file=record(command.getString("id"));if(!file.getParentFile().isDirectory() && !file.getParentFile().mkdirs())throw new IOException("Stockage MCP indisponible.");
        File part=new File(file.getPath()+".part");
        try {
            try(OutputStream stream=new FileOutputStream(part)) { stream.write(command.toString().getBytes(StandardCharsets.UTF_8)); }
            if(!part.renameTo(file))throw new IOException("Commande non enregistrée.");
        } finally { part.delete(); }
    }
    private List<JSONObject> commands() throws Exception {
        File folder=new File(context.getFilesDir(),"mcp_inputs");File[] dirs=folder.listFiles();List<JSONObject> result=new ArrayList<>();
        if(dirs!=null)for(File dir:dirs)if(dir.getName().matches("[a-f0-9]{32}") && new File(dir,"phone-command.json").isFile())result.add(get(dir.getName()));
        result.sort((a,b)->Long.compare(a.optLong("created"),b.optLong("created")));return result;
    }
    public synchronized JSONObject listing() throws Exception {
        JSONArray jobs=new JSONArray(),images=new JSONArray();
        for(JSONObject command:commands()) {
            JSONObject safe=new JSONObject(command.toString());safe.remove("download_token");jobs.put(safe);
            JSONArray refs=command.getJSONArray("references");for(int i=0;i<refs.length();i++) images.put(new JSONObject().put("id",refs.getString(i)).put("command_id",command.getString("id")));
        }
        return new JSONObject().put("commands",jobs).put("images",images);
    }
    public synchronized JSONObject poll() throws Exception {
        JSONArray queue=new JSONArray();for(JSONObject command:commands())if("pending".equals(command.optString("status")))queue.put(command);
        return new JSONObject().put("local_commands",queue);
    }
    public synchronized JSONObject update(String id,String state,String message) throws Exception {
        if(!state.matches("pending|running|ready|error"))throw new IOException("État MCP invalide.");
        JSONObject command=get(id).put("status",state).put("message",message).put("updated",System.currentTimeMillis());save(command);return command;
    }
    public File model(String id) throws Exception { return new File(context.getFilesDir(),"cloud_models/"+CloudApi.id(id)+".glb"); }
    public synchronized File image(String command,String reference) throws Exception {
        JSONObject job=get(command);JSONArray refs=job.getJSONArray("references");
        for(int i=0;i<refs.length();i++)if(refs.getString(i).equals(reference))return new File(record(command).getParentFile(),"image-"+i+".png");
        throw new IOException("Image MCP introuvable.");
    }
    public JSONObject create(JSONArray sources,String engine,String quality,boolean smoothing) throws Exception {
        return create(sources,engine,quality,smoothing,null,.7f);
    }
    public JSONObject create(JSONArray sources,String engine,String quality,boolean smoothing,JSONArray detailRegion,float strength)throws Exception{
        com.chasmet.modeliseur3d.model.TripoDetailRegion region=com.chasmet.modeliseur3d.model.TripoDetailRegion.fromJson(detailRegion);
        if(!Float.isFinite(strength)||strength<0||strength>1)throw new IOException("Force du détail invalide.");
        if(region!=null&&(sources==null||sources.length()!=1||"silhouettes".equals(engine)))throw new IOException("Le gros plan utilise une image TripoSR.");
        synchronized(creation){return createSerial(sources,engine,quality,smoothing,region,strength);}
    }
    private JSONObject createSerial(JSONArray sources,String engine,String quality,boolean smoothing,com.chasmet.modeliseur3d.model.TripoDetailRegion detailRegion,float strength) throws Exception {
        if(sources==null || sources.length()<1 || sources.length()>4)throw new IOException("Fournis entre 1 et 4 images.");
        if(!engine.matches("auto|triposr|silhouettes") || !quality.matches("fast|balanced|precise"))throw new IOException("Moteur ou qualité invalide.");
        int count=sources.length()==4?1:sources.length(),pending=0;
        List<JSONObject> existing=commands();
        for(JSONObject job:existing)if(job.optString("status").matches("pending|running"))pending++;
        if(pending+count>4)throw new IOException("Quatre créations maximum en attente.");
        if(existing.size()+count>100)throw new IOException("Limite de l’historique MCP atteinte.");
        JSONArray jobs=new JSONArray();List<File> created=new ArrayList<>();
        try {
            int cursor=0;
            for(int j=0;j<count;j++) {
                String commandId=id();File folder=record(commandId).getParentFile();if(!folder.mkdirs())throw new IOException("Stockage des images indisponible.");created.add(folder);
                JSONArray references=new JSONArray();int views=sources.length()==4?4:1;
                for(int i=0;i<views;i++) { byte[] raw=readSource(sources.getString(cursor++));storePng(raw,new File(folder,"image-"+i+".png"));references.put(id()); }
                JSONObject command=new JSONObject().put("id",commandId).put("mode",("silhouettes".equals(engine)?"silhouettes":"triposr")+(views==4?"_four":"_single"))
                        .put("references",references).put("options",new JSONObject().put("quality",quality).put("smoothing",smoothing).put("pipeline","manual-workshop-v1").put("segmentation","isnet"))
                        .put("status","pending").put("message","En attente du moteur local Android.").put("created",System.currentTimeMillis()).put("updated",System.currentTimeMillis()).put("download_token",id());
                if(detailRegion!=null)command.getJSONObject("options").put("detail_region",detailRegion.json()).put("detail_strength",strength);
                JSONObject safe=new JSONObject(command.toString());safe.remove("download_token");jobs.put(safe);
            }
            synchronized(this) {
                try { for(int i=0;i<jobs.length();i++) {
                    JSONObject persisted=new JSONObject(jobs.getJSONObject(i).toString()).put("download_token",id());save(persisted);
                } } catch(Exception error) {
                    for(File folder:created) { File record=new File(folder,"phone-command.json");record.delete(); }
                    throw error;
                }
            }
            return new JSONObject().put("primary_model_id",jobs.getJSONObject(0).getString("id")).put("models",jobs).put("count",count).put("quality",quality).put("execution","Android local / MCP direct");
        } catch(Exception error) { for(File folder:created){File[] files=folder.listFiles();if(files!=null)for(File file:files)file.delete();folder.delete();}throw error; }
    }
    private static byte[] readSource(String source) throws Exception {
        if(source.length()>12*1024*1024)throw new IOException("Image supérieure à 8 Mo.");
        if(source.startsWith("https://")) {
            URI uri=new URI(source);if(uri.getHost()==null || uri.getUserInfo()!=null)throw new IOException("URL image HTTPS invalide.");
            for(InetAddress ip:InetAddress.getAllByName(uri.getHost())) if(!PhoneNetworkDiagnostics.global(ip))throw new IOException("L’image HTTPS doit être publique.");
            HttpURLConnection request=(HttpURLConnection)uri.toURL().openConnection();request.setInstanceFollowRedirects(false);request.setConnectTimeout(15000);request.setReadTimeout(30000);
            try { if(request.getResponseCode()!=200)throw new IOException("Image HTTPS indisponible. Fournis un PNG en base64 ou une URL directe.");try(InputStream in=request.getInputStream()) { return bounded(in); } }
            finally { request.disconnect(); }
        }
        if(source.startsWith("data:image/")) { int comma=source.indexOf(',');if(comma<0 || !source.substring(0,comma).endsWith(";base64"))throw new IOException("Image base64 invalide.");source=source.substring(comma+1); }
        else if(source.startsWith("base64:"))source=source.substring(7);
        byte[] raw;try { raw=Base64.decode(source,Base64.DEFAULT); } catch(IllegalArgumentException e) { throw new IOException("Image base64 invalide."); }
        if(raw.length==0 || raw.length>8*1024*1024)throw new IOException("Image limitée à 8 Mo.");return raw;
    }
    private static byte[] bounded(InputStream input) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;
        while((n=input.read(b))!=-1) { if(out.size()+n>8*1024*1024)throw new IOException("Image limitée à 8 Mo.");out.write(b,0,n); }return out.toByteArray();
    }
    private void storePng(byte[] raw,File target) throws Exception {
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(raw,0,raw.length,bounds);
        if(bounds.outWidth<3 || bounds.outHeight<3 || (long)bounds.outWidth*bounds.outHeight>16_000_000L)throw new IOException("Dimensions d’image invalides.");
        File original=new File(target.getPath()+".source");Bitmap image=null;
        try {
            try(OutputStream out=new FileOutputStream(original)) { out.write(raw); }
            image=OfflineImageImporter.decode(context.getContentResolver(),Uri.fromFile(original),com.chasmet.modeliseur3d.model.TripoComputePolicy.imageLimit());
            if(image==null)throw new IOException("Image illisible.");
            try(OutputStream out=new FileOutputStream(target)) { if(!image.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("PNG non enregistré."); }
        } finally { original.delete();if(image!=null)image.recycle(); }
    }
}
