package com.chasmet.modeliseur3d;

import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.chasmet.modeliseur3d.model.RuntimeModelStore;

public final class ModelPackActivity extends AppCompatActivity {
    public static final String EXTRA_TARGET = "target";
    private ProgressBar progress;
    private TextView status;
    private Button action;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 64, 48, 48);

        TextView title = new TextView(this);
        title.setText("Modeliseur 3D V9.2 — Installation IA");
        title.setTextSize(25);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(0, 24, 0, 24);
        root.addView(status);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        root.addView(progress, new LinearLayout.LayoutParams(-1, 32));

        action = new Button(this);
        action.setText("Télécharger / reprendre les IA");
        action.setOnClickListener(v -> installPack());
        root.addView(action);
        setContentView(root);
        refresh();
    }

    private void refresh() {
        long total = RuntimeModelStore.totalBytes();
        long done = RuntimeModelStore.installedBytes(this);
        progress.setProgress(total > 0 ? (int) Math.min(1000, done * 1000 / total) : 0);
        if (RuntimeModelStore.isReady(this)) {
            status.setText("Pack IA vérifié. Le mode hors ligne est prêt.");
            action.setText("Continuer");
            action.setOnClickListener(v -> openTarget());
        } else {
            status.setText("IA à installer : " + (total - done) / (1024 * 1024) + " Mo restants. Une coupure pourra reprendre.");
        }
    }

    private void installPack() {
        action.setEnabled(false);
        new Thread(() -> {
            try {
                RuntimeModelStore.installAll(this, (model, modelDone, modelTotal, done, total, state) ->
                        runOnUiThread(() -> {
                            progress.setProgress((int) Math.min(1000, done * 1000 / total));
                            status.setText(model + " — " + state + " — " + Math.round(done * 100f / total) + " %");
                        }));
                runOnUiThread(() -> {
                    action.setEnabled(true);
                    refresh();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    action.setEnabled(true);
                    action.setText("Reprendre");
                    status.setText("Interrompu : " + error.getMessage() + ". Les données déjà reçues sont conservées.");
                });
            }
        }, "v92-model-pack").start();
    }

    private void openTarget() {
        String target = getIntent().getStringExtra(EXTRA_TARGET);
        Class<?> destination = "25d".equals(target) ? MainActivityV52.class : Manual3DActivity.class;
        startActivity(new Intent(this, destination));
        finish();
    }
}
