# Modéliseur 3D V7 DA3 — Android Java

Application Android qui transforme quatre vues réelles d'un même personnage
(face, profil droit, dos et profil gauche) en un modèle 3D texturé exportable en
GLB. Le dépôt contient aussi une vraie reconstruction vidéo 360° à huit angles,
le moteur 2.5D Face/Dos et un catalogue de 259 assets 3D.

## Reconstruction V7 « profondeur neuronale multivue »

La V7 conserve les contrôles V6.1 et ajoute une profondeur réellement issue des
pixels avant la reconstruction :

1. détourage local de chaque vue avec IS-Net Anime FP32 ;
2. conservation de la confiance alpha du réseau au bord du sujet ;
3. correction automatique et manuelle de l'orientation des profils ;
4. mesure de la surface et de la largeur utile de chaque profil ;
5. remplacement d'un profil effondré par la vue opposée en miroir ;
6. normalisation indépendante des axes largeur, hauteur et profondeur ;
7. analyse simultanée des quatre images par Depth Anything 3 Small ;
8. cartes de profondeur cohérentes grâce à l'attention entre les vues ;
9. distance signée euclidienne pour chaque silhouette ;
10. fusion robuste face/dos et droite/gauche ;
11. sculpture DA3 limitée à l'intérieur de la coque vérifiée ;
12. garde-fou automatique si les cartes neuronales écrasent trop le volume ;
13. récupération adaptative d'un détail seulement s'il est confirmé sur deux
   axes différents ;
14. arrondi local des sections du torse, des membres et des accessoires afin de
   supprimer les coins artificiels de l'intersection orthographique ;
15. mode de profondeur séparé pour les formes larges comme un kart ou un siège ;
16. champ de densité sous-pixel transmis directement au mailleur ;
17. surface lisse, normales recalculées et atlas multivue jusqu'à 2K ;
18. export du maillage complet sans simplification destructive.

Le mode haute précision utilise une grille allant jusqu'à 128 × 256 × 304 sur
les appareils disposant de suffisamment de mémoire. Un profil compatible réduit
automatiquement la grille et l'atlas pour éviter une saturation mémoire.

## Ce qui améliore réellement la qualité

- les contours ne sont plus coupés en « vrai/faux » avant la création du
  maillage ;
- ajouter des triangles sert désormais à représenter une surface fractionnaire,
  et non à lisser une forme déjà appauvrie ;
- les bras et les jambes séparés reçoivent une profondeur locale plus faible que
  le torse ;
- les espaces visibles, notamment entre les jambes, restent ouverts ;
- un accessoire caché dans une vue peut être conservé s'il est visible depuis
  deux directions perpendiculaires ;
- le score de conservation des silhouettes est calculé après reconstruction et
  affiché avec le résultat ;
- un profil presque vide ne peut plus aplatir l'ensemble du sujet ;
- la géométrie et la texture utilisent exactement le même profil de secours ;
- un kart ou un objet large n'est plus aminci comme un membre humain.
- la profondeur du visage, du vêtement, du siège et des pièces mécaniques peut
  désormais modifier la surface au lieu de rester uniquement dans la texture ;
- une vidéo de rotation utilise huit angles pour créer une vraie surface 3D et
  une texture cylindrique, au lieu d'être réduite à quatre faces 2.5D.

## Prise de vues recommandée

Pour exploiter la précision du moteur :

- photographier exactement le même personnage et la même pose ;
- garder le corps entier visible, sans couper les pieds ni les accessoires ;
- utiliser une lumière uniforme et un fond contrasté ;
- conserver la même hauteur de caméra et une distance proche ;
- fournir les vues dans l'ordre Face, Droite, Dos, Gauche ;
- corriger la rotation ou le miroir des profils dans l'écran prévu à cet effet.

## Export GLB

Le mode 3D crée un fichier autonome :

```text
personnage_3d_v7_da3_multivue.glb
```

L'export conserve le nombre complet de triangles, les normales, les UV et la
texture PNG. Le matériau externe utilise `KHR_materials_unlit`, masque les faces
arrière et désactive les mipmaps afin d'éviter le mélange entre les quatre zones
de l'atlas.

## Catalogue

- 259 assets classés ;
- 247 modèles procéduraux générés hors ligne sous licence CC0 ;
- 12 modèles officiels Khronos sous licences permissives ;
- 112 assets animés ;
- ouverture et export via le sélecteur de documents Android ;
- limite de sécurité de 8 Mo par asset téléchargé.

Le catalogue utilise Internet pour récupérer les modèles distants. La
reconstruction des photos reste exécutée sur le téléphone et n'envoie pas les
images à un serveur.

## Configuration Android

- Java uniquement ;
- `minSdkVersion 21` ;
- `compileSdkVersion 34` ;
- `targetSdkVersion 34` ;
- Java 17 ;
- ABI `arm64-v8a` ;
- ONNX Runtime Android 1.22.0 ;
- IS-Net Anime FP32 pour le détourage ;
- DA3-SMALL quatre vues 224 px pour la profondeur ;
- version `7.0.0` (`versionCode 38`).

## Compilation

```bash
chmod +x gradlew
./gradlew --no-daemon clean lintDebug assembleDebug
```

APK produit :

```text
app/build/outputs/apk/debug/app-debug.apk
```

Le workflow `.github/workflows/android.yml` :

- télécharge et vérifie IS-Net Anime FP32 par SHA-256 ;
- exporte le checkpoint DA3-SMALL épinglé vers ONNX quatre vues et compare ses
  sorties à PyTorch ;
- exécute les tests Java du catalogue, des orientations, de la géométrie
  historique, de l'enveloppe continue, de la fiabilité des profils et de la
  fusion de profondeur ;
- lance `lintDebug` et `assembleDebug` ;
- vérifie la signature et les deux modèles ONNX inclus ;
- publie l'artefact `Modeliseur-V7-DA3-Multivue-Video360-debug`.

## Limite physique

Quatre images ne contiennent aucune information sur une zone cachée dans les
quatre vues. La V7 améliore l'enveloppe, les profils, les contours et la surface,
mais elle ne peut pas inventer avec certitude l'intérieur d'un vêtement, un
dessous invisible ou une micro-géométrie absente des photos. Une reconstruction
photogrammétrique complète nécessiterait davantage d'angles et des
correspondances de points fiables.
