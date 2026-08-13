package com.chasmet.modeliseur3d.model;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * V9.2: stockage runtime des gros réseaux ONNX.
 *
 * L'APK reste légère. Les cinq modèles sont téléchargés séparément depuis une
 * Release GitHub contrôlée, repris après coupure avec HTTP Range, vérifiés par
 * taille + SHA-256, puis conservés dans files/neural_models. Une fois installés,
 * aucune connexion n'est nécessaire pour générer les GLB.
 */
public final class RuntimeModelStore {
    private static final String RELEASE_BASE =
            "https://github.com/Chasmet/Modeliseur-3d/releases/download/v9.2-model-pack/";

    public static final ModelSpec ANIME = new ModelSpec(
            "IS-Net Anime FP32",
            "isnet_anime_fp32.onnx",
            "isnet_anime_fp32_v47.onnx",
            176_068_431L,
            "6a92a19a47e8197fb6dbcf85be14600806019831fedfe7f86eeeeffd4c40dbba"
    );
    public static final ModelSpec GENERAL = new ModelSpec(
            "IS-Net General Use",
            "isnet_general_use.onnx",
            "isnet_general_use_v81.onnx",
            178_648_008L,
            "60920e99c45464f2ba57bee2ad08c919a52bbf852739e96947fbb4358c0d964a"
    );
    public static final ModelSpec SAM_ENCODER = new ModelSpec(
            "EfficientViT-SAM XL0 encodeur",
            "efficientvit_sam_xl0_encoder.onnx",
            "efficientvit_sam_xl0_encoder_v9.onnx",
            451_763_082L,
            "b39add8f7e2f8a612af867e864d962eff5ac98c8336b32feca08327a89cc8de3"
    );
    public static final ModelSpec SAM_DECODER = new ModelSpec(
            "EfficientViT-SAM XL0 décodeur",
            "efficientvit_sam_xl0_decoder.onnx",
            "efficientvit_sam_xl0_decoder_v9.onnx",
            16_470_368L,
            "051bfae9a07c1ff180fd7c5238334fdc45eaa0227c46f29740d98662865e01f7"
    );
    public static final ModelSpec DA3_392 = new ModelSpec(
            "Depth Anything 3 Small multivue 392",
            "da3_small_four_view_392.onnx",
            "da3_small_four_view_392_v9.onnx",
            101_116_474L,
            "8cb3b0f246e152e9a784bc7d5b743c174fcd2e25a18713cbcb4781a2fbce50c3"
    );

    private static final ModelSpec[] ALL = {
            GENERAL,
            SAM_DECODER,
            DA3_392,
            ANIME,
            SAM_ENCODER
    };

    private RuntimeModelStore() {
    }

    public static boolean isReady(Context context) {
        for (ModelSpec spec : ALL) {
            File file = destination(context, spec);
            if (!file.isFile() || file.length() != spec.bytes) {
                return false;
            }
        }
        return true;
    }

    public static long totalBytes() {
        long total = 0L;
        for (ModelSpec spec : ALL) {
            total += spec.bytes;
        }
        return total;
    }

    public static long installedBytes(Context context) {
        long total = 0L;
        for (ModelSpec spec : ALL) {
            File file = destination(context, spec);
            if (file.isFile() && file.length() == spec.bytes) {
                total += spec.bytes;
            } else {
                File part = new File(file.getParentFile(), file.getName() + ".part");
                if (part.isFile()) {
                    total += Math.min(part.length(), spec.bytes);
                }
            }
        }
        return total;
    }

    public static File requireAnime(Context context) throws Exception {
        return require(context, ANIME, null);
    }

    public static File requireGeneral(Context context) throws Exception {
        return require(context, GENERAL, null);
    }

    public static File requireSamEncoder(Context context) throws Exception {
        return require(context, SAM_ENCODER, null);
    }

    public static File requireSamDecoder(Context context) throws Exception {
        return require(context, SAM_DECODER, null);
    }

    public static File requireDa3(Context context) throws Exception {
        return require(context, DA3_392, null);
    }

    public static void installAll(Context context, ProgressListener listener) throws Exception {
        long completed = 0L;
        for (ModelSpec spec : ALL) {
            File existing = destination(context, spec);
            if (existing.isFile() && existing.length() == spec.bytes) {
                completed += spec.bytes;
                notifyProgress(listener, spec, spec.bytes, spec.bytes, completed, totalBytes(), "déjà installé");
                continue;
            }
            final long base = completed;
            require(context, spec, (done, total, state) ->
                    notifyProgress(
                            listener,
                            spec,
                            done,
                            total,
                            base + Math.min(done, spec.bytes),
                            totalBytes(),
                            state
                    )
            );
            completed += spec.bytes;
        }
    }

