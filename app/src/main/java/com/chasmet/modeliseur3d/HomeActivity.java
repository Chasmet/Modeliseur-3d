package com.chasmet.modeliseur3d;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/** Écran d'accueil volontairement limité aux deux modes de reconstruction. */
public final class HomeActivity extends AppCompatActivity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        findViewById(R.id.mode25dButton).setOnClickListener(view ->
                startActivity(new Intent(this, MainActivityV52.class))
        );
        findViewById(R.id.mode3dButton).setOnClickListener(view ->
                startActivity(new Intent(this, Manual3DActivity.class))
        );
    }
}
