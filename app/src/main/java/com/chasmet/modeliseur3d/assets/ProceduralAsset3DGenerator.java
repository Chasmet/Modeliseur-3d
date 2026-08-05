package com.chasmet.modeliseur3d.assets;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Random;

/**
 * Générateur local de petits assets GLB 2.0 sous licence CC0.
 *
 * Les modèles sont créés à la demande sur le téléphone. Ils utilisent des
 * formes low-poly, des UV, une texture PNG procédurale et, lorsque demandé,
 * une animation glTF intégrée. Aucun serveur ni compte n'est nécessaire.
 */
public final class ProceduralAsset3DGenerator {
    private static final String PREFIX = "generated://";
    private static final int TEXTURE_SIDE = 128;

    private ProceduralAsset3DGenerator() {
    }

    public static boolean supports(Asset3DItem item) {
        return item != null
                && item.getDownloadUrl() != null
                && item.getDownloadUrl().startsWith(PREFIX);
    }

    public static void write(File output, Asset3DItem item) throws IOException {
        if (output == null || item == null || !supports(item)) {
            throw new IOException("Asset procédural invalide");
        }
        Preset preset = Preset.parse(item.getDownloadUrl());
        MeshBuilder mesh = new MeshBuilder();
        int seed = item.getId().hashCode();
        build(mesh, preset.kind, preset.variant, seed);
        if (mesh.vertexCount() < 3 || mesh.indexCount() < 3) {
            throw new IOException("Le générateur n'a produit aucune géométrie");
        }

        Bitmap texture = createTexture(preset.kind, preset.variant, seed);
        byte[] png;
        try {
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            if (!texture.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                throw new IOException("Impossible d'encoder la texture PNG");
            }
            png = stream.toByteArray();
        } finally {
            texture.recycle();
        }

        byte[] glb = buildGlb(mesh, png, item, preset.kind);
        File parent = output.getParentFile();
        if (parent != null && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Impossible de créer le dossier de l'asset");
        }
        try (FileOutputStream target = new FileOutputStream(output)) {
            target.write(glb);
            target.flush();
        }
    }

    private static void build(MeshBuilder mesh, String kind, int variant, int seed) {
        switch (kind) {
            case "character":
                buildCharacter(mesh, variant, seed);
                break;
            case "animal":
                buildAnimal(mesh, variant, seed);
                break;
            case "fantasy":
                buildFantasy(mesh, variant, seed);
                break;
            case "vehicle":
                buildVehicle(mesh, variant, seed);
                break;
            case "road":
                buildRoad(mesh, variant, seed);
                break;
            case "wall":
                buildWall(mesh, variant, seed);
                break;
            case "nature":
                buildNature(mesh, variant, seed);
                break;
            case "water":
                buildWater(mesh, variant, seed);
                break;
            default:
                buildObject(mesh, variant, seed);
                break;
        }
    }

    private static void buildCharacter(MeshBuilder mesh, int variant, int seed) {
        Palette p = Palette.forSeed(seed, false);
        float bodyWidth = 0.62f + (variant % 4) * 0.035f;
        float bodyHeight = 1.02f + (variant % 3) * 0.06f;
        float legSpread = 0.22f + (variant % 2) * 0.05f;
        mesh.addBox(0f, 1.55f, 0f, bodyWidth, bodyHeight, 0.38f, 0f, p.primary);
        mesh.addSphere(0f, 2.42f, 0f, 0.32f, 0.36f, 0.30f, 8, 12, p.skin);
        mesh.addCylinder(-bodyWidth * 0.62f, 1.55f, 0f, 0.11f, 0.95f, 8,
                MeshBuilder.AXIS_Y, p.secondary);
        mesh.addCylinder(bodyWidth * 0.62f, 1.55f, 0f, 0.11f, 0.95f, 8,
                MeshBuilder.AXIS_Y, p.secondary);
        mesh.addCylinder(-legSpread, 0.60f, 0f, 0.14f, 1.08f, 8,
                MeshBuilder.AXIS_Y, p.dark);
        mesh.addCylinder(legSpread, 0.60f, 0f, 0.14f, 1.08f, 8,
                MeshBuilder.AXIS_Y, p.dark);
        mesh.addBox(-legSpread, 0.06f, -0.08f, 0.30f, 0.16f, 0.48f, 0f, p.dark);
        mesh.addBox(legSpread, 0.06f, -0.08f, 0.30f, 0.16f, 0.48f, 0f, p.dark);

        int style = variant % 6;
        if (style == 0) {
            mesh.addCylinder(0f, 2.73f, 0f, 0.38f, 0.10f, 14,
                    MeshBuilder.AXIS_Y, p.dark);
            mesh.addBox(0f, 2.84f, 0f, 0.46f, 0.18f, 0.38f, 0f, p.primary);
        } else if (style == 1) {
            mesh.addPyramid(0f, 2.62f, 0f, 0.80f, 0.70f, 0.72f, p.secondary);
        } else if (style == 2) {
            mesh.addBox(0f, 1.55f, 0.27f, 0.86f, 1.18f, 0.08f, 0f, p.secondary);
        } else if (style == 3) {
            mesh.addCylinder(0f, 2.76f, 0f, 0.34f, 0.20f, 12,
                    MeshBuilder.AXIS_Y, p.dark);
            mesh.addCone(0f, 2.96f, 0f, 0.30f, 0.42f, 10, p.primary);
        } else if (style == 4) {
            mesh.addBox(-0.46f, 1.50f, -0.12f, 0.10f, 1.35f, 0.10f,
                    -0.18f, p.accent);
            mesh.addBox(-0.46f, 2.18f, -0.12f, 0.42f, 0.08f, 0.08f,
                    0f, p.accent);
        } else {
            mesh.addCylinder(0.52f, 1.36f, -0.06f, 0.08f, 1.55f, 8,
                    MeshBuilder.AXIS_Y, p.accent);
            mesh.addSphere(0.52f, 2.18f, -0.06f, 0.14f, 0.14f, 0.14f,
                    6, 8, p.accent);
        }
    }

    private static void buildAnimal(MeshBuilder mesh, int variant, int seed) {
        Palette p = Palette.forSeed(seed, true);
        int type = variant % 6;
        if (type == 0) {
            buildQuadruped(mesh, p, 1.25f, 0.62f, 0.58f, false);
        } else if (type == 1) {
            buildQuadruped(mesh, p, 1.55f, 0.78f, 0.72f, true);
        } else if (type == 2) {
            buildBird(mesh, p, false);
        } else if (type == 3) {
            buildFish(mesh, p, false);
        } else if (type == 4) {
            buildTurtle(mesh, p, false);
        } else {
            buildCrabOrOctopus(mesh, p, variant % 2 == 0);
        }
    }

    private static void buildFantasy(MeshBuilder mesh, int variant, int seed) {
        Palette p = Palette.forSeed(seed, true);
        int type = variant % 6;
        if (type == 0) {
            buildDragon(mesh, p, false);
        } else if (type == 1) {
            buildBird(mesh, p, true);
            mesh.addCone(0f, 1.20f, -0.10f, 0.16f, 0.55f, 8, p.accent);
        } else if (type == 2) {
            buildQuadruped(mesh, p, 1.45f, 0.70f, 0.68f, true);
            mesh.addCone(0.58f, 1.66f, 0f, 0.10f, 0.46f, 8, p.accent);
        } else if (type == 3) {
            buildSerpent(mesh, p);
        } else if (type == 4) {
            buildGolemOrSlime(mesh, p, variant % 2 == 0);
        } else {
            buildTurtle(mesh, p, true);
        }
    }

