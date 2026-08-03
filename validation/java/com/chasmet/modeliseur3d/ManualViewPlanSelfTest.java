package com.chasmet.modeliseur3d;

public final class ManualViewPlanSelfTest {
    private ManualViewPlanSelfTest() {
    }

    public static void main(String[] arguments) {
        if (ManualViewPlan.VIEW_COUNT != 8) {
            throw new AssertionError("Le plan manuel doit contenir huit vues");
        }
        for (int index = 0; index < ManualViewPlan.VIEW_COUNT; index++) {
            int expectedAngle = index * 45;
            if (ManualViewPlan.getAngleDegrees(index) != expectedAngle) {
                throw new AssertionError("Angle incorrect pour la vue " + index);
            }
            String label = ManualViewPlan.getSlotLabel(index);
            if (!label.contains(expectedAngle + "°")) {
                throw new AssertionError("Libellé sans angle pour la vue " + index);
            }
            float guide = ManualViewPlan.getGuideWidthFactor(index);
            float aspect = ManualViewPlan.getTargetAspectRatio(index);
            if (!(guide > 0.30f && guide < 0.90f)) {
                throw new AssertionError("Largeur de guide invalide pour " + index);
            }
            if (!(aspect > 0.15f && aspect < 0.70f)) {
                throw new AssertionError("Rapport de silhouette invalide pour " + index);
            }
        }
        if (!ManualViewPlan.isFrontOrBack(0)
                || !ManualViewPlan.isFrontOrBack(4)
                || !ManualViewPlan.isProfile(2)
                || !ManualViewPlan.isProfile(6)
                || !ManualViewPlan.isThreeQuarter(1)) {
            throw new AssertionError("Les catégories d’angles V5.4 sont incorrectes");
        }
        if (ManualViewPlan.getGuideWidthFactor(2)
                >= ManualViewPlan.getGuideWidthFactor(0)) {
            throw new AssertionError("Le guide profil doit être plus fin que la face");
        }
        boolean[] selected = new boolean[ManualViewPlan.VIEW_COUNT];
        selected[0] = true;
        selected[1] = true;
        if (ManualViewPlan.findFirstMissing(selected) != 2) {
            throw new AssertionError("La première vue manquante doit être la troisième");
        }
        for (int index = 0; index < selected.length; index++) {
            selected[index] = true;
        }
        if (ManualViewPlan.findFirstMissing(selected) != -1) {
            throw new AssertionError("Toutes les vues devraient être complètes");
        }
        System.out.println("Plan guidé V5.4 de huit silhouettes validé");
    }
}
