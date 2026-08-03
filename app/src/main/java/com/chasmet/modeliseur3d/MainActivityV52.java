package com.chasmet.modeliseur3d;

import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.chasmet.modeliseur3d.gl.ModelGLSurfaceViewV52;
import com.chasmet.modeliseur3d.media.VideoFrameExtractor;
import com.chasmet.modeliseur3d.model.FaceBack25DEngine;
import com.chasmet.modeliseur3d.model.MeshData;
import com.chasmet.modeliseur3d.model.ObjExporter;
import com.chasmet.modeliseur3d.model.Relief25DEngine;
import com.chasmet.modeliseur3d.performance.DevicePerformanceProfile;
import com.chasmet.modeliseur3d.performance.ProcessingPowerLock;
import com.chasmet.modeliseur3d.util.BitmapUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Activité V5.2 utilisant le vrai moteur Face/Dos commun. */
public final class MainActivityV52 extends AppCompatActivity {
    private static final String TAG = "Modeliseur25DV52";
    private static final int REQUEST_FRONT_IMAGE = 2201;
    private static final int REQUEST_BACK_IMAGE = 2202;
    private static final int REQUEST_VIDEO = 2203;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private DevicePerformanceProfile performanceProfile;
    private ProcessingPowerLock processingPowerLock;
    private FaceBack25DEngine faceBackEngine;
    private Relief25DEngine videoEngine;
    private ModelGLSurfaceViewV52 viewer;
    private ProgressBar progressBar;
    private TextView statusText;
    private TextView emptyText;
    private Button frontButton;
    private Button backButton;
    private Button generateButton;
    private Button videoButton;
    private Button rotationButton;
    private Button exportButton;

    private Uri selectedFrontUri;
    private Uri selectedBackUri;
    private MeshData currentMesh;
    private Bitmap currentTexture;
    private boolean busy;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        performanceProfile = DevicePerformanceProfile.detect(this);
        FrameLayout container = findViewById(R.id.viewerContainer);
        viewer = new ModelGLSurfaceViewV52(this);
        container.addView(viewer, 0, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        progressBar = findViewById(R.id.progressBar);
        statusText = findViewById(R.id.statusText);
        emptyText = findViewById(R.id.emptyText);
        frontButton = findViewById(R.id.selectFrontButton);
        backButton = findViewById(R.id.selectBackButton);
        generateButton = findViewById(R.id.generatePairButton);
        videoButton = findViewById(R.id.selectVideoButton);
        rotationButton = findViewById(R.id.rotationButton);
        exportButton = findViewById(R.id.exportButton);
        Button resetButton = findViewById(R.id.resetButton);

        frontButton.setOnClickListener(view -> chooseImage(REQUEST_FRONT_IMAGE));
        backButton.setOnClickListener(view -> chooseImage(REQUEST_BACK_IMAGE));
        generateButton.setOnClickListener(view -> generateSelectedImages());
        videoButton.setOnClickListener(view -> chooseVideo());
        rotationButton.setOnClickListener(view -> toggleAutomaticRotation());
        resetButton.setOnClickListener(view -> {
            viewer.stopAutoRotation();
            viewer.resetView();
            rotationButton.setText(R.string.rotation_start);
        });
        exportButton.setOnClickListener(view -> exportCurrentModel());

        updateSelectionButtons();
        statusText.setText(getString(
                R.string.status_ready_profile,
                performanceProfile.describe()
        ));
    }

