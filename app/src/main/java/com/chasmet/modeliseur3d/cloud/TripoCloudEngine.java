package com.chasmet.modeliseur3d.cloud;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Environment;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Client Android Java pour la génération Tripo H3.1 haute fidélité.
 *
 * La clé fournie par l'utilisateur n'est jamais journalisée. L'appel direct
 * convient à l'application personnelle de test ; une publication publique
 * pourra remplacer ce client par un relais serveur sans modifier le format GLB.
 */
public final class TripoCloudEngine {
    public static final String MODEL_VERSION = "v3.1-20260211";
    public static final int MAXIMUM_FACE_COUNT = 100_000;

    private static final String API_BASE = "https://openapi.tripo3d.ai/v3";
    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int REQUEST_TIMEOUT_MS = 120_000;
    private static final int POLL_INTERVAL_MS = 2_000;
    private static final long MAXIMUM_TASK_DURATION_MS = 15L * 60L * 1000L;
    private static final long MAXIMUM_MODEL_BYTES = 350L * 1024L * 1024L;
    private static final int MAXIMUM_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final Set<String> VALID_ROLES = new HashSet<>();

    static {
        VALID_ROLES.add("front");
        VALID_ROLES.add("left");
        VALID_ROLES.add("back");
        VALID_ROLES.add("right");
    }

    private final Context context;
    private final String apiKey;

    public TripoCloudEngine(Context context, String apiKey) {
        this.context = context.getApplicationContext();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        if (this.apiKey.isEmpty()) {
            throw new IllegalArgumentException("Clé API Tripo absente");
        }
    }

    public Result generate(
            List<InputView> inputViews,
            ProgressListener listener
    ) throws Exception {
        validateViews(inputViews);
        long started = SystemClock.elapsedRealtime();

        List<UploadedView> uploads = new ArrayList<>();
        for (int index = 0; index < inputViews.size(); index++) {
            ensureNotCancelled();
            notifyProgress(
                    listener,
                    Stage.UPLOADING,
                    Math.round(index * 100.0f / inputViews.size())
            );
            InputView view = inputViews.get(index);
            uploads.add(new UploadedView(
                    view.role,
                    uploadImage(view.bitmap, index)
            ));
        }
        notifyProgress(listener, Stage.UPLOADING, 100);

        String taskId = createGenerationTask(uploads);
        TaskResult task = waitForTask(
                taskId,
                listener,
                Stage.GENERATING
        );
        File outputDirectory = createOutputDirectory();

        notifyProgress(listener, Stage.DOWNLOADING_HD, 0);
        File highDefinitionFile = downloadModel(
                task.modelUrl,
                outputDirectory,
                "personnage_v43_h31_hd.glb"
        );
        validateGlb(highDefinitionFile);
        notifyProgress(listener, Stage.DOWNLOADING_HD, 100);

        MobileOutcome mobile = createMobileVariant(
                taskId,
                outputDirectory,
                listener
        );

        return new Result(
                highDefinitionFile,
                mobile.file,
                taskId,
                inputViews.size(),
                task.creditsConsumed + mobile.creditsConsumed,
                SystemClock.elapsedRealtime() - started,
                MODEL_VERSION,
                mobile.presetLabel
        );
    }

    private String uploadImage(Bitmap source, int index) throws Exception {
        if (source == null || source.isRecycled()) {
            throw new IllegalArgumentException("Vue " + (index + 1) + " invalide");
        }

        String boundary = "----Modeliseur3DV43" + UUID.randomUUID();
        HttpURLConnection connection = openConnection(
                new URL(API_BASE + "/files"),
                "POST"
        );
        connection.setDoOutput(true);
        connection.setChunkedStreamingMode(256 * 1024);
        connection.setRequestProperty(
                "Content-Type",
                "multipart/form-data; boundary=" + boundary
        );
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);

