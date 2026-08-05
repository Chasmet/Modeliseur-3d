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
                "corset_character",
                "Mannequin avec corset",
                CHARACTERS,
                "Personnage féminin habillé, utile pour tester les vêtements.",
                "CC0 1.0",
                "Microsoft / UX3D",
                "Corset",
                false
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
                "barramundi_fish",
                "Poisson barramundi",
                ANIMALS,
                "Poisson texturé léger pour les scènes marines.",
                "CC0 1.0",
                "Microsoft",
                "BarramundiFish",
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
                "car_concept",
                "Voiture concept",
                VEHICLES,
                "Concept-car détaillé avec variantes de matériaux. Logos Khronos présents.",
                "CC BY 4.0",
                "Darmstadt Graphics Group",
                "CarConcept",
                false
        ));

        assets.add(item(
                "multiple_scenes",
                "Décor à scènes multiples",
                WORLDS,
                "Petit environnement contenant plusieurs scènes glTF.",
                "CC0 1.0",
                "Public",
                "MultipleScenes",
                false
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
                "beautiful_game",
                "Grande scène d'échecs",
                WORLDS,
                "Échiquier complet utilisable comme décor ou scène d'intérieur.",
                "CC BY 4.0",
                "MaterialX Project / Ed Mackey",
                "ABeautifulGame",
                false
        ));
        assets.add(item(
                "directional_light_scene",
                "Scène éclairage directionnel",
                WORLDS,
                "Décor technique avec éclairage et objets de référence.",
                "CC0 1.0",
                "Rickard Sahlin",
                "DirectionalLight",
                false
        ));
        assets.add(item(
                "diffuse_plant",
                "Plante décorative",
                WORLDS,
                "Plante en pot pour ville, maison, port ou royaume naturel.",
                "CC0 + CC BY 4.0",
                "Rico Cilliers / Darmstadt Graphics Group",
                "DiffuseTransmissionPlant",
                true
        ));

        assets.add(item(
                "animated_cube",
                "Cube animé",
                OBJECTS,
                "Objet simple animé par rotation et translation.",
                "CC0 1.0",
                "Microsoft",
                "AnimatedCube",
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
                "flight_helmet",
                "Casque de pilote",
                OBJECTS,
                "Casque détaillé utilisable comme équipement ou décoration.",
                "CC0 1.0",
                "Gary Hsu",
                "FlightHelmet",
                false
        ));
        assets.add(item(
                "water_bottle",
                "Bouteille d'eau",
                OBJECTS,
                "Objet PBR léger pour les décors et inventaires.",
                "CC0 1.0",
                "Microsoft",
                "WaterBottle",
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
        assets.add(item(
                "clearcoat_wicker",
                "Boule en osier verni",
                OBJECTS,
                "Objet décoratif avec matériau osier et vernis.",
                "CC0 1.0",
                "Eric Chadwick",
                "ClearcoatWicker",
                false
        ));
        assets.add(item(
                "transmission_teacup",
                "Tasse translucide",
                OBJECTS,
                "Tasse décorative avec matériau translucide.",
                "CC0 1.0",
                "Poly Haven / Eric Chadwick",
                "DiffuseTransmissionTeacup",
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
