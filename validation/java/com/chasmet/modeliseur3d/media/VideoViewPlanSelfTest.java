package com.chasmet.modeliseur3d.media;

import java.util.Arrays;
import java.util.List;

public final class VideoViewPlanSelfTest {
    private VideoViewPlanSelfTest() {
    }

    public static void main(String[] args) {
        assertRoles(
                VideoViewPlan.roles(0, true),
                "front", "left", "back", "right"
        );
        assertRoles(
                VideoViewPlan.roles(0, false),
                "front", "right", "back", "left"
        );
        assertRoles(
                VideoViewPlan.roles(3, true),
                "left", "back", "right", "front"
        );
        assertRoles(
                VideoViewPlan.roles(2, false),
                "back", "left", "front", "right"
        );

        boolean rejected = false;
        try {
            VideoViewPlan.roles(4, true);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        if (!rejected) {
            throw new AssertionError("Une position invalide doit etre refusee");
        }
        System.out.println("VideoViewPlanSelfTest: OK");
    }

    private static void assertRoles(List<String> actual, String... expected) {
        List<String> values = Arrays.asList(expected);
        if (!actual.equals(values)) {
            throw new AssertionError(
                    "Roles attendus " + values + ", obtenus " + actual
            );
        }
    }
}
