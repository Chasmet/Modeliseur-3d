package com.chasmet.modeliseur3d;

import android.app.Application;
import android.content.*;
import android.content.pm.*;
import android.os.Looper;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.work.*;
import com.chasmet.modeliseur3d.update.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.*;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Integration checks: Android activities, real public Release download, installer intent. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=25)
@LooperMode(LooperMode.Mode.PAUSED)
public class NavigationUpdateTest {
    private Application app;
    private File project;
    private PackageInfo installed;
    @Before public void setup() throws Exception {
        app=RuntimeEnvironment.getApplication();
        UpdateManager.prefs(app).edit().putBoolean("automatic",false).commit();
        try { WorkManager.getInstance(app); }
        catch(IllegalStateException e) { WorkManager.initialize(app,new Configuration.Builder().build()); }
        installed=shadowOf(app.getPackageManager()).getInternalMutablePackageInfo(app.getPackageName());
        installed.versionCode=36;installed.versionName="6.0.0";
        installed.signatures=new Signature[]{new Signature(new byte[]{1,2,3})};
        app.getSharedPreferences("saved_projects",0).edit().putString("project","keep").commit();
        project=new File(app.getFilesDir(),"saved-project.glb");
        try(FileOutputStream out=new FileOutputStream(project)){out.write(new byte[]{7,8,9});}
    }
    private void dataIntact(){
        assertTrue(project.isFile());assertEquals(3,project.length());
        assertEquals("keep",app.getSharedPreferences("saved_projects",0).getString("project",""));
    }
    private static View text(View view,String value){
        if(view instanceof TextView && ((TextView)view).getText().toString().contains(value))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){
            View found=text(((ViewGroup)view).getChildAt(i),value);if(found!=null)return found;
        }
        return null;
    }
    private static void finishNetworkTasks() throws Exception {
        UpdateManager.executor.submit(()->{}).get(180,TimeUnit.SECONDS);
        shadowOf(Looper.getMainLooper()).idle();
    }
    @Test public void threeTabsKeepBothEnginesAndAssetsAccessible(){
        try(var controller=Robolectric.buildActivity(HomeActivity.class).setup()){
            HomeActivity home=controller.get();TabHost tabs=home.findViewById(android.R.id.tabhost);
            assertEquals(3,shadowOf(tabs).getAllTabSpecs().size());
            int[] ids={R.id.mode25dButton,R.id.mode3dButton,R.id.cloud3dButton,R.id.assets3dButton,R.id.settingsButton};
            Class<?>[] targets={MainActivityV52.class,Manual3DActivity.class,Cloud3DActivity.class,Asset3DActivity.class,SettingsActivity.class};
            for(int i=0;i<ids.length;i++){
                if(i<3)tabs.setCurrentTab(i);
                home.findViewById(ids[i]).performClick();
                assertEquals(targets[i].getName(),shadowOf(home).getNextStartedActivity().getComponent().getClassName());
            }
            dataIntact();
        }
    }
    @Test public void settingsDetectsDownloadsPublicReleaseAndOpensContentInstaller() throws Exception {
        try(var controller=Robolectric.buildActivity(SettingsActivity.class).setup()){
            SettingsActivity settings=controller.get();View root=settings.getWindow().getDecorView();
            assertNotNull(text(root,"Réglages"));assertNotNull(text(root,"Mise à jour automatique"));
            assertNotNull(text(root,"Version actuelle : 6.0.0"));
            finishNetworkTasks();
            ReleaseInfo available=ReflectionHelpers.getField(settings,"available");
            assertNotNull("A real public GitHub Release with an APK must exist",available);
            assertTrue(ReleaseInfo.newer(available.version,"6.0.0"));
            assertNotNull(text(root,"Nouvelle version disponible"));
            ((Button)text(root,"Vérifier les mises à jour")).performClick();finishNetworkTasks();
            List<String> messages=new ArrayList<>();TextView status=ReflectionHelpers.getField(settings,"status");
            status.addTextChangedListener(new TextWatcher(){
                public void beforeTextChanged(CharSequence s,int start,int count,int after){}
                public void onTextChanged(CharSequence s,int start,int before,int count){messages.add(s.toString());}
                public void afterTextChanged(Editable s){}
            });
            File partial=new File(new File(settings.getCacheDir(),"updates"),"modeliseur-update.part");
            PackageInfo archive=new PackageInfo();archive.packageName=app.getPackageName();
            archive.versionCode=37;archive.versionName=available.version;archive.signatures=installed.signatures;
            shadowOf(app.getPackageManager()).setPackageArchiveInfo(partial.getAbsolutePath(),archive);
            Button install=(Button)text(root,"Télécharger et installer");assertTrue(install.isEnabled());install.performClick();
            finishNetworkTasks();
            File ready=ReflectionHelpers.getField(settings,"ready");
            assertNotNull("Download must pass SHA256, size, package and signer checks",ready);
            assertEquals(available.bytes,ready.length());
            assertTrue(messages.stream().anyMatch(s->s.startsWith("Téléchargement : ")));
            assertTrue(messages.contains("Téléchargement : 100 %"));
            Intent intent=shadowOf(settings).getNextStartedActivity();assertNotNull(intent);
            assertEquals(Intent.ACTION_VIEW,intent.getAction());assertEquals("content",intent.getData().getScheme());
            assertEquals("application/vnd.android.package-archive",intent.getType());
            assertTrue((intent.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0);
            dataIntact();
        }
    }
    @Test public void rejectsOtherSignersPackagesAndDowngradesWithoutDeletingProjects() throws Exception {
        File file=new File(app.getCacheDir(),"candidate.apk");PackageInfo candidate=new PackageInfo();
        candidate.packageName=app.getPackageName();candidate.versionCode=37;candidate.signatures=installed.signatures;
        ShadowPackageManager pm=shadowOf(app.getPackageManager());pm.setPackageArchiveInfo(file.getAbsolutePath(),candidate);
        UpdateManager.validateArchive(app,file);
        candidate.signatures=new Signature[]{new Signature(new byte[]{4,5,6})};pm.setPackageArchiveInfo(file.getAbsolutePath(),candidate);
        assertThrows(IOException.class,()->UpdateManager.validateArchive(app,file));
        candidate.signatures=installed.signatures;candidate.versionCode=36;pm.setPackageArchiveInfo(file.getAbsolutePath(),candidate);
        assertThrows(IOException.class,()->UpdateManager.validateArchive(app,file));
        candidate.versionCode=37;candidate.packageName="another.application";pm.setPackageArchiveInfo(file.getAbsolutePath(),candidate);
        assertThrows(IOException.class,()->UpdateManager.validateArchive(app,file));dataIntact();
    }
}
