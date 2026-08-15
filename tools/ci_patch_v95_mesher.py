from pathlib import Path
import re

MESHER = Path("app/src/main/java/com/chasmet/modeliseur3d/model/SmoothHullMesher.java")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: motif attendu 1 fois, trouvé {count}")
    return text.replace(old, new, 1)


text = MESHER.read_text(encoding="utf-8")

# V9.5 : trois float[] de taille volume pour gx/gy/gz coûtaient environ trois
# fois la densité. Les mêmes différences centrées sont désormais calculées
# uniquement aux intersections de surface, sans modifier la normale obtenue.
text = replace_once(
    text,
    "        GradientField gradient = createGradientField(field, width, height, depth);\n\n"
    "        int workers = Math.max(1, Math.min(availableProcessors - 1, 10));\n",
    "        // V9.5 zero-copy : pas de GradientField 3D préalloué.\n"
    "        long voxelCount = (long) width * height * depth;\n"
    "        int workerLimit = voxelCount >= 12_000_000L ? 3 : 6;\n"
    "        int workers = Math.max(1, Math.min(availableProcessors - 1, workerLimit));\n",
    "suppression gradient global et limitation workers",
)
text = replace_once(
    text,
    "                    return polygonizeRange(\n"
    "                            field,\n"
    "                            gradient,\n"
    "                            width,\n",
    "                    return polygonizeRange(\n"
    "                            field,\n"
    "                            width,\n",
    "appel polygonize sans gradient global",
)

pattern = re.compile(
    r"    private static GradientField createGradientField\(float\[\] field, int width, int height, int depth\) \{.*?"
    r"        return new GradientField\(gx, gy, gz\);\n"
    r"    \}\n\n",
    re.S,
)
text, count = pattern.subn("", text, count=1)
if count != 1:
    raise SystemExit(f"suppression createGradientField: motif trouvé {count}")

text = replace_once(
    text,
    "    private static MeshPart polygonizeRange(\n"
    "            float[] field,\n"
    "            GradientField gradient,\n"
    "            int width,\n",
    "    private static MeshPart polygonizeRange(\n"
    "            float[] field,\n"
    "            int width,\n",
    "signature polygonize sans gradient global",
)
text = replace_once(
    text,
    "        int[] cubeIndices = new int[8];\n",
    "",
    "suppression cubeIndices",
)
text = replace_once(
    text,
    "                        cubeIndices[corner] = gridIndex;\n",
    "",
    "suppression écriture cubeIndices",
)

old_gradient_use = """                            int indexA = cubeIndices[cornerA];
                            int indexB = cubeIndices[cornerB];
                            float gridGx = lerp(gradient.x[indexA], gradient.x[indexB], t);
                            float gridGy = lerp(gradient.y[indexA], gradient.y[indexB], t);
                            float gridGz = lerp(gradient.z[indexA], gradient.z[indexB], t);
"""
new_gradient_use = """                            int gradientAX = x + CORNER_OFFSETS[cornerA][0];
                            int gradientAY = y + CORNER_OFFSETS[cornerA][1];
                            int gradientAZ = z + CORNER_OFFSETS[cornerA][2];
                            int gradientBX = x + CORNER_OFFSETS[cornerB][0];
                            int gradientBY = y + CORNER_OFFSETS[cornerB][1];
                            int gradientBZ = z + CORNER_OFFSETS[cornerB][2];
                            float gridGx = lerp(
                                    gradientX(field, gradientAX, gradientAY, gradientAZ,
                                            width, height, depth),
                                    gradientX(field, gradientBX, gradientBY, gradientBZ,
                                            width, height, depth),
                                    t
                            );
                            float gridGy = lerp(
                                    gradientY(field, gradientAX, gradientAY, gradientAZ,
                                            width, height, depth),
                                    gradientY(field, gradientBX, gradientBY, gradientBZ,
                                            width, height, depth),
                                    t
                            );
                            float gridGz = lerp(
                                    gradientZ(field, gradientAX, gradientAY, gradientAZ,
                                            width, height, depth),
                                    gradientZ(field, gradientBX, gradientBY, gradientBZ,
                                            width, height, depth),
                                    t
                            );
"""
text = replace_once(
    text,
    old_gradient_use,
    new_gradient_use,
    "gradient local aux intersections",
)

