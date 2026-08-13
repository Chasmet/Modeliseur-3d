package com.chasmet.modeliseur3d.model;

import android.content.Context;
import android.graphics.Bitmap;

/**
 * V9.3 strict : IS-Net General propose la silhouette complète, puis
 * EfficientViT-SAM-XL0 doit réellement produire le masque sémantique.
 *
 * Aucun repli silencieux n'est accepté : si SAM échoue, la génération s'arrête
 * avec une erreur explicite au lieu de prétendre utiliser le pipeline V9.
 * Les sujets personnage + véhicule utilisent trois passes SAM par vue :
 * ensemble complet, zone pilote, puis zone véhicule.
 */
public final class RiskSegmentationEngine implements AutoCloseable {
    private static final int FUSION_SIZE = 1024;

    private final GeneralSegmentationEngine general;
    private final EfficientVitSamSegmentationEngine sam;
    private int samAccepted;
    private int compositeProtected;

    public RiskSegmentationEngine(Context context) throws Exception {
        GeneralSegmentationEngine primary = null;
        EfficientVitSamSegmentationEngine semantic = null;
        try {
            primary = new GeneralSegmentationEngine(context);
            semantic = new EfficientVitSamSegmentationEngine(context);
        } catch (Exception error) {
            if (semantic != null) semantic.close();
            if (primary != null) primary.close();
            throw error;
        }
        general = primary;
        sam = semantic;
    }

    public AnimeSegmentationEngine.Mask segment(Bitmap source) throws Exception {
        return segment(source, SubjectCategory.AUTO);
    }

    public AnimeSegmentationEngine.Mask segment(
            Bitmap source,
            SubjectCategory requestedCategory
    ) throws Exception {
        AnimeSegmentationEngine.Mask coarse = general.segment(source);
        boolean composite = requestedCategory == SubjectCategory.COMPOSITE_VEHICLE
                || (requestedCategory == SubjectCategory.AUTO && looksComposite(coarse));

        AnimeSegmentationEngine.Mask whole = requireSam(source, coarse, "ensemble complet");
        if (!composite) {
            return whole;
        }

        // Les deux bandes se chevauchent volontairement au niveau siège/bassin :
        // on ne veut pas découper artificiellement le conducteur du véhicule.
        AnimeSegmentationEngine.Mask upperCoarse = bandMask(coarse, 0.00f, 0.68f);
        AnimeSegmentationEngine.Mask lowerCoarse = bandMask(coarse, 0.30f, 1.00f);
        AnimeSegmentationEngine.Mask pilot = requireSam(source, upperCoarse, "pilote");
        AnimeSegmentationEngine.Mask vehicle = requireSam(source, lowerCoarse, "véhicule");
        compositeProtected++;
        return fuseComposite(whole, pilot, vehicle, coarse);
    }

    private AnimeSegmentationEngine.Mask requireSam(
            Bitmap source,
            AnimeSegmentationEngine.Mask prompt,
            String role
    ) throws Exception {
        AnimeSegmentationEngine.Mask result = sam.segment(source, prompt);
        if (result == prompt) {
            throw new IllegalStateException(
                    "SAM XL0 a refusé la passe " + role
                            + " : aucun fallback silencieux autorisé en V9.3"
            );
        }
        samAccepted++;
        return result;
    }

    private static AnimeSegmentationEngine.Mask bandMask(
            AnimeSegmentationEngine.Mask source,
            float startY,
            float endY
    ) {
        float[] values = new float[FUSION_SIZE * FUSION_SIZE];
        int index = 0;
        for (int y = 0; y < FUSION_SIZE; y++) {
            float ny = y / (float) (FUSION_SIZE - 1);
            float gate;
            if (ny < startY || ny > endY) {
                gate = 0.0f;
            } else {
                float edge = 0.055f;
                float enter = clamp01((ny - startY) / edge);
                float leave = clamp01((endY - ny) / edge);
                gate = Math.min(enter, leave);
            }
            for (int x = 0; x < FUSION_SIZE; x++, index++) {
                float nx = x / (float) (FUSION_SIZE - 1);
                values[index] = source.sampleNormalized(nx, ny) * gate;
            }
        }
        return new AnimeSegmentationEngine.Mask(
                values, FUSION_SIZE, FUSION_SIZE,
                0, 0, FUSION_SIZE, FUSION_SIZE
        );
    }

    private static AnimeSegmentationEngine.Mask fuseComposite(
            AnimeSegmentationEngine.Mask whole,
            AnimeSegmentationEngine.Mask pilot,
            AnimeSegmentationEngine.Mask vehicle,
            AnimeSegmentationEngine.Mask coarse
    ) {
        float[] values = new float[FUSION_SIZE * FUSION_SIZE];
        int index = 0;
        for (int y = 0; y < FUSION_SIZE; y++) {
            float ny = y / (float) (FUSION_SIZE - 1);
            for (int x = 0; x < FUSION_SIZE; x++, index++) {
                float nx = x / (float) (FUSION_SIZE - 1);
                float w = whole.sampleNormalized(nx, ny);
                float p = pilot.sampleNormalized(nx, ny);
                float v = vehicle.sampleNormalized(nx, ny);
                float g = coarse.sampleNormalized(nx, ny);

                float semantic = Math.max(w, Math.max(p, v));
                if (g >= 0.90f) {
                    semantic = Math.max(semantic, 0.62f * g);
                }
                values[index] = clamp01(semantic);
            }
        }
        return new AnimeSegmentationEngine.Mask(
                values, FUSION_SIZE, FUSION_SIZE,
                0, 0, FUSION_SIZE, FUSION_SIZE
        );
    }

    private static boolean looksComposite(AnimeSegmentationEngine.Mask mask) {
        final int grid = 96;
        float upperWidth = 0.0f;
        float lowerWidth = 0.0f;
        int upperRows = 0;
        int lowerRows = 0;
        for (int y = 0; y < grid; y++) {
            float ny = (y + 0.5f) / grid;
            int left = grid;
            int right = -1;
            for (int x = 0; x < grid; x++) {
                float nx = (x + 0.5f) / grid;
                if (mask.sampleNormalized(nx, ny) >= 0.48f) {
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                }
            }
            if (right < left) {
                continue;
            }
            float width = right - left + 1.0f;
            if (ny >= 0.18f && ny <= 0.50f) {
                upperWidth += width;
                upperRows++;
            }
            if (ny >= 0.58f && ny <= 0.92f) {
                lowerWidth += width;
                lowerRows++;
            }
        }
        if (upperRows < 4 || lowerRows < 4) {
            return false;
        }
        upperWidth /= upperRows;
        lowerWidth /= lowerRows;
        return lowerWidth >= upperWidth * 1.22f;
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    public String getBackend() {
        return "V9.3 STRICT • IS-Net General + " + sam.getBackend()
                + " • SAM réellement validé " + samAccepted + " passe(s)"
                + (compositeProtected > 0
                ? " • composite triple-passe " + compositeProtected + " vue(s)"
                : "");
    }

    @Override
    public void close() {
        sam.close();
        general.close();
    }
}
