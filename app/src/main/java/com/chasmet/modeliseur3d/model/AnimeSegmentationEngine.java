package com.chasmet.modeliseur3d.model;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.Map;

/**
 * Segmentation locale spécialisée pour les personnages dessinés, anime et jeux vidéo.
 *
 * Modèle : IS-Net Anime, issu du projet open source anime-segmentation et distribué
 * par rembg. La sortie est une carte de confiance continue utilisée avant la
 * reconstruction multivue. Le modèle est chargé uniquement pendant le détourage
 * afin de ne pas conserver en mémoire simultanément les deux gros réseaux V4.1.
 */
public final class AnimeSegmentationEngine implements AutoCloseable {
    public static final String MODEL_NAME = "IS-Net Anime";

    private static final String MODEL_ASSET = "models/isnet_anime.onnx";
    private static final String MODEL_FILE = "isnet_anime_v41.onnx";
    private static final int INPUT_SIZE = 1024;
    private static final float[] MEAN = {0.485f, 0.456f, 0.406f};
    private static final long MINIMUM_MODEL_BYTES = 150_000_000L;

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final String inputName;
    private final String backend;

    public AnimeSegmentationEngine(Context context) throws Exception {
        File model = copyModelIfNeeded(context.getApplicationContext());
        environment = OrtEnvironment.getEnvironment();
        SessionBundle bundle = createSession(model);
        session = bundle.session;
        backend = bundle.backend;
        inputName = session.getInputNames().iterator().next();
    }

    public Mask segment(Bitmap source) throws Exception {
        if (source == null || source.isRecycled()) {
            throw new IllegalArgumentException("Planche absente pour la segmentation");
        }

        Bitmap resized = Bitmap.createScaledBitmap(
                source,
                INPUT_SIZE,
                INPUT_SIZE,
                true
        );
        FloatBuffer inputBuffer = createInputBuffer(resized);
        if (resized != source && !resized.isRecycled()) {
            resized.recycle();
        }

        long[] shape = {1, 3, INPUT_SIZE, INPUT_SIZE};
        try (OnnxTensor input = OnnxTensor.createTensor(
                environment,
                inputBuffer,
                shape
        )) {
            Map<String, OnnxTensor> inputs =
                    Collections.singletonMap(inputName, input);
            try (OrtSession.Result result = session.run(inputs)) {
                OnnxValue value = result.get(0);
                if (!(value instanceof OnnxTensor)) {
                    throw new OrtException("La segmentation IS-Net ne renvoie pas de tenseur");
                }
                FloatBuffer output = ((OnnxTensor) value).getFloatBuffer();
                if (output == null || output.remaining() == 0) {
                    throw new OrtException("La segmentation IS-Net est vide");
                }

                float[] raw = new float[output.remaining()];
                output.get(raw);
                int expected = INPUT_SIZE * INPUT_SIZE;
                if (raw.length < expected) {
                    throw new OrtException(
                            "Dimensions IS-Net inattendues : " + raw.length
                    );
                }
                if (raw.length != expected) {
                    float[] tail = new float[expected];
                    System.arraycopy(raw, raw.length - expected, tail, 0, expected);
                    raw = tail;
                }
                normalizeRobust(raw);
                return Mask.resize(
                        raw,
                        INPUT_SIZE,
                        INPUT_SIZE,
                        source.getWidth(),
                        source.getHeight()
                );
            }
        }
    }

    public String getBackend() {
        return backend;
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (OrtException ignored) {
            // Fermeture défensive : l'inférence est déjà terminée.
        }
    }

    private SessionBundle createSession(File model) throws Exception {
        int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        int threads = Math.max(2, Math.min(8, processors - 1));

        OrtSession.SessionOptions accelerated = new OrtSession.SessionOptions();
        accelerated.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        accelerated.setIntraOpNumThreads(threads);
        accelerated.setInterOpNumThreads(1);

        boolean nnapi = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1;
        if (nnapi) {
            try {
                accelerated.addNnapi();
            } catch (OrtException ignored) {
                nnapi = false;
            }
        }

        try {
            OrtSession created = environment.createSession(
                    model.getAbsolutePath(),
                    accelerated
            );
            accelerated.close();
            return new SessionBundle(
                    created,
                    nnapi ? "IS-Net NNAPI + CPU" : "IS-Net CPU multi-cœurs"
            );
        } catch (Exception firstError) {
            try {
                accelerated.close();
            } catch (OrtException ignored) {
                // Rien à faire.
            }

            OrtSession.SessionOptions cpu = new OrtSession.SessionOptions();
            cpu.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            cpu.setIntraOpNumThreads(threads);
            cpu.setInterOpNumThreads(1);
            try {
                OrtSession created = environment.createSession(
                        model.getAbsolutePath(),
                        cpu
                );
                return new SessionBundle(created, "IS-Net CPU multi-cœurs");
            } finally {
                try {
                    cpu.close();
                } catch (OrtException ignored) {
                    // Rien à faire.
                }
            }
        }
    }

