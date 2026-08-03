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
    public static final long MAXIMUM_MOBILE_GLB_BYTES = 200_000L;

    private static final MobilePreset[] MOBILE_PRESETS = {
            new MobilePreset(4_000, 256),
            new MobilePreset(3_200, 224),
            new MobilePreset(2_600, 192),
            new MobilePreset(2_100, 160),
            new MobilePreset(1_600, 144),
            new MobilePreset(1_200, 128),
            new MobilePreset(900, 112),
            new MobilePreset(700, 96),
            new MobilePreset(500, 80),
            new MobilePreset(350, 64),
            new MobilePreset(220, 48)
    };

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
                "Modeliseur25D/Personnage_25D_V5_" + stamp
        );
        if (!directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Impossible de créer le dossier d'export");
        }

        File highDefinitionFile = new File(
                directory,
                "personnage_25d_v5_hd.glb"
        );
        File mobileFile = new File(
                directory,
                "personnage_25d_v5_mobile_200ko.glb"
        );
        File objFile = new File(directory, "personnage_25d_v5.obj");
        File mtlFile = new File(directory, "personnage_25d_v5.mtl");
        File textureFile = new File(directory, "texture_25d_v5.png");
        File infoFile = new File(directory, "informations_25d.txt");

        GlbExporter.write(highDefinitionFile, mesh, texture);
        MobileResult mobile = writeMobileCopy(mobileFile, mesh, texture);
        writeObj(objFile, mesh);
        writeMtl(mtlFile);
        writeTexture(textureFile, texture);
        writeInfo(
                infoFile,
                mesh,
                highDefinitionFile.length(),
                mobile
        );

        List<File> files = new ArrayList<>();
        files.add(highDefinitionFile);
        files.add(mobileFile);
        files.add(objFile);
        files.add(mtlFile);
        files.add(textureFile);
        files.add(infoFile);
        return new ExportResult(
                directory,
                files,
                highDefinitionFile,
                mobileFile,
                mobile
        );
    }

    private static MobileResult writeMobileCopy(
            File output,
            MeshData source,
            Bitmap texture
    ) throws IOException {
        IOException lastError = null;
        for (MobilePreset preset : MOBILE_PRESETS) {
            try {
                MeshData mobileMesh = MobileMeshOptimizer.simplify(
                        source,
                        preset.triangleBudget
                );
                MobileGlbExporter.write(
                        output,
                        mobileMesh,
                        texture,
                        preset.textureSide,
                        100
                );
                long size = output.length();
                if (size > 0L && size <= MAXIMUM_MOBILE_GLB_BYTES) {
                    return new MobileResult(
                            size,
                            mobileMesh.getVertexCount(),
                            mobileMesh.getTriangleCount(),
                            preset
                    );
                }
            } catch (IOException | RuntimeException error) {
                lastError = error instanceof IOException
                        ? (IOException) error
                        : new IOException(error.getMessage(), error);
            }
        }
        if (output.exists() && !output.delete()) {
            output.deleteOnExit();
        }
        throw new IOException(
                "Impossible de produire un GLB mobile inférieur ou égal à 200 000 octets",
                lastError
        );
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
            writer.write("# Relief généré localement par Modéliseur 2.5D V5\n");
            writer.write("mtllib personnage_25d_v5.mtl\n");
            writer.write("o personnage_25d_v5\n");
            for (int index = 0; index < positions.length; index += 3) {
                writer.write(String.format(
                        Locale.US,
                        "v %.6f %.6f %.6f\n",
                        positions[index],
                        positions[index + 1],
                        positions[index + 2]
                ));
            }
            for (int index = 0; index < texCoords.length; index += 2) {
                writer.write(String.format(
                        Locale.US,
                        "vt %.6f %.6f\n",
                        texCoords[index],
                        texCoords[index + 1]
                ));
            }
            for (int index = 0; index < normals.length; index += 3) {
                writer.write(String.format(
                        Locale.US,
                        "vn %.6f %.6f %.6f\n",
                        normals[index],
                        normals[index + 1],
                        normals[index + 2]
                ));
            }
            writer.write("usemtl personnage_25d_texture\n");
            for (int index = 0; index < indices.length; index += 3) {
                int a = indices[index] + 1;
                int b = indices[index + 1] + 1;
                int c = indices[index + 2] + 1;
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
            writer.write("newmtl personnage_25d_texture\n");
            writer.write("Ka 0.400000 0.400000 0.400000\n");
            writer.write("Kd 1.000000 1.000000 1.000000\n");
            writer.write("Ks 0.050000 0.050000 0.050000\n");
            writer.write("Ns 10.000000\n");
            writer.write("d 1.000000\n");
            writer.write("illum 2\n");
            writer.write("map_Kd texture_25d_v5.png\n");
        }
    }

    private static void writeTexture(File file, Bitmap texture)
            throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            if (!texture.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IOException("Écriture de la texture 2.5D impossible");
            }
        }
    }

    private static void writeInfo(
            File file,
            MeshData mesh,
            long highDefinitionSize,
            MobileResult mobile
    ) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(
                        new FileOutputStream(file),
                        StandardCharsets.UTF_8
                ))) {
            writer.write("Version : Modéliseur 2.5D V5 local\n");
            writer.write("Connexion : aucune permission Internet, aucun serveur, aucune API.\n");
            writer.write("Méthode : face avant, dos, légère épaisseur et côtés texturés.\n");
            writer.write("Rotation : limitée, car le fichier reste volontairement 2.5D.\n");
            writer.write("Format principal : GLB 2.0 autonome avec texture PNG alpha.\n");
            writer.write("Copie mobile : GLB 2.0, indices 16 bits, texture PNG alpha.\n");
            writer.write("Sommets HD : " + mesh.getVertexCount() + "\n");
            writer.write("Triangles HD : " + mesh.getTriangleCount() + "\n");
            writer.write("Taille GLB HD : " + highDefinitionSize + " octets\n");
            writer.write("Sommets mobile : " + mobile.vertexCount + "\n");
            writer.write("Triangles mobile : " + mobile.triangleCount + "\n");
            writer.write("Taille GLB mobile : " + mobile.sizeBytes + " octets\n");
            writer.write("Limite mobile vérifiée : "
                    + MAXIMUM_MOBILE_GLB_BYTES + " octets\n");
            writer.write("Réglage mobile : " + mobile.preset.label() + "\n");
            writer.write("Détourage : IS-Net Anime FP32 embarqué.\n");
            writer.write("Modèle de profondeur : non utilisé dans la V5 2.5D.\n");
            writer.write("Import direct : Godot, Blender ou Unity.\n");
        }
    }

    private static final class MobilePreset {
        private final int triangleBudget;
        private final int textureSide;

        private MobilePreset(int triangleBudget, int textureSide) {
            this.triangleBudget = triangleBudget;
            this.textureSide = textureSide;
        }

        private String label() {
            return triangleBudget + " triangles, texture PNG "
                    + textureSide + " px";
        }
    }

    private static final class MobileResult {
        private final long sizeBytes;
        private final int vertexCount;
        private final int triangleCount;
        private final MobilePreset preset;

        private MobileResult(
                long sizeBytes,
                int vertexCount,
                int triangleCount,
                MobilePreset preset
        ) {
            this.sizeBytes = sizeBytes;
            this.vertexCount = vertexCount;
            this.triangleCount = triangleCount;
            this.preset = preset;
        }
    }

    public static final class ExportResult {
        private final File directory;
        private final List<File> files;
        private final File highDefinitionFile;
        private final File mobileFile;
        private final MobileResult mobile;

        ExportResult(
                File directory,
                List<File> files,
                File highDefinitionFile,
                File mobileFile,
                MobileResult mobile
        ) {
            this.directory = directory;
            this.files = files;
            this.highDefinitionFile = highDefinitionFile;
            this.mobileFile = mobileFile;
            this.mobile = mobile;
        }

        public File getDirectory() {
            return directory;
        }

        public List<File> getFiles() {
            return files;
        }

        public File getHighDefinitionFile() {
            return highDefinitionFile;
        }

        public File getMobileFile() {
            return mobileFile;
        }

        public long getMobileSizeBytes() {
            return mobile.sizeBytes;
        }

        public int getMobileTriangleCount() {
            return mobile.triangleCount;
        }
    }
}
