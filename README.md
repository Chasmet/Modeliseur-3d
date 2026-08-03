# Modéliseur 3D V4.4 — Android local, image et vidéo

Application Android Java qui transforme localement :

- une image unique ;
- une planche contenant plusieurs vues ;
- une courte vidéo de rotation ;

en un modèle 3D texturé exportable en GLB.

## Confidentialité et fonctionnement

La V4.4 n’utilise :

- aucune API distante ;
- aucune clé ;
- aucun compte ;
- aucun crédit ni abonnement ;
- aucune permission Internet.

Les images, les trames vidéo, les modèles neuronaux et les GLB restent sur le téléphone. Internet est seulement utilisé par GitHub Actions pendant la compilation pour télécharger les modèles open source avant de les intégrer à l’APK.

## Vidéo locale en huit vues

Pour une vidéo, l’application :

1. vérifie que sa durée est comprise entre 1,2 seconde et 2 minutes ;
2. répartit huit zones sur la rotation ;
3. compare plusieurs trames dans chaque zone ;
4. conserve la trame la plus nette et la mieux exposée ;
5. corrige l’orientation de la vidéo ;
6. compose localement une planche 4 × 2 ;
7. transmet cette planche au moteur multivue embarqué.

Le MP4 n’est jamais envoyé ni copié sur un serveur.

## Reconstruction locale

Pipeline principal :

1. décodage de l’entrée jusqu’à 2048 px ;
2. détourage par **IS-Net Anime FP32** ;
3. regroupement des morceaux appartenant au même sujet ;
4. détection des vues réellement exploitables ;
5. enveloppe volumique multivue ou volume monoculaire arrondi ;
6. maillage lissé préservant les membres et accessoires ;
7. relief par **Depth Anything V2 Small FP32** ;
8. calcul NNAPI quand il est compatible, avec repli CPU multi-cœurs ;
9. affichage OpenGL ES 3 ;
10. export GLB, OBJ, MTL et texture.

## Deux GLB à chaque export

### GLB haute définition

```text
personnage_v44_local_hd.glb
```

Il conserve le maillage complet et la texture PNG intégrée.

### GLB mobile limité à 200 Ko

```text
personnage_v44_mobile_200ko.glb
```

L’application réduit progressivement :

- le nombre de triangles ;
- la taille de la texture ;
- la qualité JPEG ;

puis mesure le fichier réellement écrit. Le GLB mobile n’est accepté que si sa taille est comprise entre 1 et **200 000 octets**.

Une limite de 200 Ko impose nécessairement une perte de détails. Le GLB HD reste disponible pour conserver la meilleure qualité produite par le téléphone.

## Modèles embarqués

### IS-Net Anime FP32

```text
SHA-256 : 6a92a19a47e8197fb6dbcf85be14600806019831fedfe7f86eeeeffd4c40dbba
```

### Depth Anything V2 Small FP32

```text
SHA-256 : afb6a5c28f3b6bf1618c6e43f02073ef9dfdc70e937502d51603e57b0a1df10c
```

Runtime : ONNX Runtime Android 1.20.0.

## Configuration Android

- langage : Java ;
- `minSdkVersion 21` ;
- `compileSdkVersion 34` ;
- `targetSdkVersion 34` ;
- ABI : `arm64-v8a` ;
- version : `4.4.0` ;
- versionCode : `10`.

## Compilation

```bash
chmod +x gradlew
./gradlew --no-daemon clean lintDebug assembleDebug
```

APK produit :

```text
app/build/outputs/apk/debug/app-debug.apk
```

Le workflow `.github/workflows/android.yml` exécute les tests Java, vérifie l’absence de permission Internet, compile l’APK, contrôle sa signature et les SHA-256 des deux modèles, puis publie l’artefact :

```text
Modeliseur3D-V4.4-Local-Video8-debug
```

## Principaux fichiers V4.4

```text
app/src/main/java/com/chasmet/modeliseur3d/
├── MainActivity.java
├── media/
│   ├── VideoFrameExtractor.java
│   └── VideoSheetComposer.java
├── model/
│   ├── AnimeSegmentationEngine.java
│   ├── NeuralDepthEngine.java
│   ├── NeuralReconstructionEngine.java
│   ├── MobileMeshOptimizer.java
│   ├── MobileGlbExporter.java
│   ├── GlbExporter.java
│   └── ObjExporter.java
└── gl/
    ├── ModelGLSurfaceView.java
    └── ModelRenderer.java
```

## Limites réelles

Cette application n’exécute pas un grand générateur 3D distant. Elle combine segmentation, profondeur neuronale et reconstruction géométrique sur Android. La précision dépend directement de la qualité de l’image ou de la rotation vidéo : pose stable, sujet entier visible, fond propre, éclairage régulier et angles suffisamment différents.

Les zones constamment cachées, l’intérieur des vêtements et les détails très fins ne peuvent pas être reconstruits exactement. Aucun squelette d’animation n’est généré dans cette version.
