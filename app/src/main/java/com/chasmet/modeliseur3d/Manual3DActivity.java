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
import com.chasmet.modeliseur3d.model.FaceBackAutoViewSynthesizer;
import com.chasmet.modeliseur3d.model.MeshData;
import com.chasmet.modeliseur3d.model.ObjExporter;
import com.chasmet.modeliseur3d.model.VideoReconstructionEngineV48;
import com.chasmet.modeliseur3d.performance.DevicePerformanceProfile;
import com.chasmet.modeliseur3d.performance.ProcessingPowerLock;
import com.chasmet.modeliseur3d.util.BitmapUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** V5.5 : reconstruction 3D assistée avec seulement une face et un dos. */
public final class Manual3DActivity extends AppCompatActivity {
    private static final String TAG = "Modeliseur3DAutoV55";
    private static final int REQUEST_FRONT = 5501;
    private static final int REQUEST_BACK = 5502;
    private static final int MAXIMUM_DECODE_SIDE = 1280;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private DevicePerformanceProfile performanceProfile;
    private ProcessingPowerLock processingPowerLock;
    private FaceBackAutoViewSynthesizer synthesizer;
    private VideoReconstructionEngineV48 reconstructionEngine;
    private ModelGLSurfaceViewV52 viewer;

    private ScrollView capturePanel;
    private View viewerPanel;
    private ProgressBar progressBar;
    private TextView statusText;
    private TextView frontLabel;
    private TextView backLabel;
    private TextView depthValueText;
    private ImageView frontPreview;
    private ImageView backPreview;
    private SeekBar depthSeekBar;
    private Button generateButton;
    private Button clearButton;
    private Button editButton;
    private Button resetButton;
    private Button rotationButton;
    private Button exportButton;

