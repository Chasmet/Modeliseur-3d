# Modéliseur 3D V4.2 Neural — Android local

Application Android Java pour une **image unique** ou une **planche de rotation multivue** d'un personnage, animal, monstre ou objet détourable.

La V4 ajoute un véritable réseau neuronal embarqué à la reconstruction géométrique. L’image reste sur le téléphone.

## Moteur V4

Le pipeline est hybride afin de rester utilisable sur Android :

1. lecture de l'image jusqu’à 2048 px ;
2. détourage neuronal puis regroupement des morceaux appartenant au même sujet ;
3. distinction automatique entre image unique et vraies vues face/dos/profil ;
4. volume monoculaire arrondi ou enveloppe 3D multivue selon l'entrée ;
5. préservation des membres, pattes et accessoires séparés ;
6. extraction d’une surface triangulée lisse ;
7. création d'un atlas face, dos et profil synthétique ou réel ;
8. une à trois inférences utiles avec **Depth Anything V2 Small FP32** ;
9. fusion bornée du relief puis recalcul des normales ;
10. affichage OpenGL ES 3 et export GLB 2.0, OBJ, MTL et PNG.

## Intelligence artificielle embarquée

Segmentation :

- modèle : IS-Net Anime FP32 (176 068 431 octets) ;
- graphe FP32 sans `ConvInteger` ;
- exécution CPU pure, sans NNAPI ;
- entrée RGB 1024 × 1024 dans l'intervalle `[0, 1]`, avec conservation du ratio et fond noir ;
- arrière-plan du masque rendu transparent avant la détection des vues.

Relief :

- Modèle : Depth Anything V2 Small FP32 ;
- taille du modèle : environ 99 Mo ;
- licence du modèle Small : Apache-2.0 ;
- runtime : ONNX Runtime Android 1.20.0, licence MIT ;
- version du runtime choisie pour conserver `minSdkVersion 21` ;
- accélération : NNAPI quand Android et le modèle le permettent, uniquement pour Depth Anything V2 ;
- repli : CPU multi-cœurs avec optimisations ONNX ;
- entrée du réseau : 518 × 518, normalisation ImageNet ;
- aucune API distante et aucun serveur.

Les deux modèles sont téléchargés pendant la compilation, vérifiés par SHA-256 puis inclus dans les assets de l’APK. Au premier lancement, ils sont copiés dans le stockage privé de l’application pour permettre un chargement efficace par ONNX Runtime.

## Puissance du téléphone

La V4 utilise deux niveaux de calcul :

- le moteur géométrique répartit la création du volume et du maillage sur plusieurs cœurs ;
- ONNX Runtime utilise NNAPI ou le CPU optimisé pour les trois inférences neuronales.

Résolution géométrique adaptative :

- Ultra propre : 112 × 224 × 84 voxels ;
- Haute précision : 96 × 192 × 72 ;
- Équilibrée : 80 × 160 × 60 ;
- Compatible : 64 × 128 × 48.

La V4 est actuellement compilée pour `arm64-v8a`, adapté aux téléphones Android modernes 64 bits et permettant de ne pas multiplier inutilement la taille des bibliothèques natives.

## Gestes

- glisser avec un doigt : rotation 360° ;
- pincer avec deux doigts : zoom ;
- double appui ou bouton Recentrer : vue initiale.

## Exports

- `personnage_v4_neural.glb` ;
- `personnage_v4_neural.obj` ;
- `personnage_v4_neural.mtl` ;
- `texture_multivue_v4.png` ;
- `informations.txt`.

Le GLB contient la géométrie, les normales, les UV et la texture intégrée. Il est importable dans Godot, Blender et Unity.

## Compilation

```bash
chmod +x gradlew
./gradlew --no-daemon clean lintDebug assembleDebug
```

Le premier build télécharge automatiquement les modèles neuronaux et vérifie leurs SHA-256 :

```text
afb6a5c28f3b6bf1618c6e43f02073ef9dfdc70e937502d51603e57b0a1df10c
6a92a19a47e8197fb6dbcf85be14600806019831fedfe7f86eeeeffd4c40dbba
```

APK généré :

```text
app/build/outputs/apk/debug/app-debug.apk
```

Le workflow `.github/workflows/android.yml` publie l’APK dans **Actions > Artifacts** sous le nom `Modeliseur3D-V4.2-Neural-debug`.

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
│   ├── AnimeSegmentationEngine.java
│   ├── MeshData.java
│   ├── NeuralDepthEngine.java
│   ├── NeuralReconstructionEngine.java
│   ├── ViewCandidateGrouper.java
│   ├── ObjExporter.java
│   └── SmoothHullMesher.java
└── util/
    └── BitmapUtils.java
```

## Limites réelles

Cette V4 utilise bien deux réseaux neuronaux, mais elle ne prétend pas exécuter sur téléphone un grand modèle génératif 3D de plusieurs milliards de paramètres. Elle combine un détourage anime, une enveloppe multivue et une profondeur neuronale détaillée.

La V4.2 ne réutilise plus une vue de face comme faux profil. Avec une image unique, l'épaisseur dépend de la distance au bord : les membres restent plus fins et le corps gagne un volume progressif. Avec une planche, les morceaux proches sont d'abord regroupés puis seules les vraies silhouettes sont comparées.

Les zones absentes de toutes les vues restent estimées. Les espaces très fins entre les doigts, l’intérieur des vêtements et les éléments cachés ne peuvent pas être reconstruits exactement. Aucun squelette d’animation n’est encore généré.
