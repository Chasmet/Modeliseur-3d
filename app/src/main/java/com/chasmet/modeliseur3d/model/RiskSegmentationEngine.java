package com.chasmet.modeliseur3d.model;

import android.content.Context;
import android.graphics.Bitmap;

/** V9 Risk: IS-Net proposes a box, EfficientViT-SAM-XL0 redraws the subject. */
public final class RiskSegmentationEngine implements AutoCloseable {
    private final GeneralSegmentationEngine general;
    private final EfficientVitSamSegmentationEngine sam;
    private final String backend;

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
        backend = "V9 Risk • " + sam.getBackend()
                + " • box IS-Net General";
    }

    public AnimeSegmentationEngine.Mask segment(Bitmap source) throws Exception {
        AnimeSegmentationEngine.Mask coarse = general.segment(source);
        try {
            return sam.segment(source, coarse);
        } catch (Exception ignored) {
            return coarse;
        }
    }

    public String getBackend() {
        return backend;
    }

    @Override
    public void close() {
        sam.close();
        general.close();
    }
}
