package com.chasmet.modeliseur3d.mcp;

import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import java.io.InputStream;
import java.util.concurrent.*;

/** Configuration is explicit: a LAN URL is never presented as a working public ChatGPT endpoint. */
public final class PhoneMcpSettingsActivity extends AppCompatActivity {
    private final ExecutorService files=Executors.newSingleThreadExecutor();
    private TextView state;private EditText publicAddress,password;private CheckBox direct;
    private final ActivityResultLauncher<String[]> certificate=registerForActivityResult(new ActivityResultContracts.OpenDocument(),uri->{
        if(uri==null)return;String secret=password.getText().toString();state.setText("Vérification du certificat…");
        files.execute(()->{
            try(InputStream input=getContentResolver().openInputStream(uri)) {
                if(input==null)throw new java.io.IOException("Certificat introuvable.");PhoneMcpSettings.importCertificate(this,input,secret);
                show("Certificat PKCS12 importé. Enregistre la configuration pour démarrer HTTPS sur le port 8443.");
            } catch(Exception error) {show("Certificat refusé : vérifie le fichier, sa validité et son mot de passe.");}
        });
    });
    private void show(String message) {runOnUiThread(()->{if(!isDestroyed())state.setText(message);});}
    private void text(LinearLayout layout,String value,int size) {TextView label=new TextView(this);label.setText(value);label.setTextSize(size);layout.addView(label);}
    private Button button(LinearLayout layout,String label,Runnable action) {Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setOnClickListener(v->action.run());layout.addView(b);return b;}
    @Override protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);ScrollView scroll=new ScrollView(this);LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);
        int padding=(int)(20*getResources().getDisplayMetrics().density);layout.setPadding(padding,padding,padding,padding);scroll.addView(layout);setContentView(scroll);
        text(layout,"Serveur MCP du téléphone",26);
        text(layout,"Qualité par défaut : TripoSR Précis + détourage IS-Net, comme dans l’atelier manuel. L’application crée des modèles ; elle ne les anime pas.",16);
        direct=new CheckBox(this);direct.setText("MCP direct sur ce téléphone · sans relais Render");direct.setChecked(PhoneMcpSettings.direct(this));layout.addView(direct);
        text(layout,"Le serveur local reçoit les images et conserve les commandes et GLB sur ce téléphone. Pour que ChatGPT le joigne depuis Internet, il faut une adresse HTTPS publique accessible via ta box. Une adresse Wi-Fi seule fonctionne uniquement sur ton réseau local.",16);
        publicAddress=new EditText(this);publicAddress.setSingleLine(true);publicAddress.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);publicAddress.setHint("https://ton-domaine:8443");publicAddress.setText(PhoneMcpSettings.publicBase(this));layout.addView(publicAddress);
        text(layout,"HTTPS intégré : importe un certificat PKCS12 (.p12) contenant le certificat public valide, sa chaîne et la clé privée. Le certificat doit correspondre au domaine. La box redirige le port externe vers le port 8443 du téléphone.",15);
        password=new EditText(this);password.setSingleLine(true);password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);password.setHint("Mot de passe du fichier .p12");layout.addView(password);
        button(layout,"Importer le certificat HTTPS",()->certificate.launch(new String[]{"*/*"}));
        button(layout,"Enregistrer la connexion",this::save);
        button(layout,"Copier l’URL MCP locale",()->copy(PhoneMcpSettings.localUrl(this),"Lien local copié · utilisable sur le Wi-Fi de la box."));
        button(layout,"Copier l’URL MCP HTTPS publique",()->{
            String url=PhoneMcpSettings.publicUrl(this);if(url.isEmpty())state.setText("Renseigne et enregistre d’abord l’adresse HTTPS publique.");
            else copy(url,"Lien privé copié. L’accès Internet doit encore être testé depuis l’extérieur de ta box.");
        });
        button(layout,"Autoriser l’activité écran éteint",this::battery);
        button(layout,"Ouvrir les réglages de l’application",()->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()))));
        state=new TextView(this);layout.addView(state);refresh();
        text(layout,"Sur certains téléphones, active aussi le lancement automatique et l’activité en arrière-plan dans les réglages de batterie du constructeur. Un téléphone complètement éteint ou une application arrêtée de force ne peut pas recevoir de commandes.",15);
    }
    private void refresh() {
        PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);
        boolean exempt=Build.VERSION.SDK_INT<23 || (power!=null&&power.isIgnoringBatteryOptimizations(getPackageName()));
        state.setText(McpConnectionService.status(this)+"\nActivité écran éteint : "+(exempt?"autorisée par Android":"autorisation batterie à accorder")
                +"\nCertificat HTTPS : "+(PhoneMcpSettings.certificate(this).isFile()?"importé":"absent")
                +"\nAdresse locale : "+PhoneMcpSettings.lanAddress()+":"+PhoneMcpSettings.HTTP_PORT
                +"\nUne adresse publique enregistrée ne prouve pas encore que ta box est configurée.");
    }
    private void save() {
        try {
            if(!getSharedPreferences("mcp_background",0).getString("command","").isEmpty()) {state.setText("Termine la commande conservée avant de changer de connexion. Les fichiers sont préservés.");return;}
            String address=PhoneMcpSettings.validatePublicBase(publicAddress.getText().toString());
            boolean enabled=McpConnectionService.enabled(this);stopService(new Intent(this,McpConnectionService.class));
            PhoneMcpSettings.prefs(this).edit().putBoolean("direct",direct.isChecked()).putString("public_base",address).commit();
            if(enabled)McpConnectionService.start(this);refresh();
        } catch(Exception error) {state.setText(error.getMessage()==null?"Configuration invalide.":error.getMessage());}
    }
    private void copy(String value,String message) {((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("MCP Modéliseur",value));state.setText(message);}
    private void battery() {
        if(Build.VERSION.SDK_INT<23) {refresh();return;}
        try {startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:"+getPackageName())));}
        catch(RuntimeException e) {startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));}
    }
    @Override protected void onResume() {super.onResume();if(state!=null)refresh();}
    @Override protected void onDestroy() {files.shutdownNow();super.onDestroy();}
}
