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
        tabs.addTab(tabs.newTabSpec("3d").setIndicator(tabTitle("3D · 4 images")).setContent(R.id.tab3d));
        tabs.addTab(tabs.newTabSpec("trellis").setIndicator(tabTitle("TRELLIS")).setContent(R.id.tabTrellis));
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
        findViewById(R.id.assets3dButton).setOnClickListener(view ->
                startActivity(new Intent(this, Asset3DActivity.class))
        );
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
