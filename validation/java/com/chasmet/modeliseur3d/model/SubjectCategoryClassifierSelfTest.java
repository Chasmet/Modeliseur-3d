package com.chasmet.modeliseur3d.model;

/** Régressions JVM des familles structurelles V7.3. */
public final class SubjectCategoryClassifierSelfTest {
    private SubjectCategoryClassifierSelfTest() {
    }

    public static void main(String[] args) {
        detectsAnElongatedAnimal();
        detectsARiderOnAWideVehicle();
        detectsABoxyBuilding();
        detectsATreeCanopy();
        manualSelectionAlwaysWins();
        preservesQuadrupedLegTracksAcrossTemporaryMerge();
        doesNotCutAnimalTorso();
        keepsVehicleBaseSolid();
        System.out.println("SubjectCategoryClassifierSelfTest: OK");
    }

    private static void detectsAnElongatedAnimal() {
        int width = 40;
        int height = 48;
        int depth = 72;
        boolean[][] masks = masks(width, height, depth);
        fillOpposed(masks, 0, 2, width, 15, 8, 24, 30);
        fillOpposed(masks, 0, 2, width, 17, 31, 18, 43);
        fillOpposed(masks, 0, 2, width, 21, 31, 22, 43);
        fillOpposed(masks, 1, 3, depth, 8, 8, 63, 30);
        fillOpposed(masks, 1, 3, depth, 14, 31, 20, 43);
        fillOpposed(masks, 1, 3, depth, 49, 31, 55, 43);
        check(resolve(masks, width, height, depth) == SubjectCategory.ANIMAL,
                "Un quadrupède allongé doit activer le prior animal");
    }

    private static void detectsARiderOnAWideVehicle() {
        int width = 48;
        int height = 56;
        int depth = 44;
        boolean[][] masks = masks(width, height, depth);
        fillOpposed(masks, 0, 2, width, 19, 4, 28, 27);
        fillOpposed(masks, 0, 2, width, 3, 28, 44, 51);
        fillOpposed(masks, 1, 3, depth, 12, 4, 31, 27);
        fillOpposed(masks, 1, 3, depth, 3, 28, 40, 51);
        check(resolve(masks, width, height, depth)
                        == SubjectCategory.COMPOSITE_VEHICLE,
                "Un conducteur sur base large doit activer le prior composé");
    }

    private static void detectsABoxyBuilding() {
        int width = 48;
        int height = 56;
        int depth = 40;
        boolean[][] masks = masks(width, height, depth);
        fillOpposed(masks, 0, 2, width, 7, 5, 40, 51);
        fillOpposed(masks, 1, 3, depth, 6, 5, 33, 51);
        check(resolve(masks, width, height, depth)
                        == SubjectCategory.ARCHITECTURE_OBJECT,
                "Un volume rectangulaire stable doit être reconnu comme rigide");
    }

    private static void detectsATreeCanopy() {
        int width = 52;
        int height = 64;
        int depth = 48;
        boolean[][] masks = masks(width, height, depth);
        fillOpposed(masks, 0, 2, width, 8, 5, 43, 28);
        fillOpposed(masks, 0, 2, width, 22, 29, 29, 59);
        fillOpposed(masks, 1, 3, depth, 7, 5, 40, 28);
        fillOpposed(masks, 1, 3, depth, 20, 29, 27, 59);
        check(resolve(masks, width, height, depth) == SubjectCategory.PLANT,
                "Une canopée large sur tronc fin doit activer le prior végétal");
    }

    private static void manualSelectionAlwaysWins() {
        int width = 24;
        int height = 32;
        int depth = 20;
        boolean[][] masks = masks(width, height, depth);
        fillOpposed(masks, 0, 2, width, 5, 3, 18, 28);
        fillOpposed(masks, 1, 3, depth, 4, 3, 15, 28);
        SubjectCategory result = SubjectCategoryClassifier.resolve(
                SubjectCategory.ANIMAL,
                masks,
                width,
                height,
                depth
        );
        check(result == SubjectCategory.ANIMAL,
                "Le choix utilisateur doit rester prioritaire sur Auto");
    }

