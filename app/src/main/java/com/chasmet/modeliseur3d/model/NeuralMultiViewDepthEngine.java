package com.chasmet.modeliseur3d.model;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
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
import java.util.Collections;
import java.util.Map;

/**
 * Depth Anything 3 Small, four-view edition, executed entirely on Android.
 *
 * <p>The ONNX graph keeps DA3's cross-view attention: face, right, back and
 * left are processed together instead of four unrelated monocular passes.
 * The graph is generated in CI from the pinned Apache-2.0 checkpoint.</p>
 */
public final class NeuralMultiViewDepthEngine implements AutoCloseable {
    public static final String MODEL_NAME = "Depth Anything 3 Small multivue";

    private static final String MODEL_ASSET =
            "models/da3_small_four_view_224.onnx";
    private static final String MODEL_FILE =
            "da3_small_four_view_224_v7_2.onnx";
    private static final int VIEW_COUNT = 4;
    private static final int INPUT_SIZE = 224;
    private static final int CONTENT_MARGIN = 7;
    private static final long MINIMUM_MODEL_BYTES = 60_000_000L;

    private final OrtEnvironment environment;
    private final File model;
    private OrtSession session;
    private final String inputName;
    private String backend;

    public NeuralMultiViewDepthEngine(Context context) throws Exception {
        Context applicationContext = context.getApplicationContext();
        model = copyModelIfNeeded(applicationContext);
        environment = OrtEnvironment.getEnvironment();
        SessionBundle bundle = createSession(model);
        session = bundle.session;
        backend = bundle.backend;
        inputName = session.getInputNames().iterator().next();
    }

    public Prediction estimate(
            Bitmap[] sourceViews,
            int[] targetWidths,
            int targetHeight
    ) throws Exception {
        validate(sourceViews, targetWidths, targetHeight);
        long started = SystemClock.elapsedRealtime();
        PreparedInput[] prepared = new PreparedInput[VIEW_COUNT];
        try {
            for (int view = 0; view < VIEW_COUNT; view++) {
                prepared[view] = prepareInput(sourceViews[view]);
            }
            FloatBuffer inputBuffer = createInputBuffer(prepared);
            long[] shape = {1, VIEW_COUNT, 3, INPUT_SIZE, INPUT_SIZE};
            try (OnnxTensor input = OnnxTensor.createTensor(
                    environment,
                    inputBuffer,
                    shape
            )) {
                Map<String, OnnxTensor> inputs =
                        Collections.singletonMap(inputName, input);
                RawOutputs rawOutputs = runRawWithCpuFallback(inputs);
                float[] rawDepth = rawOutputs.depth;
                float[] rawConfidence = rawOutputs.confidence;
                int pixelsPerView = rawDepth.length / VIEW_COUNT;
                int outputSide = Math.round((float) Math.sqrt(pixelsPerView));
                if (pixelsPerView <= 0
                        || outputSide * outputSide != pixelsPerView
                        || rawDepth.length != pixelsPerView * VIEW_COUNT) {
                    throw new OrtException(
                            "Dimensions DA3 inattendues : " + rawDepth.length
                    );
                }
                if (rawConfidence != null
                        && rawConfidence.length != rawDepth.length) {
                    rawConfidence = null;
                }

                float[][] depth = new float[VIEW_COUNT][];
                float[][] confidence = new float[VIEW_COUNT][];
                for (int view = 0; view < VIEW_COUNT; view++) {
                    depth[view] = resampleOutput(
                            rawDepth,
                            view * pixelsPerView,
                            outputSide,
                            prepared[view],
                            targetWidths[view],
                            targetHeight,
                            false
                    );
                    if (rawConfidence == null) {
                        confidence[view] = filled(
                                targetWidths[view] * targetHeight,
                                1.0f
                        );
                    } else {
                        confidence[view] = resampleOutput(
                                rawConfidence,
                                view * pixelsPerView,
                                outputSide,
                                prepared[view],
                                targetWidths[view],
                                targetHeight,
                                true
                        );
                    }
                }
                return new Prediction(
                        depth,
                        confidence,
                        backend,
                        SystemClock.elapsedRealtime() - started
                );
            }
        } finally {
            for (PreparedInput input : prepared) {
                if (input != null) {
                    input.close();
                }
            }
        }
    }

    public String getBackend() {
        return backend;
    }

    @Override
    public synchronized void close() {
        try {
            session.close();
        } catch (OrtException ignored) {
            // Closing an accelerator session must never crash the activity.
        }
    }

    private synchronized RawOutputs runRawWithCpuFallback(
            Map<String, OnnxTensor> inputs
    ) throws OrtException {
        try {
            RawOutputs outputs = runRaw(inputs);
            validateNeuralSignal(outputs.depth);
            return outputs;
        } catch (OrtException acceleratedFailure) {
            if (!backend.contains("NNAPI")) {
                throw acceleratedFailure;
            }
            switchToCpu();
            try {
                RawOutputs outputs = runRaw(inputs);
                validateNeuralSignal(outputs.depth);
                return outputs;
            } catch (OrtException cpuFailure) {
                cpuFailure.addSuppressed(acceleratedFailure);
                throw cpuFailure;
            }
        }
    }

