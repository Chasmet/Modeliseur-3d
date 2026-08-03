# Validation V4.4 locale

La validation automatique doit confirmer les points suivants :

- aucune permission `android.permission.INTERNET` ;
- aucune classe Tripo, clé API ou adresse de service cloud ;
- version Android `4.4.0` / versionCode `10` ;
- extraction locale de huit vues vidéo ;
- regroupement des silhouettes testé en Java ;
- simplification du maillage mobile testée en Java ;
- export HD conservé séparément ;
- copie mobile refusée si elle dépasse 200 000 octets ;
- IS-Net Anime FP32 et Depth Anything V2 vérifiés par SHA-256 dans l’APK ;
- signature APK v1/v2 vérifiée ;
- `lintDebug` et `assembleDebug` réussis.

Commande de compilation :

```bash
./gradlew --no-daemon clean lintDebug assembleDebug
```

Artefact attendu :

```text
Modeliseur3D-V4.4-Local-Video8-debug
```
