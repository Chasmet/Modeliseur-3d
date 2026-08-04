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
import com.chasmet.modeliseur3d.model.MeshData;
import com.chasmet.modeliseur3d.model.ObjExporter;
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

/** Mode 3D V5.7. Le mode 2.5D reste séparé dans MainActivityV52. */
public final class Manual3DActivity extends AppCompatActivity {
    private static final int MAX_SIDE = 1600;
    private static final int[] REQUESTS = {5701, 5702, 5703, 5704};
    private static final int[] CARDS = {R.id.frontCard, R.id.rightCard, R.id.backCard, R.id.leftCard};
    private static final int[] PREVIEWS = {R.id.frontPreview, R.id.rightPreview, R.id.backPreview, R.id.leftPreview};
    private static final int[] LABELS = {R.id.frontLabel, R.id.rightLabel, R.id.backLabel, R.id.leftLabel};
    private static final String[] NAMES = {"FACE", "PROFIL DROIT", "DOS", "PROFIL GAUCHE"};

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
    private boolean busy;

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
        for (int i = 0; i < 4; i++) {
            previews[i] = findViewById(PREVIEWS[i]);
            labels[i] = findViewById(LABELS[i]);
            final int slot = i;
            findViewById(CARDS[i]).setOnClickListener(v -> choose(slot));
        }
        FrameLayout container = findViewById(R.id.viewer3dContainer);
        viewer = new ModelGLSurfaceViewV52(this);
        container.addView(viewer, new FrameLayout.LayoutParams(-1, -1));
        viewer.setVisibility(View.INVISIBLE);
        clear.setOnClickListener(v -> clearViews());
        generate.setOnClickListener(v -> generate());
        edit.setOnClickListener(v -> showInputs());
        reset.setOnClickListener(v -> { viewer.stopAutoRotation(); viewer.resetView(); rotation.setText(R.string.rotation_start); });
        rotation.setOnClickListener(v -> rotation.setText(viewer.toggleAutoRotation() ? R.string.rotation_stop : R.string.rotation_start));
        export.setOnClickListener(v -> export());
        depth.setMax(70);
        depth.setProgress(35);
        depth.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) { updateDepth(); }
            public void onStartTrackingTouch(SeekBar bar) { }
            public void onStopTrackingTouch(SeekBar bar) { }
        });
        updateDepth();
        updateSelection();
    }

    private void choose(int slot) {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUESTS[slot]);
    }

    @Override
    protected void onActivityResult(int request, int result, @Nullable Intent data) {
        super.onActivityResult(request, result, data);
        int slot = slot(request);
        if (slot < 0 || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        String type = getContentResolver().getType(uri);
        if (type != null && !type.startsWith("image/")) {
            Toast.makeText(this, "Fichier image requis.", Toast.LENGTH_LONG).show();
            return;
        }
        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (SecurityException ignored) { }
        uris[slot] = uri;
        previews[slot].setImageURI(uri);
        labels[slot].setText(NAMES[slot] + " ✓");
        updateSelection();
    }

    private static int slot(int request) {
        for (int i = 0; i < REQUESTS.length; i++) if (REQUESTS[i] == request) return i;
        return -1;
    }

    private int selected() {
        int count = 0;
        for (Uri uri : uris) if (uri != null) count++;
        return count;
    }

    private void updateSelection() {
        int count = selected();
        generate.setEnabled(!busy && count == 4);
        if (!busy) status.setText(count == 4 ? "Quatre vues prêtes." : "Vues réelles : " + count + "/4");
    }

    private void clearViews() {
        if (busy) return;
        for (int i = 0; i < 4; i++) {
            uris[i] = null;
            previews[i].setImageDrawable(null);
            labels[i].setText(NAMES[i]);
        }
        updateSelection();
    }

    private float depthMultiplier() { return 0.65f + depth.getProgress() / 100f; }

    private void updateDepth() {
        int percent = Math.round(depthMultiplier() * 100f);
        depthText.setText((percent < 90 ? "Fin" : percent <= 112 ? "Normal" : "Large") + " — " + percent + " %");
    }

    private void generate() {
        if (selected() != 4) {
            Toast.makeText(this, "Face, droite, dos et gauche sont obligatoires.", Toast.LENGTH_LONG).show();
            return;
        }
        setBusy(true, "Lecture des quatre vues…");
        viewer.stopAutoRotation();
        Uri[] selected = uris.clone();
        float depthValue = depthMultiplier();
        worker.execute(() -> {
            ProcessingPowerLock.favorCurrentThread();
            List<Bitmap> images = new ArrayList<>(4);
            try {
                int side = Math.min(MAX_SIDE, profile.getMaximumInputSide());
                for (Uri uri : selected) images.add(BitmapUtils.decodeBitmapFromUri(getContentResolver(), uri, side));
                StylizedCharacter3DEngine.Result result = engine.generate(images, depthValue, this::engineProgress);
                showResult(result, depthValue);
            } catch (Exception | OutOfMemoryError error) {
                fail("Reconstruction 3D impossible", error);
            } finally {
                for (Bitmap bitmap : images) recycle(bitmap);
            }
        });
    }

    private void engineProgress(StylizedCharacter3DEngine.Stage stage, int current, int total) {
        String text;
        switch (stage) {
            case SEGMENTING: text = "Détourage " + current + "/" + total + "…"; break;
            case CLEANING: text = "Séparation des parties…"; break;
            case BUILDING_HULL: text = "Construction du volume…"; break;
            default: text = "Création du maillage et des textures…";
        }
        runOnUiThread(() -> status.setText(text));
    }

    private void showResult(StylizedCharacter3DEngine.Result result, float depthValue) {
        runOnUiThread(() -> {
            Bitmap old = texture;
            mesh = result.getMesh();
            texture = result.getTexture();
            viewer.setModel(mesh, texture);
            viewer.resetView();
            viewer.setVisibility(View.VISIBLE);
            if (old != null && old != texture && !old.isRecycled()) old.recycle();
            capturePanel.setVisibility(View.GONE);
            viewerPanel.setVisibility(View.VISIBLE);
            setBusy(false, "Personnage 3D prêt.");
            status.setText(result.getQualityLabel() + " • " + mesh.getTriangleCount() + " triangles • "
                    + result.getAverageComponents() + " parties/vue • profondeur "
                    + Math.round(depthValue * 100f) + "% • "
                    + String.format(Locale.FRANCE, "%.1f s", result.getTotalDurationMs() / 1000.0));
        });
    }

    private void showInputs() {
        if (busy) return;
        viewer.stopAutoRotation();
        viewerPanel.setVisibility(View.GONE);
        capturePanel.setVisibility(View.VISIBLE);
        updateSelection();
    }

    private void export() {
        if (mesh == null || texture == null || busy) return;
        setBusy(true, "Export GLB…");
        worker.execute(() -> {
            try {
                ObjExporter.ExportResult result = ObjExporter.export(this, mesh, texture);
                runOnUiThread(() -> { setBusy(false, "Export terminé."); share(result); });
            } catch (Exception | OutOfMemoryError error) { fail("Export impossible", error); }
        });
    }

    private void share(ObjExporter.ExportResult result) {
        ArrayList<Uri> files = new ArrayList<>();
        String authority = getPackageName() + ".fileprovider";
        for (File file : result.getFiles()) files.add(FileProvider.getUriForFile(this, authority, file));
        if (files.isEmpty()) return;
        Intent intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
        intent.setType("application/octet-stream");
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, files);
        intent.putExtra(Intent.EXTRA_SUBJECT, "Personnage 3D V5.7");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ClipData clip = ClipData.newRawUri("GLB", files.get(0));
        for (int i = 1; i < files.size(); i++) clip.addItem(new ClipData.Item(files.get(i)));
        intent.setClipData(clip);
        startActivity(Intent.createChooser(intent, "Enregistrer le GLB"));
    }

    private void fail(String prefix, Throwable error) {
        String detail = error instanceof OutOfMemoryError ? "mémoire saturée" : error.getMessage();
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
        generate.setEnabled(!value && selected() == 4);
        edit.setEnabled(!value);
        reset.setEnabled(!value && mesh != null);
        rotation.setEnabled(!value && mesh != null);
        export.setEnabled(!value && mesh != null);
        depth.setEnabled(!value);
        for (int id : CARDS) findViewById(id).setEnabled(!value);
        status.setText(text);
        if (value) {
            if (powerLock == null) powerLock = ProcessingPowerLock.acquire(this, "stylized-3d-v57");
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            if (powerLock != null) { powerLock.close(); powerLock = null; }
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }

    @Override protected void onResume() { super.onResume(); viewer.onResume(); }
    @Override protected void onPause() { viewer.onPause(); super.onPause(); }
    @Override protected void onDestroy() {
        worker.shutdownNow();
        setBusy(false, "");
        engine.close();
        recycle(texture);
        super.onDestroy();
    }
}
