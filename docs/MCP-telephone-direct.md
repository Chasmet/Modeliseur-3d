# Serveur Android direct — 6.4.4

Modéliseur 3D, package `com.chasmet.modeliseur3d`, Java 17, minSdk 21, targetSdk 34. Le serveur existant est réutilisé en mode autonome : aucun Serveur App Host universel n’est installé par cet APK. GitHub compile et distribue l’APK ; il ne participe pas à l’exécution du serveur. Calculs et GLB restent sur le téléphone.

## Configuration automatique

Installer la mise à jour par-dessus l’application. Dans **Réglages → Serveur MCP du téléphone**, choisir **Activer HTTPS automatique** et accepter les conditions Let’s Encrypt affichées. Aucun champ IP, MAC, port ou URL n’est à remplir.

Après une mise à jour, un ancien échec `badCSR` de la gestion automatique déclenche une nouvelle demande une seule fois. Une configuration manuelle vide après cet échec est récupérée seulement si le MCP est actif, sans certificat manuel et sans commande conservée. Les modes manuels valides et les arrêts explicites restent respectés.

Le démarrage vérifie réellement `initialize`, `tools/list` et `tools/call` sur la socket loopback avant de marquer `LOCAL_OK`.

Un exécuteur indépendant du calcul 3D :

1. Lit l’IPv4 et la passerelle du Wi-Fi actif avec ConnectivityManager.
2. Demande l’adresse WAN via UPnP IGD puis NAT-PMP. Sans adresse publique exploitable, compare deux services HTTPS : api4.ipify.org et checkip.amazonaws.com. Une divergence bloque l’émission.
3. Tente PCP, NAT-PMP puis UPnP pour TCP public 8443 vers le port TLS réel. Un conflit local choisit un port libre. Une règle visant un autre service n’est pas écrasée.
4. Lance HTTP-01 sur 8080 ou un port libre et tente une redirection temporaire TCP externe 80. Seul le challenge aléatoire est servi, sans données ni fonctions MCP.
5. Demande un certificat IP via ACME v2, identifiant `ip`, profil `shortlived`, JWS RS256, nonces et POST-as-GET. Les endpoints restent sur la même autorité HTTPS.
6. Vérifie clé, validité, SAN IP et chaîne reconnue par Android avant d’enregistrer le PKCS12 chiffré. Recharge le listener TLS sans arrêter les calculs ni les sockets déjà acceptés.
7. Recalcule l’URL depuis les valeurs mesurées. Contrôle le réseau et renouvelle les règles toutes les cinq minutes. Renouvelle le certificat lorsqu’il reste moins de 48 heures, ou après changement d’IP publique.

Les certificats IP Let’s Encrypt durent 160 heures. Le délai automatique d’échec ACME est persistant et dure au moins une heure. Un clic explicite sur Renouveler peut lever ce délai local une fois toutes les cinq minutes, sans contourner un Retry-After ou une limitation de débit imposés par Let’s Encrypt. Le worker relit les préférences ; aucun second délai en mémoire ne bloque le clic. Le diagnostic expose localement les étapes, l’heure du prochain essai, les requêtes HTTP-01 reçues et les détails d’erreur bornés. Le téléphone doit rester allumé, connecté et autorisé à fonctionner en arrière-plan. Les changements réseau et erreurs Internet ne suppriment ni projets, ni file locale, ni GLB. La validation TLS n’est jamais désactivée.

Si le serveur de test Python occupe 8443, l’arrêter libère le port de la règle Livebox existante. Si la box refuse l’ouverture automatique, le diagnostic affiche la règle exacte avec l’adresse locale et les ports mesurés. Le port externe 80 doit rester redirigé vers le port du challenge pour les renouvellements avec une règle manuelle ; le répondeur est fermé hors émission.

