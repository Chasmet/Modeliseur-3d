# Reconstruction apprise dans le troisième onglet

L’APK 6.3.1 embarque réellement l’encodeur TripoSR (419 millions de paramètres),
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
5. Les quatre champs 64³, 88³ ou 112³ sont pivotés dans un repère commun
   (face, dos, droite, gauche). Les silhouettes opposées sont légèrement recalées,
   puis la fusion combine 40 % du meilleur support, 40 % du deuxième et 20 %
   de la moyenne pondérée par leur accord. Un contour signé tolère environ un pixel
   d’écart ; les ouvertures des silhouettes sont conservées.
6. L’isosurface continue utilise des sommets communs. Les minuscules composants
   sont filtrés ; les morceaux significatifs restent séparés. L’orientation des
   triangles est rendue cohérente et orientée vers l’extérieur des composants fermés.
7. Depuis 6.3.1, un lissage Taubin léger est facultatif : deux cycles 0,25/−0,26,
   déplacement maximal de 0,8/(hauteur de grille−1), extrémités et frontières
   ouvertes figées, bornes conservées. Les passes qui inverseraient un triangle
   sont corrigées localement ou abandonnées. Les normales sont recalculées avec
   les angles des sommets, moins sensibles aux petits triangles. Aucun membre
   ni raccord anatomique n’est inventé. Cette finition s’exécute avant les UV.
8. Quatre cartes de profondeur CPU limitent les projections aux surfaces visibles.
   Les photos se raccordent par pondération angulaire dans un atlas 2048², puis
   l’application exporte le GLB avec la version, la méthode et le réglage de lissage.
9. Les triplans et chaque grille sont conservés séparément avec une clé de modèle
   et de détourage. Le réglage de lissage, la profondeur des profils et un détail
   déjà calculé réutilisent les champs sans relancer l’encodeur.

Le bouton « Comparer les silhouettes des quatre vues » prépare les détourages et
montre l’accord des paires opposées sans inférence TripoSR. Le détourage IS-Net
reste optionnel. L’accord des silhouettes n’est pas une mesure de fidélité 3D.

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

## Option 3 — TripoSR une image (6.3.2)

Le troisième choix du moteur accepte une seule photo et réutilise le détourage
Face ainsi que les mêmes triplans locaux. Un seul passage encodeur est exécuté.
Le décodeur restitue la densité et ses trois logits RGB ; la sigmoid officielle
convertit les couleurs. La forme est extraite dans le repère natif, avec une
rotation Z vertical vers Y vertical, sans contrainte issue de photos absentes,
sans extrusion ni duplication de l’image en quatre fausses vues.

Le champ RGB possède un cache séparé `.field-N-rgb`, compatible avec les anciens
champs de densité. Le changement de mode conserve les autres photos, réglages
et GLB. Les textures sont un atlas de petits triangles interpolant les couleurs
neuronales, puis exportées par le même chemin GLB. Les zones invisibles restent
estimées par le modèle, pas mesurées. Le détail et le lissage restent disponibles ;
le réglage de profondeur des profils et la comparaison quatre vues sont masqués.

Accès : accueil → IA locale → TripoSR — 1 seule image, ou atelier → troisième
choix du moteur. Tests : véritable encodeur/décodeur à une image sans sockets,
cache réutilisé, profondeur non plate, couleurs et export GLB avec inputViews=1.
