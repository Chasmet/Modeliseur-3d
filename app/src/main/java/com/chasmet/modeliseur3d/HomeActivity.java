package com.chasmet.modeliseur3d;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.chasmet.modeliseur3d.model.RuntimeModelStore;

/** Accueil des moteurs de reconstruction et du catalogue d'assets 3D. */
public final class HomeActivity extends AppCompatActivity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        findViewById(R.id.mode25dButton).setOnClickListener(view -> openAiMode("25d"));
        findViewById(R.id.mode3dButton).setOnClickListener(view -> openAiMode("3d"));
        findViewById(R.id.assets3dButton).setOnClickListener(view ->
                startActivity(new Intent(this, Asset3DActivity.class))
        );
    }

    private void openAiMode(String target) {
        if (RuntimeModelStore.isReady(this)) {
            Class<?> destination = "25d".equals(target)
                    ? MainActivityV52.class
                    : Manual3DActivity.class;
            startActivity(new Intent(this, destination));
            return;
        }
        Intent installer = new Intent(this, ModelPackActivity.class);
        installer.putExtra(ModelPackActivity.EXTRA_TARGET, target);
        startActivity(installer);
    }
}
