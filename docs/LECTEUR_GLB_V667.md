# Audit ciblé et lecteur GLB V6.6.7

## Base de travail préservée

- Base : Release **v6.6.6** (versionCode 68), et non branche main V5.9.10.
- Branche de sécurité : `backup/release-v666-before-glb-20261122`.
- Aucune modification des moteurs TripoSR, 2.5D, 3D manuel, des modèles ONNX,
  du serveur MCP, de la reconstruction ni de l'UpdateManager.
- Même applicationId, mêmes chemins de stockage, même système de signature.
- Version prévue : 6.6.7 / versionCode 69 pour permettre une mise à jour sans désinstallation.

## Problème trouvé

Le catalogue télécharge/exporte les GLB, mais son bouton « ouvrir » envoyait un
intent externe. La Release v6.6.6 possédait déjà un visualiseur WebGL local
réservé aux identifiants de jobs du module Cloud.

## Correction minimale

- Nouveau bouton « Lecteur GLB » sur l'accueil.
- Sélection d'un GLB du téléphone avec Android OpenDocument.
- Ouverture des GLB du catalogue **dans l'application**.
- Réutilisation du lecteur local existant (Three.js embarqué, aucune dépendance CDN).
- Lecteur de job cloud inchangé.
- Flux GLB servi directement depuis son URI content:// autorisé, sans duplication
  du fichier, sans exposer l'accès aux autres documents et sans serveur réseau.
- Gestes existants : rotation, zoom, face, dos, droite, gauche ; lecture des animations
  lorsqu'elles existent ; modes texture, argile et filaire.

## Vérifications avant fusion / livraison

1. `./gradlew --no-daemon clean lintDebug assembleDebug` en environnement Android.
2. Ouvrir un fichier .glb par le nouveau bouton d'accueil.
3. Ouvrir un modèle depuis le catalogue ; vérifier absence d'application extérieure.
4. Lire un GLB animé et changer de séquence dans le lecteur.
5. Vérifier le module Cloud existant et les quatre onglets de génération.
6. Vérifier APK signé par **la même clé** que la version installée ;
   ne jamais désinstaller l'application pour contourner une erreur de signature.
7. Publier la Release v6.6.7 avec l'APK **vérifié**, pour qu'UpdateManager
   propose la mise à jour dans les Réglages.

La compilation et la compatibilité de signature ne sont pas déclarées réussies
avant confirmation par GitHub Actions et/ou un test sur appareil.

## Correctif interface — 22 novembre 2026

- L'accueil affiche désormais **cinq onglets**, avec « Lecteur GLB » en premier.
- Au premier lancement de V6.6.7, le lecteur est sélectionné pour être immédiatement visible.
- La navigation entre les onglets est défilante horizontalement ; les onglets originaux sont conservés.
- Boutons « Ouvrir un fichier GLB » et « Bibliothèque 3D » directement dans cet onglet.
- Le lecteur interne, les animations et l'export du catalogue sont conservés.
- La publication doit impérativement réutiliser la signature historique via le workflow OIDC autorisé de la branche de release ; ne jamais publier un nouvel APK signé différemment.
