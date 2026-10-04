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
    private static final String RECONFIGURE = "com.chasmet.modeliseur3d.MCP_RECONFIGURE";
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
    private PowerManager.WakeLock connectionPower;
    private long lastNotification;
    private PhoneMcpServer phoneServer;
    private android.net.wifi.WifiManager.WifiLock wifi;
    private android.net.ConnectivityManager connectivity;
    private android.net.ConnectivityManager.NetworkCallback networkCallback;
    private int failures;
    private long retryAt;


    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS,MODE_PRIVATE); }
    public static boolean enabled(Context c) { return prefs(c).getBoolean("enabled",false); }
    public static String status(Context c) {
        SharedPreferences p=prefs(c);
        if (!enabled(c)) return "ChatGPT MCP : déconnecté";
        if (PhoneMcpSettings.direct(c)) {
            boolean running=PhoneMcpSettings.prefs(c).getBoolean("running",false);
            return running ? "MCP direct : serveur téléphone actif\n"+p.getString("progress","En attente de commande")
                    +(PhoneMcpSettings.publicBase(c).isEmpty()?"\nAdresse HTTPS publique à configurer":!PhoneMcpSettings.prefs(c).getBoolean("https_running",false)?"\n"+PhoneMcpSettings.prefs(c).getString("tls_error","HTTPS indisponible")
                    :PhoneMcpSettings.prefs(c).getLong("external_client",0)>0?"\nAccès HTTPS extérieur observé":"\nAccès Internet non vérifié")
                    : "MCP direct : démarrage en attente";
        }
        long last=p.getLong("heartbeat",0);
        if (last==0 || System.currentTimeMillis()-last>90000) return "ChatGPT MCP : reconnexion…";
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
    public static void restart(Context c) {
        ContextCompat.startForegroundService(c,new Intent(c,McpConnectionService.class).setAction(RECONFIGURE));
    }
    @Override public void onCreate() {
        super.onCreate();
        PhoneMcpSettings.prefs(this).edit().putBoolean("running",false).remove("external_client").apply();
        android.net.wifi.WifiManager manager=(android.net.wifi.WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
        if (manager!=null) { wifi=manager.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,"Modeliseur3D:McpWifi");wifi.setReferenceCounted(false); }

        if (Build.VERSION.SDK_INT>=26) {
            NotificationChannel channel=new NotificationChannel(CHANNEL,"Connexion ChatGPT et calcul 3D",NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Le service reste actif pendant YouTube, Netflix ou lorsque l’écran de l’application est fermé.");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
        PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
        power=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Modeliseur3D:McpJob");
        power.setReferenceCounted(false);
        connectionPower=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Modeliseur3D:McpConnection");
        connectionPower.setReferenceCounted(false);
        connectivity=(android.net.ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        networkCallback=new android.net.ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(android.net.Network n) { changed(); }
            @Override public void onLost(android.net.Network n) { changed(); }
            @Override public void onLinkPropertiesChanged(android.net.Network n,android.net.LinkProperties p) { changed(); }
            private void changed() {
                if(network.isShutdown())return;
                try {network.execute(()->{
                    retryAt=0;failures=0;
                    try {
                        String snapshot=PhoneNetworkDiagnostics.snapshot(McpConnectionService.this).toString();
                        SharedPreferences state=PhoneMcpSettings.prefs(McpConnectionService.this);
                        if(!snapshot.equals(state.getString("network_snapshot","")))
                            state.edit().putString("network_snapshot",snapshot).remove("external_client").apply();
                    } catch(Exception ignored) { }
                });} catch(RejectedExecutionException ignored) { }
            }
        };
        try {
            if(connectivity!=null)connectivity.registerNetworkCallback(new android.net.NetworkRequest.Builder()
                    .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),networkCallback);
        } catch(RuntimeException ignored) { }
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if ((intent!=null && DISCONNECT.equals(intent.getAction())) || !enabled(this)) {
            prefs(this).edit().putBoolean("enabled",false).putLong("heartbeat",0).commit();
            disconnect(); return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION,notification());
        if(intent!=null && RECONFIGURE.equals(intent.getAction()) && active) {
            network.execute(()->{closePhoneServer();api=null;retryAt=0;failures=0;});
        }
        cancelRestart(this);
        acquireConnectionPower();
        try { if(wifi!=null && !wifi.isHeld())wifi.acquire(); } catch(RuntimeException ignored) { }

        if (!active) {
            active=true;
            polling=network.scheduleWithFixedDelay(this::pollOnce,0,4,TimeUnit.SECONDS);
        }
        return START_STICKY;
    }
    protected synchronized CloudApi connect() throws Exception {
        if (!PhoneMcpSettings.direct(this)) return McpBridgeSession.ensureApi(this);
        PhoneMcpStore store=new PhoneMcpStore(this);
        phoneServer=new PhoneMcpServer(this,store);
        try { phoneServer.start(); }
        catch(Exception failure) { phoneServer.close();phoneServer=null;throw failure; }
        if(!connected()) { phoneServer.close();phoneServer=null;throw new CancellationException("Connexion arrêtée."); }
        PhoneMcpSettings.prefs(this).edit().putBoolean("running",true).apply();
        try {if(wifi!=null && !wifi.isHeld())wifi.acquire();}catch(RuntimeException ignored) { }
        progress="En attente de commande · mode Précis + IS-Net";
        return new PhoneMcpApi(store);
    }
    protected File generate(JSONObject command,TripoSREngine.Progress callback) throws Exception {
        return LocalMcpGeneration.generate(this,command,callback);
    }
    private boolean connected() { return active && enabled(this); }
    void pollOnce() {
        if (!connected()) return;
        if(api==null && SystemClock.elapsedRealtime()<retryAt)return;
        try {
            if (api==null) api=connect();
            JSONObject response=api.json("/api/poll",null);
            if (!connected()) return;
            failures=0;retryAt=0;
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
            if(api==null)retryAt=SystemClock.elapsedRealtime()+Math.min(60000L,1000L << Math.min(6,failures++));
            progress=working.get()?progress:PhoneMcpSettings.direct(this)?"Serveur téléphone indisponible · vérifie le port et le certificat":"Relais indisponible · reconnexion automatique";
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
    private synchronized void acquireConnectionPower() {
        if (connectionPower!=null && !connectionPower.isHeld()) connectionPower.acquire();
    }
    private synchronized void releaseConnectionPower() {
        if (connectionPower!=null && connectionPower.isHeld()) connectionPower.release();
    }
    private static PendingIntent restartIntent(Context c) {
        Intent intent=new Intent(c,McpKeepAliveReceiver.class).setAction(McpKeepAliveReceiver.ACTION_RESTART);
        return PendingIntent.getBroadcast(c,804,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    private static void scheduleRestart(Context c,long delayMs) {
        if (!enabled(c)) return;
        try {
            AlarmManager alarms=(AlarmManager)c.getSystemService(ALARM_SERVICE);
            if (alarms!=null) {
                long when=SystemClock.elapsedRealtime()+Math.max(1000L,delayMs);
                PendingIntent restart=restartIntent(c);
                if (Build.VERSION.SDK_INT>=Build.VERSION_CODES.M) {
                    alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,when,restart);
                } else {
                    alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,when,restart);
                }
            }
        } catch (RuntimeException ignored) { }
    }
    private static void cancelRestart(Context c) {
        try {
            AlarmManager alarms=(AlarmManager)c.getSystemService(ALARM_SERVICE);
            if (alarms!=null) alarms.cancel(restartIntent(c));
        } catch (RuntimeException ignored) { }
    }
    private Notification notification() {
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,HomeActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,McpConnectionService.class).setAction(DISCONNECT),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Modéliseur 3D · serveur téléphone actif").setContentText(progress)
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
        cancelRestart(this);
        if (polling!=null) polling.cancel(false);
        compute.shutdownNow(); releasePower();
        // Serialized after any poll in flight, so a late heartbeat cannot undo disconnection.
        network.execute(()->{
            try {
                CloudApi current=api;
                if (current==null && !PhoneMcpSettings.direct(this)) {
                    SharedPreferences p=getSharedPreferences("mcp_local_bridge",MODE_PRIVATE);
                    String token=p.getString("token","");
                    if (!token.isEmpty()) current=new CloudApi(p.getString("server",McpBridgeSession.DEFAULT_SERVER),token);
                }
                if (current!=null) current.json("/api/disconnect",new JSONObject());
            } catch (Exception ignored) { }
            finally { network.shutdown(); }
        });
        releaseConnectionPower();
        closePhoneServer();
        stopForeground(true); stopSelf();
    }
    @Override public void onTaskRemoved(Intent rootIntent) {
        // Some Android/OEM builds still reclaim foreground services. Schedule a cheap restart.
        scheduleRestart(this,5000L);
        super.onTaskRemoved(rootIntent);
    }
    @Override public void onDestroy() {
        active=false;
        try { if(connectivity!=null && networkCallback!=null)connectivity.unregisterNetworkCallback(networkCallback); }catch(RuntimeException ignored) { }
        closePhoneServer();
        boolean shouldRestart=enabled(this);
        active=false;
        if (polling!=null) polling.cancel(false);
        network.shutdown(); compute.shutdownNow(); releasePower(); releaseConnectionPower();
        if (shouldRestart) scheduleRestart(this,5000L);
        super.onDestroy();
    }
    private synchronized void closePhoneServer() {
        if(phoneServer!=null) {phoneServer.close();phoneServer=null;}
        PhoneMcpSettings.prefs(this).edit().putBoolean("running",false).apply();
        PhoneMcpSettings.prefs(this).edit().putBoolean("https_running",false).apply();
        try {if(wifi!=null && wifi.isHeld())wifi.release();}catch(RuntimeException ignored) { }
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
