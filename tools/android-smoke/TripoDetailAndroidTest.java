package com.chasmet.modeliseur3d;

import android.content.Intent;
import android.graphics.*;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import com.chasmet.modeliseur3d.model.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class TripoDetailAndroidTest {
    private static View text(View view,String value){
        if(view instanceof TextView&&((TextView)view).getText().toString().contains(value))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=text(((ViewGroup)view).getChildAt(i),value);if(found!=null)return found;}return null;
    }
    private static TripoSRRefinedField field(){
        int size=24;float[] density=new float[size*size*size];int[] rgb=new int[density.length];
        for(int x=0;x<size;x++)for(int y=0;y<size;y++)for(int z=0;z<size;z++){
            float xx=2f*x/(size-1)-1,yy=2f*y/(size-1)-1,zz=2f*z/(size-1)-1;int i=(x*size+y)*size+z;
            density[i]=TripoSRField.ISO+20*(.75f-(float)Math.sqrt(xx*xx+yy*yy+zz*zz));rgb[i]=0xff2040e0;
        }
        return new TripoSRRefinedField(size,size,size,new float[]{-1,-1,-1,1,1,1},density,rgb);
    }
    private static MeshData sphere(){
        int size=24;float[] values=new float[size*size*size];
        for(int y=1;y<size-1;y++)for(int x=1;x<size-1;x++)for(int z=1;z<size-1;z++){
            float xx=2f*x/(size-1)-1,yy=2f*y/(size-1)-1,zz=2f*z/(size-1)-1;
            values[(y*size+x)*size+z]=Math.max(0,Math.min(1,.5f+4*(.75f-(float)Math.sqrt(xx*xx+yy*yy+zz*zz))));
        }
        return OfflineMeshFinisher.finish(OfflineHullMesher.buildField(values,size,size,size),false,0).mesh;
    }
    private static Bitmap photo(){
        Bitmap photo=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(photo);Paint p=new Paint();p.setColor(Color.RED);c.drawOval(new RectF(8,8,248,248),p);return photo;
    }
    @Test public void localSubdivisionAndDepthKeepClosedTopologyAndNeverMoveTheBack(){
        MeshData original=sphere();Bitmap image=photo();TripoDetailRegion region=new TripoDetailRegion(.25f,.2f,.75f,.6f);
        float[] samples=new float[64*64];for(int y=0;y<64;y++)for(int x=0;x<64;x++){float dx=x/63f-.5f,dy=y/63f-.5f;samples[y*64+x]=(float)Math.exp(-(dx*dx+dy*dy)*90);}
        try{
            TripoDetailRefiner.Result detail=TripoDetailRefiner.refine(original,image,region,new OfflineDepthField(samples,64,64),.7f,60000);
            assertTrue(detail.addedTriangles>0);assertTrue(detail.movedVertices>0);assertTrue(detail.maximumMove>0);assertTrue(detail.maximumMove<=1.5f*.4f*.04f*.7f+.001f);
            assertEquals(0,OfflineMeshFinisher.finish(detail.mesh,false,0).boundaryEdges);assertEquals(0,MeshQualityReport.inspect(detail.mesh).degenerateTriangles);
            float[] before=original.getPositions(),after=detail.mesh.getPositions();int back=0;
            for(int i=0;i<before.length;i+=3){assertEquals(before[i],after[i],0);assertEquals(before[i+1],after[i+1],0);if(before[i+2]<0){assertEquals(before[i+2],after[i+2],0);back++;}}
            assertTrue(back>100);
            TripoDetailRefiner.Result zero=TripoDetailRefiner.refine(original,image,region,new OfflineDepthField(samples,64,64),0,60000);assertSame(original,zero.mesh);assertEquals(0,zero.movedVertices);
        }finally{image.recycle();}
    }
    @Test public void closeupUsesItsOwnPhotoChartAndTheHiddenBackRemainsNeural(){
        Bitmap image=photo();TripoDetailRegion region=new TripoDetailRegion(.25f,.2f,.75f,.6f);OfflineImageVolume.Result result=null;
        try{
            result=TripoSRPhotoTexture.bake(sphere(),field(),image,"fixture",new TripoQualityOptions(512,1,true),region);
            assertEquals(2048,result.texture.getWidth());assertEquals(1024,result.texture.getHeight());assertTrue(result.method.contains("texture dédiée 512 × 512"));
            assertTrue(Color.red(result.texture.getPixel(1280,768))>180);
            float[] p=result.mesh.getPositions(),uv=result.mesh.getTexCoords();int closeup=0,back=0;
            for(int i=0;i<p.length;i+=3){int at=i/3,x=Math.min(2047,(int)(uv[at*2]*2048)),y=Math.min(1023,(int)(uv[at*2+1]*1024));
                if(x>=1024&&x<1536&&y>=512)closeup++;
                if(p[i+2]<-.55f&&Math.abs(p[i])<.12f&&Math.abs(p[i+1])<.12f){assertTrue(Color.blue(result.texture.getPixel(x,y))>180);back++;}
            }
            assertTrue(closeup>100);assertTrue(back>0);
        }finally{image.recycle();if(result!=null)result.texture.recycle();}
    }
    @Test public void selectedDetailUsesStableScaleDespiteDifferentSilhouetteWidths(){
        Bitmap image=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(image);Paint p=new Paint();p.setColor(Color.RED);
        for(int y=8;y<248;y++){int width=y%2==0?110:50;c.drawRect(128-width,y,128+width,y+1,p);}
        try{
            TripoSRPhotoTexture.FrontProjection projection=TripoSRPhotoTexture.frontProjection(sphere(),image,new TripoDetailRegion(.2f,.2f,.8f,.7f));float[] a=new float[2],b=new float[2];
            assertTrue(projection.source(.1f,.1f,a));assertTrue(projection.source(.1f,.2f,b));assertEquals(a[0],b[0],1e-5f);
        }finally{image.recycle();}
    }
    @Test public void actualEmbeddedDepthSeesTheCropAndReusesOnlyTheMatchingCache()throws Exception{
        Bitmap image=photo();File cache=new File(RuntimeEnvironment.getApplication().getCacheDir(),"detail-depth-test.bin");List<String> messages=new ArrayList<>();
        TripoSREngine.Progress progress=new TripoSREngine.Progress(){public void update(String value){messages.add(value);}public void check(){}};
        try{
            TripoDetailRegion region=new TripoDetailRegion(.25f,.2f,.75f,.6f);
            OfflineDepthField map=TripoDetailDepth.estimate(RuntimeEnvironment.getApplication(),image,region,cache,"fixture-v1",progress);assertEquals(256,map.width);assertTrue(Float.isFinite(map.sample(.5f,.5f)));assertTrue(messages.stream().anyMatch(s->s.contains("Depth Anything")));
            long modified=cache.lastModified();messages.clear();TripoDetailDepth.estimate(RuntimeEnvironment.getApplication(),image,region,cache,"fixture-v1",progress);assertEquals(modified,cache.lastModified());assertEquals(1,messages.size());assertTrue(messages.get(0).contains("cache local"));
            String saved=new String(java.nio.file.Files.readAllBytes(new File(cache.getPath()+".key").toPath()),java.nio.charset.StandardCharsets.UTF_8);assertTrue(saved.contains(region.cacheKey()));
        }finally{image.recycle();cache.delete();new File(cache.getPath()+".key").delete();}
    }
    @Test public void oneTapPresetOpensTouchSelectionAndActivatesPreciseQuality()throws Exception{
        var app=RuntimeEnvironment.getApplication();Bitmap image=photo();File source=new File(app.getFilesDir(),"offline-workshop-image.png");
        try(OutputStream out=new FileOutputStream(source)){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));}image.recycle();
        try(var controller=Robolectric.buildActivity(Offline3DActivity.class,new Intent(app,Offline3DActivity.class).putExtra(Offline3DActivity.EXTRA_SINGLE_IMAGE,true)).setup()){
            Offline3DActivity activity=controller.get();shadowOf(Looper.getMainLooper()).idle();Button preset=(Button)text(activity.getWindow().getDecorView(),"Améliorer un visage / détail");assertNotNull(preset);assertTrue(preset.isEnabled());preset.performClick();
            ExecutorService worker=ReflectionHelpers.getField(activity,"worker");worker.submit(()->{}).get(60,TimeUnit.SECONDS);shadowOf(Looper.getMainLooper()).idle();
            android.app.AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
            // AppCompat dialogs are tracked by the same Android shadow when supported.
            if(dialog!=null){dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick();}
            else {
                var dialogs=org.robolectric.shadows.ShadowDialog.getShownDialogs();assertFalse(dialogs.isEmpty());
                ((androidx.appcompat.app.AlertDialog)dialogs.get(dialogs.size()-1)).getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick();
            }
            assertEquals(2,((Spinner)ReflectionHelpers.getField(activity,"quality")).getSelectedItemPosition());assertTrue(((CheckBox)ReflectionHelpers.getField(activity,"assistDetail")).isChecked());assertNotNull(ReflectionHelpers.getField(activity,"detailRegion"));assertTrue(app.getSharedPreferences("offline_workshop",0).getBoolean("assistDetail",false));
        }finally{source.delete();}
    }
    @Test public void powerfulPhoneGetsMoreCpuThreadsButMemoryPressureKeepsTheSafeBudget(){
        long gb=1024L*1024*1024,mb=1024L*1024;
        assertEquals(6,TripoComputePolicy.threads(8,12*gb,6*gb,512*mb,false));assertEquals(2,TripoComputePolicy.threads(8,12*gb,512*mb,512*mb,false));assertEquals(2,TripoComputePolicy.threads(8,12*gb,6*gb,128*mb,false));assertEquals(1,TripoComputePolicy.threads(1,12*gb,6*gb,512*mb,false));
        assertThrows(IllegalArgumentException.class,()->new TripoDetailRegion(.5f,0,.4f,1));assertThrows(IllegalArgumentException.class,()->new TripoDetailRegion(0,0,Float.NaN,1));
    }
}
