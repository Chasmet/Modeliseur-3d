# V7.2 — reconstruction multi-formes

La V7.2 répond aux essais sur un cheval puis sur un personnage assis sur un
véhicule. Le cheval était reconnaissable, mais encore trop court selon certains
angles. Le sujet composé mélangeait les membres, le siège et le châssis.

## Changements

- ajout des familles `Auto`, `Personnage`, `Animal / quadrupède`, `Personnage +
  véhicule / objet composé`, `Habitation / objet rigide` et `Arbre / fleur /
  plante` dans l'écran quatre vues ;
- classifieur local de secours fondé sur la topologie des quatre silhouettes ;
- priorité absolue au choix manuel pour les sujets ambigus ;
- profondeur disponible accrue pour les quadrupèdes et véhicules allongés ;
- dans le mode composé, haut articulé traité comme un personnage et base basse
  traitée comme un volume large ;
- fusion DA3 protégée contre les contradictions entre conducteur, guidon, siège
  et châssis ;
- projection UV refusée lorsqu'une vue ne contient pas le point du sujet ;
- texture de bord dilatée localement seulement, avec couleur moyenne opaque dans
  les zones lointaines pour supprimer les détails répétés et les zones noires ;
- nouveaux tests JVM pour les cinq familles et pour le cas conducteur + kart.

## Version Android

- `versionName` : `7.2.0`
- `versionCode` : `40`
