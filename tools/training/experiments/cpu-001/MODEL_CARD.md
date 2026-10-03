# Candidat CPU-001 — expérimental, non activé dans Android

Décodeur TripoSR réellement ajusté sur CPU le 2 octobre 2026. L’encodeur INT4
reste figé. 600 étapes Adam ont modifié 41 086 valeurs flottantes ; 41 089
paramètres étaient ajustables. L’export ONNX autonome conserve l’interface
`triplane_features` → `density_rgb` et la licence MIT de TripoSR.

Empreinte SHA-256 du candidat :
`edcb8d1634dfeec03b26f91c10c80d85fb523c44b1abd372398670506268f977`.

## Mesures sur les références synthétiques réservées

L’IoU mesure le recouvrement des cellules de volume, pas la fidélité visuelle
d’un personnage. L’alignement est fixé sur le modèle initial. Les scores ci-dessous
ne représentent pas le traitement complet de l’application Android.

| Objet de test | IoU avant | IoU après | Précision avant → après | Rappel avant → après |
|---|---:|---:|---:|---:|
| Bouteille | 68,3 % | 82,2 % | 100,0 → 98,6 % | 68,3 → 83,2 % |
| Chaise | 34,5 % | 55,9 % | 43,3 → 68,0 % | 63,0 → 75,9 % |
| Figurine | 28,9 % | 41,5 % | 70,4 → 59,6 % | 32,9 → 57,7 % |
| Moyenne IoU | 43,9 % | 59,9 % | — | — |

Les six objets d’apprentissage, les trois de validation et les trois de test
sont distincts. Le checkpoint a été choisi uniquement sur la validation
(48,9 → 67,2 % d’IoU). Aucune référence n’a été exclue. Le test réservé constate
un gain d’IoU sur les trois objets ; il constate aussi une baisse de précision
sur la figurine et la bouteille. « Benchmark synthétique réussi » ne signifie
donc pas « aucune régression » ou « modèle prêt à publier ».

## Décision

**Pas d’activation dans l’APK 6.3.1.** La figurine reste insuffisamment fidèle
et son volume comporte davantage de faux positifs. Les références sont des
assemblages analytiques simples, sans vêtements, visages réalistes ni scans.
Le détourage, les silhouettes et les textures de la chaîne Android complète
n’ont pas été évalués avec ce candidat. Ni la vitesse ni la qualité sur le
téléphone de l’utilisateur ne sont mesurées.

L’expérience prouve qu’un ajustement réel du petit décodeur est possible ici.
Pour améliorer un personnage photographié, la prochaine étape est un corpus
représentatif de scans/modèles validés avec rendus ou photos correspondants,
puis une évaluation par sujets réservés et une validation Android. Un
réentraînement du gros encodeur ou d’un vrai modèle multivue nécessite une
autre expérience et des ressources de calcul adaptées ; il n’a pas été réalisé.

## Vérifications et reproductibilité

- Cinq tests : empreinte et poids du candidat archivé, gradients par différences finies, parité ONNX, conventions des
  quatre vues, vérités d’occupation et rendus synthétiques.
- Écart maximal NumPy/ONNX sur l’échantillon contrôlé : 1,19e−6.
- Les 195 paramètres RGB finaux sont identiques ; les couches cachées peuvent
  malgré tout modifier la couleur neuronale, inutilisée par l’application.
- Poids sources fixés par SHA-256 ; aucun asset Android modifié.
- Voir `dataset.json`, `report.json`, `audit.json` et les scripts du dossier
  parent pour les graines, l’historique et les détails. Les sorties lourdes et
  les caches sont sous `build/training/`, exclus de Git.

Les licences et notices du modèle sont jointes. Aucun fichier personnel n’est
utilisé ou distribué dans cette expérience.
