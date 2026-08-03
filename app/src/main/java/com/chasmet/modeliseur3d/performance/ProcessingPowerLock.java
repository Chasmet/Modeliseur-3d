package com.chasmet.modeliseur3d.performance;

import android.content.Context;
import android.os.PowerManager;
import android.os.Process;

/** Maintient le CPU éveillé pendant un calcul local long. */
public final class ProcessingPowerLock implements AutoCloseable {
    private static final long MAXIMUM_LOCK_DURATION_MS = 30L * 60L * 1000L;

    private final PowerManager.WakeLock wakeLock;
    private boolean closed;

    private ProcessingPowerLock(PowerManager.WakeLock wakeLock) {
        this.wakeLock = wakeLock;
    }

    public static ProcessingPowerLock acquire(Context context, String reason) {
        PowerManager manager = (PowerManager) context.getApplicationContext()
                .getSystemService(Context.POWER_SERVICE);
        if (manager == null) {
            return new ProcessingPowerLock(null);
        }
        PowerManager.WakeLock lock = manager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Modeliseur3D:V47:" + reason
        );
        lock.setReferenceCounted(false);
        lock.acquire(MAXIMUM_LOCK_DURATION_MS);
        return new ProcessingPowerLock(lock);
    }

    public static void favorCurrentThread() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_MORE_FAVORABLE);
        } catch (SecurityException | IllegalArgumentException ignored) {
            // Android peut refuser une priorité plus favorable sur certains ROM.
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }
}
