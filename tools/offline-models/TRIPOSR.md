# Reconstruction apprise dans le troisième onglet

L’APK 6.1.0 embarque réellement l’encodeur TripoSR (419 millions de paramètres),
quantifié à 4 bits pour les MatMul à poids constants, et le décodeur NeRF float32.
Aucune URL ni connexion réseau n’est utilisée par `TripoSREngine` sur Android.
Les poids sont téléchargés et vérifiés uniquement pendant la compilation.

Source MIT : [TripoSR](https://github.com/VAST-AI-Research/TripoSR).
Conversion ONNX MIT : [jc-builds/triposr-ios](https://huggingface.co/jc-builds/triposr-ios),
révision `f0c7507db372e147d97f7d3e502d67b022a0c751`.
Les empreintes des sources et de l’export sont fixées dans `fetch_triposr.py` et
vérifiées de nouveau dans l’APK avant publication. L’APK reste sous 512 Mio afin
que la mise à jour de 6.0.x puisse la télécharger.

Pour construire sans cache : Python 3.12, `pip install -r tools/offline-models/requirements.txt`,
puis Gradle. La conversion des poids est reproductible avec ONNX 1.17.0 et
ONNX Runtime 1.20.0, MatMulNBits 4 bits symétrique, block_size=128,
accuracy_level=4. Le float32 de référence de 1,67 Go n’est pas livré dans l’APK.

## Traitement réel

1. Le détourage existant est réutilisé. Un PNG transparent conserve son alpha.
2. Chaque objet détouré est centré à 90 % sur un carré 512 px gris 0,5.
3. L’encodeur CPU à deux threads infère chaque photo séparément. Une seule
   session est chargée pour les quatre vues, puis fermée avant le décodeur.
4. Les trois plans XY, XZ et YZ de 40 canaux chacun sont échantillonnés comme
   `grid_sample(align_corners=False, padding_mode=zeros)`. Le décodeur produit
   la densité brute ; exp(densité−1) est extraite au seuil 25 du modèle officiel.
5. Les quatre champs 64³ sont alignés sur leurs limites et pivotés dans un repère
   commun (face, dos, droite, gauche). La fusion conserve 75 % du meilleur support
   appris et 25 % de la moyenne pondérée des quatre vues, sous leurs silhouettes.
   Cela conserve une surface soutenue par une vue si une autre l’estime mal.
6. Une isosurface continue est maillée avec des sommets communs et des normales.
   L’atlas existant applique les quatre véritables photographies, puis exporte un GLB.
7. Chaque champ est conservé séparément avec une clé de modèle et de détourage.
   Les changements de détail ou de profondeur des profils réutilisent les champs.

TripoSR est entraîné sur une image à la fois. L’assemblage des quatre estimations
est une méthode de cette application, pas un modèle multivue natif TripoSR ni
TRELLIS complet. Les proportions sont mesurées dans les photos ; les creux et
petits détails restent estimés. Les poses doivent correspondre.

## Vérification

L’encodeur 4 bits a été exécuté sur CPU de développement, avec environ 1,9 Gio au
pic mémoire du processus pour quatre inférences successives. Cette mesure ne
prédit pas la vitesse, la chauffe ou la mémoire disponible sur un téléphone.
Une comparaison d’un échantillon contre le float32 donne environ 89 % d’IoU sur
l’occupation de la grille : la quantification n’est pas sans perte.

`TripoSROfflineTest` infère réellement quatre photos procédurales différentes,
interdit les connexions sortantes, vérifie les champs appris, la contribution des
quatre vues, le cache, l’atlas et un GLB distinct du moteur géométrique.
Les GLB sont validés avec le validateur Khronos. Les anciens tests d’atelier,
de navigation, de téléchargement de mise à jour et d’installateur restent exécutés.
La vitesse sur le téléphone de l’utilisateur reste à mesurer.

Les activités originales 2.5D et 3D à quatre photos restent inchangées.
Le moteur Silhouettes reste disponible dans le troisième onglet pour un calcul
plus léger. Le MCP est reporté ; Render ne participe pas à la reconstruction.
