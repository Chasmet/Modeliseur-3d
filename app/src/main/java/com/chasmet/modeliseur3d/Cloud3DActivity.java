package com.chasmet.modeliseur3d;

import android.app.ActivityManager;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.*;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import com.chasmet.modeliseur3d.cloud.CloudApi;
import org.json.*;
import java.io.*;
import java.util.concurrent.*;

/** Cloud GPU reconstruction with automatic no-auth MCP presence for this phone. */
public final class Cloud3DActivity extends AppCompatActivity {
    private static final String DEFAULT_SERVER = "https://modeliseur-trellis-mcp.onrender.com";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService heartbeatWorker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private EditText server;
    private TextView status, connectionStatus;
    private CheckBox rig;
    private Switch mcpSwitch;
    private Button connect, choose, generate, open, export, copyLink, clear;
    private volatile CloudApi api;
    private JSONObject saved = new JSONObject();
    private File image, model;
    private volatile boolean visible, busy, suppressToggle, registering, heartbeatInFlight;
    private String reference = "", latestJob = "";
    private final ActivityResultLauncher<String[]> picker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::imagePicked);
    private final ActivityResultLauncher<String> exporter = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("model/gltf-binary"), uri -> {
                File source = model;
                if (uri == null || source == null) return;
                runTask(() -> {
                    try (InputStream in = new FileInputStream(source);
                         OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                        if (out == null) throw new IOException("Export refusé par le dossier choisi.");
                        CloudApi.copy(in, out);
                    }
                    ui(() -> status.setText("GLB exporté."));
                });
            });
    interface Work { void run() throws Exception; }
    private void ui(Runnable action) { runOnUiThread(() -> { if (!isDestroyed()) action.run(); }); }
    private void runTask(Work task) {
        if (busy) return;
        busy = true; buttons();
        worker.execute(() -> {
            try { task.run(); }
            catch (Exception e) { ui(() -> status.setText(e.getMessage() == null ? "Opération impossible." : e.getMessage())); }
            finally { ui(() -> { busy = false; buttons(); }); }
        });
    }
    private File stateFile() { return new File(getNoBackupFilesDir(), "cloud-connection.json"); }
    private synchronized void save() throws Exception {
        // Tokens stay in app-private, non-backed-up storage, never in a shared file or APK.
        File tmp = new File(stateFile().getPath() + ".part");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(saved.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!tmp.renameTo(stateFile())) throw new IOException("Sauvegarde de connexion impossible.");
    }
    private Button button(LinearLayout layout, String title, Runnable action) {
        Button b = new Button(this); b.setText(title); b.setAllCaps(false);
        layout.addView(b); b.setOnClickListener(v -> action.run()); return b;
    }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try (InputStream in = new FileInputStream(stateFile()); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CloudApi.copy(in, out); saved = new JSONObject(out.toString("UTF-8"));
            api = new CloudApi(saved.getString("server"), saved.getString("token"));
            reference = saved.optString("reference"); latestJob = saved.optString("job");
        } catch (Exception ignored) { api = null; }
        if (latestJob.matches("[a-f0-9]{32}")) {
            File f = new File(getFilesDir(), "cloud_models/" + latestJob + ".glb");
            if (f.isFile()) model = f;
        }
        ScrollView scroll = new ScrollView(this);
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        panel.setPadding(padding, padding, padding, padding); scroll.addView(panel); setContentView(scroll);
        TextView title = new TextView(this); title.setText("TRELLIS.2 + MCP"); title.setTextSize(26); panel.addView(title);
        TextView info = new TextView(this);
        ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (am != null) am.getMemoryInfo(mem);
        info.setText(String.format(java.util.Locale.FRANCE,
                "RAM détectée : %.1f Go • mémoire de l'appli : %d Mo\nProfil mobile : 100 000 triangles, texture 1 024 px.\nTRELLIS.2 calcule sur un GPU distant. L'image est envoyée au relais et à Hugging Face. Les modes locaux restent accessibles à l'accueil.\nLe MCP répond lorsque cet écran est ouvert.",
                mem.totalMem / 1073741824.0, Runtime.getRuntime().maxMemory() / 1048576));
        panel.addView(info);
        server = new EditText(this); server.setHint("https://ton-relais.onrender.com");
        server.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        server.setSingleLine(true); server.setText(saved.optString("server", DEFAULT_SERVER)); panel.addView(server);
        connect = button(panel, "Connecter ce téléphone au relais", this::connectRelay);
        choose = button(panel, "Choisir une image", () -> picker.launch(new String[]{"image/*"}));
        rig = new CheckBox(this); rig.setText("Ajouter des animations humanoïdes approximatives"); panel.addView(rig);
        generate = button(panel, "Générer le modèle 3D", this::generate);
        open = button(panel, "Ouvrir le dernier GLB", () -> openModel(model));
        export = button(panel, "Exporter le dernier GLB", () -> exporter.launch("TRELLIS_mobile.glb"));
        mcpSwitch = new Switch(this); mcpSwitch.setText("Compatibilité ancien lien MCP privé");
        mcpSwitch.setChecked(!saved.optString("mcp").isEmpty()); panel.addView(mcpSwitch);
        mcpSwitch.setVisibility(android.view.View.GONE);
        mcpSwitch.setOnCheckedChangeListener((v, enabled) -> { if (!suppressToggle) toggleMcp(enabled); });
        copyLink = button(panel, "Copier l’URL MCP ChatGPT", () -> {
            if (api == null) return;
            ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cb != null) cb.setPrimaryClip(ClipData.newPlainText("MCP Modéliseur 3D", api.base() + "/mcp"));
            status.setText("URL MCP copiée. Dans ChatGPT Plugins, choisis Aucune authentification.");
        });
        clear = button(panel, "Effacer mes images et travaux du relais", this::clearRemote);
        connectionStatus = new TextView(this); panel.addView(connectionStatus);
        connectionStatus.setText(api == null ? "MCP : connexion automatique au serveur…" : "MCP : vérification de la présence du téléphone…");
        status = new TextView(this); panel.addView(status);
        status.setText("Prêt. Ouvre cet écran pour rendre le téléphone visible automatiquement dans ChatGPT.");
        buttons();
    }
    private void buttons() {
        if (connect == null) return;
        connect.setEnabled(!busy); choose.setEnabled(!busy);
        generate.setEnabled(!busy && api != null && (!reference.isEmpty() || image != null));
        open.setEnabled(!busy && model != null); export.setEnabled(!busy && model != null);
        mcpSwitch.setEnabled(!busy && api != null); clear.setEnabled(!busy && api != null);
        copyLink.setEnabled(!busy && api != null);
        server.setEnabled(!busy); rig.setEnabled(!busy);
    }
    private void connectRelay() {
        String address = server.getText().toString();
        runTask(() -> {
            CloudApi candidate = new CloudApi(address, "");
            JSONObject health = candidate.json("/health", null);
            if (!"remote_trellis2".equals(health.optString("generation"))) throw new IOException("Ce serveur n'est pas un relais Modéliseur 3D.");
            // Explicit reconnect: revoke the previous MCP link when reachable.
            if (api != null && !saved.optString("mcp").isEmpty()) {
                try { api.json("/api/mcp", new JSONObject().put("enabled", false)); }
                catch (CloudApi.HttpFailure e) { if (e.code != 401) throw e; }
            }
            JSONObject registration = candidate.json("/api/register", new JSONObject());
            api = new CloudApi(candidate.base(), registration.getString("token"));
            saved = new JSONObject().put("server", candidate.base()).put("token", registration.getString("token"));
            reference = ""; latestJob = ""; save();
            ui(() -> {
                checked(false);
                connectionStatus.setText("MCP : téléphone connecté. Présence automatique active.");
                status.setText("Téléphone connecté. Choisis une image pour commencer.");
            });
        });
    }
    private void checked(boolean enabled) {
        suppressToggle = true; mcpSwitch.setChecked(enabled); suppressToggle = false;
    }
    private void imagePicked(Uri uri) {
        if (uri == null) return;
        runTask(() -> {
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, bounds); }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Image illisible.");
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 1024) options.inSampleSize *= 2;
            Bitmap bitmap;
            try (InputStream in = getContentResolver().openInputStream(uri)) { bitmap = BitmapFactory.decodeStream(in, null, options); }
            if (bitmap == null) throw new IOException("Image illisible.");
            File prepared = new File(getCacheDir(), "trellis-input.jpg");
            try (OutputStream out = new FileOutputStream(prepared)) {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) throw new IOException("Préparation de l'image impossible.");
            } finally { bitmap.recycle(); }
            image = prepared; reference = ""; saved.remove("reference"); save();
            ui(() -> status.setText("Image préparée à 1 024 pixels maximum. Elle sera envoyée lorsque tu lanceras la génération."));
        });
    }
    private void generate() {
        final boolean humanoid = rig.isChecked();
        runTask(() -> {
            if (reference.isEmpty()) {
                reference = CloudApi.id(api.upload(image).getString("reference_id"));
                saved.put("reference", reference); save();
            }
            JSONObject result = api.json("/api/jobs", new JSONObject().put("reference_id", reference).put("humanoid", humanoid));
            latestJob = CloudApi.id(result.getString("id")); saved.put("job", latestJob); save();
            ui(() -> status.setText("Génération demandée. Tu peux rouvrir cet écran pour retrouver son état."));
        });
    }
    private void toggleMcp(boolean enabled) {
        if (busy || api == null) { checked(!saved.optString("mcp").isEmpty()); return; }
        runTask(() -> {
            try {
                JSONObject r = api.json("/api/mcp", new JSONObject().put("enabled", enabled));
                saved.put("mcp", enabled ? r.getString("mcp_token") : ""); save();
                ui(() -> status.setText(enabled ? "MCP autorisé. Copie le lien privé pour connecter ChatGPT." : "MCP désactivé sur le relais. L'ancien lien est révoqué."));
            } finally { ui(() -> checked(!saved.optString("mcp").isEmpty())); }
        });
    }
    private void clearRemote() {
        runTask(() -> {
            api.json("/api/clear", new JSONObject());
            reference = ""; latestJob = ""; saved.remove("reference"); saved.remove("job"); save();
            ui(() -> status.setText("Images et travaux distants effacés. Les GLB téléchargés restent sur ton téléphone."));
        });
    }
    private File obtain(String jobId) throws Exception {
        File directory = new File(getFilesDir(), "cloud_models");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Stockage indisponible.");
        File file = new File(directory, CloudApi.id(jobId) + ".glb");
        if (!file.isFile()) api.download(jobId, file);
        return file;
    }
    private void openModel(File file) {
        if (file == null || !file.isFile()) return;
        String id = file.getName().replace(".glb", "");
        startActivity(new Intent(this, CloudModelViewerActivity.class).putExtra("job", id));
    }
    private void ensureRelayConnected() {
        if (!visible || api != null || registering || isDestroyed()) return;
        registering = true;
        String address = server == null ? saved.optString("server", DEFAULT_SERVER) : server.getText().toString().trim();
        if (address.isEmpty()) address = DEFAULT_SERVER;
        final String target = address;
        heartbeatWorker.execute(() -> {
            try {
                CloudApi candidate = new CloudApi(target, "");
                JSONObject health = candidate.json("/health", null);
                if (!"remote_trellis2".equals(health.optString("generation"))) {
                    throw new IOException("Ce serveur n'est pas un relais Modéliseur 3D.");
                }
                JSONObject registration = candidate.json("/api/register", new JSONObject());
                CloudApi connected = new CloudApi(candidate.base(), registration.getString("token"));
                synchronized (this) {
                    api = connected;
                    saved.put("server", candidate.base()).put("token", registration.getString("token"));
                    saved.remove("mcp");
                    saved.remove("reference");
                    saved.remove("job");
                    reference = "";
                    latestJob = "";
                    save();
                }
                ui(() -> {
                    server.setText(candidate.base());
                    connectionStatus.setText("MCP : téléphone connecté. Présence automatique active.");
                    buttons();
                });
            } catch (Exception e) {
                ui(() -> connectionStatus.setText("MCP : connexion impossible. Nouvelle tentative automatique…"));
            } finally {
                registering = false;
            }
        });
    }

    private void sendHeartbeat() {
        if (!visible || api == null || heartbeatInFlight || isDestroyed()) return;
        heartbeatInFlight = true;
        CloudApi current = api;
        heartbeatWorker.execute(() -> {
            try {
                current.json("/api/poll", null);
                ui(() -> connectionStatus.setText("MCP : téléphone en ligne."));
            } catch (CloudApi.HttpFailure e) {
                if (e.code == 401) {
                    synchronized (this) {
                        if (api == current) api = null;
                        saved.remove("token");
                        saved.remove("mcp");
                        saved.remove("reference");
                        saved.remove("job");
                        reference = "";
                        latestJob = "";
                        try { save(); } catch (Exception ignored) {}
                    }
                    ui(() -> connectionStatus.setText("MCP : session serveur renouvelée automatiquement…"));
                } else {
                    ui(() -> connectionStatus.setText("MCP : serveur temporairement indisponible."));
                }
            } catch (Exception e) {
                ui(() -> connectionStatus.setText("MCP : serveur temporairement indisponible."));
            } finally {
                heartbeatInFlight = false;
            }
        });
    }

    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            if (api == null) ensureRelayConnected(); else sendHeartbeat();
            handler.postDelayed(this, 4000);
        }
    };

    private final Runnable polling = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            if (api != null && !busy) runTask(() -> {
                JSONObject r = api.json("/api/poll", null);
                JSONArray jobs = r.getJSONArray("jobs");
                if (jobs.length() > 0) {
                    JSONObject job = jobs.getJSONObject(0);
                    String id = CloudApi.id(job.getString("id"));
                    latestJob = id; saved.put("job", id); save();
                    if ("ready".equals(job.getString("status"))) model = obtain(id);
                    String message = job.optString("message");
                    ui(() -> status.setText(message));
                }
                JSONArray commands = r.getJSONArray("commands");
                if (visible && commands.length() > 0) {
                    JSONObject command = commands.getJSONObject(0);
                    File f = obtain(command.getString("job"));
                    api.json("/api/commands/" + CloudApi.id(command.getString("id")) + "/ack", new JSONObject());
                    ui(() -> { if (visible) { model = f; openModel(f); } });
                }
            });
            handler.postDelayed(this, 5000);
        }
    };
    @Override protected void onStart() {
        super.onStart();
        visible = true;
        handler.post(heartbeat);
        handler.post(polling);
    }
    @Override protected void onStop() {
        visible = false;
        handler.removeCallbacks(heartbeat);
        handler.removeCallbacks(polling);
        super.onStop();
    }
    @Override protected void onDestroy() {
        worker.shutdown();
        heartbeatWorker.shutdownNow();
        super.onDestroy();
    }
}
