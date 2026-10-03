# Mentions de projets open source

## Modly

La V4.8 reprend des idées d’architecture observées dans le projet open source Modly : séparation du pipeline en étapes, traitement local, génération asynchrone et optimisation progressive des maillages.

Aucun composant Python, Electron ni modèle GPU de Modly n’est embarqué dans l’APK Android. Le moteur mobile reste une implémentation Java/ONNX Runtime distincte, adaptée aux ressources du téléphone.

Based on [Modly](https://github.com/lightningpixel/modly) by [Lightning Pixel](https://github.com/lightningpixel).

Modly est distribué sous licence MIT. Le texte de sa licence est disponible dans son dépôt d’origine.

## Depth Anything 3 Small

La V7.2 embarque un export ONNX fixe du checkpoint officiel `DA3-SMALL` pour
estimer ensemble la profondeur relative des vues face, droite, dos et gauche.
Le modèle est exécuté localement par ONNX Runtime ; aucune photo n'est envoyée
à ByteDance, Hugging Face ou un autre service.

Code et modèle : [ByteDance-Seed/Depth-Anything-3](https://github.com/ByteDance-Seed/Depth-Anything-3).
Le code et le checkpoint `DA3-SMALL` sont distribués sous licence Apache 2.0.
L'export ONNX s'appuie sur l'implémentation Apache 2.0
[devin-lai/Depth-Anything-3-Onnx](https://github.com/devin-lai/Depth-Anything-3-Onnx).

## ONNX Runtime

L'inférence Android utilise Microsoft ONNX Runtime, distribué sous licence MIT.