    private static void buildVehicle(MeshBuilder mesh, int variant, int seed) {
        Palette p = Palette.forSeed(seed, false);
        int type = variant % 6;
        if (type == 0) {
            buildCart(mesh, p, false);
        } else if (type == 1) {
            buildCart(mesh, p, true);
        } else if (type == 2) {
            buildCar(mesh, p, false);
        } else if (type == 3) {
            buildBoat(mesh, p, false);
        } else if (type == 4) {
            buildBoat(mesh, p, true);
        } else {
            buildAirship(mesh, p);
        }
    }

    private static void buildRoad(MeshBuilder mesh, int variant, int seed) {
        Palette p = Palette.forSeed(seed, false);
        int type = variant % 9;
        int ground = type == 2 ? Color.rgb(121, 89, 55)
                : type == 3 ? Color.rgb(211, 190, 135)
                : type == 4 ? Color.rgb(224, 233, 242)
                : type == 5 ? Color.rgb(80, 45, 32)
                : type == 6 ? Color.rgb(92, 92, 96)
                : Color.rgb(62, 65, 70);
        if (type == 1) {
            for (int i = -3; i <= 3; i++) {
                mesh.addBox(i * 0.52f, 0f, Math.abs(i) * 0.18f,
                        0.62f, 0.12f, 1.85f, i * 0.12f, ground);
            }
        } else if (type == 7) {
            mesh.addBox(0f, 0f, 0f, 4.2f, 0.14f, 1.45f, 0f, ground);
            mesh.addBox(0f, 0f, 0f, 1.45f, 0.14f, 4.2f, 0f, ground);
        } else if (type == 8) {
            mesh.addBox(0f, 0f, 0f, 4.2f, 0.14f, 1.45f, 0f, ground);
            mesh.addBox(0f, 0f, 1.35f, 1.45f, 0.14f, 2.7f, 0f, ground);
        } else {
            mesh.addBox(0f, 0f, 0f, 4.4f, 0.14f, 1.55f, 0f, ground);
        }
        if (type == 5) {
            for (int i = -5; i <= 5; i++) {
                mesh.addBox(i * 0.42f, 0.12f, 0f, 0.32f, 0.12f, 1.65f,
                        0f, Color.rgb(122, 81, 47));
            }
        } else if (type == 6) {
            for (int i = -4; i <= 4; i++) {
                for (int j = -1; j <= 1; j++) {
                    mesh.addBox(i * 0.48f, 0.11f, j * 0.48f,
                            0.42f, 0.10f, 0.42f, 0f,
                            ((i + j) & 1) == 0 ? p.primary : p.secondary);
                }
            }
        } else {
            mesh.addBox(0f, 0.10f, 0f, 4.0f, 0.03f, 0.08f,
                    0f, type == 4 ? Color.rgb(120, 160, 210) : Color.rgb(240, 205, 72));
        }
    }

    private static void buildWall(MeshBuilder mesh, int variant, int seed) {
        Palette p = Palette.forSeed(seed, false);
        int type = variant % 9;
        if (type <= 2) {
            mesh.addBox(0f, 1.0f, 0f, 3.5f, 2.0f, 0.38f, 0f,
                    type == 0 ? Color.rgb(115, 112, 108)
                            : type == 1 ? Color.rgb(145, 70, 50)
                            : Color.rgb(112, 78, 45));
            if (type == 0 || type == 1) {
                for (int row = 0; row < 5; row++) {
                    for (int col = 0; col < 8; col++) {
                        float x = -1.55f + col * 0.44f + (row % 2) * 0.20f;
                        float y = 0.24f + row * 0.38f;
                        mesh.addBox(x, y, -0.22f, 0.36f, 0.28f, 0.08f,
                                0f, ((row + col) & 1) == 0 ? p.primary : p.secondary);
                    }
                }
            }
        } else if (type == 3) {
            mesh.addBox(-1.45f, 1.05f, 0f, 0.55f, 2.10f, 0.55f, 0f, p.dark);
            mesh.addBox(1.45f, 1.05f, 0f, 0.55f, 2.10f, 0.55f, 0f, p.dark);
            mesh.addBox(0f, 1.86f, 0f, 2.45f, 0.45f, 0.55f, 0f, p.primary);
        } else if (type == 4) {
            mesh.addCylinder(0f, 1.20f, 0f, 0.82f, 2.4f, 14,
                    MeshBuilder.AXIS_Y, p.primary);
            mesh.addCone(0f, 2.62f, 0f, 1.0f, 0.85f, 14, p.secondary);
        } else if (type == 5) {
            mesh.addBox(0f, 0.90f, 0f, 2.6f, 1.8f, 1.8f, 0f, p.primary);
            mesh.addPyramid(0f, 1.80f, 0f, 3.0f, 1.1f, 2.2f, p.secondary);
            mesh.addBox(0f, 0.55f, -0.95f, 0.58f, 1.1f, 0.12f, 0f, p.dark);
        } else if (type == 6) {
            mesh.addBox(0f, 0.70f, 0f, 2.3f, 1.4f, 1.7f, 0f, p.secondary);
            mesh.addPyramid(0f, 1.40f, 0f, 2.7f, 0.95f, 2.1f, p.primary);
        } else if (type == 7) {
            mesh.addCylinder(0f, 1.45f, 0f, 0.45f, 2.9f, 12,
                    MeshBuilder.AXIS_Y, Color.rgb(225, 225, 210));
            mesh.addCylinder(0f, 2.95f, 0f, 0.56f, 0.18f, 12,
                    MeshBuilder.AXIS_Y, p.accent);
            mesh.addCone(0f, 3.35f, 0f, 0.62f, 0.70f, 12, p.dark);
        } else {
            for (int i = -3; i <= 3; i++) {
                mesh.addBox(i * 0.52f, 0.42f + Math.abs(i) * 0.08f, 0f,
                        0.42f, 0.84f, 0.52f, 0f,
                        (i & 1) == 0 ? p.primary : p.secondary);
            }
        }
    }

    private static void buildNature(MeshBuilder mesh, int variant, int seed) {
        Palette p = Palette.forSeed(seed, true);
        int type = variant % 9;
        if (type <= 2) {
            float height = type == 2 ? 3.6f : 2.4f + type * 0.45f;
            int mountainColor = type == 1 ? Color.rgb(220, 230, 238)
                    : type == 2 ? Color.rgb(110, 55, 32)
                    : Color.rgb(94, 92, 86);
            mesh.addPyramid(0f, 0f, 0f, 3.6f, height, 3.3f, mountainColor);
            mesh.addPyramid(-1.20f, 0f, 0.65f, 2.0f, height * 0.62f, 1.8f, p.secondary);
            if (type == 2) {
                mesh.addCone(0f, height - 0.18f, 0f, 0.48f, 0.62f, 12,
                        Color.rgb(235, 75, 30));
            }
        } else if (type == 3) {
            mesh.addSphere(0f, 0.75f, 0f, 1.7f, 0.78f, 1.5f,
                    8, 14, Color.rgb(91, 126, 60));
        } else if (type == 4) {
            mesh.addBox(0f, 1.15f, 0f, 3.8f, 2.3f, 0.8f, 0f, p.dark);
            mesh.addPyramid(0f, 2.30f, 0f, 4.2f, 1.2f, 1.2f, p.primary);
        } else if (type == 5) {
            mesh.addSphere(0f, 0.45f, 0f, 0.95f, 0.55f, 0.82f,
                    7, 10, p.dark);
            mesh.addSphere(0.65f, 0.30f, 0.20f, 0.55f, 0.38f, 0.48f,
                    6, 9, p.secondary);
        } else if (type == 6) {
            buildTree(mesh, p, variant % 2 == 0);
        } else if (type == 7) {
            mesh.addCylinder(0f, 0.60f, 0f, 0.18f, 1.20f, 8,
                    MeshBuilder.AXIS_Y, p.dark);
            for (int i = 0; i < 7; i++) {
                double angle = i * Math.PI * 2.0 / 7.0;
                mesh.addCone((float) Math.cos(angle) * 0.44f, 1.38f,
                        (float) Math.sin(angle) * 0.44f,
                        0.18f, 0.92f, 8, p.accent);
            }
        } else {
            mesh.addCylinder(0f, 0.55f, 0f, 0.35f, 1.1f, 10,
                    MeshBuilder.AXIS_Y, p.primary);
            mesh.addSphere(0f, 1.22f, 0f, 0.75f, 0.40f, 0.75f,
                    6, 10, p.accent);
        }
    }