    private Uri frontUri;
    private Uri backUri;
    private Bitmap frontPreviewBitmap;
    private Bitmap backPreviewBitmap;
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
        progressBar = findViewById(R.id.manualProgressBar);
        statusText = findViewById(R.id.manualStatusText);
        frontLabel = findViewById(R.id.frontLabel);
        backLabel = findViewById(R.id.backLabel);
        depthValueText = findViewById(R.id.depthValueText);
        frontPreview = findViewById(R.id.frontPreview);
        backPreview = findViewById(R.id.backPreview);
        depthSeekBar = findViewById(R.id.depthSeekBar);
        generateButton = findViewById(R.id.generate3dButton);
        clearButton = findViewById(R.id.clearViewsButton);
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
        generateButton.setOnClickListener(view -> generateAutomaticModel());
        clearButton.setOnClickListener(view -> clearInputs());
        editButton.setOnClickListener(view -> showCapturePanel());
        resetButton.setOnClickListener(view -> {
            viewer.stopAutoRotation();
            viewer.resetView();
            rotationButton.setText(R.string.rotation_start);
        });
        rotationButton.setOnClickListener(view -> toggleAutomaticRotation());
        exportButton.setOnClickListener(view -> exportCurrentModel());
        depthSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateDepthText();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        updateDepthText();
        updateInputState();
        statusText.setText("Prêt — Face + Dos vers six vues automatiques • "
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
        String type = getContentResolver().getType(uri);
        if (type != null && !type.startsWith("image/")) {
            Toast.makeText(this, "Le fichier choisi n’est pas une image.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            );
        } catch (SecurityException ignored) {
        }
        boolean front = requestCode == REQUEST_FRONT;
        if (front) {
            frontUri = uri;
        } else {
            backUri = uri;
        }
        loadPreview(front, uri);
    }

    private void loadPreview(boolean front, Uri uri) {
        setBusy(true, front ? "Lecture de la face…" : "Lecture du dos…");
        worker.execute(() -> {
            Bitmap bitmap = null;
            try {
                bitmap = BitmapUtils.decodeBitmapFromUri(
                        getContentResolver(),
                        uri,
                        720
                );
                Bitmap finalBitmap = bitmap;
                runOnUiThread(() -> {
                    replacePreview(front, finalBitmap);
                    setBusy(false, front
                            ? "Face sélectionnée. Ajoute maintenant le dos."
                            : "Dos sélectionné. Les deux images sont prêtes.");
                    updateInputState();
                });
                bitmap = null;
            } catch (Exception | OutOfMemoryError error) {
                Bitmap failed = bitmap;
                runOnUiThread(() -> {
                    recycle(failed);
                    if (front) {
                        frontUri = null;
                    } else {
                        backUri = null;
                    }
                    setBusy(false, "Impossible de lire cette image : " + safeMessage(error));
                    updateInputState();
                });
            }
        });
    }

    private void replacePreview(boolean front, Bitmap bitmap) {
        if (front) {
            recycle(frontPreviewBitmap);
            frontPreviewBitmap = bitmap;
            frontPreview.setImageBitmap(bitmap);
            frontLabel.setText("FACE ✓ — Appuyer pour remplacer");
        } else {
            recycle(backPreviewBitmap);
            backPreviewBitmap = bitmap;
            backPreview.setImageBitmap(bitmap);
            backLabel.setText("DOS ✓ — Appuyer pour remplacer");
        }
    }

    private void updateInputState() {
        generateButton.setEnabled(!busy && frontUri != null && backUri != null);
        clearButton.setEnabled(!busy && (frontUri != null || backUri != null));
    }

    private void updateDepthText() {
        int progress = depthSeekBar.getProgress();
        int percent = 80 + Math.round(progress * 0.4f);
        String label = percent < 94 ? "Fin" : (percent > 108 ? "Large" : "Normal");
        depthValueText.setText(label + " — " + percent + " %");
    }

    private float depthMultiplier() {
        return 0.80f + depthSeekBar.getProgress() * 0.004f;
    }

    private void generateAutomaticModel() {
        if (frontUri == null || backUri == null) {
            Toast.makeText(this, "Ajoute obligatoirement la face et le dos.", Toast.LENGTH_LONG).show();
            return;
        }
        setBusy(true, "Préparation des deux vraies images…");
        viewer.stopAutoRotation();
        rotationButton.setText(R.string.rotation_start);

        worker.execute(() -> {
            ProcessingPowerLock.favorCurrentThread();
            Bitmap front = null;
            Bitmap back = null;
            List<Bitmap> generatedViews = null;
            try {
                int maximumSide = Math.min(
                        MAXIMUM_DECODE_SIDE,
                        performanceProfile.getMaximumInputSide()
                );
                front = BitmapUtils.decodeBitmapFromUri(
                        getContentResolver(), frontUri, maximumSide
                );
                back = BitmapUtils.decodeBitmapFromUri(
                        getContentResolver(), backUri, maximumSide
                );
                if (synthesizer == null) {
                    synthesizer = new FaceBackAutoViewSynthesizer(
                            getApplicationContext(),
                            performanceProfile
                    );
                }
                FaceBackAutoViewSynthesizer.Result synthesized = synthesizer.synthesize(
                        front,
                        back,
                        depthMultiplier(),
                        (current, total, message) -> postStatus(message)
                );
                generatedViews = synthesized.getViews();
                postStatus("Reconstruction du volume à partir des huit vues cohérentes…");
                if (reconstructionEngine == null) {
                    reconstructionEngine = new VideoReconstructionEngineV48(
                            getApplicationContext(),
                            performanceProfile
                    );
                }
                VideoReconstructionEngineV48.Result result = reconstructionEngine.generate(
                        generatedViews,
                        FaceBackAutoViewSynthesizer.VIEW_COUNT,
                        this::postReconstructionProgress
                );
                showResult(result, synthesized.getBackend());
            } catch (Exception | OutOfMemoryError error) {
                handleFailure(error, "La génération 3D Face + Dos a échoué.", true);
            } finally {
                recycle(front);
                recycle(back);
                recycleBitmaps(generatedViews);
            }
        });
    }

    private void postReconstructionProgress(
            VideoReconstructionEngineV48.Stage stage,
            int current,
            int total
    ) {
        switch (stage) {
            case SEGMENTING:
                postStatus("Contrôle de la vue automatique " + current + "/" + total + "…");
                break;
            case BUILDING_HULL:
                postStatus("Calcul du volume 360° stable…");
                break;
            case MESHING:
                postStatus("Création du maillage fermé…");
                break;
            case DEPTH:
            default:
                postStatus("Application des textures face, dos et transitions…");
                break;
        }
    }

    private void showResult(
            VideoReconstructionEngineV48.Result result,
            String synthesisBackend
    ) {
        runOnUiThread(() -> {
            Bitmap previous = currentTexture;
            currentMesh = result.getMesh();
            currentTexture = result.getTexture();
            viewer.setModel(currentMesh, currentTexture);
            viewer.resetView();
            viewer.setVisibility(View.VISIBLE);
            if (previous != null && previous != currentTexture && !previous.isRecycled()) {
                previous.recycle();
            }
            setBusy(false, "Modèle 3D V5.5 prêt.");
            capturePanel.setVisibility(View.GONE);
            viewerPanel.setVisibility(View.VISIBLE);
            statusText.setText(
                    "V5.5 Face + Dos • 6 vues automatiques • "
                            + currentMesh.getTriangleCount() + " triangles • "
                            + currentMesh.getVertexCount() + " sommets • "
                            + String.format(java.util.Locale.FRANCE, "%.1f s", result.getTotalDurationMs() / 1000.0)
                            + " • " + synthesisBackend
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
        statusText.setText("Modifie la face, le dos ou l’épaisseur puis relance la génération.");
    }

    private void toggleAutomaticRotation() {
        boolean running = viewer.toggleAutoRotation();
        rotationButton.setText(running ? R.string.rotation_stop : R.string.rotation_start);
    }

    private void exportCurrentModel() {
        if (currentMesh == null || currentTexture == null || busy) {
            return;
        }
        setBusy(true, "Création du GLB HD et de la copie mobile 200 ko…");
        worker.execute(() -> {
            try {
                ObjExporter.ExportResult result = ObjExporter.export(
                        this,
                        currentMesh,
                        currentTexture
                );
                runOnUiThread(() -> {
                    setBusy(false, "Export GLB terminé.");
                    statusText.setText("GLB mobile : "
                            + result.getMobileSizeBytes() + " octets • "
                            + result.getMobileTriangleCount() + " triangles");
                    shareFiles(result);
                });
            } catch (Exception | OutOfMemoryError error) {
                handleFailure(error, "L’export GLB a échoué.", false);
            }
        });
    }

    private void shareFiles(ObjExporter.ExportResult result) {
        ArrayList<Uri> uris = new ArrayList<>();
        String authority = getPackageName() + ".fileprovider";
        for (File file : result.getFiles()) {
            uris.add(FileProvider.getUriForFile(this, authority, file));
        }
        Intent share = new Intent(Intent.ACTION_SEND_MULTIPLE);
        share.setType("application/octet-stream");
        share.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        share.putExtra(Intent.EXTRA_SUBJECT, "Modèle 3D V5.5 Face + Dos");
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (!uris.isEmpty()) {
            ClipData clip = ClipData.newRawUri("Modèle 3D V5.5", uris.get(0));
            for (int index = 1; index < uris.size(); index++) {
                clip.addItem(new ClipData.Item(uris.get(index)));
            }
            share.setClipData(clip);
        }
        startActivity(Intent.createChooser(share, "Partager ou enregistrer le modèle 3D"));
    }

    private void clearInputs() {
        if (busy) {
            return;
        }
        frontUri = null;
        backUri = null;
        frontPreview.setImageDrawable(null);
        backPreview.setImageDrawable(null);
        recycle(frontPreviewBitmap);
        recycle(backPreviewBitmap);
        frontPreviewBitmap = null;
        backPreviewBitmap = null;
        frontLabel.setText("FACE — Appuyer pour choisir");
        backLabel.setText("DOS — Appuyer pour choisir");
        updateInputState();
        statusText.setText("Sélectionne la face et le dos.");
    }

    private void setBusy(boolean value, String message) {
        busy = value;
        progressBar.setVisibility(value ? View.VISIBLE : View.GONE);
        generateButton.setEnabled(!value && frontUri != null && backUri != null);
        clearButton.setEnabled(!value && (frontUri != null || backUri != null));
        editButton.setEnabled(!value);
        resetButton.setEnabled(!value && currentMesh != null);
        rotationButton.setEnabled(!value && currentMesh != null);
        exportButton.setEnabled(!value && currentMesh != null);
        depthSeekBar.setEnabled(!value);
        statusText.setText(message);
        configurePerformanceMode(value);
    }

    private void configurePerformanceMode(boolean enabled) {
        if (enabled) {
            if (processingPowerLock == null) {
                processingPowerLock = ProcessingPowerLock.acquire(this, "face-back-auto-v55");
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
            }
        }
    }

    private void handleFailure(Throwable error, String prefix, boolean showCapture) {
        Log.e(TAG, prefix, error);
        String message = prefix + " " + safeMessage(error);
        runOnUiThread(() -> {
            setBusy(false, message);
            if (showCapture) {
                capturePanel.setVisibility(View.VISIBLE);
                viewerPanel.setVisibility(View.GONE);
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
    }

    private void postStatus(String message) {
        runOnUiThread(() -> statusText.setText(message));
    }

    private static void recycleBitmaps(List<Bitmap> bitmaps) {
        if (bitmaps == null) {
            return;
        }
        for (Bitmap bitmap : bitmaps) {
            recycle(bitmap);
        }
        bitmaps.clear();
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }

    private static String safeMessage(Throwable error) {
        if (error instanceof OutOfMemoryError) {
            return "Mémoire Android saturée : ferme les autres applications puis réessaie.";
        }
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return error.getClass().getSimpleName();
        }
        return message.length() > 180 ? message.substring(0, 177) + "…" : message;
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
        if (synthesizer != null) {
            synthesizer.close();
        }
        if (reconstructionEngine != null) {
            reconstructionEngine.close();
        }
        frontPreview.setImageDrawable(null);
        backPreview.setImageDrawable(null);
        recycle(frontPreviewBitmap);
        recycle(backPreviewBitmap);
        recycle(currentTexture);
        super.onDestroy();
    }
}
