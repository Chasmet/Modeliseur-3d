package com.chasmet.modeliseur3d;

import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
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
import com.chasmet.modeliseur3d.model.AnimeSegmentationEngine;
import com.chasmet.modeliseur3d.model.ManualViewPreprocessor;
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

/**
 * Reconstruction 3D V5.4 : huit photos guidées par silhouettes, validées et
 * normalisées avant d'être transmises au moteur de maillage.
 */
public final class Manual3DActivity extends AppCompatActivity {
    private static final String TAG = "Modeliseur3DGuideV54";
    private static final int REQUEST_VIEW_BASE = 5400;
    private static final int MAXIMUM_DECODE_SIDE = 1280;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Uri[] sourceUris = new Uri[ManualViewPlan.VIEW_COUNT];
    private final ManualViewPreprocessor.Result[] preparedResults =
            new ManualViewPreprocessor.Result[ManualViewPlan.VIEW_COUNT];
    private final GuidedPhotoSlotView[] slotViews =
            new GuidedPhotoSlotView[ManualViewPlan.VIEW_COUNT];
    private final ManualViewPreprocessor preprocessor =
            new ManualViewPreprocessor();

    private DevicePerformanceProfile performanceProfile;
    private ProcessingPowerLock processingPowerLock;
    private AnimeSegmentationEngine validationSegmentation;
    private VideoReconstructionEngineV48 reconstructionEngine;
    private ModelGLSurfaceViewV52 viewer;
    private ScrollView capturePanel;
    private View viewerPanel;
    private ProgressBar progressBar;
    private TextView statusText;
    private TextView selectionCounter;
    private TextView sequenceQualityText;
    private TextView emptyViewerText;
    private Button generateButton;
    private Button clearButton;
    private Button editButton;
    private Button resetButton;
    private Button rotationButton;
    private Button exportButton;

