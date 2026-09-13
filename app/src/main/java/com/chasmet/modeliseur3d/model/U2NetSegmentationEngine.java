package com.chasmet.modeliseur3d.model;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

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
 * V8.2 second avis de segmentation locale avec U²-Net.
 *
 * Le prétraitement suit le modèle u2net utilisé par rembg : RGB 320x320,
 * valeurs [0,1], normalisation ImageNet, puis min/max sur la première sortie.
 * Ce moteur n'est pas destiné à remplacer IS-Net General : il apporte une
 * seconde estimation indépendante des contours pour la fusion Qualité Extrême.
 */
public final class U2NetSegmentationEngine implements AutoCloseable {
    public static final String MODEL_NAME = "U2Net General FP32";

    private static final String MODEL_ASSET = "models/u2net.onnx";
    private static final String MODEL_FILE = "u2net_v82.onnx";
    private static final int INPUT_SIZE = 320;
    private static final long MINIMUM_MODEL_BYTES = 170_000_000L;

    private static final float[] MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] STD = {0.229f, 0.224f, 0.225f};

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final String inputName;
    private final String backend;

    public U2NetSegmentationEngine(Context context) throws Exception {
        File model = copyModelIfNeeded(context.getApplicationContext());
        environment = OrtEnvironment.getEnvironment();
        int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        int threads = Math.max(2, Math.min(6, processors - 1));
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
        options.setIntraOpNumThreads(threads);
        options.setInterOpNumThreads(1);
        try {
            session = environment.createSession(model.getAbsolutePath(), options);
        } finally {
            options.close();
        }
        inputName = session.getInputNames().iterator().next();
        backend = MODEL_NAME + " • CPU " + threads + " threads";
    }

    public AnimeSegmentationEngine.Mask segment(Bitmap source) throws Exception {
        if (source == null || source.isRecycled()) {
            throw new IllegalArgumentException("Image absente pour U2Net");
        }

        Bitmap resized = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(resized);
        canvas.drawColor(Color.BLACK);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        canvas.drawBitmap(source, null, new RectF(0, 0, INPUT_SIZE, INPUT_SIZE), paint);

        FloatBuffer inputBuffer;
        try {
            inputBuffer = createInputBuffer(resized);
        } finally {
            resized.recycle();
        }

        try (OnnxTensor input = OnnxTensor.createTensor(
                environment,
                inputBuffer,
                new long[]{1, 3, INPUT_SIZE, INPUT_SIZE}
        )) {
            Map<String, OnnxTensor> inputs = Collections.singletonMap(inputName, input);
            try (OrtSession.Result result = session.run(inputs)) {
                OnnxValue value = result.get(0);
                if (!(value instanceof OnnxTensor)) {
                    throw new OrtException("U2Net ne renvoie pas de tenseur");
                }
                FloatBuffer output = ((OnnxTensor) value).getFloatBuffer();
                int expected = INPUT_SIZE * INPUT_SIZE;
                if (output == null || output.remaining() < expected) {
                    throw new OrtException("Sortie U2Net invalide");
                }
                float[] raw = new float[expected];
                output.get(raw, 0, expected);
                normalizeOutput(raw);
                return new AnimeSegmentationEngine.Mask(
                        raw,
                        INPUT_SIZE,
                        INPUT_SIZE,
                        0,
                        0,
                        INPUT_SIZE,
                        INPUT_SIZE
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
        }
    }

    private static FloatBuffer createInputBuffer(Bitmap bitmap) {
        int count = INPUT_SIZE * INPUT_SIZE;
        int[] pixels = new int[count];
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);
        FloatBuffer buffer = ByteBuffer
                .allocateDirect(count * 3 * Float.BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        for (int channel = 0; channel < 3; channel++) {
            for (int color : pixels) {
                int component = channel == 0
                        ? (color >> 16) & 0xFF
                        : channel == 1 ? (color >> 8) & 0xFF : color & 0xFF;
                float value = component / 255.0f;
                buffer.put((value - MEAN[channel]) / STD[channel]);
            }
        }
        buffer.rewind();
        return buffer;
    }

    private static void normalizeOutput(float[] values) {
        float minimum = Float.POSITIVE_INFINITY;
        float maximum = Float.NEGATIVE_INFINITY;
        for (float value : values) {
            if (!Float.isFinite(value)) {
                continue;
            }
            minimum = Math.min(minimum, value);
            maximum = Math.max(maximum, value);
        }
        if (!Float.isFinite(minimum) || !Float.isFinite(maximum)) {
            throw new IllegalArgumentException("Masque U2Net non fini");
        }
        float range = Math.max(1.0e-6f, maximum - minimum);
        for (int i = 0; i < values.length; i++) {
            float value = values[i];
            values[i] = Float.isFinite(value)
                    ? Math.max(0.0f, Math.min(1.0f, (value - minimum) / range))
                    : 0.0f;
        }
    }

    private static File copyModelIfNeeded(Context context) throws Exception {
        File directory = new File(context.getFilesDir(), "neural_models");
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IllegalStateException("Impossible de créer le dossier des réseaux locaux");
        }
        File destination = new File(directory, MODEL_FILE);
        if (destination.isFile() && destination.length() >= MINIMUM_MODEL_BYTES) {
            return destination;
        }
        File temporary = new File(directory, MODEL_FILE + ".part");
        temporary.delete();
        try (InputStream input = new BufferedInputStream(
                context.getAssets().open(MODEL_ASSET), 1024 * 1024
        ); BufferedOutputStream output = new BufferedOutputStream(
                new FileOutputStream(temporary), 1024 * 1024
        )) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
        if (temporary.length() < MINIMUM_MODEL_BYTES) {
            temporary.delete();
            throw new IllegalStateException("Le réseau U2Net embarqué est incomplet");
        }
        if (destination.exists() && !destination.delete()) {
            temporary.delete();
            throw new IllegalStateException("Ancien réseau U2Net verrouillé");
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IllegalStateException("Installation U2Net impossible");
        }
        return destination;
    }
}
