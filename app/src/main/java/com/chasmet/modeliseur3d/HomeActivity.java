package com.chasmet.modeliseur3d;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TabHost;
import android.widget.TextView;
import com.chasmet.modeliseur3d.update.UpdateManager;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/** Accueil des deux moteurs de reconstruction et du catalogue d'assets 3D. */
public final class HomeActivity extends AppCompatActivity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        TabHost tabs = findViewById(android.R.id.tabhost);
        tabs.setup();
        tabs.addTab(tabs.newTabSpec("25d").setIndicator(tabTitle("2.5D")).setContent(R.id.tab25d));
        tabs.addTab(tabs.newTabSpec("3d").setIndicator(tabTitle("3D")).setContent(R.id.tab3d));
        tabs.addTab(tabs.newTabSpec("trellis").setIndicator(tabTitle("TRELLIS")).setContent(R.id.tabTrellis));
        if (savedInstanceState != null) tabs.setCurrentTab(savedInstanceState.getInt("homeTab", 0));
        findViewById(R.id.settingsButton).setOnClickListener(view -> startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.mode25dButton).setOnClickListener(view ->
                startActivity(new Intent(this, MainActivityV52.class))
        );
        findViewById(R.id.mode3dButton).setOnClickListener(view ->
                startActivity(new Intent(this, Manual3DActivity.class))
        );
        findViewById(R.id.cloud3dButton).setOnClickListener(view ->
                startActivity(new Intent(this, Cloud3DActivity.class))
        );
        findViewById(R.id.assets3dButton).setOnClickListener(view ->
                startActivity(new Intent(this, Asset3DActivity.class))
        );
    }
    private TextView tabTitle(String text) {
        TextView title = new TextView(this);
        title.setText(text); title.setTextSize(16); title.setTextColor(android.graphics.Color.WHITE);
        title.setGravity(android.view.Gravity.CENTER);
        title.setBackgroundResource(android.R.drawable.list_selector_background);
        return title;
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("homeTab", ((TabHost) findViewById(android.R.id.tabhost)).getCurrentTab());
        super.onSaveInstanceState(state);
    }
    @Override protected void onResume() {
        super.onResume();
        UpdateManager.schedule(this);
        if (UpdateManager.automatic(this)) {
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
