package com.chasmet.modeliseur3d.model;

import android.content.Context;
import android.graphics.Bitmap;

/**
 * V9.3 strict : IS-Net General propose la silhouette complète, puis
 * EfficientViT-SAM-XL0 doit réellement produire le masque sémantique.
 *
 * Aucun repli silencieux n'est accepté : si SAM échoue, la génération s'arrête
 * avec une erreur explicite au lieu de prétendre utiliser le pipeline V9.
 * Pour les sujets personnage + véhicule, les zones très sûres d'IS-Net sont
 * conservées afin qu'un prompt SAM global ne puisse pas perdre le pilote ou le kart.
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
        AnimeSegmentationEngine.Mask semantic = sam.segment(source, coarse);
        if (semantic == coarse) {
            throw new IllegalStateException(
                    "SAM XL0 a refusé cette vue : aucun fallback silencieux autorisé en V9.3"
            );
        }
        samAccepted++;

        if (requestedCategory == SubjectCategory.COMPOSITE_VEHICLE) {
            compositeProtected++;
            return preserveCompositeCoverage(semantic, coarse);
        }
        return semantic;
    }

    private static AnimeSegmentationEngine.Mask preserveCompositeCoverage(
            AnimeSegmentationEngine.Mask semantic,
            AnimeSegmentationEngine.Mask coarse
    ) {
        float[] values = new float[FUSION_SIZE * FUSION_SIZE];
        int index = 0;
        for (int y = 0; y < FUSION_SIZE; y++) {
            float ny = y / (float) (FUSION_SIZE - 1);
            for (int x = 0; x < FUSION_SIZE; x++, index++) {
                float nx = x / (float) (FUSION_SIZE - 1);
                float samValue = semantic.sampleNormalized(nx, ny);
                float generalValue = coarse.sampleNormalized(nx, ny);
                float recovered = generalValue >= 0.90f
                        ? Math.max(samValue, 0.62f * generalValue)
                        : samValue;
                values[index] = Math.max(0.0f, Math.min(1.0f, recovered));
            }
        }
        return new AnimeSegmentationEngine.Mask(
                values,
                FUSION_SIZE,
                FUSION_SIZE,
                0,
                0,
                FUSION_SIZE,
                FUSION_SIZE
        );
    }

    public String getBackend() {
        return "V9.3 STRICT • IS-Net General + " + sam.getBackend()
                + " • SAM validé " + samAccepted + " vue(s)"
                + (compositeProtected > 0
                ? " • composite pilote/véhicule protégé " + compositeProtected + " vue(s)"
                : "");
    }

    @Override
    public void close() {
        sam.close();
        general.close();
    }
}
