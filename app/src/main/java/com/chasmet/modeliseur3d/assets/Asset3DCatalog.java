package com.chasmet.modeliseur3d.assets;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Catalogue intégré de modèles glTF provenant du dépôt officiel Khronos.
 * Chaque téléchargement est contrôlé à 8 Mo maximum par Asset3DDownloader.
 */
public final class Asset3DCatalog {
    public static final String ALL = "Tous";
    public static final String CHARACTERS = "Personnages";
    public static final String ANIMALS = "Animaux";
    public static final String VEHICLES = "Véhicules";
    public static final String WORLDS = "Mondes et décors";
    public static final String OBJECTS = "Objets";

    private static final String RAW =
            "https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models/";
    private static final String SOURCE =
            "https://github.com/KhronosGroup/glTF-Sample-Assets/tree/main/Models/";

    private Asset3DCatalog() {
    }

    public static List<String> categories() {
        List<String> categories = new ArrayList<>();
        categories.add(ALL);
        categories.add(CHARACTERS);
        categories.add(ANIMALS);
        categories.add(VEHICLES);
        categories.add(WORLDS);
        categories.add(OBJECTS);
        return Collections.unmodifiableList(categories);
    }

    public static List<Asset3DItem> all() {
        List<Asset3DItem> assets = new ArrayList<>();

        assets.add(item(
                "rigged_simple",
                "Personnage articulé simple",
                CHARACTERS,
                "Personnage de test avec squelette et animation.",
                "CC BY 4.0",
                "Cesium",
                "RiggedSimple",
                true
        ));
        assets.add(item(
                "rigged_figure",
                "Personnage articulé complet",
                CHARACTERS,
                "Silhouette humanoïde avec armature et animation.",
                "CC BY 4.0",
                "Cesium",
                "RiggedFigure",
                true
        ));
        assets.add(item(
                "cesium_man",
                "Personnage Cesium animé",
                CHARACTERS,
                "Personnage texturé avec squelette et animation. Logo Cesium présent.",
                "CC BY 4.0 avec limites de marque",
                "Cesium",
                "CesiumMan",
                true
        ));
        assets.add(item(
                "recursive_skeletons",
                "Personnages squelettés",
                CHARACTERS,
                "Plusieurs structures de squelette animées pour les tests.",
                "CC0 + CC BY 4.0",
                "Fran Calvente / Darmstadt Graphics Group",
                "RecursiveSkeletons",
                true
        ));
        assets.add(item(
                "brain_stem",
                "Créature organique animée",
                CHARACTERS,
                "Modèle organique complexe avec squelette et animation.",
                "CC BY 4.0",
                "Microsoft",
                "BrainStem",
                true
        ));

        assets.add(item(
                "fox_animated",
                "Renard animé",
                ANIMALS,
                "Renard low-poly avec animations marche, course et observation.",
                "CC0 + CC BY 4.0",
                "PixelMannen / tomkranis / AsoboStudio",
                "Fox",
                true
        ));
        assets.add(item(
                "duck",
                "Canard",
                ANIMALS,
                "Animal léger et texturé pour les îles, étangs et ports.",
                "CC0 1.0",
                "Khronos Group",
                "Duck",
                false
        ));

        assets.add(item(
                "toy_car",
                "Voiture jouet",
                VEHICLES,
                "Voiture 3D complète avec matériaux modernes.",
                "CC0 1.0",
                "Adobe",
                "ToyCar",
                false
        ));
        assets.add(item(
                "cesium_milk_truck",
                "Camion Cesium animé",
                VEHICLES,
                "Petit camion texturé avec animation. Logo Cesium présent.",
                "CC BY 4.0 avec limites de marque",
                "Cesium",
                "CesiumMilkTruck",
                true
        ));

        assets.add(item(
                "simple_instancing",
                "Décor avec objets répétés",
                WORLDS,
                "Scène légère utilisant plusieurs instances d'un même objet.",
                "CC0 1.0",
                "Marco Hutter",
                "SimpleInstancing",
                false
        ));
        assets.add(item(
                "alpha_blend_scene",
                "Décor transparences",
                WORLDS,
                "Scène de référence avec plusieurs objets transparents.",
                "CC0 1.0",
                "Khronos Group",
                "AlphaBlendModeTest",
                false
        ));
        assets.add(item(
                "textured_box_scene",
                "Décor caisse texturée",
                WORLDS,
                "Petit élément de décor texturé, léger et réutilisable.",
                "CC0 1.0",
                "Khronos Group",
                "BoxTextured",
                false
        ));
        assets.add(item(
                "punctual_lamp_scene",
                "Décor avec lampes",
                WORLDS,
                "Petite scène équipée de plusieurs éclairages ponctuels.",
                "CC0 1.0",
                "Khronos Group",
                "LightsPunctualLamp",
                false
        ));
        assets.add(item(
                "light_visibility_scene",
                "Décor lumière animée",
                WORLDS,
                "Scène technique montrant l'apparition et la disparition de lumières.",
                "CC0 1.0",
                "Khronos Group",
                "LightVisibility",
                true
        ));

        assets.add(item(
                "morph_stress",
                "Forme morphing animée",
                OBJECTS,
                "Objet de test avec plusieurs transformations de forme animées.",
                "CC0 1.0",
                "Khronos Group",
                "MorphStressTest",
                true
        ));
        assets.add(item(
                "animated_morph_cube",
                "Cube morphing animé",
                OBJECTS,
                "Objet simple qui change de forme par animation.",
                "CC0 1.0",
                "Khronos Group",
                "AnimatedMorphCube",
                true
        ));
        assets.add(item(
                "animated_colors_cube",
                "Cube couleurs animées",
                OBJECTS,
                "Cube avec animation de couleur pour effets magiques.",
                "CC0 1.0",
                "Ed Mackey",
                "AnimatedColorsCube",
                true
        ));
        assets.add(item(
                "box_animated",
                "Caisse animée",
                OBJECTS,
                "Boîte avec animations de rotation et déplacement.",
                "CC BY 4.0",
                "Cesium",
                "BoxAnimated",
                true
        ));
        assets.add(item(
                "cube_visibility",
                "Cube apparition/disparition",
                OBJECTS,
                "Objet animé utilisant la visibilité des nœuds.",
                "CC0 1.0",
                "Aaron Franke / Khronos Group",
                "CubeVisibility",
                true
        ));
        assets.add(item(
                "damaged_helmet",
                "Casque de combat endommagé",
                OBJECTS,
                "Casque détaillé utilisable comme équipement ou décoration.",
                "CC0 1.0",
                "Leonardo Carrion / Khronos Group",
                "DamagedHelmet",
                false
        ));
        assets.add(item(
                "box_vertex_colors",
                "Caisse aux couleurs de sommets",
                OBJECTS,
                "Objet très léger avec couleurs intégrées au maillage.",
                "CC0 1.0",
                "Khronos Group",
                "BoxVertexColors",
                false
        ));
        assets.add(item(
                "clearcoat_car_paint",
                "Boule peinture automobile",
                OBJECTS,
                "Objet de matériau brillant utile pour tester les métaux.",
                "CC0 1.0",
                "Eric Chadwick",
                "ClearCoatCarPaint",
                false
        ));

        return Collections.unmodifiableList(assets);
    }

    public static List<Asset3DItem> filter(String category) {
        if (category == null || ALL.equals(category)) {
            return all();
        }
        List<Asset3DItem> filtered = new ArrayList<>();
        for (Asset3DItem item : all()) {
            if (category.equals(item.getCategory())) {
                filtered.add(item);
            }
        }
        return filtered;
    }

    private static Asset3DItem item(
            String id,
            String name,
            String category,
            String description,
            String license,
            String credit,
            String modelDirectory,
            boolean animated
    ) {
        String file = modelDirectory + ".glb";
        return new Asset3DItem(
                id,
                name,
                category,
                description,
                license,
                credit,
                RAW + modelDirectory + "/glTF-Binary/" + file,
                SOURCE + modelDirectory,
                animated
        );
    }
}
