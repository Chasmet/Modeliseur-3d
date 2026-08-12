# V6.1 — profils fiabilisés et formes larges

## Régression reproduite

Le GLB V5.9.7 de test contient un profil qui ne couvre qu'environ 9 % de sa
cellule de texture, contre environ 45 % pour le profil opposé. Les anciennes
versions acceptaient pourtant cette paire. L'intersection multivue écrasait
alors la profondeur et la texture du profil incomplet formait des bandes sur
les côtés du modèle.

## Corrections

- mesure séparée de la surface, de la largeur effective et de la similarité de
  chaque paire de profils ;
- détection conservatrice d'un profil réellement effondré ;
- remplacement automatique du seul profil invalide par la vue opposée en
  miroir ;
- utilisation de la même vue réparée pour la géométrie et l'atlas afin
  d'éviter les traînées de texture ;
- affichage du côté réparé avant la génération et dans le résumé final ;
- détection des sujets à partie basse large, notamment kart, voiture ou siège ;
- profondeur renforcée pour ces formes afin qu'elles ne soient plus traitées
  comme plusieurs bras ou jambes minces ;
- tests JVM reproduisant le rapport 9 % / 45 % du GLB fourni et un conducteur
  placé dans un kart large.

## Version

- `versionName` : `6.1.0`
- `versionCode` : `37`
