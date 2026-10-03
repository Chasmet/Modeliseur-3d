# V7.3 — Reconstruction DA3 multi-experts

## Objectif

Faire sortir le mode quatre vues du simple réglage global par catégorie. La V7.3 traite désormais différemment les zones d’un même sujet pour éviter qu’un conducteur, des pattes, un tronc ou une façade soient sculptés comme une seule masse.

## Nouveau moteur multi-experts

- profil de forme construit à partir des silhouettes face/dos et droite/gauche ;
- mesure locale de la largeur et de la profondeur sur chaque hauteur du sujet ;
- politiques DA3 différentes selon la zone réellement observée ;
- personnage : tête, torse et membres ne reçoivent plus la même sculpture ;
- quadrupède : le torse conserve son volume tandis que les pattes étroites sont davantage séparées ;
- personnage + véhicule : le conducteur est fortement sculpté alors que le châssis conserve une base large et rigide ;
- habitation / objet rigide : faible sculpture, noyau volumique protégé et géométrie plus plane ;
- arbre / plante : canopée, branches et tronc utilisent des contraintes distinctes ;
- lissage DA3 préservant les ruptures de profondeur au lieu d’effacer les petits détails ;
- protection du noyau de chaque zone et garde anti-effondrement propres à chaque famille ;
- aucune création de voxel hors de la coque déjà validée par les quatre silhouettes.

## Validation ajoutée

Les tests JVM contrôlent maintenant explicitement que :

- le conducteur est sculpté plus de deux fois plus fortement que le châssis ;
- les pattes d’un quadrupède sont sculptées plus de deux fois plus fortement que son torse ;
- un bâtiment reste nettement plus rigide qu’un membre humain ;
- les cartes DA3 plates sont refusées ;
- la profondeur ne crée jamais de géométrie hors silhouette ;
- le garde anti-effondrement reste actif ;
- les détails proches sont conservés.

Cette version reste entièrement locale : DA3 et IS-Net sont exécutés dans l’application, sans API payante.
