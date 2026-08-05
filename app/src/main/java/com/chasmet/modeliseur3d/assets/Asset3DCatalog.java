package com.chasmet.modeliseur3d.assets;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Catalogue V5.9.10 : 259 GLB libres, dont plus de 100 animés.
 *
 * Une petite sélection provient du dépôt officiel Khronos. La majorité des
 * assets utiles au jeu est générée localement au format GLB 2.0 sous CC0.
 */
public final class Asset3DCatalog {
    public static final String ALL = "Tous";
    public static final String ANIMATED = "Animés";
    public static final String CHARACTERS = "Personnages";
    public static final String ANIMALS = "Animaux";
    public static final String FANTASY = "Animaux fantastiques";
    public static final String VEHICLES = "Véhicules";
    public static final String ROADS = "Routes et sols";
    public static final String WALLS = "Murs et bâtiments";
    public static final String NATURE = "Montagnes et nature";
    public static final String WATER = "Eau et mer";
    public static final String WEAPONS = "Armes et équipements";
    public static final String PORTS = "Ports et maritime";
    public static final String FOOD = "Nourriture et marché";
    public static final String MAGIC = "Magie et effets";
    public static final String FURNITURE = "Mobilier et intérieur";
    public static final String OBJECTS = "Objets";

    private static final String RAW =
            "https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models/";
    private static final String SOURCE =
            "https://github.com/KhronosGroup/glTF-Sample-Assets/tree/main/Models/";

    private static final String[] CHARACTER_NAMES = {
            "Capitaine pirate", "Pirate sabreur", "Pirate tireur",
            "Garde maritime", "Soldat du royaume", "Marchand ambulant",
            "Forgeron", "Pêcheur", "Exploratrice", "Sorcière",
            "Mage", "Chevalier", "Ninja", "Marin", "Villageois",
            "Reine", "Roi", "Aventurier"
    };

    private static final String[] ANIMAL_NAMES = {
            "Chien", "Chat", "Cheval", "Vache", "Cochon", "Mouton",
            "Loup", "Ours", "Cerf", "Aigle", "Perroquet", "Mouette",
            "Poisson tropical", "Requin", "Dauphin", "Tortue marine",
            "Crabe", "Pieuvre"
    };

    private static final String[] FANTASY_NAMES = {
            "Dragon", "Wyverne", "Griffon", "Phénix", "Licorne",
            "Kraken", "Serpent marin", "Loup d'ombre", "Ours de glace",
            "Lézard de feu", "Araignée géante", "Golem", "Slime",
            "Tortue de cristal", "Oiseau-tonnerre", "Sanglier démoniaque",
            "Poisson abyssal", "Chimère"
    };

    private static final String[] VEHICLE_NAMES = {
            "Charrette", "Chariot couvert", "Calèche", "Voiture légère",
            "Camion", "Canoë", "Barque", "Voilier", "Navire pirate",
            "Navire marchand", "Dirigeable", "Traîneau"
    };

    private static final String[] ROAD_NAMES = {
            "Route droite", "Route courbe", "Chemin de terre",
            "Chemin de sable", "Route enneigée", "Pont en bois",
            "Place pavée", "Croisement routier", "Carrefour en T",
            "Route de port", "Sentier de forêt", "Sentier de montagne",
            "Route de lave", "Quai en planches", "Passage de château",
            "Sol de village", "Route de royaume", "Piste côtière"
    };

    private static final String[] WALL_NAMES = {
            "Mur de pierre", "Mur de briques", "Mur en bois",
            "Porte fortifiée", "Tour de garde", "Maison du village",
            "Cabane", "Phare", "Arche en ruine", "Rempart",
            "Palissade", "Entrepôt du port", "Temple", "Colonne ancienne",
            "Mur enneigé", "Mur volcanique", "Pont de pierre",
            "Entrée de grotte"
    };

    private static final String[] NATURE_NAMES = {
            "Montagne rocheuse", "Montagne enneigée", "Volcan",
            "Colline verte", "Falaise", "Grand rocher", "Pin",
            "Palmier", "Arbre tropical", "Buisson", "Cactus",
            "Cristaux", "Champignons géants", "Souche", "Forêt miniature",
            "Roche de lave", "Pic de glace", "Plateau montagneux"
    };

    private static final String[] WATER_NAMES = {
            "Eau calme", "Rivière", "Lac", "Cascade", "Iceberg",
            "Tourbillon", "Récif corallien", "Marécage", "Vague marine",
            "Lagon", "Fontaine", "Geyser", "Banquise", "Mer agitée",
            "Bassin du port"
    };

    private static final String[] WEAPON_NAMES = {
            "Sabre pirate", "Épée courte", "Épée royale", "Katana",
            "Dague", "Hache", "Hache double", "Lance", "Trident", "Arc",
            "Arbalète", "Bâton magique", "Masse", "Marteau de guerre",
            "Bouclier rond", "Bouclier royal", "Fusil pirate",
            "Pistolet pirate", "Canon portatif", "Grappin"
    };

