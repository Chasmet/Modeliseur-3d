package com.chasmet.modeliseur3d.diagnostics;

import android.app.ActivityManager;
import android.content.Context;
import android.os.*;
import com.chasmet.modeliseur3d.update.UpdateManager;
import com.chasmet.modeliseur3d.mcp.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.Locale;

/** Local checks run off the UI thread. Presence is never described as an inference test. */
public final class AppDiagnostics {
    private AppDiagnostics(){}
    private static final String[] MODELS={"isnet_anime_fp32.onnx","depth_anything_v2_small_fp32.onnx","triposr_encoder_int4.onnx","triposr_encoder_int4.onnx.data","triposr_decoder.onnx","triposr_decoder.onnx.data"};
    private static final String[] HASHES={"6a92a19a47e8197fb6dbcf85be14600806019831fedfe7f86eeeeffd4c40dbba","afb6a5c28f3b6bf1618c6e43f02073ef9dfdc70e937502d51603e57b0a1df10c","76dab077ff2768898ace523c72e0a017134bfd3628c4b15fd6f65cf304439089","fd5be249ab455b368812ba25095a2720e561ab0dcdeb42c81d922f7f67669a00","90b322cae570324f1d699f7307d4f056275b3bc8564db8568a5aebe403691424","9c3b1412ecbc803983003091939e449f9be3078c1c9a1880d5c5196a40a31d6c"};
    public static String report(Context c,boolean verify)throws Exception{
        DiagnosticLog.initialize(c);StringBuilder out=new StringBuilder("DIAGNOSTIC MODÉLISEUR 3D\n");
        out.append("Version : ").append(UpdateManager.currentVersion(c)).append(" · Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        out.append("Appareil : ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("\n");
        out.append("Calcul TripoSR : ").append(com.chasmet.modeliseur3d.model.TripoComputePolicy.summary(c)).append("\n");
        Runtime runtime=Runtime.getRuntime();out.append(String.format(Locale.FRANCE,"Mémoire application : %.0f / %.0f Mio · espace libre : %.0f Mio\n",(runtime.totalMemory()-runtime.freeMemory())/1048576.0,runtime.maxMemory()/1048576.0,c.getFilesDir().getUsableSpace()/1048576.0));
        ActivityManager manager=(ActivityManager)c.getSystemService(Context.ACTIVITY_SERVICE);
        if(manager!=null){ActivityManager.MemoryInfo info=new ActivityManager.MemoryInfo();manager.getMemoryInfo(info);out.append("Pression mémoire Android : ").append(info.lowMemory?"ÉLEVÉE":"normale").append("\n");}
        out.append("MCP : ").append(McpConnectionService.enabled(c)?"activé":"désactivé").append(" · transport ").append(PhoneMcpSettings.direct(c)?"direct téléphone":"relais").append("\n");
        out.append("État MCP : ").append(DiagnosticLog.sanitize(McpConnectionService.status(c))).append("\n");
        out.append("Accès ChatGPT : ").append(PhoneMcpSettings.prefs(c).getLong("external_client",0)>0?"accès authentifié observé":"non confirmé par ce diagnostic local").append("\n");
        out.append("Mise à jour auto : ").append(UpdateManager.automatic(c)?"activée":"désactivée").append("\n");
        out.append("Dernier contrôle GitHub : ").append(UpdateManager.prefs(c).getString("check_status","pas encore vérifié")).append("\n");
        out.append("Moteurs embarqués — ").append(verify?"contrôle SHA-256":"présence seulement").append(" :\n");
        for(int i=0;i<MODELS.length;i++){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            out.append("  ").append(MODELS[i]).append(" : ");
            try(InputStream in=c.getAssets().open("models/"+MODELS[i])){
                if(!verify){out.append(in.read()>=0?"présent":"ERREUR vide");}
                else{MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] b=new byte[1024*1024];int n;
                    while((n=in.read(b))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedException();digest.update(b,0,n);}
                    StringBuilder hex=new StringBuilder();for(byte value:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",value&255));
                    out.append(hex.toString().equals(HASHES[i])?"OK SHA-256":"ERREUR empreinte incorrecte");}
            }catch(IOException e){out.append("ERREUR manquant ou illisible");}
            out.append('\n');
        }
        File[] models=new File(c.getFilesDir(),"cloud_models").listFiles((folder,name)->name.endsWith(".glb"));
        out.append("Modèles sauvegardés : ").append(models==null?0:models.length).append("\n");
        var prefs=c.getSharedPreferences("offline_workshop",Context.MODE_PRIVATE);
        out.append("Dernière reconstruction : ").append(prefs.getString("lastQualityReport","aucune mesure enregistrée dans cette version")).append("\n");
        if(prefs.getBoolean("interruptedWork",false))out.append("Calcul en cours ou précédent calcul interrompu.\n");
        out.append("Ces contrôles ne mesurent pas la fidélité du modèle et ne lancent pas de calcul IA.\n\nJOURNAL LOCAL\n").append(DiagnosticLog.read());
        return out.toString();
    }
}
