package com.chasmet.modeliseur3d.model;

import android.content.Context;
import android.graphics.Bitmap;

/**
 * V8.2 Qualité Extrême : fusion locale IS-Net General + U²-Net.
 *
 * IS-Net reste la source principale. U²-Net sert de second avis sur les bords
 * difficiles. La fusion favorise le consensus et n'autorise un ajout venant
 * uniquement de U²-Net que lorsqu'il est très confiant et qu'IS-Net apporte
 * déjà un support minimal. Cela limite les halos tout en récupérant davantage
 * de pattes, oreilles, roues, queues et branches fines.
 */
public final class ExtremeSegmentationEngine implements AutoCloseable {
    private static final int FUSION_SIZE = 1024;

    private final GeneralSegmentationEngine general;
    private final U2NetSegmentationEngine u2net;
    private final String backend;

    public ExtremeSegmentationEngine(Context context) throws Exception {
        GeneralSegmentationEngine primary = null;
        U2NetSegmentationEngine secondary = null;
        try {
            primary = new GeneralSegmentationEngine(context);
            secondary = new U2NetSegmentationEngine(context);
        } catch (Exception error) {
            if (primary != null) {
                primary.close();
            }
            if (secondary != null) {
                secondary.close();
            }
            throw error;
        }
        general = primary;
        u2net = secondary;
        backend = "V8.2 Extreme • " + general.getBackend() + " + " + u2net.getBackend();
    }

    public AnimeSegmentationEngine.Mask segment(Bitmap source) throws Exception {
        AnimeSegmentationEngine.Mask primary = general.segment(source);
        AnimeSegmentationEngine.Mask secondary;
        try {
            secondary = u2net.segment(source);
        } catch (Exception ignored) {
            // Qualité extrême doit rester robuste : IS-Net General seul reste
            // un résultat valide si le second réseau échoue ponctuellement.
            return primary;
        }

        float[] fused = new float[FUSION_SIZE * FUSION_SIZE];
        int index = 0;
        for (int y = 0; y < FUSION_SIZE; y++) {
            float ny = y / (float) Math.max(1, FUSION_SIZE - 1);
            for (int x = 0; x < FUSION_SIZE; x++, index++) {
                float nx = x / (float) Math.max(1, FUSION_SIZE - 1);
                float g = primary.sampleNormalized(nx, ny);
                float u = secondary.sampleNormalized(nx, ny);
                fused[index] = fuseProbability(g, u);
            }
        }
        return new AnimeSegmentationEngine.Mask(
                fused,
                FUSION_SIZE,
                FUSION_SIZE,
                0,
                0,
                FUSION_SIZE,
                FUSION_SIZE
        );
    }

    static float fuseProbability(float generalProbability, float u2Probability) {
        float g = clamp01(generalProbability);
        float u = clamp01(u2Probability);
        float minimum = Math.min(g, u);
        float maximum = Math.max(g, u);
        float difference = maximum - minimum;

        // Accord net : le bord est probablement réel, on conserve le meilleur
        // des deux tout en lissant légèrement la différence.
        if (minimum >= 0.55f) {
            return clamp01(Math.max(0.68f * g + 0.32f * u, maximum * 0.94f));
        }

        // IS-Net très sûr : il reste prioritaire même si U²-Net hésite.
        if (g >= 0.72f && u < 0.35f) {
            return clamp01(g * 0.96f);
        }

        // U²-Net peut sauver un appendice fin, mais seulement si IS-Net voit
        // déjà un minimum de matière. On évite ainsi d'inventer du fond.
        if (u >= 0.78f && g >= 0.18f) {
            return clamp01(Math.max(g, 0.86f * u));
        }

        // Désaccord modéré : IS-Net reste dominant.
        float mixed = 0.76f * g + 0.24f * u;
        if (difference <= 0.18f) {
            mixed = Math.max(mixed, maximum * 0.90f);
        }
        return clamp01(mixed);
    }

    public String getBackend() {
        return backend;
    }

    @Override
    public void close() {
        u2net.close();
        general.close();
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
