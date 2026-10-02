# Modéliseur 3D V6.0.6 — branche TRELLIS isolée

Trois onglets à l’accueil : **2.5D**, **3D** et **TRELLIS**. Le catalogue de 259 assets reste accessible.
Les moteurs locaux, exports et données existants ne sont pas modifiés. Le troisième onglet ouvre uniquement l’atelier local et le lecteur GLB embarqué. Aucun compte, serveur ou téléchargement à la première utilisation.

L’infrastructure Render/MCP historique est conservée pour un travail ultérieur. Elle n’est utilisée par aucune opération du troisième onglet local. Son adresse reste `https://modeliseur-trellis-mcp.onrender.com`.
Voir [le relais Android/MCP](backend/README.md) pour les quotas et l’appairage privé. Render peut se mettre en veille ; les fichiers distants sont éphémères. Les GLB téléchargés restent sur le téléphone.

## Mise à jour automatique

**Réglages → Mise à jour automatique** affiche la version installée, vérifie les GitHub Releases publiques, télécharge l’APK avec progression et ouvre l’installateur Android.
Aucune clé API ou PAT n’est demandé. Le téléchargement vérifie taille, SHA-256, package, versionCode supérieur et identité du certificat avant l’installation. Android demande l’autorisation d’installer puis une confirmation.
La vérification automatique est périodique (WorkManager, toutes les 12 h sous réserve des contraintes Android) et se fait aussi au démarrage, sans bloquer l’interface.

La publication utilise le GITHUB_TOKEN natif et une identité GitHub Actions OIDC de courte durée. La signature Android existante de **l’APK 6.0.0 fournie le 1 octobre 2026** est conservée dans l’environnement privé Render. Aucune clé privée ne figure dans ce dépôt ni dans l’APK. La publication refuse une identité de signature différente et ne crée jamais une clé de remplacement.

**Attention :** l’APK 5.9.10 du 5 septembre 2026 avait un certificat différent. Sa clé n’est pas dans la sauvegarde V6. Sans cette clé d’origine, une mise à jour directe par-dessus 5.9.10 est impossible. Ne pas désinstaller cette ancienne application pour contourner le conflit : le système de mise à jour refuse cette opération.

## Vérification

CI : `testDebugUnitTest lintDebug assembleDebug`, catalogue/reconstruction historiques, tests d’isolation/révocation MCP, arrêt sur quota GPU et test navigateur du lecteur/animations. Les tests ne remplacent pas un essai d’installation sur le téléphone.
La branche `main` et les services Render préexistants restent séparés de cette branche.

## Atelier hors connexion V6.0.6

Le deuxième onglet affiche explicitement le mode original à quatre images (face, dos, droite, gauche). Ses moteurs et son écran de capture restent inchangés. Les onglets utilisent des couleurs contrastées et mémorisent le dernier choix.

Dans le troisième onglet, **Atelier hors connexion** propose désormais quatre photos : face, dos, profil droit et profil gauche. Chaque photo possède une copie privée, un détourage et une profondeur optionnelle distincts. Les quatre silhouettes sont normalisées à une hauteur commune en conservant leurs proportions, puis intersectées en une enveloppe volumique fermée. Les quatre photographies sont projetées sur les faces correspondantes d'un atlas GLB. Les photos doivent montrer le même objet entier dans la même pose. Cette enveloppe reste approximative et ne restitue pas tous les creux invisibles : ce n'est pas une inférence TRELLIS complète.

Grilles quatre vues 64/88/112, extraction séquentielle et deux threads pour les IA successives, au plus 120 000 triangles et texture de 1 024 × 1 024. La grille est réduite avec un petit budget mémoire Java ou un maillage trop complexe. La profondeur des profils est réglable entre 65 et 135 %. Aucun réseau ni téléchargement de poids à l'utilisation. Les quatre vues sont requises pour générer dans ce mode, sélectionné par défaut ; import, rotation et cache sont indépendants. Le thème local reste lisible en mode nuit Android.

L'option une image conserve le volume, relief et objet de révolution antérieurs, ainsi que la photo importée et tous les anciens GLB. Les moteurs et écrans des deux premiers onglets restent inchangés.

Les GLB sont enregistrés dans le stockage privé du téléphone, disponibles dans la galerie locale, affichés avec le lecteur embarqué et exportables via le sélecteur de documents Android. Le parcours serveur a été retiré du troisième onglet. Le code distant existant est conservé pour un éventuel travail MCP ultérieur, sans être appelé par cet atelier.

V6.0.6 : versionCode 42, même applicationId et certificat V6.0.0/V6.0.1. Les projets existants restent conservés.


### Fonctions locales 6.0.6

