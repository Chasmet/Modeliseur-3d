# MCP direct du téléphone — 6.3.10

## Ce qui est intégré

Dans **Réglages → Serveur MCP du téléphone**, le mode direct démarre un vrai serveur MCP Streamable HTTP sur Android. Les six outils lisent l’état local, reçoivent les images et créent/récupèrent les GLB. La file et les images restent dans le stockage privé. Les calculs sont indépendants de l’écran de l’application.

Une image sans réglage explicite déclenche **TripoSR une image · Face · Précis · IS-Net**. Import EXIF, conservation de l’alpha PNG et préparation du détourage sont partagés avec l’atelier manuel. Le cache ancien de préparation MCP n’est pas réutilisé. Les qualités rapide/équilibrée restent possibles si demandées explicitement. Les surfaces invisibles restent estimées ; ce parcours ne fournit ni squelette ni animation.

## Configurer la box pour ChatGPT

1. Installer l’APK et garder le téléphone sur le Wi-Fi de la box. Réserver son adresse IPv4 dans le DHCP de la box pour éviter qu’elle change.
2. Vérifier que la box possède une adresse publique joignable. Avec un CGNAT opérateur, une redirection IPv4 seule ne suffit pas : demander une IPv4 publique à l’opérateur. Le serveur n’est pas accessible depuis Internet par une simple URL Wi-Fi.
3. Faire pointer un domaine ou un nom DNS dynamique vers la connexion publique. Obtenir un certificat HTTPS reconnu publiquement pour ce nom, avec sa chaîne et sa clé privée. L’application n’émet pas de certificat et ne renouvelle pas automatiquement celui importé.
4. Préparer un fichier PKCS12 `.p12` contenant certificat, chaîne et clé privée. Avec OpenSSL 3 et les anciens Android, utiliser une exportation PKCS12 compatible (option `-legacy`). Importer ce fichier et son mot de passe dans les réglages MCP. Ne partager ni le fichier ni sa clé privée.
5. Dans la box, rediriger le port TCP externe **8443** vers le port **8443** du téléphone. Le port externe peut aussi être 443 si disponible. Ne pas exposer le port HTTP **8787** à Internet.
6. Saisir l’adresse publique, par exemple `https://ton-domaine:8443`, activer **MCP direct**, enregistrer, puis activer **Connecté** sur l’accueil.
7. Copier l’**URL MCP HTTPS publique** dans les réglages et remplacer l’ancienne adresse du connecteur ChatGPT par cette URL privée. Elle contient un jeton d’accès : la garder secrète. Ne pas utiliser l’adresse Render pour ce mode.
8. Vérifier la connexion depuis l’extérieur du Wi-Fi, puis demander `application_status`. La réponse directe indique `transport: Android direct` et `relay_required: false`. Enregistrer une adresse ne prouve pas que la redirection, le DNS et le certificat fonctionnent.

Le serveur écoute HTTP **8787** uniquement pour les clients locaux et HTTPS **8443** si un certificat valide est importé. L’URL privée est conservée entre les mises à jour. Les origines et hôtes inconnus sont refusés. Les commandes reçoivent un lien GLB distinct du jeton MCP. Aucun journal d’accès n’affiche les liens privés.

## Continuer pendant YouTube, Netflix ou écran éteint

Activer **Connecté**, autoriser les notifications et, via **Autoriser l’activité écran éteint**, accepter l’exemption batterie Android. Si le constructeur impose d’autres restrictions, autoriser le lancement automatique et l’activité en arrière-plan dans les réglages de l’application. Le service conserve des verrous CPU/Wi-Fi tant que la connexion est activée : cela consomme de la batterie, même sans commande. Le téléphone doit garder son réseau et sa batterie.

**Déconnecté** arrête le serveur et suspend le calcul aux points d’arrêt du moteur. Les fichiers sont conservés pour la reprise. Android peut néanmoins interrompre le processus ; le service prévoit une relance et une reprise des commandes. Un téléphone complètement éteint, un arrêt forcé Android ou une perte de réseau empêchent la réception. Rouvrir l’application après un arrêt forcé. Aucun essai sur le téléphone physique de l’utilisateur n’est revendiqué par les tests de bureau.

## Compatibilité

Tant que la box HTTPS n’est pas prête, laisser le mode direct décoché permet de conserver le relais existant. La qualité par défaut y est également **Précis + IS-Net** après mise à jour du backend. Le relais transporte seulement les entrées/résultats ; l’inférence est locale dans les deux modes. La signature APK et l’automise à jour existantes sont conservées. Leur workflow CI utilise la clé déjà protégée ; ce n’est pas une dépendance du serveur direct pendant son fonctionnement.
