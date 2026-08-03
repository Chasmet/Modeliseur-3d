package com.chasmet.modeliseur3d;

import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
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
import com.chasmet.modeliseur3d.media.VideoFrameExtractor;
import com.chasmet.modeliseur3d.media.VideoSheetComposer;
import com.chasmet.modeliseur3d.model.MeshData;
import com.chasmet.modeliseur3d.model.NeuralReconstructionEngine;
import com.chasmet.modeliseur3d.model.ObjExporter;
import com.chasmet.modeliseur3d.util.BitmapUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends AppCompatActivity {
    private static final String TAG = "Modeliseur3D";
    private static final int REQUEST_IMAGE = 2001;
    private static final int REQUEST_VIDEO = 2002;
    private static final int MAX_INPUT_SIDE = 2048;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private NeuralReconstructionEngine generator;
    private ModelGLSurfaceView viewer;
    private ProgressBar progressBar;
    private TextView statusText;
    private TextView emptyText;
    private Button selectButton;
    private Button videoButton;
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
        videoButton = findViewById(R.id.selectVideoButton);
        exportButton = findViewById(R.id.exportButton);
        Button resetButton = findViewById(R.id.resetButton);

        selectButton.setOnClickListener(view -> chooseImage());
        videoButton.setOnClickListener(view -> chooseVideo());
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

    private void chooseVideo() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_VIDEO);
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            @Nullable Intent data
    ) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK
                || data == null
                || data.getData() == null) {
            return;
        }

        Uri uri = data.getData();
        persistReadPermission(uri);
        if (requestCode == REQUEST_IMAGE) {
            generateImageModel(uri);
        } else if (requestCode == REQUEST_VIDEO) {
            generateVideoModel(uri);
        }
    }

    private void persistReadPermission(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            );
        } catch (SecurityException ignored) {
            // Certains fournisseurs ne proposent pas de permission persistante.
        }
    }

    private void generateImageModel(Uri imageUri) {
        setBusy(true, R.string.status_loading);
        worker.execute(() -> {
            Bitmap source = null;
            try {
                source = BitmapUtils.decodeBitmapFromUri(
                        getContentResolver(),
                        imageUri,
                        MAX_INPUT_SIDE
                );
                generateFromBitmap(source);
            } catch (Exception | OutOfMemoryError error) {
                handleGenerationFailure(error, R.string.error_generation);
            } finally {
                recycleSource(source);
            }
        });
    }

    private void generateVideoModel(Uri videoUri) {
        setBusy(true, R.string.status_extracting_video);
        worker.execute(() -> {
            Bitmap sheet = null;
            try (VideoFrameExtractor.Result extracted =
                         new VideoFrameExtractor(this).extract(
                                 videoUri,
                                 (current, total) -> postStatus(getString(
                                         R.string.status_extracting_video_progress,
                                         current,
                                         total
                                 ))
                         )) {
                postStatus(getString(R.string.status_building_video_sheet));
                sheet = VideoSheetComposer.compose(extracted.getFrames());
                generateFromBitmap(sheet);
            } catch (Exception | OutOfMemoryError error) {
                handleGenerationFailure(error, R.string.error_video);
            } finally {
                recycleSource(sheet);
            }
        });
    }

    private void generateFromBitmap(Bitmap source) throws Exception {
        if (generator == null) {
            postStatus(getString(R.string.status_loading_neural_engine));
            generator = new NeuralReconstructionEngine(getApplicationContext());
        }

        postStatus(getString(R.string.status_generating_neural));
        NeuralReconstructionEngine.Result result = generator.generate(source);
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
    }

    private void handleGenerationFailure(Throwable error, int messageResource) {
        Log.e(TAG, "Échec de reconstruction V4.4 locale", error);
        String details = safeMessage(error);
        Runtime.getRuntime().gc();
        runOnUiThread(() -> {
            setBusy(false, messageResource);
            String message = getString(messageResource) + " " + details;
            statusText.setText(message);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
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
                    statusText.setText(getString(
                            R.string.status_exported_details,
                            result.getMobileSizeBytes() / 1000.0,
                            result.getMobileTriangleCount()
                    ));
                    shareFiles(result);
                });
            } catch (Exception | OutOfMemoryError error) {
                Log.e(TAG, "Échec d'export V4.4", error);
                String details = safeMessage(error);
                runOnUiThread(() -> {
                    setBusy(false, R.string.error_export);
                    String message = getString(R.string.error_export)
                            + " " + details;
                    statusText.setText(message);
                    Toast.makeText(
                            this,
                            message,
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
        share.putExtra(
                Intent.EXTRA_SUBJECT,
                "Modèle 3D V4.4 local — GLB HD + GLB mobile 200 Ko"
        );
        share.putExtra(
                Intent.EXTRA_TEXT,
                "Fichiers créés localement dans : "
                        + result.getDirectory().getAbsolutePath()
                        + "\nGLB mobile vérifié : "
                        + result.getMobileSizeBytes()
                        + " octets."
        );
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        ClipData clipData = ClipData.newRawUri(
                "Modèle 3D V4.4",
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

    private void setBusy(boolean busy, int messageResource) {
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        selectButton.setEnabled(!busy);
        videoButton.setEnabled(!busy);
        exportButton.setEnabled(!busy && currentMesh != null);
        statusText.setText(messageResource);
    }

    private void postStatus(String message) {
        runOnUiThread(() -> statusText.setText(message));
    }

    private void recycleSource(Bitmap source) {
        if (source != null
                && source != currentTexture
                && !source.isRecycled()) {
            source.recycle();
        }
    }

    private static String safeMessage(Throwable error) {
        if (error instanceof OutOfMemoryError) {
            return "(mémoire du téléphone insuffisante ; ferme les autres applications puis réessaie)";
        }

        Throwable current = error;
        String message = null;
        while (current != null) {
            String candidate = current.getMessage();
            if (candidate != null && !candidate.trim().isEmpty()) {
                message = candidate.trim();
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }

        String type = error.getClass().getSimpleName();
        if (message == null || message.isEmpty()) {
            return "(" + type + ")";
        }
        if (message.length() > 180) {
            message = message.substring(0, 177) + "…";
        }
        return "(" + type + " : " + message + ")";
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
        if (currentTexture != null && !currentTexture.isRecycled()) {
            currentTexture.recycle();
        }
        super.onDestroy();
    }
}