    private static final String[] PORT_NAMES = {
            "Quai en pierre", "Quai en bois", "Ponton", "Jetée",
            "Grue portuaire", "Filets de pêche", "Bouée", "Balise maritime",
            "Petite épave", "Grande épave", "Mât cassé",
            "Tente du marché du port", "Cabane de pêcheur", "Caisse de poissons",
            "Barque renversée", "Ancre géante", "Portail du port",
            "Tour de vigie", "Chantier naval", "Phare secondaire"
    };

    private static final String[] FOOD_NAMES = {
            "Étal de fruits", "Étal de légumes", "Étal de poissons",
            "Étal de viandes", "Étal de pains", "Étal de fromages",
            "Gâteau géant", "Bonbons", "Sucettes", "Barils d'épices",
            "Sacs de riz", "Cageots de nourriture", "Table de banquet",
            "Grande marmite", "Four de cuisine", "Tonnelet de boisson",
            "Bouteilles", "Panier de provisions", "Poulet rôti",
            "Poisson grillé"
    };

    private static final String[] MAGIC_NAMES = {
            "Portail magique bleu", "Portail magique rouge", "Aura lumineuse",
            "Orbe magique", "Cristaux flottants", "Cercle d'invocation",
            "Vortex", "Flammes magiques", "Explosion de glace", "Éclair",
            "Fumée mystique", "Brume enchantée", "Âme errante", "Rune",
            "Coffre enchanté", "Pierre en lévitation", "Champ de force",
            "Plume magique", "Lanterne spectrale", "Météore"
    };

    private static final String[] FURNITURE_NAMES = {
            "Table en bois", "Chaise", "Banc", "Lit", "Armoire",
            "Étagère", "Comptoir de boutique", "Bar", "Tabouret", "Bureau",
            "Trône", "Coffre de rangement", "Tapis", "Chandelier",
            "Cheminée", "Bibliothèque", "Lit de capitaine", "Hamac",
            "Table en tonneau", "Siège en caisse"
    };

    private static final String[] OBJECT_NAMES = {
            "Coffre au trésor", "Tonneau", "Caisse", "Torche",
            "Lanterne", "Canon", "Ancre", "Épée", "Bouclier",
            "Potion", "Gemme rare", "Cristal magique"
    };

    private Asset3DCatalog() {
    }

    public static List<String> categories() {
        List<String> categories = new ArrayList<>();
        categories.add(ALL);
        categories.add(ANIMATED);
        categories.add(CHARACTERS);
        categories.add(ANIMALS);
        categories.add(FANTASY);
        categories.add(VEHICLES);
        categories.add(ROADS);
        categories.add(WALLS);
        categories.add(NATURE);
        categories.add(WATER);
        categories.add(WEAPONS);
        categories.add(PORTS);
        categories.add(FOOD);
        categories.add(MAGIC);
        categories.add(FURNITURE);
        categories.add(OBJECTS);
        return Collections.unmodifiableList(categories);
    }

    public static List<Asset3DItem> all() {
        List<Asset3DItem> assets = new ArrayList<>();
        addOfficialAssets(assets);
        addGeneratedSeries(assets, "character", CHARACTER_NAMES, CHARACTERS,
                "Personnage low-poly texturé avec animation légère intégrée.", true);
        addGeneratedSeries(assets, "animal", ANIMAL_NAMES, ANIMALS,
                "Animal low-poly texturé, léger et animé pour le monde vivant.", true);
        addGeneratedSeries(assets, "fantasy", FANTASY_NAMES, FANTASY,
                "Créature fantastique low-poly texturée avec animation intégrée.", true);
        addGeneratedSeries(assets, "vehicle", VEHICLE_NAMES, VEHICLES,
                "Véhicule léger avec matériaux texturés et déplacement animé.", true);
        addGeneratedSeries(assets, "road", ROAD_NAMES, ROADS,
                "Élément de route ou de sol texturé, prêt à assembler.", false);
        addGeneratedSeries(assets, "wall", WALL_NAMES, WALLS,
                "Mur, bâtiment ou structure légère avec texture procédurale.", false);
        addGeneratedSeries(assets, "nature", NATURE_NAMES, NATURE,
                "Élément naturel low-poly pour îles, montagnes et royaumes.", false);
        addGeneratedSeries(assets, "water", WATER_NAMES, WATER,
                "Élément aquatique texturé avec animation légère intégrée.", true);
        addGeneratedSeries(assets, "object", WEAPON_NAMES, WEAPONS,
                "Arme ou équipement low-poly texturé pour personnages et décors.", false);
        addGeneratedSeries(assets, "wall", PORT_NAMES, PORTS,
                "Élément maritime low-poly pour ports, quais et villages côtiers.", false);
        addGeneratedSeries(assets, "object", FOOD_NAMES, FOOD,
                "Élément de nourriture ou de marché texturé pour les royaumes.", false);
        addGeneratedSeries(assets, "fantasy", MAGIC_NAMES, MAGIC,
                "Effet magique GLB texturé avec boucle d'animation intégrée.", true);
        addGeneratedSeries(assets, "wall", FURNITURE_NAMES, FURNITURE,
                "Mobilier low-poly texturé pour maisons, boutiques et navires.", false);
        for (int index = 0; index < OBJECT_NAMES.length; index++) {
            assets.add(generatedItem(
                    "object_base_" + index,
                    OBJECT_NAMES[index],
                    OBJECTS,
                    "Objet de jeu low-poly texturé, prêt pour inventaires et décors.",
                    "object",
                    index,
                    index == 3 || index == 4 || index == 9 || index == 11
            ));
        }
        return Collections.unmodifiableList(assets);
    }

