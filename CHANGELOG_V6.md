# V6.0 — enveloppe multivue continue

## Cause de la qualité limitée en V5

Les quatre détourages étaient convertis trop tôt en masques binaires. Le moteur
intersectait ensuite des colonnes de voxels rectangulaires. Le lissage et un
grand nombre de triangles amélioraient l'apparence, mais ne pouvaient pas
recréer les contours et volumes déjà perdus.

## Changements

- alpha IS-Net conservé au lieu d'être forcé à 255 ;
- normalisation bilinéaire des quatre silhouettes ;
- distances signées euclidiennes exactes ;
- fusion continue face/dos et droite/gauche ;
- mode adaptatif limité aux détails confirmés sur deux axes ;
- sections superelliptiques pour supprimer l'effet « bloc » ;
- profondeur locale distincte pour torse, bras, jambes et accessoires ;
- conservation des espaces entre composantes ;
- mailleur alimenté directement par le champ fractionnaire ;
- grille maximale 128 × 256 × 304 ;
- atlas multivue jusqu'à 2048 px de haut ;
- score de conservation des silhouettes affiché ;
- export GLB complet renommé pour la V6 ;
- tests JVM dédiés aux contours continus, aux sections arrondies, aux membres
  et à la récupération adaptative.

## Version

- `versionName` : `6.0.0`
- `versionCode` : `36`
