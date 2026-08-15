#!/usr/bin/env python3
from pathlib import Path

ENGINE = Path("app/src/main/java/com/chasmet/modeliseur3d/model/StylizedCharacter3DEngine.java")
MESHER = Path("app/src/main/java/com/chasmet/modeliseur3d/model/SmoothHullMesher.java")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: motif attendu 1 fois, trouvé {count}")
    return text.replace(old, new, 1)


engine = ENGINE.read_text(encoding="utf-8")

# Imports pour le spool texture sans perte sur disque.
engine = replace_once(
    engine,
    "import android.graphics.Bitmap;\n",
    "import android.graphics.Bitmap;\nimport android.graphics.BitmapFactory;\n",
    "import BitmapFactory",
)
engine = replace_once(
    engine,
    "import java.util.List;\n",
    "import java.io.File;\nimport java.io.FileOutputStream;\nimport java.io.IOException;\nimport java.util.List;\n",
    "imports fichiers texture",
)

# Dès qu'une vue a été segmentée et isolée, le bitmap d'entrée 1600px n'est
# plus utile. Le List externe le recyclera à nouveau sans danger dans finally.
old_seg = """                AnimeSegmentationEngine.Mask neuralMask = segmentation.segment(views.get(index));
                isolated[index] = NeuralSheetIsolator.isolate(views.get(index), neuralMask);
                bounds[index] = findForegroundBounds(isolated[index]);
"""
new_seg = """                Bitmap sourceView = views.get(index);
                AnimeSegmentationEngine.Mask neuralMask = segmentation.segment(sourceView);
                isolated[index] = NeuralSheetIsolator.isolate(sourceView, neuralMask);
                bounds[index] = findForegroundBounds(isolated[index]);
                if (sourceView != isolated[index] && !sourceView.isRecycled()) {
                    sourceView.recycle();
                }
"""
engine = replace_once(engine, old_seg, new_seg, "recyclage vues entrée après segmentation")

# Après DA3, les bitmaps détourés ne servent plus qu'à la texture. On les
# transforme une seule fois à la résolution exacte de l'atlas et on les écrit
# en PNG lossless dans le cache. La grille 3D et le meshing ne cohabitent donc
# plus avec huit gros bitmaps (4 entrées + 4 isolés).
old_after_depth = """        notifyProgress(listener, Stage.CLEANING, REQUIRED_VIEW_COUNT, REQUIRED_VIEW_COUNT);
        releaseMemory();
        MemoryDiagnostics.mark("COQUE 3D");
"""
new_after_depth = """        SmoothHullMesher.AtlasLayout layout = SmoothHullMesher.AtlasLayout.create(
                profile.width,
                profile.height,
                profile.depth,
                profile.atlasHeight
        );
        MemoryDiagnostics.mark("TEXTURE SPOOL LOSSLESS");
        TextureSpool textureSpool = TextureSpool.capture(
                context,
                isolated,
                bounds,
                layout,
                profileCorrection.shouldFlipLeft()
        );
        recycleAll(isolated);
        isolated = null;

        notifyProgress(listener, Stage.CLEANING, REQUIRED_VIEW_COUNT, REQUIRED_VIEW_COUNT);
        releaseMemory();
        MemoryDiagnostics.mark("COQUE 3D");
"""
engine = replace_once(engine, old_after_depth, new_after_depth, "spool texture avant coque")

# Le layout existe désormais avant la coque.
old_layout = """        SmoothHullMesher.AtlasLayout layout = SmoothHullMesher.AtlasLayout.create(
                profile.width,
                profile.height,
                profile.depth,
                profile.atlasHeight
        );

        // V9.5 : le maillage passe AVANT l'atlas. Une texture 4096 x 4096 ARGB
"""
new_layout = """        // V9.5.1 : le layout et les textures ont déjà été préparés en spool
        // lossless avant l'allocation de la grille dense.

        // V9.5 : le maillage passe AVANT l'atlas. Une texture 4096 x 4096 ARGB
"""
engine = replace_once(engine, old_layout, new_layout, "suppression second layout")

# Après meshing, on reconstruit l'atlas en décodant une seule cellule PNG à la
# fois. On ne garde jamais front+back+right+left simultanément en RAM.
old_atlas = """        Bitmap atlas;
        try {
            atlas = buildAtlas(
                    isolated,
                    bounds,
                    layout,
                    profileCorrection.shouldFlipLeft()
            );
        } finally {
            recycleAll(isolated);
        }
        notifyProgress(listener, Stage.TEXTURING, 1, 1);
"""
new_atlas = """        Bitmap atlas;
        try {
            atlas = textureSpool.buildAtlas(layout);
        } finally {
            textureSpool.close();
        }
        notifyProgress(listener, Stage.TEXTURING, 1, 1);
"""
engine = replace_once(engine, old_atlas, new_atlas, "atlas depuis spool")