Livebox 5 : conserver le bail DHCP statique et le pare-feu **Moyen**. Ne modifier ni Wi-Fi, ni mots de passe, ni autres appareils. Les API Android ordinaires ne permettent pas de changer la MAC aléatoire ou un bail DHCP ; les redirections automatiques suivent les changements d’IPv4 locale. Une redirection acceptée n’est pas une preuve d’accès extérieur.

## Routes

| Route | Accès | Capacités |
| --- | --- | --- |
| GET /health, /apps/modeliseur3d/health | Aucun secret | Santé légère |
| GET /status, /apps/modeliseur3d/status en HTTPS sans Bearer | Aucun | État non sensible |
| POST /apps/modeliseur3d/mcp ou /mcp en HTTPS sans Bearer | Aucun | application_status et application_capabilities |
| POST /mcp ou route application avec Bearer valide | Jeton | Six outils complets |
| POST /mcp/&lt;jeton&gt; | Lien privé | Six outils complets précédents |
| GET /files/&lt;id&gt;/&lt;secret&gt; | Secret par modèle | GLB prêt |
| HTTP loopback 127.0.0.1:8787 ou port libre | Bearer / lien privé | Diagnostic local uniquement |

Les six outils complets : `application_status`, `application_capabilities`, `list_models_and_images`, `create_model_from_images`, `model_status`, `model_download`. Le manifeste local dans `assets/mcp-tools.json` décrit les schémas et les politiques. Streamable HTTP négocie 2025-03-26, 2025-06-18 ou 2025-11-25.

L’endpoint public sans authentification ne donne accès ni aux modèles personnels, ni aux commandes d’écriture, ni aux informations réseau du téléphone. Jetons, clé ACME et PKCS12 sont chiffrés par AES-GCM avec Android Keystore ; Android 21/22 enveloppe la clé AES par RSA. Les anciennes URL privées restent compatibles. Aucun shell, stockage arbitraire ou contrôle d’autres applications.

## Validation et plugin

Le panneau sépare les observations : listener actif, test local réussi, TCP extérieur observé, TLS actif et outil MCP appelé depuis l’extérieur. Il ne déclare pas ChatGPT connecté à partir d’un heartbeat.

- LOCAL_OK : test réel sur le téléphone.
- PUBLIC_TCP_OK : handshake extérieur.
- HTTPS_OK : test extérieur avec certificat et SAN IP vérifiés.
- MCP_OK : initialize, tools/list et tools/call sur l’URL publique.
- PLUGIN_READY : connexion ChatGPT préparée pour l’URL vérifiée.
- CHATGPT_CONNECTED : appel effectif d’un outil depuis ChatGPT.

Après MCP_OK : nom **Modéliseur 3D**, description **État et capacités du modeleur 3D Android**, URL fournie par **Copier l’URL ChatGPT sans authentification**, authentification **Aucune**. Le panneau recalcule l’URL après changement d’IP ; l’APK ne peut pas modifier à lui seul le plugin ChatGPT distant.

## Contrôles et limites

Tests de bureau : signatures JWS, badNonce, identifiant IP, POST-as-GET, profil shortlived, CSR vérifié indépendamment par OpenSSL, PCP/NAT-PMP, répondeur HTTP-01 réel, sockets TLS, outils publics limités, écritures refusées sans secret et rechargement TLS. Tests existants des six outils, file persistante et migration des secrets conservés.

Ces tests ne remplacent pas l’émission Let’s Encrypt sur le téléphone, les essais avec la Livebox réelle, le test extérieur HTTPS/MCP, l’écran éteint, le reboot et l’appel ChatGPT. Les tests Keystore emploient un adaptateur en mémoire ; ils ne prouvent pas la protection matérielle.

Le relais historique reste disponible si l’utilisateur le choisit. Sa présence indique « téléphone en ligne · appel ChatGPT à vérifier », pas une connexion ChatGPT validée. Le mode direct n’en dépend pas. Même package et signature APK, moteurs TripoSR/Silhouettes/IS-Net/Depth Anything V2, projets et mise à jour automatique conservés ; aucun rigging ni animation ajouté.
