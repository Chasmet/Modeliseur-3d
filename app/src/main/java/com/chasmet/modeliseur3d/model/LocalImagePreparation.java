package com.chasmet.modeliseur3d.model;

import android.content.Context;
import android.graphics.Bitmap;

/** Shared by the manual workshop and MCP: identical transparency / IS-Net / tolerance rules. */
public final class LocalImagePreparation {
    private LocalImagePreparation() { }
    public static OfflineImageVolume.Prepared prepare(Context context, Bitmap image, int tolerance,
            boolean useAi, TripoSREngine.Progress progress) throws Exception {
        progress.check();
        AnimeSegmentationEngine.Mask mask=null;
        if (useAi && !OfflineImageVolume.hasUsefulTransparency(image)) {
            progress.update("Détourage IS-Net embarqué · calcul CPU local…");
            try (AnimeSegmentationEngine engine=new AnimeSegmentationEngine(context,2)) {
                mask=engine.segment(image);
            }
        }
        progress.check();
        return OfflineImageVolume.prepare(image,tolerance,mask);
    }
}
