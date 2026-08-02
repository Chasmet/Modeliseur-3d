# Modéliseur 3D V4 Neural — Android local

Application Android Java spécialisée pour les **planches de rotation multivues** contenant le même personnage de face, de dos et de profil.

La V4 ajoute un véritable réseau neuronal embarqué à la reconstruction géométrique. L’image reste sur le téléphone.

## Moteur V4

Le pipeline est hybride afin de rester utilisable sur Android :

1. lecture de la planche jusqu’à 3072 px ;
2. détection des silhouettes et sélection automatique des vues ;
3. construction d’une enveloppe 3D multivue fermée ;
4. extraction d’une surface triangulée lisse ;
5. découpe de l’atlas en face, dos et profil ;
6. trois inférences locales avec **Depth Anything V2 Small FP32** ;
7. normalisation et filtrage des cartes de profondeur ;
8. fusion des profondeurs selon l’orientation de chaque normale ;
9. déformation bornée de la surface pour ajouter le relief neuronal ;
10. recalcul des normales ;
11. affichage OpenGL ES 3 ;
12. export GLB 2.0, OBJ, MTL et PNG.

## Intelligence artificielle embarquée

- Modèle : Depth Anything V2 Small FP32 ;
- taille du modèle : environ 99 Mo ;
- licence du modèle Small : Apache-2.0 ;
- runtime : ONNX Runtime Android 1.26.0, licence MIT ;
- accélération : NNAPI quand Android et le modèle le permettent ;
- repli : CPU multi-cœurs avec optimisations ONNX ;
- entrée du réseau : 518 × 518, normalisation ImageNet ;
- aucune API distante et aucun serveur.

Le modèle est téléchargé pendant la compilation, vérifié par SHA-256 puis inclus dans les assets de l’APK. Au premier lancement, il est copié dans le stockage privé de l’application pour permettre un chargement efficace par ONNX Runtime.

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

Le premier build télécharge automatiquement le modèle neuronal et vérifie son SHA-256 :

```text
afb6a5c28f3b6bf1618c6e43f02073ef9dfdc70e937502d51603e57b0a1df10c
```

APK généré :

```text
app/build/outputs/apk/debug/app-debug.apk
```

Le workflow `.github/workflows/android.yml` publie l’APK dans **Actions > Artifacts** sous le nom `Modeliseur3D-V4-Neural-debug`.

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
│   ├── NeuralDepthEngine.java
│   ├── NeuralReconstructionEngine.java
│   ├── ObjExporter.java
│   └── SmoothHullMesher.java
└── util/
    └── BitmapUtils.java
```

## Limites réelles

Cette V4 utilise bien un réseau neuronal, mais elle ne prétend pas exécuter sur téléphone un grand modèle génératif 3D de plusieurs milliards de paramètres. Elle combine une enveloppe multivue fiable et une profondeur neuronale détaillée.

Les zones absentes de toutes les vues restent estimées. Les espaces très fins entre les doigts, l’intérieur des vêtements et les éléments cachés ne peuvent pas être reconstruits exactement. Aucun squelette d’animation n’est encore généré.
