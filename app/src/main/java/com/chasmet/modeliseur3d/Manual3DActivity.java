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
import android.widget.GridLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.chasmet.modeliseur3d.gl.ModelGLSurfaceViewV52;
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

/** Reconstruction 3D locale à partir de huit images placées dans un ordre connu. */
public final class Manual3DActivity extends AppCompatActivity {
    private static final String TAG = "Modeliseur3DManuel";
    private static final int REQUEST_VIEW_BASE = 5300;
    private static final int MAXIMUM_DECODE_SIDE = 1280;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Uri[] selectedUris = new Uri[ManualViewPlan.VIEW_COUNT];
    private final Button[] slotButtons = new Button[ManualViewPlan.VIEW_COUNT];

    private DevicePerformanceProfile performanceProfile;
    private ProcessingPowerLock processingPowerLock;
    private VideoReconstructionEngineV48 reconstructionEngine;
    private ModelGLSurfaceViewV52 viewer;
    private ScrollView capturePanel;
    private View viewerPanel;
    private ProgressBar progressBar;
    private TextView statusText;
    private TextView selectionCounter;
    private Button generateButton;
    private Button clearButton;
    private Button editButton;
    private Button resetButton;
    private Button rotationButton;
    private Button exportButton;

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
        selectionCounter = findViewById(R.id.selectionCounter);
        generateButton = findViewById(R.id.generate3dButton);
        clearButton = findViewById(R.id.clearViewsButton);
        editButton = findViewById(R.id.editViewsButton);
        resetButton = findViewById(R.id.reset3dButton);
        rotationButton = findViewById(R.id.rotation3dButton);
        exportButton = findViewById(R.id.export3dButton);

        FrameLayout viewerContainer = findViewById(R.id.viewer3dContainer);
        viewer = new ModelGLSurfaceViewV52(this);
        viewerContainer.addView(viewer, 0, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        viewer.setVisibility(View.INVISIBLE);

        buildViewSlots(findViewById(R.id.manualViewGrid));
        generateButton.setOnClickListener(view -> generateManualModel());
        clearButton.setOnClickListener(view -> clearAllViews());
        editButton.setOnClickListener(view -> showCapturePanel());
        resetButton.setOnClickListener(view -> {
            viewer.stopAutoRotation();
            viewer.resetView();
            rotationButton.setText(R.string.rotation_start);
        });
        rotationButton.setOnClickListener(view -> toggleAutomaticRotation());
        exportButton.setOnClickListener(view -> exportCurrentModel());

        updateSelectionState();
        statusText.setText(getString(
                R.string.manual_status_ready,
                performanceProfile.describe()
        ));
    }

