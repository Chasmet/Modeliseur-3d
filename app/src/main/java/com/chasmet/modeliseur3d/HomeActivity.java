package com.chasmet.modeliseur3d;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TabHost;
import android.widget.TextView;
import com.chasmet.modeliseur3d.update.UpdateManager;
import com.chasmet.modeliseur3d.mcp.McpConnectionService;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/** Accueil des moteurs locaux, du catalogue et du pont MCP ChatGPT. */
public final class HomeActivity extends AppCompatActivity {
    private final android.os.Handler connectionUi = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable refreshConnection = new Runnable() {
        @Override public void run() {
            boolean enabled = McpConnectionService.enabled(HomeActivity.this);
            ((android.widget.Button)findViewById(R.id.mcpConnectionButton)).setText(enabled ? "Désactiver MCP" : "Activer MCP");
            ((TextView)findViewById(R.id.mcpHomeStatus)).setText(McpConnectionService.status(HomeActivity.this));
            connectionUi.postDelayed(this, 1000);
        }
    };
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.chasmet.modeliseur3d.diagnostics.DiagnosticLog.initialize(this);
        com.chasmet.modeliseur3d.diagnostics.DiagnosticLog.record("INFO","Ouverture de l’application "+UpdateManager.currentVersion(this));
        setContentView(R.layout.activity_home);
        ((TextView)findViewById(R.id.homeVersion)).setText("Modéliseur 3D V"+UpdateManager.currentVersion(this));

        findViewById(R.id.mcpConnectionButton).setOnClickListener(view -> {
            boolean connect = !McpConnectionService.enabled(this);
            McpConnectionService.setEnabled(this, connect);
            boolean notificationPermissionNeeded = connect && android.os.Build.VERSION.SDK_INT >= 33
                    && androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (notificationPermissionNeeded) {
                androidx.core.app.ActivityCompat.requestPermissions(this,
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 803);
            } else if (connect) {
                requestBackgroundExemption();
            }
            connectionUi.removeCallbacks(refreshConnection);
            refreshConnection.run();
        });
        TabHost tabs = findViewById(android.R.id.tabhost);
        tabs.setup();
        tabs.addTab(tabs.newTabSpec("25d").setIndicator(tabTitle("2.5D")).setContent(R.id.tab25d));
        tabs.addTab(tabs.newTabSpec("3d").setIndicator(tabTitle("3D · 4 images")).setContent(R.id.tab3d));
        tabs.addTab(tabs.newTabSpec("trellis").setIndicator(tabTitle("IA locale")).setContent(R.id.tabTrellis));
        tabs.setCurrentTab(savedInstanceState != null ? savedInstanceState.getInt("homeTab", 1)
                : getPreferences(MODE_PRIVATE).getInt("homeTab", 1));
        tabs.setOnTabChangedListener(tag -> getPreferences(MODE_PRIVATE).edit().putInt("homeTab", tabs.getCurrentTab()).apply());
        findViewById(R.id.settingsButton).setOnClickListener(view -> startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.mode25dButton).setOnClickListener(view ->
                startActivity(new Intent(this, MainActivityV52.class))
        );
        findViewById(R.id.mode3dButton).setOnClickListener(view ->
                startActivity(new Intent(this, Manual3DActivity.class))
        );
        findViewById(R.id.offline3dButton).setOnClickListener(view -> startActivity(new Intent(this, Offline3DActivity.class)));
        findViewById(R.id.singleImage3dButton).setOnClickListener(view -> startActivity(new Intent(this, Offline3DActivity.class).putExtra(Offline3DActivity.EXTRA_SINGLE_IMAGE,true)));
        findViewById(R.id.assets3dButton).setOnClickListener(view ->
                startActivity(new Intent(this, Asset3DActivity.class))
        );
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode==803 && McpConnectionService.enabled(this)) requestBackgroundExemption();
    }

    private void requestBackgroundExemption() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return;
        android.os.PowerManager power=(android.os.PowerManager)getSystemService(POWER_SERVICE);
        if (power==null || power.isIgnoringBatteryOptimizations(getPackageName())) return;
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:"+getPackageName())));
        } catch (RuntimeException unavailable) {
            try { startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
            catch (RuntimeException ignored) { }
        }
    }

    private TextView tabTitle(String text) {
        TextView title = new TextView(this);
        title.setText(text); title.setTextSize(13); title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(new android.content.res.ColorStateList(new int[][]{{android.R.attr.state_selected},{}},
                new int[]{android.graphics.Color.WHITE,0xFF121722}));
        title.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.StateListDrawable background = new android.graphics.drawable.StateListDrawable();
        for (boolean selected : new boolean[]{true,false}) {
            android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
            shape.setColor(selected ? 0xFFA83D00 : 0xFFDEE5EF); shape.setCornerRadius(8 * getResources().getDisplayMetrics().density);
            background.addState(selected ? new int[]{android.R.attr.state_selected} : new int[]{},shape);
        }
        title.setBackground(background);
        title.setContentDescription(text);
        return title;
    }
    @Override protected void onStart() {
        super.onStart();
        if (McpConnectionService.enabled(this)) McpConnectionService.start(this);
        connectionUi.removeCallbacks(refreshConnection);
        refreshConnection.run();
    }
    @Override protected void onStop() {
        connectionUi.removeCallbacks(refreshConnection);
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("homeTab", ((TabHost) findViewById(android.R.id.tabhost)).getCurrentTab());
        super.onSaveInstanceState(state);
    }
    @Override protected void onResume() {
        super.onResume();
        UpdateManager.schedule(this);
        if (((TabHost) findViewById(android.R.id.tabhost)).getCurrentTab() != 2 && UpdateManager.automatic(this)) {
            UpdateManager.executor.execute(() -> {
                try { UpdateManager.check(this); } catch (Exception ignored) { }
                runOnUiThread(() -> {
                    String version = UpdateManager.pendingVersion(this);
                    ((TextView) findViewById(R.id.updateHomeStatus)).setText(version.isEmpty() ? "" : "Mise à jour " + version + " disponible dans Réglages.");
                });
            });
        }
    }
}
