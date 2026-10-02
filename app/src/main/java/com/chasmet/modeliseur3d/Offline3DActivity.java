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
    private Spinner quality,shape;private CheckBox ai;private Button generate,open,export,choose,rotate,gallery;
    private ProgressBar progress;private boolean busy;private String lastId="";
    private android.content.SharedPreferences prefs(){return getSharedPreferences("offline_workshop",MODE_PRIVATE);}
    private File source(){return new File(getFilesDir(),"offline-workshop-image.png");}
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
        if(busy)return;busy=true;buttons();worker.execute(()->{
            try{action.run();}catch(OutOfMemoryError e){message("Mémoire de l'application insuffisante. Choisis Rapide et désactive le détourage IA.");}
            catch(Exception e){message(e.getMessage()==null?"Opération locale impossible.":e.getMessage());}
            finally{ui(()->{busy=false;buttons();});}
        });
    }
    private TextView text(LinearLayout p,String value,int size){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(0xFF121722);t.setPadding(0,12,0,8);p.addView(t);return t;}
    private Button button(LinearLayout p,String value,Runnable action){Button b=new Button(this);b.setText(value);b.setAllCaps(false);p.addView(b);b.setOnClickListener(v->action.run());return b;}
    private Spinner spinner(LinearLayout p,String... choices){Spinner s=new Spinner(this);s.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,choices));p.addView(s);return s;}
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);ScrollView scroll=new ScrollView(this);LinearLayout p=new LinearLayout(this);p.setOrientation(LinearLayout.VERTICAL);
        int pad=Math.round(20*getResources().getDisplayMetrics().density);p.setPadding(pad,pad,pad,pad);scroll.addView(p);setContentView(scroll);
        text(p,"Atelier 3D hors connexion",26);
        text(p,"Une image → volume approximatif texturé. Tout le calcul reste sur ce téléphone. Le dos est déduit ; pour tes quatre vues réelles, utilise le mode 3D original.",16);
        choose=button(p,"Choisir une image",()->picker.launch(new String[]{"image/*"}));
        preview=new ImageView(this);preview.setAdjustViewBounds(true);preview.setMaxHeight(pad*12);preview.setContentDescription("Image de départ du volume local");p.addView(preview);
        rotate=button(p,"Tourner l'image de 90°",this::rotateImage);
        text(p,"Détail du modèle",18);quality=spinner(p,"Rapide · grille 80","Équilibré · grille 112","Précis · grille 144");quality.setSelection(prefs().getInt("quality",1));
        shape=spinner(p,"Volume arrondi","Relief fin");shape.setSelection(prefs().getInt("shape",0));
        depthLabel=text(p,"",18);depth=new SeekBar(this);depth.setMax(100);depth.setProgress(prefs().getInt("depth",40));p.addView(depth);
        depth.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int value,boolean user){depthLabel.setText("Épaisseur : "+(value+10)+" %");}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}});
        depthLabel.setText("Épaisseur : "+(depth.getProgress()+10)+" %");
        text(p,"Tolérance du fond uni",18);tolerance=new SeekBar(this);tolerance.setMax(100);tolerance.setProgress(prefs().getInt("tolerance",35));p.addView(tolerance);
        ai=new CheckBox(this);ai.setText("Détourage IA local IS-Net · plus lent");ai.setChecked(prefs().getBoolean("ai",false));p.addView(ai);
        text(p,"PNG transparent recommandé. Sur un fond complexe, essaie le détourage IA. La grille est limitée selon la mémoire disponible pour l'application.",14);
        generate=button(p,"Générer sur ce téléphone",this::generate);
        progress=new ProgressBar(this);p.addView(progress);status=text(p,"Prêt hors connexion. Aucun compte ni serveur requis.",16);
        open=button(p,"Ouvrir mon modèle",()->openModel(lastId));export=button(p,"Exporter mon GLB",()->exporter.launch("volume-local-"+lastId.substring(0,8)+".glb"));
        gallery=button(p,"Mes modèles conservés hors connexion",this::gallery);
        lastId=prefs().getString("last","");if(source().isFile())preview.setImageURI(Uri.fromFile(source()));buttons();
    }
    private void buttons(){
        boolean saved=lastId.matches("[a-f0-9]{32}")&&model(lastId).isFile();
        choose.setEnabled(!busy);rotate.setEnabled(!busy&&source().isFile());generate.setEnabled(!busy&&source().isFile());
        open.setEnabled(!busy&&saved);export.setEnabled(!busy&&saved);gallery.setEnabled(!busy);
        depth.setEnabled(!busy);tolerance.setEnabled(!busy);quality.setEnabled(!busy);shape.setEnabled(!busy);ai.setEnabled(!busy);
        progress.setVisibility(busy?View.VISIBLE:View.GONE);
    }
    private void saveImage(Bitmap bitmap)throws IOException{
        File part=new File(source().getPath()+".part");try{
            try(FileOutputStream out=new FileOutputStream(part)){if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("Image locale non enregistrée.");out.getFD().sync();}
            if(!part.renameTo(source()))throw new IOException("Image locale non enregistrée.");
        }finally{part.delete();}
        ui(()->{preview.setImageURI(null);preview.setImageURI(Uri.fromFile(source()));});
    }
    private void importImage(Uri uri){if(uri==null)return;message("Lecture et réduction de l'image à 1 024 pixels…");work(()->{
        Bitmap bitmap=BitmapUtils.decodeBitmapFromUri(getContentResolver(),uri,1024);try{saveImage(bitmap);}finally{bitmap.recycle();}message("Image copiée sur le téléphone. Tu peux couper la connexion.");
    });}
    private void rotateImage(){work(()->{Bitmap bitmap=BitmapFactory.decodeFile(source().getAbsolutePath());if(bitmap==null)throw new IOException("Image illisible.");Bitmap turned=null;
        try{Matrix matrix=new Matrix();matrix.postRotate(90);turned=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),matrix,true);saveImage(turned);}finally{if(turned!=null&&turned!=bitmap)turned.recycle();bitmap.recycle();}
    });}
    private void generate(){
        int selected=quality.getSelectedItemPosition(),kind=shape.getSelectedItemPosition(),t=tolerance.getProgress()+8;boolean useAi=ai.isChecked();float thickness=.025f+depth.getProgress()*.0035f;
        prefs().edit().putInt("quality",selected).putInt("shape",kind).putInt("depth",depth.getProgress()).putInt("tolerance",tolerance.getProgress()).putBoolean("ai",useAi).apply();
        message("Création du volume local…");work(()->{
            Bitmap bitmap=BitmapFactory.decodeFile(source().getAbsolutePath());if(bitmap==null)throw new IOException("Image locale illisible.");OfflineImageVolume.Result result=null;
            String id=UUID.randomUUID().toString().replace("-","");File output=model(id),part=new File(output.getPath()+".part");
            try{
                AnimeSegmentationEngine.Mask mask=null;
                if(useAi){message("Détourage IA sur le processeur du téléphone…");try(AnimeSegmentationEngine segment=new AnimeSegmentationEngine(this,2)){mask=segment.segment(bitmap);}}
                message("Construction du maillage et de sa texture…");
                result=OfflineImageVolume.generate(bitmap,new int[]{80,112,144}[selected],t,thickness,kind==0,mask);
                if(!output.getParentFile().isDirectory()&&!output.getParentFile().mkdirs())throw new IOException("Stockage du modèle indisponible.");
                ExternalViewerGlbExporter.write(part,result.mesh,result.texture);
                if(part.length()>64L*1024*1024)throw new IOException("Le modèle dépasse la limite mobile de 64 Mo.");
                if(!part.renameTo(output))throw new IOException("Modèle non enregistré.");
                prefs().edit().putString("last",id).apply();int triangles=result.mesh.getTriangleCount();String method=result.method;
                ui(()->{lastId=id;status.setText("Modèle enregistré • "+triangles+" triangles • "+String.format(Locale.FRANCE,"%.1f Mo",output.length()/1048576.0)+"\n"+method+"\nOuvre-le ou exporte-le sans connexion.");});
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
    @Override protected void onDestroy(){worker.shutdown();super.onDestroy();}
}
