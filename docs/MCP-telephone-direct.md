# Serveur Android direct — 6.4.1

Le serveur et les moteurs 3D s’exécutent sur le téléphone. Aucune clé OpenAI ni aucun serveur de calcul externe n’est nécessaire. GitHub compile et distribue l’APK ; il n’héberge pas ce serveur MCP.

## Réglages

Ouvrir **Réglages → Serveur MCP du téléphone → Activer le serveur du téléphone**.

- **Diagnostic réseau** : type de connexion, adresses du lien actif, IPv6 globale, IPv4 privée/publique et CGNAT observable sur le lien.
- **Tester le serveur local** : requête HTTP réelle et découverte des six outils sur le téléphone.
- **Tester HTTPS à l’adresse publique** : contrôle TLS avec les autorités Android habituelles et le nom d’hôte configuré. Aucun certificat n’est accepté en désactivant la vérification.
- Le test de l’adresse publique depuis le téléphone ne prouve pas l’accès extérieur : la box peut permettre uniquement le réseau local ou, inversement, refuser le retour par sa propre adresse publique.
- Un accès MCP authentifié reçu en HTTPS depuis une adresse extérieure globale est enregistré séparément. Cette observation est invalidée quand le lien réseau ou la configuration change.

Le bouton d’accueil indique **Activer MCP / Désactiver MCP** : il n’affiche plus « Connecté » uniquement parce que le service est activé.

## Endpoints et authentification

| Endpoint | Usage | Accès |
| --- | --- | --- |
| `GET /health` | État léger, sans données privées | Pas de secret requis |
| `GET /status` | État détaillé et diagnostic réseau | Bearer |
| `POST /mcp` | Streamable HTTP | Bearer |
| `POST /mcp/<jeton>` | Compatibilité avec l’URL privée précédente | Secret dans l’URL |
| `GET /files/<id>/<secret>` | GLB sauvegardé | Secret de téléchargement propre au modèle |

Le protocole négocie les versions 2025-03-26, 2025-06-18 et 2025-11-25. Les arguments sont validés strictement avant mise en file. Aucune commande shell ou lecture arbitraire de fichiers n’est exposée.

Outils : `application_status`, `application_capabilities`, `list_models_and_images`, `create_model_from_images`, `model_status`, `model_download`.

Les jetons et le PKCS12 sont chiffrés par AES-GCM avec une clé protégée par Android Keystore. Android 21/22 utilise une clé AES enveloppée par une clé RSA du Keystore. La migration conserve le jeton et les liens privés existants. Les secrets ne sont pas sauvegardés dans les sauvegardes Android et ne sont pas journalisés.

HTTP écoute **127.0.0.1:8787** pour les tests sur le téléphone seulement. Le réseau utilise HTTPS **8443** avec un certificat importé. Si le port est occupé, un port libre est conservé dans les réglages : adapter alors le pare-feu ou la redirection à ce port affiché. Un certificat absent, invalide ou expiré n’empêche pas le serveur local de fonctionner.

## Accès direct depuis ChatGPT

1. Installer la mise à jour par-dessus l’application existante.
2. Activer le serveur du téléphone et consulter le diagnostic.
3. En IPv6 globale, autoriser le port HTTPS entrant dans le pare-feu de la box. En IPv4 privée, vérifier l’adresse WAN de la box puis configurer sa redirection vers le téléphone. Une IP privée ne suffit pas pour déterminer le double NAT ou le CGNAT amont.
4. Importer un PKCS12 contenant un certificat reconnu publiquement, sa chaîne et sa clé privée, valable pour le domaine ou l’IP configurée. Renouveler ce certificat avant son expiration.
5. Enregistrer la base HTTPS publique, sans chemin. Le diagnostic refuse les IP locales/réservées comme adresse publique.
6. Copier l’URL MCP HTTPS privée et l’utiliser dans le connecteur ChatGPT. Ne pas la publier.
7. Tester depuis ChatGPT ou un réseau extérieur. Le connecteur Render existant ne devient pas automatiquement le connecteur direct : son endpoint doit être remplacé.

La simple présence d’une IPv6 globale ne prouve pas que son pare-feu est ouvert. Si aucune adresse entrante n’est exploitable, l’accès Internet direct n’est pas établi ; le service local et les fichiers restent utilisables. Aucun relais payant n’est ajouté automatiquement.

## Persistance

Le ForegroundService conserve les tâches et les GLB dans le stockage privé, reprend les travaux après interruption et surveille les changements de réseau avec ConnectivityManager. Les échecs de démarrage utilisent un délai progressif jusqu’à une minute. Les coupures réseau n’annulent pas l’inférence locale.

Autoriser l’activité en arrière-plan et les notifications. Les restrictions du constructeur peuvent nécessiter d’autoriser aussi le lancement automatique. Un téléphone éteint ou une application arrêtée de force ne reçoit pas de commandes ; rouvrir l’application après un arrêt forcé.

## Limites restantes

L’émission et le renouvellement ACME ainsi que PCP/NAT-PMP/UPnP ne sont pas implémentés. La configuration HTTPS et du réseau entrant reste nécessaire. Aucun test depuis le téléphone physique de l’utilisateur, écran éteint ou depuis Internet n’est revendiqué par les tests de bureau. Les tests cryptographiques de bureau utilisent un adaptateur de Keystore en mémoire ; ils vérifient AES-GCM et la migration, pas la protection matérielle Android.

Le mode relais précédent reste disponible en compatibilité. Les moteurs TripoSR/Silhouettes, les modes existants, les projets et la mise à jour automatique sont conservés. L’application modélise ; elle n’effectue ni rigging ni animation.
