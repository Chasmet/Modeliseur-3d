package com.chasmet.modeliseur3d.mcp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Restarts the user-enabled MCP foreground service after Android reclaims it,
 * after a reboot, or after the APK is updated. Explicit "Déconnecté" remains authoritative.
 */
public final class McpKeepAliveReceiver extends BroadcastReceiver {
    public static final String ACTION_RESTART = "com.chasmet.modeliseur3d.MCP_RESTART";

    @Override public void onReceive(Context context, Intent intent) {
        if (!McpConnectionService.enabled(context)) return;
        try {
            McpConnectionService.start(context.getApplicationContext());
        } catch (RuntimeException ignored) {
            // Android can temporarily deny a foreground-service start; opening the app retries it.
        }
    }
}
