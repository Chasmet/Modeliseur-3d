# Tripo Fusion 360° — Mode quatre vues hors connexion

## Accès

Accueil → onglet **Fusion 360°** → **Tripo Fusion 360°**.

## Entrées

Importer quatre photos du **même sujet, dans la même pose et à distance comparable** :

1. Face
2. Dos
3. Profil droit
4. Profil gauche

Choisir **Précis**, **Puissance maximale** et **Assistance profondeur** selon la mémoire disponible. L'application sauvegarde localement les photos et les résultats précédents.

## Traitement réel

1. Import HD (jusqu'à 2048 px si le tas Java le permet), orientation et détourage IS-Net.
2. Renforcement léger facultatif des images avant TripoSR, sans modifier les photos de texture.
3. Quatre champs appris TripoSR indépendants (jusqu'à 112³ dans le modèle déjà embarqué), avec cache et vérification de l'interruption.
4. Recalage des silhouettes opposées. Les silhouettes réellement photographiées protègent le visage, les membres et les autres formes principales contre les désaccords entre les quatre prédictions neuronales. Lorsqu'une seule photo est légèrement décalée, les trois vues concordantes peuvent maintenir la silhouette. Le maillage reste adaptatif et borné en triangles (jusqu'à 160 cellules par axe sous budget).
5. Depth Anything V2 Small hors ligne sur les **quatre** images, **désactivé par défaut car expérimental**. Seuls les détails relatifs à haute fréquence des sommets visibles peuvent être affinés, avec déplacement borné à 0,003 unité et contrôle d'orientation des triangles.
6. Projection des vraies photos selon leur visibilité, raccords sur l'atlas et export GLB dans la bibliothèque habituelle.

En cas de profondeur indisponible, le calcul garde le moteur quatre vues TripoSR sans ce relief. L'option de profondeur est désactivable.

**Limites** : il ne s'agit pas de photogrammétrie métrique ; un visage, un pelage et les zones invisibles peuvent rester approximatifs. Le nombre accru de triangles ne garantit pas une meilleure ressemblance.

## Versions et conservation

Modéliseur 3D 6.6.6, Java, compileSdk 34, targetSdk 34, minSdk 21.
Le package Android et la clé de signature doivent rester identiques aux versions 6.6.x pour conserver les données.
Les anciennes activités 2.5D, 3D, TripoSR 1 image, galerie et mise à jour automatique sont maintenues.

## Vérifications

Tests sans poids volumineux : `./gradlew -PtripoSourceCheckOnly -PofflineSmoke :app:compileDebugJavaWithJavac :app:testDebugUnitTest --tests com.chasmet.modeliseur3d.TripoFusion360AndroidTest`.

APK complet : `./gradlew assembleDebug` après récupération/vérification des poids locaux et de la signature Android originale ; publication GitHub Actions depuis la branche de publication existante.
