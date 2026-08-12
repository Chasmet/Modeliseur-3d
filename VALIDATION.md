# Validation V6.1

La validation automatique doit confirmer les points suivants :

- version Android `6.1.0` / versionCode `37` ;
- reconstruction des photos exécutée localement ;
- IS-Net Anime FP32 vérifié par SHA-256 dans l'APK ;
- enveloppe continue testée en Java pur ;
- coins artificiels arrondis sans perdre les projections ;
- profondeur locale des membres inférieure à celle du torse ;
- espace entre les jambes conservé ;
- détail adaptatif accepté seulement avec deux axes de support ;
- profil à 9 % détecté face à un profil valide à 45 % ;
- vue de secours appliquée à la géométrie et à la texture ;
- profondeur d'un kart large conservée sans aplatissement en feuilles ;
- export GLB complet sans simplification ;
- signature APK v1/v2 vérifiée ;
- `lintDebug` et `assembleDebug` réussis.

Commande de compilation :

```bash
./gradlew --no-daemon clean lintDebug assembleDebug
```

Artefact attendu :

```text
Modeliseur-V6.1-Profils-Fiabilises-Kart-debug
```
