# Validation V4.5

La V4.5 doit confirmer :

- sélecteur Android 13+ forcé avec `MediaStore.ACTION_PICK_IMAGES` et type `video/*` ;
- repli vers la médiathèque vidéo ou `ACTION_OPEN_DOCUMENT` sur les autres appareils ;
- extraction de huit trames nettes ;
- détourage IS-Net Anime FP32 séparé pour chaque trame ;
- projection angulaire de huit silhouettes dans un même volume ;
- test JVM de `MultiViewHullProjector` ;
- relief Depth Anything V2 sur face, dos et profil ;
- GLB HD sans limite mobile ;
- GLB mobile mesuré et limité à 1 000 000 octets ;
- aucune permission Internet et aucun composant cloud ;
- `lintDebug` et `assembleDebug` réussis ;
- signature et modèles ONNX vérifiés dans l'APK.
