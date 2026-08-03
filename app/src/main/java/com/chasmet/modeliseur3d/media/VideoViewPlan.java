package com.chasmet.modeliseur3d.media;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Associe les quatre instants d'une rotation aux vues Tripo attendues. */
public final class VideoViewPlan {
    public static final int VIEW_COUNT = 4;

    private VideoViewPlan() {
    }

    public static List<String> roles(
            int frontSlot,
            boolean nextQuarterIsLeft
    ) {
        if (frontSlot < 0 || frontSlot >= VIEW_COUNT) {
            throw new IllegalArgumentException("Position de face invalide");
        }
        String[] rotation = nextQuarterIsLeft
                ? new String[]{"front", "left", "back", "right"}
                : new String[]{"front", "right", "back", "left"};
        List<String> roles = new ArrayList<>(VIEW_COUNT);
        for (int slot = 0; slot < VIEW_COUNT; slot++) {
            int relativeSlot = Math.floorMod(slot - frontSlot, VIEW_COUNT);
            roles.add(rotation[relativeSlot]);
        }
        return Collections.unmodifiableList(roles);
    }
}
