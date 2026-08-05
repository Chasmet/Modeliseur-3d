package com.chasmet.modeliseur3d.assets;

/** Métadonnées d'un asset GLB proposé directement dans l'application. */
public final class Asset3DItem {
    private final String id;
    private final String name;
    private final String category;
    private final String description;
    private final String license;
    private final String credit;
    private final String downloadUrl;
    private final String sourceUrl;
    private final boolean animated;

    public Asset3DItem(
            String id,
            String name,
            String category,
            String description,
            String license,
            String credit,
            String downloadUrl,
            String sourceUrl,
            boolean animated
    ) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.description = description;
        this.license = license;
        this.credit = credit;
        this.downloadUrl = downloadUrl;
        this.sourceUrl = sourceUrl;
        this.animated = animated;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getCategory() {
        return category;
    }

    public String getDescription() {
        return description;
    }

    public String getLicense() {
        return license;
    }

    public String getCredit() {
        return credit;
    }

    public String getDownloadUrl() {
        return downloadUrl;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public boolean isAnimated() {
        return animated;
    }

    public String fileName() {
        return id + ".glb";
    }
}
