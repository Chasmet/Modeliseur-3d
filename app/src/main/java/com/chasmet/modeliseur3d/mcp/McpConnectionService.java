package com.chasmet.modeliseur3d.mcp;

import android.app.*;
import android.content.*;
import android.os.*;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import com.chasmet.modeliseur3d.HomeActivity;
import com.chasmet.modeliseur3d.R;
import com.chasmet.modeliseur3d.cloud.CloudApi;
import com.chasmet.modeliseur3d.model.TripoSREngine;
import org.json.*;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** User-controlled foreground service. Polling never waits for the local inference worker. */
public class McpConnectionService extends Service {
    public static final String DISCONNECT = "com.chasmet.modeliseur3d.MCP_DISCONNECT";
    private static final String PREFS="mcp_background", CHANNEL="mcp_background";
    private static final int NOTIFICATION=803;
    private final ScheduledExecutorService network=Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService compute=Executors.newSingleThreadExecutor();
    private static final Semaphore ENGINE = new Semaphore(1);
    private final AtomicBoolean working=new AtomicBoolean();
    private volatile boolean active;
    private volatile CloudApi api;
    private volatile String progress="Connexion au relais…";
    private volatile String commandId="";
    private ScheduledFuture<?> polling;
    private PowerManager.WakeLock power;
    private long lastNotification;

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS,MODE_PRIVATE); }
    public static boolean enabled(Context c) { return prefs(c).getBoolean("enabled",false); }
    public static String status(Context c) {
        SharedPreferences p=prefs(c);
        if (!enabled(c)) return "ChatGPT MCP : déconnecté";
        long last=p.getLong("heartbeat",0);
        if (last==0 || System.currentTimeMillis()-last>25000) return "ChatGPT MCP : reconnexion…";
        return "ChatGPT MCP : connecté\n"+p.getString("progress","En attente de commande");
    }
    public static void setEnabled(Context c,boolean enabled) {
        prefs(c).edit().putBoolean("enabled",enabled).putLong("heartbeat",0).commit();
        if (enabled) start(c);
        else c.startService(new Intent(c,McpConnectionService.class).setAction(DISCONNECT));
    }
    public static void start(Context c) {
        ContextCompat.startForegroundService(c,new Intent(c,McpConnectionService.class));
    }
    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT>=26) {
            NotificationChannel channel=new NotificationChannel(CHANNEL,"Connexion ChatGPT et calcul 3D",NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Le service reste actif pendant YouTube, Netflix ou lorsque l’écran de l’application est fermé.");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
        PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
        power=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Modeliseur3D:McpJob");
        power.setReferenceCounted(false);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if ((intent!=null && DISCONNECT.equals(intent.getAction())) || !enabled(this)) {
            prefs(this).edit().putBoolean("enabled",false).putLong("heartbeat",0).commit();
            disconnect(); return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION,notification());
        if (!active) {
            active=true;
            polling=network.scheduleWithFixedDelay(this::pollOnce,0,4,TimeUnit.SECONDS);
        }
        return START_STICKY;
    }
    protected CloudApi connect() throws Exception { return McpBridgeSession.ensureApi(this); }
    protected File generate(JSONObject command,TripoSREngine.Progress callback) throws Exception {
        return LocalMcpGeneration.generate(this,command,callback);
    }
    private boolean connected() { return active && enabled(this); }
    void pollOnce() {
        if (!connected()) return;
        try {
            if (api==null) api=connect();
            JSONObject response=api.json("/api/poll",null);
            if (!connected()) return;
            prefs(this).edit().putLong("heartbeat",System.currentTimeMillis()).putString("progress",progress).apply();
            if (working.get()) {
                renewPower();
            } else {
                String saved=prefs(this).getString("command","");
                JSONArray commands=response.optJSONArray("local_commands");
                JSONObject next=!saved.isEmpty()?new JSONObject(saved):commands!=null&&commands.length()>0?commands.getJSONObject(0):null;
                if (next!=null && working.compareAndSet(false,true)) {
                    // Commit BEFORE marking running on the server; process death can resume this command.
                    prefs(this).edit().putString("command",next.toString()).commit();
                    renewPower(); compute.execute(()->executeCommand(next));
                }
            }
            showNotification();
        } catch (Exception error) {
            if (error instanceof CloudApi.HttpFailure && ((CloudApi.HttpFailure)error).code==401) api=null;
            progress=working.get()?progress:"Relais indisponible · reconnexion automatique";
            showNotification();
        }
    }
    private void check() {
        if (!connected() || Thread.currentThread().isInterrupted()) throw new CancellationException("Connexion arrêtée par l’utilisateur.");
    }
    private void executeCommand(JSONObject command) {
        CloudApi current=api;
        boolean ownsEngine=ENGINE.tryAcquire();
        if (!ownsEngine) { working.set(false); releasePower(); return; }
        try {
            check(); commandId=CloudApi.id(command.getString("id"));
            JSONArray refs=command.getJSONArray("references");
            String mode=command.getString("mode");
            if (!mode.matches("(triposr|silhouettes)_(single|four)") || refs.length()!=(mode.endsWith("_four")?4:1))
                throw new IOException("Commande locale incompatible.");
            String previousError=prefs(this).getString("error","");
            if (!previousError.isEmpty()) {
                current.updateLocalStatus(commandId,"error",previousError);
                clearCommand(); return;
            }
            current.updateLocalStatus(commandId,"running","Calcul Android local en arrière-plan…");
            File output=new File(getFilesDir(),"cloud_models/"+commandId+".glb");
            if (!output.isFile()) {
                File folder=new File(getFilesDir(),"mcp_inputs/"+commandId);
                for (int i=0;i<refs.length();i++) {
                    check(); progress="Réception de l’image "+(i+1)+" / "+refs.length();
                    File image=new File(folder,"image-"+i+".png");
                    if (!image.isFile()) current.downloadLocalImage(commandId,CloudApi.id(refs.getString(i)),image);
                }
                check();
                try {
                    output=generate(command,new TripoSREngine.Progress() {
                        public void update(String value) { progress=value; }
                        public void check() { McpConnectionService.this.check(); }
                    });
                } catch (CancellationException e) { throw e; }
                catch (Exception | OutOfMemoryError e) {
                    check();
                    String message=e instanceof OutOfMemoryError?"Mémoire Android insuffisante.":e.getMessage();
                    if (message==null) message="Échec du calcul local.";
                    prefs(this).edit().putString("error",message).commit();
                    current.updateLocalStatus(commandId,"error",message);
                    clearCommand(); progress="Échec du calcul · consulte ChatGPT"; return;
                }
            }
            check(); progress="GLB sauvegardé · synchronisation avec ChatGPT…";
            current.uploadLocalResult(commandId,output);
            clearCommand(); progress="Modèle terminé et sauvegardé · en attente de commande";
        } catch (CancellationException ignored) {
            // Keep command, cutouts, neural caches and finished GLB for an explicit reconnect.
        } catch (Exception error) {
            if (error instanceof CloudApi.HttpFailure && ((CloudApi.HttpFailure)error).code==404) {
                // The free relay may lose its queue on restart. Preserve local files, unblock new commands.
                clearCommand(); progress="Commande expirée sur le relais · fichiers locaux conservés";
            } else progress="Travail conservé · nouvel essai à la reconnexion";
        } finally {
            commandId=""; working.set(false); releasePower(); ENGINE.release();
        }
    }
    private void clearCommand() { prefs(this).edit().remove("command").remove("error").commit(); }
    private synchronized void renewPower() {
        if (connected() && working.get()) power.acquire(10*60*1000L);
    }
    private synchronized void releasePower() { if (power!=null && power.isHeld()) power.release(); }
    private Notification notification() {
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,HomeActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,McpConnectionService.class).setAction(DISCONNECT),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Modéliseur 3D · ChatGPT connecté").setContentText(progress)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(progress)).setContentIntent(open)
                .setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_SERVICE)
                .addAction(0,"Déconnecter",stop).build();
    }
    private void showNotification() {
        if (!connected() || SystemClock.elapsedRealtime()-lastNotification<3000) return;
        lastNotification=SystemClock.elapsedRealtime();
        // startForeground also works when Android 13 notification permission was declined.
        startForeground(NOTIFICATION,notification());
    }
    private void disconnect() {
        active=false;
        if (polling!=null) polling.cancel(false);
        compute.shutdownNow(); releasePower();
        // Serialized after any poll in flight, so a late heartbeat cannot undo disconnection.
        network.execute(()->{
            try {
                CloudApi current=api;
                if (current==null) {
                    SharedPreferences p=getSharedPreferences("mcp_local_bridge",MODE_PRIVATE);
                    String token=p.getString("token","");
                    if (!token.isEmpty()) current=new CloudApi(p.getString("server",McpBridgeSession.DEFAULT_SERVER),token);
                }
                if (current!=null) current.json("/api/disconnect",new JSONObject());
            } catch (Exception ignored) { }
            finally { network.shutdown(); }
        });
        stopForeground(true); stopSelf();
    }
    @Override public void onTaskRemoved(Intent rootIntent) {
        // Removing the screen from recent apps does not disable the user's connection.
        super.onTaskRemoved(rootIntent);
    }
    @Override public void onDestroy() {
        active=false;
        if (polling!=null) polling.cancel(false);
        network.shutdown(); compute.shutdownNow(); releasePower();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
