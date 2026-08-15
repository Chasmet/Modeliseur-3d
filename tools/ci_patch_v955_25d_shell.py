#!/usr/bin/env python3
from pathlib import Path

ENGINE = Path('app/src/main/java/com/chasmet/modeliseur3d/model/FaceBack25DEngine.java')
STRINGS = Path('app/src/main/res/values/strings.xml')


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: attendu 1 occurrence, trouvé {count}')
    return text.replace(old, new, 1)


source = ENGINE.read_text(encoding='utf-8')

old_build = '''            FaceBack25DMesher.BuildResult built = FaceBack25DMesher.build(
                    merged.left,
                    merged.right,
                    merged.topV,
                    merged.bottomV,
                    merged.aspectScale,
                    profile.halfDepth,
                    profile.columns,
                    layout
            );'''
new_build = '''            FaceBack25DShellMesher.BuildResult built = FaceBack25DShellMesher.build(
                    front.mask,
                    back.mask,
                    front.texture.getWidth(),
                    front.texture.getHeight(),
                    merged.aspectScale,
                    profile.halfDepth,
                    profile.rows,
                    profile.columns,
                    layout
            );'''
source = replace_once(source, old_build, new_build, 'activation shell mesher')

old_quality = '''            String quality = label
                    + " 2.5D V5.2 " + profile.label
                    + " • " + built.getRows() + " couches"
                    + " • " + built.getColumns() + " colonnes"
                    + " • épaisseur renforcée "
                    + Math.round(profile.halfDepth * 200.0f) + "%";
            String details = backend
                    + " • alignement Face/Dos commun"
                    + " • silhouette moyenne stabilisée"
                    + " • profils calculés depuis les bords réels"
                    + " • UV verticaux corrigés"
                    + " • texture avant et arrière distinctes";'''
new_quality = '''            String quality = label
                    + " 2.5D V5.5 Shell " + profile.label
                    + " • grille " + built.getRows() + "×" + built.getColumns()
                    + " • " + built.getActiveCells() + " cellules utiles"
                    + " • " + built.getBoundaryEdges() + " bords réels";
            String details = backend
                    + " • coque multi-composants Face/Dos"
                    + " • vides entre membres/roues conservés"
                    + " • profondeur locale depuis distance au contour"
                    + " • bords biseautés avant/milieu/arrière"
                    + " • côtés texturés avec les pixels locaux face/dos";'''
source = replace_once(source, old_quality, new_quality, 'texte qualité V5.5')

# Le nouveau maillage raster complet a besoin de davantage de colonnes pour
# conserver les roues, bras, accessoires et espaces négatifs. La charge reste
# très faible par rapport au moteur 3D volumique.
source = replace_once(
    source,
    '''                            144,
                            32,
                            5,
                            processors,
                            0.190f,
                            "Turbo"''',
    '''                            176,
                            80,
                            5,
                            processors,
                            0.165f,
                            "Turbo"''',
    'profil Turbo 2.5D',
)
source = replace_once(
    source,
    '''                            120,
                            28,
                            4,
                            processors,
                            0.178f,
                            "Qualité"''',
    '''                            152,
                            68,
                            4,
                            processors,
                            0.155f,
                            "Qualité"''',
    'profil Qualité 2.5D',
)
source = replace_once(
    source,
    '''                            92,
                            24,
                            4,
                            processors,
                            0.162f,
                            "Compatible"''',
    '''                            120,
                            52,
                            4,
                            processors,
                            0.145f,
                            "Compatible"''',
    'profil Compatible 2.5D',
)

source = source.replace('Vrai moteur Face/Dos 2.5D V5.2.', 'Vrai moteur Face/Dos 2.5D V5.5 Shell.')
ENGINE.write_text(source, encoding='utf-8')

strings = STRINGS.read_text(encoding='utf-8')
replacements = {
    'Modéliseur 2.5D V5.4 — Face + Dos réel': 'Modéliseur 2.5D V5.5 — Coque Face + Dos',
    'Le mode 2.5D aligne les silhouettes, garde les textures avant et arrière et reconstruit l’épaisseur depuis les bords réels.': 'Le mode 2.5D construit une coque fermée depuis les silhouettes face et dos, conserve les trous entre les membres, roues et accessoires, puis arrondit les vrais contours.',
    'Prêt — moteur 2.5D V5.4 Face + Dos réel.': 'Prêt — moteur 2.5D V5.5 Shell Face + Dos.',
    'Prêt — moteur Face + Dos V5.4 • %1$s': 'Prêt — moteur Face + Dos V5.5 Shell • %1$s',
    'Création du volume Face/Dos plus épais et plus stable…': 'Création de la coque 2.5D multi-composants et de ses bords arrondis…',
    'Création des vraies textures face, dos et côtés calculés depuis les bords…': 'Préparation des textures face/dos ; les côtés réutilisent les pixels locaux du contour…',
    'Personnage image 2.5D V5.4 prêt.': 'Coque 2.5D V5.5 prête.',
    'Personnage Face + Dos V5.4 prêt.': 'Coque Face + Dos V5.5 prête.',
    'Face + Dos réels V5.4': 'Face + Dos réels V5.5 Shell',
    'Deux détourages IS-Net • silhouette commune • vraies textures avant/arrière • profils depuis les bords • UV corrigés': 'Deux détourages IS-Net • masque complet • trous conservés • profondeur locale • bords biseautés • textures face/dos locales',
    'Partager ou enregistrer le personnage 2.5D V5.4': 'Partager ou enregistrer la coque 2.5D V5.5',
}
for old, new in replacements.items():
    if old not in strings:
        raise SystemExit(f'string introuvable: {old}')
    strings = strings.replace(old, new, 1)
STRINGS.write_text(strings, encoding='utf-8')

print('V5.5 Shell activée: masque complet, trous conservés, profondeur locale, bords biseautés.')
