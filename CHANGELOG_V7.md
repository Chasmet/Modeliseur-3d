# V7 — profondeur neuronale multivue et vraie vidéo 360°

## Cause du plafond à 35 %

Le mode quatre photos utilisait IS-Net uniquement pour détourer le sujet. Le
maillage était ensuite créé depuis les silhouettes : les triangles
représentaient un volume lisse, mais aucune profondeur visuelle des vêtements,
du visage, du siège ou du kart n'entrait réellement dans la géométrie.

Le bouton vidéo était également encore relié au moteur 2.5D alors qu'un moteur
de surface huit vues existait déjà dans le dépôt.

## V7

- intégration locale de Depth Anything 3 Small, modèle Apache 2.0 ;
- export ONNX fixe `1 × 4 × 3 × 224 × 224` contrôlé contre PyTorch ;
- analyse simultanée face/droite/dos/gauche, avec attention entre les vues ;
- fusion des cartes de profondeur avec la coque continue ;
- sculpture locale des surfaces sans créer de matière hors des silhouettes ;
- garde-fou anti-écrasement si les profondeurs se contredisent ;
- retour automatique à la V6.1 si DA3 ou NNAPI n'est pas disponible ;
- vidéo reliée à la vraie surface 3D huit angles et à une texture cylindrique ;
- tests Java purs pour le relief, les cartes plates, les limites de la coque et
  la conservation du volume ;
- modèle et dépendances épinglés dans GitHub Actions.

## Version

- `versionName` : `7.0.0`
- `versionCode` : `38`
