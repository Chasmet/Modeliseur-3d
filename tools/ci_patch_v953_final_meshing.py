#!/usr/bin/env python3
from pathlib import Path

ENGINE = Path("app/src/main/java/com/chasmet/modeliseur3d/model/StylizedCharacter3DEngine.java")
MESHER = Path("app/src/main/java/com/chasmet/modeliseur3d/model/MemorySafeIndexedMesher.java")
LAYOUT = Path("app/src/main/res/layout/activity_manual_3d.xml")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: motif attendu 1 fois, trouvé {count}")
    return text.replace(old, new, 1)


# 1) Route le pipeline V9.5 vers le mesher indexé pré-dimensionné.
engine = ENGINE.read_text(encoding="utf-8")
old = """            mesh = SmoothHullMesher.build(
                    finalDensity,
                    profile.width,
                    profile.height,
                    profile.depth,
                    layout,
                    profile.processors,
                    masks,
                    category
            );
            // V9.5 : la densité (~75 Mio au profil extrême) et les masques ne
            // servent plus dès que l'extraction de surface est terminée.
            finalDensity = null;
            masks = null;
            releaseMemory();
            try {
                mesh = MeshSurfaceOptimizer.optimize(mesh, adaptive ? 2 : 1);
            } catch (OutOfMemoryError ignored) {
                // Repli mémoire : le maillage brut est déjà géométriquement valide.
                releaseMemory();
            } catch (RuntimeException ignored) {
                // Le maillage brut reste valide si l'optimisation facultative échoue.
            }
            mesh = MeshOrientationCorrector.correct(mesh);
"""
new = """            mesh = MemorySafeIndexedMesher.build(
                    finalDensity,
                    profile.width,
                    profile.height,
                    profile.depth,
                    layout,
                    masks,
                    category
            );
            // V9.5.3 : l'isosurface est maintenant indexée dès sa création.
            // La densité et les masques sont libérés avant tout traitement optionnel.
            finalDensity = null;
            masks = null;
            releaseMemory();

            Runtime runtime = Runtime.getRuntime();
            long optimizerUsed = runtime.totalMemory() - runtime.freeMemory();
            long optimizerHeadroom = Math.max(0L, runtime.maxMemory() - optimizerUsed);
            boolean optimizerSafe = mesh.getVertexCount() <= 700_000
                    && optimizerHeadroom >= 160L * 1024L * 1024L;
            if (optimizerSafe) {
                try {
                    mesh = MeshSurfaceOptimizer.optimize(mesh, adaptive ? 2 : 1);
                } catch (OutOfMemoryError ignored) {
                    // Le maillage indexé brut est déjà valide : ne jamais perdre le résultat.
                    releaseMemory();
                } catch (RuntimeException ignored) {
                    // L'optimisation reste facultative.
                }
            } else {
                MemoryDiagnostics.mark(
                        "OPTIMISATION SAUTEE • maillage déjà lisse/indexé • "
                                + mesh.getVertexCount() + " sommets"
                );
            }
            mesh = MeshOrientationCorrector.correct(mesh);
"""
engine = replace_once(engine, old, new, "activation mesher indexé + optimizer guard")
ENGINE.write_text(engine, encoding="utf-8")

# 2) Supprime les petites allocations par triangle/quad dans le nouveau mesher.
mesher = MESHER.read_text(encoding="utf-8")
mesher = replace_once(
    mesher,
    "        long[] edgeKeys = new long[4];\n\n        LongIntMap cache = new LongIntMap(1 << 17);\n",
    "        long[] edgeKeys = new long[4];\n"
    "        int[] quadOrder = new int[4];\n"
    "        float[] quadAngles = new float[4];\n\n"
    "        LongIntMap cache = new LongIntMap(1 << 17);\n",
    "buffers quad réutilisables",
)
mesher = replace_once(
    mesher,
    """                                int[] order = orderQuad(
                                        pointX, pointY, pointZ,
                                        normalX, normalY, normalZ
                                );
                                sink.triangle(
                                        grid, guide,
                                        pointX, pointY, pointZ,
                                        normalX, normalY, normalZ,
                                        edgeKeys,
                                        order[0], order[1], order[2]
                                );
                                sink.triangle(
                                        grid, guide,
                                        pointX, pointY, pointZ,
                                        normalX, normalY, normalZ,
                                        edgeKeys,
                                        order[0], order[2], order[3]
                                );
""",
    """                                orderQuad(
                                        pointX, pointY, pointZ,
                                        normalX, normalY, normalZ,
                                        quadOrder, quadAngles
                                );
                                sink.triangle(
                                        grid, guide,
                                        pointX, pointY, pointZ,
                                        normalX, normalY, normalZ,
                                        edgeKeys,
                                        quadOrder[0], quadOrder[1], quadOrder[2]
                                );
                                sink.triangle(
                                        grid, guide,
                                        pointX, pointY, pointZ,
                                        normalX, normalY, normalZ,
                                        edgeKeys,
                                        quadOrder[0], quadOrder[2], quadOrder[3]
                                );
""",
    "quad sans allocation",
)
mesher = replace_once(
    mesher,
    """    private static int[] orderQuad(
            float[] x,
            float[] y,
            float[] z,
            float[] nx,
            float[] ny,
            float[] nz
    ) {
""",
    """    private static void orderQuad(
            float[] x,
            float[] y,
            float[] z,
            float[] nx,
            float[] ny,
            float[] nz,
            int[] order,
            float[] angles
    ) {
""",
    "signature quad sans allocation",
)
mesher = replace_once(
    mesher,
    """        float[] angles = new float[4];
        int[] order = {0, 1, 2, 3};
        for (int i = 0; i < 4; i++) {
""",
    """        order[0] = 0;
        order[1] = 1;
        order[2] = 2;
        order[3] = 3;
        for (int i = 0; i < 4; i++) {
""",
    "initialisation quad réutilisée",
)
mesher = replace_once(
    mesher,
    """        return order;
    }

    private static long packEdge""",
    """    }

    private static long packEdge""",
    "retour quad supprimé",
)

