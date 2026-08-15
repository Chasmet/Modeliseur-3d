from pathlib import Path

ENGINE = Path("app/src/main/java/com/chasmet/modeliseur3d/model/StylizedCharacter3DEngine.java")
HULL = Path("app/src/main/java/com/chasmet/modeliseur3d/model/ContinuousVisualHull.java")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: motif attendu 1 fois, trouvé {count}")
    return text.replace(old, new, 1)


engine = ENGINE.read_text(encoding="utf-8")
engine = replace_once(
    engine,
    "        SmoothHullMesher.AtlasLayout layout = SmoothHullMesher.AtlasLayout.create(\n",
    "        final double hullSilhouetteScore = hull.getSilhouetteScore();\n"
    "        final boolean hullComplexShapeMode = hull.isComplexShapeMode();\n"
    "        // V9.5 : si DA3 a créé une nouvelle densité, la coque source n'est\n"
    "        // plus utile. Libérer sa référence avant atlas/meshing évite de garder\n"
    "        // deux énormes float[] en vie sur les objets composés larges.\n"
    "        if (finalDensity != hull.getDensity()) {\n"
    "            hull = null;\n"
    "            releaseMemory();\n"
    "        }\n"
    "        SmoothHullMesher.AtlasLayout layout = SmoothHullMesher.AtlasLayout.create(\n",
    "insertion libération coque",
)
engine = replace_once(
    engine,
    "        if (hull.isComplexShapeMode()) {\n",
    "        if (hullComplexShapeMode) {\n",
    "résumé complex shape",
)
engine = replace_once(
    engine,
    "Math.round(hull.getSilhouetteScore() * 100.0)",
    "Math.round(hullSilhouetteScore * 100.0)",
    "résumé silhouette",
)
ENGINE.write_text(engine, encoding="utf-8")

hull = HULL.read_text(encoding="utf-8")
hull = replace_once(
    hull,
    "        boolean[] occupancy = new boolean[size];\n",
    "        // V9.5 : pas de volume boolean[] parallèle. La densité contient déjà\n"
    "        // toute l'information nécessaire pour les projections.\n",
    "suppression allocation occupancy",
)
hull = replace_once(
    hull,
    "                        occupancy[voxel] = true;\n",
    "",
    "suppression écriture occupancy",
)
hull = replace_once(
    hull,
    "        double silhouetteScore = projectionScore(\n"
    "                occupancy, frontUnion, sideUnion, width, height, depth\n"
    "        );\n",
    "        double silhouetteScore = projectionScore(\n"
    "                density, frontUnion, sideUnion, width, height, depth\n"
    "        );\n",
    "projection depuis density",
)
hull = replace_once(
    hull,
    "                density,\n                occupancy,\n                occupied,\n",
    "                density,\n                null,\n                occupied,\n",
    "Result occupancy null",
)
hull = replace_once(
    hull,
    "    private static double projectionScore(\n            boolean[] occupancy,\n",
    "    private static double projectionScore(\n            float[] density,\n",
    "signature projectionScore",
)
hull = replace_once(
    hull,
    "                    if (occupancy[base + z]) {\n",
    "                    if (density[base + z] >= ISO) {\n",
    "projection threshold density",
)
HULL.write_text(hull, encoding="utf-8")

print("V9.5 memory patch applied: hull source released before meshing; occupancy array removed")
