# Génération TRELLIS.2 et GLB animé compressé

Cette chaîne s’exécute sur un ordinateur ou GitHub Actions. Elle n’installe pas
TRELLIS.2 sur Android : le modèle de génération tourne sur le GPU de la démo
Microsoft/Hugging Face. Aucun achat ni création de serveur ne sont déclenchés.

## Depuis GitHub Actions

Ouvrir **Actions → Générer, animer et compresser un GLB → Run workflow**.
Renseigner une seule source HTTPS publique : `image_url` pour générer un modèle,
ou `glb_url` pour traiter un modèle déjà créé. Le fichier final se récupère dans
l’artefact **Modeliseur-GLB-compresse**. Les images personnelles n’ont pas à être
ajoutées au dépôt public ; utiliser le client local pour des références privées.

La démo est soumise à disponibilité et quota. Une génération réussie ne garantit
pas l’export, qui consomme aussi du GPU. Les refus de quota arrêtent immédiatement
le workflow, sans changer d’adresse, de compte ou de fournisseur pour les contourner.
Un aperçu est conservé quand la génération a réussi et que l’export est refusé.
GitHub Actions n’apporte aucun GPU supplémentaire et ne contourne pas cette limite.

## Client local

```bash
cd tools/trellis
python -m pip install -r requirements.txt
npm ci
python generate.py --image /chemin/personnage.png --output output
node finish.mjs output/generated.glb output/personnage_anime_compresse.glb --humanoid
```

`generate.py --resume` reprend l’étape enregistrée dans le même dossier. Le client
conserve le hash de la référence, la session et l’aperçu avant de demander l’export.
La reprise de l’export n’est possible que tant que les latents restent présents sur
le service : la session distante peut expirer rapidement. Le checkpoint ne permet
pas de récupérer ces latents après leur suppression. Ne pas publier `checkpoint.json`,
les références, le cache ou un token. Un `HF_TOKEN` peut être fourni exclusivement
par l’environnement serveur ; il est facultatif et n’est pas intégré à l’application.

## Rigging et animations

Le mode `--humanoid` conserve tout squelette déjà présent. Sinon, il ajoute un rig
de 21 articulations et un skinning normalisé à un personnage complet, debout,
axe Y vertical, bras abaissés ou légèrement écartés. Les maillages animés sans
squelette, les morph targets et les hiérarchies incompatibles sont refusés.

Animations ajoutées : repos, marche, course, saut, chute, atterrissage, attaque,
défense, esquive, nage, montée, monte, conduite, pilotage, rap et salut. Les boucles
ont des poses initiale/finale identiques ; saut, atterrissage, attaque et esquive
sont des actions ponctuelles. Ces mouvements sont **procéduraux et approximatifs**,
pas issus d’une capture de mouvement. Vérifier les contacts et déformations en jeu.
La reconnaissance de silhouette n’est pas un détecteur sémantique d’humain.

Le rigging conserve la forme de repos, l’apparence, les matériaux et les textures.
TRELLIS.2 reconstruit depuis une image ; il ne garantit ni une ressemblance exacte
sur les parties invisibles, ni un véritable scan mesuré.

## Compression et compatibilité

Compression `EXT_meshopt_compression` obligatoire. Aucun changement de résolution
ou encodage des textures, aucune simplification, aucune quantification supplémentaire
ni filtre Meshopt destructif. Après export, le fichier est relu et comparé :
positions, attributs, triangles, skinning, animations et octets des images.
La validation glTF et la réduction réelle de taille sont exigées avant livraison.
La compression seule peut être utilisée sans `--humanoid`.

Le moteur de destination doit prendre en charge **EXT_meshopt_compression**.
Avec Three.js : `GLTFLoader.setMeshoptDecoder(MeshoptDecoder)`.
Les moteurs sans ce décodeur doivent recevoir le GLB source, ou ajouter le décodeur.
Les formats Draco, KTX2/Basis et autres extensions nécessitant des décodeurs non
installés sont refusés par l’import au lieu d’être silencieusement dégradés.

## Contrôles

```bash
npm test
python -m unittest discover -s test -p 'test_*.py'
```

Les tests couvrent un GLB synthétique, le skinning, les boucles et actions, la
compression relue et un export refusé pour quota. Ils n’appellent pas le service GPU.
Ils ne remplacent pas l’inspection visuelle d’un personnage réel.

Sources officielles :
- [TRELLIS.2](https://github.com/microsoft/TRELLIS.2)
- [Démo Microsoft](https://huggingface.co/spaces/microsoft/TRELLIS.2)
- [Client Gradio](https://www.gradio.app/guides/getting-started-with-the-python-client)
- [Extension Meshopt](https://github.com/KhronosGroup/glTF/blob/main/extensions/2.0/Vendor/EXT_meshopt_compression/README.md)
