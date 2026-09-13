package com.chasmet.modeliseur3d.model;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * V9.4 memory-safe : EfficientViT-SAM-XL0 reste le segmentateur sémantique
 * principal, mais le mode quatre vues ne garde plus IS-Net General en mémoire
 * en même temps que l'encodeur XL0.
 *
 * Le prompt boîte est proposé par un détecteur de premier plan très léger basé
 * sur la couleur du bord de l'image. Pour personnage + véhicule, une seule
 * passe SAM lourde est exécutée par vue ; les pixels de premier plan très sûrs
 * servent ensuite uniquement à protéger roues, châssis et pilote. Cette
 * architecture supprime les 3 encodages XL0 par vue de V9.3 qui pouvaient
 * saturer la RAM native Android.
 */
public final class RiskSegmentationEngine implements AutoCloseable {
    private static final int COARSE_SIZE = 256;
    private static final int FUSION_SIZE = 1024;

    private final EfficientVitSamSegmentationEngine sam;
    private int samAccepted;
    private int compositeProtected;

    public RiskSegmentationEngine(Context context) throws Exception {
        sam = new EfficientVitSamSegmentationEngine(context);
    }

    public AnimeSegmentationEngine.Mask segment(Bitmap source) throws Exception {
        return segment(source, SubjectCategory.AUTO);
    }

    public AnimeSegmentationEngine.Mask segment(
            Bitmap source,
            SubjectCategory requestedCategory
    ) throws Exception {
        if (source == null || source.isRecycled()) {
            throw new IllegalArgumentException("Image absente pour SAM XL0");
        }

        AnimeSegmentationEngine.Mask coarse = lightweightPrompt(source);
        AnimeSegmentationEngine.Mask semantic = sam.segment(source, coarse);
        if (semantic == coarse) {
            throw new IllegalStateException(
                    "SAM XL0 a refusé cette vue : aucun fallback silencieux autorisé"
            );
        }
        samAccepted++;

        boolean composite = requestedCategory == SubjectCategory.COMPOSITE_VEHICLE
                || (requestedCategory == SubjectCategory.AUTO && looksComposite(coarse));
        if (!composite) {
            return semantic;
        }

        compositeProtected++;
        return preserveCompositeCoverage(semantic, coarse);
    }

    /**
     * Produit un prompt compact sans charger un second réseau neuronal.
     * Les coins et le bord donnent une estimation du fond ; la différence de
     * couleur ne décide jamais seule du masque final, elle sert principalement
     * à proposer la boîte SAM et à sauver les pixels extrêmement certains.
     */
    private static AnimeSegmentationEngine.Mask lightweightPrompt(Bitmap source) {
        Bitmap small = Bitmap.createScaledBitmap(source, COARSE_SIZE, COARSE_SIZE, true);
        int[] pixels = new int[COARSE_SIZE * COARSE_SIZE];
        small.getPixels(pixels, 0, COARSE_SIZE, 0, 0, COARSE_SIZE, COARSE_SIZE);
        if (small != source) {
            small.recycle();
        }

        long sumR = 0L;
        long sumG = 0L;
        long sumB = 0L;
        int samples = 0;
        int border = 10;
        for (int y = 0; y < COARSE_SIZE; y += 3) {
            for (int x = 0; x < COARSE_SIZE; x += 3) {
                if (x >= border && x < COARSE_SIZE - border
                        && y >= border && y < COARSE_SIZE - border) {
                    continue;
                }
                int color = pixels[y * COARSE_SIZE + x];
                sumR += Color.red(color);
                sumG += Color.green(color);
                sumB += Color.blue(color);
                samples++;
            }
        }
        float bgR = sumR / (float) Math.max(1, samples);
        float bgG = sumG / (float) Math.max(1, samples);
        float bgB = sumB / (float) Math.max(1, samples);

        float[] values = new float[pixels.length];
        int minX = COARSE_SIZE;
        int minY = COARSE_SIZE;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < COARSE_SIZE; y++) {
            int row = y * COARSE_SIZE;
            for (int x = 0; x < COARSE_SIZE; x++) {
                int index = row + x;
                int color = pixels[index];
                float dr = Math.abs(Color.red(color) - bgR);
                float dg = Math.abs(Color.green(color) - bgG);
                float db = Math.abs(Color.blue(color) - bgB);
                float distance = Math.max(dr, Math.max(dg, db));
                float alpha = Color.alpha(color) / 255.0f;

                float probability;
                if (alpha < 0.10f) {
                    probability = 0.0f;
                } else if (distance >= 82.0f) {
                    probability = 0.96f;
                } else if (distance >= 48.0f) {
                    probability = 0.78f;
                } else if (distance >= 27.0f) {
                    probability = 0.56f;
                } else {
                    probability = 0.08f;
                }
                values[index] = probability;
                if (probability >= 0.50f) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }

        // Si le fond est complexe ou trop proche du sujet, un grand rectangle
        // central donne quand même à SAM un prompt exploitable sans masquer le sujet.
        if (maxX < minX || maxY < minY
                || (maxX - minX) < COARSE_SIZE / 10
                || (maxY - minY) < COARSE_SIZE / 10) {
            int margin = COARSE_SIZE / 18;
            for (int y = margin; y < COARSE_SIZE - margin; y++) {
                int row = y * COARSE_SIZE;
                for (int x = margin; x < COARSE_SIZE - margin; x++) {
                    values[row + x] = Math.max(values[row + x], 0.52f);
                }
            }
        }

        return new AnimeSegmentationEngine.Mask(
                values,
                COARSE_SIZE,
                COARSE_SIZE,
                0,
                0,
                COARSE_SIZE,
                COARSE_SIZE
        );
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
                float promptValue = coarse.sampleNormalized(nx, ny);

                // SAM reste la vérité sémantique. Le prompt léger ne peut sauver
                // qu'un pixel extrêmement différent du fond : roue, bord du kart,
                // casque, bras ou autre pièce nette.
                float recovered = promptValue >= 0.94f
                        ? Math.max(samValue, 0.60f * promptValue)
                        : samValue;
                values[index] = clamp01(recovered);
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
        return "V9.4 MEMORY-SAFE • " + sam.getBackend()
                + " • 1 encodage lourd par vue"
                + " • SAM validé " + samAccepted + " vue(s)"
                + (compositeProtected > 0
                ? " • pilote/kart protégé " + compositeProtected + " vue(s)"
                : "");
    }

    @Override
    public void close() {
        sam.close();
    }
}
