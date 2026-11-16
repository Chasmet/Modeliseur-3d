# Signature de Mon Coran sur Google Play (Sync 30 Numérique)

## Architecture et sécurité

Le module Android public `com.sync30.moncoran` est compilé dans `Chasmet/Coran` via `.github/workflows/google-play.yml`. L'APK personnelle `com.chk.coran` conserve son workflow `android.yml` et ses données.

Le service existant Render `modeliseur-trellis-mcp` expose `/coran/ci/signing` pour les exécutions GitHub Actions autorisées. Les clés persistent dans l'environnement sécurisé Render, **jamais dans GitHub ou ces notes**.

Le serveur valide JWT GitHub OIDC et requiert **exactement** : repository `Chasmet/Coran`, ID du dépôt, ID du propriétaire, ref `refs/heads/main`, runner GitHub hébergé, événement `push` ou `workflow_dispatch` et un des deux `workflow_ref` explicites :

- `Chasmet/Coran/.github/workflows/android.yml@refs/heads/main`
- `Chasmet/Coran/.github/workflows/google-play.yml@refs/heads/main`

Les autres branches/dépôts/workflows doivent être refusés. Voir `backend/tests/test_coran_signing.py`.

## Incident Google Play du 16 novembre 2026

Le run `Chasmet/Coran` n°23 a reçu HTTP 403 car seul le workflow Android personnel figurait dans la liste autorisée. La correction, commit `6f0eaa9e686faae7d10d2b63a575aa4dbda9db0d`, élargit **uniquement** cette liste au workflow Google Play sur main ; elle ne crée ni ne régénère de clé.

## Vérifications de reprise

1. Confirmer que **le service Render** exécute le commit corrigé, et que les tests GitHub Actions passent.
2. Relancer uniquement le workflow Play échoué, puis vérifier l'étape de certificat, le fingerprint public SHA-256 prévu dans le workflow et l'artefact AAB privé.
3. Contrôler package, versionCode, compatibilité et licences des textes/récitations ; importer sur la piste **interne** en premier.
4. Vérifier l'absence d'une nouvelle publication APK personnelle après le commit `[play-only]`.
5. Ne pas annoncer de production publique tant qu'aucune preuve Play Console ne le confirme.

Ce fichier ne contient ni keystore, ni secret, ni token. Le suivi opérationnel est aussi documenté dans `Chasmet/Coran/issues/2`.
