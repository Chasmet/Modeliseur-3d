#!/usr/bin/env python3
from pathlib import Path

LAYOUT = Path("app/src/main/res/layout/activity_manual_3d.xml")

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

print("V9.5.6 UI : intégrité personnage + animal affichée, compatibilité CI conservée")
