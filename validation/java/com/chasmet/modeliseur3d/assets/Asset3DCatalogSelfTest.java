package com.chasmet.modeliseur3d.assets;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class Asset3DCatalogSelfTest {
    public static void main(String[] args) {
        List<Asset3DItem> assets = Asset3DCatalog.all();
        if (assets.size() < 20) {
            throw new AssertionError("Le catalogue doit contenir au moins 20 assets");
        }
        Set<String> ids = new HashSet<>();
        int animated = 0;
        for (Asset3DItem item : assets) {
            if (!ids.add(item.getId())) {
                throw new AssertionError("Identifiant asset dupliqué : " + item.getId());
            }
            if (!item.getDownloadUrl().startsWith("https://")) {
                throw new AssertionError("URL non sécurisée : " + item.getName());
            }
            if (!item.getDownloadUrl().endsWith(".glb")) {
                throw new AssertionError("Le catalogue doit proposer des GLB : " + item.getName());
            }
            if (item.getLicense().trim().isEmpty() || item.getCredit().trim().isEmpty()) {
                throw new AssertionError("Licence ou crédit absent : " + item.getName());
            }
            if (item.isAnimated()) {
                animated++;
            }
        }
        if (animated < 7) {
            throw new AssertionError("Le catalogue doit contenir plusieurs assets animés");
        }
        for (String category : Asset3DCatalog.categories()) {
            if (!Asset3DCatalog.ALL.equals(category)
                    && Asset3DCatalog.filter(category).isEmpty()) {
                throw new AssertionError("Catégorie vide : " + category);
            }
        }
        System.out.println("Asset3DCatalogSelfTest V5.9.8 OK : "
                + assets.size() + " assets, " + animated + " animés");
    }
}
