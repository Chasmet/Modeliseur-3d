# Modéliseur 3D V6.0.5 — branche TRELLIS isolée

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

## Atelier hors connexion V6.0.5

Le deuxième onglet affiche explicitement le mode original à quatre images (face, dos, droite, gauche). Ses moteurs et son écran de capture restent inchangés. Les onglets utilisent des couleurs contrastées et mémorisent le dernier choix.

Dans le troisième onglet, **Atelier hors connexion** reconstruit une silhouette en volume texturé approximatif : import réduit à 1 024 px, PNG alpha ou retrait de fond uni, détourage IS-Net embarqué optionnel, grilles 80/112/144 limitées selon le budget mémoire Java, épaisseur réglable et volume arrondi ou relief fin. Aucun réseau ni téléchargement de modèle n’est nécessaire. Le dos est déduit de la silhouette ; ce résultat n’équivaut pas à une inférence TRELLIS.2.

Les GLB sont enregistrés dans le stockage privé du téléphone, disponibles dans la galerie locale, affichés avec le lecteur embarqué et exportables via le sélecteur de documents Android. Le parcours serveur a été retiré du troisième onglet. Le code distant existant est conservé pour un éventuel travail MCP ultérieur, sans être appelé par cet atelier.

V6.0.5 : versionCode 41, même applicationId et certificat V6.0.0/V6.0.1. Les projets existants restent conservés.


### Fonctions locales 6.0.5

- Vérification visuelle du détourage avant génération ; PNG détouré conservé et réutilisé lorsque seuls la forme, l’épaisseur ou le détail changent. Import/rotation ou changement de méthode/tolérance invalident ce cache.
- Objet rond à 360° : profil de révolution fermé pour bouteilles/vases verticaux et symétriques ; profils lissés, texture issue de l’image, moins de 20 000 triangles. Ce mode ne convient pas aux personnages ou objets asymétriques et ne déduit pas une face cachée réelle.
- Arrêt après l’étape en cours, une seule opération à la fois, durée et taille affichées, grilles limitées et réglages/modèles conservés.
- Tests Android : connexions sortantes interdites pendant l’inférence CPU IS-Net réelle, la génération des trois formes, la sauvegarde, la reprise et l’accès aux ressources privées du lecteur. Validation GLB et rendu couleur dans un navigateur séparé. Pas de mesure sur téléphone physique revendiquée.


### Profondeur IA locale

Depth Anything V2 Small FP32 (Apache-2.0) est maintenant réellement embarqué dans l’APK, en plus d’IS-Net. Les poids de 99,1 Mo sont téléchargés et vérifiés uniquement pendant la compilation, jamais par l’application. Révision ONNX Community figée `64fe43eba7f8a384b02fe3fadaa26cbba35548d8`, SHA-256 `afb6a5c28f3b6bf1618c6e43f02073ef9dfdc70e937502d51603e57b0a1df10c` ; licence et attribution dans `assets/licenses`.

Le troisième onglet estime la profondeur relative de la face visible sur CPU (deux threads), puis forme un relief ou un volume fermé. Ce calcul est optionnel et ses résultats sont sauvegardés dans une carte locale de 128 × 128 valeurs. Changer le détail ou l’épaisseur réutilise cette carte. Les sessions de détourage et de profondeur sont fermées successivement, pour éviter de garder les deux réseaux chargés ensemble. Le mode de révolution déduit son épaisseur de la silhouette et désactive la profondeur IA.

La face cachée reste une approximation : ces deux IA ne constituent pas une génération TRELLIS complète. Le moteur de profondeur historique garde ses paramètres par défaut ; seul l’atelier utilise le nouveau constructeur avec budget CPU explicite. Les performances sur l’appareil physique restent à mesurer.

Le lecteur limite les rafraîchissements à 30 FPS pendant les mouvements et animations ; une scène au repos est redessinée uniquement lorsqu’elle change.