    private RawOutputs runRaw(Map<String, OnnxTensor> inputs)
            throws OrtException {
        try (OrtSession.Result outputs = session.run(inputs)) {
            float[] depth = tensorValues(outputs.get(0), "profondeur");
            float[] confidence = outputs.size() > 1
                    ? tensorValues(outputs.get(1), "confiance")
                    : null;
            return new RawOutputs(depth, confidence);
        }
    }

    private static void validateNeuralSignal(float[] depth) throws OrtException {
        int finite = 0;
        float minimum = Float.POSITIVE_INFINITY;
        float maximum = Float.NEGATIVE_INFINITY;
        for (float value : depth) {
            if (!Float.isFinite(value)) {
                continue;
            }
            finite++;
            minimum = Math.min(minimum, value);
            maximum = Math.max(maximum, value);
        }
        float scale = Math.max(1.0f, Math.max(Math.abs(minimum), Math.abs(maximum)));
        if (finite < depth.length * 0.75f
                || !Float.isFinite(minimum)
                || !Float.isFinite(maximum)
                || maximum - minimum <= scale * 1.0e-6f) {
            throw new OrtException("Sortie DA3 vide ou plate");
        }
    }

    private void switchToCpu() throws OrtException {
        try {
            session.close();
        } catch (OrtException ignored) {
            // The fresh CPU session below is independent from NNAPI state.
        }
        int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        int threads = Math.max(2, Math.min(8, processors - 1));
        OrtSession.SessionOptions cpu = options(threads);
        try {
            session = environment.createSession(model.getAbsolutePath(), cpu);
            backend = "DA3 CPU multi-cœurs après repli NNAPI";
        } finally {
            cpu.close();
        }
    }

    private SessionBundle createSession(File model) throws Exception {
        int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        int threads = Math.max(2, Math.min(8, processors - 1));
        OrtSession.SessionOptions accelerated = options(threads);
        boolean nnapi = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1;
        if (nnapi) {
            try {
                accelerated.addNnapi();
            } catch (OrtException ignored) {
                nnapi = false;
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
                    nnapi ? "DA3 NNAPI + CPU" : "DA3 CPU multi-cœurs"
            );
        } catch (Exception acceleratorError) {
            accelerated.close();
            OrtSession.SessionOptions cpu = options(threads);
            try {
                return new SessionBundle(
                        environment.createSession(model.getAbsolutePath(), cpu),
                        "DA3 CPU multi-cœurs"
                );
            } finally {
                cpu.close();
            }
        }
    }

    private static OrtSession.SessionOptions options(int threads)
            throws OrtException {
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        options.setIntraOpNumThreads(threads);
        options.setInterOpNumThreads(1);
        return options;
    }

