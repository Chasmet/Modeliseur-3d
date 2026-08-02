# Validation de compilation

Ce fichier accompagne le workflow GitHub Actions.

Commande de vérification :

```bash
./gradlew --no-daemon clean lintDebug assembleDebug
```

L'APK produit se trouve dans `app/build/outputs/apk/debug/app-debug.apk`.
