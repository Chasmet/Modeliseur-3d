package com.chasmet.modeliseur3d;

import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.chasmet.modeliseur3d.gl.ModelGLSurfaceView;
import com.chasmet.modeliseur3d.model.MeshData;
import com.chasmet.modeliseur3d.model.NeuralReconstructionEngine;
import com.chasmet.modeliseur3d.model.ObjExporter;
import com.chasmet.modeliseur3d.util.BitmapUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends AppCompatActivity {
    private static final int REQUEST_IMAGE = 2001;
    private static final int MAX_INPUT_SIDE = 3072;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private NeuralReconstructionEngine generator;
    private ModelGLSurfaceView viewer;
    private ProgressBar progressBar;
    private TextView statusText;
    private TextView emptyText;
    private Button selectButton;
    private Button exportButton;

    private MeshData currentMesh;
    private Bitmap currentTexture;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        FrameLayout container = findViewById(R.id.viewerContainer);
        viewer = new ModelGLSurfaceView(this);
        container.addView(viewer, 0, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        progressBar = findViewById(R.id.progressBar);
        statusText = findViewById(R.id.statusText);
        emptyText = findViewById(R.id.emptyText);
        selectButton = findViewById(R.id.selectButton);
        exportButton = findViewById(R.id.exportButton);
        Button resetButton = findViewById(R.id.resetButton);

        selectButton.setOnClickListener(view -> chooseImage());
        resetButton.setOnClickListener(view -> viewer.resetView());
        exportButton.setOnClickListener(view -> exportCurrentModel());
    }

    private void chooseImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_IMAGE);
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            @Nullable Intent data
    ) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMAGE
                || resultCode != RESULT_OK
                || data == null
                || data.getData() == null) {
            return;
        }

        Uri imageUri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(
                    imageUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            );
        } catch (SecurityException ignored) {
            // Certains fournisseurs n'autorisent pas la permission persistante.
        }
        generateModel(imageUri);
    }

    private void generateModel(Uri imageUri) {
        setBusy(true, R.string.status_loading);
        exportButton.setEnabled(false);

        worker.execute(() -> {
            Bitmap source = null;
            try {
                source = BitmapUtils.decodeBitmapFromUri(
                        getContentResolver(),
                        imageUri,
                        MAX_INPUT_SIDE
                );

                if (generator == null) {
                    runOnUiThread(() -> statusText.setText(
                            R.string.status_loading_neural_engine
                    ));
                    generator = new NeuralReconstructionEngine(
                            getApplicationContext()
                    );
                }

                runOnUiThread(() -> statusText.setText(
                        R.string.status_generating_neural
                ));
                NeuralReconstructionEngine.Result result =
                        generator.generate(source);

                currentMesh = result.getMesh();
                currentTexture = result.getTexture();
                runOnUiThread(() -> {
                    viewer.setModel(currentMesh, currentTexture);
                    emptyText.setVisibility(View.GONE);
                    setBusy(false, R.string.status_done);
                    statusText.setText(getString(
                            R.string.status_done_details,
                            result.getDetectedViewCount(),
                            result.getQualityLabel(),
                            result.getProcessorCount(),
                            currentMesh.getTriangleCount(),
                            result.getNeuralBackend(),
                            result.getNeuralDurationMs() / 1000.0,
                            result.getTotalDurationMs() / 1000.0
                    ));
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setBusy(false, R.string.error_generation);
                    Toast.makeText(
                            this,
                            getString(R.string.error_generation)
                                    + " " + safeMessage(error),
                            Toast.LENGTH_LONG
                    ).show();
                });
            } finally {
                if (source != null
                        && source != currentTexture
                        && !source.isRecycled()) {
                    source.recycle();
                }
            }
        });
    }

    private void exportCurrentModel() {
        MeshData mesh = currentMesh;
        Bitmap texture = currentTexture;
        if (mesh == null || texture == null) {
            return;
        }

        setBusy(true, R.string.status_exporting);
        worker.execute(() -> {
            try {
                ObjExporter.ExportResult result = ObjExporter.export(
                        this,
                        mesh,
                        texture
                );
                runOnUiThread(() -> {
                    setBusy(false, R.string.status_exported);
                    shareFiles(result);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setBusy(false, R.string.error_export);
                    Toast.makeText(
                            this,
                            getString(R.string.error_export)
                                    + " " + safeMessage(error),
                            Toast.LENGTH_LONG
                    ).show();
                });
            }
        });
    }

    private void shareFiles(ObjExporter.ExportResult result) {
        ArrayList<Uri> uris = new ArrayList<>();
        String authority = getPackageName() + ".fileprovider";
        for (File file : result.getFiles()) {
            uris.add(FileProvider.getUriForFile(this, authority, file));
        }
        if (uris.isEmpty()) {
            return;
        }

        Intent share = new Intent(Intent.ACTION_SEND_MULTIPLE);
        share.setType("application/octet-stream");
        share.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        share.putExtra(Intent.EXTRA_SUBJECT, "Modèle 3D V4 neuronal — GLB + OBJ");
        share.putExtra(
                Intent.EXTRA_TEXT,
                "GLB V4 neuronal et fichiers OBJ créés dans : "
                        + result.getDirectory().getAbsolutePath()
        );
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        ClipData clipData = ClipData.newRawUri(
                "Modèle 3D V4 neuronal",
                uris.get(0)
        );
        for (int index = 1; index < uris.size(); index++) {
            clipData.addItem(new ClipData.Item(uris.get(index)));
        }
        share.setClipData(clipData);
        startActivity(Intent.createChooser(
                share,
                getString(R.string.share_model)
        ));
    }

    private void setBusy(boolean busy, int messageRes) {
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        selectButton.setEnabled(!busy);
        exportButton.setEnabled(!busy && currentMesh != null);
        statusText.setText(messageRes);
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? ""
                : "(" + message + ")";
    }

    @Override
    protected void onResume() {
        super.onResume();
        viewer.onResume();
    }

    @Override
    protected void onPause() {
        viewer.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        if (generator != null) {
            generator.close();
        }
        super.onDestroy();
    }
}
