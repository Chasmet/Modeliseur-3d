#!/usr/bin/env python3
from pathlib import Path

ACTIVITY = Path("app/src/main/java/com/chasmet/modeliseur3d/Manual3DActivity.java")
LAYOUT = Path("app/src/main/res/layout/activity_manual_3d.xml")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: motif attendu 1 fois, trouvé {count}")
    return text.replace(old, new, 1)


activity = ACTIVITY.read_text(encoding="utf-8")

activity = replace_once(
    activity,
    "import android.content.ClipData;\n",
    "import android.content.ClipData;\nimport android.content.ClipboardManager;\n",
    "import ClipboardManager",
)

activity = replace_once(
    activity,
    "    private TextView status;\n",
    "    private TextView status;\n"
    "    private TextView diagnosticLogText;\n"
    "    private Button copyDiagnosticLog;\n"
    "    private final StringBuilder diagnosticLog = new StringBuilder(8192);\n"
    "    private long diagnosticStartMs;\n",
    "champs journal diagnostic",
)

activity = replace_once(
    activity,
    "        status = findViewById(R.id.manualStatusText);\n",
    "        status = findViewById(R.id.manualStatusText);\n"
    "        diagnosticLogText = findViewById(R.id.diagnosticLogText);\n"
    "        copyDiagnosticLog = findViewById(R.id.copyDiagnosticLogButton);\n"
    "        copyDiagnosticLog.setOnClickListener(view -> copyDiagnosticLog());\n",
    "liaison journal diagnostic",
)

activity = replace_once(
    activity,
    "        refreshManualTransformUi();\n        updateSelection();\n    }\n\n    private void choose(int slot) {\n",
    "        refreshManualTransformUi();\n"
    "        updateSelection();\n"
    "        resetDiagnosticLog(\"APPLICATION PRETE\");\n"
    "    }\n\n"
    "    private void choose(int slot) {\n",
    "initialisation journal",
)

activity = replace_once(
    activity,
    "        SubjectCategory category = selectedSubjectCategory();\n",
    "        SubjectCategory category = selectedSubjectCategory();\n"
    "        resetDiagnosticLog(\"NOUVELLE RECONSTRUCTION 3D\");\n"
    "        appendDiagnostic(\"Catégorie = \" + category\n"
    "                + \" • épaisseur = \" + Math.round(depthValue * 100f) + \" %\"\n"
    "                + \" • entrée max = \" + Math.min(MAX_SIDE, profile.getMaximumInputSide()) + \" px\");\n"
    "        appendDiagnostic(\"Profils manuels : droit R\" + manualRotations[RIGHT_PROFILE]\n"
    "                + (manualMirrors[RIGHT_PROFILE] ? \" miroir\" : \" normal\")\n"
    "                + \" • gauche R\" + manualRotations[LEFT_PROFILE]\n"
    "                + (manualMirrors[LEFT_PROFILE] ? \" miroir\" : \" normal\"));\n",
    "configuration génération dans log",
)

old_progress = """        String memory = com.chasmet.modeliseur3d.model.MemoryDiagnostics.mark(stage.name());
        String detailedText = text + " • " + memory;
        runOnUiThread(() -> status.setText(detailedText));
"""
new_progress = """        String memory = com.chasmet.modeliseur3d.model.MemoryDiagnostics.mark(stage.name());
        String detailedText = text + " • " + memory;
        runOnUiThread(() -> {
            status.setText(detailedText);
            appendDiagnostic(stage.name() + " " + current + "/" + total + " — " + text);
        });
"""
activity = replace_once(activity, old_progress, new_progress, "progression moteur journalisée")

activity = replace_once(
    activity,
    "            setBusy(false, modelSummary);\n            startExportPreparation(mesh, texture);\n",
    "            setBusy(false, modelSummary);\n"
    "            appendDiagnostic(\"RESULTAT 3D — \" + modelSummary);\n"
    "            startExportPreparation(mesh, texture);\n",
    "résultat final journalisé",
)

activity = replace_once(
    activity,
    "        status.setText(modelSummary\n                + \" • préparation du GLB qualité sans simplification…\");\n",
    "        status.setText(modelSummary\n"
    "                + \" • préparation du GLB qualité sans simplification…\");\n"
    "        appendDiagnostic(\"EXPORT GLB — préparation démarrée\");\n",
    "début export journalisé",
)

activity = replace_once(
    activity,
    "                            status.setText(modelSummary + \" • \" + action + \"…\");\n",
    "                            status.setText(modelSummary + \" • \" + action + \"…\");\n"
    "                            appendDiagnostic(\"EXPORT \" + stage.name() + \" \" + current + \"/\" + total\n"
    "                                    + \" — \" + action);\n",
    "progress export journalisé",
)

