package com.chasmet.modeliseur3d.model;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Environment;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class ObjExporter {
    private ObjExporter() {
    }

    public static ExportResult export(
            Context context,
            MeshData mesh,
            Bitmap texture
    ) throws IOException {
        File documents = context.getExternalFilesDir(
                Environment.DIRECTORY_DOCUMENTS
        );
        if (documents == null) {
            throw new IOException("Stockage externe indisponible");
        }

        String stamp = new SimpleDateFormat(
                "yyyyMMdd_HHmmss",
                Locale.FRANCE
        ).format(new Date());
        File directory = new File(
                documents,
                "Modeliseur3D/Modele_V3_" + stamp
        );
        if (!directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Impossible de créer le dossier d'export");
        }

        File glbFile = new File(directory, "personnage_v3.glb");
        File objFile = new File(directory, "personnage_v3.obj");
        File mtlFile = new File(directory, "personnage_v3.mtl");
        File textureFile = new File(directory, "texture_multivue_v3.png");
        File infoFile = new File(directory, "informations.txt");

        GlbExporter.write(glbFile, mesh, texture);
        writeObj(objFile, mesh);
        writeMtl(mtlFile);
        writeTexture(textureFile, texture);
        writeInfo(infoFile, mesh, glbFile.length());

        List<File> files = new ArrayList<>();
        files.add(glbFile);
        files.add(objFile);
        files.add(mtlFile);
        files.add(textureFile);
        files.add(infoFile);
        return new ExportResult(directory, files);
    }

    private static void writeObj(File file, MeshData mesh) throws IOException {
        float[] positions = mesh.getPositions();
        float[] normals = mesh.getNormals();
        float[] texCoords = mesh.getTexCoords();
        int[] indices = mesh.getIndices();

        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(
                        new FileOutputStream(file),
                        StandardCharsets.UTF_8
                ))) {
            writer.write("# Modèle lissé généré localement par Modéliseur 3D V3\n");
            writer.write("mtllib personnage_v3.mtl\n");
            writer.write("o personnage_v3\n");

            for (int i = 0; i < positions.length; i += 3) {
                writer.write(String.format(
                        Locale.US,
                        "v %.6f %.6f %.6f\n",
                        positions[i],
                        positions[i + 1],
                        positions[i + 2]
                ));
            }
            for (int i = 0; i < texCoords.length; i += 2) {
                writer.write(String.format(
                        Locale.US,
                        "vt %.6f %.6f\n",
                        texCoords[i],
                        texCoords[i + 1]
                ));
            }
            for (int i = 0; i < normals.length; i += 3) {
                writer.write(String.format(
                        Locale.US,
                        "vn %.6f %.6f %.6f\n",
                        normals[i],
                        normals[i + 1],
                        normals[i + 2]
                ));
            }

            writer.write("usemtl personnage_texture_v3\n");
            for (int i = 0; i < indices.length; i += 3) {
                int a = indices[i] + 1;
                int b = indices[i + 1] + 1;
                int c = indices[i + 2] + 1;
                writer.write("f " + a + "/" + a + "/" + a + " "
                        + b + "/" + b + "/" + b + " "
                        + c + "/" + c + "/" + c + "\n");
            }
        }
    }

    private static void writeMtl(File file) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(
                        new FileOutputStream(file),
                        StandardCharsets.UTF_8
                ))) {
            writer.write("newmtl personnage_texture_v3\n");
            writer.write("Ka 0.400000 0.400000 0.400000\n");
            writer.write("Kd 1.000000 1.000000 1.000000\n");
            writer.write("Ks 0.060000 0.060000 0.060000\n");
            writer.write("Ns 10.000000\n");
            writer.write("d 1.000000\n");
            writer.write("illum 2\n");
            writer.write("map_Kd texture_multivue_v3.png\n");
        }
    }

    private static void writeTexture(File file, Bitmap texture)
            throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            if (!texture.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IOException("Écriture de la texture impossible");
            }
        }
    }

    private static void writeInfo(
            File file,
            MeshData mesh,
            long glbSize
    ) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(
                        new FileOutputStream(file),
                        StandardCharsets.UTF_8
                ))) {
            writer.write("Version : Modéliseur 3D V3 ultra propre\n");
            writer.write("Format principal : GLB 2.0 autonome\n");
            writer.write("Formats secondaires : OBJ + MTL + PNG\n");
            writer.write("Sommets : " + mesh.getVertexCount() + "\n");
            writer.write("Triangles : " + mesh.getTriangleCount() + "\n");
            writer.write("Taille GLB : " + glbSize + " octets\n");
            writer.write("Méthode : enveloppe multivue, champ lissé et extraction de surface par tétraèdres.\n");
            writer.write("Normales : gradient volumique lissé.\n");
            writer.write("Texture : atlas face, dos et profils avec extension des couleurs.\n");
            writer.write("Le GLB peut être importé directement dans Godot, Blender ou Unity.\n");
        }
    }

    public static final class ExportResult {
        private final File directory;
        private final List<File> files;

        ExportResult(File directory, List<File> files) {
            this.directory = directory;
            this.files = files;
        }

        public File getDirectory() {
            return directory;
        }

        public List<File> getFiles() {
            return files;
        }
    }
}
