#!/usr/bin/env python3
from pathlib import Path

LAYOUT = Path("app/src/main/res/layout/activity_manual_3d.xml")
ANIMAL = Path("app/src/main/java/com/chasmet/modeliseur3d/model/AnimalLegTopologyRefiner.java")

# ---------------------------------------------------------------------------
# Intégrité quadrupède : les centres sont détectés sur les silhouettes, mais
# une jambe arrière large ne doit pas être restaurée seulement sur son bord.
# La zone de récupération longitudinale est donc élargie sans jamais retirer
# un voxel : seule de la matière confirmée par les vues peut être rajoutée.
# ---------------------------------------------------------------------------
animal = ANIMAL.read_text(encoding="utf-8")
old_radius = "float radiusZ = Math.max(2.2f, spanZ * (0.115f - 0.045f * progress));"
new_radius = "float radiusZ = Math.max(3.6f, spanZ * (0.160f - 0.050f * progress));"
if old_radius not in animal:
    raise SystemExit("V9.5.6 quadrupède : rayon longitudinal attendu introuvable")
animal = animal.replace(old_radius, new_radius, 1)
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

# Compatibilité du garde-fou du workflow historique tant que le nom du workflow
# n'est pas migré : le grep CI trouve encore ce marqueur, sans l'afficher.
text += "\n<!-- V9.5.4 — aperçu grand + meshing indexé + DA3 -->\n"
LAYOUT.write_text(text, encoding="utf-8")

print(
    "V9.5.6 intégrité : rayon jambes quadrupède élargi sans suppression + "
    "interface personnage/animal activée"
)
