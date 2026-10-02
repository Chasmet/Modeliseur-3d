# Modéliseur 3D V6.0.4 — branche TRELLIS isolée

Trois onglets à l’accueil : **2.5D**, **3D** et **TRELLIS**. Le catalogue de 259 assets reste accessible.
Les moteurs locaux, exports et données existants ne sont pas modifiés. Le troisième onglet ouvre uniquement l’atelier local et le lecteur GLB embarqué. Aucun compte, serveur ou téléchargement à la première utilisation.

Le relais Render gratuit préconfiguré est `https://modeliseur-trellis-mcp.onrender.com`.
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

## Atelier hors connexion V6.0.4

Le deuxième onglet affiche explicitement le mode original à quatre images (face, dos, droite, gauche). Ses moteurs et son écran de capture restent inchangés. Les onglets utilisent des couleurs contrastées et mémorisent le dernier choix.

Dans le troisième onglet, **Atelier hors connexion** reconstruit une silhouette en volume texturé approximatif : import réduit à 1 024 px, PNG alpha ou retrait de fond uni, détourage IS-Net embarqué optionnel, grilles 80/112/144 limitées selon le budget mémoire Java, épaisseur réglable et volume arrondi ou relief fin. Aucun réseau ni téléchargement de modèle n’est nécessaire. Le dos est déduit de la silhouette ; ce résultat n’équivaut pas à une inférence TRELLIS.2.

Les GLB sont enregistrés dans le stockage privé du téléphone, disponibles dans la galerie locale, affichés avec le lecteur embarqué et exportables via le sélecteur de documents Android. Le parcours serveur a été retiré du troisième onglet. Le code distant existant est conservé pour un éventuel travail MCP ultérieur, sans être appelé par cet atelier.

V6.0.4 : versionCode 40, même applicationId et certificat V6.0.0/V6.0.1. Les projets existants restent conservés.


### Fonctions locales 6.0.4

- Vérification visuelle du détourage avant génération ; PNG détouré conservé et réutilisé lorsque seuls la forme, l’épaisseur ou le détail changent. Import/rotation ou changement de méthode/tolérance invalident ce cache.
- Objet rond à 360° : profil de révolution fermé pour bouteilles/vases verticaux et symétriques ; profils lissés, texture issue de l’image, moins de 20 000 triangles. Ce mode ne convient pas aux personnages ou objets asymétriques et ne déduit pas une face cachée réelle.
- Arrêt après l’étape en cours, une seule opération à la fois, durée et taille affichées, grilles limitées et réglages/modèles conservés.
- Tests Android : connexions sortantes interdites pendant l’inférence CPU IS-Net réelle, la génération des trois formes, la sauvegarde, la reprise et l’accès aux ressources privées du lecteur. Validation GLB et rendu couleur dans un navigateur séparé. Pas de mesure sur téléphone physique revendiquée.
