package com.chasmet.modeliseur3d;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Debug;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Journal diagnostic persistant destiné aux tests téléphone et au support. */
final class DiagnosticLogStore {
    private static final String TAG = "ModeliseurDiag";
    private static final String FILE_NAME = "modeliseur-diagnostic.log";
    private static final long MAX_FILE_BYTES = 512L * 1024L;
    private static final Object LOCK = new Object();

    private static Context appContext;
    private static File logFile;
    private static String lastEntry = "";
    private static long lastEntryAtMs;

    private DiagnosticLogStore() {
    }

    static void initialize(Context context) {
        synchronized (LOCK) {
            if (appContext != null) {
                return;
            }
            appContext = context.getApplicationContext();
            logFile = new File(appContext.getFilesDir(), FILE_NAME);
            rotateIfNeededLocked();
            writeLocked("\n================ NOUVELLE SESSION ================\n");
            writeLocked(buildHeader(appContext));
            writeLocked("===================================================\n");
        }
    }

    static void append(Context context, String source, CharSequence message) {
        initialize(context);
        String text = message == null ? "(null)" : message.toString().trim();
        if (text.isEmpty()) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        synchronized (LOCK) {
            String dedupeKey = source + "|" + text;
            if (dedupeKey.equals(lastEntry) && now - lastEntryAtMs < 500L) {
                return;
            }
            lastEntry = dedupeKey;
            lastEntryAtMs = now;
            rotateIfNeededLocked();
            String line = timestamp() + " [" + source + "] " + text
                    + " | " + memorySnapshot() + "\n";
            writeLocked(line);
            Log.i(TAG, line.trim());
        }
    }

    static void appendThrowable(Context context, String source, Throwable throwable) {
        if (throwable == null) {
            append(context, source, "Throwable null");
            return;
        }
        StringWriter buffer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(buffer));
        append(context, source, buffer.toString());
    }

    static void copyToClipboard(Context context) {
        initialize(context);
        String payload = buildCopyPayload(context);
        ClipboardManager clipboard = (ClipboardManager)
                context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            Toast.makeText(context, "Presse-papiers indisponible.", Toast.LENGTH_LONG).show();
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("Logs Modéliseur 3D", payload));
        Toast.makeText(
                context,
                "Logs copiés. Tu peux maintenant les coller dans ChatGPT.",
                Toast.LENGTH_LONG
        ).show();
    }

    static String buildCopyPayload(Context context) {
        initialize(context);
        synchronized (LOCK) {
            StringBuilder output = new StringBuilder(64 * 1024);
            output.append("===== MODELISEUR 3D — DIAGNOSTIC COPIABLE =====\n");
            output.append(buildHeader(context));
            output.append("État mémoire au moment de la copie : ")
                    .append(memorySnapshot())
                    .append('\n');
            output.append("================================================\n\n");
            output.append(readLogLocked());
            return output.toString();
        }
    }

    private static String buildHeader(Context context) {
        String versionName = "?";
        long versionCode = -1L;
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            versionName = info.versionName == null ? "?" : info.versionName;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                versionCode = info.getLongVersionCode();
            } else {
                //noinspection deprecation
                versionCode = info.versionCode;
            }
        } catch (PackageManager.NameNotFoundException ignored) {
            // Le package courant existe forcément ; garder les valeurs de secours.
        }
        return "Application : " + context.getPackageName() + "\n"
                + "Version : " + versionName + " (" + versionCode + ")\n"
                + "Appareil : " + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                + "Android : " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT + "\n"
                + "ABI : " + supportedAbis() + "\n";
    }

    private static String supportedAbis() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            StringBuilder value = new StringBuilder();
            for (String abi : Build.SUPPORTED_ABIS) {
                if (value.length() > 0) {
                    value.append(", ");
                }
                value.append(abi);
            }
            return value.toString();
        }
        //noinspection deprecation
        return Build.CPU_ABI;
    }

    private static String memorySnapshot() {
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        long max = runtime.maxMemory();
        long nativeUsed = Debug.getNativeHeapAllocatedSize();
        return "heap " + mb(used) + "/" + mb(max)
                + " Mo, natif " + mb(nativeUsed) + " Mo";
    }

    private static String mb(long bytes) {
        return String.format(Locale.FRANCE, "%.1f", bytes / 1048576.0);
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.FRANCE)
                .format(new Date());
    }

    private static void rotateIfNeededLocked() {
        if (logFile != null && logFile.isFile() && logFile.length() > MAX_FILE_BYTES) {
            File old = new File(logFile.getParentFile(), FILE_NAME + ".old");
            if (old.exists()) {
                //noinspection ResultOfMethodCallIgnored
                old.delete();
            }
            if (!logFile.renameTo(old)) {
                //noinspection ResultOfMethodCallIgnored
                logFile.delete();
            }
        }
    }

    private static void writeLocked(String text) {
        if (logFile == null) {
            return;
        }
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(logFile, true),
                StandardCharsets.UTF_8
        )) {
            writer.write(text);
        } catch (Exception error) {
            Log.e(TAG, "Écriture du journal impossible", error);
        }
    }

    private static String readLogLocked() {
        if (logFile == null || !logFile.isFile()) {
            return "Aucun journal disponible.\n";
        }
        StringBuilder output = new StringBuilder((int) Math.min(logFile.length(), 256L * 1024L));
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(logFile),
                StandardCharsets.UTF_8
        ))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        } catch (Exception error) {
            output.append("Lecture du journal impossible : ")
                    .append(error.getMessage())
                    .append('\n');
        }
        return output.toString();
    }
}
