package com.chasmet.modeliseur3d;

import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.chasmet.modeliseur3d.cloud.CloudCredentialStore;
import com.chasmet.modeliseur3d.cloud.TripoCloudEngine;
import com.chasmet.modeliseur3d.gl.ModelGLSurfaceView;
import com.chasmet.modeliseur3d.media.VideoFrameExtractor;
import com.chasmet.modeliseur3d.model.CloudViewPreprocessor;
import com.chasmet.modeliseur3d.model.MeshData;
import com.chasmet.modeliseur3d.model.NeuralReconstructionEngine;
import com.chasmet.modeliseur3d.model.ObjExporter;
import com.chasmet.modeliseur3d.util.BitmapUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends AppCompatActivity {
    private static final String TAG = "Modeliseur3D";
    private static final int REQUEST_IMAGES = 2001;
    private static final int REQUEST_VIDEO = 2002;
    private static final int MAX_INPUT_SIDE = 2048;
    private static final String TRIPO_CONSOLE = "https://platform.tripo3d.ai";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Uri> selectedImageUris = new ArrayList<>();

    private NeuralReconstructionEngine localGenerator;
    private ModelGLSurfaceView localViewer;
    private CloudCredentialStore credentialStore;
    private ProgressBar progressBar;
    private TextView statusText;
    private TextView emptyText;
    private Button selectButton;
    private Button videoButton;
    private Button settingsButton;
    private Button exportButton;
    private Button highDefinitionButton;
    private Button previewButton;
    private Button localButton;

    private MeshData currentMesh;
    private Bitmap currentTexture;
    private TripoCloudEngine.Result cloudResult;
    private Uri selectedVideoUri;
    private int videoFrontSlot;
    private boolean videoNextQuarterIsLeft = true;
    private boolean busy;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        FrameLayout container = findViewById(R.id.viewerContainer);
        localViewer = new ModelGLSurfaceView(this);
        localViewer.setVisibility(View.GONE);
        container.addView(localViewer, 0, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        credentialStore = new CloudCredentialStore(this);
        progressBar = findViewById(R.id.progressBar);
        statusText = findViewById(R.id.statusText);
        emptyText = findViewById(R.id.emptyText);
        selectButton = findViewById(R.id.selectButton);
        videoButton = findViewById(R.id.selectVideoButton);
        settingsButton = findViewById(R.id.settingsButton);
        exportButton = findViewById(R.id.exportButton);
        highDefinitionButton = findViewById(R.id.highDefinitionButton);
        previewButton = findViewById(R.id.previewButton);
        localButton = findViewById(R.id.localButton);
        Button resetButton = findViewById(R.id.resetButton);

        selectButton.setOnClickListener(view -> chooseImages());
        videoButton.setOnClickListener(view -> chooseVideo());
        settingsButton.setOnClickListener(view -> showApiKeyMenu());
        resetButton.setOnClickListener(view -> localViewer.resetView());
        exportButton.setOnClickListener(view -> exportPrimaryModel());
        highDefinitionButton.setOnClickListener(
                view -> shareCloudFile(true)
        );
        previewButton.setOnClickListener(view -> previewCloudModel());
        localButton.setOnClickListener(view -> generateLocalFallback());
        updateControls();
    }

    private void chooseImages() {
        if (busy) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_IMAGES);
    }

    private void chooseVideo() {
        if (busy) {
            return;
        }
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
        if (resultCode != RESULT_OK || data == null) {
            return;
        }
        if (requestCode == REQUEST_IMAGES) {
            List<Uri> uris = collectImageUris(data);
            if (uris.isEmpty()) {
                showToast(R.string.error_image);
                return;
            }
            if (uris.size() > 4) {
                showToast(R.string.error_too_many_images);
                return;
            }
            for (Uri uri : uris) {
                persistReadPermission(uri);
            }
            selectedImageUris.clear();
            selectedImageUris.addAll(uris);
            selectedVideoUri = null;
            prepareForNewInput();
            ensureApiKey(() -> generateCloudFromImages(
                    new ArrayList<>(selectedImageUris)
            ));
        } else if (requestCode == REQUEST_VIDEO && data.getData() != null) {
            selectedVideoUri = data.getData();
            persistReadPermission(selectedVideoUri);
            selectedImageUris.clear();
            prepareForNewInput();
            askVideoFrontPosition();
        }
    }

    private List<Uri> collectImageUris(Intent data) {
        Set<Uri> unique = new LinkedHashSet<>();
        ClipData clipData = data.getClipData();
        if (clipData != null) {
            for (int index = 0; index < clipData.getItemCount(); index++) {
                Uri uri = clipData.getItemAt(index).getUri();
                if (uri != null) {
                    unique.add(uri);
                }
            }
        } else if (data.getData() != null) {
            unique.add(data.getData());
        }
        return new ArrayList<>(unique);
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

    private void askVideoFrontPosition() {
        String[] positions = {
                getString(R.string.video_front_start),
                getString(R.string.video_front_quarter),
                getString(R.string.video_front_middle),
                getString(R.string.video_front_three_quarters)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.video_front_title)
                .setMessage(R.string.video_front_message)
                .setSingleChoiceItems(positions, videoFrontSlot, (dialog, which) -> {
                    videoFrontSlot = which;
                    dialog.dismiss();
                    askVideoDirection();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void askVideoDirection() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.video_direction_title)
                .setMessage(R.string.video_direction_message)
                .setPositiveButton(R.string.video_left_profile, (dialog, which) -> {
                    videoNextQuarterIsLeft = true;
                    ensureApiKey(() -> generateCloudFromVideo(
                            selectedVideoUri,
                            videoFrontSlot,
                            true
                    ));
                })
                .setNegativeButton(R.string.video_right_profile, (dialog, which) -> {
                    videoNextQuarterIsLeft = false;
                    ensureApiKey(() -> generateCloudFromVideo(
                            selectedVideoUri,
                            videoFrontSlot,
                            false
                    ));
                })
                .setNeutralButton(R.string.cancel, null)
                .show();
    }

    private void ensureApiKey(Runnable continuation) {
        if (credentialStore.hasKey()) {
            continuation.run();
            return;
        }
        showApiKeyEditor(continuation);
    }

    private void showApiKeyMenu() {
        if (!credentialStore.hasKey()) {
            showApiKeyEditor(null);
            return;
        }
        String[] actions = {
                getString(R.string.api_key_replace),
                getString(R.string.api_key_open_site),
                getString(R.string.api_key_delete)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.api_dialog_title)
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) {
                        showApiKeyEditor(null);
                    } else if (which == 1) {
                        openTripoConsole();
                    } else {
                        credentialStore.clear();
                        showToast(R.string.api_key_deleted);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showApiKeyEditor(@Nullable Runnable continuation) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(R.string.api_key_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setPadding(42, 20, 42, 20);

        new AlertDialog.Builder(this)
                .setTitle(R.string.api_dialog_title)
                .setMessage(R.string.api_dialog_message)
                .setView(input)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    try {
                        credentialStore.save(input.getText().toString());
                        showToast(R.string.api_key_saved);
                        if (continuation != null) {
                            continuation.run();
                        }
                    } catch (Exception error) {
                        statusText.setText(getString(
                                R.string.api_key_save_error,
                                safeMessage(error)
                        ));
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .setNeutralButton(
                        R.string.api_key_open_site,
                        (dialog, which) -> openTripoConsole()
                )
                .show();
    }

    private void openTripoConsole() {
        try {
            startActivity(new Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(TRIPO_CONSOLE)
            ));
        } catch (Exception error) {
            statusText.setText(TRIPO_CONSOLE);
        }
    }

    private void generateCloudFromImages(List<Uri> uris) {
        setBusy(true, R.string.status_loading);
        worker.execute(() -> {
            List<Bitmap> sources = new ArrayList<>();
            try {
                for (int index = 0; index < uris.size(); index++) {
                    int current = index + 1;
                    postStatus(getString(
                            R.string.status_reading_images,
                            current,
                            uris.size()
                    ));
                    sources.add(BitmapUtils.decodeBitmapFromUri(
                            getContentResolver(),
                            uris.get(index),
                            MAX_INPUT_SIDE
                    ));
                }
                TripoCloudEngine.Result result = runCloudPipeline(
                        sources,
                        null
                );
                handleCloudSuccess(result);
            } catch (Exception | OutOfMemoryError error) {
                handleCloudFailure(error);
            } finally {
                recycleAll(sources);
            }
        });
    }

    private void generateCloudFromVideo(
            Uri videoUri,
            int frontSlot,
            boolean nextQuarterIsLeft
    ) {
        if (videoUri == null) {
            return;
        }
        setBusy(true, R.string.status_extracting_video);
        worker.execute(() -> {
            try (VideoFrameExtractor.Result extracted =
                         new VideoFrameExtractor(this).extract(
                                 videoUri,
                                 frontSlot,
                                 nextQuarterIsLeft,
                                 (current, total) -> postStatus(getString(
                                         R.string.status_extracting_video_progress,
                                         current,
                                         total
                                 ))
                         )) {
                TripoCloudEngine.Result result = runCloudPipeline(
                        extracted.getFrames(),
                        extracted.getRoles()
                );
                handleCloudSuccess(result);
            } catch (Exception | OutOfMemoryError error) {
                handleCloudFailure(error);
            }
        });
    }

    private TripoCloudEngine.Result runCloudPipeline(
            List<Bitmap> sources,
            @Nullable List<String> roles
    ) throws Exception {
        postStatus(getString(R.string.status_preparing_cloud));
        CloudViewPreprocessor preprocessor = new CloudViewPreprocessor(this);
        try (CloudViewPreprocessor.PreparedViews prepared =
                     preprocessor.prepare(
                             sources,
                             roles,
                             (current, total) -> postStatus(getString(
                                     R.string.status_preparing_cloud_progress,
                                     current,
                                     total
                             ))
                     )) {
            String apiKey = credentialStore.load();
            if (apiKey.isEmpty()) {
                throw new IllegalStateException(
                        getString(R.string.api_key_required)
                );
            }
            return new TripoCloudEngine(this, apiKey).generate(
                    prepared.getViews(),
                    this::onCloudProgress
            );
        }
    }

    private void onCloudProgress(TripoCloudEngine.Stage stage, int percent) {
        int resource;
        switch (stage) {
            case UPLOADING:
                resource = R.string.status_uploading_cloud;
                break;
            case GENERATING:
                resource = R.string.status_cloud_waiting;
                break;
            case DOWNLOADING_HD:
                resource = R.string.status_cloud_downloading;
                break;
            case OPTIMIZING:
                resource = R.string.status_cloud_optimizing;
                break;
            case DOWNLOADING_MOBILE:
            default:
                resource = R.string.status_mobile_downloading;
                break;
        }
        postStatus(getString(resource, percent));
    }

    private void handleCloudSuccess(TripoCloudEngine.Result result) {
        runOnUiThread(() -> {
            cloudResult = result;
            localViewer.setVisibility(View.GONE);
            emptyText.setVisibility(View.VISIBLE);
            emptyText.setText(R.string.cloud_ready_message);
            setBusy(false, R.string.status_cloud_ready);
            statusText.setText(getString(
                    R.string.status_cloud_done,
                    result.getViewCount(),
                    result.getMobileSizeBytes() / 1000.0,
                    result.getHighDefinitionSizeBytes() / (1024.0 * 1024.0),
                    result.getDurationMs() / 1000.0,
                    result.getCreditsConsumed(),
                    result.getMobilePreset()
            ));
            updateControls();
            showToast(R.string.status_cloud_ready);
        });
    }

    private void handleCloudFailure(Throwable error) {
        Log.e(TAG, "Echec de generation V4.3", error);
        String details = safeMessage(error);
        Runtime.getRuntime().gc();
        runOnUiThread(() -> {
            setBusy(false, R.string.error_cloud_generation);
            statusText.setText(getString(
                    R.string.error_cloud_generation_details,
                    details
            ));
            updateControls();
            Toast.makeText(
                    this,
                    getString(R.string.error_cloud_generation) + " " + details,
                    Toast.LENGTH_LONG
            ).show();
        });
    }

    private void previewCloudModel() {
        if (cloudResult == null || cloudResult.getMobileFile() == null) {
            return;
        }
        Intent intent = new Intent(this, CloudModelActivity.class);
        intent.putExtra(
                CloudModelActivity.EXTRA_MODEL_PATH,
                cloudResult.getMobileFile().getAbsolutePath()
        );
        intent.putExtra(
                CloudModelActivity.EXTRA_MODEL_LABEL,
                getString(
                        R.string.cloud_viewer_label,
                        cloudResult.getMobileSizeBytes() / 1000.0
                )
        );
        startActivity(intent);
    }

    private void exportPrimaryModel() {
        if (cloudResult != null && cloudResult.getMobileFile() != null) {
            shareFile(
                    cloudResult.getMobileFile(),
                    getString(R.string.share_mobile_model)
            );
        } else if (currentMesh != null && currentTexture != null) {
            exportLocalModel();
        }
    }

    private void shareCloudFile(boolean highDefinition) {
        if (cloudResult == null) {
            return;
        }
        File file = highDefinition
                ? cloudResult.getHighDefinitionFile()
                : cloudResult.getMobileFile();
        if (file != null) {
            shareFile(
                    file,
                    getString(highDefinition
                            ? R.string.share_hd_model
                            : R.string.share_mobile_model)
            );
        }
    }

    private void shareFile(File file, String chooserTitle) {
        if (!file.isFile()) {
            showToast(R.string.error_export);
            return;
        }
        Uri uri = FileProvider.getUriForFile(
                this,
                getPackageName() + ".fileprovider",
                file
        );
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("model/gltf-binary");
        share.putExtra(Intent.EXTRA_STREAM, uri);
        share.putExtra(Intent.EXTRA_SUBJECT, file.getName());
        share.putExtra(
                Intent.EXTRA_TEXT,
                getString(
                        R.string.share_file_details,
                        file.getName(),
                        formatFileSize(file.length())
                )
        );
        share.setClipData(ClipData.newRawUri(file.getName(), uri));
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(share, chooserTitle));
    }

    private void generateLocalFallback() {
        if (selectedImageUris.isEmpty() && selectedVideoUri == null) {
            return;
        }
        setBusy(true, R.string.status_loading_neural_engine);
        worker.execute(() -> {
            Bitmap source = null;
            try {
                source = buildLocalSource();
                if (localGenerator == null) {
                    localGenerator = new NeuralReconstructionEngine(
                            getApplicationContext()
                    );
                }
                postStatus(getString(R.string.status_generating_neural));
                NeuralReconstructionEngine.Result result =
                        localGenerator.generate(source);
                currentMesh = result.getMesh();
                Bitmap oldTexture = currentTexture;
                currentTexture = result.getTexture();
                if (oldTexture != null
                        && oldTexture != currentTexture
                        && !oldTexture.isRecycled()) {
                    oldTexture.recycle();
                }
                runOnUiThread(() -> {
                    localViewer.setModel(currentMesh, currentTexture);
                    localViewer.setVisibility(View.VISIBLE);
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
                    updateControls();
                });
            } catch (Exception | OutOfMemoryError error) {
                Log.e(TAG, "Echec du secours local V4.2", error);
                String details = safeMessage(error);
                runOnUiThread(() -> {
                    setBusy(false, R.string.error_generation);
                    statusText.setText(getString(
                            R.string.error_generation_details,
                            details
                    ));
                    updateControls();
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

    private Bitmap buildLocalSource() throws Exception {
        if (!selectedImageUris.isEmpty()) {
            List<Bitmap> images = new ArrayList<>();
            try {
                for (Uri uri : selectedImageUris) {
                    images.add(BitmapUtils.decodeBitmapFromUri(
                            getContentResolver(),
                            uri,
                            MAX_INPUT_SIDE
                    ));
                }
                if (images.size() == 1) {
                    return images.remove(0);
                }
                return createHorizontalSheet(images);
            } finally {
                recycleAll(images);
            }
        }

        try (VideoFrameExtractor.Result extracted =
                     new VideoFrameExtractor(this).extract(
                             selectedVideoUri,
                             videoFrontSlot,
                             videoNextQuarterIsLeft,
                             null
                     )) {
            return createHorizontalSheet(extracted.getFrames());
        }
    }

    private static Bitmap createHorizontalSheet(List<Bitmap> images) {
        if (images == null || images.isEmpty()) {
            throw new IllegalArgumentException("Aucune vue pour la planche locale");
        }
        int width = MAX_INPUT_SIDE;
        int cellWidth = Math.max(1, width / images.size());
        int height = 1;
        for (Bitmap image : images) {
            height = Math.max(height, Math.round(
                    cellWidth * image.getHeight()
                            / (float) Math.max(1, image.getWidth())
            ));
        }
        height = Math.min(MAX_INPUT_SIDE, Math.max(256, height));

        Bitmap sheet = Bitmap.createBitmap(
                width,
                height,
                Bitmap.Config.ARGB_8888
        );
        Canvas canvas = new Canvas(sheet);
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(
                Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG
        );
        for (int index = 0; index < images.size(); index++) {
            Bitmap image = images.get(index);
            float scale = Math.min(
                    cellWidth / (float) Math.max(1, image.getWidth()),
                    height / (float) Math.max(1, image.getHeight())
            );
            float drawWidth = image.getWidth() * scale;
            float drawHeight = image.getHeight() * scale;
            float left = index * cellWidth + (cellWidth - drawWidth) * 0.5f;
            float top = (height - drawHeight) * 0.5f;
            canvas.drawBitmap(
                    image,
                    null,
                    new RectF(left, top, left + drawWidth, top + drawHeight),
                    paint
            );
        }
        return sheet;
    }

    private void exportLocalModel() {
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
                    shareLocalFiles(result);
                    updateControls();
                });
            } catch (Exception | OutOfMemoryError error) {
                Log.e(TAG, "Echec d'export local", error);
                String details = safeMessage(error);
                runOnUiThread(() -> {
                    setBusy(false, R.string.error_export);
                    statusText.setText(getString(
                            R.string.error_export_details,
                            details
                    ));
                    updateControls();
                });
            }
        });
    }

    private void shareLocalFiles(ObjExporter.ExportResult result) {
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
        share.putExtra(Intent.EXTRA_SUBJECT, "Modele 3D local V4.2");
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ClipData clipData = ClipData.newRawUri("Modele local", uris.get(0));
        for (int index = 1; index < uris.size(); index++) {
            clipData.addItem(new ClipData.Item(uris.get(index)));
        }
        share.setClipData(clipData);
        startActivity(Intent.createChooser(
                share,
                getString(R.string.share_model)
        ));
    }

    private void prepareForNewInput() {
        cloudResult = null;
        currentMesh = null;
        localViewer.setVisibility(View.GONE);
        emptyText.setVisibility(View.VISIBLE);
        emptyText.setText(R.string.empty_message);
        statusText.setText(R.string.status_input_selected);
        updateControls();
    }

    private void setBusy(boolean isBusy, int messageResource) {
        busy = isBusy;
        progressBar.setVisibility(isBusy ? View.VISIBLE : View.GONE);
        statusText.setText(messageResource);
        updateControls();
    }

    private void updateControls() {
        boolean hasInput = !selectedImageUris.isEmpty()
                || selectedVideoUri != null;
        boolean hasCloud = cloudResult != null
                && cloudResult.getMobileFile() != null;
        selectButton.setEnabled(!busy);
        videoButton.setEnabled(!busy);
        settingsButton.setEnabled(!busy);
        localButton.setEnabled(!busy && hasInput);
        exportButton.setEnabled(!busy && (hasCloud
                || (currentMesh != null && currentTexture != null)));
        highDefinitionButton.setEnabled(!busy && hasCloud);
        previewButton.setEnabled(!busy && hasCloud);
        exportButton.setText(hasCloud
                ? R.string.export_mobile_model
                : R.string.export_local_model);
    }

    private void postStatus(String value) {
        runOnUiThread(() -> statusText.setText(value));
    }

    private void showToast(int resource) {
        Toast.makeText(this, resource, Toast.LENGTH_LONG).show();
    }

    private static void recycleAll(List<Bitmap> bitmaps) {
        if (bitmaps == null) {
            return;
        }
        for (Bitmap bitmap : bitmaps) {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
        try {
            bitmaps.clear();
        } catch (UnsupportedOperationException ignored) {
            // Une vue non modifiable est acceptable ; les bitmaps sont deja liberes.
        }
    }

    private static String formatFileSize(long bytes) {
        if (bytes < 1000L) {
            return bytes + " octets";
        }
        if (bytes < 1_000_000L) {
            return String.format(Locale.FRANCE, "%.1f Ko", bytes / 1000.0);
        }
        return String.format(
                Locale.FRANCE,
                "%.1f Mo",
                bytes / 1_000_000.0
        );
    }

    private static String safeMessage(Throwable error) {
        if (error instanceof OutOfMemoryError) {
            return "memoire du telephone insuffisante ; ferme les autres applications";
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
            return error.getClass().getSimpleName();
        }
        return message.length() > 220
                ? message.substring(0, 217) + "..."
                : message;
    }

    @Override
    protected void onResume() {
        super.onResume();
        localViewer.onResume();
    }

    @Override
    protected void onPause() {
        localViewer.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        if (localGenerator != null) {
            localGenerator.close();
        }
        if (currentTexture != null && !currentTexture.isRecycled()) {
            currentTexture.recycle();
        }
        super.onDestroy();
    }
}