    private static FloatBuffer createInputBuffer(Bitmap bitmap) {
        int count = INPUT_SIZE * INPUT_SIZE;
        int[] pixels = new int[count];
        bitmap.getPixels(
                pixels,
                0,
                INPUT_SIZE,
                0,
                0,
                INPUT_SIZE,
                INPUT_SIZE
        );

        FloatBuffer buffer = ByteBuffer
                .allocateDirect(count * 3 * Float.BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();

        for (int channel = 0; channel < 3; channel++) {
            float mean = MEAN[channel];
            for (int color : pixels) {
                int component;
                if (channel == 0) {
                    component = (color >> 16) & 0xFF;
                } else if (channel == 1) {
                    component = (color >> 8) & 0xFF;
                } else {
                    component = color & 0xFF;
                }
                buffer.put(component / 255.0f - mean);
            }
        }
        buffer.rewind();
        return buffer;
    }

    private static void normalizeRobust(float[] values) {
        float minimum = Float.POSITIVE_INFINITY;
        float maximum = Float.NEGATIVE_INFINITY;
        for (float value : values) {
            if (Float.isFinite(value)) {
                minimum = Math.min(minimum, value);
                maximum = Math.max(maximum, value);
            }
        }
        float range = Math.max(1.0e-6f, maximum - minimum);
        for (int i = 0; i < values.length; i++) {
            float value = values[i];
            if (!Float.isFinite(value)) {
                value = minimum;
            }
            values[i] = clamp01((value - minimum) / range);
        }
    }

    private static File copyModelIfNeeded(Context context) throws Exception {
        File directory = new File(context.getFilesDir(), "neural_models");
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IllegalStateException(
                    "Impossible de créer le dossier des réseaux V4.1"
            );
        }

        File destination = new File(directory, MODEL_FILE);
        if (destination.isFile() && destination.length() >= MINIMUM_MODEL_BYTES) {
            return destination;
        }

        File temporary = new File(directory, MODEL_FILE + ".part");
        if (temporary.exists()) {
            temporary.delete();
        }
        try (InputStream input = new BufferedInputStream(
                context.getAssets().open(MODEL_ASSET),
                1024 * 1024
        ); BufferedOutputStream output = new BufferedOutputStream(
                new FileOutputStream(temporary),
                1024 * 1024
        )) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }

        if (temporary.length() < MINIMUM_MODEL_BYTES) {
            temporary.delete();
            throw new IllegalStateException(
                    "Le réseau IS-Net Anime embarqué est incomplet"
            );
        }
        if (destination.exists() && !destination.delete()) {
            temporary.delete();
            throw new IllegalStateException(
                    "Ancien réseau de segmentation verrouillé"
            );
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IllegalStateException(
                    "Installation de la segmentation V4.1 impossible"
            );
        }
        return destination;
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private static final class SessionBundle {
        final OrtSession session;
        final String backend;

        SessionBundle(OrtSession session, String backend) {
            this.session = session;
            this.backend = backend;
        }
    }

    public static final class Mask {
        private final float[] values;
        private final int width;
        private final int height;

        private Mask(float[] values, int width, int height) {
            this.values = values;
            this.width = width;
            this.height = height;
        }

        static Mask resize(
                float[] source,
                int sourceWidth,
                int sourceHeight,
                int width,
                int height
        ) {
            float[] output = new float[width * height];
            for (int y = 0; y < height; y++) {
                float sy = (y + 0.5f) * sourceHeight / (float) height - 0.5f;
                int y0 = Math.max(0, Math.min(sourceHeight - 1, (int) Math.floor(sy)));
                int y1 = Math.min(sourceHeight - 1, y0 + 1);
                float ty = sy - (float) Math.floor(sy);
                for (int x = 0; x < width; x++) {
                    float sx = (x + 0.5f) * sourceWidth / (float) width - 0.5f;
                    int x0 = Math.max(0, Math.min(sourceWidth - 1, (int) Math.floor(sx)));
                    int x1 = Math.min(sourceWidth - 1, x0 + 1);
                    float tx = sx - (float) Math.floor(sx);

                    float top = lerp(
                            source[y0 * sourceWidth + x0],
                            source[y0 * sourceWidth + x1],
                            tx
                    );
                    float bottom = lerp(
                            source[y1 * sourceWidth + x0],
                            source[y1 * sourceWidth + x1],
                            tx
                    );
                    output[y * width + x] = lerp(top, bottom, ty);
                }
            }
            return new Mask(output, width, height);
        }

        public float get(int x, int y) {
            if (x < 0 || y < 0 || x >= width || y >= height) {
                return 0.0f;
            }
            return values[y * width + x];
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }
    }

    private static float lerp(float first, float second, float amount) {
        return first + (second - first) * amount;
    }
}
