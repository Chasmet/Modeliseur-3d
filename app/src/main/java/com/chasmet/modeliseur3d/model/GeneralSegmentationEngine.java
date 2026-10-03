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
 * V8.1 segmentation généraliste locale IS-Net General Use.
 *
 * Le prétraitement reproduit le pipeline officiel rembg/DIS : image RGB
 * 1024x1024, normalisation par le maximum de l'image puis centrage à 0.5.
 * La sortie est remise dans [0,1] par min/max avant d'être exposée sous la
 * même forme de masque que l'ancien moteur Anime.
 */
public final class GeneralSegmentationEngine implements AutoCloseable {
    public static final String MODEL_NAME = "IS-Net General Use FP32";

    private static final String MODEL_ASSET = "models/isnet_general_use.onnx";
    private static final String MODEL_FILE = "isnet_general_use_v81.onnx";
    private static final int INPUT_SIZE = 1024;
    private static final long MINIMUM_MODEL_BYTES = 175_000_000L;

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final String inputName;
    private final String backend;

    public GeneralSegmentationEngine(Context context) throws Exception {
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
            throw new IllegalArgumentException("Image absente pour la segmentation générale");
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
                    throw new OrtException("IS-Net général ne renvoie pas de tenseur");
                }
                FloatBuffer output = ((OnnxTensor) value).getFloatBuffer();
                if (output == null || output.remaining() < INPUT_SIZE * INPUT_SIZE) {
                    throw new OrtException("Sortie IS-Net général invalide");
                }
                float[] raw = new float[INPUT_SIZE * INPUT_SIZE];
                output.get(raw, 0, raw.length);
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

        int maximum = 1;
        for (int pixel : pixels) {
            maximum = Math.max(maximum, (pixel >> 16) & 0xFF);
            maximum = Math.max(maximum, (pixel >> 8) & 0xFF);
            maximum = Math.max(maximum, pixel & 0xFF);
        }
        final float divisor = maximum;

        FloatBuffer buffer = ByteBuffer
                .allocateDirect(count * 3 * Float.BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        for (int channel = 0; channel < 3; channel++) {
            for (int color : pixels) {
                int component = channel == 0
                        ? (color >> 16) & 0xFF
                        : channel == 1 ? (color >> 8) & 0xFF : color & 0xFF;
                buffer.put(component / divisor - 0.5f);
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
            throw new IllegalArgumentException("Masque IS-Net général non fini");
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
            throw new IllegalStateException("Le réseau IS-Net General Use embarqué est incomplet");
        }
        if (destination.exists() && !destination.delete()) {
            temporary.delete();
            throw new IllegalStateException("Ancien réseau général verrouillé");
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IllegalStateException("Installation IS-Net General Use impossible");
        }
        return destination;
    }
}
