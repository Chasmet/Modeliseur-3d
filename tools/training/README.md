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
dossier sont sous la licence MIT du dépôt. Aucun GLB ou cliché personnel n’est
utilisé. Les checkpoints dérivés conservent la licence MIT et la notice TripoSR
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

Références primaires : [TripoSR officiel](https://github.com/VAST-AI-Research/TripoSR),
[rapport technique](https://arxiv.org/abs/2403.02151),
[poids officiels et licence](https://huggingface.co/stabilityai/TripoSR).
