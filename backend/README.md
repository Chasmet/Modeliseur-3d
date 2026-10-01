# TRELLIS.2 Android + MCP (V6.0.0)

L'APK possède un écran TRELLIS.2/MCP, un sélecteur d'image, une connexion HTTPS,
le suivi de génération, un téléchargement GLB, l'ouverture dans un lecteur externe
et l'export par le sélecteur Android. Aucun compte OpenAI/API payante n'est nécessaire.
Les modèles TRELLIS.2 ne sont **pas** exécutés sur le téléphone : leur implémentation
officielle exige CUDA/NVIDIA. Les 12 Go de RAM du téléphone ne changent pas cela.
Les modes de reconstruction locale précédents restent disponibles à l'accueil.

Profil distant mobile : résolution 512, 50 000 triangles, texture 1 024 pixels.
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

## Appairage MCP

1. Ouvrir l'écran TRELLIS/MCP dans l'APK et connecter l'adresse HTTPS du relais.
2. Activer « Autoriser le MCP », puis copier le lien privé.
3. Dans ChatGPT, activer le mode développeur et ajouter ce lien comme MCP personnel
   dans Plugins. La connexion au client ChatGPT est une étape distincte du déploiement.
4. Garder cet écran de l'APK ouvert pour recevoir les demandes d'ouverture.

Le lien est une capacité secrète de 256 bits, pas un lien à partager : sa possession
donne accès aux images/travaux de **cette connexion uniquement**. Choisir sans OAuth
pour ce lien personnel ; ce mécanisme n'est pas conçu pour publier un plugin public.
Le désactiver révoque immédiatement le lien côté serveur ; le réactiver le remplace.
Les logs d'accès doivent rester désactivés car l'URL contient cette capacité.
Les secrets Android restent dans `getNoBackupFilesDir()`.

Outils : `application_status`, `list_models_and_images`, `generate_model` sur une
image déjà envoyée depuis le téléphone, `open_model_on_phone` sur un GLB prêt.
Une commande acceptée signifie **en attente**, pas exécutée sur le téléphone.
Le MCP ne lit pas les autres fichiers du téléphone et ne fournit aucune commande shell.

## Vérification

```sh
python -m pip install -r backend/requirements.txt pytest==9.1.1
python -m pytest backend/tests -q
```

Les tests utilisent le vrai transport MCP et un moteur GPU simulé pour vérifier
l'isolation, la révocation, l'envoi borné, le cycle commandes/acquittement et l'arrêt
sur quota. Ils ne valident pas une génération GPU réelle ni le téléphone physique.
