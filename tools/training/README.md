# Entraînement expérimental du décodeur TripoSR

Ce dossier entraîne **les poids du vrai décodeur NeRF TripoSR** utilisé dans
l’application. L’encodeur d’image et le transformeur quantifiés sont figés.
Ce n’est ni un nouvel entraînement complet de TripoSR, ni un modèle multivue
nativement entraîné de bout en bout. Aucun réglage Android « Entraîner » factice
n’est ajouté. L’APK publique reste utilisable avec ses poids vérifiés.

## Exécuter

Python 3.11 ou 3.12, CPU, poids locaux préparés par `fetch_triposr.py` :

```sh
python -m venv .training-venv
. .training-venv/bin/activate
python -m pip install -r tools/training/requirements.txt
OPENBLAS_NUM_THREADS=2 python tools/training/test_training.py
OPENBLAS_NUM_THREADS=2 python tools/training/train_decoder.py \
  --steps 600 --encoder-threads 6 --output build/training/cpu-001
```

L’exécution refuse des poids sources différents des empreintes fixées et refuse
de produire les résultats dans les assets Android. Aucun téléchargement n’a lieu
dans le script d’entraînement. Les sources et les références procédurales de ce
dossier sont sous la licence MIT du dépôt. Cette expérience procédurale n’utilise
aucun GLB ou cliché personnel. Les checkpoints dérivés conservent la licence MIT et la notice TripoSR
de `app/src/main/assets/models/`.

## Données et optimisation

- Douze objets 3D analytiques : bouteilles, chaises et figurines synthétiques.
- Quatre rendus par objet, dans l’ordre face, dos, droite, gauche.
- Six objets d’entraînement, trois de validation et trois de test. Le découpage
  se fait par objet, jamais par pixel ou par photo du même objet.
- Le véritable encodeur INT4 produit les triplans. Leur échantillonnage reproduit
  l’alignement et le padding de `TripoSREngine.samplePlane`.
- Le MLP de dix couches a 41 284 paramètres. Ses 41 089 paramètres de géométrie
  et couches cachées sont ajustables ; les 195 paramètres des trois lignes RGB
  finales sont figés. Les couches cachées peuvent malgré tout modifier la couleur
  neuronale, inutilisée par l’application qui projette ses propres photos.
- La supervision est l’occupation 3D connue, avec perte binaire, Adam, gradient
  borné, taux 2e−5 et petite régularisation vers les poids initiaux. C’est une
  méthode expérimentale différente de l’entraînement officiel par rendu.
- Le meilleur checkpoint est choisi sur la validation. Le test réservé ne décide
  ni des gradients ni du checkpoint. La fusion d’occupation applique les deux
  meilleurs supports (40 % chacun) et la moyenne des vues (20 %).

Le script produit les références, les triplans en cache, un décodeur ONNX
autonome et un rapport JSON : scores avant/après pour chaque objet, historique
d’optimisation, empreinte du candidat et écart NumPy/ONNX.

## Limites et passage à la production

Le benchmark utilise des formes synthétiques simples, un alignement fixé sur le
décodeur initial, sans le détourage, le recalage complet, le clipping des
silhouettes ou les textures Android. Trois objets réservés ne suffisent pas à
prouver la généralisation. Même un gain d’IoU **ne prouve pas** une meilleure
reproduction d’un personnage photographié. Le rapport maintient toujours
`production_approved: false` pour ce prototype.

Avant toute intégration : constituer un corpus représentatif avec références
3D fiables (scans ou modèles validés et licences compatibles), poses, vêtements,
objets et éclairages variés ; réserver des sujets entiers à l’évaluation ; mesurer
les surfaces, les membres et les ouvertures sur la chaîne Android complète ;
vérifier la précision, le temps et la mémoire après export sur un téléphone.
Des photos seules ou des GLB approximatifs produits par l’application ne sont
pas des vérités 3D de référence.

## Adaptation avec des références GLB privées

`train_mesh_decoder.py` poursuit l’adaptation du vrai décodeur à partir de modèles
statiques fournis localement, avec 50 % de rejeu des six objets synthétiques
d’entraînement. Installer `requirements-mesh.txt` et lancer les contrôles :

```sh
python -m pip install -r tools/training/requirements-mesh.txt
OPENBLAS_NUM_THREADS=2 python -m unittest discover -s tools/training -p 'test_*training.py'
OPENBLAS_NUM_THREADS=2 python tools/training/train_mesh_decoder.py \
  --train-reference sujet-a /chemin/prive/sujet-a.glb \
  --train-reference sujet-b /chemin/prive/sujet-b.glb \
  --train-reference objet-c /chemin/prive/objet-c.glb \
  --validation-reference objet-d /chemin/prive/objet-d.glb \
  --test-reference sujet-e /chemin/prive/sujet-e.glb \
  --steps 800 --grid 40 --objective four-view \
  --initial app/src/main/assets/models/triposr_decoder.onnx \
  --output build/training/uploads-002
```

- Le lecteur reconnaît le contenu binaire même avec un suffixe `.glb.txt`,
  applique les transformations des nœuds et conserve les UV et couleurs.
- Quatre vues orthographiques sont rendues sur CPU avec Trimesh et Embree,
  sans remplacer ni modifier les fichiers d’origine. Le rendu utilise la
  couleur de base et un éclairage diffus ; il ne reproduit pas le PBR complet.
- L’occupation est l’union des composantes fermées à orientation cohérente.
  Les UV sont conservés pour le rendu et soudés séparément pour la topologie.
  Aucun trou n’est rempli. Les volumes des composantes ouvertes et leurs
  abords sont exclus de la supervision et de l’évaluation.
- Les objets sont séparés entre entraînement, validation et test. Des fichiers
  identiques dans plusieurs rôles sont refusés. La validation choisit le
  checkpoint avec des contrôles par objet d’IoU, de précision et de rappel ;
  un gain moyen ne compense pas la perte de membres ou d’autres pièces sur un
  autre objet. Le test ne choisit aucun poids.
- L’alignement du champ est fixé sur le décodeur publié et les bornes de la
  référence. Le score reste celui d’une grille d’occupation, avec des zones
  masquées et des détails fins potentiellement absents. Il ne mesure pas la
  fidélité sur des photographies réelles ni la chaîne Android complète.
- Les données, rendus, caches et checkpoints privés sont écrits exclusivement
  dans `build/`, ignoré par Git. La diffusion publique des références et des
  poids dérivés exige des droits distincts du simple droit de les lire localement.
  Ne pas les copier dans `experiments/` ou les assets Android par défaut.

Le rapport compare le décodeur publié, le checkpoint initial et celui retenu.
Il conserve aussi le dernier checkpoint optimisé si aucun nouveau candidat ne
passe la validation. `production_approved` reste toujours faux.

L’objectif `four-view` différencie la fusion des deux meilleurs supports et de
la moyenne des quatre champs. Les features correspondent au même point 3D dans
les quatre triplans. Les gradients sont vérifiés par différences finies.
L’objectif `per-view` conserve le protocole initial, avec une vue par minibatch.
Les deux entraînent uniquement le petit décodeur partagé, jamais l’encodeur.

Références primaires : [TripoSR officiel](https://github.com/VAST-AI-Research/TripoSR),
[rapport technique](https://arxiv.org/abs/2403.02151),
[poids officiels et licence](https://huggingface.co/stabilityai/TripoSR).
