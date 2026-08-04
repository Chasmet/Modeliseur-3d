package com.chasmet.modeliseur3d;

import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
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
import com.chasmet.modeliseur3d.model.Fast3DGlbExporter;
import com.chasmet.modeliseur3d.model.MeshData;
import com.chasmet.modeliseur3d.model.QuickFourViewValidator;
import com.chasmet.modeliseur3d.model.StylizedCharacter3DEngine;
import com.chasmet.modeliseur3d.performance.DevicePerformanceProfile;
import com.chasmet.modeliseur3d.performance.ProcessingPowerLock;
import com.chasmet.modeliseur3d.util.BitmapUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Mode 3D V5.9.2. Le mode 2.5D reste séparé et inchangé. */
public final class Manual3DActivity extends AppCompatActivity {
    private static final int MAX_SIDE = 1600;
    private static final int QUICK_ANALYSIS_SIDE = 640;
    private static final int[] REQUESTS = {5921, 5922, 5923, 5924};
    private static final int[] CARDS = {
            R.id.frontCard,
            R.id.rightCard,
            R.id.backCard,
            R.id.leftCard
    };
    private static final int[] PREVIEWS = {
            R.id.frontPreview,
            R.id.rightPreview,
            R.id.backPreview,
            R.id.leftPreview
    };
    private static final int[] LABELS = {
            R.id.frontLabel,
            R.id.rightLabel,
            R.id.backLabel,
            R.id.leftLabel
    };
    private static final String[] NAMES = {
            "FACE",
            "PROFIL DROIT",
            "DOS",
            "PROFIL GAUCHE"
    };

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Uri[] uris = new Uri[4];
    private final ImageView[] previews = new ImageView[4];
    private final TextView[] labels = new TextView[4];

