from pathlib import Path

ENGINE = Path("app/src/main/java/com/chasmet/modeliseur3d/model/StylizedCharacter3DEngine.java")
HULL = Path("app/src/main/java/com/chasmet/modeliseur3d/model/ContinuousVisualHull.java")
ACTIVITY = Path("app/src/main/java/com/chasmet/modeliseur3d/Manual3DActivity.java")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: motif attendu 1 fois, trouvé {count}")
    return text.replace(old, new, 1)


engine = ENGINE.read_text(encoding="utf-8")
engine = replace_once(
    engine,
    "        validateViews(views);\n",
    "        validateViews(views);\n"
    "        MemoryDiagnostics.mark(\"SEGMENTATION\");\n",
    "diagnostic segmentation",
)
engine = replace_once(
    engine,
    "            notifyProgress(listener, Stage.NEURAL_DEPTH, 0, 1);\n",
    "            MemoryDiagnostics.mark(\"DA3 392\");\n"
    "            notifyProgress(listener, Stage.NEURAL_DEPTH, 0, 1);\n",
    "diagnostic DA3",
)
engine = replace_once(
    engine,
    "        notifyProgress(listener, Stage.BUILDING_HULL, 0, 1);\n\n"
    "        ContinuousVisualHull.Result hull;\n",
    "        MemoryDiagnostics.mark(\"COQUE 3D\");\n"
    "        notifyProgress(listener, Stage.BUILDING_HULL, 0, 1);\n\n"
    "        ContinuousVisualHull.Result hull;\n",
    "diagnostic coque",
)

old_geometry_block = """        SmoothHullMesher.AtlasLayout layout = SmoothHullMesher.AtlasLayout.create(
                profile.width,
                profile.height,
                profile.depth,
                profile.atlasHeight
        );
        Bitmap atlas;
        try {
            atlas = buildAtlas(
                    isolated,
                    bounds,
                    layout,
                    profileCorrection.shouldFlipLeft()
            );
        } finally {
            recycleAll(isolated);
        }

        notifyProgress(listener, Stage.MESHING, 0, 1);
        MeshData mesh;
        try {
            mesh = SmoothHullMesher.build(
                    finalDensity,
                    profile.width,
                    profile.height,
                    profile.depth,
                    layout,
                    profile.processors,
                    masks,
                    category
            );
            try {
                mesh = MeshSurfaceOptimizer.optimize(mesh, adaptive ? 2 : 1);
            } catch (RuntimeException ignored) {
                // Le maillage brut reste valide si l'optimisation facultative échoue.
            }
            mesh = MeshOrientationCorrector.correct(mesh);
        } catch (Exception | OutOfMemoryError error) {
            recycle(atlas);
            throw error;
        }
"""
new_geometry_block = """        final double hullSilhouetteScore = hull.getSilhouetteScore();
        final boolean hullComplexShapeMode = hull.isComplexShapeMode();
        final boolean neuralCpuFallback = neuralPrediction != null
                && neuralPrediction.getBackend().contains("repli NNAPI");

        // V9.5 : finalDensity conserve à lui seul le champ utile. Le wrapper de
        // coque, les cartes de confiance et la prédiction DA3 ne doivent pas
        // rester référencés pendant le marching tetrahedra.
        hull = null;
        confidences = null;
        neuralPrediction = null;
        releaseMemory();

        SmoothHullMesher.AtlasLayout layout = SmoothHullMesher.AtlasLayout.create(
                profile.width,
                profile.height,
                profile.depth,
                profile.atlasHeight
        );

        // V9.5 : le maillage passe AVANT l'atlas. Une texture 4096 x 4096 ARGB
        // représente environ 64 Mo ; ne pas la garder pendant le meshing réduit
        // fortement le pic mémoire du kart + pilote sans aucune baisse de qualité.
        MemoryDiagnostics.mark("MESHING");
        notifyProgress(listener, Stage.MESHING, 0, 1);
        MeshData mesh;
        try {
            mesh = SmoothHullMesher.build(
                    finalDensity,
                    profile.width,
                    profile.height,
                    profile.depth,
                    layout,
                    profile.processors,
                    masks,
                    category
            );
            try {
                mesh = MeshSurfaceOptimizer.optimize(mesh, adaptive ? 2 : 1);
            } catch (RuntimeException ignored) {
                // Le maillage brut reste valide si l'optimisation facultative échoue.
            }
            mesh = MeshOrientationCorrector.correct(mesh);
        } catch (Exception | OutOfMemoryError error) {
            recycleAll(isolated);
            MemoryDiagnostics.mark("MESHING ECHEC");
            throw error;
        }

        // La géométrie est maintenant terminée : seulement ensuite on alloue
        // l'atlas multivue 2K/4K, puis on libère immédiatement les quatre vues.
        MemoryDiagnostics.mark("TEXTURE 4K");
        notifyProgress(listener, Stage.TEXTURING, 0, 1);
        Bitmap atlas;
        try {
            atlas = buildAtlas(
                    isolated,
                    bounds,
                    layout,
                    profileCorrection.shouldFlipLeft()
            );
        } finally {
            recycleAll(isolated);
        }
        notifyProgress(listener, Stage.TEXTURING, 1, 1);
        MemoryDiagnostics.mark("RESULTAT PRET");
"""
engine = replace_once(
    engine,
    old_geometry_block,
    new_geometry_block,
    "maillage avant texture 4K",
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
engine = replace_once(
    engine,
    "            if (neuralPrediction != null\n"
    "                    && neuralPrediction.getBackend().contains(\"repli NNAPI\")) {\n",
    "            if (neuralCpuFallback) {\n",
    "résumé fallback CPU",
)
engine = replace_once(
    engine,
    "        BUILDING_HULL,\n        MESHING\n",
    "        BUILDING_HULL,\n        MESHING,\n        TEXTURING\n",
    "étape texturing",
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

activity = ACTIVITY.read_text(encoding="utf-8")
activity = replace_once(
    activity,
    "            case BUILDING_HULL:\n"
    "                text = \"Fusion continue des quatre silhouettes…\";\n"
    "                break;\n"
    "            default:\n",
    "            case BUILDING_HULL:\n"
    "                text = \"Fusion continue des quatre silhouettes…\";\n"
    "                break;\n"
    "            case TEXTURING:\n"
    "                text = \"Texture multivue 2K/4K…\";\n"
    "                break;\n"
    "            default:\n",
    "texte étape texture",
)
activity = replace_once(
    activity,
    "        runOnUiThread(() -> status.setText(text));\n",
    "        String memory = com.chasmet.modeliseur3d.model.MemoryDiagnostics.mark(stage.name());\n"
    "        String detailedText = text + \" • \" + memory;\n"
    "        runOnUiThread(() -> status.setText(detailedText));\n",
    "RAM visible pendant génération",
)
activity = replace_once(
    activity,
    "        String detail = error instanceof OutOfMemoryError\n"
    "                ? \"mémoire saturée\"\n"
    "                : error.getMessage();\n",
    "        String detail = error instanceof OutOfMemoryError\n"
    "                ? \"mémoire saturée pendant \"\n"
    "                + com.chasmet.modeliseur3d.model.MemoryDiagnostics.describeOom()\n"
    "                : error.getMessage();\n",
    "diagnostic OOM utilisateur",
)
ACTIVITY.write_text(activity, encoding="utf-8")

print(
    "V9.5 memory patch applied: occupancy supprimé; cartes DA3 libérées; "
    "meshing avant atlas 4K; diagnostic Java/natif actif"
)
