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
import com.chasmet.modeliseur3d.util.BitmapUtils;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Local workspace: no HTTP client, no server registration, no model downloads. */
public final class Offline3DActivity extends AppCompatActivity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private TextView status,depthLabel;private ImageView preview;private SeekBar depth,tolerance;
    private Spinner quality,shape;private Button inspect,cancel;private CheckBox ai,depthAi;private Button generate,open,export,choose,rotate,gallery;
    private ProgressBar progress;private boolean busy;private volatile boolean cancelled;private String lastId="";
    private android.content.SharedPreferences prefs(){return getSharedPreferences("offline_workshop",MODE_PRIVATE);}
    private File source(){return new File(getFilesDir(),"offline-workshop-image.png");}
    private File cutout(){return new File(getFilesDir(),"offline-workshop-cutout.png");}
    private File depthCache(){return new File(getFilesDir(),"offline-workshop-depth.bin");}
    private File model(String id){return new File(getFilesDir(),"cloud_models/"+id+".glb");}
    private final ActivityResultLauncher<String[]> picker=registerForActivityResult(new ActivityResultContracts.OpenDocument(),this::importImage);
    private final ActivityResultLauncher<String> exporter=registerForActivityResult(new ActivityResultContracts.CreateDocument("model/gltf-binary"),uri->{
        if(uri==null||!lastId.matches("[a-f0-9]{32}"))return;File selected=model(lastId);
        work(()->{try(InputStream in=new FileInputStream(selected);OutputStream out=getContentResolver().openOutputStream(uri,"w")){
            if(out==null)throw new IOException("Dossier d'export indisponible.");byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);
        }message("GLB exporté hors connexion.");});
    });
    private void ui(Runnable action){runOnUiThread(()->{if(!isDestroyed())action.run();});}
    private void message(String value){ui(()->status.setText(value));}
    private interface Task{void run()throws Exception;}
    private void work(Task action){
        if(busy||isDestroyed())return;busy=true;cancelled=false;buttons();worker.execute(()->{
            try{action.run();}catch(CancellationException e){message("Opération arrêtée. Les modèles précédents sont conservés.");}catch(OutOfMemoryError e){message("Mémoire de l'application insuffisante. Choisis Rapide et désactive les options IA locales.");}
            catch(Exception e){message(e.getMessage()==null?"Opération locale impossible.":e.getMessage());}
            finally{ui(()->{busy=false;buttons();});}
        });
    }
    private void checkpoint(){if(cancelled||Thread.currentThread().isInterrupted())throw new CancellationException();}
    private TextView text(LinearLayout p,String value,int size){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(0xFF121722);t.setPadding(0,12,0,8);p.addView(t);return t;}
    private Button button(LinearLayout p,String value,Runnable action){Button b=new Button(this);b.setText(value);b.setAllCaps(false);p.addView(b);b.setOnClickListener(v->action.run());return b;}
    private Spinner spinner(LinearLayout p,String... choices){Spinner s=new Spinner(this);s.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,choices));p.addView(s);return s;}
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);ScrollView scroll=new ScrollView(this);LinearLayout p=new LinearLayout(this);p.setOrientation(LinearLayout.VERTICAL);
        int pad=Math.round(20*getResources().getDisplayMetrics().density);p.setPadding(pad,pad,pad,pad);scroll.addView(p);setContentView(scroll);
        text(p,"Atelier 3D hors connexion",26);
        text(p,"Tout fonctionne sur ce téléphone : détourage, volume, aperçu, sauvegarde et export. Aucun serveur ni compte. Une seule image permet une approximation ; les quatre vues réelles restent dans le deuxième onglet.",16);
        choose=button(p,"Choisir une image",()->picker.launch(new String[]{"image/*"}));
        preview=new ImageView(this);preview.setAdjustViewBounds(true);preview.setMaxHeight(pad*12);preview.setContentDescription("Image de départ du volume local");p.addView(preview);
        rotate=button(p,"Tourner l'image de 90°",this::rotateImage);
        text(p,"Détail du modèle",18);quality=spinner(p,"Rapide · grille 80","Équilibré · grille 112","Précis · grille 144");quality.setSelection(prefs().getInt("quality",1));
        shape=spinner(p,"Volume arrondi","Relief fin","Objet rond à 360° · vase / bouteille");shape.setSelection(prefs().getInt("shape",0));
        depthLabel=text(p,"",18);depth=new SeekBar(this);depth.setMax(100);depth.setProgress(prefs().getInt("depth",40));p.addView(depth);
        depth.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int value,boolean user){depthLabel.setText("Réglage de l’épaisseur : "+value+" / 100");}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}});
        depthLabel.setText("Réglage de l’épaisseur : "+depth.getProgress()+" / 100");
        text(p,"Tolérance du fond uni",18);tolerance=new SeekBar(this);tolerance.setMax(100);tolerance.setProgress(prefs().getInt("tolerance",35));p.addView(tolerance);
        ai=new CheckBox(this);ai.setText("Détourage IA local IS-Net · plus lent");ai.setChecked(prefs().getBoolean("ai",false));p.addView(ai);
        depthAi=new CheckBox(this);depthAi.setText("Profondeur IA locale · Depth Anything V2 Small");depthAi.setChecked(prefs().getBoolean("depthAi",true));p.addView(depthAi);
        text(p,"Les deux IA sont embarquées. Le premier calcul peut être lent ; le détourage et la profondeur sont conservés pour les réglages suivants. Le relief IA estime la face visible ; la face cachée reste approximative.",14);
        text(p,"Objet rond à 360° : photographie un objet vertical et symétrique. Sa forme tourne autour de son axe ; cette méthode convient aux vases et bouteilles, pas aux personnages. Le décor et la face cachée ne sont pas inventés par TRELLIS.",14);
        text(p,"PNG transparent recommandé. Sur un fond complexe, essaie le détourage IA. La grille est limitée selon la mémoire disponible pour l'application.",14);
        inspect=button(p,"Vérifier le détourage avant de générer",this::inspectCutout);
        generate=button(p,"Générer sur ce téléphone",this::generate);
        cancel=button(p,"Arrêter après l’étape en cours",()->{cancelled=true;status.setText("Arrêt demandé… Les modèles enregistrés sont conservés.");cancel.setEnabled(false);});
        progress=new ProgressBar(this);p.addView(progress);status=text(p,"Prêt hors connexion. Aucun compte ni serveur requis.",16);
        open=button(p,"Ouvrir mon modèle",()->openModel(lastId));export=button(p,"Exporter mon GLB",()->exporter.launch("volume-local-"+lastId.substring(0,8)+".glb"));
        gallery=button(p,"Mes modèles conservés hors connexion",this::gallery);
        lastId=prefs().getString("last","");if(source().isFile())preview.setImageURI(Uri.fromFile(source()));buttons();
        shape.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onItemSelected(AdapterView<?> parent,View view,int position,long id){
                depthLabel.setText(position==2?"Épaisseur déduite de la silhouette pour l’objet rond à 360°":"Réglage de l’épaisseur : "+depth.getProgress()+" / 100");buttons();
            }public void onNothingSelected(AdapterView<?> parent){}
        });
    }
    private void buttons(){
        boolean saved=lastId.matches("[a-f0-9]{32}")&&model(lastId).isFile();
        choose.setEnabled(!busy);rotate.setEnabled(!busy&&source().isFile());generate.setEnabled(!busy&&source().isFile());
        inspect.setEnabled(!busy&&source().isFile());cancel.setVisibility(busy?View.VISIBLE:View.GONE);cancel.setEnabled(busy&&!cancelled);
        open.setEnabled(!busy&&saved);export.setEnabled(!busy&&saved);gallery.setEnabled(!busy);
        depth.setEnabled(!busy&&shape.getSelectedItemPosition()!=2);depthAi.setEnabled(!busy&&shape.getSelectedItemPosition()!=2);tolerance.setEnabled(!busy);quality.setEnabled(!busy);shape.setEnabled(!busy);ai.setEnabled(!busy);
        progress.setVisibility(busy?View.VISIBLE:View.GONE);
    }
    private void saveBitmap(Bitmap bitmap,File target)throws IOException{
        File part=new File(target.getPath()+".part");try{
            try(FileOutputStream out=new FileOutputStream(part)){if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("Image locale non enregistrée.");out.getFD().sync();}
            checkpoint();if(!part.renameTo(target))throw new IOException("Image locale non enregistrée.");
        }finally{part.delete();}
    }
    private void saveImage(Bitmap bitmap)throws IOException{
        saveBitmap(bitmap,source());cutout().delete();depthCache().delete();prefs().edit().remove("cutoutKey").remove("depthKey").apply();
        ui(()->{preview.setImageURI(null);preview.setImageURI(Uri.fromFile(source()));});
    }
    private String cutoutKey(int tolerance,boolean useAi){return "v1:"+source().lastModified()+":"+source().length()+":"+tolerance+":"+useAi;}
    private File prepareCutout(int tolerance,boolean useAi)throws Exception{
        String key=cutoutKey(tolerance,useAi);
        if(cutout().isFile()&&key.equals(prefs().getString("cutoutKey","")))return cutout();
        checkpoint();Bitmap bitmap=BitmapFactory.decodeFile(source().getAbsolutePath());
        if(bitmap==null)throw new IOException("Image locale illisible.");
        try{
            AnimeSegmentationEngine.Mask mask=null;
            if(useAi){message("Détourage IA embarqué : calcul CPU local, sans téléchargement…");
                try(AnimeSegmentationEngine segment=new AnimeSegmentationEngine(this,2)){checkpoint();mask=segment.segment(bitmap);}}
            checkpoint();
            try(OfflineImageVolume.Prepared prepared=OfflineImageVolume.prepare(bitmap,tolerance,mask)){
                saveBitmap(prepared.bitmap,cutout());
                prefs().edit().putString("cutoutKey",key).putString("cutoutMethod",prepared.method).apply();
            }
        }finally{bitmap.recycle();}
        return cutout();
    }
    private void inspectCutout(){
        int t=tolerance.getProgress()+8;boolean useAi=ai.isChecked();
        message("Vérification locale du détourage…");work(()->{
            File ready=prepareCutout(t,useAi);checkpoint();
            ui(()->{preview.setImageURI(null);preview.setImageURI(Uri.fromFile(ready));status.setText("Détourage prêt et conservé sur le téléphone. Ajuste la tolérance si nécessaire. Changer l’épaisseur ou le détail réutilise ce résultat.");});
        });
    }
    private OfflineDepthField prepareDepth(Bitmap bitmap)throws Exception{
        String key="depth-v1:"+prefs().getString("cutoutKey","");
        if(depthCache().isFile()&&key.equals(prefs().getString("depthKey",""))){
            try{return OfflineDepthField.read(depthCache());}catch(IOException e){depthCache().delete();}
        }
        message("Profondeur IA embarquée : calcul CPU local sur deux threads…");checkpoint();
        float[] samples=new float[128*128];
        try(NeuralDepthEngine engine=new NeuralDepthEngine(this,2,false)){
            checkpoint();NeuralDepthEngine.DepthMap map=engine.estimate(bitmap);
            for(int y=0;y<128;y++)for(int x=0;x<128;x++)samples[y*128+x]=map.sample(x/127f,y/127f);
        }
        checkpoint();OfflineDepthField field=new OfflineDepthField(samples,128,128);File part=new File(depthCache().getPath()+".part");
        try{field.write(part);checkpoint();if(!part.renameTo(depthCache()))throw new IOException("Profondeur locale non enregistrée.");
            prefs().edit().putString("depthKey",key).apply();return field;
        }finally{part.delete();}
    }
    private void importImage(Uri uri){if(uri==null)return;message("Lecture et réduction de l'image à 1 024 pixels…");work(()->{
        Bitmap bitmap=BitmapUtils.decodeBitmapFromUri(getContentResolver(),uri,1024);try{saveImage(bitmap);}finally{bitmap.recycle();}message("Image copiée sur le téléphone. Tu peux couper la connexion.");
    });}
    private void rotateImage(){work(()->{Bitmap bitmap=BitmapFactory.decodeFile(source().getAbsolutePath());if(bitmap==null)throw new IOException("Image illisible.");Bitmap turned=null;
        try{Matrix matrix=new Matrix();matrix.postRotate(90);turned=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),matrix,true);saveImage(turned);}finally{if(turned!=null&&turned!=bitmap)turned.recycle();bitmap.recycle();}
    });}
    private void generate(){
        int selected=quality.getSelectedItemPosition(),kind=shape.getSelectedItemPosition(),t=tolerance.getProgress()+8;boolean useAi=ai.isChecked(),useDepth=depthAi.isChecked()&&kind!=2;float thickness=.025f+depth.getProgress()*.0035f;
        prefs().edit().putInt("quality",selected).putInt("shape",kind).putInt("depth",depth.getProgress()).putInt("tolerance",tolerance.getProgress()).putBoolean("ai",useAi).putBoolean("depthAi",depthAi.isChecked()).apply();
        message("Création du volume local…");work(()->{
            long started=android.os.SystemClock.elapsedRealtime();
            File ready=prepareCutout(t,useAi);checkpoint();
            Bitmap bitmap=BitmapFactory.decodeFile(ready.getAbsolutePath());if(bitmap==null)throw new IOException("Détourage local illisible.");OfflineImageVolume.Result result=null;
            String id=UUID.randomUUID().toString().replace("-","");File output=model(id),part=new File(output.getPath()+".part");
            try{
                OfflineDepthField field=useDepth?prepareDepth(bitmap):null;checkpoint();
                message("Construction locale du maillage et de sa texture…");
                result=OfflineImageVolume.buildPrepared(bitmap,new int[]{80,112,144}[selected],thickness,kind,prefs().getString("cutoutMethod","Détourage local"),field);checkpoint();
                if(!output.getParentFile().isDirectory()&&!output.getParentFile().mkdirs())throw new IOException("Stockage du modèle indisponible.");
                ExternalViewerGlbExporter.write(part,result.mesh,result.texture);
                if(part.length()>64L*1024*1024)throw new IOException("Le modèle dépasse la limite mobile de 64 Mo.");
                checkpoint();if(!part.renameTo(output))throw new IOException("Modèle non enregistré.");
                prefs().edit().putString("last",id).apply();int triangles=result.mesh.getTriangleCount();String method=result.method;long seconds=(android.os.SystemClock.elapsedRealtime()-started)/1000;
                ui(()->{lastId=id;status.setText("Modèle enregistré • "+triangles+" triangles • "+String.format(Locale.FRANCE,"%.1f Mo",output.length()/1048576.0)+" • "+seconds+" s\n"+method+"\nOuvre-le ou exporte-le sans connexion.");});
            }finally{part.delete();bitmap.recycle();if(result!=null)result.texture.recycle();}
        });
    }
    private void openModel(String id){if(id.matches("[a-f0-9]{32}")&&model(id).isFile())startActivity(new Intent(this,CloudModelViewerActivity.class).putExtra("job",id));}
    private void gallery(){
        File folder=new File(getFilesDir(),"cloud_models");File[] files=folder.listFiles((d,n)->n.matches("[a-f0-9]{32}\\.glb"));
        if(files==null||files.length==0){status.setText("Aucun modèle conservé. Génère ton premier volume local.");return;}
        Arrays.sort(files,(a,b)->Long.compare(b.lastModified(),a.lastModified()));String[] labels=new String[files.length];
        for(int i=0;i<files.length;i++)labels[i]=new java.text.SimpleDateFormat("dd/MM HH:mm",Locale.FRANCE).format(new Date(files[i].lastModified()))+" · "+String.format(Locale.FRANCE,"%.1f Mo",files[i].length()/1048576.0)+" · "+files[i].getName().substring(0,8);
        new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Modèles disponibles hors connexion").setItems(labels,(dialog,index)->{
            lastId=files[index].getName().substring(0,32);prefs().edit().putString("last",lastId).apply();buttons();openModel(lastId);
        }).setNegativeButton("Fermer",null).show();
    }
    @Override protected void onPause(){
        prefs().edit().putInt("quality",quality.getSelectedItemPosition()).putInt("shape",shape.getSelectedItemPosition())
                .putInt("depth",depth.getProgress()).putInt("tolerance",tolerance.getProgress()).putBoolean("ai",ai.isChecked()).putBoolean("depthAi",depthAi.isChecked()).apply();
        super.onPause();
    }
    @Override protected void onDestroy(){cancelled=true;worker.shutdownNow();super.onDestroy();}
}
