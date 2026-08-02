# Modéliseur 3D local Android

Application Android Java qui transforme une image en un **maillage 3D local**, affichable et manipulable directement sur le téléphone.

## Fonctionnement actuel — version 1.0

1. L'utilisateur choisit une image dans sa galerie.
2. L'application estime la couleur du fond.
3. Elle détecte et conserve le plus grand sujet de l'image.
4. Elle crée une silhouette extrudée avec un relief calculé à partir de la distance aux contours.
5. Le modèle est affiché dans un moteur OpenGL ES 3.0.
6. L'utilisateur peut tourner le modèle au doigt, zoomer avec deux doigts et recentrer avec un double appui.
7. Le résultat peut être exporté au format `OBJ + MTL + PNG`.

Tout le traitement est réalisé **sans serveur et sans connexion Internet**.

## Limite importante

Cette première version génère un vrai maillage 3D, mais il s'agit d'un relief volumique estimé. Une image unique ne contient pas les informations exactes sur les parties invisibles. Le dos est donc approximé à partir de l'image avant.

Pour obtenir une reconstruction complète et plus réaliste à 360°, la prochaine étape sera l'intégration d'un modèle IA mobile converti en ONNX ou ORT. Ce type de modèle est volumineux et doit être optimisé pour la mémoire et la puissance du téléphone.

## Gestes

- Glisser avec un doigt : rotation.
- Pincer avec deux doigts : zoom.
- Double appui : réinitialisation de la caméra.

## Formats exportés

- `modele.obj` : géométrie.
- `modele.mtl` : matériau.
- `texture.png` : texture avec fond transparent.
- `informations.txt` : détails du maillage et limites de la reconstruction.

## Configuration Android

- Langage : Java.
- `minSdkVersion 21`.
- `targetSdkVersion 34`.
- `compileSdkVersion 34`.
- OpenGL ES 3.0 obligatoire.
- Java 17.
- Android Gradle Plugin 8.10.1.
- Gradle 8.11.1.

## Compilation locale

```bash
chmod +x gradlew
./gradlew assembleDebug
```

APK généré :

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Compilation GitHub Actions

Le workflow `.github/workflows/android.yml` compile automatiquement l'APK et le publie dans l'onglet **Actions > Artifacts** sous le nom `Modeliseur3D-debug`.

## Structure principale

```text
app/src/main/java/com/chasmet/modeliseur3d/
├── MainActivity.java
├── gl/
│   ├── ModelGLSurfaceView.java
│   └── ModelRenderer.java
├── model/
│   ├── ImageToMeshGenerator.java
│   ├── MeshData.java
│   └── ObjExporter.java
└── util/
    └── BitmapUtils.java
```

## Roadmap

- Sélection manuelle des vues avant, profil et arrière sur une planche de référence.
- Fusion de plusieurs vues pour améliorer les côtés et le dos.
- Modèle IA ONNX entièrement local.
- Export GLB compatible Godot, Unity et Blender.
- Réduction automatique du nombre de polygones.
- Génération d'un squelette pour l'animation.
