package com.chasmet.modeliseur3d.model;

import android.os.Debug;

import java.util.Locale;

/**
 * Diagnostic mémoire léger pour le pipeline 3D V9.5.
 *
 * <p>Conserve la dernière étape connue ainsi qu'un instantané du heap Java et
 * du heap natif. Aucun gros buffer n'est alloué ici : cette classe sert
 * uniquement à rendre les OOM exploitables sur téléphone.</p>
 */
public final class MemoryDiagnostics {
    private static volatile String currentStage = "initialisation";
    private static volatile String lastSnapshot = "initialisation";

    private MemoryDiagnostics() {
    }

    public static String mark(String stage) {
        String safeStage = stage == null || stage.trim().isEmpty()
                ? "étape inconnue"
                : stage.trim();
        currentStage = safeStage;
        lastSnapshot = safeStage + " — " + snapshot();
        return lastSnapshot;
    }

    /**
     * Mesure la mémoire courante sans modifier l'étape mémorisée. Cette méthode
     * est utilisée par le journal visible dans l'application : écrire une ligne
     * de log ne doit jamais remplacer MESHING/DA3/TEXTURE comme étape OOM.
     */
    public static String snapshot() {
        Runtime runtime = Runtime.getRuntime();
        long javaUsed = Math.max(0L, runtime.totalMemory() - runtime.freeMemory());
        long javaMax = Math.max(1L, runtime.maxMemory());
        long javaHeadroom = Math.max(0L, javaMax - javaUsed);
        long nativeAllocated = Math.max(0L, Debug.getNativeHeapAllocatedSize());
        long nativeSize = Math.max(nativeAllocated, Debug.getNativeHeapSize());
        return "Java " + mb(javaUsed) + "/" + mb(javaMax) + " Mo"
                + " (libre " + mb(javaHeadroom) + " Mo)"
                + " • natif " + mb(nativeAllocated) + "/" + mb(nativeSize) + " Mo";
    }

    public static String lastSnapshot() {
        return lastSnapshot;
    }

    public static String describeOom() {
        String snapshot = lastSnapshot;
        if (snapshot == null || snapshot.trim().isEmpty()) {
            return currentStage;
        }
        return snapshot;
    }

    private static String mb(long bytes) {
        return String.format(Locale.FRANCE, "%.1f", bytes / (1024.0 * 1024.0));
    }
}