    private static PreparedInput prepareInput(Bitmap source) {
        Bitmap bitmap = Bitmap.createBitmap(
                INPUT_SIZE,
                INPUT_SIZE,
                Bitmap.Config.ARGB_8888
        );
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.rgb(127, 127, 127));
        float available = INPUT_SIZE - CONTENT_MARGIN * 2.0f;
        float scale = Math.min(
                available / Math.max(1.0f, source.getWidth()),
                available / Math.max(1.0f, source.getHeight())
        );
        int width = Math.max(1, Math.round(source.getWidth() * scale));
        int height = Math.max(1, Math.round(source.getHeight() * scale));
        int left = (INPUT_SIZE - width) / 2;
        int top = (INPUT_SIZE - height) / 2;
        Paint paint = new Paint(
                Paint.ANTI_ALIAS_FLAG
                        | Paint.FILTER_BITMAP_FLAG
                        | Paint.DITHER_FLAG
        );
        canvas.drawBitmap(
                source,
                null,
                new RectF(left, top, left + width, top + height),
                paint
        );
        return new PreparedInput(bitmap, left, top, width, height);
    }

    private static FloatBuffer createInputBuffer(PreparedInput[] prepared) {
        int pixelsPerView = INPUT_SIZE * INPUT_SIZE;
        FloatBuffer buffer = ByteBuffer.allocateDirect(
                        VIEW_COUNT * 3 * pixelsPerView * Float.BYTES
                )
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        int[] pixels = new int[pixelsPerView];
        for (PreparedInput input : prepared) {
            input.bitmap.getPixels(
                    pixels,
                    0,
                    INPUT_SIZE,
                    0,
                    0,
                    INPUT_SIZE,
                    INPUT_SIZE
            );
            for (int channel = 0; channel < 3; channel++) {
                int shift = channel == 0 ? 16 : channel == 1 ? 8 : 0;
                for (int color : pixels) {
                    buffer.put(((color >> shift) & 0xFF) / 255.0f);
                }
            }
        }
        buffer.rewind();
        return buffer;
    }

    private static float[] tensorValues(OnnxValue value, String label)
            throws OrtException {
        if (!(value instanceof OnnxTensor)) {
            throw new OrtException("Sortie DA3 " + label + " invalide");
        }
        FloatBuffer buffer = ((OnnxTensor) value).getFloatBuffer();
        if (buffer == null || buffer.remaining() == 0) {
            throw new OrtException("Sortie DA3 " + label + " vide");
        }
        float[] values = new float[buffer.remaining()];
        buffer.get(values);
        return values;
    }

    private static float[] resampleOutput(
            float[] source,
            int sourceOffset,
            int sourceSide,
            PreparedInput mapping,
            int targetWidth,
            int targetHeight,
            boolean sanitizeConfidence
    ) {
        float[] output = new float[targetWidth * targetHeight];
        for (int y = 0; y < targetHeight; y++) {
            float contentY = mapping.top
                    + (y + 0.5f) * mapping.height / targetHeight;
            float sourceY = contentY / INPUT_SIZE * sourceSide - 0.5f;
            for (int x = 0; x < targetWidth; x++) {
                float contentX = mapping.left
                        + (x + 0.5f) * mapping.width / targetWidth;
                float sourceX = contentX / INPUT_SIZE * sourceSide - 0.5f;
                float value = bilinear(
                        source,
                        sourceOffset,
                        sourceSide,
                        sourceX,
                        sourceY
                );
                if (!Float.isFinite(value)) {
                    value = sanitizeConfidence ? 0.0f : Float.NaN;
                }
                output[y * targetWidth + x] = value;
            }
        }
        return output;
    }

    private static float bilinear(
            float[] source,
            int offset,
            int side,
            float x,
            float y
    ) {
        x = clamp(x, 0.0f, side - 1.0f);
        y = clamp(y, 0.0f, side - 1.0f);
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int x1 = Math.min(side - 1, x0 + 1);
        int y1 = Math.min(side - 1, y0 + 1);
        float tx = x - x0;
        float ty = y - y0;
        float top = source[offset + y0 * side + x0] * (1.0f - tx)
                + source[offset + y0 * side + x1] * tx;
        float bottom = source[offset + y1 * side + x0] * (1.0f - tx)
                + source[offset + y1 * side + x1] * tx;
        return top * (1.0f - ty) + bottom * ty;
    }

    private static float[] filled(int size, float value) {
        float[] output = new float[size];
        java.util.Arrays.fill(output, value);
        return output;
    }

    private static void validate(
            Bitmap[] views,
            int[] targetWidths,
            int targetHeight
    ) {
        if (views == null || views.length != VIEW_COUNT
                || targetWidths == null || targetWidths.length != VIEW_COUNT
                || targetHeight < 4) {
            throw new IllegalArgumentException("Entrées multivues DA3 invalides");
        }
        for (int view = 0; view < VIEW_COUNT; view++) {
            if (views[view] == null || views[view].isRecycled()
                    || targetWidths[view] < 4) {
                throw new IllegalArgumentException("Vue DA3 " + view + " invalide");
            }
        }
    }

    private static File copyModelIfNeeded(Context context) throws Exception {
        File directory = new File(context.getFilesDir(), "neural_models");
        if (!directory.exists()
                && !directory.mkdirs()
                && !directory.isDirectory()) {
            throw new IllegalStateException("Dossier neuronal inaccessible");
        }
        File destination = new File(directory, MODEL_FILE);
        if (destination.isFile() && destination.length() >= MINIMUM_MODEL_BYTES) {
            return destination;
        }
        File temporary = new File(directory, MODEL_FILE + ".part");
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
            throw new IllegalStateException("Modèle DA3 incomplet");
        }
        if (destination.exists() && !destination.delete()) {
            temporary.delete();
            throw new IllegalStateException("Ancien modèle DA3 verrouillé");
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IllegalStateException("Installation DA3 impossible");
        }
        return destination;
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static final class SessionBundle {
        final OrtSession session;
        final String backend;

        SessionBundle(OrtSession session, String backend) {
            this.session = session;
            this.backend = backend;
        }
    }

    private static final class RawOutputs {
        final float[] depth;
        final float[] confidence;

        RawOutputs(float[] depth, float[] confidence) {
            this.depth = depth;
            this.confidence = confidence;
        }
    }

    private static final class PreparedInput implements AutoCloseable {
        final Bitmap bitmap;
        final int left;
        final int top;
        final int width;
        final int height;

        PreparedInput(Bitmap bitmap, int left, int top, int width, int height) {
            this.bitmap = bitmap;
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
        }

        @Override
        public void close() {
            if (!bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
    }

    public static final class Prediction {
        private final float[][] depth;
        private final float[][] confidence;
        private final String backend;
        private final long durationMs;

        Prediction(
                float[][] depth,
                float[][] confidence,
                String backend,
                long durationMs
        ) {
            this.depth = depth;
            this.confidence = confidence;
            this.backend = backend;
            this.durationMs = durationMs;
        }

        public float[][] getDepth() {
            return depth;
        }

        public float[][] getConfidence() {
            return confidence;
        }

        public String getBackend() {
            return backend;
        }

        public long getDurationMs() {
            return durationMs;
        }
    }
}
