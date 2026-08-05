package com.chasmet.modeliseur3d.assets;

import android.content.Context;
import android.os.Environment;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Téléchargement direct et sécurisé des assets du catalogue. */
public final class Asset3DDownloader {
    public static final long MAXIMUM_ASSET_BYTES = 8_000_000L;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int MAXIMUM_REDIRECTS = 5;

    private Asset3DDownloader() {
    }

    public static File fileFor(Context context, Asset3DItem item) throws IOException {
        File documents = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (documents == null) {
            throw new IOException("Stockage externe indisponible");
        }
        File directory = new File(
                documents,
                "Modeliseur3D/Assets3D/" + safeName(item.getCategory())
        );
        if (!directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Impossible de créer le dossier des assets 3D");
        }
        return new File(directory, item.fileName());
    }

    public static File download(
            Context context,
            Asset3DItem item,
            ProgressListener listener
    ) throws IOException {
        File output = fileFor(context, item);
        if (isValidGlb(output)) {
            return output;
        }
        File temporary = new File(output.getParentFile(), output.getName() + ".part");
        deleteQuietly(temporary);
        deleteQuietly(output);

        HttpURLConnection connection = open(item.getDownloadUrl());
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("Serveur asset indisponible : HTTP " + status);
            }
            long announced = connection.getContentLengthLong();
            if (announced > MAXIMUM_ASSET_BYTES) {
                throw new IOException("Asset refusé : plus de 8 Mo");
            }
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 OutputStream target = new BufferedOutputStream(new FileOutputStream(temporary))) {
                byte[] buffer = new byte[BUFFER_SIZE];
                long downloaded = 0L;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    downloaded += read;
                    if (downloaded > MAXIMUM_ASSET_BYTES) {
                        throw new IOException("Asset refusé : plus de 8 Mo");
                    }
                    target.write(buffer, 0, read);
                    if (listener != null) {
                        listener.onProgress(downloaded, announced);
                    }
                }
                target.flush();
            }
            if (!isValidGlb(temporary)) {
                throw new IOException("Le fichier téléchargé n'est pas un GLB 2.0 valide");
            }
            if (!temporary.renameTo(output)) {
                throw new IOException("Impossible de finaliser l'asset téléchargé");
            }
            writeLicenseFile(output.getParentFile(), item);
            return output;
        } catch (IOException error) {
            deleteQuietly(temporary);
            deleteQuietly(output);
            throw error;
        } finally {
            connection.disconnect();
        }
    }

    public static boolean isDownloaded(Context context, Asset3DItem item) {
        try {
            return isValidGlb(fileFor(context, item));
        } catch (IOException ignored) {
            return false;
        }
    }

    public static String formatBytes(long bytes) {
        if (bytes >= 1_000_000L) {
            return String.format(Locale.FRANCE, "%.2f Mo", bytes / 1_000_000.0);
        }
        return String.format(Locale.FRANCE, "%.0f ko", bytes / 1000.0);
    }

    private static HttpURLConnection open(String source) throws IOException {
        URL current = new URL(source);
        for (int redirect = 0; redirect <= MAXIMUM_REDIRECTS; redirect++) {
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(45_000);
            connection.setRequestProperty("User-Agent", "Modeliseur3D-Android/5.9.8");
            connection.setRequestProperty("Accept", "model/gltf-binary,application/octet-stream,*/*");
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_MOVED_PERM
                    || status == HttpURLConnection.HTTP_MOVED_TEMP
                    || status == HttpURLConnection.HTTP_SEE_OTHER
                    || status == 307
                    || status == 308) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || location.trim().isEmpty()) {
                    throw new IOException("Redirection asset invalide");
                }
                current = new URL(current, location);
                continue;
            }
            return connection;
        }
        throw new IOException("Trop de redirections pendant le téléchargement");
    }

    private static boolean isValidGlb(File file) {
        if (file == null || !file.isFile() || file.length() < 20L
                || file.length() > MAXIMUM_ASSET_BYTES) {
            return false;
        }
        byte[] header = new byte[12];
        try (InputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < header.length) {
                int read = input.read(header, offset, header.length - offset);
                if (read < 0) {
                    return false;
                }
                offset += read;
            }
        } catch (IOException ignored) {
            return false;
        }
        int version = littleEndianInt(header, 4);
        long declaredLength = littleEndianInt(header, 8) & 0xFFFFFFFFL;
        return header[0] == 'g'
                && header[1] == 'l'
                && header[2] == 'T'
                && header[3] == 'F'
                && version == 2
                && declaredLength == file.length();
    }

    private static int littleEndianInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }

    private static void writeLicenseFile(File directory, Asset3DItem item)
            throws IOException {
        File license = new File(directory, item.getId() + "_LICENCE.txt");
        String text = "Asset : " + item.getName() + "\n"
                + "Catégorie : " + item.getCategory() + "\n"
                + "Licence : " + item.getLicense() + "\n"
                + "Crédit : " + item.getCredit() + "\n"
                + "Source : " + item.getSourceUrl() + "\n"
                + "Téléchargé depuis le catalogue Modéliseur 3D V5.9.8.\n";
        try (OutputStream output = new FileOutputStream(license)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String safeName(String value) {
        return value.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists() && !file.delete()) {
            file.deleteOnExit();
        }
    }

    public interface ProgressListener {
        void onProgress(long downloadedBytes, long totalBytes);
    }
}
