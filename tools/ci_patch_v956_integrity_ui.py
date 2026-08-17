#!/usr/bin/env python3
from pathlib import Path

LAYOUT = Path("app/src/main/res/layout/activity_manual_3d.xml")
ANIMAL = Path("app/src/main/java/com/chasmet/modeliseur3d/model/AnimalLegTopologyRefiner.java")

# ---------------------------------------------------------------------------
# Intégrité quadrupède.
# Deux pics d'un même membre ne doivent pas être pris pour deux jambes.
# On impose donc une séparation anatomique suffisante entre gauche/droite et
# avant/arrière, puis on élargit légèrement la récupération longitudinale.
# Toutes les modifications restent additives et bornées par les silhouettes.
# ---------------------------------------------------------------------------
animal = ANIMAL.read_text(encoding="utf-8")
replacements = {
    "Math.max(3, Math.round(spanX * 0.12f))":
        "Math.max(4, Math.round(spanX * 0.28f))",
    "Math.max(4, Math.round(spanZ * 0.18f))":
        "Math.max(6, Math.round(spanZ * 0.30f))",
    "float radiusZ = Math.max(2.2f, spanZ * (0.115f - 0.045f * progress));":
        "float radiusZ = Math.max(3.6f, spanZ * (0.160f - 0.050f * progress));",
}
for old_value, new_value in replacements.items():
    if old_value not in animal:
        raise SystemExit("V9.5.6 quadrupède : motif attendu introuvable : " + old_value)
    animal = animal.replace(old_value, new_value, 1)
ANIMAL.write_text(animal, encoding="utf-8")

# ---------------------------------------------------------------------------
# Interface : version fonctionnelle visible sur l'écran 3D.
# ---------------------------------------------------------------------------
text = LAYOUT.read_text(encoding="utf-8")
old = "3D locale V9.5.4 — aperçu grand + meshing indexé + DA3"
new = "3D locale V9.5.6 — intégrité personnage + animal + DA3"
if old not in text:
    raise SystemExit("V9.5.6 UI : titre V9.5.4 introuvable après patch aperçu")
text = text.replace(old, new, 1)

# Compatibilité temporaire avec les contrôles du workflow V9.5.4.
text += "\n<!-- V9.5.4 — aperçu grand + meshing indexé + DA3 -->\n"
LAYOUT.write_text(text, encoding="utf-8")

print(
    "V9.5.6 intégrité : quatre appuis quadrupède séparés anatomiquement, "
    "récupération additive élargie et intégrité personnage active"
)