    private ManualViewSequenceValidator.Result sequenceValidation;
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
        sequenceQualityText = findViewById(R.id.sequenceQualityText);
        emptyViewerText = findViewById(R.id.manualEmptyViewerText);
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
            GuidedPhotoSlotView slot = new GuidedPhotoSlotView(this, index);
            slot.setContentDescription(getString(
                    R.string.manual_slot_content_description,
                    ManualViewPlan.getSlotLabel(index)
            ));
            slot.setOnClickListener(view -> chooseImage(slotIndex));
            slot.setOnLongClickListener(view -> {
                clearView(slotIndex);
                return true;
            });
            GridLayout.LayoutParams parameters = new GridLayout.LayoutParams();
            parameters.width = 0;
            parameters.height = dp(258);
            parameters.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1.0f);
            parameters.setMargins(margin, margin, margin, margin);
            slot.setLayoutParams(parameters);
            slotViews[index] = slot;
            grid.addView(slot);
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
        persistPermission(uri);
        validateSelectedImage(viewIndex, uri);
    }

    private void persistPermission(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            );
        } catch (SecurityException ignored) {
            // Certains fournisseurs donnent seulement une permission temporaire.
        }
    }

    private void validateSelectedImage(int viewIndex, Uri uri) {
        setBusy(true, getString(
                R.string.manual_status_analyzing_view,
                ManualViewPlan.getName(viewIndex)
        ));
        slotViews[viewIndex].showAnalyzing();

        worker.execute(() -> {
            ProcessingPowerLock.favorCurrentThread();
            Bitmap source = null;
            ManualViewPreprocessor.Result result = null;
            try {
                source = BitmapUtils.decodeBitmapFromUri(
                        getContentResolver(),
                        uri,
                        Math.min(
                                MAXIMUM_DECODE_SIDE,
                                performanceProfile.getMaximumInputSide()
                        )
                );
                ensureValidationSegmentation();
                result = preprocessor.process(
                        source,
                        validationSegmentation,
                        viewIndex
                );
                ManualViewPreprocessor.Result finalResult = result;
                result = null;
                runOnUiThread(() -> acceptValidationResult(
                        viewIndex,
                        uri,
                        finalResult
                ));
            } catch (Exception | OutOfMemoryError error) {
                if (result != null) {
                    result.close();
                }
                handleValidationFailure(viewIndex, error);
            } finally {
                if (source != null && !source.isRecycled()) {
                    source.recycle();
                }
            }
        });
    }

    private void ensureValidationSegmentation() throws Exception {
        if (validationSegmentation == null) {
            validationSegmentation = new AnimeSegmentationEngine(
                    getApplicationContext(),
                    performanceProfile.getNeuralThreadCount()
            );
        }
    }

    private void acceptValidationResult(
            int viewIndex,
            Uri uri,
            ManualViewPreprocessor.Result result
    ) {
        releasePreparedResult(viewIndex);
        sourceUris[viewIndex] = uri;
        preparedResults[viewIndex] = result;
        setBusy(false, result.getMessage());
        updateSelectionState();
        statusText.setText(getString(
                result.isAccepted()
                        ? R.string.manual_status_view_valid
                        : R.string.manual_status_view_invalid,
                ManualViewPlan.getName(viewIndex),
                result.getScore(),
                result.getMessage()
        ));
        if (!result.isAccepted()) {
            Toast.makeText(
                    this,
                    getString(
                            R.string.manual_toast_replace_view,
                            ManualViewPlan.getName(viewIndex),
                            result.getMessage()
                    ),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void handleValidationFailure(int viewIndex, Throwable error) {
        Log.e(TAG, "Échec de validation de la vue " + viewIndex, error);
        String message = getString(
                R.string.manual_error_validation,
                ManualViewPlan.getName(viewIndex)
        ) + " " + safeMessage(error);
        runOnUiThread(() -> {
            setBusy(false, message);
            updateSelectionState();
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
    }

    private void clearView(int index) {
        if (busy) {
            return;
        }
        sourceUris[index] = null;
        releasePreparedResult(index);
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
        for (int index = 0; index < ManualViewPlan.VIEW_COUNT; index++) {
            sourceUris[index] = null;
            releasePreparedResult(index);
        }
        closeValidationSegmentation();
        updateSelectionState();
        statusText.setText(R.string.manual_status_ready_short);
    }

    private void releasePreparedResult(int index) {
        GuidedPhotoSlotView slot = slotViews[index];
        if (slot != null) {
            slot.clearPreviewReference();
        }
        ManualViewPreprocessor.Result previous = preparedResults[index];
        preparedResults[index] = null;
        if (previous != null) {
            previous.close();
        }
    }

    private void updateSelectionState() {
        int chosen = 0;
        int valid = 0;
        float[] aspects = new float[ManualViewPlan.VIEW_COUNT];
        boolean[] accepted = new boolean[ManualViewPlan.VIEW_COUNT];

        for (int index = 0; index < ManualViewPlan.VIEW_COUNT; index++) {
            ManualViewPreprocessor.Result result = preparedResults[index];
            if (result == null) {
                slotViews[index].showEmpty();
                aspects[index] = Float.NaN;
                continue;
            }
            chosen++;
            if (result.isAccepted()) {
                valid++;
            }
            accepted[index] = result.isAccepted();
            aspects[index] = result.getSilhouetteAspect();
            slotViews[index].showResult(
                    result.getNormalizedBitmap(),
                    result.isAccepted(),
                    result.getScore(),
                    result.getMessage()
            );
        }

        selectionCounter.setText(getString(
                R.string.manual_selection_counter,
                chosen,
                valid,
                ManualViewPlan.VIEW_COUNT
        ));

        sequenceValidation = null;
        boolean allIndividuallyValid = valid == ManualViewPlan.VIEW_COUNT;
        if (allIndividuallyValid) {
            sequenceValidation = ManualViewSequenceValidator.validate(
                    aspects,
                    accepted
            );
            if (sequenceValidation.isValid()) {
                sequenceQualityText.setText(
                        R.string.manual_sequence_valid
                );
                sequenceQualityText.setTextColor(Color.rgb(42, 145, 78));
            } else {
                sequenceQualityText.setText(sequenceValidation.getMessage());
                sequenceQualityText.setTextColor(Color.rgb(196, 55, 55));
                int failing = sequenceValidation.getFailingIndex();
                if (failing >= 0) {
                    slotViews[failing].showSequenceWarning(
                            sequenceValidation.getMessage()
                    );
                }
            }
        } else {
            sequenceQualityText.setText(getString(
                    R.string.manual_sequence_progress,
                    valid,
                    ManualViewPlan.VIEW_COUNT
            ));
            sequenceQualityText.setTextColor(getColorCompat(R.color.text_secondary));
        }

        generateButton.setEnabled(
                !busy
                        && allIndividuallyValid
                        && sequenceValidation != null
                        && sequenceValidation.isValid()
        );
    }

    private int firstProblemIndex() {
        for (int index = 0; index < ManualViewPlan.VIEW_COUNT; index++) {
            ManualViewPreprocessor.Result result = preparedResults[index];
            if (result == null || !result.isAccepted()) {
                return index;
            }
        }
        if (sequenceValidation == null) {
            updateSelectionState();
        }
        if (sequenceValidation != null && !sequenceValidation.isValid()) {
            return sequenceValidation.getFailingIndex();
        }
        return -1;
    }

    private void generateManualModel() {
        updateSelectionState();
        int problem = firstProblemIndex();
        if (problem >= 0) {
            String message;
            ManualViewPreprocessor.Result result = preparedResults[problem];
            if (result == null) {
                message = getString(
                        R.string.manual_error_missing_view,
                        ManualViewPlan.getSlotLabel(problem)
                );
            } else if (!result.isAccepted()) {
                message = getString(
                        R.string.manual_error_invalid_view,
                        ManualViewPlan.getSlotLabel(problem),
                        result.getMessage()
                );
            } else {
                message = sequenceValidation != null
                        ? sequenceValidation.getMessage()
                        : getString(R.string.manual_error_sequence);
            }
            statusText.setText(message);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            slotViews[problem].requestFocus();
            return;
        }

        setBusy(true, getString(R.string.manual_status_preparing_v54));
        viewer.stopAutoRotation();
        rotationButton.setText(R.string.rotation_start);

        worker.execute(() -> {
            ProcessingPowerLock.favorCurrentThread();
            ArrayList<Bitmap> views = new ArrayList<>(ManualViewPlan.VIEW_COUNT);
            try {
                closeValidationSegmentation();
                for (int index = 0; index < ManualViewPlan.VIEW_COUNT; index++) {
                    postStatus(getString(
                            R.string.manual_status_loading_normalized_view,
                            index + 1,
                            ManualViewPlan.VIEW_COUNT,
                            ManualViewPlan.getName(index)
                    ));
                    Bitmap normalized = preparedResults[index]
                            .getNormalizedBitmap();
                    Bitmap copy = normalized.copy(
                            Bitmap.Config.ARGB_8888,
                            false
                    );
                    if (copy == null) {
                        throw new IllegalStateException(
                                "Copie normalisée impossible pour "
                                        + ManualViewPlan.getName(index)
                        );
                    }
                    views.add(copy);
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
                handleFailure(error, R.string.manual_error_generation, true);
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
                postStatus(getString(
                        R.string.manual_status_segmenting_normalized,
                        current,
                        total
                ));
                break;
            case BUILDING_HULL:
                postStatus(getString(R.string.manual_status_building_hull_v54));
                break;
            case MESHING:
                postStatus(getString(R.string.manual_status_meshing_v54));
                break;
            case DEPTH:
            default:
                postStatus(getString(R.string.manual_status_texturing_v54));
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
            emptyViewerText.setVisibility(View.GONE);
            if (previousTexture != null
                    && previousTexture != currentTexture
                    && !previousTexture.isRecycled()) {
                previousTexture.recycle();
            }
            setBusy(false, getString(R.string.manual_status_done_v54));
            capturePanel.setVisibility(View.GONE);
            viewerPanel.setVisibility(View.VISIBLE);
            statusText.setText(getString(
                    R.string.manual_status_done_details,
                    getString(R.string.manual_quality_v54),
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
        updateSelectionState();
        statusText.setText(R.string.manual_status_editing_v54);
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
                handleFailure(error, R.string.manual_error_export, false);
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
        ClipData clipData = ClipData.newRawUri("Modèle 3D guidé", uris.get(0));
        for (int index = 1; index < uris.size(); index++) {
            clipData.addItem(new ClipData.Item(uris.get(index)));
        }
        share.setClipData(clipData);
        startActivity(Intent.createChooser(
                share,
                getString(R.string.manual_share_chooser)
        ));
    }

    private void handleFailure(
            Throwable error,
            int messageResource,
            boolean returnToCapture
    ) {
        Log.e(TAG, "Échec du mode 3D guidé V5.4", error);
        String message = getString(messageResource) + " " + safeMessage(error);
        Runtime.getRuntime().gc();
        runOnUiThread(() -> {
            setBusy(false, message);
            if (returnToCapture || currentMesh == null) {
                capturePanel.setVisibility(View.VISIBLE);
                viewerPanel.setVisibility(View.GONE);
            } else {
                capturePanel.setVisibility(View.GONE);
                viewerPanel.setVisibility(View.VISIBLE);
            }
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
        for (GuidedPhotoSlotView slot : slotViews) {
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
                        "guided-silhouettes-3d-v54"
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
                // Certaines surcouches refusent ce mode malgré son support annoncé.
            }
        }
    }

    private void closeValidationSegmentation() {
        AnimeSegmentationEngine engine = validationSegmentation;
        validationSegmentation = null;
        if (engine != null) {
            engine.close();
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
        if (message == null || message.isEmpty()) {
            return "(" + error.getClass().getSimpleName() + ")";
        }
        if (message.length() > 180) {
            message = message.substring(0, 177) + "…";
        }
        return "(" + error.getClass().getSimpleName() + " : " + message + ")";
    }

    private int getColorCompat(int resource) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return getColor(resource);
        }
        //noinspection deprecation
        return getResources().getColor(resource);
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
        closeValidationSegmentation();
        if (reconstructionEngine != null) {
            reconstructionEngine.close();
        }
        for (int index = 0; index < ManualViewPlan.VIEW_COUNT; index++) {
            releasePreparedResult(index);
        }
        if (currentTexture != null && !currentTexture.isRecycled()) {
            currentTexture.recycle();
        }
        super.onDestroy();
    }
}
