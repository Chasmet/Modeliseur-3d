package com.chasmet.modeliseur3d;

/** Vérifie que les huit silhouettes représentent réellement un tour à 360°. */
public final class ManualViewSequenceValidator {
    private ManualViewSequenceValidator() {
    }

    public static Result validate(float[] aspects, boolean[] accepted) {
        if (aspects == null || accepted == null
                || aspects.length != ManualViewPlan.VIEW_COUNT
                || accepted.length != ManualViewPlan.VIEW_COUNT) {
            throw new IllegalArgumentException("Séquence 3D incomplète");
        }
        for (int index = 0; index < ManualViewPlan.VIEW_COUNT; index++) {
            if (!accepted[index] || !Float.isFinite(aspects[index])) {
                return new Result(
                        false,
                        index,
                        "La vue " + ManualViewPlan.getName(index)
                                + " doit être validée avant la reconstruction"
                );
            }
        }

        float frontBack = average(aspects[0], aspects[4]);
        float profiles = average(aspects[2], aspects[6]);
        float threeQuarters = average(
                aspects[1], aspects[3], aspects[5], aspects[7]
        );

        if (profiles >= frontBack * 0.98f) {
            int failing = aspects[2] >= aspects[6] ? 2 : 6;
            return new Result(
                    false,
                    failing,
                    "Le profil doit être nettement plus fin que la face et le dos"
            );
        }
        if (threeQuarters < profiles * 0.88f) {
            int failing = smallestIndex(aspects, 1, 3, 5, 7);
            return new Result(
                    false,
                    failing,
                    "Le trois-quarts ressemble trop à un profil complet"
            );
        }
        if (threeQuarters > frontBack * 1.10f) {
            int failing = largestIndex(aspects, 1, 3, 5, 7);
            return new Result(
                    false,
                    failing,
                    "Le trois-quarts ressemble trop à une vue de face"
            );
        }
        if (relativeDifference(aspects[2], aspects[6]) > 0.42f) {
            int failing = aspects[2] > aspects[6] ? 2 : 6;
            return new Result(
                    false,
                    failing,
                    "Les profils gauche et droit n’ont pas la même pose ou la même échelle"
            );
        }
        if (relativeDifference(aspects[0], aspects[4]) > 0.48f) {
            int failing = aspects[0] > aspects[4] ? 0 : 4;
            return new Result(
                    false,
                    failing,
                    "La face et le dos doivent garder la même pose et la même échelle"
            );
        }
        return new Result(
                true,
                -1,
                "Huit silhouettes cohérentes pour un tour complet à 360°"
        );
    }

    private static float average(float first, float second) {
        return (first + second) * 0.5f;
    }

    private static float average(float a, float b, float c, float d) {
        return (a + b + c + d) * 0.25f;
    }

    private static float relativeDifference(float first, float second) {
        float denominator = Math.max(0.001f, (Math.abs(first) + Math.abs(second)) * 0.5f);
        return Math.abs(first - second) / denominator;
    }

    private static int smallestIndex(float[] values, int... indexes) {
        int result = indexes[0];
        for (int index : indexes) {
            if (values[index] < values[result]) {
                result = index;
            }
        }
        return result;
    }

    private static int largestIndex(float[] values, int... indexes) {
        int result = indexes[0];
        for (int index : indexes) {
            if (values[index] > values[result]) {
                result = index;
            }
        }
        return result;
    }

    public static final class Result {
        private final boolean valid;
        private final int failingIndex;
        private final String message;

        Result(boolean valid, int failingIndex, String message) {
            this.valid = valid;
            this.failingIndex = failingIndex;
            this.message = message;
        }

        public boolean isValid() {
            return valid;
        }

        public int getFailingIndex() {
            return failingIndex;
        }

        public String getMessage() {
            return message;
        }
    }
}
