package com.chasmet.modeliseur3d;

import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.chasmet.modeliseur3d.gl.ModelGLSurfaceViewV52;
import com.chasmet.modeliseur3d.model.FaceBack25DEngine;
import com.chasmet.modeliseur3d.model.MeshData;
import com.chasmet.modeliseur3d.model.MeshDepthScaler;
import com.chasmet.modeliseur3d.model.ObjExporter;
import com.chasmet.modeliseur3d.model.Relief25DEngine;
import com.chasmet.modeliseur3d.performance.DevicePerformanceProfile;
import com.chasmet.modeliseur3d.performance.ProcessingPowerLock;
import com.chasmet.modeliseur3d.util.BitmapUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * V5.6 : volume Face + Dos stable.
 *
 * Le moteur n'invente plus six vues et n'utilise plus de texture cylindrique.
 * Il conserve les deux textures réelles, crée des côtés depuis leurs bords et
 * ajuste seulement la profondeur du maillage déjà stable du mode Face/Dos.
 */
public final class Manual3DActivity extends AppCompatActivity {
    private static final String TAG = "ModeliseurVolumeV56";
    private static final int REQUEST_FRONT = 5601;
    private static final int REQUEST_BACK = 5602;
    private static final int MAXIMUM_DECODE_SIDE = 1600;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private DevicePerformanceProfile performanceProfile;
    private ProcessingPowerLock processingPowerLock;
    private FaceBack25DEngine faceBackEngine;
    private ModelGLSurfaceViewV52 viewer;

    private ScrollView capturePanel;
    private View viewerPanel;
    private ImageView frontPreview;
    private ImageView backPreview;
    private TextView frontLabel;
    private TextView backLabel;
    private TextView depthValueText;
    private TextView statusText;
    private ProgressBar progressBar;
    private SeekBar depthSeekBar;
    private Button clearButton;
    private Button generateButton;
    private Button editButton;
    private Button resetButton;
    private Button rotationButton;
    private Button exportButton;