    private static void buildWater(MeshBuilder mesh, int variant, int seed) {
        Palette p = Palette.forSeed(seed, true);
        int type = variant % 8;
        int blue = type == 4 ? Color.rgb(188, 226, 242)
                : type == 7 ? Color.rgb(64, 92, 72)
                : Color.rgb(55, 145, 205);
        if (type <= 2 || type == 7) {
            float width = type == 1 ? 1.35f : 4.2f;
            float depth = type == 2 ? 1.35f : 3.2f;
            mesh.addBox(0f, 0f, 0f, width, 0.08f, depth, 0f, blue);
            for (int i = -4; i <= 4; i++) {
                mesh.addBox(i * width / 9f, 0.07f,
                        (i % 2 == 0 ? -0.35f : 0.35f),
                        width / 12f, 0.025f, depth * 0.65f,
                        i * 0.05f, p.secondary);
            }
        } else if (type == 3) {
            for (int i = 0; i < 10; i++) {
                float x = -1.8f + i * 0.4f;
                float y = 1.8f - i * 0.17f;
                mesh.addBox(x, y, 0f, 0.48f, 0.18f, 1.25f,
                        0f, i % 2 == 0 ? blue : p.secondary);
            }
        } else if (type == 4) {
            mesh.addPyramid(0f, 0f, 0f, 2.3f, 1.2f, 2.0f, blue);
            mesh.addBox(0f, -0.08f, 0f, 3.6f, 0.12f, 3.0f, 0f,
                    Color.rgb(70, 145, 185));
        } else if (type == 5) {
            for (int ring = 0; ring < 5; ring++) {
                float radius = 0.35f + ring * 0.33f;
                for (int i = 0; i < 16; i++) {
                    double angle = i * Math.PI * 2.0 / 16.0 + ring * 0.25;
                    mesh.addSphere((float) Math.cos(angle) * radius,
                            ring * 0.08f,
                            (float) Math.sin(angle) * radius,
                            0.14f, 0.08f, 0.14f, 5, 6,
                            ring % 2 == 0 ? blue : p.secondary);
                }
            }
        } else {
            mesh.addCylinder(0f, 0.16f, 0f, 1.65f, 0.22f, 18,
                    MeshBuilder.AXIS_Y, blue);
            for (int i = 0; i < 12; i++) {
                double angle = i * Math.PI * 2.0 / 12.0;
                mesh.addCone((float) Math.cos(angle) * 0.9f, 0.18f,
                        (float) Math.sin(angle) * 0.9f,
                        0.18f, 0.72f, 7,
                        i % 2 == 0 ? p.accent : p.secondary);
            }
        }
    }

    private static void buildObject(MeshBuilder mesh, int variant, int seed) {
        Palette p = Palette.forSeed(seed, false);
        int type = variant % 8;
        if (type == 0) {
            mesh.addBox(0f, 0.50f, 0f, 1.25f, 1.0f, 0.90f, 0f, p.primary);
            mesh.addCylinder(0f, 1.05f, 0f, 0.64f, 0.22f, 12,
                    MeshBuilder.AXIS_X, p.secondary);
        } else if (type == 1) {
            mesh.addCylinder(0f, 0.70f, 0f, 0.52f, 1.40f, 12,
                    MeshBuilder.AXIS_Y, p.primary);
            mesh.addCylinder(0f, 0.34f, 0f, 0.56f, 0.10f, 12,
                    MeshBuilder.AXIS_Y, p.dark);
            mesh.addCylinder(0f, 1.06f, 0f, 0.56f, 0.10f, 12,
                    MeshBuilder.AXIS_Y, p.dark);
        } else if (type == 2) {
            mesh.addBox(0f, 0.48f, 0f, 1.1f, 0.96f, 1.1f, 0f, p.secondary);
            mesh.addBox(0f, 0.48f, -0.57f, 0.12f, 0.36f, 0.08f, 0f, p.accent);
        } else if (type == 3) {
            mesh.addCylinder(0f, 0.72f, 0f, 0.10f, 1.44f, 8,
                    MeshBuilder.AXIS_Y, p.dark);
            mesh.addSphere(0f, 1.58f, 0f, 0.28f, 0.42f, 0.28f,
                    6, 9, Color.rgb(255, 138, 36));
        } else if (type == 4) {
            mesh.addCylinder(0f, 0.70f, 0f, 0.36f, 1.12f, 10,
                    MeshBuilder.AXIS_Y, p.dark);
            mesh.addCone(0f, 1.42f, 0f, 0.44f, 0.58f, 10, p.accent);
        } else if (type == 5) {
            mesh.addCylinder(0f, 0.30f, 0f, 0.62f, 0.30f, 12,
                    MeshBuilder.AXIS_X, p.dark);
            mesh.addCylinder(0f, 0.62f, 0f, 0.25f, 1.05f, 10,
                    MeshBuilder.AXIS_Z, p.primary);
        } else if (type == 6) {
            mesh.addBox(0f, 0.80f, 0f, 0.16f, 1.6f, 0.10f, 0f, p.accent);
            mesh.addBox(0f, 1.55f, 0f, 0.78f, 0.12f, 0.12f, 0f, p.dark);
            mesh.addBox(0f, 0.10f, 0f, 0.58f, 0.12f, 0.16f, 0f, p.dark);
        } else {
            mesh.addPyramid(0f, 0f, 0f, 1.1f, 1.65f, 1.0f, p.accent);
            mesh.addPyramid(0f, 1.30f, 0f, 0.65f, 0.95f, 0.58f, p.secondary);
        }
    }

    private static void buildQuadruped(
            MeshBuilder mesh,
            Palette p,
            float length,
            float height,
            float bodyWidth,
            boolean horned
    ) {
        mesh.addSphere(0f, 0.95f, 0f, length * 0.55f, height * 0.48f,
                bodyWidth * 0.50f, 7, 12, p.primary);
        mesh.addSphere(length * 0.52f, 1.10f, 0f, 0.34f, 0.36f, 0.32f,
                7, 10, p.secondary);
        for (int side = -1; side <= 1; side += 2) {
            for (int end = -1; end <= 1; end += 2) {
                mesh.addCylinder(end * length * 0.30f, 0.43f,
                        side * bodyWidth * 0.35f,
                        0.09f, 0.78f, 7, MeshBuilder.AXIS_Y, p.dark);
            }
        }
        mesh.addBox(-length * 0.62f, 1.04f, 0f, 0.72f, 0.10f, 0.10f,
                0.22f, p.secondary);
        if (horned) {
            mesh.addCone(length * 0.60f, 1.45f, -0.18f,
                    0.08f, 0.40f, 7, p.accent);
            mesh.addCone(length * 0.60f, 1.45f, 0.18f,
                    0.08f, 0.40f, 7, p.accent);
        }
    }

