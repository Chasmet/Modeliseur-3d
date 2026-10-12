package com.chasmet.modeliseur3d;

import android.app.Application;
import android.content.*;
import android.content.pm.*;
import android.os.Looper;
import android.widget.Button;
import androidx.work.*;
import com.chasmet.modeliseur3d.update.UpdateManager;
import java.io.*;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Recovery of the installer after Android destroys Settings during permission setup. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
@LooperMode(LooperMode.Mode.PAUSED)
public class UpdateInstallationRecoveryTest {
    private Application app;private File apk;private PackageInfo archive;
    @Before public void setup()throws Exception{
        app=RuntimeEnvironment.getApplication();
        try{WorkManager.getInstance(app);}catch(IllegalStateException e){WorkManager.initialize(app,new Configuration.Builder().build());}
        PackageInfo installed=shadowOf(app.getPackageManager()).getInternalMutablePackageInfo(app.getPackageName());
        // Above latest public release avoids an irrelevant second download during this test.
        installed.versionName="99.0.0";installed.versionCode=1000;installed.signatures=new Signature[]{new Signature(new byte[]{1,2,3})};
        archive=new PackageInfo();archive.packageName=app.getPackageName();archive.versionName="99.0.1";archive.versionCode=1001;archive.signatures=installed.signatures;
        apk=new File(new File(app.getCacheDir(),"updates"),"modeliseur-update.apk");assertTrue(apk.getParentFile().isDirectory()||apk.getParentFile().mkdirs());
        byte[] bytes={80,75,3,4,1,2,3,4};try(OutputStream out=new FileOutputStream(apk)){out.write(bytes);}
        StringBuilder hash=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))hash.append(String.format("%02x",b&255));
        UpdateManager.prefs(app).edit().clear().putBoolean("automatic",false).putString("ready_version","99.0.1")
                .putString("ready_sha256",hash.toString()).putLong("ready_bytes",bytes.length).commit();
        shadowOf(app.getPackageManager()).setPackageArchiveInfo(apk.getAbsolutePath(),archive);
        app.getSharedPreferences("saved_projects",0).edit().putString("project","keep").commit();
    }
    private static void finishTasks()throws Exception{UpdateManager.executor.submit(()->{}).get(180,TimeUnit.SECONDS);shadowOf(Looper.getMainLooper()).idle();}
    @Test public void reopenedSettingsRecoversVerifiedApkAndResumesPermissionInstallation()throws Exception{
        try(var old=Robolectric.buildActivity(SettingsActivity.class).setup()){
            finishTasks();assertEquals(apk,ReflectionHelpers.getField(old.get(),"ready"));
            shadowOf(app.getPackageManager()).setCanRequestPackageInstalls(false);
            ((Button)ReflectionHelpers.getField(old.get(),"install")).performClick();
            Intent permission=shadowOf(old.get()).getNextStartedActivity();assertNotNull(permission);
            assertEquals(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,permission.getAction());
            assertTrue(UpdateManager.prefs(app).getBoolean("install_permission_pending",false));
        }
        shadowOf(app.getPackageManager()).setCanRequestPackageInstalls(true);
        try(var recreated=Robolectric.buildActivity(SettingsActivity.class).setup()){
            finishTasks();assertEquals(apk,ReflectionHelpers.getField(recreated.get(),"ready"));
            Intent installer=shadowOf(recreated.get()).getNextStartedActivity();assertNotNull(installer);
            assertEquals(Intent.ACTION_VIEW,installer.getAction());assertEquals("content",installer.getData().getScheme());
            assertTrue((installer.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0);
            assertFalse(UpdateManager.prefs(app).getBoolean("install_permission_pending",true));
        }
        assertEquals("keep",app.getSharedPreferences("saved_projects",0).getString("project",""));
    }
    @Test public void recoveryRejectsTamperedDownloadsAndNowInstalledVersions()throws Exception{
        assertEquals(apk,UpdateManager.readyDownload(app));
        try(RandomAccessFile file=new RandomAccessFile(apk,"rw")){file.write(0);}
        assertThrows(IOException.class,()->UpdateManager.readyDownload(app));
        setup();archive.versionCode=1000;shadowOf(app.getPackageManager()).setPackageArchiveInfo(apk.getAbsolutePath(),archive);
        assertThrows(IOException.class,()->UpdateManager.readyDownload(app));
    }
}