    private Uri frontUri;
    private Uri backUri;
    private MeshData currentMesh;
    private Bitmap currentTexture;
    private boolean busy;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_manual_3d);

        performanceProfile = DevicePerformanceProfile.detect(this);
        capturePanel = findViewById(R.id.capturePanel);
        viewerPanel = findViewById(R.id.viewer3dPanel);
        frontPreview = findViewById(R.id.frontPreview);
        backPreview = findViewById(R.id.backPreview);
        frontLabel = findViewById(R.id.frontLabel);
        backLabel = findViewById(R.id.backLabel);
        depthValueText = findViewById(R.id.depthValueText);
        statusText = findViewById(R.id.manualStatusText);
        progressBar = findViewById(R.id.manualProgressBar);
        depthSeekBar = findViewById(R.id.depthSeekBar);
        clearButton = findViewById(R.id.clearViewsButton);
        generateButton = findViewById(R.id.generate3dButton);
        editButton = findViewById(R.id.editViewsButton);
        resetButton = findViewById(R.id.reset3dButton);
        rotationButton = findViewById(R.id.rotation3dButton);
        exportButton = findViewById(R.id.export3dButton);

        FrameLayout viewerContainer = findViewById(R.id.viewer3dContainer);
        viewer = new ModelGLSurfaceViewV52(this);
        viewerContainer.addView(viewer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        viewer.setVisibility(View.INVISIBLE);

        findViewById(R.id.frontCard).setOnClickListener(view -> chooseImage(true));
        findViewById(R.id.backCard).setOnClickListener(view -> chooseImage(false));
        clearButton.setOnClickListener(view -> clearImages());
        generateButton.setOnClickListener(view -> generateStableVolume());
        editButton.setOnClickListener(view -> showCapturePanel());
        resetButton.setOnClickListener(view -> {
            viewer.stopAutoRotation();
            viewer.resetView();
            rotationButton.setText(R.string.rotation_start);
        });
        rotationButton.setOnClickListener(view -> toggleRotation());
        exportButton.setOnClickListener(view -> exportCurrentModel());

        depthSeekBar.setMax(150);
        depthSeekBar.setProgress(50);
        depthSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateDepthLabel();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        updateDepthLabel();
        updateSelectionState();
        statusText.setText("V5.6 prête — volume Face + Dos stable • "
                + performanceProfile.describe());
    }

    private void chooseImage(boolean front) {
        if (busy) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, front ? REQUEST_FRONT : REQUEST_BACK);
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            @Nullable Intent data
    ) {
        super.onActivityResult(requestCode, resultCode, data);
        if ((requestCode != REQUEST_FRONT && requestCode != REQUEST_BACK)
                || resultCode != RESULT_OK
                || data == null
                || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        String mimeType = getContentResolver().getType(uri);
        if (mimeType != null && !mimeType.startsWith("image/")) {
            Toast.makeText(this, "Le fichier choisi n’est pas une image.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            );
        } catch (SecurityException ignored) {
            // Une permission temporaire reste suffisante avec certains fournisseurs.
        }

        if (requestCode == REQUEST_FRONT) {
            frontUri = uri;
            frontPreview.setImageURI(uri);
            frontLabel.setText("FACE ✓ — Appuyer pour remplacer");
        } else {
            backUri = uri;
            backPreview.setImageURI(uri);
            backLabel.setText("DOS ✓ — Appuyer pour remplacer");
        }
        updateSelectionState();
    }

    private void updateSelectionState() {
        boolean complete = frontUri != null && backUri != null;
        generateButton.setEnabled(!busy && complete);
        if (!busy) {
            if (complete) {
                statusText.setText("Face et dos prêts. Le moteur gardera leurs textures réelles.");
            } else if (frontUri != null) {
                statusText.setText("Face sélectionnée. Ajoute maintenant le dos.");
            } else if (backUri != null) {
                statusText.setText("Dos sélectionné. Ajoute maintenant la face.");
            } else {
                statusText.setText("Sélectionne une image de face et une image de dos.");
            }
        }
    }

    private void clearImages() {
        if (busy) {
            return;
        }
        frontUri = null;
        backUri = null;
        frontPreview.setImageDrawable(null);
        backPreview.setImageDrawable(null);
        frontLabel.setText("FACE — Appuyer pour choisir");
        backLabel.setText("DOS — Appuyer pour choisir");
        updateSelectionState();
    }

    private float getDepthMultiplier() {
        return 0.50f + depthSeekBar.getProgress() / 100.0f;
    }

    private void updateDepthLabel() {
        int percent = Math.round(getDepthMultiplier() * 100.0f);
        String label;
        if (percent < 85) {
            label = "Fin";
        } else if (percent <= 125) {
            label = "Normal";
        } else {
            label = "Large";
        }
        depthValueText.setText(label + " — " + percent + " %");
    }

    private void generateStableVolume() {
        if (frontUri == null || backUri == null) {
            Toast.makeText(this, "La face et le dos sont obligatoires.", Toast.LENGTH_LONG).show();
            return;
        }
        setBusy(true, "Lecture des deux vraies images…");
        viewer.stopAutoRotation();
        rotationButton.setText(R.string.rotation_start);

        Uri selectedFront = frontUri;
        Uri selectedBack = backUri;
        float depthMultiplier = getDepthMultiplier();

        worker.execute(() -> {
            ProcessingPowerLock.favorCurrentThread();
            Bitmap front = null;
            Bitmap back = null;
            try {
                int maximumSide = Math.min(
                        MAXIMUM_DECODE_SIDE,
                        performanceProfile.getMaximumInputSide()
                );
                front = BitmapUtils.decodeBitmapFromUri(
                        getContentResolver(),
                        selectedFront,
                        maximumSide
                );
                back = BitmapUtils.decodeBitmapFromUri(
                        getContentResolver(),
                        selectedBack,
                        maximumSide
                );
                if (faceBackEngine == null) {
                    faceBackEngine = new FaceBack25DEngine(
                            getApplicationContext(),
                            performanceProfile
                    );
                }
                Relief25DEngine.Result result = faceBackEngine.generate(
                        front,
                        back,
                        this::postFaceBackProgress
                );
                MeshData scaled = MeshDepthScaler.scaleDepth(
                        result.getMesh(),
                        depthMultiplier
                );
                showResult(result, scaled, depthMultiplier);
            } catch (Exception | OutOfMemoryError error) {
                handleFailure(error, "La création du volume Face + Dos a échoué.");
            } finally {
                recycle(front);
                recycle(back);
            }
        });
    }

    private void postFaceBackProgress(
            Relief25DEngine.Stage stage,
            int current,
            int total
    ) {
        String message;
        switch (stage) {
            case SEGMENTING:
                message = "Détourage réel Face/Dos " + current + "/" + total + "…";
                break;
            case ALIGNING:
                message = "Alignement de la tête, du corps et des pieds…";
                break;
            case TEXTURING:
                message = "Conservation des textures avant, arrière et des bords…";
                break;
            case MESHING:
            default:
                message = "Création du volume fermé sans six fausses vues…";
                break;
        }
        postStatus(message);
    }

    private void showResult(
            Relief25DEngine.Result result,
            MeshData scaledMesh,
            float depthMultiplier
    ) {
        runOnUiThread(() -> {
            Bitmap previousTexture = currentTexture;
            currentMesh = scaledMesh;
            currentTexture = result.getTexture();
            viewer.setModel(currentMesh, currentTexture);
            viewer.resetView();
            viewer.setVisibility(View.VISIBLE);
            if (previousTexture != null
                    && previousTexture != currentTexture
                    && !previousTexture.isRecycled()) {
                previousTexture.recycle();
            }
            capturePanel.setVisibility(View.GONE);
            viewerPanel.setVisibility(View.VISIBLE);
            setBusy(false, "Volume Face + Dos V5.6 prêt.");
            statusText.setText(
                    "V5.6 Volume Face + Dos stable • "
                            + currentMesh.getTriangleCount() + " triangles • "
                            + currentMesh.getVertexCount() + " sommets • profondeur "
                            + Math.round(depthMultiplier * 100.0f) + "% • "
                            + result.getProcessorCount() + " cœurs • total "
                            + String.format(java.util.Locale.FRANCE, "%.1f s",
                            result.getTotalDurationMs() / 1000.0)
            );
        });
    }

    private void showCapturePanel() {
        if (busy) {
            return;
        }
        viewer.stopAutoRotation();
        rotationButton.setText(R.string.rotation_start);
        viewerPanel.setVisibility(View.GONE);
        capturePanel.setVisibility(View.VISIBLE);
        statusText.setText("Modifie la face, le dos ou la profondeur puis régénère.");
    }

    private void toggleRotation() {
        boolean running = viewer.toggleAutoRotation();
        rotationButton.setText(running
                ? R.string.rotation_stop
                : R.string.rotation_start);
    }

    private void exportCurrentModel() {
        MeshData mesh = currentMesh;
        Bitmap texture = currentTexture;
        if (mesh == null || texture == null || busy) {
            return;
        }
        setBusy(true, "Création du GLB HD et de la copie mobile 200 ko…");
        worker.execute(() -> {
            ProcessingPowerLock.favorCurrentThread();
            try {
                ObjExporter.ExportResult result = ObjExporter.export(
                        this,
                        mesh,
                        texture
                );
                runOnUiThread(() -> {
                    setBusy(false, "Export GLB terminé.");
                    statusText.setText(
                            "GLB mobile "
                                    + String.format(java.util.Locale.FRANCE, "%.3f Mo",
                                    result.getMobileSizeBytes() / 1_000_000.0)
                                    + " • " + result.getMobileTriangleCount()
                                    + " triangles • limite 200 ko vérifiée."
                    );
                    shareFiles(result);
                });
            } catch (Exception | OutOfMemoryError error) {
                handleFailure(error, "L’export GLB a échoué.");
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
        share.putExtra(Intent.EXTRA_SUBJECT, "Modèle V5.6 Face + Dos stable");
        share.putExtra(Intent.EXTRA_TEXT,
                "GLB créé localement dans : "
                        + result.getDirectory().getAbsolutePath());
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ClipData clipData = ClipData.newRawUri("Modèle V5.6", uris.get(0));
        for (int index = 1; index < uris.size(); index++) {
            clipData.addItem(new ClipData.Item(uris.get(index)));
        }
        share.setClipData(clipData);
        startActivity(Intent.createChooser(share, "Partager ou enregistrer le GLB"));
    }

    private void handleFailure(Throwable error, String prefix) {
        Log.e(TAG, prefix, error);
        String message = prefix + " " + safeMessage(error);
        Runtime.getRuntime().gc();
        runOnUiThread(() -> {
            setBusy(false, message);
            capturePanel.setVisibility(View.VISIBLE);
            viewerPanel.setVisibility(View.GONE);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
    }

    private void setBusy(boolean value, String message) {
        busy = value;
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        clearButton.setEnabled(!busy);
        generateButton.setEnabled(!busy && frontUri != null && backUri != null);
        editButton.setEnabled(!busy);
        resetButton.setEnabled(!busy && currentMesh != null);
        rotationButton.setEnabled(!busy && currentMesh != null);
        exportButton.setEnabled(!busy && currentMesh != null);
        depthSeekBar.setEnabled(!busy);
        findViewById(R.id.frontCard).setEnabled(!busy);
        findViewById(R.id.backCard).setEnabled(!busy);
        statusText.setText(message);
        configurePerformanceMode(busy);
    }

    private void configurePerformanceMode(boolean enabled) {
        if (enabled) {
            if (processingPowerLock == null) {
                processingPowerLock = ProcessingPowerLock.acquire(
                        this,
                        "face-back-stable-volume-v56"
                );
            }
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            if (processingPowerLock != null) {
                processingPowerLock.close();
                processingPowerLock = null;
            }
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                && performanceProfile != null
                && performanceProfile.isSustainedPerformanceSupported()) {
            try {
                getWindow().setSustainedPerformanceMode(enabled);
            } catch (RuntimeException ignored) {
                // Certaines surcouches refusent ce mode.
            }
        }
    }

    private void postStatus(String message) {
        runOnUiThread(() -> statusText.setText(message));
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }

    private static String safeMessage(Throwable error) {
        if (error instanceof OutOfMemoryError) {
            return "(mémoire Android saturée ; ferme les autres applications puis réessaie)";
        }
        Throwable current = error;
        String message = null;
        while (current != null) {
            if (current.getMessage() != null
                    && !current.getMessage().trim().isEmpty()) {
                message = current.getMessage().trim();
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        if (message == null) {
            return "(" + error.getClass().getSimpleName() + ")";
        }
        if (message.length() > 180) {
            message = message.substring(0, 177) + "…";
        }
        return "(" + message + ")";
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
        configurePerformanceMode(false);
        if (faceBackEngine != null) {
            faceBackEngine.close();
        }
        if (currentTexture != null && !currentTexture.isRecycled()) {
            currentTexture.recycle();
        }
        super.onDestroy();
    }
}