activity = replace_once(
    activity,
    "        if (exportRequested) {\n",
    "        appendDiagnostic(\"EXPORT PRET — \" + formatFileSize(result.getSizeBytes())\n"
    "                + \" • \" + result.getTriangleCount() + \" triangles\"\n"
    "                + \" • texture \" + result.getTextureMaximumSide() + \" px\");\n"
    "        if (exportRequested) {\n",
    "export prêt journalisé",
)

activity = replace_once(
    activity,
    "        status.setText(modelSummary + \" • préparation GLB impossible : \" + detail);\n",
    "        status.setText(modelSummary + \" • préparation GLB impossible : \" + detail);\n"
    "        appendDiagnostic(\"ERREUR EXPORT — \" + error.getClass().getSimpleName()\n"
    "                + \" : \" + detail);\n",
    "erreur export journalisée",
)

activity = replace_once(
    activity,
    "        runOnUiThread(() -> {\n            setBusy(false, message);\n",
    "        runOnUiThread(() -> {\n"
    "            appendDiagnostic(\"ERREUR RECONSTRUCTION — \"\n"
    "                    + error.getClass().getSimpleName() + \" : \" + message);\n"
    "            setBusy(false, message);\n",
    "erreur reconstruction journalisée",
)

helper_marker = """    private static void recycle(Bitmap bitmap) {
"""
helpers = r'''    private void resetDiagnosticLog(String title) {
        diagnosticStartMs = System.currentTimeMillis();
        diagnosticLog.setLength(0);
        appendDiagnostic(title);
    }

    private void appendDiagnostic(String event) {
        if (event == null) {
            event = "événement sans détail";
        }
        double seconds = diagnosticStartMs <= 0L
                ? 0.0
                : (System.currentTimeMillis() - diagnosticStartMs) / 1000.0;
        String memory = com.chasmet.modeliseur3d.model.MemoryDiagnostics.snapshot();
        String line = String.format(
                Locale.FRANCE,
                "[%7.2f s] %s • %s",
                seconds,
                event,
                memory
        );
        if (diagnosticLog.length() > 60000) {
            diagnosticLog.delete(0, Math.min(12000, diagnosticLog.length()));
            diagnosticLog.insert(0, "[... début du log tronqué ...]\n");
        }
        diagnosticLog.append(line).append('\n');
        if (diagnosticLogText != null) {
            diagnosticLogText.setText(diagnosticLog.toString());
        }
    }

    private void copyDiagnosticLog() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) {
            Toast.makeText(this, "Presse-papiers indisponible", Toast.LENGTH_LONG).show();
            return;
        }
        String text = diagnosticLog.toString();
        if (text.trim().isEmpty()) {
            text = "Aucun diagnostic enregistré.";
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("Log Modéliseur 3D", text));
        Toast.makeText(this, "Log complet copié", Toast.LENGTH_SHORT).show();
    }

'''
activity = replace_once(activity, helper_marker, helpers + helper_marker, "méthodes journal diagnostic")
ACTIVITY.write_text(activity, encoding="utf-8")

layout = LAYOUT.read_text(encoding="utf-8")
if "@+id/diagnosticLogText" in layout:
    raise SystemExit("layout diagnostic déjà présent")
layout = layout.replace(
    'android:text="3D locale V7.3.1 — composants suivis + DA3"',
    'android:text="3D locale V9.5.2 — diagnostic complet + DA3"',
    1,
)
panel = r'''

    <TextView
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="6dp"
        android:text="Journal diagnostic réel"
        android:textColor="@color/text_primary"
        android:textSize="12sp"
        android:textStyle="bold" />

    <ScrollView
        android:layout_width="match_parent"
        android:layout_height="120dp"
        android:layout_marginTop="4dp"
        android:background="#161920"
        android:fillViewport="true"
        android:padding="6dp">

        <TextView
            android:id="@+id/diagnosticLogText"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:fontFamily="monospace"
            android:text="Le journal complet apparaîtra ici."
            android:textColor="#E7EAF0"
            android:textIsSelectable="true"
            android:textSize="9sp" />
    </ScrollView>

    <Button
        android:id="@+id/copyDiagnosticLogButton"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="4dp"
        android:text="COPIER TOUT LE LOG"
        android:textAllCaps="false"
        android:textStyle="bold" />
'''
end = layout.rfind("\n</LinearLayout>")
if end < 0:
    raise SystemExit("racine layout introuvable")
layout = layout[:end] + panel + layout[end:]
LAYOUT.write_text(layout, encoding="utf-8")

print("V9.5.2 runtime log appliqué : journal visible + RAM + copie presse-papiers + export/erreurs.")