    private void buildViewSlots(GridLayout grid) {
        grid.setColumnCount(2);
        int margin = dp(5);
        for (int index = 0; index < ManualViewPlan.VIEW_COUNT; index++) {
            final int slotIndex = index;
            Button button = new Button(this);
            button.setAllCaps(false);
            button.setText(getString(
                    R.string.manual_slot_empty,
                    ManualViewPlan.getSlotLabel(index)
            ));
            button.setContentDescription(getString(
                    R.string.manual_slot_content_description,
                    ManualViewPlan.getSlotLabel(index)
            ));
            button.setOnClickListener(view -> chooseImage(slotIndex));
            button.setOnLongClickListener(view -> {
                clearView(slotIndex);
                return true;
            });
            GridLayout.LayoutParams parameters = new GridLayout.LayoutParams();
            parameters.width = 0;
            parameters.height = dp(112);
            parameters.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1.0f);
            parameters.setMargins(margin, margin, margin, margin);
            button.setLayoutParams(parameters);
            slotButtons[index] = button;
            grid.addView(button);
        }
    }

    private void chooseImage(int viewIndex) {
        if (busy) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_VIEW_BASE + viewIndex);
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            @Nullable Intent data
    ) {
        super.onActivityResult(requestCode, resultCode, data);
        int viewIndex = requestCode - REQUEST_VIEW_BASE;
        if (viewIndex < 0 || viewIndex >= ManualViewPlan.VIEW_COUNT
                || resultCode != RESULT_OK
                || data == null
                || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        String mimeType = getContentResolver().getType(uri);
        if (mimeType != null && !mimeType.startsWith("image/")) {
            Toast.makeText(this, R.string.manual_error_not_image, Toast.LENGTH_LONG).show();
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            );
        } catch (SecurityException ignored) {
            // Permission temporaire suffisante pour certains fournisseurs.
        }
        selectedUris[viewIndex] = uri;
        updateSlot(viewIndex);
        updateSelectionState();
    }

    private void updateSlot(int index) {
        boolean selected = selectedUris[index] != null;
        slotButtons[index].setText(getString(
                selected ? R.string.manual_slot_selected : R.string.manual_slot_empty,
                ManualViewPlan.getSlotLabel(index)
        ));
    }

    private void clearView(int index) {
        if (busy) {
            return;
        }
        selectedUris[index] = null;
        updateSlot(index);
        updateSelectionState();
        statusText.setText(getString(
                R.string.manual_status_slot_cleared,
                ManualViewPlan.getSlotLabel(index)
        ));
    }

    private void clearAllViews() {
        if (busy) {
            return;
        }
        for (int index = 0; index < selectedUris.length; index++) {
            selectedUris[index] = null;
            updateSlot(index);
        }
        updateSelectionState();
        statusText.setText(R.string.manual_status_ready_short);
    }

    private void updateSelectionState() {
        int selectedCount = 0;
        for (Uri uri : selectedUris) {
            if (uri != null) {
                selectedCount++;
            }
        }
        selectionCounter.setText(getString(
                R.string.manual_selection_counter,
                selectedCount,
                ManualViewPlan.VIEW_COUNT
        ));
        generateButton.setEnabled(!busy && selectedCount == ManualViewPlan.VIEW_COUNT);
    }

    private int firstMissingView() {
        boolean[] selected = new boolean[ManualViewPlan.VIEW_COUNT];
        for (int index = 0; index < selectedUris.length; index++) {
            selected[index] = selectedUris[index] != null;
        }
        return ManualViewPlan.findFirstMissing(selected);
    }

    private void generateManualModel() {
        int missing = firstMissingView();
        if (missing >= 0) {
            String message = getString(
                    R.string.manual_error_missing_view,
                    ManualViewPlan.getSlotLabel(missing)
            );
            statusText.setText(message);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            slotButtons[missing].requestFocus();
            return;
        }

        setBusy(true, getString(R.string.manual_status_preparing));
        viewer.stopAutoRotation();
        rotationButton.setText(R.string.rotation_start);

        worker.execute(() -> {
            ProcessingPowerLock.favorCurrentThread();
            ArrayList<Bitmap> views = new ArrayList<>(ManualViewPlan.VIEW_COUNT);
            try {
                int maximumSide = Math.min(
                        MAXIMUM_DECODE_SIDE,
                        performanceProfile.getMaximumInputSide()
                );
                for (int index = 0; index < ManualViewPlan.VIEW_COUNT; index++) {
                    postStatus(getString(
                            R.string.manual_status_loading_view,
                            index + 1,
                            ManualViewPlan.VIEW_COUNT,
                            ManualViewPlan.getName(index)
                    ));
                    views.add(BitmapUtils.decodeBitmapFromUri(
                            getContentResolver(),
                            selectedUris[index],
                            maximumSide
                    ));
                }
                if (reconstructionEngine == null) {
                    reconstructionEngine = new VideoReconstructionEngineV48(
                            getApplicationContext(),
                            performanceProfile
                    );
                }
                VideoReconstructionEngineV48.Result result = reconstructionEngine.generate(
                        views,
                        ManualViewPlan.VIEW_COUNT,
                        this::postReconstructionProgress
                );
                showResult(result);
            } catch (Exception | OutOfMemoryError error) {
                handleFailure(error, R.string.manual_error_generation);
            } finally {
                recycleBitmaps(views);
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
                postStatus(getString(R.string.manual_status_segmenting, current, total));
                break;
            case BUILDING_HULL:
                postStatus(getString(R.string.manual_status_building_hull));
                break;
            case MESHING:
                postStatus(getString(R.string.manual_status_meshing));
                break;
            case DEPTH:
            default:
                postStatus(getString(R.string.manual_status_texturing));
                break;
        }
    }

    private void showResult(VideoReconstructionEngineV48.Result result) {
        runOnUiThread(() -> {
            Bitmap previousTexture = currentTexture;
            currentMesh = result.getMesh();
            currentTexture = result.getTexture();
            viewer.setModel(currentMesh, currentTexture);
            viewer.resetView();
            viewer.setVisibility(View.VISIBLE);
            if (previousTexture != null
                    && previousTexture != currentTexture
                    && !previousTexture.isRecycled()) {
                previousTexture.recycle();
            }
            setBusy(false, getString(R.string.manual_status_done));
            capturePanel.setVisibility(View.GONE);
            viewerPanel.setVisibility(View.VISIBLE);
            statusText.setText(getString(
                    R.string.manual_status_done_details,
                    result.getQualityLabel(),
                    result.getProcessorCount(),
                    currentMesh.getTriangleCount(),
                    currentMesh.getVertexCount(),
                    result.getDecodedFrameCount(),
                    result.getRepairedViewCount(),
                    result.getTotalDurationMs() / 1000.0
            ));
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
        statusText.setText(R.string.manual_status_editing);
    }

    private void toggleAutomaticRotation() {
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
        setBusy(true, getString(R.string.manual_status_exporting));
        worker.execute(() -> {
            ProcessingPowerLock.favorCurrentThread();
            try {
                ObjExporter.ExportResult result = ObjExporter.export(this, mesh, texture);
                runOnUiThread(() -> {
                    setBusy(false, getString(R.string.manual_status_exported));
                    statusText.setText(getString(
                            R.string.manual_status_exported_details,
                            result.getMobileSizeBytes() / 1_000_000.0,
                            result.getMobileTriangleCount()
                    ));
                    shareFiles(result);
                });
            } catch (Exception | OutOfMemoryError error) {
                handleFailure(error, R.string.manual_error_export);
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
        share.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.manual_share_subject));
        share.putExtra(Intent.EXTRA_TEXT, getString(
                R.string.manual_share_text,
                result.getDirectory().getAbsolutePath(),
                result.getMobileSizeBytes()
        ));
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ClipData clipData = ClipData.newRawUri("Modèle 3D manuel", uris.get(0));
        for (int index = 1; index < uris.size(); index++) {
            clipData.addItem(new ClipData.Item(uris.get(index)));
        }
        share.setClipData(clipData);
        startActivity(Intent.createChooser(
                share,
                getString(R.string.manual_share_chooser)
        ));
    }

    private void handleFailure(Throwable error, int messageResource) {
        Log.e(TAG, "Échec du mode 3D manuel", error);
        String message = getString(messageResource) + " " + safeMessage(error);
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
        editButton.setEnabled(!busy);
        resetButton.setEnabled(!busy && currentMesh != null);
        rotationButton.setEnabled(!busy && currentMesh != null);
        exportButton.setEnabled(!busy && currentMesh != null);
        for (Button slot : slotButtons) {
            if (slot != null) {
                slot.setEnabled(!busy);
            }
        }
        updateSelectionState();
        statusText.setText(message);
        configurePerformanceMode(busy);
    }

    private void configurePerformanceMode(boolean enabled) {
        if (enabled) {
            if (processingPowerLock == null) {
                processingPowerLock = ProcessingPowerLock.acquire(
                        this,
                        "manual-multiview-3d-v53"
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

    private static void recycleBitmaps(List<Bitmap> bitmaps) {
        for (Bitmap bitmap : bitmaps) {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
        bitmaps.clear();
    }

    private static String safeMessage(Throwable error) {
        if (error instanceof OutOfMemoryError) {
            return "(mémoire Android saturée ; ferme les autres applications puis réessaie)";
        }
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return "(" + error.getClass().getSimpleName() + ")";
        }
        message = message.trim();
        if (message.length() > 180) {
            message = message.substring(0, 177) + "…";
        }
        return "(" + error.getClass().getSimpleName() + " : " + message + ")";
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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
        if (reconstructionEngine != null) {
            reconstructionEngine.close();
        }
        if (currentTexture != null && !currentTexture.isRecycled()) {
            currentTexture.recycle();
        }
        super.onDestroy();
    }
}
