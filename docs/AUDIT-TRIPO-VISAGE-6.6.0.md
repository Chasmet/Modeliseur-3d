# Audit TripoSR une image et assistant de détail — 6.6.0

## Fichier examiné

GLB fourni par l’utilisateur, produit par 6.5.0, 10 466 516 octets. Aucun exemplaire du fichier ou de sa texture personnelle n’est ajouté au dépôt.

| Mesure | Résultat |
|---|---:|
| Sommets exportés (avec coutures UV) | 123 101 |
| Positions distinctes après soudure exacte | 105 658 |
| Triangles | 211 312 |
| Arêtes ouvertes après soudure | 0 |
| Arêtes non manifold après soudure | 0 |
| Triangles dégénérés | 0 |
| Champ neuronal enregistré | 73 × 92 × 192 |
| Maillage effectif enregistré | 92 × 192 × 73 |
| Texture | 3 072 × 2 048 |

Le maillage est dense et fermé. Les artefacts du visage ne s’expliquent donc pas par un manque global de triangles ou par des trous. Le gros plan de l’atlas montre des étirements horizontaux et des ruptures autour des yeux et cheveux. Le modèle traite un personnage entier ; le visage n’occupe qu’une petite part des 512 × 512 pixels de l’encodeur.

## Fonctionnement et limites constatées

Le troisième atelier et le service MCP utilisent les mêmes classes de préparation : alpha utile, IS-Net ou fond uni, puis recadrage du sujet. L’import précédent plafonnait la source à 1 024 pixels. TripoSR encode le sujet entier en triplanes INT4 ; les champs denses et les couleurs proviennent du vrai décodeur. La grille est limitée à 256 sur sa plus grande dimension. Le plafond de triangles dépend du tas Java Android, distinct de la RAM physique du téléphone.

La texture à six projections utilise une carte de profondeur rasterisée. Son recalage horizontal suivait la silhouette à chaque ligne : un contour de cheveux irrégulier pouvait changer l’échelle d’un œil à l’autre. Un atlas corps entier de 1 024 pixels de haut ne donne qu’une petite centaine de pixels à un petit visage. Depth Anything V2 était déjà embarqué pour les silhouettes ; il ne participait pas au mode TripoSR à une image. L’exécution TripoSR utilisait systématiquement deux threads CPU.

L’image originale n’est pas contenue dans ce GLB : son atlas a déjà été projeté et déformé. Une comparaison fidèle avant/après sur cette photo exige de la réimporter dans l’application. Les tests de cette version vérifient les mécanismes et invariants ; ils ne mesurent pas la ressemblance finale de ce personnage sur son téléphone.

## Correction implantée

1. Une sélection tactile sur le détourage donne un rectangle explicite. Pas de détecteur humain appliqué aveuglément à un personnage animé ou à un objet. La validation active un préréglage Précis et conserve la sélection tant que le détourage reste identique.
2. Le moteur embarqué Depth Anything V2 reçoit le gros plan, pas le corps entier. Un cache 256 × 256 dépend de l’image et du rectangle. Les sessions TripoSR et profondeur sont fermées successivement.
3. Le maillage reçoit au maximum deux passes de subdivision locale conforme : les triangles voisins partagent leurs milieux d’arêtes. Le budget final reste respecté. Seules les surfaces frontales visibles sont déplacées.
4. Le relief utilise le contraste de profondeur relatif après retrait d’une composante large, un raccord progressif au bord, une limite de 4 % de la hauteur du détail multipliée par la force et un rejet des inversions. Il préserve la forme globale et les coordonnées du dos.
5. Dans les lignes du détail, une largeur médiane stabilise la projection. Géométrie, texture et UV partagent le même recalage. Un septième îlot UV réserve une texture au détail jusqu’à 1 024 × 1 024. L’atlas devient 4 × 2 cellules quand l’assistant est actif ; le mode standard garde ses six îlots.
6. L’import une image garde jusqu’à 2 048 pixels sur un tas Java d’au moins 384 Mio. Le mode silhouettes et les quatre vues gardent leur plafond précédent. Les anciens fichiers ne retrouvent pas les détails perdus : réimporter l’original.
7. La politique CPU sélectionne jusqu’à six threads, bornés par les cœurs, la RAM totale, la RAM libre et le tas Java. Sous pression elle reprend le budget prudent. Les logs rapportent les valeurs effectives.
8. Les options MCP direct sont validées avant de créer une commande : rectangle fini dans [0,1], ordre et taille minimale, force [0,1], une seule image TripoSR. Aucune modification de transport, accès réseau ou authentification.

Aucun modèle supplémentaire téléchargé à l’exécution, aucun nouvel abonnement et aucune clé API. TripoSR n’est ni réentraîné ni remplacé. L’assistant vise la lisibilité et le relief visible ; il ne reconstruit pas des parties cachées observées par aucune photo.

## Vérification prévue avant publication

Compilation Android réelle, tests unitaires et lint ; tests Android avec rendu natif de sélection, cache de profondeur et véritable inférence ONNX, atlas distinct, projection stable, subdivision sans trou, rejet des inversions, absence de modification du dos et budget CPU sous pression. Régression de l’atelier quatre images, du service MCP, de la navigation et de la mise à jour publique. Publication CI avec le certificat existant, puis téléchargement et vérification du package, version, SHA-256 et signature.

Sources primaires du fonctionnement des modèles : [TripoSR](https://github.com/VAST-AI-Research/TripoSR), [Depth Anything V2](https://github.com/DepthAnything/Depth-Anything-V2). Les résultats d’audit et l’algorithme d’assistance ci-dessus proviennent du code de cette application.
