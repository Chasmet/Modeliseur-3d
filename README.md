# Modéliseur 3D V6.0 — Android Java

Application Android qui transforme quatre vues réelles d'un même personnage
(face, profil droit, dos et profil gauche) en un modèle 3D texturé exportable en
GLB. Le dépôt contient aussi le moteur 2.5D Face/Dos et un catalogue de 259
assets 3D.

## Reconstruction V6 « précision extrême »

La V6 remplace l'ancienne intersection de voxels binaires par une enveloppe
visuelle continue :

1. détourage local de chaque vue avec IS-Net Anime FP32 ;
2. conservation de la confiance alpha du réseau au bord du sujet ;
3. correction automatique et manuelle de l'orientation des profils ;
4. normalisation indépendante des axes largeur, hauteur et profondeur ;
5. distance signée euclidienne pour chaque silhouette ;
6. fusion robuste face/dos et droite/gauche ;
7. récupération adaptative d'un détail seulement s'il est confirmé sur deux
   axes différents ;
8. arrondi local des sections du torse, des membres et des accessoires afin de
   supprimer les coins artificiels de l'intersection orthographique ;
9. champ de densité sous-pixel transmis directement au mailleur ;
10. surface lisse, normales recalculées et atlas multivue jusqu'à 2K ;
11. export du maillage complet sans simplification destructive.

Le mode haute précision utilise une grille allant jusqu'à 128 × 256 × 304 sur
les appareils disposant de suffisamment de mémoire. Un profil compatible réduit
automatiquement la grille et l'atlas pour éviter une saturation mémoire.

## Ce qui améliore réellement la qualité

- les contours ne sont plus coupés en « vrai/faux » avant la création du
  maillage ;
- ajouter des triangles sert désormais à représenter une surface fractionnaire,
  et non à lisser une forme déjà appauvrie ;
- les bras et les jambes séparés reçoivent une profondeur locale plus faible que
  le torse ;
- les espaces visibles, notamment entre les jambes, restent ouverts ;
- un accessoire caché dans une vue peut être conservé s'il est visible depuis
  deux directions perpendiculaires ;
- le score de conservation des silhouettes est calculé après reconstruction et
  affiché avec le résultat.

## Prise de vues recommandée

Pour exploiter la précision du moteur :

- photographier exactement le même personnage et la même pose ;
- garder le corps entier visible, sans couper les pieds ni les accessoires ;
- utiliser une lumière uniforme et un fond contrasté ;
- conserver la même hauteur de caméra et une distance proche ;
- fournir les vues dans l'ordre Face, Droite, Dos, Gauche ;
- corriger la rotation ou le miroir des profils dans l'écran prévu à cet effet.

## Export GLB

Le mode 3D crée un fichier autonome :

```text
personnage_3d_v6_0_precision.glb
```

L'export conserve le nombre complet de triangles, les normales, les UV et la
texture PNG. Le matériau externe utilise `KHR_materials_unlit`, masque les faces
arrière et désactive les mipmaps afin d'éviter le mélange entre les quatre zones
de l'atlas.

## Catalogue

- 259 assets classés ;
- 247 modèles procéduraux générés hors ligne sous licence CC0 ;
- 12 modèles officiels Khronos sous licences permissives ;
- 112 assets animés ;
- ouverture et export via le sélecteur de documents Android ;
- limite de sécurité de 8 Mo par asset téléchargé.

Le catalogue utilise Internet pour récupérer les modèles distants. La
reconstruction des photos reste exécutée sur le téléphone et n'envoie pas les
images à un serveur.

## Configuration Android

- Java uniquement ;
- `minSdkVersion 21` ;
- `compileSdkVersion 34` ;
- `targetSdkVersion 34` ;
- Java 17 ;
- ABI `arm64-v8a` ;
- ONNX Runtime Android 1.20.0 ;
- version `6.0.0` (`versionCode 36`).

## Compilation

```bash
chmod +x gradlew
./gradlew --no-daemon clean lintDebug assembleDebug
```

APK produit :

```text
app/build/outputs/apk/debug/app-debug.apk
```

Le workflow `.github/workflows/android.yml` :

- télécharge et vérifie IS-Net Anime FP32 par SHA-256 ;
- exécute les tests Java du catalogue, des orientations, de la géométrie
  historique et de l'enveloppe continue V6 ;
- lance `lintDebug` et `assembleDebug` ;
- vérifie la signature et le modèle ONNX inclus ;
- publie l'artefact `Modeliseur-V6.0-Precision-Continue-2K-debug`.

## Limite physique

Quatre images ne contiennent aucune information sur une zone cachée dans les
quatre vues. La V6 améliore fortement l'enveloppe, les contours et la surface,
mais elle ne peut pas inventer avec certitude l'intérieur d'un vêtement, un
dessous invisible ou une micro-géométrie absente des photos. Une reconstruction
photogrammétrique complète nécessiterait davantage d'angles et des
correspondances de points fiables.
