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

/** Segmentation locale IS-Net Anime FP32 avec threads adaptatifs V4.7. */
public final class AnimeSegmentationEngine implements AutoCloseable {
    public static final String MODEL_NAME = "IS-Net Anime FP32 mobile";

    private static final String MODEL_ASSET = "models/isnet_anime_fp32.onnx";
    private static final String MODEL_FILE = "isnet_anime_fp32_v47.onnx";
    private static final String LEGACY_FILE_V412 = "isnet_anime_fp32_v412.onnx";
    private static final String LEGACY_INT8_FILE = "isnet_anime_int8_v411.onnx";
    private static final int INPUT_SIZE = 1024;
    private static final long MINIMUM_MODEL_BYTES = 170_000_000L;

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final String inputName;
    private final String backend;

    public AnimeSegmentationEngine(Context context) throws Exception {
        this(
                context,
                Math.max(2, Math.min(
                        4,
                        Runtime.getRuntime().availableProcessors() - 1
                ))
        );
    }

    public AnimeSegmentationEngine(Context context, int requestedThreads)
            throws Exception {
        File model = copyModelIfNeeded(context.getApplicationContext());
        environment = OrtEnvironment.getEnvironment();
        int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        int threads = Math.max(2, Math.min(
                Math.min(8, processors),
                requestedThreads
        ));
        session = createCpuSession(model, threads);
        inputName = session.getInputNames().iterator().next();
        backend = "IS-Net Anime FP32 • CPU " + threads + " threads";
    }

    public Mask segment(Bitmap source) throws Exception {
        if (source == null || source.isRecycled()) {
            throw new IllegalArgumentException("Image absente pour la segmentation");
        }
        PreparedInput prepared = prepareInput(source);
        FloatBuffer inputBuffer;
        try {
            inputBuffer = createInputBuffer(prepared.bitmap);
        } finally {
            prepared.bitmap.recycle();
        }

        try (OnnxTensor input = OnnxTensor.createTensor(
                environment,
                inputBuffer,
                new long[]{1, 3, INPUT_SIZE, INPUT_SIZE}
        )) {
            Map<String, OnnxTensor> inputs =
                    Collections.singletonMap(inputName, input);
            try (OrtSession.Result result = session.run(inputs)) {
                OnnxValue value = result.get(0);
                if (!(value instanceof OnnxTensor)) {
                    throw new OrtException("La segmentation ne renvoie pas de tenseur");
                }
                FloatBuffer output = ((OnnxTensor) value).getFloatBuffer();
                if (output == null || output.remaining() == 0) {
                    throw new OrtException("La segmentation est vide");
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
                    float[] primary = new float[expected];
                    System.arraycopy(raw, 0, primary, 0, expected);
                    raw = primary;
                }
                sanitizeProbabilities(raw);
                return new Mask(
                        raw,
                        INPUT_SIZE,
                        INPUT_SIZE,
                        prepared.contentLeft,
                        prepared.contentTop,
                        prepared.contentWidth,
                        prepared.contentHeight
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
            // La fermeture ne doit pas interrompre l'activité.
        }
    }

    private OrtSession createCpuSession(File model, int threads) throws Exception {
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        options.setExecutionMode(
                OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL
        );
        options.setIntraOpNumThreads(threads);
        options.setInterOpNumThreads(1);
        try {
            return environment.createSession(model.getAbsolutePath(), options);
        } finally {
            options.close();
        }
    }

    private static PreparedInput prepareInput(Bitmap source) {
        float scale = Math.min(
                INPUT_SIZE / (float) source.getWidth(),
                INPUT_SIZE / (float) source.getHeight()
        );
        int contentWidth = Math.max(1, Math.round(source.getWidth() * scale));
        int contentHeight = Math.max(1, Math.round(source.getHeight() * scale));
        int contentLeft = (INPUT_SIZE - contentWidth) / 2;
        int contentTop = (INPUT_SIZE - contentHeight) / 2;
        Bitmap bitmap = Bitmap.createBitmap(
                INPUT_SIZE,
                INPUT_SIZE,
                Bitmap.Config.ARGB_8888
        );
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.BLACK);
        Paint paint = new Paint(
                Paint.ANTI_ALIAS_FLAG
                        | Paint.FILTER_BITMAP_FLAG
                        | Paint.DITHER_FLAG
        );
        canvas.drawBitmap(
                source,
                null,
                new RectF(
                        contentLeft,
                        contentTop,
                        contentLeft + contentWidth,
                        contentTop + contentHeight
                ),
                paint
        );
        return new PreparedInput(
                bitmap,
                contentLeft,
                contentTop,
                contentWidth,
                contentHeight
        );
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
            for (int color : pixels) {
                int component;
                if (channel == 0) {
                    component = (color >> 16) & 0xFF;
                } else if (channel == 1) {
                    component = (color >> 8) & 0xFF;
                } else {
                    component = color & 0xFF;
                }
                buffer.put(component / 255.0f);
            }
        }
        buffer.rewind();
        return buffer;
    }

