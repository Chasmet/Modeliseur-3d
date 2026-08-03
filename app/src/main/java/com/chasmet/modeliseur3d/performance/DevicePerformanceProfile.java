package com.chasmet.modeliseur3d.performance;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.PowerManager;

import java.util.Locale;

/**
 * Mesure les ressources réellement disponibles sur l'appareil et choisit un
 * profil de calcul local. La RAM physique sert à choisir la qualité, tandis
 * que la limite de heap Android sert à éviter les dépassements mémoire.
 */
public final class DevicePerformanceProfile {
    public enum Tier {
        TURBO,
        QUALITY,
        COMPATIBILITY
    }

    private final Tier tier;
    private final int processorCount;
    private final int neuralThreadCount;
    private final long totalMemoryBytes;
    private final long maximumHeapBytes;
    private final int imageGridLongSide;
    private final int textureHeight;
    private final int maximumInputSide;
    private final int videoNormalizationSide;
    private final int imageTriangleTarget;
    private final boolean sustainedPerformanceSupported;

    private DevicePerformanceProfile(
            Tier tier,
            int processorCount,
            int neuralThreadCount,
            long totalMemoryBytes,
            long maximumHeapBytes,
            int imageGridLongSide,
            int textureHeight,
            int maximumInputSide,
            int videoNormalizationSide,
            int imageTriangleTarget,
            boolean sustainedPerformanceSupported
    ) {
        this.tier = tier;
        this.processorCount = processorCount;
        this.neuralThreadCount = neuralThreadCount;
        this.totalMemoryBytes = totalMemoryBytes;
        this.maximumHeapBytes = maximumHeapBytes;
        this.imageGridLongSide = imageGridLongSide;
        this.textureHeight = textureHeight;
        this.maximumInputSide = maximumInputSide;
        this.videoNormalizationSide = videoNormalizationSide;
        this.imageTriangleTarget = imageTriangleTarget;
        this.sustainedPerformanceSupported = sustainedPerformanceSupported;
    }

    public static DevicePerformanceProfile detect(Context context) {
        Context app = context.getApplicationContext();
        ActivityManager activityManager = (ActivityManager) app.getSystemService(
                Context.ACTIVITY_SERVICE
        );
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        long totalMemory = 0L;
        if (activityManager != null) {
            activityManager.getMemoryInfo(memoryInfo);
            totalMemory = memoryInfo.totalMem;
        }
        if (totalMemory <= 0L) {
            totalMemory = Runtime.getRuntime().maxMemory();
        }

        int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        long maximumHeap = Runtime.getRuntime().maxMemory();
        long totalGb = totalMemory / (1024L * 1024L * 1024L);
        long heapMb = maximumHeap / (1024L * 1024L);

        PowerManager powerManager = (PowerManager) app.getSystemService(
                Context.POWER_SERVICE
        );
        boolean sustained = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                && powerManager != null
                && powerManager.isSustainedPerformanceModeSupported();

        if (totalGb >= 8L && processors >= 8 && heapMb >= 384L) {
            return new DevicePerformanceProfile(
                    Tier.TURBO,
                    processors,
                    Math.max(4, Math.min(8, processors - 1)),
                    totalMemory,
                    maximumHeap,
                    240,
                    1536,
                    2560,
                    896,
                    260_000,
                    sustained
            );
        }
        if (totalGb >= 5L && processors >= 6 && heapMb >= 256L) {
            return new DevicePerformanceProfile(
                    Tier.QUALITY,
                    processors,
                    Math.max(3, Math.min(6, processors - 1)),
                    totalMemory,
                    maximumHeap,
                    192,
                    1280,
                    2200,
                    768,
                    170_000,
                    sustained
            );
        }
        return new DevicePerformanceProfile(
                Tier.COMPATIBILITY,
                processors,
                Math.max(2, Math.min(4, processors - 1)),
                totalMemory,
                maximumHeap,
                144,
                1024,
                1792,
                640,
                95_000,
                sustained
        );
    }

    public Tier getTier() {
        return tier;
    }

    public boolean isTurbo() {
        return tier == Tier.TURBO;
    }

    public int getProcessorCount() {
        return processorCount;
    }

    public int getNeuralThreadCount() {
        return neuralThreadCount;
    }

    public long getTotalMemoryBytes() {
        return totalMemoryBytes;
    }

    public long getMaximumHeapBytes() {
        return maximumHeapBytes;
    }

    public int getImageGridLongSide() {
        return imageGridLongSide;
    }

    public int getTextureHeight() {
        return textureHeight;
    }

    public int getMaximumInputSide() {
        return maximumInputSide;
    }

    public int getVideoNormalizationSide() {
        return videoNormalizationSide;
    }

    public int getImageTriangleTarget() {
        return imageTriangleTarget;
    }

    public boolean isSustainedPerformanceSupported() {
        return sustainedPerformanceSupported;
    }

    public String getLabel() {
        switch (tier) {
            case TURBO:
                return "Turbo automatique";
            case QUALITY:
                return "Qualité automatique";
            case COMPATIBILITY:
            default:
                return "Compatible automatique";
        }
    }

    public String describe() {
        return String.format(
                Locale.FRANCE,
                "%s • %d cœurs • %.1f Go RAM • heap %.0f Mo • %d threads IA",
                getLabel(),
                processorCount,
                totalMemoryBytes / (1024.0 * 1024.0 * 1024.0),
                maximumHeapBytes / (1024.0 * 1024.0),
                neuralThreadCount
        );
    }
}
