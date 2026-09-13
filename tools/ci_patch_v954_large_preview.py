#!/usr/bin/env python3
from pathlib import Path

RENDERER = Path("app/src/main/java/com/chasmet/modeliseur3d/gl/ModelRendererV52.java")
LAYOUT = Path("app/src/main/res/layout/activity_manual_3d.xml")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: motif attendu 1 fois, trouvé {count}")
    return text.replace(old, new, 1)


# ---------------------------------------------------------------------------
# 1) Aperçu OpenGL : cadrage automatique réel sur les bornes du mesh.
# ---------------------------------------------------------------------------
renderer = RENDERER.read_text(encoding="utf-8")

renderer = replace_once(
    renderer,
    """    private float angleX = -2.0f;
    private float angleY = 0.0f;
    private float zoom = 1.18f;
    private boolean autoRotation;
""",
    """    private float angleX = -2.0f;
    private float angleY = 0.0f;
    // zoom = geste utilisateur ; fitScale = cadrage automatique du modèle.
    private float zoom = 1.0f;
    private float fitScale = 1.18f;
    private float modelCenterX;
    private float modelCenterY;
    private float modelCenterZ;
    private float modelSpanX = 2.0f;
    private float modelSpanY = 2.0f;
    private float modelSpanZ = 2.0f;
    private boolean autoRotation;
""",
    "champs cadrage automatique",
)

renderer = replace_once(
    renderer,
    """        surfaceHeight = Math.max(1, height);
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight);
        updateProjection();
""",
    """        surfaceHeight = Math.max(1, height);
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight);
        updateProjection();
        synchronized (this) {
            updateFitScale();
        }
""",
    "recalcul cadrage au redimensionnement",
)

renderer = replace_once(
    renderer,
    """        float drawAngleX;
        float drawAngleY;
        float drawZoom;
        synchronized (this) {
            updateAutomaticRotation();
            drawAngleX = angleX;
            drawAngleY = angleY;
            drawZoom = zoom;
        }

        Matrix.setIdentityM(model, 0);
        Matrix.scaleM(model, 0, drawZoom, drawZoom, drawZoom);
        Matrix.rotateM(model, 0, drawAngleX, 1.0f, 0.0f, 0.0f);
        Matrix.rotateM(model, 0, drawAngleY, 0.0f, 1.0f, 0.0f);
""",
    """        float drawAngleX;
        float drawAngleY;
        float drawZoom;
        float drawFitScale;
        float drawCenterX;
        float drawCenterY;
        float drawCenterZ;
        synchronized (this) {
            updateAutomaticRotation();
            drawAngleX = angleX;
            drawAngleY = angleY;
            drawZoom = zoom;
            drawFitScale = fitScale;
            drawCenterX = modelCenterX;
            drawCenterY = modelCenterY;
            drawCenterZ = modelCenterZ;
        }

        Matrix.setIdentityM(model, 0);
        float finalScale = drawZoom * drawFitScale;
        Matrix.scaleM(model, 0, finalScale, finalScale, finalScale);
        Matrix.rotateM(model, 0, drawAngleX, 1.0f, 0.0f, 0.0f);
        Matrix.rotateM(model, 0, drawAngleY, 0.0f, 1.0f, 0.0f);
        // Centre le vrai sujet avant rotation : aucun kart/personnage ne reste
        // minuscule ou décalé à cause de ses bornes géométriques.
        Matrix.translateM(model, 0, -drawCenterX, -drawCenterY, -drawCenterZ);
""",
    "application cadrage au rendu",
)

renderer = replace_once(
    renderer,
    """    public synchronized void setModel(MeshData mesh, Bitmap texture) {
        pendingMesh = mesh;
        pendingTexture = texture;
    }
""",
    """    public synchronized void setModel(MeshData mesh, Bitmap texture) {
        pendingMesh = mesh;
        pendingTexture = texture;
        updateFraming(mesh);
        zoom = 1.0f;
        lastFrameNanos = 0L;
    }
""",
    "cadrage à chaque nouveau modèle",
)

renderer = replace_once(
    renderer,
    """    public synchronized void scale(float factor) {
        zoom = clamp(zoom * factor, 0.48f, 2.8f);
    }

    public synchronized void resetView() {
        angleX = -2.0f;
        angleY = 0.0f;
        zoom = 1.18f;
        lastFrameNanos = 0L;
    }
""",
    """    public synchronized void scale(float factor) {
        zoom = clamp(zoom * factor, 0.35f, 4.5f);
    }

    public synchronized void resetView() {
        angleX = -2.0f;
        angleY = 0.0f;
        zoom = 1.0f;
        updateFitScale();
        lastFrameNanos = 0L;
    }
""",
    "zoom utilisateur indépendant du cadrage",
)