old_projection = """    private static int chooseProjection(
            float nx,
            float ny,
            float nz,
            float gridX,
            float gridY,
            float gridZ,
            int width,
            int height,
            int depth,
            Guide guide
    ) {
        float absoluteX = Math.abs(nx);
        float absoluteY = Math.abs(ny);
        float absoluteZ = Math.abs(nz);
        float[] alignment = {
                Math.max(0.0f, nz),
                Math.max(0.0f, -nz),
                Math.max(0.0f, nx),
                Math.max(0.0f, -nx)
        };
        if (absoluteY > Math.max(absoluteX, absoluteZ) * 1.18f) {
            float radialX = gridX / Math.max(1.0f, width - 1.0f) * 2.0f - 1.0f;
            float radialZ = gridZ / Math.max(1.0f, depth - 1.0f) * 2.0f - 1.0f;
            alignment[SmoothHullMesher.AtlasLayout.FRONT] = Math.max(0.0f, radialZ);
            alignment[SmoothHullMesher.AtlasLayout.BACK] = Math.max(0.0f, -radialZ);
            alignment[SmoothHullMesher.AtlasLayout.RIGHT] = Math.max(0.0f, radialX);
            alignment[SmoothHullMesher.AtlasLayout.LEFT] = Math.max(0.0f, -radialX);
        }
        int best = SmoothHullMesher.AtlasLayout.FRONT;
        float bestScore = Float.NEGATIVE_INFINITY;
        for (int projection = 0; projection < 4; projection++) {
            float support = guide.support(projection, gridX, gridY, gridZ, width, height, depth);
            float score = (0.10f + alignment[projection]) * (0.08f + 0.92f * support);
            if (score > bestScore) {
                bestScore = score;
                best = projection;
            }
        }
        return best;
    }
"""
new_projection = """    private static int chooseProjection(
            float nx,
            float ny,
            float nz,
            float gridX,
            float gridY,
            float gridZ,
            int width,
            int height,
            int depth,
            Guide guide
    ) {
        float absoluteX = Math.abs(nx);
        float absoluteY = Math.abs(ny);
        float absoluteZ = Math.abs(nz);
        float front = Math.max(0.0f, nz);
        float back = Math.max(0.0f, -nz);
        float right = Math.max(0.0f, nx);
        float left = Math.max(0.0f, -nx);
        if (absoluteY > Math.max(absoluteX, absoluteZ) * 1.18f) {
            float radialX = gridX / Math.max(1.0f, width - 1.0f) * 2.0f - 1.0f;
            float radialZ = gridZ / Math.max(1.0f, depth - 1.0f) * 2.0f - 1.0f;
            front = Math.max(0.0f, radialZ);
            back = Math.max(0.0f, -radialZ);
            right = Math.max(0.0f, radialX);
            left = Math.max(0.0f, -radialX);
        }
        int best = SmoothHullMesher.AtlasLayout.FRONT;
        float bestScore = Float.NEGATIVE_INFINITY;
        for (int projection = 0; projection < 4; projection++) {
            float alignment;
            if (projection == SmoothHullMesher.AtlasLayout.FRONT) {
                alignment = front;
            } else if (projection == SmoothHullMesher.AtlasLayout.BACK) {
                alignment = back;
            } else if (projection == SmoothHullMesher.AtlasLayout.RIGHT) {
                alignment = right;
            } else {
                alignment = left;
            }
            float support = guide.support(projection, gridX, gridY, gridZ, width, height, depth);
            float score = (0.10f + alignment) * (0.08f + 0.92f * support);
            if (score > bestScore) {
                bestScore = score;
                best = projection;
            }
        }
        return best;
    }
"""
mesher = replace_once(mesher, old_projection, new_projection, "projection sans float[] par triangle")
MESHER.write_text(mesher, encoding="utf-8")

# 3) Version visible dans l'écran diagnostic.
layout = LAYOUT.read_text(encoding="utf-8")
layout = layout.replace(
    '3D locale V9.5.2 — diagnostic complet + DA3',
    '3D locale V9.5.3 — FINAL meshing indexé + DA3',
    1,
)
LAYOUT.write_text(layout, encoding="utf-8")

print(
    "V9.5.3 FINAL meshing appliqué : sommets indexés, préflight exact, "
    "allocation finale exacte, repli de résolution virtuel si nécessaire, "
    "optimizer protégé et zéro allocation par triangle/quad."
)
