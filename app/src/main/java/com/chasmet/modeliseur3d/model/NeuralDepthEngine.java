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
 * Moteur de profondeur neuronale entièrement local.
 *
 * Le modèle embarqué est Depth Anything V2 Small FP32 (Apache-2.0).
 * L'inférence est exécutée par ONNX Runtime. NNAPI est activé sur les appareils
 * compatibles afin d'utiliser le NPU/GPU/accélérateur Android, avec repli CPU.
 */
public final class NeuralDepthEngine implements AutoCloseable {
    public static final String MODEL_NAME = "Depth Anything V2 Small FP32";

    private static final String MODEL_ASSET =
            "models/depth_anything_v2_small_fp32.onnx";
    private static final String MODEL_FILE =
            "depth_anything_v2_small_fp32_v4.onnx";
    private static final int INPUT_SIZE = 518;
    private static final float[] MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] STD = {0.229f, 0.224f, 0.225f};

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final String inputName;
    private final String backend;

    public NeuralDepthEngine(Context context) throws Exception {
        Context applicationContext = context.getApplicationContext();
        File model = copyModelIfNeeded(applicationContext);
        environment = OrtEnvironment.getEnvironment();

        SessionBundle bundle = createSession(model);
        session = bundle.session;
        backend = bundle.backend;
        inputName = session.getInputNames().iterator().next();
    }

    public DepthMap estimate(Bitmap source) throws Exception {
        if (source == null || source.isRecycled()) {
            throw new IllegalArgumentException("Image neuronale absente");
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
            try (OrtSession.Result outputs = session.run(inputs)) {
                OnnxValue value = outputs.get(0);
                if (!(value instanceof OnnxTensor)) {
                    throw new OrtException("La sortie du réseau n'est pas un tenseur");
                }
                FloatBuffer output = ((OnnxTensor) value).getFloatBuffer();
                if (output == null || output.remaining() == 0) {
                    throw new OrtException("La carte de profondeur est vide");
                }

                float[] raw = new float[output.remaining()];
                output.get(raw);
                int side = Math.round((float) Math.sqrt(raw.length));
                if (side * side != raw.length) {
                    if (raw.length >= INPUT_SIZE * INPUT_SIZE) {
                        float[] tail = new float[INPUT_SIZE * INPUT_SIZE];
                        System.arraycopy(
                                raw,
                                raw.length - tail.length,
                                tail,
                                0,
                                tail.length
                        );
                        raw = tail;
                        side = INPUT_SIZE;
                    } else {
                        throw new OrtException(
                                "Dimensions de profondeur inattendues : " + raw.length
                        );
                    }
                }
                return DepthMap.fromRaw(raw, side, side);
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
            // La fermeture ne doit jamais faire planter l'activité Android.
        }
    }

    private SessionBundle createSession(File model) throws Exception {
        int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        int neuralThreads = Math.max(2, Math.min(10, processors - 1));

        OrtSession.SessionOptions accelerated = new OrtSession.SessionOptions();
        accelerated.setOptimizationLevel(
                OrtSession.SessionOptions.OptLevel.ALL_OPT
        );
        accelerated.setIntraOpNumThreads(neuralThreads);
        accelerated.setInterOpNumThreads(1);

        boolean nnapiRequested = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1;
        if (nnapiRequested) {
            try {
                accelerated.addNnapi();
            } catch (OrtException ignored) {
                nnapiRequested = false;
            }
        }

        try {
            OrtSession acceleratedSession = environment.createSession(
                    model.getAbsolutePath(),
                    accelerated
            );
            accelerated.close();
            return new SessionBundle(
                    acceleratedSession,
                    nnapiRequested ? "NNAPI + CPU" : "CPU multi-cœurs"
            );
        } catch (Exception acceleratedError) {
            accelerated.close();

            OrtSession.SessionOptions cpu = new OrtSession.SessionOptions();
            cpu.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            cpu.setIntraOpNumThreads(neuralThreads);
            cpu.setInterOpNumThreads(1);
            try {
                OrtSession cpuSession = environment.createSession(
                        model.getAbsolutePath(),
                        cpu
                );
                return new SessionBundle(cpuSession, "CPU multi-cœurs");
            } finally {
                cpu.close();
            }
        }
    }

    private static FloatBuffer createInputBuffer(Bitmap bitmap) {
        int pixelCount = INPUT_SIZE * INPUT_SIZE;
        int[] pixels = new int[pixelCount];
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
                .allocateDirect(pixelCount * 3 * Float.BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();

        for (int channel = 0; channel < 3; channel++) {
            float mean = MEAN[channel];
            float std = STD[channel];
            for (int color : pixels) {
                int component;
                if (channel == 0) {
                    component = (color >> 16) & 0xFF;
                } else if (channel == 1) {
                    component = (color >> 8) & 0xFF;
                } else {
                    component = color & 0xFF;
                }
                float normalized = (component / 255.0f - mean) / std;
                buffer.put(normalized);
            }
        }
        buffer.rewind();
        return buffer;
    }

    private static File copyModelIfNeeded(Context context) throws Exception {
        File modelDirectory = new File(context.getFilesDir(), "neural_models");
        if (!modelDirectory.exists()
                && !modelDirectory.mkdirs()
                && !modelDirectory.isDirectory()) {
            throw new IllegalStateException(
                    "Impossible de créer le dossier du moteur neuronal"
            );
        }

        File destination = new File(modelDirectory, MODEL_FILE);
        if (destination.isFile() && destination.length() > 90_000_000L) {
            return destination;
        }

        File temporary = new File(modelDirectory, MODEL_FILE + ".part");
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

        if (temporary.length() < 90_000_000L) {
            temporary.delete();
            throw new IllegalStateException("Le modèle neuronal embarqué est incomplet");
        }
        if (destination.exists() && !destination.delete()) {
            temporary.delete();
            throw new IllegalStateException("Ancien modèle neuronal verrouillé");
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IllegalStateException("Installation du modèle neuronal impossible");
        }
        return destination;
    }

    private static final class SessionBundle {
        final OrtSession session;
        final String backend;

        SessionBundle(OrtSession session, String backend) {
            this.session = session;
            this.backend = backend;
        }
    }

    public static final class DepthMap {
        private final float[] values;
        private final int width;
        private final int height;

        private DepthMap(float[] values, int width, int height) {
            this.values = values;
            this.width = width;
            this.height = height;
        }

        static DepthMap fromRaw(float[] raw, int width, int height) {
            float[] sample = new float[(raw.length + 15) / 16];
            int sampleCount = 0;
            for (int i = 0; i < raw.length; i += 16) {
                float value = raw[i];
                if (Float.isFinite(value)) {
                    sample[sampleCount++] = value;
                }
            }
            if (sampleCount < 32) {
                throw new IllegalArgumentException("Profondeur neuronale invalide");
            }
            java.util.Arrays.sort(sample, 0, sampleCount);
            float low = sample[Math.max(0, Math.round((sampleCount - 1) * 0.04f))];
            float high = sample[Math.max(0, Math.round((sampleCount - 1) * 0.96f))];
            float range = Math.max(1.0e-6f, high - low);

            float[] normalized = new float[raw.length];
            for (int i = 0; i < raw.length; i++) {
                float value = raw[i];
                if (!Float.isFinite(value)) {
                    value = low;
                }
                normalized[i] = clamp01((value - low) / range);
            }
            blur(normalized, width, height, 2);
            return new DepthMap(normalized, width, height);
        }

        public float sample(float normalizedX, float normalizedY) {
            float x = clamp01(normalizedX) * (width - 1);
            float y = clamp01(normalizedY) * (height - 1);
            int x0 = Math.max(0, Math.min(width - 1, (int) Math.floor(x)));
            int y0 = Math.max(0, Math.min(height - 1, (int) Math.floor(y)));
            int x1 = Math.min(width - 1, x0 + 1);
            int y1 = Math.min(height - 1, y0 + 1);
            float tx = x - x0;
            float ty = y - y0;

            float top = lerp(
                    values[y0 * width + x0],
                    values[y0 * width + x1],
                    tx
            );
            float bottom = lerp(
                    values[y1 * width + x0],
                    values[y1 * width + x1],
                    tx
            );
            return lerp(top, bottom, ty);
        }

        private static void blur(
                float[] values,
                int width,
                int height,
                int passes
        ) {
            float[] temporary = new float[values.length];
            for (int pass = 0; pass < passes; pass++) {
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        float sum = 0.0f;
                        float weight = 0.0f;
                        float center = values[y * width + x];
                        for (int offsetY = -1; offsetY <= 1; offsetY++) {
                            int sampleY = Math.max(
                                    0,
                                    Math.min(height - 1, y + offsetY)
                            );
                            for (int offsetX = -1; offsetX <= 1; offsetX++) {
                                int sampleX = Math.max(
                                        0,
                                        Math.min(width - 1, x + offsetX)
                                );
                                float current = values[sampleY * width + sampleX];
                                float rangeWeight = 1.0f
                                        / (1.0f + Math.abs(current - center) * 10.0f);
                                float spatialWeight = offsetX == 0 && offsetY == 0
                                        ? 2.0f
                                        : 1.0f;
                                float currentWeight = rangeWeight * spatialWeight;
                                sum += current * currentWeight;
                                weight += currentWeight;
                            }
                        }
                        temporary[y * width + x] = sum / Math.max(1.0e-6f, weight);
                    }
                }
                System.arraycopy(
                        temporary,
                        0,
                        values,
                        0,
                        values.length
                );
            }
        }

        private static float lerp(float first, float second, float amount) {
            return first + (second - first) * amount;
        }

        private static float clamp01(float value) {
            return Math.max(0.0f, Math.min(1.0f, value));
        }
    }
}
