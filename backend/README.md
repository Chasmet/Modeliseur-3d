# TRELLIS.2 Android + MCP (V6.0.0)

L'APK possède un écran TRELLIS.2/MCP, un sélecteur d'image, une connexion HTTPS,
le suivi de génération, un téléchargement GLB, l'ouverture et la lecture des animations dans l'APK
et l'export par le sélecteur Android. Aucun compte OpenAI/API payante n'est nécessaire.
Les modèles TRELLIS.2 ne sont **pas** exécutés sur le téléphone : leur implémentation
officielle exige CUDA/NVIDIA. Les 12 Go de RAM du téléphone ne changent pas cela.
Les modes de reconstruction locale précédents restent disponibles à l'accueil.

Profil distant mobile : résolution 512, 100 000 triangles, texture 1 024 pixels.
Images normalisées à 1 024 pixels, transferts par blocs de 64 Ko, GLB limité à
64 Mo. Le résultat est un GLB standard, sans décodeur Meshopt obligatoire.
Les animations optionnelles sont des approximations procédurales, adaptées aux
humanoïdes verticaux ; un rig incompatible conserve le GLB sans animation.

## Relais HTTPS sur Render

Créer un **Web Service Python gratuit** sur la branche qui contient ces fichiers.
Commande de build :

```sh
python -m pip install -r backend/requirements.txt && npm ci --prefix tools/trellis
```

Commande de démarrage :

```sh
python -m uvicorn backend.server:app --host 0.0.0.0 --port "$PORT" --no-access-log
```

Node doit être disponible dans l'environnement de build et d'exécution ; le relais
ne déclare pas le moteur prêt si l'export Node est absent. Ajouter `HF_TOKEN` seulement
sur le serveur si un compte Hugging Face est nécessaire. Ne jamais mettre de token
Hugging Face dans l'APK, le dépôt ou le lien MCP. `RENDER_EXTERNAL_HOSTNAME` permet
au SDK MCP de valider le Host et est fourni par Render.

Le service Render gratuit peut se mettre en veille, interrompre un travail et perdre
ses fichiers/SQLite lors d'un redéploiement. Les erreurs et quotas sont affichés sans
relance automatique. Ce relais utilise la démo officielle Hugging Face, soumise à ses
quotas et à sa disponibilité, et n'héberge pas lui-même un GPU. Une instance GPU
dédiée serait une étape séparée. Conserver les GLB via l'export Android.
Les images/travaux distants restent jusqu'à effacement depuis l'application ou perte
du stockage éphémère. Le stockage refuse les nouvelles images au-delà de 256 Mo ;
12 images et 12 travaux maximum par connexion, 2 travaux simultanément en attente,
1 génération exécutée à la fois. Aucun lien source arbitraire n'est accepté.

## Connexion MCP sans authentification

Le point d'entrée principal est :

`https://modeliseur-trellis-mcp.onrender.com/mcp`

Dans ChatGPT Plugins, choisir **Aucune authentification**. Aucun OAuth, PAT GitHub,
clé API ni secret n'est demandé par ce MCP. Le relais associe automatiquement les
commandes au téléphone Android Modéliseur 3D le plus récemment actif. Si aucun
téléphone n'est connecté, les générations restent dans un espace public éphémère
du relais jusqu'au prochain redéploiement Render.

Le parcours historique avec un lien privé `/mcp/<capacité>` reste disponible pour
compatibilité avec les anciennes versions de l'APK, mais il n'est plus nécessaire
pour le plugin ChatGPT principal.

Outils : `application_status`, `list_models_and_images`,
`create_model_from_images`, `model_status`, `model_download`,
`generate_model` et `open_model_on_phone`.

`create_model_from_images` accepte de 1 à 4 images sous forme d'URL HTTPS publique,
de Data URL base64, de `base64:<données>` ou de base64 brut. Les fichiers sont
bornés à 8 Mo et normalisés à 1 024 px. TRELLIS.2 étant un moteur mono-vue, plusieurs
images produisent volontairement plusieurs candidats 3D plutôt qu'une fausse fusion
multicaméra. L'image la plus informative doit être placée en premier. Le GLB prêt est
récupérable par `model_download` et peut être demandé à l'ouverture dans l'application.

**Sécurité :** ce choix sans authentification rend `/mcp` accessible depuis Internet.
Il est volontaire pour cet usage personnel. Les anciennes routes Android restent
protégées par leur jeton d'appairage ; aucune commande shell ni accès général aux
fichiers du téléphone n'est exposé.

## Vérification

```sh
python -m pip install -r backend/requirements.txt pytest==9.1.1
python -m pytest backend/tests -q
```

Les tests utilisent le vrai transport MCP et un moteur GPU simulé pour vérifier
l'isolation, la révocation, l'envoi borné, le cycle commandes/acquittement et l'arrêt
sur quota. Ils ne valident pas une génération GPU réelle ni le téléphone physique.

## Signature de publication

`/ci/signing` est réservé au workflow de publication : JWT GitHub OIDC signé, audience/émetteur, IDs du dépôt et du propriétaire, acteur, branche et workflow contrôlés. Les requêtes MCP/Android ne peuvent pas récupérer la clé. La clé V6.0.0 est persistée comme variable privée Render ; pas dans le stockage éphémère ni dans le dépôt. Ne jamais activer les logs d’accès ni afficher la réponse de signature dans les logs CI.
