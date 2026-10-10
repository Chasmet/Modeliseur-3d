package com.chasmet.modeliseur3d;

import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import com.chasmet.modeliseur3d.model.*;
import com.chasmet.modeliseur3d.util.OfflineImageImporter;
import com.chasmet.modeliseur3d.performance.ProcessingPowerLock;
import com.chasmet.modeliseur3d.update.UpdateManager;
import com.chasmet.modeliseur3d.diagnostics.DiagnosticLog;
import com.chasmet.modeliseur3d.mcp.McpBridgeSession;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Local workspace: no HTTP client, no server registration, no model downloads. */
public final class Offline3DActivity extends AppCompatActivity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    public static final String EXTRA_SINGLE_IMAGE="triposr_single_image";
    public static final String EXTRA_MCP_COMMAND_ID="mcp_command_id";
    public static final String EXTRA_MCP_MODE="mcp_mode";
    public static final String EXTRA_MCP_QUALITY="mcp_quality";
    public static final String EXTRA_MCP_SMOOTHING="mcp_smoothing";
    private TextView modeHelp,status,depthLabel;private ImageView preview;private SeekBar depth,tolerance;
    private Button selectDetail,detectFaceDetail;private CheckBox assistDetail;private SeekBar detailStrength;private TextView detailLabel;private TripoDetailRegion detailRegion;
    private Spinner textureQuality;private SeekBar photoFidelity;private TextView photoFidelityLabel;private CheckBox fineDetail,maximumPower,tripoInputBoost;private Button photoCheck;
    private Spinner quality,shape,engine;private Button inspect,compare,cancel;private CheckBox ai,depthAi,smoothing;private Button generate,open,export,choose,rotate,gallery;
    private final ImageView[] previews=new ImageView[4];
    private final Button[] choices=new Button[4],rotations=new Button[4];
    private final Button[] mirrors=new Button[4];
    private EditText projectName;private volatile Thread runningThread;
    private final LinearLayout[] cards=new LinearLayout[4];
    private static final String[] VIEWS={"Face","Dos","Profil droit","Profil gauche"};
    private CheckBox fourViews;private TextView countLabel;private int selectedSlot;
    private ProgressBar progress;private volatile boolean busy;private volatile boolean cancelled;private String lastId="";
    private String mcpCommandId="",mcpMode="",mcpQuality="precise";private boolean mcpSmoothing=true;private volatile boolean mcpGenerating;
    private android.content.SharedPreferences prefs(){return getSharedPreferences("offline_workshop",MODE_PRIVATE);}
    private boolean isMcp(){return mcpCommandId.matches("[a-f0-9]{32}");}
    private File mcpFolder(){return new File(getFilesDir(),"mcp_inputs/"+mcpCommandId);}
    private File source(){return source(0);}
    private File source(int slot){return isMcp()?new File(mcpFolder(),"image-"+slot+".png"):new File(getFilesDir(),"offline-workshop-image"+(slot==0?"":"-"+slot)+".png");}
    private File cutout(){return cutout(0);}
    private File cutout(int slot){return isMcp()?new File(mcpFolder(),"cutout-"+slot+".png"):new File(getFilesDir(),"offline-workshop-cutout"+(slot==0?"":"-"+slot)+".png");}
    private File depthCache(){return depthCache(0);}
    private File depthCache(int slot){return isMcp()?new File(mcpFolder(),"depth-"+slot+".bin"):new File(getFilesDir(),"offline-workshop-depth"+(slot==0?"":"-"+slot)+".bin");}
    private String key(String name,int slot){return (isMcp()?"mcp_"+mcpCommandId+"_":"")+name+(slot==0?"":"_"+slot);}
    private File learnedCache(int slot){return isMcp()?new File(mcpFolder(),"triposr-"+slot+".bin"):new File(getFilesDir(),"offline-workshop-triposr-"+slot+".bin");}
    private File model(String id){return new File(getFilesDir(),"cloud_models/"+id+".glb");}
    private final ActivityResultLauncher<String[]> picker=registerForActivityResult(new ActivityResultContracts.OpenDocument(),this::importImage);
    private final ActivityResultLauncher<String> exporter=registerForActivityResult(new ActivityResultContracts.CreateDocument("model/gltf-binary"),uri->{
        if(uri==null||!lastId.matches("[a-f0-9]{32}"))return;File selected=model(lastId);
        work(()->{try(InputStream in=new FileInputStream(selected);OutputStream out=getContentResolver().openOutputStream(uri,"w")){
            if(out==null)throw new IOException("Dossier d'export indisponible.");byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);
        }message("GLB exporté hors connexion.");});
    });
    private void ui(Runnable action){runOnUiThread(()->{if(!isDestroyed())action.run();});}
    private void message(String value){DiagnosticLog.record("INFO",value);ui(()->status.setText(value));}
    private interface Task{void run()throws Exception;}
    private void reportMcpFailure(String value){
        if(mcpGenerating&&isMcp()){
            mcpGenerating=false;
            McpBridgeSession.reportError(this,mcpCommandId,value);
        }
    }
    private void work(Task action){
        if(busy||isDestroyed())return;busy=true;cancelled=false;prefs().edit().putBoolean("interruptedWork",true).apply();getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);buttons();worker.execute(()->{
            runningThread=Thread.currentThread();
            try(ProcessingPowerLock lock=ProcessingPowerLock.acquire(this,"atelier-local")){
                action.run();
            }catch(CancellationException e){
                reportMcpFailure("Calcul local arrêté sur le téléphone.");
                message("Opération arrêtée. Les modèles précédents sont conservés.");
            }catch(OutOfMemoryError e){
                DiagnosticLog.record("ERREUR","Mémoire insuffisante dans l’atelier",e);
                reportMcpFailure("Mémoire Android insuffisante pendant la reconstruction locale.");
                message("Mémoire de l’application insuffisante. Choisis le moteur Silhouettes et le détail Rapide pour un calcul plus léger.");
            }catch(Exception e){
                DiagnosticLog.record("ERREUR","Opération atelier",e);
                String value=e.getMessage()==null?"Opération locale impossible.":e.getMessage();
                reportMcpFailure(value);
                message(value);
            }finally{
                runningThread=null;prefs().edit().putBoolean("interruptedWork",false).apply();
                ui(()->{busy=false;getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);buttons();});
            }
        });
    }
    private void checkpoint(){if(cancelled||Thread.currentThread().isInterrupted())throw new CancellationException();}
    private TextView text(LinearLayout p,String value,int size){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(0xFF121722);t.setPadding(0,12,0,8);p.addView(t);return t;}
    private Button button(LinearLayout p,String value,Runnable action){Button b=new Button(this);b.setText(value);b.setAllCaps(false);p.addView(b);b.setOnClickListener(v->action.run());return b;}
    private Spinner spinner(LinearLayout p,String... choices){Spinner s=new Spinner(this);s.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,choices));p.addView(s);return s;}
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);DiagnosticLog.initialize(this);
        String incoming=getIntent().getStringExtra(EXTRA_MCP_COMMAND_ID);
        if(incoming!=null&&incoming.matches("[a-f0-9]{32}")){
            mcpCommandId=incoming;
            mcpMode=getIntent().getStringExtra(EXTRA_MCP_MODE);
            if(mcpMode==null)mcpMode="triposr_single";
            mcpQuality=getIntent().getStringExtra(EXTRA_MCP_QUALITY);
            if(mcpQuality==null)mcpQuality="precise";
            mcpSmoothing=getIntent().getBooleanExtra(EXTRA_MCP_SMOOTHING,true);
        }
        selectedSlot=state==null?prefs().getInt("selectedSlot",0):state.getInt("selectedSlot",0);
        if(selectedSlot<0||selectedSlot>3)selectedSlot=0;
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(0xFFF4F6FA);
        if(android.os.Build.VERSION.SDK_INT>=29)scroll.setForceDarkAllowed(false);LinearLayout p=new LinearLayout(this);p.setOrientation(LinearLayout.VERTICAL);
        int pad=Math.round(20*getResources().getDisplayMetrics().density);p.setPadding(pad,pad,pad,pad);scroll.addView(p);setContentView(scroll);
        text(p,"Atelier 3D hors connexion",26);
        projectName=new EditText(this);projectName.setSingleLine(true);projectName.setHint("Nom du projet (facultatif)");projectName.setText(isMcp()?"ChatGPT "+mcpCommandId.substring(0,8):prefs().getString("projectName",""));p.addView(projectName);
        modeHelp=text(p,"Quatre vues du même objet entier, dans la même pose : face, dos, profil droit et profil gauche. Une forme 3D apprise est calculée pour chaque photo, puis les quatre formes sont alignées et combinées avec les silhouettes et textures réelles. Tout se calcule sur ce téléphone, sans serveur.",16);
        text(p,"Moteur de reconstruction",18);engine=spinner(p,"TripoSR · 4 images","Silhouettes · rapide","TripoSR · 1 seule image");
        int requestedEngine=getIntent().getBooleanExtra(EXTRA_SINGLE_IMAGE,false)?2:Math.max(0,Math.min(2,prefs().getInt("engine",0)));
        if(isMcp())requestedEngine=mcpMode.startsWith("silhouettes")?1:(mcpMode.endsWith("_single")?2:0);
        engine.setSelection(requestedEngine);
        fourViews=new CheckBox(this);fourViews.setText("Reconstruction avec les 4 vues (recommandé)");
        fourViews.setChecked(isMcp()?mcpMode.endsWith("_four"):(engine.getSelectedItemPosition()==0||(engine.getSelectedItemPosition()==1&&prefs().getBoolean("fourViews",true))));p.addView(fourViews);
        countLabel=text(p,"",16);
        for(int row=0;row<2;row++) {
            LinearLayout line=new LinearLayout(this);line.setOrientation(LinearLayout.HORIZONTAL);p.addView(line);
            for(int col=0;col<2;col++) {
                final int slot=row*2+col;LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(4,4,4,4);line.addView(card,new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1));cards[slot]=card;
                text(card,VIEWS[slot],18);
                choices[slot]=button(card,"Choisir · "+VIEWS[slot],()->{selectedSlot=slot;prefs().edit().putInt("selectedSlot",slot).apply();picker.launch(new String[]{"image/*"});});
                ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setBackgroundColor(0xFFE2E6EC);
                image.setContentDescription("Photo · "+VIEWS[slot]);card.addView(image,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,pad*7));previews[slot]=image;
                rotations[slot]=button(card,"Tourner 90°",()->rotateImage(slot));
                mirrors[slot]=button(card,"Miroir horizontal",()->mirrorImage(slot));
                if(source(slot).isFile())image.setImageURI(Uri.fromFile(source(slot)));
            }
        }
        choose=choices[0];rotate=rotations[0];preview=previews[0];
        text(p,"Détail du modèle",18);quality=spinner(p,"Rapide · économie de mémoire","Équilibré","Précis · plus lent");
        int requestedQuality="fast".equals(mcpQuality)?0:"precise".equals(mcpQuality)?2:1;
        quality.setSelection(isMcp()?requestedQuality:prefs().getInt("quality",1));
        shape=spinner(p,"Volume arrondi","Relief fin","Objet rond à 360° · vase / bouteille");shape.setSelection(prefs().getInt("shape",0));
        depthLabel=text(p,"",18);depth=new SeekBar(this);depth.setMax(100);depth.setProgress(prefs().getInt("depth",40));p.addView(depth);
        depth.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int value,boolean user){updateDepthLabel();}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}});
        depthLabel.setText("Réglage de l’épaisseur : "+depth.getProgress()+" / 100");
        text(p,"Tolérance du fond uni",18);tolerance=new SeekBar(this);tolerance.setMax(100);tolerance.setProgress(prefs().getInt("tolerance",35));p.addView(tolerance);
        ai=new CheckBox(this);ai.setText("Détourage IA local IS-Net · plus lent");ai.setChecked(isMcp()||prefs().getBoolean("ai",false));p.addView(ai);
        depthAi=new CheckBox(this);depthAi.setText("Profondeur IA locale · Depth Anything V2 Small");depthAi.setChecked(isMcp()||prefs().getBoolean("depthAi",true));p.addView(depthAi);
        text(p,"TripoSR, IS-Net et Depth Anything sont embarqués dans l’APK. TripoSR calcule une image ou les quatre vues successivement sur CPU ; le premier calcul peut prendre plusieurs minutes. Garde l’application ouverte. Les formes apprises sont conservées pour les réglages suivants.",14);
        text(p,"Option une image uniquement — Objet rond à 360° : photographie un objet vertical et symétrique. Sa forme tourne autour de son axe ; cette méthode convient aux vases et bouteilles, pas aux personnages. Choisis le moteur Silhouettes pour utiliser cette option.",14);
        text(p,"Le mode IA recale légèrement les vues opposées et utilise leurs contours pour préserver les parties fines. Les textures sont projetées sur les surfaces visibles et raccordées. TripoSR estime les détails ; quatre photos ne garantissent pas une copie exacte. Vérifie une même pose et des profils bien orientés. L’aperçu Géométrie permet de contrôler le volume sans les photos.",14);
        smoothing=new CheckBox(this);smoothing.setText("Lissage léger du maillage IA");smoothing.setChecked(isMcp()?mcpSmoothing:prefs().getBoolean("smoothing",true));p.addView(smoothing);
        text(p,"Le lissage réduit les bosses de la grille sans assembler des morceaux séparés. Il réutilise les formes IA en cache. Désactive-le pour garder toute la rugosité du champ appris.",14);
        text(p,"Finition TripoSR",20);
        textureQuality=spinner(p,"Texture légère · 512 pixels par face","Texture HD · 768 pixels par face","Texture maximale · 1 024 pixels par face");textureQuality.setSelection(Math.max(0,Math.min(2,prefs().getInt("textureQuality",2))));
        fineDetail=new CheckBox(this);fineDetail.setText("Préserver le détail fin · TripoSR 1 image");fineDetail.setChecked(prefs().getBoolean("fineDetail",true));p.addView(fineDetail);
        maximumPower=new CheckBox(this);maximumPower.setText("Puissance maximale · calcul plus long");maximumPower.setChecked(prefs().getBoolean("maximumPower",true));p.addView(maximumPower);
        tripoInputBoost=new CheckBox(this);tripoInputBoost.setText("Améliorer les détails de l’image avant TripoSR · local / sans crédits");tripoInputBoost.setChecked(prefs().getBoolean("tripoInputBoost",true));p.addView(tripoInputBoost);
        text(p,"Le renforcement local préserve la transparence et accentue légèrement les textures avant le réseau TripoSR. L’image originale reste utilisée pour le GLB. Option désactivable si le résultat est moins fidèle. Aucun téléchargement ni calcul distant.",14);
        text(p,"Le mode maximal utilise une grille plus fine et davantage de triangles sur un téléphone puissant. Il revient au budget normal si la mémoire disponible est insuffisante. Sélectionne Précis pour en profiter.",14);
        maximumPower.setOnCheckedChangeListener((button,checked)->prefs().edit().putBoolean("maximumPower",checked).apply());
        text(p,"Le détail fin augmente le budget de triangles si Android dispose d’assez de mémoire. La texture est adaptée à la mémoire de l’application. Ces réglages réutilisent les calculs IA en cache.",14);
        photoFidelityLabel=text(p,"Fidélité de la photo visible : "+prefs().getInt("photoFidelity",100)+" %",16);
        photoFidelity=new SeekBar(this);photoFidelity.setMax(100);photoFidelity.setProgress(Math.max(0,Math.min(100,prefs().getInt("photoFidelity",100))));p.addView(photoFidelity);
        photoFidelity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar bar,int value,boolean user){photoFidelityLabel.setText("Fidélité de la photo visible : "+value+" % · une image");}public void onStartTrackingTouch(SeekBar bar){}public void onStopTrackingTouch(SeekBar bar){}});
        selectDetail=button(p,"Améliorer un visage / détail",this::selectDetail);
        detectFaceDetail=button(p,"Détecter mon visage automatiquement · hors ligne",this::detectFaceDetail);
        assistDetail=new CheckBox(this);assistDetail.setText("Assistant gros plan · TripoSR + Depth Anything");assistDetail.setChecked(prefs().getBoolean("assistDetail",false));p.addView(assistDetail);
        try{String saved=prefs().getString("detailRegion","");if(!saved.isEmpty())detailRegion=TripoDetailRegion.fromJson(new org.json.JSONArray(saved));}catch(Exception ignored){assistDetail.setChecked(false);}
        detailLabel=text(p,"Relief du détail : "+prefs().getInt("detailStrength",70)+" %",16);
        detailStrength=new SeekBar(this);detailStrength.setMax(100);detailStrength.setProgress(prefs().getInt("detailStrength",70));p.addView(detailStrength);
        detailStrength.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar bar,int value,boolean user){detailLabel.setText("Relief du détail : "+value+" % · 0 % conserve uniquement la texture du gros plan");}public void onStartTrackingTouch(SeekBar bar){}public void onStopTrackingTouch(SeekBar bar){}});
        text(p,"Une image : encadre les yeux, le nez et la bouche, ou un autre détail. Le gros plan réserve sa propre texture et ajoute un relief local borné avec Depth Anything. Une zone de départ est proposée ; ajuste-la au sujet. Il ne détecte pas automatiquement les visages. Réimporte une photo HD pour garder jusqu’à 2 048 pixels selon la mémoire.",14);
        text(p,"Puissance disponible : "+TripoComputePolicy.summary(this),14);
        photoCheck=button(p,"Diagnostiquer mes images avant calcul",this::diagnosePhotos);
        compare=button(p,"Comparer les silhouettes des quatre vues",this::compareSilhouettes);
        inspect=button(p,"Vérifier le détourage avant de générer",this::inspectCutout);
        generate=button(p,"Générer sur ce téléphone",this::generate);
        cancel=button(p,"Arrêter après l’étape en cours",()->{cancelled=true;Thread thread=runningThread;if(thread!=null)thread.interrupt();status.setText("Arrêt demandé… Les modèles enregistrés sont conservés.");cancel.setEnabled(false);});
        progress=new ProgressBar(this);p.addView(progress);status=text(p,"Prêt hors connexion. Aucun compte ni serveur requis.",16);
        open=button(p,"Ouvrir mon modèle",()->openModel(lastId));export=button(p,"Exporter mon GLB",()->exporter.launch("volume-local-"+lastId.substring(0,8)+".glb"));
        gallery=button(p,"Mes modèles conservés hors connexion",this::gallery);
        button(p,"Libérer les calculs en cache",this::clearDerivedCaches);
        button(p,"Réglages · diagnostic et logs copiables",()->startActivity(new Intent(this,SettingsActivity.class)));
        if(prefs().getBoolean("interruptedWork",false))status.setText("Le calcul précédent a été interrompu. Tes photos et GLB sont conservés. Générer reprend les vues IA déjà calculées.");
        lastId=prefs().getString("last","");buttons();
        fourViews.setOnCheckedChangeListener((b,checked)->{if(!isMcp()&&engine.getSelectedItemPosition()==1)prefs().edit().putBoolean("fourViews",checked).apply();buttons();});
        engine.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onItemSelected(AdapterView<?> parent,View view,int position,long id){
                if(isMcp())fourViews.setChecked(mcpMode.endsWith("_four"));
                else fourViews.setChecked(position==0||(position==1&&prefs().getBoolean("fourViews",true)));
                if(position==2)selectedSlot=0;buttons();
            }
            public void onNothingSelected(AdapterView<?> parent){}
        });
        shape.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onItemSelected(AdapterView<?> parent,View view,int position,long id){
                buttons();
            }public void onNothingSelected(AdapterView<?> parent){}
        });
        if(isMcp()&&!prefs().getBoolean("mcpComplete_"+mcpCommandId,false)){
            mcpGenerating=true;
            status.setText("Commande ChatGPT reçue. Génération locale automatique…");
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::generate,300);
        }
    }
    private void updateDepthLabel(){
        depthLabel.setText(fourViews.isChecked()?"Profondeur des profils : "+(65+Math.round(depth.getProgress()*.7f))+" %":shape.getSelectedItemPosition()==2?"Épaisseur déduite de la silhouette pour l’objet rond à 360°":"Réglage de l’épaisseur : "+depth.getProgress()+" / 100");
    }
    private void buttons(){
        boolean managed=isMcp();
        boolean single=engine.getSelectedItemPosition()==2,learned=engine.getSelectedItemPosition()!=1,multiple=!single&&fourViews.isChecked(),saved=lastId.matches("[a-f0-9]{32}")&&model(lastId).isFile();int count=0;
        for(int slot=0;slot<4;slot++) {
            boolean exists=source(slot).isFile();if(exists)count++;
            choices[slot].setEnabled(!busy&&!managed);rotations[slot].setEnabled(!busy&&!managed&&exists);cards[slot].setVisibility(multiple||slot==0?View.VISIBLE:View.GONE);
            mirrors[slot].setEnabled(!busy&&!managed&&exists);
        }
        modeHelp.setText(single?"Une seule photo du sujet entier, de face ou de trois-quarts, avec un fond propre ou transparent. TripoSR estime le volume sur ce téléphone. La photo conserve les détails visibles ; les surfaces cachées utilisent les couleurs IA. Le dos et les zones cachées sont estimés, pas mesurés.":"Quatre vues du même objet entier et dans la même pose : face, dos et deux profils. TripoSR combine quatre formes IA ; Silhouettes propose un calcul plus léger.");
        countLabel.setText(single?(source().isFile()?"1 / 1 image prête. Les autres photos restent conservées.":"0 / 1 image. Choisis une photo dans Face."):multiple?count+" / 4 vues conservées sur ce téléphone. Ajoute les quatre pour générer.":"Une image : volume ou relief approximatif. La photo précédente est conservée dans Face.");
        generate.setEnabled(!busy&&(multiple?count==4:source().isFile()));inspect.setEnabled(!busy&&(multiple?count>0:source().isFile()));
        cancel.setVisibility(busy?View.VISIBLE:View.GONE);cancel.setEnabled(busy&&!cancelled);
        open.setEnabled(!busy&&saved);export.setEnabled(!busy&&saved);gallery.setEnabled(!busy);
        depth.setVisibility(single?View.GONE:View.VISIBLE);depthLabel.setVisibility(single?View.GONE:View.VISIBLE);
        depth.setEnabled(!busy&&!single&&(multiple||shape.getSelectedItemPosition()!=2));depthAi.setEnabled(!busy&&!learned&&(multiple||shape.getSelectedItemPosition()!=2));depthAi.setVisibility(learned?View.GONE:View.VISIBLE);
        selectDetail.setVisibility(single?View.VISIBLE:View.GONE);detectFaceDetail.setVisibility(single?View.VISIBLE:View.GONE);assistDetail.setVisibility(single?View.VISIBLE:View.GONE);detailLabel.setVisibility(single?View.VISIBLE:View.GONE);detailStrength.setVisibility(single?View.VISIBLE:View.GONE);
        selectDetail.setEnabled(!busy&&!managed&&single&&source().isFile());detectFaceDetail.setEnabled(!busy&&!managed&&single&&source().isFile());assistDetail.setEnabled(!busy&&!managed&&single&&detailRegion!=null);detailStrength.setEnabled(!busy&&!managed&&single&&detailRegion!=null);
        tripoInputBoost.setEnabled(!busy&&!managed&&learned);tripoInputBoost.setVisibility(learned?View.VISIBLE:View.GONE);textureQuality.setEnabled(!busy&&!managed&&learned);fineDetail.setEnabled(!busy&&!managed&&single);maximumPower.setEnabled(!busy&&!managed&&single);maximumPower.setVisibility(single?View.VISIBLE:View.GONE);
        photoFidelity.setEnabled(!busy&&!managed&&single);photoFidelityLabel.setVisibility(single?View.VISIBLE:View.GONE);photoFidelity.setVisibility(single?View.VISIBLE:View.GONE);fineDetail.setVisibility(single?View.VISIBLE:View.GONE);photoCheck.setEnabled(!busy&&!managed);
        tolerance.setEnabled(!busy&&!managed);quality.setEnabled(!busy&&!managed);shape.setEnabled(!busy&&!managed&&!multiple);shape.setVisibility(multiple||single?View.GONE:View.VISIBLE);
        ai.setEnabled(!busy&&!managed);engine.setEnabled(!busy&&!managed);fourViews.setEnabled(!busy&&!managed&&!learned);fourViews.setVisibility(single?View.GONE:View.VISIBLE);progress.setVisibility(busy?View.VISIBLE:View.GONE);updateDepthLabel();
        projectName.setEnabled(!busy&&!managed);compare.setEnabled(!busy&&!managed&&multiple);compare.setVisibility(single?View.GONE:View.VISIBLE);smoothing.setEnabled(!busy&&!managed&&learned);smoothing.setVisibility(learned?View.VISIBLE:View.GONE);
    }
    private void saveBitmap(Bitmap bitmap,File target)throws IOException{
        File part=new File(target.getPath()+".part");try{
            try(FileOutputStream out=new FileOutputStream(part)){if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("Image locale non enregistrée.");out.getFD().sync();}
            checkpoint();if(!part.renameTo(target))throw new IOException("Image locale non enregistrée.");
        }finally{part.delete();}
    }
    private void saveImage(Bitmap bitmap,int slot)throws IOException{
        saveBitmap(bitmap,source(slot));
        if(slot==0){new File(getFilesDir(),"offline-workshop-detail-depth.bin").delete();prefs().edit().remove("detailRegion").remove("detailCutoutKey").putBoolean("assistDetail",false).apply();ui(()->{detailRegion=null;assistDetail.setChecked(false);});}
        TripoSREngine.clearDetailedCache(learnedCache(slot));cutout(slot).delete();depthCache(slot).delete();TripoSREngine.clearDetailedCache(learnedCache(slot));learnedCache(slot).delete();new File(learnedCache(slot).getPath()+".key").delete();for(int side:new int[]{64,88,112}){new File(learnedCache(slot).getPath()+".field-"+side).delete();new File(learnedCache(slot).getPath()+".field-"+side+"-rgb").delete();}prefs().edit().remove(key("cutoutKey",slot)).remove(key("depthKey",slot)).apply();
        ui(()->{previews[slot].setImageURI(null);previews[slot].setImageURI(Uri.fromFile(source(slot)));});
    }
    private File prepareCutout(int tolerance,boolean useAi)throws Exception{return prepareCutout(0,tolerance,useAi);}
    private File prepareCutout(int slot,int tolerance,boolean useAi)throws Exception{
        String cache="v3-soft-alpha:"+(engine.getSelectedItemPosition()==2?TripoComputePolicy.imageLimit():1024)+":"+source(slot).lastModified()+":"+source(slot).length()+":"+tolerance+":"+useAi;
        if(cutout(slot).isFile()&&cache.equals(prefs().getString(key("cutoutKey",slot),"")))return cutout(slot);
        checkpoint();Bitmap bitmap=BitmapFactory.decodeFile(source(slot).getAbsolutePath());
        if(bitmap==null)throw new IOException("Photo "+VIEWS[slot]+" illisible.");
        if(engine.getSelectedItemPosition()!=2&&Math.max(bitmap.getWidth(),bitmap.getHeight())>1024){int max=Math.max(bitmap.getWidth(),bitmap.getHeight());Bitmap scaled=Bitmap.createScaledBitmap(bitmap,Math.max(3,bitmap.getWidth()*1024/max),Math.max(3,bitmap.getHeight()*1024/max),true);if(scaled!=bitmap){bitmap.recycle();bitmap=scaled;}}

        try{
            try(OfflineImageVolume.Prepared prepared=LocalImagePreparation.prepare(this,bitmap,tolerance,useAi,new TripoSREngine.Progress(){
                public void update(String value){message(VIEWS[slot]+" : "+value);}public void check(){checkpoint();}
            })){
                saveBitmap(prepared.bitmap,cutout(slot));
                prefs().edit().putString(key("cutoutKey",slot),cache).putString(key("cutoutMethod",slot),prepared.method).apply();
            }
        }finally{bitmap.recycle();}
        return cutout(slot);
    }
    private static boolean hasTransparency(Bitmap bitmap){
        return OfflineImageVolume.hasUsefulTransparency(bitmap);
    }
    private void compareSilhouettes(){
        if(busy)return;
        for(int slot=0;slot<4;slot++)if(!source(slot).isFile()){message("Ajoute la vue "+VIEWS[slot]+" pour comparer les silhouettes.");return;}
        int t=tolerance.getProgress()+8;boolean useAi=ai.isChecked();
        message("Comparaison locale des quatre silhouettes…");
        work(()->{
            Bitmap[] images=new Bitmap[4];
            try{
                for(int slot=0;slot<4;slot++){checkpoint();File ready=prepareCutout(slot,t,useAi);images[slot]=BitmapFactory.decodeFile(ready.getAbsolutePath());}
                FourViewCalibration calibration=TripoSRFourViewVolume.calibrate(images);checkpoint();
                int size=96;boolean[][] masks=new boolean[4][];for(int v=0;v<4;v++)masks[v]=calibration.alignedMask(v,size,size);
                int[] colours=new int[size*size*2];
                for(int pair=0;pair<2;pair++)for(int y=0;y<size;y++)for(int x=0;x<size;x++){
                    boolean a=masks[pair*2][y*size+x],b=masks[pair*2+1][y*size+size-1-x];
                    colours[y*size*2+pair*size+x]=a&&b?0xff2db780:a?0xfff46ca0:b?0xff64a7ff:0xff18202e;
                }
                Bitmap overlay=Bitmap.createBitmap(colours,size*2,size,Bitmap.Config.ARGB_8888);
                String agreement="Face / dos : "+Math.round(calibration.frontAgreement*100)+" % · profils : "+Math.round(calibration.profileAgreement*100)+" %";
                runOnUiThread(()->{
                    if(isDestroyed()||isFinishing()){overlay.recycle();return;}
                    LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);int pad=(int)(16*getResources().getDisplayMetrics().density);content.setPadding(pad,pad,pad,pad);
                    text(content,agreement,16);text(content,"À gauche : face/dos. À droite : profils. Vert : contours communs ; rose et bleu : écarts. Vérifie la même pose, l’objet entier et le sens des profils. Cet accord de silhouettes ne mesure pas la fidélité 3D.",14);
                    ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setImageBitmap(overlay);content.addView(image,new LinearLayout.LayoutParams(-1,pad*12));
                    var dialog=new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Comparaison avant calcul IA").setView(content).setPositiveButton("Fermer",null).create();dialog.setOnDismissListener(d->overlay.recycle());dialog.show();
                });
                message(agreement+(Math.min(calibration.frontAgreement,calibration.profileAgreement)<.65f?" · Écarts importants : vérifie les photos avant de lancer l’IA.":" · Comparaison disponible sans lancer TripoSR."));
            }finally{for(Bitmap image:images)if(image!=null)image.recycle();}
        });
    }
    /** Optional, on-device Android face detection; manual selection remains available. */
    private void detectFaceDetail(){
        if(busy||!source().isFile())return;
        int t=tolerance.getProgress()+8;boolean useAi=ai.isChecked();
        work(()->{
            File ready=prepareCutout(t,useAi);checkpoint();
            Bitmap image=BitmapFactory.decodeFile(ready.getPath());
            if(image==null)throw new IOException("Image préparée illisible.");
            TripoDetailRegion detected;
            try{detected=TripoFaceLocator.locate(image);}finally{image.recycle();}
            checkpoint();
            if(detected==null){
                message("Visage frontal non reconnu localement. Utilise Améliorer un visage / détail pour placer le cadre manuellement.");
                return;
            }
            String selectedKey=prefs().getString(key("cutoutKey",0),"");
            ui(()->{
                if(isFinishing())return;
                detailRegion=detected;
                prefs().edit().putString("detailRegion",detected.json().toString())
                        .putString("detailCutoutKey",selectedKey).putBoolean("assistDetail",true).apply();
                assistDetail.setChecked(true);quality.setSelection(2);textureQuality.setSelection(2);
                fineDetail.setChecked(true);photoFidelity.setProgress(100);buttons();
                status.setText("Visage trouvé sur le téléphone. Vérifie le cadre avec Améliorer un visage / détail, puis lance TripoSR.");
            });
        });
    }
    private void selectDetail(){
        if(busy||!source().isFile())return;
        int t=tolerance.getProgress()+8;boolean useAi=ai.isChecked();
        work(()->{
            File ready=prepareCutout(t,useAi);checkpoint();Bitmap image=BitmapFactory.decodeFile(ready.getPath());if(image==null)throw new IOException("Détourage illisible.");
            String selectedKey=prefs().getString(key("cutoutKey",0),"");
            ui(()->{
                if(isFinishing()){image.recycle();return;}
                LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);int pad=Math.round(16*getResources().getDisplayMetrics().density);content.setPadding(pad,0,pad,0);
                text(content,"Glisse le cadre ou ses coins autour du visage. Garde les yeux, le nez et la bouche dans le cadre. Tu peux aussi choisir une main ou un détail d’objet.",15);
                TripoDetailSelector selector=new TripoDetailSelector(this,image,selectedKey.equals(prefs().getString("detailCutoutKey",""))?detailRegion:null);
                int height=Math.min(Math.round(getResources().getDisplayMetrics().heightPixels*.52f),pad*28);content.addView(selector,new LinearLayout.LayoutParams(-1,height));
                var dialog=new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Gros plan à améliorer").setView(content).setPositiveButton("Appliquer le mode visage",null).setNegativeButton("Annuler",null).create();dialog.setOnDismissListener(d->image.recycle());dialog.show();
                dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener(v->{
                    try{detailRegion=selector.region();prefs().edit().putString("detailRegion",detailRegion.json().toString()).putString("detailCutoutKey",selectedKey).putBoolean("assistDetail",true).apply();
                        quality.setSelection(2);textureQuality.setSelection(2);fineDetail.setChecked(true);photoFidelity.setProgress(100);assistDetail.setChecked(true);buttons();
                        status.setText("Gros plan prêt · qualité Précis · texture dédiée · "+TripoComputePolicy.summary(this)+". Appuie sur Générer sur ce téléphone.");dialog.dismiss();
                    }catch(IllegalArgumentException e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}
                });
            });
        });
    }
    private void diagnosePhotos(){
        int limit=engine.getSelectedItemPosition()==2?1:(fourViews.isChecked()?4:1);
        work(()->{
            StringBuilder report=new StringBuilder("Diagnostic des images sources\n");
            for(int slot=0;slot<limit;slot++){
                checkpoint();report.append("\n").append(VIEWS[slot]).append(" : ");
                if(!source(slot).isFile()){report.append("image manquante\n");continue;}
                Bitmap bitmap=BitmapFactory.decodeFile(source(slot).getPath());
                if(bitmap==null){report.append("image illisible\n");continue;}
                try{report.append(InputPhotoDiagnostics.inspect(bitmap)).append("\n");}finally{bitmap.recycle();}
            }
            report.append("\nCes indications ne garantissent pas la fidélité 3D. Vérifie la même pose et le sujet entier sur chaque vue.");
            message(report.toString());
        });
    }
    private void inspectCutout(){
        int t=tolerance.getProgress()+8,limit=fourViews.isChecked()?4:1;boolean useAi=ai.isChecked();
        message("Vérification locale des détourages…");work(()->{
            for(int slot=0;slot<limit;slot++)if(source(slot).isFile()) {
                checkpoint();message(VIEWS[slot]+" : vérification du détourage…");File ready=prepareCutout(slot,t,useAi);checkpoint();final int index=slot;
                ui(()->{previews[index].setImageURI(null);previews[index].setImageURI(Uri.fromFile(ready));});
            }
            message("Détourages conservés sur le téléphone. Vérifie les contours et la même pose sur les quatre vues. Changer la profondeur ou le détail réutilise les résultats.");
        });
    }
    private OfflineDepthField prepareDepth(Bitmap bitmap)throws Exception{return prepareDepth(bitmap,0);}
    private OfflineDepthField prepareDepth(Bitmap bitmap,int slot)throws Exception{
        String cache="depth-v1:"+prefs().getString(key("cutoutKey",slot),"");
        if(depthCache(slot).isFile()&&cache.equals(prefs().getString(key("depthKey",slot),""))){
            try{return OfflineDepthField.read(depthCache(slot));}catch(IOException e){depthCache(slot).delete();}
        }
        message(VIEWS[slot]+" : profondeur IA embarquée : calcul CPU local sur deux threads…");checkpoint();
        float[] samples=new float[128*128];
        try(NeuralDepthEngine engine=new NeuralDepthEngine(this,2,false)){
            checkpoint();NeuralDepthEngine.DepthMap map=engine.estimate(bitmap);
            for(int y=0;y<128;y++)for(int x=0;x<128;x++)samples[y*128+x]=map.sample(x/127f,y/127f);
        }
        checkpoint();OfflineDepthField field=new OfflineDepthField(samples,128,128);File part=new File(depthCache(slot).getPath()+".part");
        try{field.write(part);checkpoint();if(!part.renameTo(depthCache(slot)))throw new IOException("Profondeur locale non enregistrée.");
            prefs().edit().putString(key("depthKey",slot),cache).apply();return field;
        }finally{part.delete();}
    }
    private void importImage(Uri uri){if(uri==null)return;final int slot=selectedSlot;message("Lecture de l’image HD et correction de l’orientation…");final int limit=engine.getSelectedItemPosition()==2?TripoComputePolicy.imageLimit():1024;work(()->{
        Bitmap bitmap=OfflineImageImporter.decode(getContentResolver(),uri,limit);try{saveImage(bitmap,slot);}finally{bitmap.recycle();}message("Orientation corrigée. Image copiée sur le téléphone ; prête hors connexion.");
    });}
    private void rotateImage(){rotateImage(0);}
    private void rotateImage(int slot){work(()->{Bitmap bitmap=BitmapFactory.decodeFile(source(slot).getAbsolutePath());if(bitmap==null)throw new IOException("Image illisible.");Bitmap turned=null;
        try{Matrix matrix=new Matrix();matrix.postRotate(90);turned=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),matrix,true);saveImage(turned,slot);}finally{if(turned!=null&&turned!=bitmap)turned.recycle();bitmap.recycle();}
    });}
    private void mirrorImage(int slot){work(()->{
        Bitmap bitmap=BitmapFactory.decodeFile(source(slot).getAbsolutePath());if(bitmap==null)throw new IOException("Image illisible.");Bitmap mirrored=null;
        try{Matrix m=new Matrix();m.setScale(-1,1);mirrored=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),m,true);saveImage(mirrored,slot);}
        finally{if(mirrored!=null&&mirrored!=bitmap)mirrored.recycle();bitmap.recycle();}
    });}
    private void clearDerivedCaches(){
        if(busy)return;
        new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Libérer les calculs en cache ?")
            .setMessage("Les quatre photos, les modèles GLB et les poids IA restent conservés. Le prochain calcul IA sera plus long.")
            .setPositiveButton("Libérer",(dialog,which)->work(()->{
                new File(getFilesDir(),"offline-workshop-detail-depth.bin").delete();new File(getFilesDir(),"offline-workshop-detail-depth.bin.key").delete();
                for(int slot=0;slot<4;slot++){
                    depthCache(slot).delete();TripoSREngine.clearDetailedCache(learnedCache(slot));learnedCache(slot).delete();new File(learnedCache(slot).getPath()+".key").delete();
                    for(int side:new int[]{64,88,112}){new File(learnedCache(slot).getPath()+".field-"+side).delete();new File(learnedCache(slot).getPath()+".field-"+side+"-rgb").delete();}
                }message("Calculs en cache libérés. Photos et GLB conservés.");
            })).setNegativeButton("Annuler",null).show();
    }
    private void generate(){
        int selected=quality.getSelectedItemPosition(),kind=shape.getSelectedItemPosition(),t=tolerance.getProgress()+8;boolean single=engine.getSelectedItemPosition()==2,learned=engine.getSelectedItemPosition()!=1,multiple=!single&&fourViews.isChecked(),useAi=ai.isChecked(),useDepth=!learned&&depthAi.isChecked()&&(multiple||kind!=2);float profileScale=.65f+depth.getProgress()*.007f;float thickness=.025f+depth.getProgress()*.0035f;
        boolean smooth=smoothing.isChecked();final boolean boostInput=learned&&(isMcp()||tripoInputBoost.isChecked());
        final TripoDetailRegion requestedRegion=single&&assistDetail.isChecked()?detailRegion:null;final float requestedStrength=detailStrength.getProgress()/100f;
        TripoQualityOptions finishOptions=new TripoQualityOptions(isMcp()?1024:new int[]{512,768,1024}[textureQuality.getSelectedItemPosition()],isMcp()?1:photoFidelity.getProgress()/100f,isMcp()||fineDetail.isChecked(),single&&TripoComputePolicy.maximumPower(this,maximumPower.isChecked()));
        DiagnosticLog.record("INFO","TripoSR : préparation locale de l’image "+(boostInput?"renforcée (détails fins)":"originale"));
        DiagnosticLog.record("INFO","Reconstruction : "+(single?"TripoSR 1 image":learned?"TripoSR 4 images":"Silhouettes")+" · détail "+selected+" · texture demandée "+finishOptions.textureCell+" / effective "+finishOptions.effectiveTextureCell()+" · budget "+finishOptions.triangleBudget()+" · "+TripoComputePolicy.summary(this));
        String project=projectName.getText().toString().trim();if(project.length()>80)project=project.substring(0,80);final String savedProject=project;
        if(!isMcp()){
            prefs().edit().putBoolean("smoothing",smooth).putInt("textureQuality",textureQuality.getSelectedItemPosition()).putInt("photoFidelity",photoFidelity.getProgress()).putBoolean("fineDetail",fineDetail.isChecked()).putBoolean("tripoInputBoost",tripoInputBoost.isChecked()).putString("projectName",savedProject)
                    .putInt("engine",engine.getSelectedItemPosition()).putInt("quality",selected).putInt("shape",kind)
                    .putInt("depth",depth.getProgress()).putInt("tolerance",tolerance.getProgress())
                    .putBoolean("ai",useAi).putBoolean("depthAi",depthAi.isChecked()).apply();
        }
        if(learned&&!single&&!multiple){status.setText("TripoSR utilise les quatre vues. Ajoute face, dos et les deux profils.");return;}
        if(multiple)for(int slot=0;slot<4;slot++)if(!source(slot).isFile()){status.setText("Ajoute la vue "+VIEWS[slot]+" avant de générer.");return;}
        if(isMcp())mcpGenerating=true;
        message(isMcp()?"Commande ChatGPT : création du modèle avec le moteur local…":"Création du volume local…");work(()->{
            long started=android.os.SystemClock.elapsedRealtime();
            Bitmap[] bitmaps=new Bitmap[multiple?4:1];Bitmap[] inferenceBitmaps=new Bitmap[bitmaps.length];OfflineImageVolume.Result result=null;
            TripoDetailRegion appliedRegion=null;
            String id=UUID.randomUUID().toString().replace("-","");File output=model(id),part=new File(output.getPath()+".part");
            try{
                OfflineDepthField[] fields=useDepth?new OfflineDepthField[bitmaps.length]:null;
                for(int slot=0;slot<bitmaps.length;slot++) {
                    checkpoint();message(VIEWS[slot]+" · "+(slot+1)+" / "+bitmaps.length);
                    File ready=prepareCutout(slot,t,useAi);checkpoint();bitmaps[slot]=BitmapFactory.decodeFile(ready.getAbsolutePath());
                    if(bitmaps[slot]==null)throw new IOException("Détourage "+VIEWS[slot]+" illisible.");
                    if(useDepth)fields[slot]=prepareDepth(bitmaps[slot],slot);checkpoint();
                }
                TripoDetailRegion boostRegion=single&&requestedRegion!=null
                        &&prefs().getString("detailCutoutKey","").equals(prefs().getString(key("cutoutKey",0),""))
                        ?requestedRegion:null;
                for(int slot=0;slot<bitmaps.length;slot++){
                    checkpoint();
                    if(boostInput){
                        message(VIEWS[slot]+" · renforcement local des contours avant TripoSR…");
                        inferenceBitmaps[slot]=TripoInputDetailBoost.enhance(bitmaps[slot],single?boostRegion:null,.8f);
                    }else inferenceBitmaps[slot]=bitmaps[slot];
                }
                String boostCacheTag=TripoInputDetailBoost.cacheTag(boostInput,boostRegion);
                if(single){
                    int neuralDetail=finishOptions.neuralResolution(selected);
                    TripoSRRefinedField learnedField=TripoSREngine.reconstructSingleDetailed(this,inferenceBitmaps[0],learnedCache(0),TripoSREngine.CACHE_VERSION+":"+prefs().getString(key("cutoutKey",0),"")+boostCacheTag,neuralDetail,new TripoSREngine.Progress(){
                        public void update(String value){message(value);}public void check(){checkpoint();}
                    });checkpoint();message("Maillage détaillé et texture photo sur les surfaces visibles…");
                    OfflineDepthField detailDepth=null;
                    if(requestedRegion!=null){
                        if(prefs().getString("detailCutoutKey","").equals(prefs().getString(key("cutoutKey",0),""))){
                            appliedRegion=requestedRegion;
                            if(requestedStrength>0)detailDepth=TripoDetailDepth.estimate(this,bitmaps[0],appliedRegion,new File(getFilesDir(),"offline-workshop-detail-depth.bin"),prefs().getString(key("cutoutKey",0),""),new TripoSREngine.Progress(){public void update(String value){message(value);}public void check(){checkpoint();}});
                        }else message("Le détourage a changé : sélectionne à nouveau le gros plan. Génération TripoSR standard pour cette image.");
                    }
                    checkpoint();message("Affinage du détail et texture dédiée…");
                    result=TripoSRSingleViewVolume.buildDetailed(learnedField,bitmaps[0],smooth,finishOptions,appliedRegion,detailDepth,requestedStrength);
                }else if(learned){
                    File[] caches=new File[4];String[] keys=new String[4];for(int slot=0;slot<4;slot++){caches[slot]=learnedCache(slot);keys[slot]=TripoSREngine.CACHE_VERSION+":"+prefs().getString(key("cutoutKey",slot),"")+boostCacheTag;}
                    int neuralDetail=new int[]{64,88,112}[selected];
                    TripoSRField[] learnedFields=TripoSREngine.reconstruct(this,inferenceBitmaps,caches,keys,neuralDetail,new TripoSREngine.Progress(){
                        public void update(String value){message(value);}public void check(){checkpoint();}
                    });checkpoint();message("Fusion multivue "+neuralDetail+"³ et construction du maillage texturé HD…");
                    result=TripoSRFourViewVolume.build(bitmaps,learnedFields,neuralDetail,profileScale,smooth,finishOptions);
                }else{
                message("Construction locale du maillage et des textures…");
                result=multiple?OfflineFourViewVolume.build(bitmaps,new int[]{64,88,112}[selected],profileScale,fields)
                    :OfflineImageVolume.buildPrepared(bitmaps[0],new int[]{80,112,144}[selected],thickness,kind,prefs().getString("cutoutMethod","Détourage local"),useDepth?fields[0]:null);
                }checkpoint();
                if(!output.getParentFile().isDirectory()&&!output.getParentFile().mkdirs())throw new IOException("Stockage du modèle indisponible.");
                org.json.JSONObject provenance=new org.json.JSONObject().put("appVersion",UpdateManager.currentVersion(this))
                    .put("reconstructionMode",single?"single-image":multiple?"four-view":"silhouette-single").put("hiddenSurfacesEstimated",single)
                    .put("engine",learned?"TripoSR":multiple?"Silhouettes":"Volume local").put("method",result.method)
                    .put("projectName",savedProject).put("localOnly",true).put("inputViews",bitmaps.length)
                    .put("textureMode",single?"visible-photo-neural-hidden":"existing").put("localTripoInputBoost",boostInput).put("tripoInputBoostRevision",boostInput?TripoInputDetailBoost.CACHE_REVISION:"disabled").put("detail",selected).put("smoothing",learned&&smooth).put("profileScale",multiple?profileScale:1f).put("generatedAt",System.currentTimeMillis());
                MeshQualityReport measurement=MeshQualityReport.inspect(result.mesh);
                String measured=measurement.summary()+" · texture "+result.texture.getWidth()+" × "+result.texture.getHeight()+"\n"+result.method;
                provenance.put("detailRegion",appliedRegion==null?org.json.JSONObject.NULL:appliedRegion.json()).put("detailStrength",requestedStrength).put("computeThreads",TripoComputePolicy.threads(this)).put("qualityReport",measurement.summary()).put("textureWidth",result.texture.getWidth()).put("textureHeight",result.texture.getHeight()).put("photoFidelity",finishOptions.photoWeight).put("fineDetail",finishOptions.preserveFineDetail).put("maximumPower",finishOptions.maximumPower).put("triangleBudget",finishOptions.triangleBudget());
                prefs().edit().putString("lastQualityReport",measured).apply();DiagnosticLog.record("OK",measured);
                ExternalViewerGlbExporter.write(part,result.mesh,result.texture,provenance);
                if(part.length()>64L*1024*1024)throw new IOException("Le modèle dépasse la limite mobile de 64 Mo.");
                checkpoint();if(!part.renameTo(output))throw new IOException("Modèle non enregistré.");
                // Sidecar is optional: a full disk must never invalidate an already saved GLB.
                try(Writer info=new OutputStreamWriter(new FileOutputStream(new File(output.getPath()+".json")),java.nio.charset.StandardCharsets.UTF_8)){info.write(provenance.toString());}catch(IOException ignored){}
                prefs().edit().putString("last",id).apply();int triangles=result.mesh.getTriangleCount();String method=result.method;long seconds=(android.os.SystemClock.elapsedRealtime()-started)/1000;
                if(isMcp()){
                    message("Modèle créé sur le téléphone. Synchronisation du GLB avec ChatGPT…");
                    McpBridgeSession.uploadResult(this,mcpCommandId,output);
                    prefs().edit().putBoolean("mcpComplete_"+mcpCommandId,true).apply();
                    mcpGenerating=false;
                }
                ui(()->{lastId=id;status.setText((isMcp()?"Commande ChatGPT terminée • ":"Modèle enregistré • ")+triangles+" triangles • "+String.format(Locale.FRANCE,"%.1f Mo",output.length()/1048576.0)+" • "+seconds+" s\n"+method+"\n"+(isMcp()?"GLB synchronisé avec le MCP et conservé sur le téléphone.":"Ouvre-le ou exporte-le sans connexion."));});
            }finally{part.delete();for(int i=0;i<bitmaps.length;i++){if(inferenceBitmaps[i]!=null&&inferenceBitmaps[i]!=bitmaps[i])inferenceBitmaps[i].recycle();if(bitmaps[i]!=null)bitmaps[i].recycle();}if(result!=null)result.texture.recycle();}
        });
    }
    private void openModel(String id){if(id.matches("[a-f0-9]{32}")&&model(id).isFile())startActivity(new Intent(this,CloudModelViewerActivity.class).putExtra("job",id));}
    private void gallery(){
        File folder=new File(getFilesDir(),"cloud_models");File[] files=folder.listFiles((d,n)->n.matches("[a-f0-9]{32}\\.glb"));
        if(files==null||files.length==0){status.setText("Aucun modèle conservé. Génère ton premier volume local.");return;}
        Arrays.sort(files,(a,b)->Long.compare(b.lastModified(),a.lastModified()));String[] labels=new String[files.length];
        for(int i=0;i<files.length;i++){
            String name=files[i].getName().substring(0,8);
            try(BufferedReader in=new BufferedReader(new InputStreamReader(new FileInputStream(files[i].getPath()+".json"),java.nio.charset.StandardCharsets.UTF_8))){String value=in.readLine();String custom=new org.json.JSONObject(value).optString("projectName");if(!custom.isEmpty())name=custom;}catch(Exception ignored){}
            labels[i]=name+" · "+new java.text.SimpleDateFormat("dd/MM HH:mm",Locale.FRANCE).format(new Date(files[i].lastModified()))+" · "+String.format(Locale.FRANCE,"%.1f Mo",files[i].length()/1048576.0);
        }
        new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Modèles disponibles hors connexion").setItems(labels,(dialog,index)->{
            lastId=files[index].getName().substring(0,32);prefs().edit().putString("last",lastId).apply();buttons();openModel(lastId);
        }).setNegativeButton("Fermer",null).show();
    }
    @Override protected void onPause(){
        if(!isMcp()){
            prefs().edit().putInt("engine",engine.getSelectedItemPosition()).putInt("quality",quality.getSelectedItemPosition()).putInt("shape",shape.getSelectedItemPosition())
                    .putInt("depth",depth.getProgress()).putInt("tolerance",tolerance.getProgress()).putBoolean("ai",ai.isChecked()).putBoolean("depthAi",depthAi.isChecked()).putBoolean("fourViews",engine.getSelectedItemPosition()==1?fourViews.isChecked():prefs().getBoolean("fourViews",true)).apply();
            prefs().edit().putBoolean("assistDetail",assistDetail.isChecked()).putInt("detailStrength",detailStrength.getProgress()).putInt("textureQuality",textureQuality.getSelectedItemPosition()).putInt("photoFidelity",photoFidelity.getProgress()).putBoolean("fineDetail",fineDetail.isChecked()).putString("projectName",projectName.getText().toString()).putBoolean("smoothing",smoothing.isChecked()).apply();
        }
        super.onPause();
    }
    @Override protected void onSaveInstanceState(Bundle state){state.putInt("selectedSlot",selectedSlot);super.onSaveInstanceState(state);}
    @Override protected void onDestroy(){
        cancelled=true;
        worker.shutdownNow();
        super.onDestroy();
    }
}