    private static void buildBird(MeshBuilder mesh, Palette p, boolean fantasy) {
        mesh.addSphere(0f, 0.95f, 0f, 0.62f, 0.48f, 0.42f,
                8, 12, p.primary);
        mesh.addSphere(0.48f, 1.25f, 0f, 0.28f, 0.30f, 0.26f,
                7, 10, p.secondary);
        mesh.addCone(0.78f, 1.23f, 0f, 0.12f, 0.42f, 8, p.accent);
        mesh.addPyramid(-0.10f, 0.90f, -0.20f,
                fantasy ? 2.1f : 1.45f, 0.32f, 0.75f, p.secondary);
        mesh.addPyramid(-0.10f, 0.90f, 0.20f,
                fantasy ? 2.1f : 1.45f, 0.32f, 0.75f, p.secondary);
        mesh.addPyramid(-0.62f, 0.82f, 0f, 0.82f, 0.28f, 0.55f, p.dark);
        mesh.addCylinder(-0.12f, 0.35f, -0.16f, 0.05f, 0.65f, 6,
                MeshBuilder.AXIS_Y, p.dark);
        mesh.addCylinder(-0.12f, 0.35f, 0.16f, 0.05f, 0.65f, 6,
                MeshBuilder.AXIS_Y, p.dark);
    }

    private static void buildFish(MeshBuilder mesh, Palette p, boolean fantasy) {
        mesh.addSphere(0f, 0.72f, 0f, 0.95f, 0.46f, 0.32f,
                8, 14, p.primary);
        mesh.addPyramid(-0.98f, 0.40f, 0f, 0.88f, 0.68f, 0.20f, p.secondary);
        mesh.addPyramid(-0.10f, 1.05f, 0f, 0.55f, 0.55f, 0.16f, p.dark);
        if (fantasy) {
            mesh.addCone(0.40f, 1.18f, 0f, 0.10f, 0.52f, 7, p.accent);
        }
    }

