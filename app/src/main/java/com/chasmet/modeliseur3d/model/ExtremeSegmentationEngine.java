package com.chasmet.modeliseur3d.model;

import android.content.Context;
import android.graphics.Bitmap;

/**
 * V9.2 : point d'entrée historique conservé pour compatibilité.
 * Le moteur quatre vues ne fusionne plus IS-Net avec U²-Net : il utilise le
 * pipeline V9 Risk, où IS-Net propose uniquement la boîte et
 * EfficientViT-SAM-XL0 1024 redessine le sujet.
 */
public final class ExtremeSegmentationEngine implements AutoCloseable {
    private final RiskSegmentationEngine delegate;

    public ExtremeSegmentationEngine(Context context) throws Exception {
        delegate = new RiskSegmentationEngine(context);
    }

    public AnimeSegmentationEngine.Mask segment(Bitmap source) throws Exception {
        return delegate.segment(source);
    }

    public String getBackend() {
        return "V9.2 Thin • " + delegate.getBackend();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
