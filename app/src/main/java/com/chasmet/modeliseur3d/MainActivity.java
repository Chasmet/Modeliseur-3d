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
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.chasmet.modeliseur3d.gl.ModelGLSurfaceView;
import com.chasmet.modeliseur3d.media.VideoFrameExtractor;
import com.chasmet.modeliseur3d.model.MeshData;
import com.chasmet.modeliseur3d.model.NeuralReconstructionEngineV47;
import com.chasmet.modeliseur3d.model.ObjExporter;
import com.chasmet.modeliseur3d.model.VideoReconstructionEngineV47;
import com.chasmet.modeliseur3d.performance.DevicePerformanceProfile;
import com.chasmet.modeliseur3d.performance.ProcessingPowerLock;
import com.chasmet.modeliseur3d.util.BitmapUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends AppCompatActivity {
    private static final String TAG = "Modeliseur3D";
    private static final int REQUEST_IMAGE = 2001;
    private static final int REQUEST_VIDEO = 2002;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private DevicePerformanceProfile performanceProfile;
    private ProcessingPowerLock processingPowerLock;
    private NeuralReconstructionEngineV47 imageGenerator;
    private VideoReconstructionEngineV47 videoGenerator;
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

        performanceProfile = DevicePerformanceProfile.detect(this);
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
        statusText.setText(getString(
                R.string.status_ready_profile,
                performanceProfile.describe()
        ));
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
            // Certains fournisseurs accordent seulement une permission temporaire.
        }
    }

    private void generateImageModel(Uri imageUri) {
        prepareForNewGeneration();
        setBusy(true, R.string.status_loading);
        worker.execute(() -> {
            ProcessingPowerLock.favorCurrentThread();
            Bitmap source = null;
            try {
                source = BitmapUtils.decodeBitmapFromUri(
                        getContentResolver(),
                        imageUri,
                        performanceProfile.getMaximumInputSide()
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
                if (videoGenerator == null) {
                    postStatus(getString(R.string.status_loading_video_engine));
                    videoGenerator = new VideoReconstructionEngineV47(
                            getApplicationContext(),
                            performanceProfile
                    );
                }
                VideoReconstructionEngineV47.Result result =
                        videoGenerator.generate(
                                extracted.getFrames(),
                                extracted.getDecodedFrameCount(),
                                this::postVideoProgress
                        );
                runOnUiThread(() -> {
                    replaceCurrentModel(result.getMesh(), result.getTexture());
                    emptyText.setVisibility(View.GONE);
                    setBusy(false, R.string.status_done_video);
                    statusText.setText(getString(
                            R.string.status_done_video_details,
                            result.getQualityLabel(),
                            result.getProcessorCount(),
                            currentMesh.getTriangleCount(),
                            currentMesh.getVertexCount(),
                            result.getOccupiedVoxels(),
                            result.getBackend(),
                            result.getTotalDurationMs() / 1000.0
                    ));
                });
            } catch (Exception | OutOfMemoryError error) {
                handleGenerationFailure(error, R.string.error_video);
            }
        });
    }

    private void postVideoProgress(
            VideoReconstructionEngineV47.Stage stage,
            int current,
            int total
    ) {
        switch (stage) {
            case SEGMENTING:
                postStatus(getString(
                        R.string.status_video_segmenting,
                        current,
                        total
                ));
                break;
            case BUILDING_HULL:
                postStatus(getString(R.string.status_video_hull));
                break;
            case MESHING:
                postStatus(getString(R.string.status_video_meshing));
                break;
            case DEPTH:
            default:
                postStatus(getString(
                        R.string.status_video_depth,
                        current,
                        total
                ));
                break;
        }
    }

    private void generateFromBitmap(Bitmap source) throws Exception {
        if (imageGenerator == null) {
            postStatus(getString(R.string.status_loading_neural_engine));
            imageGenerator = new NeuralReconstructionEngineV47(
                    getApplicationContext(),
                    performanceProfile
            );
        }
        postStatus(getString(R.string.status_generating_image_v47));
        NeuralReconstructionEngineV47.Result result =
                imageGenerator.generate(source);
        runOnUiThread(() -> {
            replaceCurrentModel(result.getMesh(), result.getTexture());
            emptyText.setVisibility(View.GONE);
            setBusy(false, R.string.status_done);
            statusText.setText(getString(
                    R.string.status_done_details,
                    result.getFinalSubjectCount(),
                    result.getDetectedSubjectCount(),
                    result.getQualityLabel(),
                    result.getProcessorCount(),
                    currentMesh.getTriangleCount(),
                    currentMesh.getVertexCount(),
                    result.getGridWidth(),
                    result.getGridHeight(),
                    result.getNeuralBackend(),
                    result.getTotalDurationMs() / 1000.0
            ));
        });
    }

    private void prepareForNewGeneration() {
        viewer.setVisibility(View.INVISIBLE);
        emptyText.setVisibility(View.VISIBLE);
    }

    private void replaceCurrentModel(MeshData mesh, Bitmap texture) {
        Bitmap previousTexture = currentTexture;
        currentMesh = mesh;
        currentTexture = texture;
        viewer.setModel(mesh, texture);
        viewer.setVisibility(View.VISIBLE);
        if (previousTexture != null
                && previousTexture != texture
                && !previousTexture.isRecycled()) {
            previousTexture.recycle();
        }
    }

    private void handleGenerationFailure(
            Throwable error,
            int messageResource
    ) {
        Log.e(TAG, "Échec de reconstruction V4.8 mobile", error);
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
                Log.e(TAG, "Échec d'export V4.8", error);
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
                "Modèle 3D V4.8 Multivue mobile — GLB HD + GLB mobile 1 Mo"
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
                "Modèle 3D V4.8",
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
        configurePerformanceMode(busy);
    }

    private void configurePerformanceMode(boolean busy) {
        if (busy) {
            if (processingPowerLock == null) {
                processingPowerLock = ProcessingPowerLock.acquire(
                        this,
                        "reconstruction"
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
                getWindow().setSustainedPerformanceMode(busy);
            } catch (RuntimeException ignored) {
                // Certaines surcouches déclarent le mode sans l'accepter.
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
        if (source != null
                && source != currentTexture
                && !source.isRecycled()) {
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
        if (imageGenerator != null) {
            imageGenerator.close();
        }
        if (videoGenerator != null) {
            videoGenerator.close();
        }
        if (currentTexture != null && !currentTexture.isRecycled()) {
            currentTexture.recycle();
        }
        super.onDestroy();
    }
}