        try {
            try (OutputStream output = new BufferedOutputStream(
                    connection.getOutputStream(),
                    256 * 1024
            )) {
                writeAscii(output, "--" + boundary + "\r\n");
                writeAscii(
                        output,
                        "Content-Disposition: form-data; name=\"file\"; "
                                + "filename=\"vue_" + (index + 1)
                                + ".jpg\"\r\n"
                );
                writeAscii(output, "Content-Type: image/jpeg\r\n\r\n");
                writeJpegOnWhite(source, output);
                writeAscii(output, "\r\n--" + boundary + "--\r\n");
            }

            JSONObject response = readJsonResponse(connection);
            JSONObject data = requireSuccessData(response);
            String token = data.optString("file_token", "").trim();
            if (token.isEmpty()) {
                throw new IOException("Tripo n'a pas renvoyé le jeton de la vue");
            }
            return token;
        } finally {
            connection.disconnect();
        }
    }

    private String createGenerationTask(List<UploadedView> uploads)
            throws Exception {
        JSONObject body = commonGenerationOptions();
        String endpoint;
        if (uploads.size() == 1) {
            endpoint = "/generation/image-to-model";
            body.put("input", uploads.get(0).fileToken);
            body.put("enable_image_autofix", true);
            body.put("orientation", "align_image");
        } else {
            endpoint = "/generation/multiview-to-model";
            JSONArray inputs = new JSONArray();
            for (UploadedView upload : uploads) {
                JSONObject value = new JSONObject();
                value.put(upload.role, upload.fileToken);
                inputs.put(value);
            }
            body.put("inputs", inputs);
            body.put("enable_image_autofix", true);
        }

        JSONObject response = requestJson(
                "POST",
                API_BASE + endpoint,
                body
        );
        JSONObject data = requireSuccessData(response);
        String taskId = data.optString("task_id", "").trim();
        if (taskId.isEmpty()) {
            throw new IOException("Tripo n'a pas créé de tâche 3D");
        }
        return taskId;
    }

    private static JSONObject commonGenerationOptions() throws JSONException {
        JSONObject body = new JSONObject();
        body.put("model", MODEL_VERSION);
        body.put("face_limit", MAXIMUM_FACE_COUNT);
        body.put("texture", true);
        body.put("pbr", true);
        body.put("texture_alignment", "original_image");
        body.put("texture_quality", "detailed");
        body.put("geometry_quality", "detailed");
        body.put("auto_size", true);
        body.put("quad", false);
        body.put("smart_low_poly", false);
        body.put("generate_parts", false);
        body.put("compress", "geometry");
        body.put("export_uv", true);
        return body;
    }

    private TaskResult waitForTask(
            String taskId,
            ProgressListener listener,
            Stage progressStage
    ) throws Exception {
        long started = SystemClock.elapsedRealtime();
        int lastProgress = -1;

        while (SystemClock.elapsedRealtime() - started
                < MAXIMUM_TASK_DURATION_MS) {
            ensureNotCancelled();
            JSONObject response = requestJson(
                    "GET",
                    API_BASE + "/tasks/" + taskId,
                    null
            );
            JSONObject data = requireSuccessData(response);
            String status = data.optString("status", "").toLowerCase(Locale.ROOT);
            int progress = Math.max(0, Math.min(100,
                    data.optInt("progress", 0)));
            if (progress != lastProgress) {
                notifyProgress(listener, progressStage, progress);
                lastProgress = progress;
            }

            if ("success".equals(status)) {
                JSONObject output = data.optJSONObject("output");
                String modelUrl = findModelUrl(output);
                if (modelUrl.isEmpty()) {
                    throw new IOException(
                            "La tâche Tripo est terminée sans lien GLB"
                    );
                }
                int credits = (int) Math.ceil(
                        data.optDouble("credits_consumed", 0.0)
                );
                return new TaskResult(modelUrl, credits);
            }
            if ("failed".equals(status)
                    || "cancelled".equals(status)
                    || "banned".equals(status)) {
                throw new IOException(
                        "Tâche Tripo " + status + " : "
                                + providerMessage(data)
                );
            }

            sleepBeforeNextPoll();
        }
        throw new IOException(
                "La génération Tripo dépasse 15 minutes et a été arrêtée"
        );
    }

    private MobileOutcome createMobileVariant(
            String generationTaskId,
            File outputDirectory,
            ProgressListener listener
    ) throws Exception {
        int consumedCredits = 0;
        IOException lastSizeError = null;

        for (int index = 0; index < MobileOptimizationPlan.count(); index++) {
            ensureNotCancelled();
            MobileOptimizationPlan.Preset preset =
                    MobileOptimizationPlan.at(index);
            notifyProgress(listener, Stage.OPTIMIZING, 0);
            String conversionTaskId = createConversionTask(
                    generationTaskId,
                    preset
            );
            TaskResult conversion = waitForTask(
                    conversionTaskId,
                    listener,
                    Stage.OPTIMIZING
            );
            consumedCredits += conversion.creditsConsumed;

            notifyProgress(listener, Stage.DOWNLOADING_MOBILE, 0);
            File candidate = downloadModel(
                    conversion.modelUrl,
                    outputDirectory,
                    "mobile_candidat_" + (index + 1) + ".glb"
            );
            validateGlb(candidate);
            notifyProgress(listener, Stage.DOWNLOADING_MOBILE, 100);

            if (MobileOptimizationPlan.meetsTarget(candidate.length())) {
                File destination = new File(
                        outputDirectory,
                        "personnage_v43_mobile_200ko.glb"
                );
                replaceFile(candidate, destination);
                return new MobileOutcome(
                        destination,
                        consumedCredits,
                        preset.getLabel()
                );
            }

            lastSizeError = new IOException(
                    "Le preset " + preset.getLabel() + " produit "
                            + candidate.length() + " octets"
            );
            candidate.delete();
        }

        throw new IOException(
                "Impossible de garantir un GLB inférieur à 200 Ko",
                lastSizeError
        );
    }

    private String createConversionTask(
            String generationTaskId,
            MobileOptimizationPlan.Preset preset
    ) throws Exception {
        JSONObject body = new JSONObject();
        body.put("input", generationTaskId);
        body.put("format", "GLTF");
        body.put("quad", false);
        body.put("face_limit", preset.getFaceLimit());
        body.put("texture_size", preset.getTextureSize());
        body.put("texture_format", "JPEG");
        body.put("bake", true);
        body.put("pack_uv", true);
        body.put("export_vertex_colors", false);
        body.put("pivot_to_center_bottom", true);
        body.put("with_animation", false);

        JSONObject response = requestJson(
                "POST",
                API_BASE + "/models/convert",
                body
        );
        JSONObject data = requireSuccessData(response);
        String taskId = data.optString("task_id", "").trim();
        if (taskId.isEmpty()) {
            throw new IOException("Tripo n'a pas créé l'optimisation mobile");
        }
        return taskId;
    }

    private File createOutputDirectory() throws IOException {
        File documents = context.getExternalFilesDir(
                Environment.DIRECTORY_DOCUMENTS
        );
        if (documents == null) {
            throw new IOException("Stockage du téléphone indisponible");
        }
        String stamp = new SimpleDateFormat(
                "yyyyMMdd_HHmmss",
                Locale.FRANCE
        ).format(new Date());
        File directory = new File(
                documents,
                "Modeliseur3D/Cloud_V43_" + stamp
        );
        if (!directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Impossible de créer le dossier des GLB");
        }
        return directory;
    }

    private File downloadModel(
            String modelUrl,
            File directory,
            String fileName
    ) throws Exception {
        URL url = new URL(modelUrl);
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException("Tripo a renvoyé un lien non sécurisé");
        }
        if (directory == null || !directory.isDirectory()) {
            throw new IOException("Dossier de téléchargement GLB invalide");
        }

        File temporary = new File(directory, fileName + ".part");
        File destination = new File(directory, fileName);
        HttpURLConnection connection = openConnection(url, "GET");
        connection.setRequestProperty("Accept", "model/gltf-binary,application/octet-stream");
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("Téléchargement GLB refusé (HTTP " + status + ")");
            }
            long announced = connection.getContentLength();
            if (announced > MAXIMUM_MODEL_BYTES) {
                throw new IOException("Le GLB dépasse 350 Mo");
            }

            long written = 0L;
            try (InputStream input = new BufferedInputStream(
                    connection.getInputStream(),
                    1024 * 1024
            ); OutputStream output = new BufferedOutputStream(
                    new FileOutputStream(temporary),
                    1024 * 1024
            )) {
                byte[] buffer = new byte[1024 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    ensureNotCancelled();
                    written += read;
                    if (written > MAXIMUM_MODEL_BYTES) {
                        throw new IOException("Le GLB dépasse 350 Mo");
                    }
                    output.write(buffer, 0, read);
                }
            } catch (Exception error) {
                temporary.delete();
                throw error;
            }

            if (written < 20L) {
                temporary.delete();
                throw new IOException("Le GLB téléchargé est vide");
            }
            if (destination.exists() && !destination.delete()) {
                temporary.delete();
                throw new IOException("Ancien GLB verrouillé");
            }
            if (!temporary.renameTo(destination)) {
                temporary.delete();
                throw new IOException("Impossible de finaliser le GLB téléchargé");
            }
            return destination;
        } finally {
            connection.disconnect();
        }
    }

    private static void replaceFile(File source, File destination)
            throws IOException {
        if (destination.exists() && !destination.delete()) {
            throw new IOException("Ancien GLB mobile verrouillé");
        }
        if (!source.renameTo(destination)) {
            throw new IOException("Impossible de finaliser le GLB mobile");
        }
    }

    private JSONObject requestJson(
            String method,
            String endpoint,
            JSONObject body
    ) throws Exception {
        HttpURLConnection connection = openConnection(new URL(endpoint), method);
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setRequestProperty("Accept", "application/json");
        if (body != null) {
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(bytes.length);
            connection.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=utf-8"
            );
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
        }
        try {
            return readJsonResponse(connection);
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection openConnection(URL url, String method)
            throws IOException {
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException("Connexion cloud non sécurisée refusée");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(REQUEST_TIMEOUT_MS);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "Modeliseur3D-Android/4.3.0");
        return connection;
    }

    private static JSONObject readJsonResponse(HttpURLConnection connection)
            throws Exception {
        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        String body = readLimitedText(stream);
        JSONObject response;
        try {
            response = body.isEmpty() ? new JSONObject() : new JSONObject(body);
        } catch (JSONException invalidJson) {
            throw new IOException(
                    "Réponse cloud invalide (HTTP " + status + ")"
            );
        }
        if (status < 200 || status >= 300) {
            throw new IOException(
                    "Tripo HTTP " + status + " : " + providerMessage(response)
            );
        }
        return response;
    }

    private static JSONObject requireSuccessData(JSONObject response)
            throws IOException {
        int code = response.optInt("code", -1);
        if (code != 0) {
            throw new IOException(
                    "Tripo code " + code + " : " + providerMessage(response)
            );
        }
        JSONObject data = response.optJSONObject("data");
        if (data == null) {
            throw new IOException("Réponse Tripo sans données");
        }
        return data;
    }

    private static String findModelUrl(JSONObject output) {
        if (output == null) {
            return "";
        }
        String[] preferred = {
                "model_url", "pbr_model_url", "glb_url", "model"
        };
        for (String key : preferred) {
            String value = output.optString(key, "").trim();
            if (isLikelyModelUrl(value)) {
                return value;
            }
        }
        return findModelUrlRecursively(output, 0);
    }

    private static String findModelUrlRecursively(Object value, int depth) {
        if (value == null || depth > 5) {
            return "";
        }
        if (value instanceof String) {
            String candidate = ((String) value).trim();
            return isLikelyModelUrl(candidate) ? candidate : "";
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            JSONArray names = object.names();
            if (names == null) {
                return "";
            }
            for (int index = 0; index < names.length(); index++) {
                String key = names.optString(index);
                String result = findModelUrlRecursively(
                        object.opt(key),
                        depth + 1
                );
                if (!result.isEmpty()) {
                    return result;
                }
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int index = 0; index < array.length(); index++) {
                String result = findModelUrlRecursively(
                        array.opt(index),
                        depth + 1
                );
                if (!result.isEmpty()) {
                    return result;
                }
            }
        }
        return "";
    }

    private static boolean isLikelyModelUrl(String value) {
        if (value == null || !value.startsWith("https://")) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains(".glb") || lower.contains("model");
    }

    private static String providerMessage(JSONObject object) {
        if (object == null) {
            return "erreur inconnue";
        }
        String[] keys = {"message", "error", "detail", "reason"};
        for (String key : keys) {
            Object value = object.opt(key);
            if (value instanceof String && !((String) value).trim().isEmpty()) {
                return limitMessage((String) value);
            }
        }
        JSONObject data = object.optJSONObject("data");
        if (data != null && data != object) {
            String nested = providerMessage(data);
            if (!"erreur inconnue".equals(nested)) {
                return nested;
            }
        }
        return "erreur inconnue";
    }

    private static String limitMessage(String message) {
        String normalized = message.replace('\n', ' ').replace('\r', ' ').trim();
        if (normalized.length() > 240) {
            return normalized.substring(0, 237) + "…";
        }
        return normalized;
    }

    private static String readLimitedText(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        try (InputStream input = stream;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAXIMUM_RESPONSE_BYTES) {
                    throw new IOException("Réponse cloud anormalement volumineuse");
                }
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name()).trim();
        }
    }

    private static void writeJpegOnWhite(Bitmap source, OutputStream output)
            throws IOException {
        Bitmap flattened = Bitmap.createBitmap(
                source.getWidth(),
                source.getHeight(),
                Bitmap.Config.ARGB_8888
        );
        try {
            Canvas canvas = new Canvas(flattened);
            canvas.drawColor(Color.WHITE);
            canvas.drawBitmap(source, 0.0f, 0.0f, null);
            if (!flattened.compress(Bitmap.CompressFormat.JPEG, 96, output)) {
                throw new IOException("Encodage JPEG de la vue impossible");
            }
        } finally {
            flattened.recycle();
        }
    }

    private static void writeAscii(OutputStream output, String value)
            throws IOException {
        output.write(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static void validateViews(List<InputView> views) {
        if (views == null || views.isEmpty() || views.size() > 4) {
            throw new IllegalArgumentException("Une à quatre vues sont requises");
        }
        Set<String> roles = new HashSet<>();
        for (InputView view : views) {
            if (view == null || !VALID_ROLES.contains(view.role)) {
                throw new IllegalArgumentException("Rôle de vue cloud invalide");
            }
            if (!roles.add(view.role)) {
                throw new IllegalArgumentException("Vue " + view.role + " dupliquée");
            }
            if (view.bitmap == null || view.bitmap.isRecycled()) {
                throw new IllegalArgumentException("Image " + view.role + " invalide");
            }
        }
        if (!roles.contains("front")) {
            throw new IllegalArgumentException("La vue de face est obligatoire");
        }
    }

    private static void validateGlb(File file) throws IOException {
        if (file == null || !file.isFile() || file.length() < 20L) {
            throw new IOException("Fichier GLB absent ou incomplet");
        }
        try (DataInputStream input = new DataInputStream(
                new FileInputStream(file)
        )) {
            int magic = Integer.reverseBytes(input.readInt());
            int version = Integer.reverseBytes(input.readInt());
            long declaredLength = Integer.toUnsignedLong(
                    Integer.reverseBytes(input.readInt())
            );
            if (magic != 0x46546C67 || version != 2) {
                throw new IOException("Le fichier reçu n'est pas un GLB 2.0");
            }
            if (declaredLength > file.length() || declaredLength < 20L) {
                throw new IOException("Le GLB reçu est tronqué");
            }
        }
    }

    private static void sleepBeforeNextPoll() throws IOException {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Génération annulée", interrupted);
        }
    }

    private static void ensureNotCancelled() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("Génération annulée");
        }
    }

    private static void notifyProgress(
            ProgressListener listener,
            Stage stage,
            int percent
    ) {
        if (listener != null) {
            listener.onProgress(stage, Math.max(0, Math.min(100, percent)));
        }
    }

    public enum Stage {
        UPLOADING,
        GENERATING,
        DOWNLOADING_HD,
        OPTIMIZING,
        DOWNLOADING_MOBILE
    }

    public interface ProgressListener {
        void onProgress(Stage stage, int percent);
    }

    public static final class InputView {
        private final String role;
        private final Bitmap bitmap;

        public InputView(String role, Bitmap bitmap) {
            this.role = role == null
                    ? ""
                    : role.trim().toLowerCase(Locale.ROOT);
            this.bitmap = bitmap;
        }

        public String getRole() {
            return role;
        }

        public Bitmap getBitmap() {
            return bitmap;
        }
    }

    public static final class Result {
        private final File highDefinitionFile;
        private final File mobileFile;
        private final String taskId;
        private final int viewCount;
        private final int creditsConsumed;
        private final long durationMs;
        private final String modelVersion;
        private final String mobilePreset;

        Result(
                File highDefinitionFile,
                File mobileFile,
                String taskId,
                int viewCount,
                int creditsConsumed,
                long durationMs,
                String modelVersion,
                String mobilePreset
        ) {
            this.highDefinitionFile = highDefinitionFile;
            this.mobileFile = mobileFile;
            this.taskId = taskId;
            this.viewCount = viewCount;
            this.creditsConsumed = creditsConsumed;
            this.durationMs = durationMs;
            this.modelVersion = modelVersion;
            this.mobilePreset = mobilePreset;
        }

        public File getModelFile() {
            return mobileFile;
        }

        public File getHighDefinitionFile() {
            return highDefinitionFile;
        }

        public File getMobileFile() {
            return mobileFile;
        }

        public String getTaskId() {
            return taskId;
        }

        public int getViewCount() {
            return viewCount;
        }

        public int getCreditsConsumed() {
            return creditsConsumed;
        }

        public long getDurationMs() {
            return durationMs;
        }

        public String getModelVersion() {
            return modelVersion;
        }

        public String getMobilePreset() {
            return mobilePreset;
        }

        public long getMobileSizeBytes() {
            return mobileFile == null ? 0L : mobileFile.length();
        }

        public long getHighDefinitionSizeBytes() {
            return highDefinitionFile == null
                    ? 0L
                    : highDefinitionFile.length();
        }
    }

    private static final class UploadedView {
        final String role;
        final String fileToken;

        UploadedView(String role, String fileToken) {
            this.role = role;
            this.fileToken = fileToken;
        }
    }

    private static final class TaskResult {
        final String modelUrl;
        final int creditsConsumed;

        TaskResult(String modelUrl, int creditsConsumed) {
            this.modelUrl = modelUrl;
            this.creditsConsumed = creditsConsumed;
        }
    }

    private static final class MobileOutcome {
        final File file;
        final int creditsConsumed;
        final String presetLabel;

        MobileOutcome(
                File file,
                int creditsConsumed,
                String presetLabel
        ) {
            this.file = file;
            this.creditsConsumed = creditsConsumed;
            this.presetLabel = presetLabel;
        }
    }
}
