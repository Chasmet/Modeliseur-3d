# Modéliseur 3D V6.0.1 — branche TRELLIS isolée

Trois onglets à l’accueil : **2.5D**, **3D** et **TRELLIS**. Le catalogue de 259 assets reste accessible.
Les moteurs locaux, exports et données existants ne sont pas modifiés. Le troisième onglet ouvre la génération distante et le lecteur GLB intégré.

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