    private DevicePerformanceProfile profile;
    private ProcessingPowerLock powerLock;
    private StylizedCharacter3DEngine engine;
    private ModelGLSurfaceViewV52 viewer;
    private ScrollView capturePanel;
    private View viewerPanel;
    private TextView depthText;
    private TextView status;
    private ProgressBar progress;
    private SeekBar depth;
    private Button clear;
    private Button generate;
    private Button edit;
    private Button reset;
    private Button rotation;
    private Button export;
    private MeshData mesh;
    private Bitmap texture;
    private Fast3DGlbExporter.PreparedExport preparedExport;
    private boolean busy;
    private boolean validationReady;
    private boolean exportPreparing;
    private boolean exportRequested;
    private int validationSequence;
    private int exportGeneration;
    private String modelSummary = "";

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_manual_3d);
        profile = DevicePerformanceProfile.detect(this);
        engine = new StylizedCharacter3DEngine(this);
        capturePanel = findViewById(R.id.capturePanel);
        viewerPanel = findViewById(R.id.viewer3dPanel);
        depthText = findViewById(R.id.depthValueText);
        status = findViewById(R.id.manualStatusText);
        progress = findViewById(R.id.manualProgressBar);
        depth = findViewById(R.id.depthSeekBar);
        clear = findViewById(R.id.clearViewsButton);
        generate = findViewById(R.id.generate3dButton);
        edit = findViewById(R.id.editViewsButton);
        reset = findViewById(R.id.reset3dButton);
        rotation = findViewById(R.id.rotation3dButton);
        export = findViewById(R.id.export3dButton);
        for (int index = 0; index < 4; index++) {
            previews[index] = findViewById(PREVIEWS[index]);
            labels[index] = findViewById(LABELS[index]);
            final int slot = index;
            findViewById(CARDS[index]).setOnClickListener(view -> choose(slot));
        }
        FrameLayout container = findViewById(R.id.viewer3dContainer);
        viewer = new ModelGLSurfaceViewV52(this);
        container.addView(viewer, new FrameLayout.LayoutParams(-1, -1));
        viewer.setVisibility(View.INVISIBLE);
        clear.setOnClickListener(view -> clearViews());
        generate.setOnClickListener(view -> generate());
        edit.setOnClickListener(view -> showInputs());
        reset.setOnClickListener(view -> {
            viewer.stopAutoRotation();
            viewer.resetView();
            rotation.setText(R.string.rotation_start);
        });
        rotation.setOnClickListener(view -> rotation.setText(
                viewer.toggleAutoRotation()
                        ? R.string.rotation_stop
                        : R.string.rotation_start
        ));
        export.setOnClickListener(view -> export());
        depth.setMax(70);
        depth.setProgress(35);
        depth.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                updateDepth();
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });
        updateDepth();
        updateSelection();
    }

    private void choose(int slot) {
        if (busy) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        );
        startActivityForResult(intent, REQUESTS[slot]);
    }

    @Override
    protected void onActivityResult(
            int request,
            int result,
            @Nullable Intent data
    ) {
        super.onActivityResult(request, result, data);
        int slot = slot(request);
        if (slot < 0
                || result != RESULT_OK
                || data == null
                || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        String type = getContentResolver().getType(uri);
        if (type != null && !type.startsWith("image/")) {
            Toast.makeText(this, "Fichier image requis.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            );
        } catch (SecurityException ignored) {
            // Certains fournisseurs conservent uniquement une permission temporaire.
        }
        uris[slot] = uri;
        previews[slot].setImageURI(uri);
        labels[slot].setText(NAMES[slot] + " ✓ — à contrôler");
        validationReady = false;
        validationSequence++;
        updateSelection();
        if (selected() == 4) {
            runQuickValidation(validationSequence);
        }
    }

    private static int slot(int request) {
        for (int index = 0; index < REQUESTS.length; index++) {
            if (REQUESTS[index] == request) {
                return index;
            }
        }
        return -1;
    }

    private int selected() {
        int count = 0;
        for (Uri uri : uris) {
            if (uri != null) {
                count++;
            }
        }
        return count;
    }

    private void updateSelection() {
        int count = selected();
        generate.setEnabled(!busy && count == 4 && validationReady);
        if (!busy) {
            if (count < 4) {
                status.setText("Vues réelles : " + count + "/4");
            } else if (!validationReady) {
                status.setText("Analyse automatique des orientations…");
            } else {
                status.setText("Quatre vues contrôlées — génération prête.");
            }
        }
    }

    private void runQuickValidation(int sequence) {
        setBusy(true, "Contrôle face, dos, droite et gauche…");
        Uri[] selectedUris = uris.clone();
        worker.execute(() -> {
            List<Bitmap> images = new ArrayList<>(4);
            try {
                int side = Math.min(
                        QUICK_ANALYSIS_SIDE,
                        profile.getMaximumInputSide()
                );
                for (Uri uri : selectedUris) {
                    images.add(BitmapUtils.decodeBitmapFromUri(
                            getContentResolver(),
                            uri,
                            side
                    ));
                }
                QuickFourViewValidator.Result result =
                        QuickFourViewValidator.analyze(images);
                runOnUiThread(() -> applyValidation(sequence, result));
            } catch (Exception | OutOfMemoryError error) {
                runOnUiThread(() -> {
                    if (sequence != validationSequence) {
                        return;
                    }
                    validationReady = false;
                    setBusy(false, "Contrôle impossible : remplace la vue incorrecte.");
                    Toast.makeText(
                            this,
                            error.getMessage() == null
                                    ? "Une vue ne peut pas être analysée."
                                    : error.getMessage(),
                            Toast.LENGTH_LONG
                    ).show();
                });
            } finally {
                for (Bitmap bitmap : images) {
                    recycle(bitmap);
                }
            }
        });
    }

    private void applyValidation(
            int sequence,
            QuickFourViewValidator.Result result
    ) {
        if (sequence != validationSequence || selected() != 4) {
            return;
        }
        labels[0].setText(result.hasFaceBackWarning()
                ? "FACE ⚠ à vérifier"
                : "FACE ✓");
        labels[2].setText(result.hasFaceBackWarning()
                ? "DOS ⚠ à vérifier"
                : "DOS ✓");
        labels[1].setText(result.hasProfileWarning()
                ? "PROFIL DROIT ⚠"
                : "PROFIL DROIT ✓");
        if (result.hasMirrorCorrection()) {
            labels[3].setText("PROFIL GAUCHE ⚠ MIROIR AUTO");
        } else {
            labels[3].setText(result.hasProfileWarning()
                    ? "PROFIL GAUCHE ⚠"
                    : "PROFIL GAUCHE ✓");
        }
        validationReady = true;
        setBusy(false, result.getMessage() + " Cohérence "
                + Math.round(result.getCoherence() * 100.0) + " %.");
    }

    private void clearViews() {
        if (busy) {
            return;
        }
        validationSequence++;
        validationReady = false;
        for (int index = 0; index < 4; index++) {
            uris[index] = null;
            previews[index].setImageDrawable(null);
            labels[index].setText(NAMES[index]);
        }
        updateSelection();
    }

    private float depthMultiplier() {
        return 0.65f + depth.getProgress() / 100f;
    }

    private void updateDepth() {
        int percent = Math.round(depthMultiplier() * 100f);
        depthText.setText((percent < 90
                ? "Fin"
                : percent <= 112 ? "Normal" : "Large")
                + " — " + percent + " %");
    }

    private void generate() {
        if (selected() != 4 || !validationReady) {
            Toast.makeText(
                    this,
                    "Attends la validation automatique des quatre vues.",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }
        invalidatePreparedExport();
        setBusy(true, "Lecture des quatre vues…");
        viewer.stopAutoRotation();
        Uri[] selectedUris = uris.clone();
        float depthValue = depthMultiplier();
        worker.execute(() -> {
            ProcessingPowerLock.favorCurrentThread();
            List<Bitmap> images = new ArrayList<>(4);
            try {
                int side = Math.min(MAX_SIDE, profile.getMaximumInputSide());
                for (Uri uri : selectedUris) {
                    images.add(BitmapUtils.decodeBitmapFromUri(
                            getContentResolver(),
                            uri,
                            side
                    ));
                }
                StylizedCharacter3DEngine.Result result = engine.generate(
                        images,
                        depthValue,
                        this::engineProgress
                );
                showResult(result, depthValue);
            } catch (Exception | OutOfMemoryError error) {
                fail("Reconstruction 3D impossible", error);
            } finally {
                for (Bitmap bitmap : images) {
                    recycle(bitmap);
                }
            }
        });
    }

    private void engineProgress(
            StylizedCharacter3DEngine.Stage stage,
            int current,
            int total
    ) {
        String text;
        switch (stage) {
            case SEGMENTING:
                text = "Détourage " + current + "/" + total + "…";
                break;
            case ANALYSING:
                text = "Auto-correction des profils et des proportions…";
                break;
            case CLEANING:
                text = "Séparation des membres et accessoires…";
                break;
            case BUILDING_HULL:
                text = "Construction du volume adaptatif…";
                break;
            default:
                text = "Création du maillage et des textures…";
                break;
        }
        runOnUiThread(() -> status.setText(text));
    }

    private void showResult(
            StylizedCharacter3DEngine.Result result,
            float depthValue
    ) {
        runOnUiThread(() -> {
            Bitmap old = texture;
            mesh = result.getMesh();
            texture = result.getTexture();
            viewer.setModel(mesh, texture);
            viewer.resetView();
            viewer.setVisibility(View.VISIBLE);
            if (old != null && old != texture && !old.isRecycled()) {
                old.recycle();
            }
            capturePanel.setVisibility(View.GONE);
            viewerPanel.setVisibility(View.VISIBLE);
            modelSummary = result.getQualityLabel()
                    + " • " + mesh.getTriangleCount() + " triangles"
                    + " • " + result.getCorrectionSummary()
                    + " • cohérence " + Math.round(result.getCoherence() * 100.0) + "%"
                    + " • profondeur " + result.getDepthResolution() + " voxels / "
                    + Math.round(depthValue * 100f) + "%"
                    + " • " + String.format(
                            Locale.FRANCE,
                            "%.1f s",
                            result.getTotalDurationMs() / 1000.0
                    );
            setBusy(false, modelSummary);
            startExportPreparation(mesh, texture);
        });
    }

    private void startExportPreparation(MeshData meshSnapshot, Bitmap textureSnapshot) {
        int generation = ++exportGeneration;
        preparedExport = null;
        exportPreparing = true;
        exportRequested = false;
        export.setText("GLB qualité en préparation…");
        status.setText(modelSummary
                + " • préparation du GLB qualité sans simplification…");
        worker.execute(() -> {
            try {
                ProcessingPowerLock.favorCurrentThread();
                Fast3DGlbExporter.PreparedExport result = Fast3DGlbExporter.prepare(
                        this,
                        meshSnapshot,
                        textureSnapshot,
                        (stage, current, total) -> runOnUiThread(() -> {
                            if (generation != exportGeneration) {
                                return;
                            }
                            String action = stage == Fast3DGlbExporter.Stage.SIMPLIFYING
                                    ? "vérification du maillage complet"
                                    : "encodage du GLB qualité";
                            status.setText(modelSummary + " • " + action + "…");
                        })
                );
                runOnUiThread(() -> completeExportPreparation(generation, result));
            } catch (Exception | OutOfMemoryError error) {
                runOnUiThread(() -> failExportPreparation(generation, error));
            }
        });
    }

    private void completeExportPreparation(
            int generation,
            Fast3DGlbExporter.PreparedExport result
    ) {
        if (generation != exportGeneration) {
            return;
        }
        preparedExport = result;
        exportPreparing = false;
        export.setText("Exporter GLB qualité ✓");
        status.setText(modelSummary
                + " • GLB qualité prêt : " + formatFileSize(result.getSizeBytes())
                + " • " + result.getTriangleCount() + " triangles conservés"
                + " • texture " + result.getTextureMaximumSide() + " px"
                + " • préparé en " + String.format(
                        Locale.FRANCE,
                        "%.1f s",
                        result.getDurationMs() / 1000.0
                ));
        if (exportRequested) {
            exportRequested = false;
            sharePreparedExport(result);
        }
    }

    private void failExportPreparation(int generation, Throwable error) {
        if (generation != exportGeneration) {
            return;
        }
        exportPreparing = false;
        exportRequested = false;
        preparedExport = null;
        export.setText("Réessayer GLB qualité");
        String detail = error.getMessage() == null
                ? "erreur inconnue"
                : error.getMessage();
        status.setText(modelSummary + " • préparation GLB impossible : " + detail);
        Toast.makeText(
                this,
                "Export GLB impossible : " + detail,
                Toast.LENGTH_LONG
        ).show();
    }

    private void invalidatePreparedExport() {
        exportGeneration++;
        preparedExport = null;
        exportPreparing = false;
        exportRequested = false;
        if (export != null) {
            export.setText("Exporter GLB qualité");
        }
    }

    private void showInputs() {
        if (busy) {
            return;
        }
        viewer.stopAutoRotation();
        viewerPanel.setVisibility(View.GONE);
        capturePanel.setVisibility(View.VISIBLE);
        updateSelection();
    }

    private void export() {
        if (mesh == null || texture == null || busy) {
            return;
        }
        if (preparedExport != null) {
            sharePreparedExport(preparedExport);
            return;
        }
        if (exportPreparing) {
            exportRequested = true;
            export.setText("Partage qualité dès que prêt…");
            status.setText(modelSummary
                    + " • le GLB qualité est encore en préparation et s'ouvrira automatiquement.");
            return;
        }
        startExportPreparation(mesh, texture);
        exportRequested = true;
        export.setText("Partage qualité dès que prêt…");
    }

    private void sharePreparedExport(Fast3DGlbExporter.PreparedExport result) {
        File file = result.getFile();
        if (file == null || !file.isFile()) {
            failExportPreparation(exportGeneration,
                    new IllegalStateException("Fichier GLB absent"));
            return;
        }
        Uri uri = FileProvider.getUriForFile(
                this,
                getPackageName() + ".fileprovider",
                file
        );
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("model/gltf-binary");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.putExtra(Intent.EXTRA_SUBJECT, "Personnage 3D V5.9.2 qualité");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri("GLB qualité", uri));
        status.setText(modelSummary + " • partage GLB qualité instantané : "
                + formatFileSize(result.getSizeBytes()) + ".");
        startActivity(Intent.createChooser(intent, "Enregistrer le GLB qualité"));
    }

    private static String formatFileSize(long bytes) {
        if (bytes >= 1_000_000L) {
            return String.format(Locale.FRANCE, "%.2f Mo", bytes / 1_000_000.0);
        }
        return String.format(Locale.FRANCE, "%.0f ko", bytes / 1000.0);
    }

    private void fail(String prefix, Throwable error) {
        String detail = error instanceof OutOfMemoryError
                ? "mémoire saturée"
                : error.getMessage();
        String message = prefix + (detail == null ? "" : " : " + detail);
        runOnUiThread(() -> {
            setBusy(false, message);
            capturePanel.setVisibility(View.VISIBLE);
            viewerPanel.setVisibility(View.GONE);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
    }

    private void setBusy(boolean value, String text) {
        busy = value;
        progress.setVisibility(value ? View.VISIBLE : View.GONE);
        clear.setEnabled(!value);
        generate.setEnabled(!value && selected() == 4 && validationReady);
        edit.setEnabled(!value);
        reset.setEnabled(!value && mesh != null);
        rotation.setEnabled(!value && mesh != null);
        export.setEnabled(!value && mesh != null);
        depth.setEnabled(!value);
        for (int id : CARDS) {
            findViewById(id).setEnabled(!value);
        }
        status.setText(text);
        if (value) {
            if (powerLock == null) {
                powerLock = ProcessingPowerLock.acquire(this, "stylized-3d-v592");
            }
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            if (powerLock != null) {
                powerLock.close();
                powerLock = null;
            }
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
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
        validationSequence++;
        exportGeneration++;
        worker.shutdownNow();
        setBusy(false, "");
        engine.close();
        recycle(texture);
        super.onDestroy();
    }
}