    private static void buildTurtle(MeshBuilder mesh, Palette p, boolean crystal) {
        mesh.addSphere(0f, 0.48f, 0f, 0.90f, 0.34f, 0.70f,
                7, 12, crystal ? p.accent : p.primary);
        mesh.addSphere(0.88f, 0.48f, 0f, 0.26f, 0.24f, 0.24f,
                6, 9, p.secondary);
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                mesh.addSphere(sx * 0.52f, 0.24f, sz * 0.52f,
                        0.28f, 0.10f, 0.18f, 5, 7, p.dark);
            }
        }
        if (crystal) {
            for (int i = -2; i <= 2; i++) {
                mesh.addCone(i * 0.24f, 0.72f, 0f, 0.10f,
                        0.45f + 0.10f * (2 - Math.abs(i)), 6, p.accent);
            }
        }
    }

    private static void buildCrabOrOctopus(MeshBuilder mesh, Palette p, boolean crab) {
        mesh.addSphere(0f, 0.55f, 0f, crab ? 0.70f : 0.55f,
                crab ? 0.28f : 0.52f, crab ? 0.52f : 0.55f,
                7, 12, p.primary);
        int limbs = crab ? 8 : 10;
        for (int i = 0; i < limbs; i++) {
            double angle = i * Math.PI * 2.0 / limbs;
            float x = (float) Math.cos(angle) * 0.72f;
            float z = (float) Math.sin(angle) * 0.72f;
            mesh.addCylinder(x, 0.25f, z, 0.06f, crab ? 0.70f : 0.95f,
                    6, MeshBuilder.AXIS_Y, p.secondary);
        }
        if (crab) {
            mesh.addSphere(0.82f, 0.68f, -0.42f, 0.24f, 0.18f, 0.20f,
                    5, 7, p.accent);
            mesh.addSphere(0.82f, 0.68f, 0.42f, 0.24f, 0.18f, 0.20f,
                    5, 7, p.accent);
        }
    }

    private static void buildDragon(MeshBuilder mesh, Palette p, boolean small) {
        buildQuadruped(mesh, p, small ? 1.15f : 1.65f,
                small ? 0.58f : 0.78f, small ? 0.55f : 0.72f, true);
        mesh.addPyramid(-0.05f, 1.05f, -0.24f,
                small ? 1.35f : 2.15f, 0.55f, 0.85f, p.secondary);
        mesh.addPyramid(-0.05f, 1.05f, 0.24f,
                small ? 1.35f : 2.15f, 0.55f, 0.85f, p.secondary);
        for (int i = -3; i <= 3; i++) {
            mesh.addCone(i * 0.26f, 1.48f, 0f, 0.08f,
                    0.30f + (3 - Math.abs(i)) * 0.05f, 6, p.accent);
        }
    }

    private static void buildSerpent(MeshBuilder mesh, Palette p) {
        for (int i = 0; i < 9; i++) {
            float x = -1.2f + i * 0.30f;
            float z = (float) Math.sin(i * 0.85f) * 0.55f;
            float y = 0.35f + i * 0.09f;
            mesh.addSphere(x, y, z, 0.28f, 0.24f, 0.28f,
                    6, 9, i % 2 == 0 ? p.primary : p.secondary);
        }
        mesh.addSphere(1.34f, 1.18f, 0f, 0.42f, 0.38f, 0.36f,
                7, 10, p.primary);
        mesh.addCone(1.35f, 1.58f, -0.16f, 0.07f, 0.35f, 6, p.accent);
        mesh.addCone(1.35f, 1.58f, 0.16f, 0.07f, 0.35f, 6, p.accent);
    }

    private static void buildGolemOrSlime(MeshBuilder mesh, Palette p, boolean golem) {
        if (golem) {
            mesh.addBox(0f, 1.05f, 0f, 1.25f, 1.35f, 0.72f, 0f, p.primary);
            mesh.addBox(0f, 1.95f, 0f, 0.78f, 0.62f, 0.66f, 0f, p.secondary);
            mesh.addBox(-0.85f, 1.05f, 0f, 0.42f, 1.45f, 0.52f, 0f, p.dark);
            mesh.addBox(0.85f, 1.05f, 0f, 0.42f, 1.45f, 0.52f, 0f, p.dark);
            mesh.addBox(-0.32f, 0.22f, 0f, 0.48f, 0.65f, 0.55f, 0f, p.dark);
            mesh.addBox(0.32f, 0.22f, 0f, 0.48f, 0.65f, 0.55f, 0f, p.dark);
        } else {
            mesh.addSphere(0f, 0.52f, 0f, 0.90f, 0.70f, 0.78f,
                    8, 12, p.primary);
            mesh.addSphere(0f, 0.18f, 0f, 1.05f, 0.22f, 0.88f,
                    6, 12, p.secondary);
        }
    }

    private static void buildCart(MeshBuilder mesh, Palette p, boolean covered) {
        mesh.addBox(0f, 0.72f, 0f, 1.9f, 0.58f, 1.15f, 0f, p.primary);
        mesh.addBox(-1.35f, 0.55f, 0f, 0.95f, 0.16f, 0.18f, 0f, p.dark);
        mesh.addCylinder(-0.55f, 0.35f, -0.66f, 0.34f, 0.16f, 12,
                MeshBuilder.AXIS_Z, p.dark);
        mesh.addCylinder(0.55f, 0.35f, -0.66f, 0.34f, 0.16f, 12,
                MeshBuilder.AXIS_Z, p.dark);
        mesh.addCylinder(-0.55f, 0.35f, 0.66f, 0.34f, 0.16f, 12,
                MeshBuilder.AXIS_Z, p.dark);
        mesh.addCylinder(0.55f, 0.35f, 0.66f, 0.34f, 0.16f, 12,
                MeshBuilder.AXIS_Z, p.dark);
        if (covered) {
            mesh.addCylinder(0f, 1.25f, 0f, 0.82f, 1.70f, 12,
                    MeshBuilder.AXIS_X, p.secondary);
        }
    }

    private static void buildCar(MeshBuilder mesh, Palette p, boolean truck) {
        mesh.addBox(0f, 0.62f, 0f, truck ? 2.4f : 2.0f,
                0.55f, 1.10f, 0f, p.primary);
        mesh.addBox(0.15f, 1.03f, 0f, truck ? 1.15f : 1.05f,
                0.46f, 0.95f, 0f, p.secondary);
        for (int x = -1; x <= 1; x += 2) {
            for (int z = -1; z <= 1; z += 2) {
                mesh.addCylinder(x * 0.72f, 0.32f, z * 0.59f,
                        0.28f, 0.16f, 12, MeshBuilder.AXIS_Z, p.dark);
            }
        }
    }

    private static void buildBoat(MeshBuilder mesh, Palette p, boolean sail) {
        mesh.addPyramid(0f, 0.10f, 0f, 2.8f, 0.72f, 1.25f, p.primary);
        mesh.addBox(0f, 0.62f, 0f, 1.65f, 0.28f, 0.88f, 0f, p.secondary);
        if (sail) {
            mesh.addCylinder(0f, 1.65f, 0f, 0.07f, 2.25f, 8,
                    MeshBuilder.AXIS_Y, p.dark);
            mesh.addPyramid(0.18f, 1.45f, 0f, 0.18f, 1.55f, 1.25f, p.accent);
        }
    }

    private static void buildAirship(MeshBuilder mesh, Palette p) {
        mesh.addSphere(0f, 2.0f, 0f, 1.65f, 0.68f, 0.72f,
                8, 16, p.primary);
        mesh.addBox(0f, 0.72f, 0f, 1.15f, 0.42f, 0.58f, 0f, p.secondary);
        mesh.addCylinder(-0.40f, 1.30f, -0.28f, 0.04f, 1.20f, 6,
                MeshBuilder.AXIS_Y, p.dark);
        mesh.addCylinder(0.40f, 1.30f, -0.28f, 0.04f, 1.20f, 6,
                MeshBuilder.AXIS_Y, p.dark);
        mesh.addCylinder(-0.40f, 1.30f, 0.28f, 0.04f, 1.20f, 6,
                MeshBuilder.AXIS_Y, p.dark);
        mesh.addCylinder(0.40f, 1.30f, 0.28f, 0.04f, 1.20f, 6,
                MeshBuilder.AXIS_Y, p.dark);
    }

    private static void buildTree(MeshBuilder mesh, Palette p, boolean palm) {
        mesh.addCylinder(0f, 0.95f, 0f, palm ? 0.16f : 0.24f,
                1.90f, 9, MeshBuilder.AXIS_Y, p.dark);
        if (palm) {
            for (int i = 0; i < 8; i++) {
                double angle = i * Math.PI * 2.0 / 8.0;
                float x = (float) Math.cos(angle) * 0.62f;
                float z = (float) Math.sin(angle) * 0.62f;
                mesh.addBox(x, 1.98f, z, 1.05f, 0.10f, 0.28f,
                        (float) angle, p.primary);
            }
        } else {
            mesh.addCone(0f, 1.55f, 0f, 0.95f, 1.35f, 12, p.primary);
            mesh.addCone(0f, 2.10f, 0f, 0.72f, 1.05f, 12, p.secondary);
        }
    }

    private static Bitmap createTexture(String kind, int variant, int seed) {
        Bitmap bitmap = Bitmap.createBitmap(
                TEXTURE_SIDE,
                TEXTURE_SIDE,
                Bitmap.Config.ARGB_8888
        );
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.rgb(245, 245, 245));
        canvas.drawRect(0f, 0f, TEXTURE_SIDE, TEXTURE_SIDE, paint);
        Random random = new Random(seed * 31L + variant * 17L);

        if ("road".equals(kind)) {
            paint.setColor(Color.rgb(165, 165, 165));
            for (int i = 0; i < 180; i++) {
                float x = random.nextInt(TEXTURE_SIDE);
                float y = random.nextInt(TEXTURE_SIDE);
                canvas.drawCircle(x, y, 0.5f + random.nextFloat() * 1.5f, paint);
            }
            paint.setColor(Color.WHITE);
            canvas.drawRect(60f, 0f, 68f, 44f, paint);
            canvas.drawRect(60f, 76f, 68f, 128f, paint);
        } else if ("wall".equals(kind)) {
            paint.setColor(Color.rgb(172, 172, 172));
            paint.setStrokeWidth(3f);
            for (int y = 0; y <= TEXTURE_SIDE; y += 20) {
                canvas.drawLine(0f, y, TEXTURE_SIDE, y, paint);
            }
            for (int row = 0; row < 7; row++) {
                int offset = (row & 1) == 0 ? 0 : 16;
                for (int x = offset; x <= TEXTURE_SIDE; x += 32) {
                    canvas.drawLine(x, row * 20f, x, row * 20f + 20f, paint);
                }
            }
        } else if ("water".equals(kind)) {
            paint.setColor(Color.rgb(195, 225, 250));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3f);
            for (int y = 8; y < TEXTURE_SIDE; y += 16) {
                for (int x = -16; x < TEXTURE_SIDE; x += 32) {
                    canvas.drawArc(x, y, x + 28, y + 12, 190, 160, false, paint);
                }
            }
            paint.setStyle(Paint.Style.FILL);
        } else if ("nature".equals(kind)) {
            for (int i = 0; i < 220; i++) {
                int shade = 185 + random.nextInt(60);
                paint.setColor(Color.rgb(shade, shade, shade));
                canvas.drawCircle(random.nextInt(TEXTURE_SIDE),
                        random.nextInt(TEXTURE_SIDE),
                        0.5f + random.nextFloat() * 2.0f, paint);
            }
        } else if ("animal".equals(kind) || "fantasy".equals(kind)) {
            paint.setColor(Color.rgb(200, 200, 200));
            for (int i = 0; i < 45; i++) {
                float radius = 2f + random.nextFloat() * 5f;
                canvas.drawCircle(random.nextInt(TEXTURE_SIDE),
                        random.nextInt(TEXTURE_SIDE), radius, paint);
            }
            if ("fantasy".equals(kind)) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2f);
                paint.setColor(Color.rgb(175, 175, 175));
                for (int y = 0; y < TEXTURE_SIDE; y += 12) {
                    for (int x = 0; x < TEXTURE_SIDE; x += 12) {
                        canvas.drawCircle(x + (y % 24 == 0 ? 6 : 0), y,
                                6f, paint);
                    }
                }
                paint.setStyle(Paint.Style.FILL);
            }
        } else if ("character".equals(kind)) {
            paint.setColor(Color.rgb(220, 220, 220));
            paint.setStrokeWidth(1f);
            for (int i = 0; i < TEXTURE_SIDE; i += 8) {
                canvas.drawLine(i, 0f, i, TEXTURE_SIDE, paint);
                canvas.drawLine(0f, i, TEXTURE_SIDE, i, paint);
            }
        } else if ("vehicle".equals(kind)) {
            paint.setColor(Color.rgb(210, 210, 210));
            for (int i = 0; i < TEXTURE_SIDE; i += 16) {
                canvas.drawRect(i, 0f, i + 5f, TEXTURE_SIDE, paint);
            }
        } else {
            paint.setColor(Color.rgb(205, 205, 205));
            paint.setStrokeWidth(3f);
            for (int y = 8; y < TEXTURE_SIDE; y += 14) {
                canvas.drawLine(0f, y, TEXTURE_SIDE, y + 6f, paint);
            }
        }
        return bitmap;
    }

    private static byte[] buildGlb(
            MeshBuilder mesh,
            byte[] png,
            Asset3DItem item,
            String kind
    ) throws IOException {
        BinaryBuilder binary = new BinaryBuilder();
        Section positions = binary.putFloats(mesh.positions.toArray());
        Section normals = binary.putFloats(mesh.normals.toArray());
        Section uvs = binary.putFloats(mesh.uvs.toArray());
        Section colors = binary.putFloats(mesh.colors.toArray());
        Section indices = binary.putInts(mesh.indices.toArray());
        Section image = binary.putBytes(png);

        Section times = null;
        Section animationValues = null;
        if (item.isAnimated()) {
            times = binary.putFloats(new float[]{0f, 1f, 2f});
            float distance = "vehicle".equals(kind) ? 0.22f : 0.08f;
            animationValues = binary.putFloats(new float[]{
                    0f, 0f, -distance,
                    0f, distance, distance,
                    0f, 0f, -distance
            });
        }

        StringBuilder json = new StringBuilder(4096);
        json.append('{');
        json.append("\"asset\":{\"version\":\"2.0\",\"generator\":\"Modeliseur 3D V5.9.9 procedural CC0\",\"copyright\":\"CC0 1.0 - Modeliseur 3D\"},");
        json.append("\"extensionsUsed\":[\"KHR_materials_unlit\"],");
        json.append("\"scene\":0,\"scenes\":[{\"nodes\":[0]}],");
        json.append("\"nodes\":[{\"name\":\"")
                .append(escape(item.getName()))
                .append("\",\"mesh\":0}],");
        json.append("\"meshes\":[{\"name\":\"")
                .append(escape(item.getId()))
                .append("\",\"primitives\":[{\"attributes\":{\"POSITION\":0,\"NORMAL\":1,\"TEXCOORD_0\":2,\"COLOR_0\":3},\"indices\":4,\"material\":0}]}],");
        json.append("\"materials\":[{\"name\":\"Texture procédurale ")
                .append(escape(kind))
                .append("\",\"pbrMetallicRoughness\":{\"baseColorTexture\":{\"index\":0},\"metallicFactor\":0.05,\"roughnessFactor\":0.82},\"doubleSided\":true,\"extensions\":{\"KHR_materials_unlit\":{}}}],");
        json.append("\"textures\":[{\"sampler\":0,\"source\":0}],");
        json.append("\"samplers\":[{\"magFilter\":9729,\"minFilter\":9729,\"wrapS\":10497,\"wrapT\":10497}],");
        json.append("\"images\":[{\"bufferView\":5,\"mimeType\":\"image/png\"}],");

        json.append("\"accessors\":[");
        appendAccessor(json, 0, 5126, mesh.vertexCount(), "VEC3",
                mesh.minX, mesh.minY, mesh.minZ,
                mesh.maxX, mesh.maxY, mesh.maxZ, true);
        json.append(',');
        appendAccessor(json, 1, 5126, mesh.vertexCount(), "VEC3",
                0f, 0f, 0f, 0f, 0f, 0f, false);
        json.append(',');
        appendAccessor(json, 2, 5126, mesh.vertexCount(), "VEC2",
                0f, 0f, 0f, 0f, 0f, 0f, false);
        json.append(',');
        appendAccessor(json, 3, 5126, mesh.vertexCount(), "VEC4",
                0f, 0f, 0f, 0f, 0f, 0f, false);
        json.append(',');
        json.append("{\"bufferView\":4,\"componentType\":5125,\"count\":")
                .append(mesh.indexCount())
                .append(",\"type\":\"SCALAR\"}");
        if (item.isAnimated()) {
            json.append(",{")
                    .append("\"bufferView\":6,\"componentType\":5126,\"count\":3,\"type\":\"SCALAR\",\"min\":[0],\"max\":[2]}");
            json.append(",{")
                    .append("\"bufferView\":7,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"}");
        }
        json.append("],");

        json.append("\"bufferViews\":[");
        appendBufferView(json, positions, 34962);
        json.append(',');
        appendBufferView(json, normals, 34962);
        json.append(',');
        appendBufferView(json, uvs, 34962);
        json.append(',');
        appendBufferView(json, colors, 34962);
        json.append(',');
        appendBufferView(json, indices, 34963);
        json.append(',');
        appendBufferView(json, image, 0);
        if (item.isAnimated()) {
            json.append(',');
            appendBufferView(json, times, 0);
            json.append(',');
            appendBufferView(json, animationValues, 0);
        }
        json.append("],");
        if (item.isAnimated()) {
            json.append("\"animations\":[{\"name\":\"Animation légère\",\"samplers\":[{\"input\":5,\"output\":6,\"interpolation\":\"LINEAR\"}],\"channels\":[{\"sampler\":0,\"target\":{\"node\":0,\"path\":\"translation\"}}]}],");
        }
        json.append("\"buffers\":[{\"byteLength\":")
                .append(binary.size())
                .append("}],");
        json.append("\"extras\":{\"license\":\"")
                .append(escape(item.getLicense()))
                .append("\",\"credit\":\"")
                .append(escape(item.getCredit()))
                .append("\",\"category\":\"")
                .append(escape(item.getCategory()))
                .append("\"}}");
        json.append('}');

        byte[] jsonBytes = json.toString().getBytes(StandardCharsets.UTF_8);
        int jsonPadded = align4(jsonBytes.length);
        byte[] binBytes = binary.toByteArray();
        int binPadded = align4(binBytes.length);
        int total = 12 + 8 + jsonPadded + 8 + binPadded;
        ByteBuffer glb = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        glb.putInt(0x46546C67);
        glb.putInt(2);
        glb.putInt(total);
        glb.putInt(jsonPadded);
        glb.putInt(0x4E4F534A);
        glb.put(jsonBytes);
        while (glb.position() < 20 + jsonPadded) {
            glb.put((byte) 0x20);
        }
        glb.putInt(binPadded);
        glb.putInt(0x004E4942);
        glb.put(binBytes);
        while (glb.position() < total) {
            glb.put((byte) 0);
        }
        return glb.array();
    }

    private static void appendAccessor(
            StringBuilder json,
            int bufferView,
            int componentType,
            int count,
            String type,
            float minX,
            float minY,
            float minZ,
            float maxX,
            float maxY,
            float maxZ,
            boolean bounds
    ) {
        json.append("{\"bufferView\":")
                .append(bufferView)
                .append(",\"componentType\":")
                .append(componentType)
                .append(",\"count\":")
                .append(count)
                .append(",\"type\":\"")
                .append(type)
                .append('"');
        if (bounds) {
            json.append(",\"min\":[")
                    .append(number(minX)).append(',')
                    .append(number(minY)).append(',')
                    .append(number(minZ)).append("],\"max\":[")
                    .append(number(maxX)).append(',')
                    .append(number(maxY)).append(',')
                    .append(number(maxZ)).append(']');
        }
        json.append('}');
    }

    private static void appendBufferView(StringBuilder json, Section section, int target) {
        json.append("{\"buffer\":0,\"byteOffset\":")
                .append(section.offset)
                .append(",\"byteLength\":")
                .append(section.length);
        if (target != 0) {
            json.append(",\"target\":").append(target);
        }
        json.append('}');
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }

    private static String number(float value) {
        if (!Float.isFinite(value)) {
            return "0";
        }
        return String.format(Locale.US, "%.6f", value);
    }

    private static int align4(int value) {
        return (value + 3) & ~3;
    }

    private static final class Preset {
        final String kind;
        final int variant;

        Preset(String kind, int variant) {
            this.kind = kind;
            this.variant = variant;
        }

        static Preset parse(String source) throws IOException {
            String value = source.substring(PREFIX.length());
            String[] parts = value.split("/");
            if (parts.length < 2) {
                throw new IOException("Adresse de génération invalide");
            }
            try {
                return new Preset(parts[0], Integer.parseInt(parts[1]));
            } catch (NumberFormatException error) {
                throw new IOException("Variante de génération invalide", error);
            }
        }
    }

    private static final class Palette {
        final int primary;
        final int secondary;
        final int dark;
        final int accent;
        final int skin;

        Palette(int primary, int secondary, int dark, int accent, int skin) {
            this.primary = primary;
            this.secondary = secondary;
            this.dark = dark;
            this.accent = accent;
            this.skin = skin;
        }

        static Palette forSeed(int seed, boolean natural) {
            Random random = new Random(seed * 13L + 7L);
            int primary = natural
                    ? Color.rgb(70 + random.nextInt(95),
                    75 + random.nextInt(115),
                    55 + random.nextInt(90))
                    : Color.rgb(75 + random.nextInt(155),
                    65 + random.nextInt(155),
                    65 + random.nextInt(155));
            int secondary = Color.rgb(
                    80 + random.nextInt(160),
                    80 + random.nextInt(160),
                    80 + random.nextInt(160)
            );
            int dark = Color.rgb(
                    28 + random.nextInt(55),
                    28 + random.nextInt(55),
                    28 + random.nextInt(55)
            );
            int accent = Color.rgb(
                    155 + random.nextInt(100),
                    105 + random.nextInt(150),
                    45 + random.nextInt(150)
            );
            int skin = Color.rgb(
                    165 + random.nextInt(75),
                    112 + random.nextInt(90),
                    82 + random.nextInt(85)
            );
            return new Palette(primary, secondary, dark, accent, skin);
        }
    }

    private static final class BinaryBuilder {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        Section putFloats(float[] values) {
            align();
            int offset = output.size();
            ByteBuffer bytes = ByteBuffer.allocate(values.length * 4)
                    .order(ByteOrder.LITTLE_ENDIAN);
            for (float value : values) {
                bytes.putFloat(value);
            }
            write(bytes.array());
            return new Section(offset, values.length * 4);
        }

        Section putInts(int[] values) {
            align();
            int offset = output.size();
            ByteBuffer bytes = ByteBuffer.allocate(values.length * 4)
                    .order(ByteOrder.LITTLE_ENDIAN);
            for (int value : values) {
                bytes.putInt(value);
            }
            write(bytes.array());
            return new Section(offset, values.length * 4);
        }

        Section putBytes(byte[] values) {
            align();
            int offset = output.size();
            write(values);
            return new Section(offset, values.length);
        }

        int size() {
            return output.size();
        }

        byte[] toByteArray() {
            align();
            return output.toByteArray();
        }

        private void align() {
            while ((output.size() & 3) != 0) {
                output.write(0);
            }
        }

        private void write(byte[] values) {
            output.write(values, 0, values.length);
        }
    }

    private static final class Section {
        final int offset;
        final int length;

        Section(int offset, int length) {
            this.offset = offset;
            this.length = length;
        }
    }

    private static final class MeshBuilder {
        static final int AXIS_X = 0;
        static final int AXIS_Y = 1;
        static final int AXIS_Z = 2;

        final FloatList positions = new FloatList();
        final FloatList normals = new FloatList();
        final FloatList uvs = new FloatList();
        final FloatList colors = new FloatList();
        final IntList indices = new IntList();
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;

        int vertexCount() {
            return positions.size / 3;
        }

        int indexCount() {
            return indices.size;
        }

        void addBox(
                float cx,
                float cy,
                float cz,
                float sx,
                float sy,
                float sz,
                float yaw,
                int color
        ) {
            float hx = sx * 0.5f;
            float hy = sy * 0.5f;
            float hz = sz * 0.5f;
            float[][] corners = {
                    {-hx, -hy, -hz}, {hx, -hy, -hz}, {hx, hy, -hz}, {-hx, hy, -hz},
                    {hx, -hy, hz}, {-hx, -hy, hz}, {-hx, hy, hz}, {hx, hy, hz},
                    {-hx, -hy, hz}, {-hx, -hy, -hz}, {-hx, hy, -hz}, {-hx, hy, hz},
                    {hx, -hy, -hz}, {hx, -hy, hz}, {hx, hy, hz}, {hx, hy, -hz},
                    {-hx, hy, -hz}, {hx, hy, -hz}, {hx, hy, hz}, {-hx, hy, hz},
                    {-hx, -hy, hz}, {hx, -hy, hz}, {hx, -hy, -hz}, {-hx, -hy, -hz}
            };
            float[][] faceNormals = {
                    {0f, 0f, -1f}, {0f, 0f, 1f}, {-1f, 0f, 0f},
                    {1f, 0f, 0f}, {0f, 1f, 0f}, {0f, -1f, 0f}
            };
            float cos = (float) Math.cos(yaw);
            float sin = (float) Math.sin(yaw);
            for (int face = 0; face < 6; face++) {
                int base = vertexCount();
                for (int corner = 0; corner < 4; corner++) {
                    float[] local = corners[face * 4 + corner];
                    float x = local[0] * cos - local[2] * sin + cx;
                    float z = local[0] * sin + local[2] * cos + cz;
                    float[] n = faceNormals[face];
                    float nx = n[0] * cos - n[2] * sin;
                    float nz = n[0] * sin + n[2] * cos;
                    addVertex(x, local[1] + cy, z, nx, n[1], nz,
                            corner == 1 || corner == 2 ? 1f : 0f,
                            corner >= 2 ? 1f : 0f,
                            color);
                }
                indices.add(base);
                indices.add(base + 1);
                indices.add(base + 2);
                indices.add(base);
                indices.add(base + 2);
                indices.add(base + 3);
            }
        }

        void addPyramid(
                float cx,
                float baseY,
                float cz,
                float sx,
                float sy,
                float sz,
                int color
        ) {
            float hx = sx * 0.5f;
            float hz = sz * 0.5f;
            float[][] base = {
                    {-hx, baseY, -hz}, {hx, baseY, -hz},
                    {hx, baseY, hz}, {-hx, baseY, hz}
            };
            addQuad(base[3], base[2], base[1], base[0], cx, cz,
                    0f, -1f, 0f, color);
            float[] top = {0f, baseY + sy, 0f};
            for (int i = 0; i < 4; i++) {
                float[] a = base[i];
                float[] b = base[(i + 1) % 4];
                addTriangle(a, b, top, cx, cz, color);
            }
        }

        void addCone(
                float cx,
                float baseY,
                float cz,
                float radius,
                float height,
                int segments,
                int color
        ) {
            addFrustum(cx, baseY, cz, radius, 0f, height, segments, color);
        }

        void addCylinder(
                float cx,
                float cy,
                float cz,
                float radius,
                float height,
                int segments,
                int axis,
                int color
        ) {
            float base = cy - height * 0.5f;
            addFrustumAxis(cx, base, cz, radius, radius, height,
                    Math.max(5, segments), axis, color);
        }

        void addFrustum(
                float cx,
                float baseY,
                float cz,
                float bottomRadius,
                float topRadius,
                float height,
                int segments,
                int color
        ) {
            addFrustumAxis(cx, baseY, cz, bottomRadius, topRadius,
                    height, Math.max(5, segments), AXIS_Y, color);
        }

        private void addFrustumAxis(
                float cx,
                float base,
                float cz,
                float bottomRadius,
                float topRadius,
                float height,
                int segments,
                int axis,
                int color
        ) {
            int sideBase = vertexCount();
            for (int i = 0; i <= segments; i++) {
                double angle = i * Math.PI * 2.0 / segments;
                float c = (float) Math.cos(angle);
                float s = (float) Math.sin(angle);
                float[] bottom = orient(axis, c * bottomRadius, 0f, s * bottomRadius);
                float[] top = orient(axis, c * topRadius, height, s * topRadius);
                float[] normal = orient(axis, c, (bottomRadius - topRadius) / height, s);
                normalize(normal);
                addVertex(cx + bottom[0], base + bottom[1], cz + bottom[2],
                        normal[0], normal[1], normal[2],
                        i / (float) segments, 0f, color);
                addVertex(cx + top[0], base + top[1], cz + top[2],
                        normal[0], normal[1], normal[2],
                        i / (float) segments, 1f, color);
            }
            for (int i = 0; i < segments; i++) {
                int a = sideBase + i * 2;
                int b = a + 1;
                int c = a + 2;
                int d = a + 3;
                indices.add(a);
                indices.add(c);
                indices.add(b);
                indices.add(b);
                indices.add(c);
                indices.add(d);
            }
            addDisc(cx, base, cz, bottomRadius, segments, axis, false, color);
            if (topRadius > 0.0001f) {
                float[] offset = orient(axis, 0f, height, 0f);
                addDisc(cx + offset[0], base + offset[1], cz + offset[2],
                        topRadius, segments, axis, true, color);
            }
        }

        private void addDisc(
                float cx,
                float cy,
                float cz,
                float radius,
                int segments,
                int axis,
                boolean positive,
                int color
        ) {
            int center = vertexCount();
            float[] normal = orient(axis, 0f, positive ? 1f : -1f, 0f);
            addVertex(cx, cy, cz, normal[0], normal[1], normal[2],
                    0.5f, 0.5f, color);
            int ring = vertexCount();
            for (int i = 0; i <= segments; i++) {
                double angle = i * Math.PI * 2.0 / segments;
                float c = (float) Math.cos(angle);
                float s = (float) Math.sin(angle);
                float[] point = orient(axis, c * radius, 0f, s * radius);
                addVertex(cx + point[0], cy + point[1], cz + point[2],
                        normal[0], normal[1], normal[2],
                        0.5f + c * 0.5f, 0.5f + s * 0.5f, color);
            }
            for (int i = 0; i < segments; i++) {
                if (positive) {
                    indices.add(center);
                    indices.add(ring + i);
                    indices.add(ring + i + 1);
                } else {
                    indices.add(center);
                    indices.add(ring + i + 1);
                    indices.add(ring + i);
                }
            }
        }

        void addSphere(
                float cx,
                float cy,
                float cz,
                float rx,
                float ry,
                float rz,
                int latitudeSegments,
                int longitudeSegments,
                int color
        ) {
            int base = vertexCount();
            for (int lat = 0; lat <= latitudeSegments; lat++) {
                double v = lat / (double) latitudeSegments;
                double phi = Math.PI * v;
                float y = (float) Math.cos(phi);
                float ring = (float) Math.sin(phi);
                for (int lon = 0; lon <= longitudeSegments; lon++) {
                    double u = lon / (double) longitudeSegments;
                    double theta = Math.PI * 2.0 * u;
                    float x = ring * (float) Math.cos(theta);
                    float z = ring * (float) Math.sin(theta);
                    float nx = x / Math.max(rx, 0.0001f);
                    float ny = y / Math.max(ry, 0.0001f);
                    float nz = z / Math.max(rz, 0.0001f);
                    float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                    addVertex(cx + x * rx, cy + y * ry, cz + z * rz,
                            nx / length, ny / length, nz / length,
                            (float) u, 1f - (float) v, color);
                }
            }
            int row = longitudeSegments + 1;
            for (int lat = 0; lat < latitudeSegments; lat++) {
                for (int lon = 0; lon < longitudeSegments; lon++) {
                    int a = base + lat * row + lon;
                    int b = a + row;
                    indices.add(a);
                    indices.add(b);
                    indices.add(a + 1);
                    indices.add(a + 1);
                    indices.add(b);
                    indices.add(b + 1);
                }
            }
        }

        private void addQuad(
                float[] a,
                float[] b,
                float[] c,
                float[] d,
                float cx,
                float cz,
                float nx,
                float ny,
                float nz,
                int color
        ) {
            int base = vertexCount();
            addVertex(a[0] + cx, a[1], a[2] + cz, nx, ny, nz, 0f, 0f, color);
            addVertex(b[0] + cx, b[1], b[2] + cz, nx, ny, nz, 1f, 0f, color);
            addVertex(c[0] + cx, c[1], c[2] + cz, nx, ny, nz, 1f, 1f, color);
            addVertex(d[0] + cx, d[1], d[2] + cz, nx, ny, nz, 0f, 1f, color);
            indices.add(base);
            indices.add(base + 1);
            indices.add(base + 2);
            indices.add(base);
            indices.add(base + 2);
            indices.add(base + 3);
        }

        private void addTriangle(
                float[] a,
                float[] b,
                float[] c,
                float cx,
                float cz,
                int color
        ) {
            float ax = a[0] + cx;
            float ay = a[1];
            float az = a[2] + cz;
            float bx = b[0] + cx;
            float by = b[1];
            float bz = b[2] + cz;
            float cxv = c[0] + cx;
            float cy = c[1];
            float czv = c[2] + cz;
            float ux = bx - ax;
            float uy = by - ay;
            float uz = bz - az;
            float vx = cxv - ax;
            float vy = cy - ay;
            float vz = czv - az;
            float nx = uy * vz - uz * vy;
            float ny = uz * vx - ux * vz;
            float nz = ux * vy - uy * vx;
            float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (length < 0.0001f) {
                return;
            }
            nx /= length;
            ny /= length;
            nz /= length;
            int base = vertexCount();
            addVertex(ax, ay, az, nx, ny, nz, 0f, 0f, color);
            addVertex(bx, by, bz, nx, ny, nz, 1f, 0f, color);
            addVertex(cxv, cy, czv, nx, ny, nz, 0.5f, 1f, color);
            indices.add(base);
            indices.add(base + 1);
            indices.add(base + 2);
        }

        private void addVertex(
                float x,
                float y,
                float z,
                float nx,
                float ny,
                float nz,
                float u,
                float v,
                int color
        ) {
            positions.add(x);
            positions.add(y);
            positions.add(z);
            normals.add(nx);
            normals.add(ny);
            normals.add(nz);
            uvs.add(u);
            uvs.add(v);
            colors.add(Color.red(color) / 255f);
            colors.add(Color.green(color) / 255f);
            colors.add(Color.blue(color) / 255f);
            colors.add(1f);
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }

        private static float[] orient(int axis, float radialX, float height, float radialZ) {
            if (axis == AXIS_X) {
                return new float[]{height, radialX, radialZ};
            }
            if (axis == AXIS_Z) {
                return new float[]{radialX, radialZ, height};
            }
            return new float[]{radialX, height, radialZ};
        }

        private static void normalize(float[] value) {
            float length = (float) Math.sqrt(
                    value[0] * value[0]
                            + value[1] * value[1]
                            + value[2] * value[2]
            );
            if (length > 0.0001f) {
                value[0] /= length;
                value[1] /= length;
                value[2] /= length;
            }
        }
    }

    private static final class FloatList {
        float[] values = new float[256];
        int size;

        void add(float value) {
            if (size == values.length) {
                float[] expanded = new float[values.length * 2];
                System.arraycopy(values, 0, expanded, 0, size);
                values = expanded;
            }
            values[size++] = value;
        }

        float[] toArray() {
            float[] result = new float[size];
            System.arraycopy(values, 0, result, 0, size);
            return result;
        }
    }

    private static final class IntList {
        int[] values = new int[256];
        int size;

        void add(int value) {
            if (size == values.length) {
                int[] expanded = new int[values.length * 2];
                System.arraycopy(values, 0, expanded, 0, size);
                values = expanded;
            }
            values[size++] = value;
        }

        int[] toArray() {
            int[] result = new int[size];
            System.arraycopy(values, 0, result, 0, size);
            return result;
        }
    }
}