renderer = replace_once(
    renderer,
    """    private void updateAutomaticRotation() {
""",
    """    private void updateFraming(MeshData mesh) {
        if (mesh == null || mesh.getPositions() == null || mesh.getPositions().length < 3) {
            modelCenterX = 0.0f;
            modelCenterY = 0.0f;
            modelCenterZ = 0.0f;
            modelSpanX = 2.0f;
            modelSpanY = 2.0f;
            modelSpanZ = 2.0f;
            updateFitScale();
            return;
        }
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;
        float[] positions = mesh.getPositions();
        int valid = 0;
        for (int i = 0; i + 2 < positions.length; i += 3) {
            float x = positions[i];
            float y = positions[i + 1];
            float z = positions[i + 2];
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
                continue;
            }
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
            valid++;
        }
        if (valid == 0) {
            modelCenterX = 0.0f;
            modelCenterY = 0.0f;
            modelCenterZ = 0.0f;
            modelSpanX = modelSpanY = modelSpanZ = 2.0f;
        } else {
            modelCenterX = (minX + maxX) * 0.5f;
            modelCenterY = (minY + maxY) * 0.5f;
            modelCenterZ = (minZ + maxZ) * 0.5f;
            modelSpanX = Math.max(0.05f, maxX - minX);
            modelSpanY = Math.max(0.05f, maxY - minY);
            modelSpanZ = Math.max(0.05f, maxZ - minZ);
        }
        updateFitScale();
    }

    private void updateFitScale() {
        // La FOV verticale est la contrainte principale. Sur écran large,
        // largeur/profondeur utilisent naturellement l'espace horizontal.
        float aspect = Math.max(0.60f, surfaceWidth / (float) Math.max(1, surfaceHeight));
        float horizontalEquivalent = Math.max(modelSpanX, modelSpanZ) / aspect;
        float effectiveSpan = Math.max(modelSpanY, horizontalEquivalent);
        // 2.30 unités remplissent ~85 % de la hauteur avec caméra z=3.90/FOV 38°.
        fitScale = clamp(2.30f / Math.max(0.05f, effectiveSpan), 0.72f, 3.6f);
    }

    private void updateAutomaticRotation() {
""",
    "méthodes auto-fit",
)

RENDERER.write_text(renderer, encoding="utf-8")


# ---------------------------------------------------------------------------
# 2) Écran résultat : priorité à la 3D, logs toujours copiables mais repliés.
# ---------------------------------------------------------------------------
layout = LAYOUT.read_text(encoding="utf-8")

layout = replace_once(
    layout,
    """        <FrameLayout
            android:id="@+id/viewer3dContainer"
            android:layout_width="match_parent"
            android:layout_height="0dp"
            android:layout_weight="1"
            android:background="@drawable/bg_panel" />
""",
    """        <FrameLayout
            android:id="@+id/viewer3dContainer"
            android:layout_width="match_parent"
            android:layout_height="0dp"
            android:layout_weight="1"
            android:minHeight="260dp"
            android:background="@drawable/bg_panel" />
""",
    "hauteur minimale aperçu 3D",
)

layout = replace_once(
    layout,
    """        <TextView
            android:id="@+id/manualStatusText"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_marginStart="8dp"
            android:layout_weight="1"
            android:text="Sélectionne les quatre vues du même sujet."
            android:textColor="@color/text_secondary"
            android:textSize="13sp" />
""",
    """        <TextView
            android:id="@+id/manualStatusText"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_marginStart="8dp"
            android:layout_weight="1"
            android:ellipsize="end"
            android:maxLines="2"
            android:text="Sélectionne les quatre vues du même sujet."
            android:textColor="@color/text_secondary"
            android:textSize="12sp" />
""",
    "statut compact pour ne plus écraser la vue",
)

# Le journal reste intégralement alimenté et copiable via son bouton ; seule
# sa prévisualisation texte est masquée pour rendre l'espace au modèle 3D.
layout = replace_once(
    layout,
    """        android:text="Journal diagnostic réel"
        android:textColor="@color/text_primary"
""",
    """        android:text="Journal diagnostic réel"
        android:visibility="gone"
        android:textColor="@color/text_primary"
""",
    "masquage titre journal dans écran résultat",
)

layout = replace_once(
    layout,
    """    <ScrollView
        android:layout_width="match_parent"
        android:layout_height="120dp"
        android:layout_marginTop="4dp"
        android:background="#161920"
        android:fillViewport="true"
        android:padding="6dp">
""",
    """    <ScrollView
        android:layout_width="match_parent"
        android:layout_height="120dp"
        android:layout_marginTop="4dp"
        android:background="#161920"
        android:fillViewport="true"
        android:padding="6dp"
        android:visibility="gone">
""",
    "journal replié mais conservé",
)

layout = layout.replace(
    '3D locale V9.5.3 — FINAL meshing indexé + DA3',
    '3D locale V9.5.4 — aperçu grand + meshing indexé + DA3',
    1,
)
LAYOUT.write_text(layout, encoding="utf-8")

print(
    "V9.5.4 aperçu appliqué : viewport >=260dp, statut 2 lignes, journal visuel replié, "
    "logs toujours copiables et cadrage OpenGL automatique centré sur le mesh."
)
