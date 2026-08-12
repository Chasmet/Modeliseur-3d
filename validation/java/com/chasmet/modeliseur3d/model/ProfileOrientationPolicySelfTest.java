package com.chasmet.modeliseur3d.model;

/** Regression checks for wide quadrupeds and genuinely sideways photos. */
public final class ProfileOrientationPolicySelfTest {
    private ProfileOrientationPolicySelfTest() {
    }

    public static void main(String[] args) {
        preservesANaturallyWideHorseProfile();
        rotatesAGenuinelySidewaysPhoto();
        ignoresNearSquareProfiles();
        rejectsAnAmbiguousRotation();
        System.out.println("ProfileOrientationPolicySelfTest: OK");
    }

    private static void preservesANaturallyWideHorseProfile() {
        check(!ProfileOrientationPolicy.shouldQuarterTurn(
                        1.72,
                        0.62,
                        0.83,
                        0.68
                ),
                "A natural horse side silhouette must stay horizontal");
    }

    private static void rotatesAGenuinelySidewaysPhoto() {
        check(ProfileOrientationPolicy.shouldQuarterTurn(
                        1.68,
                        0.61,
                        0.46,
                        0.82
                ),
                "A sideways phone photo must still be corrected");
    }

    private static void ignoresNearSquareProfiles() {
        check(!ProfileOrientationPolicy.shouldQuarterTurn(
                        1.06,
                        0.83,
                        0.38,
                        0.91
                ),
                "Aspect ratio alone must not trigger a quarter turn");
    }

    private static void rejectsAnAmbiguousRotation() {
        check(!ProfileOrientationPolicy.shouldQuarterTurn(
                        1.55,
                        0.70,
                        0.70,
                        0.74
                ),
                "A marginal profile match must keep the user orientation");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