    private static void preservesQuadrupedLegTracksAcrossTemporaryMerge() {
        int width = 40;
        int height = 48;
        int depth = 50;
        boolean[][] masks = masks(width, height, depth);

        fillOpposed(masks, 0, 2, width, 10, 8, 29, 28);
        fillOpposed(masks, 0, 2, width, 11, 29, 15, 43);
        fillOpposed(masks, 0, 2, width, 24, 29, 28, 43);
        // Deux lignes où les deux pattes sont faussement soudées.
        fillOpposed(masks, 0, 2, width, 11, 35, 28, 36);

        fillOpposed(masks, 1, 3, depth, 8, 8, 41, 28);
        fillOpposed(masks, 1, 3, depth, 10, 29, 15, 43);
        fillOpposed(masks, 1, 3, depth, 34, 29, 39, 43);
        fillOpposed(masks, 1, 3, depth, 10, 35, 39, 36);

        SubjectCategoryClassifier.resolve(
                SubjectCategory.ANIMAL,
                masks,
                width,
                height,
                depth
        );

        check(!masks[0][35 * width + 20],
                "La fusion temporaire des pattes en vue de face doit être rouverte");
        check(!masks[1][35 * depth + 25],
                "La fusion temporaire des pattes en profil doit être rouverte");
    }

    private static void doesNotCutAnimalTorso() {
        int width = 40;
        int height = 48;
        int depth = 50;
        boolean[][] masks = masks(width, height, depth);
        fillOpposed(masks, 0, 2, width, 10, 8, 29, 28);
        fillOpposed(masks, 0, 2, width, 11, 29, 15, 43);
        fillOpposed(masks, 0, 2, width, 24, 29, 28, 43);
        fillOpposed(masks, 1, 3, depth, 8, 8, 41, 28);
        fillOpposed(masks, 1, 3, depth, 10, 29, 15, 43);
        fillOpposed(masks, 1, 3, depth, 34, 29, 39, 43);

        SubjectCategoryClassifier.resolve(
                SubjectCategory.ANIMAL,
                masks,
                width,
                height,
                depth
        );
        check(masks[0][24 * width + 20],
                "Le suivi des pattes ne doit pas découper le torse du quadrupède");
    }

    private static void keepsVehicleBaseSolid() {
        int width = 48;
        int height = 56;
        int depth = 44;
        boolean[][] masks = masks(width, height, depth);
        fillOpposed(masks, 0, 2, width, 19, 4, 28, 20);
        fillOpposed(masks, 0, 2, width, 12, 10, 15, 20);
        fillOpposed(masks, 0, 2, width, 32, 10, 35, 20);
        fillOpposed(masks, 0, 2, width, 3, 28, 44, 51);
        fillOpposed(masks, 1, 3, depth, 10, 4, 33, 20);
        fillOpposed(masks, 1, 3, depth, 3, 28, 40, 51);

        SubjectCategoryClassifier.resolve(
                SubjectCategory.COMPOSITE_VEHICLE,
                masks,
                width,
                height,
                depth
        );
        check(masks[0][40 * width + 24],
                "Le suivi du conducteur ne doit jamais fendre le châssis");
    }

    private static SubjectCategory resolve(
            boolean[][] masks,
            int width,
            int height,
            int depth
    ) {
        return SubjectCategoryClassifier.resolve(
                SubjectCategory.AUTO,
                masks,
                width,
                height,
                depth
        );
    }

    private static boolean[][] masks(int width, int height, int depth) {
        return new boolean[][]{
                new boolean[width * height],
                new boolean[depth * height],
                new boolean[width * height],
                new boolean[depth * height]
        };
    }

    private static void fillOpposed(
            boolean[][] masks,
            int first,
            int opposite,
            int width,
            int left,
            int top,
            int right,
            int bottom
    ) {
        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) {
                masks[first][y * width + x] = true;
                masks[opposite][y * width + width - 1 - x] = true;
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
