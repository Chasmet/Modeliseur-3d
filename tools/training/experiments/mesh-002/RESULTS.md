# Deux entraînements CPU avec cinq références GLB privées

**Les candidats sont refusés pour l’APK.** Aucun poids Android publié n’a été
modifié. Les références, leurs rendus et les checkpoints dérivés sont conservés
privément ; ce dossier ne publie que le protocole et les mesures agrégées.

## Travail réellement exécuté

- Cinq fichiers statiques lus sans modification : trois objets d’apprentissage,
  un objet de validation et un personnage de test, avec séparation par objet.
- Vingt rendus orthographiques de 256 × 256 pixels, couleurs et textures,
  encodés réellement par TripoSR INT4 sur CPU. Pas de GPU disponible.
- Grille 40³ par référence, union des composantes fermées. Les régions des
  composantes ouvertes sont masquées, sans remplissage des trous.
- Six objets synthétiques supplémentaires pour le rejeu des acquis ; trois
  objets synthétiques de validation et trois de test.
- Deux passes de 800 mises à jour du vrai décodeur de dix couches, Adam,
  taux 2e−5, gradient borné et régularisation. Encodeur figé. Les 195 paramètres
  des lignes RGB finales restent inchangés ; les couches cachées sont ajustées.

La première passe poursuit `cpu-001` avec une vue par minibatch. Elle modifie
41 085 paramètres flottants, mais aucun nouveau checkpoint ne passe la garde
de précision de validation. Elle est archivée comme expérience refusée.

La seconde repart du décodeur publié et différencie la fusion des quatre champs
correspondant au même point 3D. La sélection v1 utilise la moyenne des IoU de
validation avec une garde de précision. Elle retient provisoirement l’étape 200
sur 800, avec 41 085 paramètres modifiés. Une comparaison géométrique détaillée
refuse ensuite ce candidat ; il n’est pas activé dans l’application.

## Résultats du candidat provisoire de la seconde passe

Les chiffres ci-dessous comparent le décodeur publié au candidat de l’étape 200.
Ils concernent une occupation échantillonnée et partiellement masquée avec
alignement sur les bornes connues des références, **pas des photos réelles**.

| Mesure | Décodeur publié | Candidat provisoire |
|---|---:|---:|
| IoU du personnage de test | 33,76 % | 32,49 % |
| Précision du personnage de test | 47,17 % | 50,81 % |
| Rappel du personnage de test | 54,29 % | 47,39 % |
| IoU de l’objet de validation à pièces fines | 3,24 % | 0,93 % |
| IoU moyenne des trois tests synthétiques | 43,89 % | 44,40 % |

Le candidat ajoute moins de faux volume au personnage, mais perd davantage de
géométrie. Les parties fines de l’objet de validation sont mal apprises. La
petite progression synthétique ne compense pas ces régressions.

L’audit indépendant confirme les empreintes des deux checkpoints de la seconde
passe, les poids réellement modifiés, les lignes RGB finales figées, la parité
NumPy/ONNX et les cinq fichiers sources inchangés. L’écart maximum ONNX/NumPy
mesuré à l’export du candidat est 1,20e−6. Les assets publiés restent inchangés.

## Correction du protocole après ces résultats

La version v2 ajoute des gardes par objet : perte d’IoU au-delà de 0,01,
de précision au-delà de 0,02 ou de rappel au-delà de 0,02. Un meilleur score
moyen ne peut plus masquer la suppression de pièces d’un autre objet. L’audit
v2 appliqué à l’historique refuse les nouveaux checkpoints des deux passes.
Ce contrôle est distinct de la sélection v1 réellement exécutée ; aucun
rapport d’entraînement n’est réécrit pour simuler une meilleure expérience.

Les tests comprennent les gradients simples et ceux de la fusion quatre vues
par différences finies, l’export ONNX, les UV, les transformations de nœuds,
l’union de solides qui se chevauchent, le masquage des pièces ouvertes, les
gardes de séparation et celles contre la perte de géométrie.

## Limites

Cinq modèles ne constituent pas un corpus représentatif. Les rendus diffus
omettent les normal maps et le PBR complet ; des références générées ne sont
pas des scans vérifiés. La grille peut manquer de petits éléments. Ni les
surfaces détaillées, ni les textures, ni la chaîne Android complète, ni les
performances sur le téléphone ne sont validées. Il faut davantage de sujets
indépendants avec des références fiables et de vraies photos correspondantes
avant de conclure à une meilleure reconstruction.

`production_approved: false`. La version publique conserve ses poids d’origine.
