package com.chasmet.modeliseur3d.cloud;

import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** HTTPS only, no redirects of bearer credentials, bounded streaming transfers. */
public class CloudApi {
    private final String base;
    private final String token;
    public CloudApi(String address, String token) throws IOException {
        try {
            URI uri = new URI(address.trim());
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !(uri.getPath() == null || uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
                throw new IOException("Indique l'adresse HTTPS du relais, sans chemin ni identifiant.");
            }
            this.base = address.trim().replaceAll("/+$", "");
            this.token = token;
        } catch (URISyntaxException e) { throw new IOException("Adresse HTTPS invalide.", e); }
    }
    public String base() { return base; }
    public static String id(String value) throws IOException {
        if (value == null || !value.matches("[a-f0-9]{32}")) throw new IOException("Identifiant de modèle invalide.");
        return value;
    }
    private HttpURLConnection connect(String path, String method) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(30_000);
        c.setReadTimeout(45_000);
        if ("/api/poll".equals(path) || "/api/heartbeat".equals(path)) {
            c.setConnectTimeout(8_000);c.setReadTimeout(10_000);
        }
        c.setRequestMethod(method);
        if (token != null && !token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
        return c;
    }
    public static final class HttpFailure extends IOException {
        public final int code;
        public HttpFailure(int code, String message) { super(message); this.code = code; }
    }
    private static void ensureSuccess(HttpURLConnection c) throws IOException {
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) {
            if (code == 401) throw new HttpFailure(code, "Connexion expirée : reconnecte ce téléphone au relais.");
            if (code == 404) throw new HttpFailure(code, "Commande introuvable sur le relais.");
            if (code == 413) throw new IOException("Image trop volumineuse.");
            if (code == 429) throw new IOException("Limite du relais atteinte. Efface les anciens travaux ou attends.");
            if (code == 409) throw new IOException("Travail en cours : attends sa fin.");
            throw new IOException("Le relais ne répond pas correctement (HTTP " + code + ").");
        }
    }
    private static JSONObject response(HttpURLConnection c) throws Exception {
        ensureSuccess(c);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > 1024 * 1024) throw new IOException("Réponse du relais trop volumineuse.");
                out.write(buffer, 0, n);
            }
            return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
        }
    }
    public JSONObject json(String path, JSONObject body) throws Exception {
        HttpURLConnection c = connect(path, body == null ? "GET" : "POST");
        try {
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
            }
            return response(c);
        } finally { c.disconnect(); }
    }
    public JSONObject upload(File image) throws Exception {
        HttpURLConnection c = connect("/api/images", "POST");
        try {
            if (image.length() > 8 * 1024 * 1024) throw new IOException("Image supérieure à 8 Mo.");
            c.setDoOutput(true); c.setRequestProperty("Content-Type", "image/jpeg");
            c.setFixedLengthStreamingMode(image.length());
            try (InputStream in = new FileInputStream(image); OutputStream out = c.getOutputStream()) { copy(in, out); }
            return response(c);
        } finally { c.disconnect(); }
    }
    public void downloadLocalImage(String command, String reference, File output) throws Exception {
        command = id(command); reference = id(reference);
        HttpURLConnection c = connect("/api/local/" + command + "/images/" + reference, "GET");
        File part = new File(output.getPath() + ".part");
        try {
            ensureSuccess(c);
            if (output.getParentFile() != null && !output.getParentFile().isDirectory()
                    && !output.getParentFile().mkdirs()) throw new IOException("Stockage image MCP indisponible.");
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[64 * 1024]; long total = 0; int n;
                while ((n = in.read(buffer)) != -1) {
                    total += n;
                    if (total > 8L * 1024 * 1024) throw new IOException("Image MCP supérieure à 8 Mo.");
                    out.write(buffer, 0, n);
                }
            }
            try (InputStream in = new FileInputStream(part)) {
                byte[] signature = new byte[8];
                if (in.read(signature) != 8
                        || signature[0] != (byte)0x89 || signature[1] != 0x50
                        || signature[2] != 0x4e || signature[3] != 0x47) {
                    throw new IOException("Image MCP invalide.");
                }
            }
            if (!part.renameTo(output)) throw new IOException("Image MCP non enregistrée.");
        } finally { part.delete(); c.disconnect(); }
    }
    public JSONObject updateLocalStatus(String command, String status, String message) throws Exception {
        return json("/api/local/" + id(command) + "/status",
                new JSONObject().put("status", status).put("message", message == null ? "" : message));
    }
    public JSONObject uploadLocalResult(String command, File glb) throws Exception {
        command = id(command);
        if (!glb.isFile() || glb.length() < 12 || glb.length() > 64L * 1024 * 1024)
            throw new IOException("GLB local invalide ou trop volumineux.");
        try (RandomAccessFile file = new RandomAccessFile(glb, "r")) {
            byte[] header = new byte[12]; file.readFully(header);
            ByteBuffer b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            if (b.getInt() != 0x46546c67 || b.getInt() != 2
                    || Integer.toUnsignedLong(b.getInt()) != glb.length()) throw new IOException("GLB local incomplet.");
        }
        HttpURLConnection c = connect("/api/local/" + command + "/result", "POST");
        try {
            c.setDoOutput(true); c.setRequestProperty("Content-Type", "model/gltf-binary");
            c.setFixedLengthStreamingMode(glb.length());
            try (InputStream in = new FileInputStream(glb); OutputStream out = c.getOutputStream()) { copy(in, out); }
            return response(c);
        } finally { c.disconnect(); }
    }

    public void download(String job, File output) throws Exception {
        HttpURLConnection c = connect("/api/jobs/" + id(job) + "/file", "GET");
        File part = new File(output.getPath() + ".part");
        try {
            ensureSuccess(c);
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[64 * 1024]; long total = 0; int n;
                while ((n = in.read(buffer)) != -1) {
                    total += n;
                    if (total > 64L * 1024 * 1024) throw new IOException("Le GLB dépasse le profil mobile (64 Mo).");
                    out.write(buffer, 0, n);
                }
            }
            try (RandomAccessFile file = new RandomAccessFile(part, "r")) {
                byte[] header = new byte[12]; file.readFully(header);
                ByteBuffer b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
                if (b.getInt() != 0x46546c67 || b.getInt() != 2
                        || Integer.toUnsignedLong(b.getInt()) != part.length()) throw new IOException("GLB incomplet ou invalide.");
            }
            if (!part.renameTo(output)) throw new IOException("Enregistrement du modèle impossible.");
        } finally { part.delete(); c.disconnect(); }
    }
    public static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024]; int n;
        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
    }
}
