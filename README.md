# Modéliseur 3D V4.3 — vidéo 360°, multivue et GLB mobile

Application Android Java qui transforme une image, une planche, deux à quatre
vues ou une vidéo de rotation déjà découpée en GLB texturé.

La V4.3 propose deux moteurs :

- **haute fidélité en ligne** avec Tripo H3.1 ;
- **secours local V4.2** avec IS-Net Anime, Depth Anything V2 et la
  reconstruction géométrique Android existante.

## Vidéo 360° vers GLB

Le MP4 sélectionné reste sur le téléphone. L’application :

1. prélève quatre zones régulièrement espacées autour de 0, 25, 50 et 75 % ;
2. compare cinq trames dans chaque zone et conserve la plus nette ;
3. demande où se trouve la face et quel profil arrive ensuite ;
4. détoure, centre et prépare les quatre vues ;
5. envoie uniquement ces images JPEG préparées à Tripo ;
6. télécharge immédiatement le GLB H3.1 texturé.

Le clip doit contenir un seul sujet, une pose stable et une rotation complète.
La durée acceptée est comprise entre 1,2 seconde et 2 minutes. Une vidéo 1080p
sur fond propre donne de meilleurs résultats qu’un enregistrement compressé.

## Génération cloud

Le moteur utilise les API Tripo v3 :

- modèle `v3.1-20260211` ;
- géométrie et textures détaillées, PBR activé ;
- jusqu’à 100 000 faces pour le GLB haute définition ;
- multivue nommée `front`, `left`, `back`, `right` ;
- téléchargement HTTPS borné à 350 Mo et validation de l’en-tête GLB 2.0.

Une clé API personnelle est nécessaire. Elle n’est jamais inscrite dans le
code, le dépôt ou l’APK. Sur Android 6 et plus, elle est chiffrée par AES-GCM
avec une clé non exportable de l’Android Keystore. Sur Android 5, elle reste
uniquement en mémoire jusqu’à la fermeture de l’application.

L’API peut consommer des crédits Tripo. La V4.3 affiche le total déclaré par les
tâches de génération et de conversion.

## Deux GLB complémentaires

Chaque génération réussie conserve :

- `personnage_v43_h31_hd.glb` : original haute définition, non simplifié ;
- `personnage_v43_mobile_200ko.glb` : copie destinée au jeu, au plus
  **200 000 octets**.

La copie mobile est créée par simplification adaptative. L’application essaie
successivement des budgets de 1 600, 900, 450 puis 180 faces et des textures
JPEG de 256 ou 128 px. Chaque résultat est téléchargé et validé ; il n’est
accepté que si sa taille réelle est inférieure ou égale à 200 000 octets.

Une limite aussi basse ne peut pas être obtenue sans perte sur un modèle 3D
détaillé. La qualité intégrale reste donc disponible dans le GLB HD, tandis que
la copie mobile minimise la perte sous la contrainte de taille.

L’aperçu mobile repose sur Google Filament et accepte la compression géométrique
meshopt produite par Tripo.

## Mode local V4.2

Le bouton de secours hors connexion conserve le pipeline déjà validé :

- IS-Net Anime FP32 sur CPU, sans l’opérateur `ConvInteger` ;
- regroupement des morceaux d’une même silhouette ;
- enveloppe multivue ou volume monoculaire arrondi ;
- Depth Anything V2 Small avec NNAPI quand il est disponible ;
- export GLB 2.0, OBJ, MTL et texture PNG.

Les deux modèles ONNX sont téléchargés pendant le build, vérifiés par SHA-256
et intégrés à l’APK :

```text
afb6a5c28f3b6bf1618c6e43f02073ef9dfdc70e937502d51603e57b0a1df10c
6a92a19a47e8197fb6dbcf85be14600806019831fedfe7f86eeeeffd4c40dbba
```

## Confidentialité

- mode cloud : les images choisies ou les quatre trames extraites sont envoyées
  à Tripo pour exécuter la génération ; le MP4 complet n’est pas envoyé ;
- mode local : aucune image n’est envoyée ;
- les fichiers GLB sont stockés dans le dossier privé Documents de
  l’application puis partagés uniquement à la demande de l’utilisateur ;
- Android refuse le trafic HTTP non chiffré.

## Compilation

```bash
chmod +x gradlew
./gradlew --no-daemon clean lintDebug assembleDebug
```

APK généré :

```text
app/build/outputs/apk/debug/app-debug.apk
```

Le workflow `.github/workflows/android.yml` exécute les tests purs Java, le lint
Android et la compilation, puis publie l’artefact
`Modeliseur3D-V4.3-Video-Cloud-debug`.

## Limites réelles

Une vidéo dont le personnage change de pose ou de forme entre les angles ne peut
pas produire une géométrie parfaitement cohérente. Les objets transparents,
réfléchissants ou fortement occultés restent difficiles. La qualité dépend aussi
du service Tripo, du nombre de crédits disponibles et de la connexion réseau.