# Ajoute le spool juste avant notifyProgress. Les PNG sont lossless et chaque
# cellule est déjà normalisée exactement comme l'ancien buildAtlas.
marker = """    private static void notifyProgress(
"""
spool_class = r'''    private static final class TextureSpool implements AutoCloseable {
        private final File directory;
        private final File[] files;

        private TextureSpool(File directory, File[] files) {
            this.directory = directory;
            this.files = files;
        }

        static TextureSpool capture(
                Context context,
                Bitmap[] views,
                Rect[] bounds,
                SmoothHullMesher.AtlasLayout layout,
                boolean flipLeft
        ) throws IOException {
            File directory = new File(context.getCacheDir(), "modeliseur_texture_spool");
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IOException("Cache texture indisponible");
            }
            File[] oldFiles = directory.listFiles();
            if (oldFiles != null) {
                for (File old : oldFiles) {
                    if (old != null) {
                        old.delete();
                    }
                }
            }

            int[] viewOrder = {
                    StylizedFourViewProjector.FRONT,
                    StylizedFourViewProjector.BACK,
                    StylizedFourViewProjector.RIGHT,
                    StylizedFourViewProjector.LEFT
            };
            int[] widths = {
                    layout.frontWidth,
                    layout.frontWidth,
                    layout.sideWidth,
                    layout.sideWidth
            };
            File[] files = new File[REQUIRED_VIEW_COUNT];
            try {
                for (int slot = 0; slot < viewOrder.length; slot++) {
                    int view = viewOrder[slot];
                    Bitmap texture = normalizedTexture(
                            views[view],
                            bounds[view],
                            widths[slot],
                            layout.atlasHeight,
                            view == StylizedFourViewProjector.LEFT && flipLeft
                    );
                    File file = new File(directory, "cell_" + slot + ".png");
                    files[slot] = file;
                    try (FileOutputStream output = new FileOutputStream(file)) {
                        if (!texture.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                            throw new IOException("Encodage texture PNG impossible");
                        }
                        output.flush();
                    } finally {
                        recycle(texture);
                    }
                    MemoryDiagnostics.mark("TEXTURE SPOOL " + (slot + 1) + "/4");
                    releaseMemory();
                }
                return new TextureSpool(directory, files);
            } catch (IOException | RuntimeException | OutOfMemoryError error) {
                for (File file : files) {
                    if (file != null) {
                        file.delete();
                    }
                }
                throw error;
            }
        }

        Bitmap buildAtlas(SmoothHullMesher.AtlasLayout layout) throws IOException {
            Bitmap atlas = Bitmap.createBitmap(
                    layout.atlasWidth,
                    layout.atlasHeight,
                    Bitmap.Config.ARGB_8888
            );
            Canvas canvas = new Canvas(atlas);
            canvas.drawColor(Color.rgb(24, 26, 32));
            Paint paint = new Paint(
                    Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG
            );
            int[] starts = {
                    layout.frontStart,
                    layout.backStart,
                    layout.rightStart,
                    layout.leftStart
            };
            int[] widths = {
                    layout.frontWidth,
                    layout.frontWidth,
                    layout.sideWidth,
                    layout.sideWidth
            };
            try {
                for (int slot = 0; slot < files.length; slot++) {
                    Bitmap cell = BitmapFactory.decodeFile(files[slot].getAbsolutePath());
                    if (cell == null) {
                        throw new IOException("Texture spool illisible : cellule " + slot);
                    }
                    try {
                        drawCell(
                                canvas,
                                paint,
                                cell,
                                starts[slot],
                                widths[slot],
                                layout.atlasHeight
                        );
                    } finally {
                        recycle(cell);
                    }
                    MemoryDiagnostics.mark("TEXTURE STREAM " + (slot + 1) + "/4");
                    releaseMemory();
                }
                return atlas;
            } catch (IOException | RuntimeException | OutOfMemoryError error) {
                recycle(atlas);
                throw error;
            }
        }

        @Override
        public void close() {
            for (File file : files) {
                if (file != null) {
                    file.delete();
                }
            }
            File[] leftovers = directory.listFiles();
            if (leftovers == null || leftovers.length == 0) {
                directory.delete();
            }
        }
    }

'''
engine = replace_once(engine, marker, spool_class + marker, "classe TextureSpool")
ENGINE.write_text(engine, encoding="utf-8")

# Le maillage reste strictement identique, mais sur la grille extrême il est
# exécuté avec un seul worker. Cela retire les MeshPart/buffers simultanés qui
# font exploser le kart complexe. Coût : temps CPU, pas qualité.
mesher = MESHER.read_text(encoding="utf-8")
mesher = replace_once(
    mesher,
    "        int workerLimit = voxelCount >= 12_000_000L ? 3 : 6;\n",
    "        int workerLimit = voxelCount >= 15_000_000L ? 1\n"
    "                : voxelCount >= 12_000_000L ? 2 : 6;\n",
    "meshing mono-worker grille extrême",
)
MESHER.write_text(mesher, encoding="utf-8")

print(
    "V9.5.1 kart memory streaming applied: inputs recycled after segmentation; "
    "textures PNG lossless spooled before hull; isolated bitmaps freed before dense grid; "
    "atlas decoded one cell at a time; extreme meshing uses one worker without quality loss"
)
