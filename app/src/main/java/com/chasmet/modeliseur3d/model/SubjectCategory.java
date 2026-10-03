package com.chasmet.modeliseur3d.model;

/**
 * Famille structurelle utilisée par le reconstructeur quatre vues.
 *
 * <p>Ce choix ne prétend pas reconnaître une espèce ou un objet précis. Il
 * sélectionne seulement le bon prior géométrique : membres verticaux,
 * quadrupède allongé, conducteur posé sur un véhicule, volume architectural
 * ou ramifications végétales.</p>
 */
public enum SubjectCategory {
    AUTO("Auto — analyser la forme"),
    CHARACTER("Personnage"),
    ANIMAL("Animal / quadrupède"),
    COMPOSITE_VEHICLE("Personnage + véhicule / objet composé"),
    ARCHITECTURE_OBJECT("Habitation / objet rigide"),
    PLANT("Arbre / fleur / plante");

    private final String displayName;

    SubjectCategory(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
