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
            new MobilePreset(1800, 256, 88),
            new MobilePreset(1200, 256, 82),
            new MobilePreset(900, 192, 78),
            new MobilePreset(650, 192, 72),
            new MobilePreset(450, 128, 68),
            new MobilePreset(300, 128, 60),
            new MobilePreset(180, 96, 52),
            new MobilePreset(120, 80, 44),
            new MobilePreset(80, 64, 35)
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
                "Modeliseur3D/Modele_V4_4_Local_" + stamp
        );
        if (!directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Impossible de créer le dossier d'export");
        }

        File highDefinitionFile = new File(
                directory,
                "personnage_v44_local_hd.glb"
        );
        File mobileFile = new File(
                directory,
                "personnage_v44_mobile_200ko.glb"
        );
        File objFile = new File(directory, "personnage_v44_local.obj");
        File mtlFile = new File(directory, "personnage_v44_local.mtl");
        File textureFile = new File(directory, "texture_multivue_v44.png");
        File infoFile = new File(directory, "informations.txt");

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
                        preset.jpegQuality
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
            writer.write("# Modèle généré localement par Modéliseur 3D V4.4\n");
            writer.write("mtllib personnage_v44_local.mtl\n");
            writer.write("o personnage_v44_local\n");

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

            writer.write("usemtl personnage_texture_v44\n");
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
            writer.write("newmtl personnage_texture_v44\n");
            writer.write("Ka 0.400000 0.400000 0.400000\n");
            writer.write("Kd 1.000000 1.000000 1.000000\n");
            writer.write("Ks 0.080000 0.080000 0.080000\n");
            writer.write("Ns 14.000000\n");
            writer.write("d 1.000000\n");
            writer.write("illum 2\n");
            writer.write("map_Kd texture_multivue_v44.png\n");
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
            long highDefinitionSize,
            MobileResult mobile
    ) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(
                        new FileOutputStream(file),
                        StandardCharsets.UTF_8
                ))) {
            writer.write("Version : Modéliseur 3D V4.4 locale\n");
            writer.write("Connexion : aucune permission Internet, aucun serveur, aucune API.\n");
            writer.write("Format principal HD : GLB 2.0 autonome avec texture PNG.\n");
            writer.write("Copie mobile : GLB 2.0 avec indices 16 bits et texture JPEG.\n");
            writer.write("Sommets HD : " + mesh.getVertexCount() + "\n");
            writer.write("Triangles HD : " + mesh.getTriangleCount() + "\n");
            writer.write("Taille GLB HD : " + highDefinitionSize + " octets\n");
            writer.write("Sommets mobile : " + mobile.vertexCount + "\n");
            writer.write("Triangles mobile : " + mobile.triangleCount + "\n");
            writer.write("Taille GLB mobile : " + mobile.sizeBytes + " octets\n");
            writer.write("Limite mobile vérifiée : "
                    + MAXIMUM_MOBILE_GLB_BYTES + " octets\n");
            writer.write("Réglage mobile : " + mobile.preset.label() + "\n");
            writer.write("Segmentation : IS-Net Anime FP32 embarqué.\n");
            writer.write("Relief : Depth Anything V2 Small FP32 embarqué.\n");
            writer.write("Runtime : ONNX Runtime Android, NNAPI si compatible, repli CPU multi-cœurs.\n");
            writer.write("Le GLB HD conserve la qualité complète. La copie 200 Ko est nécessairement simplifiée pour respecter sa limite stricte.\n");
            writer.write("Import direct : Godot, Blender ou Unity.\n");
        }
    }

    private static final class MobilePreset {
        private final int triangleBudget;
        private final int textureSide;
        private final int jpegQuality;

        private MobilePreset(
                int triangleBudget,
                int textureSide,
                int jpegQuality
        ) {
            this.triangleBudget = triangleBudget;
            this.textureSide = textureSide;
            this.jpegQuality = jpegQuality;
        }

        private String label() {
            return triangleBudget + " triangles, texture "
                    + textureSide + " px, JPEG " + jpegQuality + "%";
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