helpers = """    private static float gradientX(
            float[] field,
            int x,
            int y,
            int z,
            int width,
            int height,
            int depth
    ) {
        int x0 = Math.max(0, x - 1);
        int x1 = Math.min(width - 1, x + 1);
        return field[index(x1, y, z, width, depth)]
                - field[index(x0, y, z, width, depth)];
    }

    private static float gradientY(
            float[] field,
            int x,
            int y,
            int z,
            int width,
            int height,
            int depth
    ) {
        int y0 = Math.max(0, y - 1);
        int y1 = Math.min(height - 1, y + 1);
        return field[index(x, y1, z, width, depth)]
                - field[index(x, y0, z, width, depth)];
    }

    private static float gradientZ(
            float[] field,
            int x,
            int y,
            int z,
            int width,
            int height,
            int depth
    ) {
        int z0 = Math.max(0, z - 1);
        int z1 = Math.min(depth - 1, z + 1);
        return field[index(x, y, z1, width, depth)]
                - field[index(x, y, z0, width, depth)];
    }

"""
text = replace_once(
    text,
    "    private static int[] orderQuad(\n",
    helpers + "    private static int[] orderQuad(\n",
    "helpers gradients locaux",
)

# Évite trois allocations temporaires par MeshPart pendant la fusion finale.
old_merge = """        for (MeshPart part : parts) {
            float[] partPositions = part.positions.toArray();
            float[] partNormals = part.normals.toArray();
            float[] partTexCoords = part.texCoords.toArray();
            System.arraycopy(partPositions, 0, positions, positionOffset, partPositions.length);
            System.arraycopy(partNormals, 0, normals, normalOffset, partNormals.length);
            System.arraycopy(partTexCoords, 0, texCoords, texCoordOffset, partTexCoords.length);
            positionOffset += partPositions.length;
            normalOffset += partNormals.length;
            texCoordOffset += partTexCoords.length;
        }
"""
new_merge = """        for (MeshPart part : parts) {
            part.positions.copyTo(positions, positionOffset);
            part.normals.copyTo(normals, normalOffset);
            part.texCoords.copyTo(texCoords, texCoordOffset);
            positionOffset += part.positions.size();
            normalOffset += part.normals.size();
            texCoordOffset += part.texCoords.size();
        }
"""
text = replace_once(text, old_merge, new_merge, "fusion MeshPart sans toArray")

text = replace_once(
    text,
    "        float[] toArray() {\n"
    "            return Arrays.copyOf(values, size);\n"
    "        }\n\n",
    "        float[] toArray() {\n"
    "            return Arrays.copyOf(values, size);\n"
    "        }\n\n"
    "        void copyTo(float[] target, int offset) {\n"
    "            System.arraycopy(values, 0, target, offset, size);\n"
    "        }\n\n",
    "FloatBuilder copyTo",
)

# La classe GradientField n'a plus aucune raison d'exister.
pattern = re.compile(
    r"    private static final class GradientField \{.*?"
    r"    \}\n\n"
    r"    private static final class MeshPart",
    re.S,
)
text, count = pattern.subn("    private static final class MeshPart", text, count=1)
if count != 1:
    raise SystemExit(f"suppression classe GradientField: motif trouvé {count}")

MESHER.write_text(text, encoding="utf-8")
print(
    "V9.5 mesher patch applied: 3 gradients 3D supprimés; gradients locaux exacts; "
    "workers limités sur grosse grille; fusion MeshPart sans copies temporaires"
)