    private static void sanitizeProbabilities(float[] values) {
        boolean finite = false;
        for (int index = 0; index < values.length; index++) {
            float value = values[index];
            if (Float.isFinite(value)) {
                finite = true;
                values[index] = clamp01(value);
            } else {
                values[index] = 0.0f;
            }
        }
        if (!finite) {
            throw new IllegalArgumentException("Masque IS-Net invalide");
        }
    }

    private static File copyModelIfNeeded(Context context) throws Exception {
        File directory = new File(context.getFilesDir(), "neural_models");
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IllegalStateException(
                    "Impossible de créer le dossier des réseaux locaux"
            );
        }
        File destination = new File(directory, MODEL_FILE);
        if (destination.isFile() && destination.length() >= MINIMUM_MODEL_BYTES) {
            deleteLegacyModels(directory);
            return destination;
        }
        File temporary = new File(directory, MODEL_FILE + ".part");
        temporary.delete();
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
                    "Le réseau IS-Net Anime FP32 embarqué est incomplet"
            );
        }
        if (destination.exists() && !destination.delete()) {
            temporary.delete();
            throw new IllegalStateException("Ancien réseau verrouillé");
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IllegalStateException(
                    "Installation de la segmentation locale impossible"
            );
        }
        deleteLegacyModels(directory);
        return destination;
    }

    private static void deleteLegacyModels(File directory) {
        new File(directory, LEGACY_FILE_V412).delete();
        new File(directory, LEGACY_INT8_FILE).delete();
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private static float lerp(float first, float second, float amount) {
        return first + (second - first) * amount;
    }

    private static final class PreparedInput {
        final Bitmap bitmap;
        final int contentLeft;
        final int contentTop;
        final int contentWidth;
        final int contentHeight;

        PreparedInput(
                Bitmap bitmap,
                int contentLeft,
                int contentTop,
                int contentWidth,
                int contentHeight
        ) {
            this.bitmap = bitmap;
            this.contentLeft = contentLeft;
            this.contentTop = contentTop;
            this.contentWidth = contentWidth;
            this.contentHeight = contentHeight;
        }
    }

    public static final class Mask {
        private final float[] values;
        private final int width;
        private final int height;
        private final int contentLeft;
        private final int contentTop;
        private final int contentWidth;
        private final int contentHeight;
        private final boolean inverted;

        Mask(
                float[] values,
                int width,
                int height,
                int contentLeft,
                int contentTop,
                int contentWidth,
                int contentHeight
        ) {
            this.values = values;
            this.width = width;
            this.height = height;
            this.contentLeft = contentLeft;
            this.contentTop = contentTop;
            this.contentWidth = contentWidth;
            this.contentHeight = contentHeight;
            this.inverted = borderAverage() > 0.52f;
        }

        public float sampleNormalized(float normalizedX, float normalizedY) {
            float x = contentLeft
                    + clamp01(normalizedX) * Math.max(1, contentWidth - 1);
            float y = contentTop
                    + clamp01(normalizedY) * Math.max(1, contentHeight - 1);
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
            float value = lerp(top, bottom, ty);
            return inverted ? 1.0f - value : value;
        }

        private float borderAverage() {
            int samples = 96;
            float sum = 0.0f;
            int count = 0;
            for (int index = 0; index < samples; index++) {
                float t = index / (float) Math.max(1, samples - 1);
                sum += rawSample(t, 0.01f);
                sum += rawSample(t, 0.99f);
                sum += rawSample(0.01f, t);
                sum += rawSample(0.99f, t);
                count += 4;
            }
            return sum / Math.max(1, count);
        }

        private float rawSample(float normalizedX, float normalizedY) {
            int x = Math.max(0, Math.min(
                    width - 1,
                    Math.round(contentLeft
                            + clamp01(normalizedX) * Math.max(1, contentWidth - 1))
            ));
            int y = Math.max(0, Math.min(
                    height - 1,
                    Math.round(contentTop
                            + clamp01(normalizedY) * Math.max(1, contentHeight - 1))
            ));
            return values[y * width + x];
        }
    }
}
