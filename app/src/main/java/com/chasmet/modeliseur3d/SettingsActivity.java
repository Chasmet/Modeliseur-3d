package com.chasmet.modeliseur3d;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import com.chasmet.modeliseur3d.update.*;
import java.io.File;
import java.text.DateFormat;
import java.util.Date;
import com.chasmet.modeliseur3d.diagnostics.*;
import java.util.concurrent.*;

public final class SettingsActivity extends AppCompatActivity {
    private TextView status,last;
    private Button check,install;
    private ReleaseInfo available;
    private File ready;
    private boolean waitingPermission;
    private final ExecutorService diagnostics=Executors.newSingleThreadExecutor();
    private TextView logText;
    private String diagnosticReport="";
    private Button refreshLogs,verifyModels,copyLogs;
    private void ui(Runnable r){runOnUiThread(()->{if(!isDestroyed())r.run();});}
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);DiagnosticLog.initialize(this);ScrollView scroll=new ScrollView(this);LinearLayout layout=new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);int p=(int)(20*getResources().getDisplayMetrics().density);layout.setPadding(p,p,p,p);scroll.addView(layout);setContentView(scroll);
        TextView heading=new TextView(this);heading.setText("Réglages");heading.setTextSize(27);layout.addView(heading);
        TextView version=new TextView(this);version.setText("Version actuelle : "+UpdateManager.currentVersion(this));layout.addView(version);
        Button mcp=new Button(this);mcp.setText("Connexion permanente et serveur MCP direct");mcp.setAllCaps(false);layout.addView(mcp);
        mcp.setOnClickListener(v->startActivity(new Intent(this,com.chasmet.modeliseur3d.mcp.PhoneMcpSettingsActivity.class)));
        TextView title=new TextView(this);title.setText("Mise à jour automatique");title.setTextSize(22);layout.addView(title);
        Switch automatic=new Switch(this);automatic.setText("Mise à jour automatique");automatic.setChecked(UpdateManager.automatic(this));layout.addView(automatic);
        automatic.setOnCheckedChangeListener((v,on)->{UpdateManager.prefs(this).edit().putBoolean("automatic",on).apply();UpdateManager.schedule(this);});
        last=new TextView(this);layout.addView(last);status=new TextView(this);status.setText("Vérifie les mises à jour disponibles.");layout.addView(status);
        check=new Button(this);check.setText("Vérifier les mises à jour");check.setAllCaps(false);layout.addView(check);check.setOnClickListener(v->check());
        install=new Button(this);install.setText("Télécharger et installer");install.setAllCaps(false);install.setEnabled(false);layout.addView(install);install.setOnClickListener(v->{if(ready!=null)installReady();else download();});
        TextView note=new TextView(this);note.setText("Source : GitHub Releases publiques. Aucune clé API requise. Android te demandera de confirmer l'installation. Tes projets restent dans l'application.");layout.addView(note);timestamp();check();
        TextView diagnosticTitle=new TextView(this);diagnosticTitle.setText("Diagnostic et logs copiables");diagnosticTitle.setTextSize(22);layout.addView(diagnosticTitle);
        refreshLogs=new Button(this);refreshLogs.setText("Actualiser le diagnostic");refreshLogs.setAllCaps(false);layout.addView(refreshLogs);refreshLogs.setOnClickListener(v->refreshDiagnostics(false));
        verifyModels=new Button(this);verifyModels.setText("Vérifier les fichiers des moteurs IA");verifyModels.setAllCaps(false);layout.addView(verifyModels);verifyModels.setOnClickListener(v->refreshDiagnostics(true));
        copyLogs=new Button(this);copyLogs.setText("Copier tous les logs");copyLogs.setAllCaps(false);layout.addView(copyLogs);copyLogs.setEnabled(false);
        copyLogs.setOnClickListener(v->{((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Diagnostic Modéliseur 3D",diagnosticReport));Toast.makeText(this,"Diagnostic et logs copiés",Toast.LENGTH_SHORT).show();});
        Button clear=new Button(this);clear.setText("Effacer uniquement le journal");clear.setAllCaps(false);layout.addView(clear);
        clear.setOnClickListener(v->new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Effacer le journal ?").setMessage("Seules les lignes du journal sont effacées. Photos, modèles et réglages restent conservés.").setNegativeButton("Annuler",null).setPositiveButton("Effacer",(dialog,which)->{DiagnosticLog.clear();refreshDiagnostics(false);}).show());
        logText=new TextView(this);logText.setTextIsSelectable(true);logText.setTextSize(12);logText.setTypeface(android.graphics.Typeface.MONOSPACE);layout.addView(logText);refreshDiagnostics(false);
    }
    private void timestamp(){long t=UpdateManager.prefs(this).getLong("checked",0);last.setText(t==0?"Aucune vérification effectuée.":"Dernière vérification : "+DateFormat.getDateTimeInstance().format(new Date(t)));}
    private void check(){
        ready=null;available=null;install.setText("Télécharger et installer");check.setEnabled(false);install.setEnabled(false);status.setText("Vérification en cours…");
        UpdateManager.executor.execute(()->{
            File saved=null;
            try{saved=UpdateManager.readyDownload(this);}catch(Exception e){DiagnosticLog.record("INFO","APK enregistré non réutilisable",e);}
            final File recovered=saved;
            if(recovered!=null)ui(()->showReady(recovered));
            try{ReleaseInfo found=UpdateManager.check(this);ui(()->{
                available=found;
                if(recovered!=null&&(found==null||!ReleaseInfo.newer(found.version,UpdateManager.prefs(this).getString("ready_version",""))))showReady(recovered);
                else{ready=null;install.setText("Télécharger et installer");status.setText(found==null?"Application à jour.":"Nouvelle version disponible : "+found.version);install.setEnabled(found!=null);}
                timestamp();
            });}
            catch(Exception e){ui(()->{if(recovered!=null)showReady(recovered);else status.setText(e.getMessage());});}
            finally{ui(()->check.setEnabled(true));}
        });
    }
    private void showReady(File file){
        ready=file;install.setText("Installer la mise à jour");install.setEnabled(true);
        status.setText("Mise à jour "+UpdateManager.prefs(this).getString("ready_version","")+" prête à être installée.");
        if(UpdateManager.prefs(this).getBoolean("install_permission_pending",false)&&
                (Build.VERSION.SDK_INT<26||getPackageManager().canRequestPackageInstalls()))installReady();
    }
    private void download(){
        ReleaseInfo chosen=available;if(chosen==null)return;check.setEnabled(false);install.setEnabled(false);
        DiagnosticLog.record("INFO","Téléchargement APK "+chosen.version);
        UpdateManager.executor.execute(()->{
            try{File file=UpdateManager.download(this,chosen,n->ui(()->status.setText("Téléchargement : "+n+" %")));
                ui(()->{ready=file;status.setText("Mise à jour prête à être installée.");install.setText("Installer la mise à jour");install.setEnabled(true);installReady();});}
            catch(Exception e){DiagnosticLog.record("ERREUR","Téléchargement APK",e);ui(()->{status.setText(e.getMessage());install.setEnabled(true);});}
            finally{ui(()->check.setEnabled(true));}
        });
    }
    private void installReady(){
        if(ready==null||!ready.isFile())return;
        if(Build.VERSION.SDK_INT>=26&&!getPackageManager().canRequestPackageInstalls()){
            waitingPermission=true;UpdateManager.prefs(this).edit().putBoolean("install_permission_pending",true).apply();status.setText("Autorise les installations pour Modéliseur 3D, puis reviens ici.");
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())));return;
        }
        UpdateManager.prefs(this).edit().putBoolean("install_permission_pending",false).apply();
        Uri uri=FileProvider.getUriForFile(this,getPackageName()+".fileprovider",ready);
        Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri("Mise à jour APK",uri));
        try{startActivity(intent);DiagnosticLog.record("INFO","Installateur Android ouvert ; confirmation requise.");}catch(ActivityNotFoundException|SecurityException e){status.setText("L’installation n’a pas pu s’ouvrir. Réessaie avec Installer la mise à jour.");DiagnosticLog.record("ERREUR","Installateur Android",e);}
    }
    private void refreshDiagnostics(boolean verify){
        refreshLogs.setEnabled(false);verifyModels.setEnabled(false);copyLogs.setEnabled(false);logText.setText(verify?"Vérification des empreintes des moteurs IA…":"Lecture du diagnostic…");
        diagnostics.execute(()->{
            try{String report=AppDiagnostics.report(this,verify);ui(()->{diagnosticReport=report;logText.setText(report);copyLogs.setEnabled(true);});}
            catch(Exception e){DiagnosticLog.record("ERREUR","Diagnostic",e);ui(()->logText.setText("Diagnostic indisponible : "+DiagnosticLog.sanitize(e.getMessage())));}
            finally{ui(()->{refreshLogs.setEnabled(true);verifyModels.setEnabled(true);});}
        });
    }
    @Override protected void onResume(){super.onResume();if(waitingPermission){waitingPermission=false;if(Build.VERSION.SDK_INT<26||getPackageManager().canRequestPackageInstalls())installReady();else status.setText("Installation non autorisée. Appuie sur Installer pour réessayer.");}}
    @Override protected void onDestroy(){diagnostics.shutdownNow();super.onDestroy();}
}
