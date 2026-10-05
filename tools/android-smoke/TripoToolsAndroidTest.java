package com.chasmet.modeliseur3d;

import android.graphics.*;
import android.content.*;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import com.chasmet.modeliseur3d.model.*;
import com.chasmet.modeliseur3d.diagnostics.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class TripoToolsAndroidTest {
    private static View text(View view,String value){
        if(view instanceof TextView&&((TextView)view).getText().toString().contains(value))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=text(((ViewGroup)view).getChildAt(i),value);if(found!=null)return found;}
        return null;
    }
    @Test public void photoFidelityChangesVisibleColoursWithoutChangingGeometryOrPaintingTheHiddenBack(){
        int size=32;float[] density=new float[size*size*size];int[] rgb=new int[density.length];
        for(int x=0;x<size;x++)for(int y=0;y<size;y++)for(int z=0;z<size;z++){
            float xx=2f*x/(size-1)-1,yy=2f*y/(size-1)-1,zz=2f*z/(size-1)-1;int i=(x*size+y)*size+z;
            density[i]=TripoSRField.ISO+20*(.65f-(float)Math.sqrt(xx*xx+yy*yy+zz*zz));rgb[i]=0xff2040e0;
        }
        TripoSRRefinedField field=new TripoSRRefinedField(size,size,size,new float[]{-1,-1,-1,1,1,1},density,rgb);
        Bitmap photo=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888);photo.eraseColor(Color.RED);
        OfflineImageVolume.Result a=null,b=null;
        try{
            a=TripoSRSingleViewVolume.buildDetailed(field,photo,true,new TripoQualityOptions(512,1,false));
            b=TripoSRSingleViewVolume.buildDetailed(field,photo,true,new TripoQualityOptions(512,0,false));
            assertArrayEquals(a.mesh.getPositions(),b.mesh.getPositions(),0);assertEquals(1536,a.texture.getWidth());assertEquals(1024,a.texture.getHeight());
            float[] p=a.mesh.getPositions(),uv=a.mesh.getTexCoords();int front=0,back=0;
            for(int i=0;i<p.length;i+=3){int at=i/3;int x=Math.min(a.texture.getWidth()-1,(int)(uv[at*2]*a.texture.getWidth())),y=Math.min(a.texture.getHeight()-1,(int)(uv[at*2+1]*a.texture.getHeight()));
                if(Math.abs(p[i])<.10f&&Math.abs(p[i+1])<.10f){
                    if(p[i+2]>.4f){assertTrue(Color.red(a.texture.getPixel(x,y))>180);assertTrue(Color.blue(b.texture.getPixel(x,y))>180);front++;}
                    if(p[i+2]<-.4f){assertTrue(Color.blue(a.texture.getPixel(x,y))>180);back++;}
                }
            }
            assertTrue(front>0&&back>0);assertTrue(MeshQualityReport.inspect(a.mesh).triangles>100);
        }finally{photo.recycle();if(a!=null)a.texture.recycle();if(b!=null)b.texture.recycle();}
    }
    @Test public void photoDiagnosticReportsClippingAndLowResolutionWithoutChangingPixels(){
        Bitmap image=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(image);Paint paint=new Paint();paint.setColor(Color.RED);canvas.drawRect(0,10,70,90,paint);
        try{String report=InputPhotoDiagnostics.inspect(image);assertTrue(report.contains("touche le bord"));assertTrue(report.contains("source plus grande"));assertEquals(Color.RED,image.getPixel(10,10));}finally{image.recycle();}
    }
    @Test public void settingsCopiesTheWholeDiagnosticAndJournalWhileProjectsRemainIntact()throws Exception{
        var app=RuntimeEnvironment.getApplication();app.getSharedPreferences("offline_workshop",0).edit().putString("projectName","conserver").commit();
        DiagnosticLog.initialize(app);DiagnosticLog.record("TEST","Test de reconstruction token=private-value");
        try(var controller=Robolectric.buildActivity(SettingsActivity.class).setup()){
            SettingsActivity settings=controller.get();ExecutorService worker=ReflectionHelpers.getField(settings,"diagnostics");worker.submit(()->{}).get(30,TimeUnit.SECONDS);shadowOf(Looper.getMainLooper()).idle();
            View root=settings.getWindow().getDecorView();assertNotNull(text(root,"Diagnostic et logs copiables"));
            Button copy=(Button)text(root,"Copier tous les logs");assertTrue(copy.isEnabled());copy.performClick();
            String report=((ClipboardManager)settings.getSystemService(Context.CLIPBOARD_SERVICE)).getPrimaryClip().getItemAt(0).getText().toString();
            assertTrue(report.contains("DIAGNOSTIC MODÉLISEUR 3D"));assertTrue(report.contains("Test de reconstruction"));assertFalse(report.contains("private-value"));
            assertEquals("conserver",app.getSharedPreferences("offline_workshop",0).getString("projectName",""));
        }
    }
}
