# Modéliseur 3D V3 local Android

Application Android Java spécialisée pour les **planches de rotation multivues** contenant le même personnage de face, de dos et de profil.

## Pipeline V3 ultra propre

1. Lecture de la planche jusqu’à 3072 px sur son plus grand côté.
2. Détection automatique des cinq silhouettes principales.
3. Sélection de la grande vue de face, du dos et du profil.
4. Nettoyage des masques et suppression des éléments parasites.
5. Construction locale d’une enveloppe volumique à partir des vues face et profil.
6. Fermeture des petits défauts et conservation du volume principal.
7. Lissage du champ volumique.
8. Extraction d’une surface triangulée lisse par tétraèdres.
9. Calcul de normales continues pour éviter l’effet cubique de la V2.
10. Projection des textures face, dos, côté gauche et côté droit.
11. Extension des couleurs autour des silhouettes afin d’éviter les trous transparents.
12. Affichage OpenGL ES 3 avec éclairage doux et rotation tactile.
13. Export en `GLB 2.0`, `OBJ`, `MTL` et `PNG`.

Tout le traitement est effectué **sur le téléphone**, sans serveur et sans transfert de l’image.

## Utilisation de la puissance du téléphone

La résolution est adaptée automatiquement au nombre de cœurs CPU et à la mémoire disponible :

- Ultra propre : 112 × 224 × 84 voxels ;
- Haute précision : 96 × 192 × 72 ;
- Équilibrée : 80 × 160 × 60 ;
- Compatible : 64 × 128 × 48.

La création du volume et l’extraction du maillage utilisent plusieurs tâches en parallèle, jusqu’à dix travailleurs.

## Gestes

- Glisser avec un doigt : rotation 360°.
- Pincer avec deux doigts : zoom.
- Double appui ou bouton Recentrer : retour à la vue initiale.

## Formats exportés

- `personnage_v3.glb` : maillage, normales, UV et texture intégrée ;
- `personnage_v3.obj` : géométrie OBJ ;
- `personnage_v3.mtl` : matériau ;
- `texture_multivue_v3.png` : atlas de textures ;
- `informations.txt` : détails techniques.

## Configuration Android

- Java uniquement ;
- `minSdkVersion 21` ;
- `targetSdkVersion 34` ;
- `compileSdkVersion 34` ;
- OpenGL ES 3.0 ;
- Java 17 ;
- Android Gradle Plugin 8.10.1 ;
- Gradle 8.11.1.

## Compilation

```bash
chmod +x gradlew
./gradlew --no-daemon clean lintDebug assembleDebug
```

APK généré :

```text
app/build/outputs/apk/debug/app-debug.apk
```

Le workflow `.github/workflows/android.yml` compile automatiquement l’APK et le publie dans **Actions > Artifacts** sous le nom `Modeliseur3D-debug`.

## Structure principale

```text
app/src/main/java/com/chasmet/modeliseur3d/
├── MainActivity.java
├── gl/
│   ├── ModelGLSurfaceView.java
│   └── ModelRenderer.java
├── model/
│   ├── GlbExporter.java
│   ├── ImageToMeshGenerator.java
│   ├── MeshData.java
│   ├── ObjExporter.java
│   └── SmoothHullMesher.java
└── util/
    └── BitmapUtils.java
```

## Limite actuelle

La V3 produit un modèle beaucoup plus propre et continu que la V2, mais elle reconstruit toujours une enveloppe visuelle. Les détails totalement invisibles sur la planche, l’intérieur des vêtements et les espaces très fins entre les doigts restent estimés. Aucun squelette d’animation n’est encore généré.