    private static File require(
            Context context,
            ModelSpec spec,
            DownloadProgress progress
    ) throws Exception {
        Context app = context.getApplicationContext();
        File target = destination(app, spec);
        if (target.isFile() && target.length() == spec.bytes) {
            return target;
        }

        File directory = target.getParentFile();
        if (directory == null
                || (!directory.exists() && !directory.mkdirs() && !directory.isDirectory())) {
            throw new IllegalStateException("Dossier des modèles IA inaccessible");
        }

        File part = new File(directory, target.getName() + ".part");
        if (part.isFile() && part.length() > spec.bytes) {
            part.delete();
        }

        downloadResumable(spec, part, progress);
        if (part.length() != spec.bytes) {
            throw new IllegalStateException(
                    spec.label + " incomplet : " + part.length() + "/" + spec.bytes
            );
        }

        if (progress != null) {
            progress.onProgress(spec.bytes, spec.bytes, "vérification SHA-256");
        }
        String digest = sha256(part);
        if (!spec.sha256.equalsIgnoreCase(digest)) {
            part.delete();
            throw new IllegalStateException(
                    spec.label + " corrompu (SHA-256 " + digest + ")"
            );
        }

        if (target.exists() && !target.delete()) {
            throw new IllegalStateException("Ancien modèle verrouillé : " + spec.label);
        }
        if (!part.renameTo(target)) {
            copyThenDelete(part, target);
        }
        if (!target.isFile() || target.length() != spec.bytes) {
            throw new IllegalStateException("Installation du modèle impossible : " + spec.label);
        }
        return target;
    }

    private static void downloadResumable(
            ModelSpec spec,
            File part,
            DownloadProgress progress
    ) throws Exception {
        int redirects = 0;
        URL url = new URL(RELEASE_BASE + spec.assetName);
        while (true) {
            long offset = part.isFile() ? part.length() : 0L;
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(25_000);
            connection.setReadTimeout(45_000);
            connection.setRequestProperty("User-Agent", "Modeliseur3D-V9.2-Android");
            connection.setRequestProperty("Accept", "application/octet-stream,*/*");
            connection.setInstanceFollowRedirects(false);
            if (offset > 0L) {
                connection.setRequestProperty("Range", "bytes=" + offset + "-");
            }

            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_PERM
                    || code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == 307
                    || code == 308) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || ++redirects > 8) {
                    throw new IllegalStateException("Redirection GitHub invalide pour " + spec.label);
                }
                url = new URL(url, location);
                continue;
            }

            boolean append;
            if (code == HttpURLConnection.HTTP_PARTIAL && offset > 0L) {
                append = true;
            } else if (code == HttpURLConnection.HTTP_OK) {
                append = false;
                offset = 0L;
            } else {
                connection.disconnect();
                throw new IllegalStateException(
                        "Téléchargement " + spec.label + " refusé : HTTP " + code
                );
            }

            if (progress != null) {
                progress.onProgress(offset, spec.bytes, offset > 0L ? "reprise" : "téléchargement");
            }
            try (InputStream input = new BufferedInputStream(connection.getInputStream(), 1024 * 1024);
                 BufferedOutputStream output = new BufferedOutputStream(
                         new FileOutputStream(part, append),
                         1024 * 1024
                 )) {
                byte[] buffer = new byte[1024 * 1024];
                int read;
                long done = offset;
                long lastReport = done;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    done += read;
                    if (done > spec.bytes) {
                        throw new IllegalStateException("Flux trop grand pour " + spec.label);
                    }
                    if (progress != null && (done - lastReport >= 4L * 1024L * 1024L || done == spec.bytes)) {
                        progress.onProgress(done, spec.bytes, "téléchargement");
                        lastReport = done;
                    }
                }
                output.flush();
            } finally {
                connection.disconnect();
            }
            return;
        }
    }

    private static File destination(Context context, ModelSpec spec) {
        return new File(new File(context.getFilesDir(), "neural_models"), spec.destinationName);
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new BufferedInputStream(new FileInputStream(file), 1024 * 1024)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) {
            result.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private static void copyThenDelete(File source, File target) throws Exception {
        try (InputStream input = new BufferedInputStream(new FileInputStream(source), 1024 * 1024);
             BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(target), 1024 * 1024)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
        if (!source.delete()) {
            source.deleteOnExit();
        }
    }

    private static void notifyProgress(
            ProgressListener listener,
            ModelSpec spec,
            long modelDone,
            long modelTotal,
            long overallDone,
            long overallTotal,
            String state
    ) {
        if (listener != null) {
            listener.onProgress(
                    spec.label,
                    modelDone,
                    modelTotal,
                    overallDone,
                    overallTotal,
                    state
            );
        }
    }

    public interface ProgressListener {
        void onProgress(
                String model,
                long modelDone,
                long modelTotal,
                long overallDone,
                long overallTotal,
                String state
        );
    }

    private interface DownloadProgress {
        void onProgress(long done, long total, String state);
    }

    public static final class ModelSpec {
        final String label;
        final String assetName;
        final String destinationName;
        final long bytes;
        final String sha256;

        ModelSpec(
                String label,
                String assetName,
                String destinationName,
                long bytes,
                String sha256
        ) {
            this.label = label;
            this.assetName = assetName;
            this.destinationName = destinationName;
            this.bytes = bytes;
            this.sha256 = sha256;
        }
    }
}
