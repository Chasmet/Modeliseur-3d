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
        System.out.println("Plan manuel 8 vues validé");
    }
}
