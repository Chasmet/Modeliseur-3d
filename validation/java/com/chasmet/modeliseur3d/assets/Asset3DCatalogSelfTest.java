package com.chasmet.modeliseur3d.assets;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class Asset3DCatalogSelfTest {
    public static void main(String[] args) {
        List<Asset3DItem> assets = Asset3DCatalog.all();
        if (assets.size() != 259) {
            throw new AssertionError(
                    "Le catalogue V5.9.10 doit contenir exactement 259 assets : "
                            + assets.size()
            );
        }
        Set<String> ids = new HashSet<>();
        int animated = 0;
        int generated = 0;
        for (Asset3DItem item : assets) {
            if (!ids.add(item.getId())) {
                throw new AssertionError("Identifiant asset dupliqué : " + item.getId());
            }
            if (item.isGenerated()) {
                generated++;
                if (!item.getDownloadUrl().startsWith("generated://")) {
                    throw new AssertionError("Générateur local invalide : " + item.getName());
                }
                if (!item.getLicense().contains("CC0")) {
                    throw new AssertionError("Asset local non CC0 : " + item.getName());
                }
            } else {
                if (!item.getDownloadUrl().startsWith("https://")) {
                    throw new AssertionError("URL non sécurisée : " + item.getName());
                }
                if (!item.getDownloadUrl().endsWith(".glb")) {
                    throw new AssertionError("GLB distant absent : " + item.getName());
                }
            }
            String license = item.getLicense().toUpperCase();
            if (license.contains("NC") || license.contains("EULA")) {
                throw new AssertionError("Licence restrictive interdite : " + item.getName());
            }
            if (item.getLicense().trim().isEmpty() || item.getCredit().trim().isEmpty()) {
                throw new AssertionError("Licence ou crédit absent : " + item.getName());
            }
            if (item.isAnimated()) {
                animated++;
            }
        }
        if (generated != 247) {
            throw new AssertionError("247 assets locaux attendus : " + generated);
        }
        if (animated != 112) {
            throw new AssertionError("112 assets animés attendus : " + animated);
        }
        if (Asset3DCatalog.filter(Asset3DCatalog.ANIMATED).size() != animated) {
            throw new AssertionError("Le filtre Animés ne correspond pas aux métadonnées");
        }
        requireCategory(Asset3DCatalog.ROADS, 18);
        requireCategory(Asset3DCatalog.WALLS, 18);
        requireCategory(Asset3DCatalog.NATURE, 19);
        requireCategory(Asset3DCatalog.WATER, 16);
        requireCategory(Asset3DCatalog.CHARACTERS, 21);
        requireCategory(Asset3DCatalog.ANIMALS, 19);
        requireCategory(Asset3DCatalog.FANTASY, 18);
        requireCategory(Asset3DCatalog.WEAPONS, 20);
        requireCategory(Asset3DCatalog.PORTS, 20);
        requireCategory(Asset3DCatalog.FOOD, 20);
        requireCategory(Asset3DCatalog.MAGIC, 20);
        requireCategory(Asset3DCatalog.FURNITURE, 20);
        for (String category : Asset3DCatalog.categories()) {
            if (!Asset3DCatalog.ALL.equals(category)
                    && Asset3DCatalog.filter(category).isEmpty()) {
                throw new AssertionError("Catégorie vide : " + category);
            }
        }
        System.out.println("Asset3DCatalogSelfTest V5.9.10 OK : "
                + assets.size() + " assets, " + animated + " animés, "
                + generated + " générés hors ligne");
    }

    private static void requireCategory(String category, int minimum) {
        int count = Asset3DCatalog.filter(category).size();
        if (count < minimum) {
            throw new AssertionError(category + " doit contenir au moins "
                    + minimum + " assets : " + count);
        }
    }
}
