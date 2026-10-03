# Adaptation CPU avec huit références publiques — 3 octobre 2026

## Décision

**Aucun nouveau poids approuvé. Aucune modification de l’APK.** Deux séances
réelles de 800 étapes ont ajusté le petit décodeur TripoSR partagé, en repartant
des poids de la version publique 6.3.1. L’encodeur INT4 reste figé. Chaque dernier
checkpoint a 41 086 paramètres différents ; aucun des checkpoints évalués tous
les 100 pas ne satisfait les gardes de validation. Les fichiers « candidate »
sont donc le repli vers les poids initiaux, pas des modèles améliorés.

## Corpus et ressources trouvées

Huit GLB Objaverse sous CC-BY 4.0, avec auteurs, URL, empreintes et attribution
dans `resources.json` et `ATTRIBUTION.md`. Quatre rendus par objet : 32 nouvelles
vues. Les 48 vues des douze références synthétiques précédentes ont aussi été
recalculées puis mises en cache ; elles servent au rejeu et aux contrôles de
régression. Aucune photographie personnelle ni ancien GLB approximatif de
l’application n’a servi de vérité géométrique pour cette séance.

| Rôle | Références |
|---|---|
| Apprentissage | Layer Michael ; Swordsman 1 ; Roberto bûcheron ; Emergent Media Robot |
| Validation | wooden man ; Mini Robot |
| Test de cette adaptation | Training mannequin ; Lamb statuette |

Le découpage par objet et par auteur est fixé avant inférence. Les sources
restent inchangées ; les cibles sont l’union des composantes fermées. Les régions
de composantes ouvertes sont exclues. Il s’agit d’un petit corpus pilote de
modèles et objets, pas d’un corpus représentatif de photographies de personnes.
Roberto comprend un décor ; Training mannequin est un buste couché. Les rendus
ont été inspectés. Un fichier au nom évoquant un personnage mais représentant
principalement une boule décorative a été exclu avant constitution du corpus.

Autres ressources primaires vérifiées, mais non utilisées dans ces deux séances :

- [Objaverse++](https://github.com/TCXX/ObjaversePlusPlus) : annotations de qualité,
  transparence, scènes et figures pour préparer un corpus plus ciblé.
- [YCB](https://ycb-benchmarks.s3.amazonaws.com/index.html) : scans texturés et
  photographies RGB/RGB-D, CC-BY 4.0, principalement objets du quotidien.
- [Google Scanned Objects](https://research.google/pubs/google-scanned-objects-a-high-quality-dataset-of-3d-scanned-household-items/)
  : scans d’objets, autre ressource pour varier les références.

## Protocole et mesures

Fusion différentiable quatre vues, BCE d’occupation, Adam à 2e-5, ancrage aux
poids initiaux, gradients bornés et 50 % de rejeu synthétique. Grilles de 28³
points, alignement fixé sur les champs initiaux et bornes connues des références.
Les gardes par objet portent sur l’IoU (tolérance 0,01), la précision et le rappel
(tolérance 0,02). Une amélioration moyenne ne compense pas une perte de forme.

La première séance échantillonne uniformément. La seconde réserve la moitié du
lot aux points occupés ; les poids d’importance conservent l’objectif BCE de
la population. Elle reprend les mêmes caches et rôles sans réencoder les images.
Le personnage articulé occupe seulement 1,64 % des points. L’échantillonnage
stratifié réduit une partie de la régression, mais ne règle pas le problème.

| Validation à 800 étapes | Poids publiés | Séance uniforme | Séance stratifiée |
|---|---:|---:|---:|
| wooden man, IoU | 13,56 % | 6,75 % | 8,97 % |
| wooden man, rappel | 23,68 % | 7,80 % | 10,86 % |
| Mini Robot, IoU | 29,21 % | 32,37 % | 32,14 % |
| Mini Robot, rappel | 94,30 % | 91,71 % | 92,07 % |

Ces colonnes décrivent les **derniers poids optimisés et refusés**, pas un
checkpoint retenu. Aucun checkpoint des deux séances ne passe la validation.
Les scores de test des fichiers de repli restent donc identiques à l’initial :
70,92 % sur Training mannequin et 76,78 % sur Lamb. Ce n’est pas un gain.
Les derniers poids refusés n’ont pas servi à choisir sur les objets de test.

## Vérifications

- 23 tests locaux réussis : rétropropagation simple et quatre vues, gradients
  pondérés par différences finies, correction analytique de l’échantillonnage,
  export ONNX, droits individuels, séparation des auteurs et verrouillage du corpus.
- Audits des quatre fichiers de checkpoints : empreintes, nombre de poids
  modifiés, valeurs finies, lignes RGB finales conservées et parité ONNX/NumPy.
- Erreur maximale ONNX/NumPy sur les checkpoints de repli : 2,39e-6.
- Huit originaux et quatre assets Android inchangés. Aucun réseau neuronal
  d’encodage n’est entraîné dans ces expériences. Aucun build Android nécessaire
  pour cette modification exclusive des outils de recherche.
- Durées des programmes : environ 361 s et 37 s ; préparation initiale du corpus
  et calcul séparé des caches synthétiques exclus. Ce ne sont pas des mesures
  de temps sur le téléphone.

## Interprétation et suite

Les données supplémentaires et la réduction de variance ne suffisent pas à
améliorer la reconstruction de ce personnage avec ce seul ajustement du
décodeur. L’hypothèse de travail est qu’il faut aussi agir sur l’encodage et
l’alignement des quatre champs ; ces séances ne démontrent pas à elles seules
la cause exacte de l’échec. Il faut une supervision de surface et de silhouettes
plus précise, ainsi qu’un corpus de figures isolées et de poses variées.

Les bornes réelles sont connues dans ce benchmark, les grilles sont grossières,
et les rendus orthographiques ne remplacent pas les photos réelles. L’APK et
ses silhouettes, textures et performances ne sont pas évaluées ici. L’éventuelle
présence de ces objets dans le préentraînement original de TripoSR est inconnue.
Les anciens tests synthétiques déjà consultés restent des contrôles de
régression ; ils ne constituent pas un nouveau test indépendant de généralisation.

**Ne pas intégrer un fichier « last » à l’APK.** Les poids et caches sont conservés
pour la recherche. Le rapport de la seconde séance se trouve dans `../public-002/`.

## Reproduire la sélection

```sh
OPENBLAS_NUM_THREADS=2 python tools/training/fetch_public_references.py \
  --output build/training/resources --count 8 \
  --expected-manifest tools/training/experiments/public-001/resources.json \
  --uids 06923953e80342e4bb1f378110a5deaf dbd5967529474547b4777c7a1f6312b0 \
  b253b8b5f70a439284655047d8a0c23b f265e07cc1394f78a5fe59148f84a15c \
  d11d8167023e49beba284283446db700 5aaf34c3eb5e44c5a40a03c7569fa8c5 \
  278532499c9048b688ba6840e19e76f9 97989d443ac048acb6bb42a2f82c6324
```

Le verrou refuse tout changement d’objet, contenu, métadonnées, licence, auteur
ou rôle. Voir le README des outils pour les deux commandes d’optimisation.