    private void chooseImage(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, requestCode);
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
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        persistReadPermission(uri);
        if (requestCode == REQUEST_FRONT_IMAGE) {
            selectedFrontUri = uri;
            updateSelectionButtons();
            statusText.setText(selectedBackUri == null
                    ? R.string.status_front_selected
                    : R.string.status_front_back_selected);
            return;
        }
        if (requestCode == REQUEST_BACK_IMAGE) {
            selectedBackUri = uri;
            updateSelectionButtons();
            statusText.setText(selectedFrontUri == null
                    ? R.string.status_back_selected_first
                    : R.string.status_front_back_selected);
            return;
        }
        if (requestCode == REQUEST_VIDEO) {
            String mimeType = getContentResolver().getType(uri);
            if (mimeType != null && !mimeType.startsWith("video/")) {
                showToast(R.string.error_not_video);
                return;
            }
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
            // Permission temporaire suffisante pour certains fournisseurs.
        }
    }

    private void updateSelectionButtons() {
        frontButton.setText(selectedFrontUri == null
                ? R.string.select_front
                : R.string.front_selected);
        backButton.setText(selectedBackUri == null
                ? R.string.select_back
                : R.string.back_selected);
        generateButton.setText(selectedBackUri == null
                ? R.string.generate_single
                : R.string.generate_front_back);
        generateButton.setEnabled(!busy && selectedFrontUri != null);
    }

    private void generateSelectedImages() {
        Uri frontUri = selectedFrontUri;
        Uri backUri = selectedBackUri;
        if (frontUri == null) {
            showToast(R.string.error_select_front);
            return;
        }
        prepareForNewGeneration();
        setBusy(true, backUri == null
                ? R.string.status_loading
                : R.string.status_loading_pair);

        worker.execute(() -> {
            ProcessingPowerLock.favorCurrentThread();
            Bitmap front = null;
            Bitmap back = null;
            try {
                front = BitmapUtils.decodeBitmapFromUri(
                        getContentResolver(),
                        frontUri,
                        performanceProfile.getMaximumInputSide()
                );
                if (backUri != null) {
                    back = BitmapUtils.decodeBitmapFromUri(
                            getContentResolver(),
                            backUri,
                            performanceProfile.getMaximumInputSide()
                    );
                }
                ensureFaceBackEngine();
                Relief25DEngine.Result result = faceBackEngine.generate(
                        front,
                        back,
                        this::postReliefProgress
                );
                showResult(
                        result,
                        backUri == null
                                ? R.string.status_done
                                : R.string.status_done_pair
                );
            } catch (Exception | OutOfMemoryError error) {
                handleGenerationFailure(error, R.string.error_generation);
            } finally {
                recycleSource(front);
                recycleSource(back);
            }
        });
    }

    private void generateVideoModel(Uri videoUri) {
        prepareForNewGeneration();
        setBusy(true, R.string.status_copying_video);
        worker.execute(() -> {
            ProcessingPowerLock.favorCurrentThread();
            try (VideoFrameExtractor.Result extracted =
                         new VideoFrameExtractor(this).extract(
                                 videoUri,
                                 (current, total) -> postStatus(getString(
                                         R.string.status_extracting_video_progress,
                                         current,
                                         total
                                 ))
                         )) {
                if (videoEngine == null) {
                    videoEngine = new Relief25DEngine(
                            getApplicationContext(),
                            performanceProfile
                    );
                }
                Relief25DEngine.Result result = videoEngine.generateVideo(
                        extracted.getFrames(),
                        extracted.getDecodedFrameCount(),
                        this::postReliefProgress
                );
                Bitmap correctedTexture = flipAtlasCellsVertically(
                        result.getTexture()
                );
                if (!result.getTexture().isRecycled()) {
                    result.getTexture().recycle();
                }
                showResultWithTexture(
                        result,
                        correctedTexture,
                        R.string.status_done_video
                );
            } catch (Exception | OutOfMemoryError error) {
                handleGenerationFailure(error, R.string.error_video);
            }
        });
    }

    private void ensureFaceBackEngine() {
        if (faceBackEngine == null) {
            faceBackEngine = new FaceBack25DEngine(
                    getApplicationContext(),
                    performanceProfile
            );
        }
    }

    private static Bitmap flipAtlasCellsVertically(Bitmap source) {
        int width = source.getWidth();
        int height = source.getHeight();
        if (width < 4 || height < 4) {
            return source.copy(Bitmap.Config.ARGB_8888, false);
        }
        int cellWidth = width / 2;
        int cellHeight = height / 2;
        Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        canvas.drawColor(Color.TRANSPARENT);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        for (int row = 0; row < 2; row++) {
            for (int column = 0; column < 2; column++) {
                int left = column * cellWidth;
                int top = row * cellHeight;
                Rect sourceRect = new Rect(
                        left,
                        top,
                        left + cellWidth,
                        top + cellHeight
                );
                Rect targetRect = new Rect(
                        left,
                        top + cellHeight,
                        left + cellWidth,
                        top
                );
                canvas.drawBitmap(source, sourceRect, targetRect, paint);
            }
        }
        return output;
    }

    private void postReliefProgress(
            Relief25DEngine.Stage stage,
            int current,
            int total
    ) {
        switch (stage) {
            case SEGMENTING:
                postStatus(getString(
                        R.string.status_25d_segmenting,
                        current,
                        total
                ));
                break;
            case ALIGNING:
                postStatus(getString(R.string.status_25d_aligning_v52));
                break;
            case MESHING:
                postStatus(getString(R.string.status_25d_meshing_v52));
                break;
            case TEXTURING:
            default:
                postStatus(getString(R.string.status_25d_texturing_v52));
                break;
        }
    }

    private void showResult(Relief25DEngine.Result result, int doneMessage) {
        showResultWithTexture(result, result.getTexture(), doneMessage);
    }

    private void showResultWithTexture(
            Relief25DEngine.Result result,
            Bitmap texture,
            int doneMessage
    ) {
        runOnUiThread(() -> {
            replaceCurrentModel(result.getMesh(), texture);
            emptyText.setVisibility(View.GONE);
            setBusy(false, doneMessage);
            statusText.setText(getString(
                    R.string.status_done_25d_details,
                    result.getQualityLabel(),
                    result.getProcessorCount(),
                    currentMesh.getTriangleCount(),
                    currentMesh.getVertexCount(),
                    result.getRows(),
                    result.getColumns(),
                    result.getSourceViewCount(),
                    result.getBackend(),
                    result.getTotalDurationMs() / 1000.0
            ));
        });
    }

    private void toggleAutomaticRotation() {
        boolean running = viewer.toggleAutoRotation();
        rotationButton.setText(running
                ? R.string.rotation_stop
                : R.string.rotation_start);
    }

    private void prepareForNewGeneration() {
        viewer.stopAutoRotation();
        rotationButton.setText(R.string.rotation_start);
        viewer.setVisibility(View.INVISIBLE);
        emptyText.setVisibility(View.VISIBLE);
    }

    private void replaceCurrentModel(MeshData mesh, Bitmap texture) {
        Bitmap previousTexture = currentTexture;
        currentMesh = mesh;
        currentTexture = texture;
        viewer.setModel(mesh, texture);
        viewer.resetView();
        viewer.setVisibility(View.VISIBLE);
        if (previousTexture != null
                && previousTexture != texture
                && !previousTexture.isRecycled()) {
            previousTexture.recycle();
        }
    }

    private void exportCurrentModel() {
        MeshData mesh = currentMesh;
        Bitmap texture = currentTexture;
        if (mesh == null || texture == null) {
            return;
        }
        setBusy(true, R.string.status_exporting);
        worker.execute(() -> {
            ProcessingPowerLock.favorCurrentThread();
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
                            result.getMobileSizeBytes() / 1_000_000.0,
                            result.getMobileTriangleCount()
                    ));
                    shareFiles(result);
                });
            } catch (Exception | OutOfMemoryError error) {
                handleGenerationFailure(error, R.string.error_export);
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
                "Personnage 2.5D V5.2 Face + Dos — GLB HD + mobile"
        );
        share.putExtra(
                Intent.EXTRA_TEXT,
                "GLB V5.2 créé localement dans : "
                        + result.getDirectory().getAbsolutePath()
                        + "\nCopie mobile : "
                        + result.getMobileSizeBytes()
                        + " octets."
        );
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ClipData clipData = ClipData.newRawUri("Personnage 2.5D V5.2", uris.get(0));
        for (int index = 1; index < uris.size(); index++) {
            clipData.addItem(new ClipData.Item(uris.get(index)));
        }
        share.setClipData(clipData);
        startActivity(Intent.createChooser(
                share,
                getString(R.string.share_model)
        ));
    }

    private void handleGenerationFailure(Throwable error, int messageResource) {
        Log.e(TAG, "Échec V5.2", error);
        String details = safeMessage(error);
        Runtime.getRuntime().gc();
        runOnUiThread(() -> {
            setBusy(false, messageResource);
            boolean hasPreviousModel = currentMesh != null && currentTexture != null;
            viewer.setVisibility(hasPreviousModel ? View.VISIBLE : View.INVISIBLE);
            emptyText.setVisibility(hasPreviousModel ? View.GONE : View.VISIBLE);
            String message = getString(messageResource) + " " + details;
            statusText.setText(message);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
    }

    private void setBusy(boolean busyValue, int messageResource) {
        busy = busyValue;
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        frontButton.setEnabled(!busy);
        backButton.setEnabled(!busy);
        videoButton.setEnabled(!busy);
        rotationButton.setEnabled(!busy && currentMesh != null);
        exportButton.setEnabled(!busy && currentMesh != null);
        updateSelectionButtons();
        statusText.setText(messageResource);
        configurePerformanceMode(busy);
    }

    private void configurePerformanceMode(boolean enabled) {
        if (enabled) {
            if (processingPowerLock == null) {
                processingPowerLock = ProcessingPowerLock.acquire(
                        this,
                        "relief-face-dos-v52"
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
                // Certaines surcouches refusent ce mode malgré sa déclaration.
            }
        }
    }

    private void postStatus(String message) {
        runOnUiThread(() -> statusText.setText(message));
    }

    private void showToast(int resource) {
        Toast.makeText(this, resource, Toast.LENGTH_LONG).show();
    }

    private void recycleSource(Bitmap source) {
        if (source != null && source != currentTexture && !source.isRecycled()) {
            source.recycle();
        }
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
        configurePerformanceMode(false);
        if (faceBackEngine != null) {
            faceBackEngine.close();
        }
        if (videoEngine != null) {
            videoEngine.close();
        }
        if (currentTexture != null && !currentTexture.isRecycled()) {
            currentTexture.recycle();
        }
        super.onDestroy();
    }
}
