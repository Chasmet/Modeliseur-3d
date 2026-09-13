package com.chasmet.modeliseur3d.model;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;

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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * V9 Risk: EfficientViT-SAM-XL0 1024x1024, executed locally with ONNX Runtime.
 *
 * IS-Net General is deliberately used only to propose a coarse subject box.
 * EfficientViT-SAM then redraws the subject from that box prompt. This is not
 * an average of two foreground matting networks: SAM is the semantic source
 * of truth and IS-Net only protects against catastrophic leakage.
 */
public final class EfficientVitSamSegmentationEngine implements AutoCloseable {
    public static final String MODEL_NAME = "EfficientViT-SAM-XL0 1024";

    private static final String ENCODER_ASSET =
            "models/efficientvit_sam_xl0_encoder.onnx";
    private static final String DECODER_ASSET =
            "models/efficientvit_sam_xl0_decoder.onnx";
    private static final String ENCODER_FILE =
            "efficientvit_sam_xl0_encoder_v9.onnx";
    private static final String DECODER_FILE =
            "efficientvit_sam_xl0_decoder_v9.onnx";

    private static final long MIN_ENCODER_BYTES = 445_000_000L;
    private static final long MIN_DECODER_BYTES = 16_000_000L;
    private static final int INPUT_SIZE = 1024;
    private static final int FUSION_SIZE = 1024;
    private static final float[] MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] STD = {0.229f, 0.224f, 0.225f};

    private final OrtEnvironment environment;
    private final OrtSession encoder;
    private final OrtSession decoder;
    private final String encoderInput;
    private final String backend;

    public EfficientVitSamSegmentationEngine(Context context) throws Exception {
        Context app = context.getApplicationContext();
        File encoderModel = copyModelIfNeeded(
                app, ENCODER_ASSET, ENCODER_FILE, MIN_ENCODER_BYTES
        );
        File decoderModel = copyModelIfNeeded(
                app, DECODER_ASSET, DECODER_FILE, MIN_DECODER_BYTES
        );
        environment = OrtEnvironment.getEnvironment();
        int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        int threads = Math.max(2, Math.min(8, processors - 1));
        encoder = createCpuSession(encoderModel, threads);
        decoder = createCpuSession(decoderModel, Math.max(2, Math.min(6, threads)));
        encoderInput = encoder.getInputNames().iterator().next();
        backend = MODEL_NAME + " • CPU " + threads + " threads • box prompt";
    }

    public AnimeSegmentationEngine.Mask segment(
            Bitmap source,
            AnimeSegmentationEngine.Mask coarse
    ) throws Exception {
        if (source == null || source.isRecycled() || coarse == null) {
            throw new IllegalArgumentException("Entrée EfficientViT-SAM invalide");
        }

        long started = SystemClock.elapsedRealtime();
        Box box = coarseBox(coarse, source.getWidth(), source.getHeight());
        Prepared prepared = prepare(source);
        float[] embedding;
        try (OnnxTensor input = OnnxTensor.createTensor(
                environment,
                prepared.tensor,
                new long[]{1, 3, INPUT_SIZE, INPUT_SIZE}
        )) {
            try (OrtSession.Result result = encoder.run(
                    java.util.Collections.singletonMap(encoderInput, input)
            )) {
                embedding = tensorValues(result.get(0), "embedding");
            }
        }
        if (embedding.length != 256 * 64 * 64) {
            throw new OrtException(
                    "Embedding EfficientViT-SAM inattendu : " + embedding.length
            );
        }

        float sx = prepared.width / (float) Math.max(1, source.getWidth());
        float sy = prepared.height / (float) Math.max(1, source.getHeight());
        float[] coords = {
                box.left * sx, box.top * sy,
                box.right * sx, box.bottom * sy
        };
        float[] labels = {2.0f, 3.0f};

        FloatBuffer embeddingBuffer = direct(embedding);
        FloatBuffer coordsBuffer = direct(coords);
        FloatBuffer labelsBuffer = direct(labels);
        Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
        try (OnnxTensor embeddingTensor = OnnxTensor.createTensor(
                    environment, embeddingBuffer, new long[]{1, 256, 64, 64});
             OnnxTensor coordsTensor = OnnxTensor.createTensor(
                    environment, coordsBuffer, new long[]{1, 2, 2});
             OnnxTensor labelsTensor = OnnxTensor.createTensor(
                    environment, labelsBuffer, new long[]{1, 2})) {
            inputs.put("image_embeddings", embeddingTensor);
            inputs.put("point_coords", coordsTensor);
            inputs.put("point_labels", labelsTensor);

            try (OrtSession.Result result = decoder.run(inputs)) {
                float[] logits = tensorValues(result.get(0), "masque");
                float iou = result.size() > 1
                        ? firstFinite(tensorValues(result.get(1), "IoU"), 0.0f)
                        : 1.0f;
                int side = Math.round((float) Math.sqrt(logits.length));
                if (side < 32 || side * side != logits.length) {
                    throw new OrtException(
                            "Masque EfficientViT-SAM inattendu : " + logits.length
                    );
                }
                if (!Float.isFinite(iou) || iou < 0.30f) {
                    return coarse;
                }

                float[] fused = new float[FUSION_SIZE * FUSION_SIZE];
                int index = 0;
                for (int y = 0; y < FUSION_SIZE; y++) {
                    float ny = y / (float) (FUSION_SIZE - 1);
                    float py = ny * Math.max(1, prepared.height - 1);
                    float ly = py / INPUT_SIZE * side - 0.5f;
                    for (int x = 0; x < FUSION_SIZE; x++, index++) {
                        float nx = x / (float) (FUSION_SIZE - 1);
                        float px = nx * Math.max(1, prepared.width - 1);
                        float lx = px / INPUT_SIZE * side - 0.5f;
                        float sam = sigmoid(bilinear(logits, side, lx, ly));
                        float general = coarse.sampleNormalized(nx, ny);
                        fused[index] = fuseSamDominant(sam, general, iou);
                    }
                }
                return new AnimeSegmentationEngine.Mask(
                        fused,
                        FUSION_SIZE,
                        FUSION_SIZE,
                        0,
                        0,
                        FUSION_SIZE,
                        FUSION_SIZE
                );
            }
        } finally {
            inputs.clear();
            prepared.close();
            long elapsed = SystemClock.elapsedRealtime() - started;
            if (elapsed < 0) {
                throw new AssertionError("Horloge Android invalide");
            }
        }
    }

    static float fuseSamDominant(float samProbability, float coarseProbability, float iou) {
        float s = clamp01(samProbability);
        float g = clamp01(coarseProbability);
        float quality = clamp01((iou - 0.30f) / 0.55f);

        // SAM est maintenant la source principale. Une décision SAM forte peut
        // corriger IS-Net au lieu d'être ramenée vers son ancien contour.
        if (s >= 0.62f) {
            return clamp01(Math.max(
                    s * (0.94f + 0.04f * quality),
                    0.84f * s + 0.16f * g
            ));
        }
        if (s <= 0.20f) {
            // IS-Net ne sauve seul qu'un appendice extrêmement certain.
            return g >= 0.93f ? 0.54f * g : 0.12f * g;
        }
        if (g >= 0.88f && s >= 0.32f) {
            return clamp01(Math.max(s, 0.72f * g));
        }
        return clamp01(0.78f * s + 0.22f * g);
    }

    public String getBackend() {
        return backend;
    }

    @Override
    public void close() {
        try {
            decoder.close();
        } catch (OrtException ignored) {
        }
        try {
            encoder.close();
        } catch (OrtException ignored) {
        }
    }

    private static Box coarseBox(
            AnimeSegmentationEngine.Mask mask,
            int sourceWidth,
            int sourceHeight
    ) {
        final int grid = 256;
        int minX = grid;
        int minY = grid;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < grid; y++) {
            float ny = (y + 0.5f) / grid;
            for (int x = 0; x < grid; x++) {
                float nx = (x + 0.5f) / grid;
                if (mask.sampleNormalized(nx, ny) < 0.42f) {
                    continue;
                }
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
        }
        if (maxX < minX || maxY < minY) {
            return new Box(0, 0, sourceWidth - 1, sourceHeight - 1);
        }
        float left = minX / (float) grid;
        float top = minY / (float) grid;
        float right = (maxX + 1) / (float) grid;
        float bottom = (maxY + 1) / (float) grid;
        float marginX = Math.max(0.025f, (right - left) * 0.045f);
        float marginY = Math.max(0.025f, (bottom - top) * 0.045f);
        left = Math.max(0.0f, left - marginX);
        top = Math.max(0.0f, top - marginY);
        right = Math.min(1.0f, right + marginX);
        bottom = Math.min(1.0f, bottom + marginY);
        return new Box(
                left * (sourceWidth - 1),
                top * (sourceHeight - 1),
                right * (sourceWidth - 1),
                bottom * (sourceHeight - 1)
        );
    }

    private static Prepared prepare(Bitmap source) {
        float scale = INPUT_SIZE / (float) Math.max(source.getWidth(), source.getHeight());
        int width = Math.max(1, Math.round(source.getWidth() * scale));
        int height = Math.max(1, Math.round(source.getHeight() * scale));
        Bitmap resized = Bitmap.createScaledBitmap(source, width, height, true);
        int[] pixels = new int[width * height];
        resized.getPixels(pixels, 0, width, 0, 0, width, height);
        if (resized != source) {
            resized.recycle();
        }

        int plane = INPUT_SIZE * INPUT_SIZE;
        FloatBuffer buffer = ByteBuffer
                .allocateDirect(plane * 3 * Float.BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        for (int channel = 0; channel < 3; channel++) {
            int shift = channel == 0 ? 16 : channel == 1 ? 8 : 0;
            for (int y = 0; y < INPUT_SIZE; y++) {
                for (int x = 0; x < INPUT_SIZE; x++) {
                    if (x >= width || y >= height) {
                        buffer.put(0.0f);
                    } else {
                        int color = pixels[y * width + x];
                        float value = ((color >> shift) & 0xFF) / 255.0f;
                        buffer.put((value - MEAN[channel]) / STD[channel]);
                    }
                }
            }
        }
        buffer.rewind();
        return new Prepared(buffer, width, height);
    }

    private static OrtSession createCpuSession(File model, int threads) throws Exception {
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
        options.setIntraOpNumThreads(threads);
        options.setInterOpNumThreads(1);
        try {
            return OrtEnvironment.getEnvironment().createSession(
                    model.getAbsolutePath(), options
            );
        } finally {
            options.close();
        }
    }

    private static File copyModelIfNeeded(
            Context context,
            String asset,
            String name,
            long minimumBytes
    ) throws Exception {
        File directory = new File(context.getFilesDir(), "neural_models");
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IllegalStateException("Dossier neuronal inaccessible");
        }
        File destination = new File(directory, name);
        if (destination.isFile() && destination.length() >= minimumBytes) {
            return destination;
        }
        File temporary = new File(directory, name + ".part");
        temporary.delete();
        try (InputStream input = new BufferedInputStream(
                context.getAssets().open(asset), 1024 * 1024
        ); BufferedOutputStream output = new BufferedOutputStream(
                new FileOutputStream(temporary), 1024 * 1024
        )) {
            byte[] chunk = new byte[1024 * 1024];
            int read;
            while ((read = input.read(chunk)) != -1) {
                output.write(chunk, 0, read);
            }
        }
        if (temporary.length() < minimumBytes) {
            temporary.delete();
            throw new IllegalStateException("Modèle EfficientViT-SAM incomplet : " + name);
        }
        if (destination.exists() && !destination.delete()) {
            temporary.delete();
            throw new IllegalStateException("Ancien modèle SAM verrouillé");
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IllegalStateException("Installation EfficientViT-SAM impossible");
        }
        return destination;
    }

    private static float[] tensorValues(OnnxValue value, String label)
            throws OrtException {
        if (!(value instanceof OnnxTensor)) {
            throw new OrtException("Sortie SAM " + label + " invalide");
        }
        FloatBuffer buffer = ((OnnxTensor) value).getFloatBuffer();
        if (buffer == null || buffer.remaining() == 0) {
            throw new OrtException("Sortie SAM " + label + " vide");
        }
        float[] values = new float[buffer.remaining()];
        buffer.get(values);
        return values;
    }

    private static FloatBuffer direct(float[] values) {
        FloatBuffer buffer = ByteBuffer
                .allocateDirect(values.length * Float.BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        buffer.put(values);
        buffer.rewind();
        return buffer;
    }

    private static float bilinear(float[] values, int side, float x, float y) {
        x = Math.max(0.0f, Math.min(side - 1.0f, x));
        y = Math.max(0.0f, Math.min(side - 1.0f, y));
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int x1 = Math.min(side - 1, x0 + 1);
        int y1 = Math.min(side - 1, y0 + 1);
        float tx = x - x0;
        float ty = y - y0;
        float top = values[y0 * side + x0] * (1.0f - tx)
                + values[y0 * side + x1] * tx;
        float bottom = values[y1 * side + x0] * (1.0f - tx)
                + values[y1 * side + x1] * tx;
        return top * (1.0f - ty) + bottom * ty;
    }

    private static float sigmoid(float value) {
        if (value >= 0.0f) {
            double exp = Math.exp(-value);
            return (float) (1.0 / (1.0 + exp));
        }
        double exp = Math.exp(value);
        return (float) (exp / (1.0 + exp));
    }

    private static float firstFinite(float[] values, float fallback) {
        for (float value : values) {
            if (Float.isFinite(value)) {
                return value;
            }
        }
        return fallback;
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private static final class Prepared implements AutoCloseable {
        final FloatBuffer tensor;
        final int width;
        final int height;

        Prepared(FloatBuffer tensor, int width, int height) {
            this.tensor = tensor;
            this.width = width;
            this.height = height;
        }

        @Override
        public void close() {
            // DirectBuffer is released by the VM after inference.
        }
    }

    private static final class Box {
        final float left;
        final float top;
        final float right;
        final float bottom;

        Box(float left, float top, float right, float bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }
}
