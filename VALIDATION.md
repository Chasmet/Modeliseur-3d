# Validation V7.1

La validation automatique doit confirmer les points suivants :

- version Android `7.1.0` / versionCode `39` ;
- reconstruction des photos exécutée localement ;
- IS-Net Anime FP32 vérifié par SHA-256 dans l'APK ;
- DA3-SMALL quatre vues vérifié par SHA-256 dans l'APK ;
- sortie ONNX comparée numériquement à PyTorch avant la compilation ;
- fusion de profondeur incapable de créer un voxel hors de la coque ;
- garde-fou anti-écrasement testé en Java pur ;
- profil de cheval naturellement large conservé sans rotation à 90° ;
- vraie photo horizontale toujours redressée lorsque le score le confirme ;
- totalité d'une cellule de texture rendue opaque par couleur voisine ;
- sortie NNAPI vide ou plate rejouée automatiquement sur CPU ;
- motif de la coque de secours affiché dans le résumé final ;
- enveloppe continue testée en Java pur ;
- coins artificiels arrondis sans perdre les projections ;
- profondeur locale des membres inférieure à celle du torse ;
- espace entre les jambes conservé ;
- détail adaptatif accepté seulement avec deux axes de support ;
- profil à 9 % détecté face à un profil valide à 45 % ;
- vue de secours appliquée à la géométrie et à la texture ;
- profondeur d'un kart large conservée sans aplatissement en feuilles ;
- vidéo routée vers la surface continue huit vues et non vers le 2.5D ;
- export GLB complet sans simplification ;
- signature APK v1/v2 vérifiée ;
- `lintDebug` et `assembleDebug` réussis.

Commande de compilation :

```bash
./gradlew --no-daemon clean lintDebug assembleDebug
```

Artefact attendu :

```text
Modeliseur-V7-1-DA3-Modeleur-Multivue-debug
```
