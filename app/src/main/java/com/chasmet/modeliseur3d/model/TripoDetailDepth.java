package com.chasmet.modeliseur3d.model;

import android.content.Context;
import android.graphics.Bitmap;
import com.chasmet.modeliseur3d.diagnostics.DiagnosticLog;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** A second, local neural model sees only the selected detail, at its full input size. */
public final class TripoDetailDepth {
    private TripoDetailDepth(){ }
    public static OfflineDepthField estimate(Context context,Bitmap image,TripoDetailRegion region,
            File cache,String imageKey,TripoSREngine.Progress progress)throws Exception{
        String key="detail-depth-v1:"+imageKey+":"+region.cacheKey();File marker=new File(cache.getPath()+".key");
        progress.check();
        if(cache.isFile()&&marker.isFile()&&marker.length()<4096){
            String saved;
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(new FileInputStream(marker),StandardCharsets.UTF_8))){saved=reader.readLine();}
            if(key.equals(saved))try{OfflineDepthField field=OfflineDepthField.read(cache);progress.update("Gros plan : profondeur reprise du cache local.");return field;}catch(IOException ignored){cache.delete();}
        }
        int x=Math.round(region.left*(image.getWidth()-1)),y=Math.round(region.top*(image.getHeight()-1));
        int w=Math.max(2,Math.round(region.right*(image.getWidth()-1))-x+1),h=Math.max(2,Math.round(region.bottom*(image.getHeight()-1))-y+1);
        Bitmap crop=Bitmap.createBitmap(image,x,y,Math.min(w,image.getWidth()-x),Math.min(h,image.getHeight()-y));
        float[] samples=new float[256*256];
        try{
            progress.update("Gros plan "+crop.getWidth()+" × "+crop.getHeight()+" · Depth Anything V2 local · "+TripoComputePolicy.threads(context)+" threads CPU…");
            // CPU is reproducible for this small crop and avoids NNAPI partition overhead.
            try(NeuralDepthEngine engine=new NeuralDepthEngine(context,TripoComputePolicy.threads(context),false)){
                progress.check();NeuralDepthEngine.DepthMap map=engine.estimate(crop);progress.check();
                for(int yy=0;yy<256;yy++)for(int xx=0;xx<256;xx++)samples[yy*256+xx]=map.sample(xx/255f,yy/255f);
                DiagnosticLog.record("OK","Assistant détail : "+crop.getWidth()+" × "+crop.getHeight()+" px sources · profondeur 256 × 256 · "+engine.getBackend());
            }
        }finally{if(crop!=image)crop.recycle();}
        OfflineDepthField field=new OfflineDepthField(samples,256,256);File part=new File(cache.getPath()+".part");
        try{field.write(part);progress.check();if(!part.renameTo(cache))throw new IOException("Profondeur du détail non enregistrée.");
            try(Writer out=new OutputStreamWriter(new FileOutputStream(marker),StandardCharsets.UTF_8)){out.write(key);}
        }finally{part.delete();}
        return field;
    }
}
