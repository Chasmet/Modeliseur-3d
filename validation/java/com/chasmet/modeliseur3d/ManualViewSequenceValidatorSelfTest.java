package com.chasmet.modeliseur3d;

public final class ManualViewSequenceValidatorSelfTest {
    private ManualViewSequenceValidatorSelfTest() {
    }

    public static void main(String[] arguments) {
        boolean[] accepted = {
                true, true, true, true, true, true, true, true
        };
        float[] coherent = {
                0.48f, 0.40f, 0.29f, 0.39f,
                0.46f, 0.38f, 0.30f, 0.41f
        };
        ManualViewSequenceValidator.Result valid =
                ManualViewSequenceValidator.validate(coherent, accepted);
        if (!valid.isValid() || valid.getFailingIndex() != -1) {
            throw new AssertionError("Une séquence cohérente devrait être acceptée");
        }

        float[] fakeProfiles = {
                0.46f, 0.44f, 0.47f, 0.43f,
                0.45f, 0.42f, 0.46f, 0.44f
        };
        ManualViewSequenceValidator.Result rejected =
                ManualViewSequenceValidator.validate(fakeProfiles, accepted);
        if (rejected.isValid()
                || (rejected.getFailingIndex() != 2
                && rejected.getFailingIndex() != 6)) {
            throw new AssertionError("Des profils presque de face doivent être refusés");
        }

        accepted[5] = false;
        ManualViewSequenceValidator.Result missingValidation =
                ManualViewSequenceValidator.validate(coherent, accepted);
        if (missingValidation.isValid() || missingValidation.getFailingIndex() != 5) {
            throw new AssertionError("Une vue refusée doit bloquer la reconstruction");
        }
        System.out.println("Cohérence 360° V5.4 validée");
    }
}
