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
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** A local test, a public-address test and an actual outside access have separate states. */
public final class PhoneMcpSettingsActivity extends AppCompatActivity {
    private final ExecutorService files=Executors.newSingleThreadExecutor();
    private TextView state;
    private TextView automaticState;
    private final Handler screen=new Handler(Looper.getMainLooper());
    private final Runnable updateAutomatic=new Runnable() {public void run() {if(!isDestroyed()&&automaticState!=null) {automaticState.setText(PhonePublicConnection.report(PhoneMcpSettingsActivity.this));screen.postDelayed(this,4000);}}};
    private EditText publicAddress,password;
    private CheckBox direct;
    private final ActivityResultLauncher<String[]> certificate=registerForActivityResult(new ActivityResultContracts.OpenDocument(),uri->{
        if(uri==null)return;
        String secret=password.getText().toString();password.setText("");
        state.setText("Vérification du certificat…");
        files.execute(()->{
            try(InputStream input=getContentResolver().openInputStream(uri)) {
                if(input==null)throw new IOException("Certificat introuvable.");
                PhoneMcpSettings.importCertificate(this,input,secret);
                PhoneMcpSettings.prefs(this).edit().putBoolean("automatic_https",false).commit();
                show("Certificat importé. Enregistre la configuration pour redémarrer HTTPS.");
            } catch(Exception error) {show("Certificat refusé : vérifie sa validité et son mot de passe. Le précédent est conservé.");}
        });
    });
    private void show(String message) {runOnUiThread(()->{if(!isDestroyed())state.setText(message);});}
    private void text(LinearLayout layout,String value,int size) {
        TextView label=new TextView(this);label.setText(value);label.setTextSize(size);layout.addView(label);
    }
    private void button(LinearLayout layout,String label,Runnable action) {
        Button button=new Button(this);button.setText(label);button.setAllCaps(false);button.setOnClickListener(v->action.run());layout.addView(button);
    }
    @Override protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        ScrollView scroll=new ScrollView(this);LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);
        int padding=(int)(20*getResources().getDisplayMetrics().density);layout.setPadding(padding,padding,padding,padding);
        scroll.addView(layout);setContentView(scroll);
        text(layout,"Serveur MCP du téléphone",26);
        text(layout,"Les images, le calcul 3D et les modèles restent sur ce téléphone. Qualité Précis + IS-Net par défaut, comme dans l’atelier manuel.",16);
        direct=new CheckBox(this);direct.setText("Serveur direct sur ce téléphone · sans relais");
        direct.setChecked(PhoneMcpSettings.direct(this));layout.addView(direct);
        button(layout,"Activer le serveur du téléphone",()->{
            direct.setChecked(true);
            if(save()) {McpConnectionService.setEnabled(this,true);refresh();}
        });
        button(layout,"Désactiver le MCP",()->{McpConnectionService.setEnabled(this,false);refresh();});
        button(layout,"Diagnostic réseau",this::diagnose);
        button(layout,"Tester le serveur local",this::testLocal);
        text(layout,"Accès depuis Internet",22);
        text(layout,"HTTPS automatique détecte l’IPv4 publique et la passerelle, tente PCP/NAT-PMP/UPnP et renouvelle le certificat IP Let’s Encrypt avant expiration. Le port externe 80 est nécessaire à la validation du certificat ; le diagnostic affiche la règle précise si la box refuse l’ouverture automatique.",16);
        button(layout,"Activer HTTPS automatique",()->new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Certificat public Let’s Encrypt")
                .setMessage("Un certificat gratuit pour ton IP publique sera demandé et renouvelé depuis ce téléphone. En activant, tu acceptes les conditions Let’s Encrypt consultables ci-dessous. La connexion publique sans authentification expose uniquement l’état non sensible et les capacités de l’application. Les 6 outils complets restent sur le lien privé.")
                .setNeutralButton("Conditions",(dialog,which)->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://letsencrypt.org/repository/"))))
                .setNegativeButton("Annuler",null).setPositiveButton("Activer",(dialog,which)->{
                    if(!getSharedPreferences("mcp_background",0).getString("command","").isEmpty()) {state.setText("Termine la commande conservée avant de changer de connexion.");return;}
                    direct.setChecked(true);PhoneMcpSettings.prefs(this).edit().putBoolean("automatic_https",true).putBoolean("direct",true).commit();
                    if(McpConnectionService.enabled(this))McpConnectionService.restart(this);else McpConnectionService.setEnabled(this,true);
                    refresh();
                }).show());
        button(layout,"Renouveler le certificat",()->{if(PhoneMcpSettings.prefs(this).getBoolean("automatic_https",false))McpConnectionService.renewCertificate(this);else state.setText("Active d’abord HTTPS automatique.");});
        button(layout,"Copier l’URL ChatGPT sans authentification",()->{
            if(PhoneMcpSettings.publicBase(this).isEmpty()||!PhoneMcpSettings.prefs(this).getBoolean("https_running",false))state.setText("URL indisponible tant que la détection publique et le certificat n’ont pas abouti.");
            else copy(PhoneMcpSettings.connectionUrl(this),"URL copiée · 2 outils de lecture non sensibles. La connexion extérieure reste à vérifier.");
        });
        automaticState=new TextView(this);automaticState.setTextIsSelectable(true);layout.addView(automaticState);screen.post(updateAutomatic);
        text(layout,"Configuration HTTPS manuelle (avancé)",20);
        publicAddress=new EditText(this);publicAddress.setSingleLine(true);
        publicAddress.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        publicAddress.setHint("https://domaine:8443 ou https://[IPv6]:8443");
        publicAddress.setText(PhoneMcpSettings.publicBase(this));layout.addView(publicAddress);
        text(layout,"Alternative manuelle : importer un certificat PKCS12 (.p12) pour cette adresse. Cette option désactive la gestion automatique lorsqu’elle est choisie.",15);
        password=new EditText(this);password.setSingleLine(true);
        password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setHint("Mot de passe du fichier .p12");layout.addView(password);
        button(layout,"Importer le certificat HTTPS",()->certificate.launch(new String[]{"*/*"}));
        button(layout,"Utiliser la configuration manuelle",()->{if(save(true))refresh();});
        button(layout,"Tester HTTPS à l’adresse publique",this::testPublic);
        button(layout,"Copier l’URL MCP HTTPS privée",()->{
            String url=PhoneMcpSettings.publicUrl(this);
            if(url.isEmpty())state.setText("Enregistre d’abord l’adresse HTTPS publique.");
            else copy(url,"Lien d’accès privé copié. Ne le publie pas. Le diagnostic précise si un accès extérieur a été observé.");
        });
        button(layout,"Copier l’URL MCP locale privée",()->copy(PhoneMcpSettings.localUrl(this),"Lien privé local copié. Il est réservé au téléphone ; le réseau utilise HTTPS."));
        text(layout,"Fonctionnement en arrière-plan",22);
        button(layout,"Autoriser l’activité écran éteint",this::battery);
        button(layout,"Ouvrir les réglages Android de l’application",()->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()))));
        state=new TextView(this);state.setTextIsSelectable(true);layout.addView(state);
        text(layout,"Outils : état et capacités de l’application, liste des modèles et images, création à partir d’images, suivi et récupération du GLB. L’application modélise ; elle ne crée pas d’animation.",15);
        text(layout,"Après un arrêt forcé, rouvre l’application. Certains téléphones demandent aussi d’autoriser le lancement automatique dans les réglages de batterie.",15);
        refresh();
    }
    private void refresh() {
        PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);
        boolean exempt=Build.VERSION.SDK_INT<23 || (power!=null&&power.isIgnoringBatteryOptimizations(getPackageName()));
        state.setText(McpConnectionService.status(this)
                +"\nActivité écran éteint : "+(exempt?"autorisée par Android":"autorisation batterie à accorder")
                +"\nCertificat HTTPS : "+(PhoneMcpSettings.hasCertificate(this)?"importé · validité contrôlée au démarrage":"absent")
                +"\nLocal : 127.0.0.1:"+PhoneMcpSettings.httpPort(this)
                +"\nHTTPS : port "+PhoneMcpSettings.httpsPort(this)
                +"\nUne adresse enregistrée ne confirme pas l’accès depuis ChatGPT.");
    }
    private boolean save() {
        return save(false);
    }
    private boolean save(boolean manual) {
        try {
            if(!getSharedPreferences("mcp_background",0).getString("command","").isEmpty()) {
                state.setText("Termine la commande conservée avant de changer de connexion. Les fichiers sont préservés.");return false;
            }
            boolean automatic=!manual&&PhoneMcpSettings.prefs(this).getBoolean("automatic_https",false);
            String address=automatic?PhoneMcpSettings.publicBase(this):PhoneMcpSettings.validatePublicBase(publicAddress.getText().toString());
            boolean enabled=McpConnectionService.enabled(this);
            // The previous service must be destroyed before its ports/executors are reused.
            PhoneMcpSettings.prefs(this).edit().putBoolean("direct",direct.isChecked()).putBoolean("automatic_https",automatic).putString("public_base",address).remove("external_client").commit();
            if(enabled)McpConnectionService.restart(this);
            return true;
        } catch(Exception error) {state.setText(error.getMessage()==null?"Configuration invalide.":error.getMessage());return false;}
    }
    private void diagnose() {
        state.setText("Lecture du réseau…");
        files.execute(()->{
            try {
                JSONObject snapshot=PhoneNetworkDiagnostics.snapshot(this);
                String external=PhoneMcpSettings.prefs(this).getLong("external_client",0)>0?"Accès extérieur HTTPS authentifié observé depuis le dernier changement réseau.":"Accès extérieur : non vérifié.";
                show(PhoneNetworkDiagnostics.summary(snapshot)+"\n"+external
                        +"\n"+PhonePublicConnection.report(this));
            } catch(Exception error) {show("Diagnostic réseau indisponible. Le serveur et les fichiers locaux restent conservés.");}
        });
    }
    private void testLocal() {
        state.setText("Test MCP sur le téléphone…");
        files.execute(()->{
            try(Socket socket=new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1",PhoneMcpSettings.httpPort(this)),3000);socket.setSoTimeout(5000);
                byte[] body="{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}".getBytes(StandardCharsets.UTF_8);
                String head="POST /mcp HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer "+PhoneMcpSettings.token(this)
                        +"\r\nContent-Type: application/json\r\nMCP-Protocol-Version: 2025-11-25\r\nContent-Length: "+body.length+"\r\n\r\n";
                socket.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(body);socket.getOutputStream().flush();
                String reply=read(socket.getInputStream(),65536);
                int separator=reply.indexOf("\r\n\r\n");
                if(!reply.startsWith("HTTP/1.1 200")||separator<0)throw new IOException("Réponse locale invalide.");
                int tools=new JSONObject(reply.substring(separator+4)).getJSONObject("result").getJSONArray("tools").length();
                PhoneMcpSettings.prefs(this).edit().putLong("local_test_at",System.currentTimeMillis()).apply();
                show("Serveur MCP local opérationnel · "+tools+" outils découverts.\nCe test ne vérifie pas l’accès depuis Internet.");
            } catch(Exception error) {show("Serveur local injoignable. Active le serveur du téléphone et vérifie que le port local affiché est libre.");}
        });
    }
    private void testPublic() {
        String base=PhoneMcpSettings.publicBase(this);
        if(base.isEmpty()) {state.setText("Enregistre d’abord l’adresse HTTPS publique.");return;}
        state.setText("Vérification HTTPS sans désactiver la sécurité du certificat…");
        files.execute(()->{
            javax.net.ssl.HttpsURLConnection connection=null;
            try {
                connection=(javax.net.ssl.HttpsURLConnection)new URL(base+"/health").openConnection();
                connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(10000);connection.setReadTimeout(10000);
                if(connection.getResponseCode()!=200)throw new IOException("Endpoint indisponible.");
                JSONObject health=new JSONObject(read(connection.getInputStream(),8192));
                if(!"android".equals(health.optString("server"))||!health.optBoolean("mcp"))throw new IOException("Serveur inattendu.");
                show("HTTPS et certificat vérifiés depuis le téléphone.\nIl reste à tester depuis un autre réseau ou ChatGPT : la box peut autoriser son propre réseau seulement.");
            } catch(Exception error) {show("HTTPS non confirmé. Vérifie l’adresse, le certificat, sa chaîne et le port entrant de la box. Un échec depuis le même Wi-Fi ne prouve pas que l’accès extérieur est bloqué.");}
            finally {if(connection!=null)connection.disconnect();}
        });
    }
    private static String read(InputStream input,int limit) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;
        while((count=input.read(buffer))!=-1) {if(out.size()+count>limit)throw new IOException("Réponse trop grande.");out.write(buffer,0,count);}
        return out.toString("UTF-8");
    }
    private void copy(String value,String message) {
        ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("MCP Modéliseur",value));state.setText(message);
    }
    private void battery() {
        if(Build.VERSION.SDK_INT<23) {refresh();return;}
        try {startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:"+getPackageName())));}
        catch(RuntimeException e) {startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));}
    }
    @Override protected void onResume() {super.onResume();if(state!=null)refresh();}
    @Override protected void onDestroy() {screen.removeCallbacksAndMessages(null);files.shutdownNow();super.onDestroy();}
}