- Vérification visuelle du détourage avant génération ; PNG détouré conservé et réutilisé lorsque seuls la forme, l’épaisseur ou le détail changent. Import/rotation ou changement de méthode/tolérance invalident ce cache.
- Objet rond à 360° : profil de révolution fermé pour bouteilles/vases verticaux et symétriques ; profils lissés, texture issue de l’image, moins de 20 000 triangles. Ce mode ne convient pas aux personnages ou objets asymétriques et ne déduit pas une face cachée réelle.
- Arrêt après l’étape en cours, une seule opération à la fois, durée et taille affichées, grilles limitées et réglages/modèles conservés.
- Tests Android : connexions sortantes interdites pendant l’inférence CPU IS-Net réelle, la génération des trois formes, la sauvegarde, la reprise et l’accès aux ressources privées du lecteur. Validation GLB et rendu couleur dans un navigateur séparé. Pas de mesure sur téléphone physique revendiquée.


### Profondeur IA locale

Depth Anything V2 Small FP32 (Apache-2.0) est maintenant réellement embarqué dans l’APK, en plus d’IS-Net. Les poids de 99,1 Mo sont téléchargés et vérifiés uniquement pendant la compilation, jamais par l’application. Révision ONNX Community figée `64fe43eba7f8a384b02fe3fadaa26cbba35548d8`, SHA-256 `afb6a5c28f3b6bf1618c6e43f02073ef9dfdc70e937502d51603e57b0a1df10c` ; licence et attribution dans `assets/licenses`.

Le troisième onglet estime séparément la profondeur relative de chacune des quatre vues, ou de la face visible en mode une image sur CPU (deux threads), puis forme un relief ou un volume fermé. Ce calcul est optionnel et ses résultats sont sauvegardés dans une carte locale de 128 × 128 valeurs. Changer le détail ou l’épaisseur réutilise cette carte. Les sessions de détourage et de profondeur sont fermées successivement, pour éviter de garder les deux réseaux chargés ensemble. Le mode de révolution déduit son épaisseur de la silhouette et désactive la profondeur IA.

La face cachée reste une approximation : ces deux IA ne constituent pas une génération TRELLIS complète. Le moteur de profondeur historique garde ses paramètres par défaut ; seul l’atelier utilise le nouveau constructeur avec budget CPU explicite. Les performances sur l’appareil physique restent à mesurer.

Le lecteur limite les rafraîchissements à 30 FPS pendant les mouvements et animations ; une scène au repos est redessinée uniquement lorsqu’elle change.


### Atelier IA local 6.3.0

VersionCode 46, package `com.chasmet.modeliseur3d`. Les deux moteurs d’origine restent conservés. Le troisième atelier utilise les quatre photos avec TripoSR embarqué sur CPU, puis une fusion propre à l’application. TripoSR reste entraîné sur une image ; ce moteur ne remplace pas une acquisition photogrammétrique complète et n’est pas TRELLIS.

- Import avec les huit orientations EXIF via AndroidX ExifInterface (Apache-2.0), rotation et miroir indépendants par vue. Les PNG détourés gardent leurs bords semi-transparents.
- Recalage **2D limité** des silhouettes opposées : décalage maximal quatre pixels sur une grille 96², échelle ±4 %. Les champs appris sont placés suivant les contours mesurés. Ce recalage ne récupère pas les caméras en perspective. Une tolérance d’environ un pixel limite les coupures dues aux petits écarts de contours ; les poses différentes restent une limite.
- Projection des quatre photos avec cartes de profondeur de visibilité 256², interpolation et raccords pondérés. Atlas 2048². Le contrôle Géométrie sans texture permet d’examiner la forme ; Maillage expose sa triangulation.
- Projets nommés, version et moteur dans les métadonnées `extras` des nouveaux GLB. Les exports historiques des deux autres modes restent identiques.
- Cache neuronal partiellement corrompu reconstruit par vue, nettoyage optionnel des calculs sans supprimer photos/GLB/poids, CPU maintenu éveillé pendant le travail. Une rotation d’écran conserve l’atelier. Après une fermeture par Android, relancer Générer réutilise les étapes déjà mises en cache ; le calcul ne reprend pas automatiquement en arrière-plan.

Vérifications ajoutées : recalage avec écart connu et espace entre les jambes, distance signée avec trou, rejet d’une surface masquée, proportions et fermeture d’un ellipsoïde, huit orientations EXIF, alpha PNG, métadonnées GLB et réparation réelle du cache neuronal. Les connexions sortantes sont interdites pendant les inférences du test TripoSR. Ces références synthétiques ne mesurent pas la fidélité sur les photos d’un utilisateur, ni la vitesse et la chauffe sur son téléphone.

Pour lancer toutes les vérifications Android de bureau : `./gradlew --no-daemon -PofflineSmoke testDebugUnitTest`. Les dépendances de test et les fixtures ne sont pas intégrées à l’APK.