    public static List<Asset3DItem> filter(String category) {
        if (category == null || ALL.equals(category)) {
            return all();
        }
        List<Asset3DItem> filtered = new ArrayList<>();
        for (Asset3DItem item : all()) {
            if (ANIMATED.equals(category)) {
                if (item.isAnimated()) {
                    filtered.add(item);
                }
            } else if (category.equals(item.getCategory())) {
                filtered.add(item);
            }
        }
        return filtered;
    }

    public static int countAnimated() {
        int count = 0;
        for (Asset3DItem item : all()) {
            if (item.isAnimated()) {
                count++;
            }
        }
        return count;
    }

    public static int countGenerated() {
        int count = 0;
        for (Asset3DItem item : all()) {
            if (item.isGenerated()) {
                count++;
            }
        }
        return count;
    }

    private static void addOfficialAssets(List<Asset3DItem> assets) {
        assets.add(official("rigged_simple", "Personnage articulé simple",
                CHARACTERS, "Personnage avec squelette et animation.",
                "CC BY 4.0", "Cesium", "RiggedSimple", true));
        assets.add(official("rigged_figure", "Personnage articulé complet",
                CHARACTERS, "Silhouette humanoïde avec armature et animation.",
                "CC BY 4.0", "Cesium", "RiggedFigure", true));
        assets.add(official("recursive_skeletons", "Personnages squelettés",
                CHARACTERS, "Plusieurs structures de squelette animées.",
                "CC BY 4.0", "Cesium / Fran Calvente / DGG",
                "RecursiveSkeletons", true));
        assets.add(official("fox_animated", "Renard animé", ANIMALS,
                "Renard avec animations observation, marche et course.",
                "CC0 + CC BY 4.0", "PixelMannen / tomkranis / AsoboStudio",
                "Fox", true));
        assets.add(official("toy_car", "Voiture jouet détaillée", VEHICLES,
                "Voiture complète avec matériaux modernes.",
                "CC0 1.0", "Adobe", "ToyCar", false));
        assets.add(official("simple_instancing", "Décor avec objets répétés",
                NATURE, "Scène légère utilisant plusieurs instances.",
                "CC0 1.0", "Marco Hutter", "SimpleInstancing", false));
        assets.add(official("alpha_blend_scene", "Décor transparences", WATER,
                "Scène de référence pour surfaces transparentes.",
                "CC BY 4.0", "Analytical Graphics / Ed Mackey",
                "AlphaBlendModeTest", false));
        assets.add(official("animated_colors_cube", "Cube couleurs animées",
                OBJECTS, "Animation de couleurs pour effets magiques.",
                "CC0 1.0", "Ed Mackey", "AnimatedColorsCube", true));
        assets.add(official("box_animated", "Caisse animée", OBJECTS,
                "Boîte avec rotation et translation animées.",
                "CC BY 4.0", "Cesium", "BoxAnimated", true));
        assets.add(official("cube_visibility", "Cube apparition et disparition",
                OBJECTS, "Objet animé utilisant la visibilité des nœuds.",
                "CC0 1.0", "Aaron Franke / Khronos Group",
                "CubeVisibility", true));
        assets.add(official("box_vertex_colors", "Caisse colorée légère",
                OBJECTS, "Objet très léger avec couleurs intégrées au maillage.",
                "CC0 1.0", "Marco Hutter", "BoxVertexColors", false));
        assets.add(official("clearcoat_car_paint",
                "Matériau peinture automobile", OBJECTS,
                "Boule de démonstration avec peinture brillante.",
                "CC0 1.0", "Eric Chadwick", "ClearCoatCarPaint", false));
    }

    private static void addGeneratedSeries(
            List<Asset3DItem> assets,
            String kind,
            String[] names,
            String category,
            String description,
            boolean animated
    ) {
        for (int index = 0; index < names.length; index++) {
            assets.add(generatedItem(
                    categoryKey(category) + "_" + index,
                    names[index],
                    category,
                    description,
                    kind,
                    index,
                    animated
            ));
        }
    }

    private static String categoryKey(String category) {
        return Integer.toHexString(category.hashCode());
    }

    private static Asset3DItem generatedItem(
            String id,
            String name,
            String category,
            String description,
            String kind,
            int variant,
            boolean animated
    ) {
        return new Asset3DItem(
                "local_" + id,
                name,
                category,
                description,
                "CC0 1.0",
                "Généré localement par Modéliseur 3D",
                "generated://" + kind + "/" + variant,
                "generated://license",
                animated
        );
    }

    private static Asset3DItem official(
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
