package com.chasmet.modeliseur3d.update;

import android.content.Context;
import android.content.pm.*;
import android.os.Build;
import androidx.work.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** GitHub public Releases updater. Never uninstalls, never accepts a different signer. */
public final class UpdateManager {
    public static final ExecutorService executor=Executors.newSingleThreadExecutor();
    private static final String LATEST="https://api.github.com/repos/Chasmet/Modeliseur-3d/releases/latest";
    private static final long MAX_APK=512L*1024*1024;
    private UpdateManager() {}
    public static android.content.SharedPreferences prefs(Context c) { return c.getSharedPreferences("apk_updates",Context.MODE_PRIVATE); }
    public static boolean automatic(Context c) { return prefs(c).getBoolean("automatic",true); }
    public static String pendingVersion(Context c) { return prefs(c).getString("pending",""); }
    public static String currentVersion(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(),0).versionName; }
        catch(PackageManager.NameNotFoundException e) { return "inconnue"; }
    }
    public static void schedule(Context c) {
        WorkManager work=WorkManager.getInstance(c);
        if(!automatic(c)) { work.cancelUniqueWork("modeliseur-apk-check"); return; }
        Constraints constraints=new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        work.enqueueUniquePeriodicWork("modeliseur-apk-check",ExistingPeriodicWorkPolicy.KEEP,
                new PeriodicWorkRequest.Builder(UpdateWorker.class,12,TimeUnit.HOURS).setConstraints(constraints).build());
    }
    private static HttpURLConnection connection(URL url) throws IOException {
        if(!"https".equals(url.getProtocol()) || url.getUserInfo()!=null) throw new IOException("Lien HTTPS invalide.");
        HttpURLConnection c=(HttpURLConnection)url.openConnection(); c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(30_000); c.setReadTimeout(60_000);
        c.setRequestProperty("User-Agent","Modeliseur3D-Update"); return c;
    }
    private static byte[] body(InputStream input,int max) throws IOException {
        try(InputStream in=input; ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192]; int n;
            while((n=in.read(buffer))!=-1) { if(out.size()+n>max) throw new IOException("Réponse trop volumineuse.");out.write(buffer,0,n); }
            return out.toByteArray();
        }
    }
    public static synchronized ReleaseInfo check(Context c) throws Exception {
        com.chasmet.modeliseur3d.diagnostics.DiagnosticLog.initialize(c);
        HttpURLConnection conn=connection(new URL(LATEST)); conn.setRequestProperty("Accept","application/vnd.github+json");
        try {
            int code=conn.getResponseCode();
            if(code==404) throw new IOException("Aucune Release publique disponible. Impossible de confirmer que l’application est à jour.");
            if(code!=200) throw new IOException(code==403 || code==429 ? "Limite GitHub atteinte. Réessaye plus tard." : "Vérification indisponible (HTTP "+code+").");
            ReleaseInfo info=parse(new JSONObject(new String(body(conn.getInputStream(),1024*1024),StandardCharsets.UTF_8)),currentVersion(c));
            prefs(c).edit().putString("pending",info==null?"":info.version).putLong("checked",System.currentTimeMillis()).apply();
            String state=info==null?"Application à jour":"Nouvelle version "+info.version;
            prefs(c).edit().putString("check_status",state).apply();
            com.chasmet.modeliseur3d.diagnostics.DiagnosticLog.record("OK","GitHub Releases : "+state);
            return info;
        } catch(Exception error){prefs(c).edit().putString("check_status",com.chasmet.modeliseur3d.diagnostics.DiagnosticLog.sanitize(error.getMessage())).apply();com.chasmet.modeliseur3d.diagnostics.DiagnosticLog.record("ERREUR","Vérification GitHub Releases",error);throw error;} finally { conn.disconnect(); }
    }
    public static ReleaseInfo parse(JSONObject release,String local) throws Exception {
        if(release.optBoolean("draft") || release.optBoolean("prerelease")) return null;
        String version=release.getString("tag_name").replaceFirst("^[vV]","");
        if(!ReleaseInfo.newer(version,local)) return null;
        JSONArray assets=release.getJSONArray("assets");
        for(int i=0;i<assets.length();i++) {
            JSONObject a=assets.getJSONObject(i);
            if(!a.optString("name").endsWith(".apk")) continue;
            String url=a.getString("browser_download_url"), hash=a.optString("digest");
            URI uri=new URI(url);
            if(!"https".equals(uri.getScheme()) || !"github.com".equals(uri.getHost())
                    || !uri.getPath().startsWith("/Chasmet/Modeliseur-3d/releases/download/")
                    || uri.getUserInfo()!=null || uri.getFragment()!=null) throw new IOException("Lien de Release inattendu.");
            long size=a.getLong("size");
            if(size<=0 || size>MAX_APK || !hash.matches("sha256:[a-fA-F0-9]{64}")) throw new IOException("Release sans taille ou empreinte SHA-256 fiable.");
            return new ReleaseInfo(version,url,hash.substring(7).toLowerCase(Locale.ROOT),release.optString("body"),size);
        }
        throw new IOException("La nouvelle Release ne contient aucun APK vérifiable.");
    }
    public interface Progress { void update(int percent); }
    public static File download(Context context,ReleaseInfo info,Progress progress) throws Exception {
        File folder=new File(context.getCacheDir(),"updates"); if(!folder.isDirectory()&&!folder.mkdirs()) throw new IOException("Stockage indisponible.");
        File target=new File(folder,"modeliseur-update.apk"), part=new File(folder,"modeliseur-update.part");
        HttpURLConnection conn=null;URL url=new URL(info.url);
        try {
            for(int redirect=0;redirect<=5;redirect++) {
                conn=connection(url); int code=conn.getResponseCode();
                if(code==200) break;
                if(!Arrays.asList(301,302,303,307,308).contains(code)) throw new IOException("Téléchargement indisponible (HTTP "+code+").");
                String next=conn.getHeaderField("Location");conn.disconnect();conn=null;
                if(next==null||redirect==5) throw new IOException("Redirection invalide.");
                url=new URL(url,next);String host=url.getHost();
                if(!("github.com".equals(host)||"release-assets.githubusercontent.com".equals(host)||"objects.githubusercontent.com".equals(host))) throw new IOException("Serveur de téléchargement inattendu.");
            }
            if(conn==null) throw new IOException("Téléchargement interrompu.");
            long count=0;int last=-1;MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(InputStream in=conn.getInputStream();OutputStream out=new FileOutputStream(part)) {
                byte[] buffer=new byte[64*1024];int n;
                while((n=in.read(buffer))!=-1) {
                    count+=n;if(count>MAX_APK||count>info.bytes) throw new IOException("APK plus volumineux que la Release.");
                    out.write(buffer,0,n);digest.update(buffer,0,n);int percent=(int)(100*count/info.bytes);
                    if(percent!=last){progress.update(percent);last=percent;}
                }
            }
            if(count!=info.bytes || !hex(digest.digest()).equals(info.sha256)) throw new IOException("APK incomplet ou empreinte incorrecte.");
            validateArchive(context,part);
            if(target.exists()&&!target.delete()) throw new IOException("Ancien téléchargement occupé.");
            if(!part.renameTo(target)) throw new IOException("Enregistrement APK impossible.");
            // Android may recreate Settings while granting the installation permission.
            // Keep the verified download addressable across that activity/process change.
            prefs(context).edit().putString("ready_version",info.version).putString("ready_sha256",info.sha256)
                    .putLong("ready_bytes",info.bytes).commit();
            return target;
        } finally {if(conn!=null)conn.disconnect();part.delete();}
    }
    public static File readyDownload(Context context) throws Exception {
        File file=new File(new File(context.getCacheDir(),"updates"),"modeliseur-update.apk");
        long bytes=prefs(context).getLong("ready_bytes",0);
        String hash=prefs(context).getString("ready_sha256","");
        if(bytes<=0||bytes>MAX_APK||!file.isFile()||file.length()!=bytes||!hash.matches("[a-f0-9]{64}"))return null;
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream input=new FileInputStream(file)){
            byte[] buffer=new byte[64*1024];int n;while((n=input.read(buffer))!=-1)digest.update(buffer,0,n);
        }
        if(!hex(digest.digest()).equals(hash))throw new IOException("APK enregistré corrompu : télécharge de nouveau la mise à jour.");
        validateArchive(context,file);
        return file;
    }
    private static String hex(byte[] bytes) {StringBuilder b=new StringBuilder();for(byte v:bytes)b.append(String.format(Locale.ROOT,"%02x",v&255));return b.toString();}
    @SuppressWarnings("deprecation")
    private static Set<String> signers(PackageInfo p) throws Exception {
        Signature[] signatures=Build.VERSION.SDK_INT>=28 && p.signingInfo!=null ? p.signingInfo.getApkContentsSigners() : p.signatures;
        if(signatures==null||signatures.length==0)throw new IOException("Signature APK manquante.");
        Set<String> out=new HashSet<>();for(Signature s:signatures)out.add(hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray())));return out;
    }
    @SuppressWarnings("deprecation")
    public static void validateArchive(Context c,File file) throws Exception {
        PackageManager pm=c.getPackageManager();int flags=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;
        PackageInfo candidate=pm.getPackageArchiveInfo(file.getAbsolutePath(),flags), installed=pm.getPackageInfo(c.getPackageName(),flags);
        if(candidate==null||!c.getPackageName().equals(candidate.packageName))throw new IOException("Ce fichier n'est pas une mise à jour du Modéliseur 3D.");
        long incoming=Build.VERSION.SDK_INT>=28?candidate.getLongVersionCode():candidate.versionCode;
        long current=Build.VERSION.SDK_INT>=28?installed.getLongVersionCode():installed.versionCode;
        if(incoming<=current)throw new IOException("Cette APK n'est pas plus récente que l'application installée.");
        if(!signers(candidate).equals(signers(installed))) throw new IOException("Signature différente : installation refusée pour protéger tes données. Ne désinstalle pas l'application.");
    }
}
