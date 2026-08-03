package com.chasmet.modeliseur3d;

import android.os.Bundle;
import android.os.Environment;
import android.view.Choreographer;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.filament.Engine;
import com.google.android.filament.android.UiHelper;
import com.google.android.filament.utils.Float3;
import com.google.android.filament.utils.Manipulator;
import com.google.android.filament.utils.ModelViewer;
import com.google.android.filament.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/** Apercu PBR du GLB mobile, y compris EXT_meshopt_compression. */
public final class CloudModelActivity extends AppCompatActivity {
    public static final String EXTRA_MODEL_PATH = "cloud_model_path";
    public static final String EXTRA_MODEL_LABEL = "cloud_model_label";

    private ModelViewer modelViewer;
    private Choreographer choreographer;
    private boolean rendering;
    private final FrameLoop frameLoop = new FrameLoop();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cloud_model);

        TextView status = findViewById(R.id.cloudViewerStatus);
        SurfaceView surface = findViewById(R.id.cloudSurfaceView);
        Button back = findViewById(R.id.cloudBackButton);
        Button reset = findViewById(R.id.cloudResetButton);
        back.setOnClickListener(view -> finish());

        try {
            File modelFile = resolveModelFile();
            Utils.init();
            Manipulator manipulator = new Manipulator.Builder()
                    .targetPosition(0.0f, 0.0f, -4.0f)
                    .viewport(
                            Math.max(1, surface.getWidth()),
                            Math.max(1, surface.getHeight())
                    )
                    .build(Manipulator.Mode.ORBIT);
            modelViewer = new ModelViewer(
                    surface,
                    Engine.create(),
                    new UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK),
                    manipulator
            );
            surface.setOnTouchListener(modelViewer);
            modelViewer.loadModelGlb(ByteBuffer.wrap(readBytes(modelFile)));
            modelViewer.transformToUnitCube(
                    new Float3(0.0f, 0.0f, -4.0f)
            );
            reset.setOnClickListener(view -> modelViewer.resetToDefaultState());

            String label = getIntent().getStringExtra(EXTRA_MODEL_LABEL);
            status.setText(label == null || label.trim().isEmpty()
                    ? getString(R.string.cloud_viewer_ready)
                    : label);
            choreographer = Choreographer.getInstance();
        } catch (Exception | OutOfMemoryError error) {
            surface.setVisibility(View.GONE);
            reset.setEnabled(false);
            status.setText(getString(
                    R.string.cloud_viewer_error,
                    safeMessage(error)
            ));
        }
    }

    private File resolveModelFile() throws IOException {
        String path = getIntent().getStringExtra(EXTRA_MODEL_PATH);
        File documents = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (path == null || documents == null) {
            throw new IOException("Fichier GLB absent");
        }
        File allowedRoot = documents.getCanonicalFile();
        File candidate = new File(path).getCanonicalFile();
        String prefix = allowedRoot.getPath() + File.separator;
        if (!candidate.getPath().startsWith(prefix)
                || !candidate.isFile()
                || candidate.length() < 20L) {
            throw new IOException("Fichier GLB non autorise ou incomplet");
        }
        return candidate;
    }

    private static byte[] readBytes(File file) throws IOException {
        if (file.length() > 2L * 1024L * 1024L) {
            throw new IOException(
                    "Utilise l'apercu mobile ; le fichier HD est trop lourd"
            );
        }
        try (InputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream(
                     (int) file.length()
             )) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return error.getClass().getSimpleName();
        }
        return message.length() > 160
                ? message.substring(0, 157) + "..."
                : message;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (choreographer != null && modelViewer != null) {
            rendering = true;
            choreographer.postFrameCallback(frameLoop);
        }
    }

    @Override
    protected void onPause() {
        rendering = false;
        if (choreographer != null) {
            choreographer.removeFrameCallback(frameLoop);
        }
        super.onPause();
    }

    private final class FrameLoop implements Choreographer.FrameCallback {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!rendering || modelViewer == null || choreographer == null) {
                return;
            }
            modelViewer.render(frameTimeNanos);
            choreographer.postFrameCallback(this);
        }
    }
}
