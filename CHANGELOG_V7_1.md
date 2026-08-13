# V7.1 — correction cheval, texture complète et DA3 réellement appliqué

## Diagnostic issu de la vidéo de test

Le résultat affichait deux informations décisives :

- `2 profils redressés automatiquement à 90°` : les profils naturellement
  larges du cheval étaient confondus avec des photos prises de travers ;
- `coque continue de secours` : le relief DA3 n'avait pas participé au maillage.

Les zones sombres provenaient aussi des cellules transparentes de l'atlas : la
dilatation de couleur s'arrêtait après dix pixels et laissait apparaître le fond
gris-noir lorsque les UV sortaient de la silhouette.

## Corrections

- une rotation à 90° requiert maintenant un gain mesurable de cohérence avec la
  structure verticale face/dos ;
- chevaux, quadrupèdes, karts et autres profils larges restent dans l'orientation
  fournie par l'utilisateur quand la rotation n'améliore pas le score ;
- chaque cellule de texture est remplie intégralement par propagation de la
  couleur opaque la plus proche, sans trou noir ;
- une exécution NNAPI en erreur, non finie ou plate est automatiquement rejouée
  sur le CPU multicœur ;
- la fusion des quatre surfaces DA3 est plus influente et accepte les reliefs
  subtils, tout en conservant le garde-fou anti-écrasement ;
- la ligne de résultat explique désormais la raison précise d'un éventuel repli
  sur la coque ;
- nouveaux tests Java purs pour le cheval, la vraie photo tournée, les profils
  ambigus et le remplissage opaque total de la texture.

## Version

- `versionName` : `7.1.0`
- `versionCode` : `39`
