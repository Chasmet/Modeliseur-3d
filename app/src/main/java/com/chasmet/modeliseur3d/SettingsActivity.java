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

public final class SettingsActivity extends AppCompatActivity {
    private TextView status,last;
    private Button check,install;
    private ReleaseInfo available;
    private File ready;
    private boolean waitingPermission;
    private void ui(Runnable r){runOnUiThread(()->{if(!isDestroyed())r.run();});}
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);ScrollView scroll=new ScrollView(this);LinearLayout layout=new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);int p=(int)(20*getResources().getDisplayMetrics().density);layout.setPadding(p,p,p,p);scroll.addView(layout);setContentView(scroll);
        TextView heading=new TextView(this);heading.setText("Réglages");heading.setTextSize(27);layout.addView(heading);
        TextView version=new TextView(this);version.setText("Version actuelle : "+UpdateManager.currentVersion(this));layout.addView(version);
        TextView title=new TextView(this);title.setText("Mise à jour automatique");title.setTextSize(22);layout.addView(title);
        Switch automatic=new Switch(this);automatic.setText("Mise à jour automatique");automatic.setChecked(UpdateManager.automatic(this));layout.addView(automatic);
        automatic.setOnCheckedChangeListener((v,on)->{UpdateManager.prefs(this).edit().putBoolean("automatic",on).apply();UpdateManager.schedule(this);});
        last=new TextView(this);layout.addView(last);status=new TextView(this);status.setText("Vérifie les mises à jour disponibles.");layout.addView(status);
        check=new Button(this);check.setText("Vérifier les mises à jour");check.setAllCaps(false);layout.addView(check);check.setOnClickListener(v->check());
        install=new Button(this);install.setText("Télécharger et installer");install.setAllCaps(false);install.setEnabled(false);layout.addView(install);install.setOnClickListener(v->{if(ready!=null)installReady();else download();});
        TextView note=new TextView(this);note.setText("Source : GitHub Releases publiques. Aucune clé API requise. Android te demandera de confirmer l'installation. Tes projets restent dans l'application.");layout.addView(note);timestamp();check();
    }
    private void timestamp(){long t=UpdateManager.prefs(this).getLong("checked",0);last.setText(t==0?"Aucune vérification effectuée.":"Dernière vérification : "+DateFormat.getDateTimeInstance().format(new Date(t)));}
    private void check(){
        check.setEnabled(false);install.setEnabled(false);status.setText("Vérification en cours…");
        UpdateManager.executor.execute(()->{
            try{ReleaseInfo found=UpdateManager.check(this);ui(()->{available=found;status.setText(found==null?"Application à jour.":"Nouvelle version disponible : "+found.version);install.setEnabled(found!=null);timestamp();});}
            catch(Exception e){ui(()->status.setText(e.getMessage()));}
            finally{ui(()->check.setEnabled(true));}
        });
    }
    private void download(){
        ReleaseInfo chosen=available;if(chosen==null)return;check.setEnabled(false);install.setEnabled(false);
        UpdateManager.executor.execute(()->{
            try{File file=UpdateManager.download(this,chosen,n->ui(()->status.setText("Téléchargement : "+n+" %")));
                ui(()->{ready=file;status.setText("Mise à jour prête à être installée.");install.setText("Installer la mise à jour");install.setEnabled(true);installReady();});}
            catch(Exception e){ui(()->{status.setText(e.getMessage());install.setEnabled(true);});}
            finally{ui(()->check.setEnabled(true));}
        });
    }
    private void installReady(){
        if(ready==null||!ready.isFile())return;
        if(Build.VERSION.SDK_INT>=26&&!getPackageManager().canRequestPackageInstalls()){
            waitingPermission=true;status.setText("Autorise les installations pour Modéliseur 3D, puis reviens ici.");
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())));return;
        }
        Uri uri=FileProvider.getUriForFile(this,getPackageName()+".fileprovider",ready);
        Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri("Mise à jour APK",uri));
        try{startActivity(intent);}catch(ActivityNotFoundException e){status.setText("Installateur Android indisponible.");}
    }
    @Override protected void onResume(){super.onResume();if(waitingPermission){waitingPermission=false;installReady();}}
}
