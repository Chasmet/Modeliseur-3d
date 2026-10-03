package com.chasmet.modeliseur3d.mcp;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import com.chasmet.modeliseur3d.Offline3DActivity;
import com.chasmet.modeliseur3d.cloud.CloudApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lightweight bridge between ChatGPT MCP and the local Android reconstruction engines.
 * The relay only transports inputs, commands and finished GLB files. Inference stays on-device.
 */
public final class McpBridgeSession implements AutoCloseable {
    public static final String DEFAULT_SERVER = "https://modeliseur-trellis-mcp.onrender.com";
    private static final String PREFS = "mcp_local_bridge";
    private static final String KEY_SERVER = "server";
    private static final String KEY_TOKEN = "token";

    private final Activity activity;
    private final TextView statusView;
    private final boolean mayLaunchCommands;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean active;
    private volatile boolean busy;
    private volatile CloudApi api;

    public McpBridgeSession(Activity activity, TextView statusView, boolean mayLaunchCommands) {
        this.activity = activity;
        this.statusView = statusView;
        this.mayLaunchCommands = mayLaunchCommands;
    }

    public void start() {
        if (active) return;
        active = true;
        setStatus("ChatGPT MCP : connexion…");
        handler.post(tick);
    }

    public void stop() {
        active = false;
        handler.removeCallbacks(tick);
    }

    @Override public void close() {
        stop();
        worker.shutdownNow();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!active || activity.isDestroyed()) return;
            if (!busy) {
                busy = true;
                worker.execute(() -> {
                    try {
                        CloudApi current = ensureApi(activity);
                        api = current;
                        JSONObject response = current.json("/api/poll", null);
                        setStatus("ChatGPT MCP : téléphone connecté");
                        if (mayLaunchCommands) {
                            JSONArray commands = response.optJSONArray("local_commands");
                            if (commands != null && commands.length() > 0) {
                                prepareAndLaunch(current, commands.getJSONObject(0));
                            }
                        }
                    } catch (CloudApi.HttpFailure failure) {
                        if (failure.code == 401) {
                            clearToken(activity);
                            api = null;
                            setStatus("ChatGPT MCP : reconnexion automatique…");
                        } else {
                            setStatus("ChatGPT MCP : serveur indisponible");
                        }
                    } catch (Exception error) {
                        setStatus("ChatGPT MCP : connexion en attente");
                    } finally {
                        busy = false;
                    }
                });
            }
            handler.postDelayed(this, 4000);
        }
    };

    private void prepareAndLaunch(CloudApi current, JSONObject command) throws Exception {
        String id = CloudApi.id(command.getString("id"));
        String mode = command.getString("mode");
        JSONArray refs = command.getJSONArray("references");
        JSONObject options = command.optJSONObject("options");
        int expected = mode.endsWith("_four") ? 4 : 1;
        if (refs.length() != expected) {
            current.updateLocalStatus(id, "error", "Nombre d’images incompatible avec le moteur local.");
            return;
        }

        current.updateLocalStatus(id, "running", "Images reçues. Préparation du moteur local Android…");
        File folder = new File(activity.getFilesDir(), "mcp_inputs/" + id);
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Stockage MCP indisponible.");
        for (int i = 0; i < refs.length(); i++) {
            String ref = CloudApi.id(refs.getString(i));
            current.downloadLocalImage(id, ref, new File(folder, "image-" + i + ".png"));
        }

        String quality = options == null ? "balanced" : options.optString("quality", "balanced");
        boolean smoothing = options == null || options.optBoolean("smoothing", true);
        activity.runOnUiThread(() -> {
            if (!active || activity.isFinishing() || activity.isDestroyed()) return;
            Intent intent = new Intent(activity, Offline3DActivity.class)
                    .putExtra(Offline3DActivity.EXTRA_MCP_COMMAND_ID, id)
                    .putExtra(Offline3DActivity.EXTRA_MCP_MODE, mode)
                    .putExtra(Offline3DActivity.EXTRA_MCP_QUALITY, quality)
                    .putExtra(Offline3DActivity.EXTRA_MCP_SMOOTHING, smoothing);
            activity.startActivity(intent);
        });
    }

    private void setStatus(String value) {
        if (statusView == null) return;
        activity.runOnUiThread(() -> {
            if (!activity.isDestroyed()) statusView.setText(value);
        });
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static synchronized CloudApi ensureApi(Context context) throws Exception {
        SharedPreferences p = prefs(context);
        String server = p.getString(KEY_SERVER, DEFAULT_SERVER);
        String token = p.getString(KEY_TOKEN, "");
        if (!token.isEmpty()) {
            CloudApi existing = new CloudApi(server, token);
            try {
                existing.json("/api/heartbeat", null);
                return existing;
            } catch (CloudApi.HttpFailure failure) {
                if (failure.code != 401) throw failure;
                p.edit().remove(KEY_TOKEN).apply();
            }
        }

        CloudApi anonymous = new CloudApi(server, "");
        JSONObject health = anonymous.json("/health", null);
        if (!"android_local_triposr".equals(health.optString("generation"))) {
            throw new IOException("Le relais MCP n'est pas configuré pour les moteurs locaux Android.");
        }
        JSONObject registration = anonymous.json("/api/register", new JSONObject());
        String fresh = registration.getString("token");
        p.edit().putString(KEY_SERVER, anonymous.base()).putString(KEY_TOKEN, fresh).apply();
        return new CloudApi(anonymous.base(), fresh);
    }

    private static void clearToken(Context context) {
        prefs(context).edit().remove(KEY_TOKEN).apply();
    }

    public static void uploadResult(Context context, String commandId, File glb) throws Exception {
        CloudApi current = ensureApi(context.getApplicationContext());
        current.uploadLocalResult(commandId, glb);
    }

    public static void reportError(Context context, String commandId, String message) {
        if (commandId == null || !commandId.matches("[a-f0-9]{32}")) return;
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                ensureApi(context.getApplicationContext()).updateLocalStatus(
                        commandId, "error", message == null ? "Échec du calcul local." : message);
            } catch (Exception ignored) { }
        });
    }
}
