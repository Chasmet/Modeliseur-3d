# MCP Modéliseur 3D — moteurs locaux Android (6.3.7)

Le MCP pilote les moteurs déjà embarqués dans l’APK : TripoSR, Silhouettes,
IS-Net et Depth Anything V2. Le serveur transporte les commandes, les images
et les GLB terminés. Il ne génère pas de géométrie et ne contacte aucun GPU
Hugging Face. L’ancienne route de génération distante répond HTTP 410 ; les
anciens GLB restent téléchargeables.

## Connexion et parcours

URL MCP : `https://modeliseur-trellis-mcp.onrender.com/mcp`.
Authentification ChatGPT : aucune. Aucun PAT GitHub ou compte IA n’est demandé.
L’application crée et conserve automatiquement son jeton de liaison local.
Le MCP utilise le téléphone le plus récemment actif.

Installer la dernière APK et garder l’accueil ou l’atelier IA locale ouvert.
L’accueil affiche l’état de connexion. Le pont vérifie la file toutes les quatre
secondes et lance le calcul dans l’atelier sans remplacer les photos personnelles.
Pendant un calcul, le téléphone reste présent et conserve les commandes suivantes.
Après chaque GLB, la commande suivante peut démarrer. Quitter l’écran pendant la
préparation rend la commande à la file ; une erreur d’image est signalée.

`create_model_from_images(images, engine, quality, smoothing)` accepte :

- Une image : TripoSR local ou Silhouettes local.
- Quatre images du même sujet dans la même pose, dans cet ordre : **face, dos,
  profil droit, profil gauche**. Un seul modèle utilise la fusion de l’application.
- Deux ou trois images : un modèle indépendant par image, traité successivement.

Les sources sont des URL HTTPS publiques ou des Data URL/base64. Maximum 8 Mo
par source, réduction à 1 024 pixels et conservation de la transparence PNG.
`quality` : `fast`, `balanced`, `precise`. `engine` : `auto`, `triposr`, `silhouettes`.
Les surfaces absentes des photos restent estimées. La fusion de quatre vues
est celle de l’application ; TripoSR n’est pas un modèle multivue natif.

Les outils `application_status`, `application_capabilities`,
`list_models_and_images`, `model_status` et `model_download` indiquent la présence,
les moteurs, la file et le lien du GLB. La réponse indique explicitement une
exécution Android locale. Le GLB est enregistré sur le téléphone avant sa
synchronisation : une panne réseau ne supprime pas le fichier local.

La génération manuelle reste possible hors connexion. Le pilotage ChatGPT et la
synchronisation des entrées/résultats demandent une connexion Internet. Le pont
fonctionne sur l’accueil et dans l’atelier ; il n’est pas un service permanent
quand Android ferme l’application.

## Déploiement du relais existant

Web Service Python, branche `agent/trellis-third-tab-auto-update`.
Build : `python -m pip install -r backend/requirements.txt`.
Démarrage :

```sh
python -m uvicorn backend.server:app --host 0.0.0.0 --port "$PORT" --no-access-log
```

`RENDER_EXTERNAL_HOSTNAME` est fourni par Render et vérifie l’hôte MCP.
Le relais gratuit peut se mettre en veille et perdre ses fichiers/SQLite lors
d’un redéploiement. Les GLB enregistrés dans l’APK restent disponibles.
La limite serveur est de 256 Mo, 12 références et quatre commandes en attente
par téléphone. L’effacement est refusé pendant un travail local actif.
Les anciens liens privés `/mcp/<capacité>` restent compatibles.

## Vérification

```sh
python -m pip install -r backend/requirements.txt pytest
python -m pytest backend/tests -q
./gradlew --no-daemon -PofflineSmoke testDebugUnitTest lintDebug
```

Les tests du relais passent par le vrai transport MCP et vérifient l’isolation,
les images, l’alpha, l’ordre des quatre vues, la file, le retour GLB et l’absence
de génération GPU. Les tests Android vérifient les intents, l’attente pendant
un calcul et la restitution d’une commande quand l’écran quitte le premier plan.
Les tests TripoSR réalisent une inférence avec les connexions réseau interdites.
Le téléphone physique n’est pas simulé par un statut de présence en production.

## Signature des mises à jour

`/ci/signing` reste réservé au workflow de publication : JWT GitHub OIDC signé,
audience/émetteur, IDs du dépôt et du propriétaire, acteur, branche et workflow
contrôlés. La clé Android existante est conservée dans une variable privée Render.
Les requêtes MCP/Android ne peuvent pas récupérer la clé. Ne jamais activer les
logs d’accès ni afficher la réponse de signature dans les logs CI.
