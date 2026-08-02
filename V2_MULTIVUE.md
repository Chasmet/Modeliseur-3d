# Modéliseur 3D V2 multivue

Cette version est spécialisée pour une planche de rotation contenant plusieurs vues séparées du même personnage sur fond clair.

## Pipeline local

1. Détection des personnages présents sur la planche.
2. Sélection automatique de la grande vue de face, du dos et du profil.
3. Adaptation de la résolution 3D à la mémoire et au nombre de cœurs du téléphone.
4. Reconstruction par enveloppe visuelle volumique multi-vues.
5. Création d’un atlas de textures face, dos, profil gauche et profil droit.
6. Visualisation OpenGL ES 3 tactile.
7. Export principal en GLB 2.0 autonome, plus OBJ, MTL et PNG.

Aucune image n’est envoyée sur Internet.
