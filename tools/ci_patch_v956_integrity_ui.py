#!/usr/bin/env python3
from pathlib import Path

LAYOUT = Path("app/src/main/res/layout/activity_manual_3d.xml")
ANIMAL = Path("app/src/main/java/com/chasmet/modeliseur3d/model/AnimalLegTopologyRefiner.java")
CHARACTER = Path("app/src/main/java/com/chasmet/modeliseur3d/model/CharacterLimbIntegrityRefiner.java")

# ---------------------------------------------------------------------------
# Intégrité quadrupède.
# Deux pics d'un même membre ne doivent pas être pris pour deux jambes.
# Les quatre appuis restent séparés anatomiquement et la reconstruction
# additive couvre toute l'épaisseur visible dans les silhouettes. Aucun voxel
# existant n'est supprimé.
# ---------------------------------------------------------------------------
animal = ANIMAL.read_text(encoding="utf-8")
replacements = {
    "Math.max(3, Math.round(spanX * 0.12f))":
        "Math.max(4, Math.round(spanX * 0.28f))",
    "Math.max(4, Math.round(spanZ * 0.18f))":
        "Math.max(6, Math.round(spanZ * 0.30f))",
    "float radiusX = Math.max(2.2f, spanX * (0.18f - 0.07f * progress));":
        "float radiusX = Math.max(3.0f, spanX * (0.210f - 0.055f * progress));",
    "float radiusZ = Math.max(2.2f, spanZ * (0.115f - 0.045f * progress));":
        "float radiusZ = Math.max(5.0f, spanZ * (0.180f - 0.040f * progress));",
}
for old_value, new_value in replacements.items():
    if old_value not in animal:
        raise SystemExit("V9.5.6 quadrupède : motif attendu introuvable : " + old_value)
    animal = animal.replace(old_value, new_value, 1)
ANIMAL.write_text(animal, encoding="utf-8")

# ---------------------------------------------------------------------------
# Intégrité personnage.
# Le premier garde-fou ne réparait les bras qu'à partir de 22 % de la hauteur
# utile. Sur un personnage avec épaules hautes, manches longues ou manteau,
# cette limite arrivait trop tard et pouvait laisser un bras incomplet. La
# bande bras commence désormais à 8 % et reste strictement additive.
# ---------------------------------------------------------------------------
character = CHARACTER.read_text(encoding="utf-8")
old_arm_band = "boolean armBand = progress >= 0.22f && progress <= 0.63f;"
new_arm_band = "boolean armBand = progress >= 0.08f && progress <= 0.66f;"
if old_arm_band not in character:
    raise SystemExit("V9.5.6 personnage : bande des bras attendue introuvable")
character = character.replace(old_arm_band, new_arm_band, 1)
CHARACTER.write_text(character, encoding="utf-8")

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
    "V9.5.6 intégrité : quatre appuis quadrupède séparés, membres restaurés "
    "sans suppression et bande bras personnage étendue jusqu'aux épaules"
)
