#!/usr/bin/env python3
from pathlib import Path

MESHER = Path("app/src/main/java/com/chasmet/modeliseur3d/model/SmoothHullMesher.java")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: motif attendu 1 fois, trouvé {count}")
    return text.replace(old, new, 1)


text = MESHER.read_text(encoding="utf-8")

# Sur la grille extrême, l'ancien chemin gardait le MeshPart complet puis
# allouait positions/normales/UV une seconde fois dans merge(). Le pic mémoire
# arrivait donc précisément à la fin du MESHING. En V9.5.2 on compte d'abord
# le nombre exact de sommets puis on écrit directement dans les tableaux finaux.
old_workers = """        long voxelCount = (long) width * height * depth;
        int workerLimit = voxelCount >= 15_000_000L ? 1
                : voxelCount >= 12_000_000L ? 2 : 6;
        int workers = Math.max(1, Math.min(availableProcessors - 1, workerLimit));
"""
new_workers = """        long voxelCount = (long) width * height * depth;
        if (voxelCount >= 15_000_000L) {
            return buildFieldTwoPass(
                    field,
                    width,
                    height,
                    depth,
                    atlas,
                    isoLevel,
                    projectionGuide
            );
        }
        int workerLimit = voxelCount >= 12_000_000L ? 2 : 6;
        int workers = Math.max(1, Math.min(availableProcessors - 1, workerLimit));
"""
text = replace_once(text, old_workers, new_workers, "activation two-pass grille extrême")

old_polygonize_header = """    private static MeshPart polygonizeRange(
            float[] field,
            int width,
            int height,
            int depth,
            AtlasLayout atlas,
            float isoLevel,
            ProjectionGuide projectionGuide,
            int startY,
            int endY
    ) {
        MeshPart result = new MeshPart(32_768);
"""
new_polygonize_header = """    private static MeshPart polygonizeRange(
            float[] field,
            int width,
            int height,
            int depth,
            AtlasLayout atlas,
            float isoLevel,
            ProjectionGuide projectionGuide,
            int startY,
            int endY
    ) {
        return polygonizeRangeInto(
                field,
                width,
                height,
                depth,
                atlas,
                isoLevel,
                projectionGuide,
                startY,
                endY,
                new MeshPart(32_768)
        );
    }

    private static MeshPart polygonizeRangeInto(
            float[] field,
            int width,
            int height,
            int depth,
            AtlasLayout atlas,
            float isoLevel,
            ProjectionGuide projectionGuide,
            int startY,
            int endY,
            MeshPart result
    ) {
"""
text = replace_once(
    text,
    old_polygonize_header,
    new_polygonize_header,
    "polygonize vers buffers fournis",
)

marker = """    private static MeshPart polygonizeRange(
"""
two_pass_methods = r'''    private static MeshData buildFieldTwoPass(
            float[] field,
            int width,
            int height,
            int depth,
            AtlasLayout atlas,
            float isoLevel,
            ProjectionGuide projectionGuide
    ) {
        MemoryDiagnostics.mark("MESHING COUNT");
        long vertexCountLong = countVerticesRange(
                field,
                width,
                height,
                depth,
                isoLevel,
                0,
                height - 1
        );
        if (vertexCountLong <= 0L) {
            throw new IllegalArgumentException("Aucune surface propre n'a pu être extraite");
        }
        if (vertexCountLong > Integer.MAX_VALUE / 3L) {
            throw new OutOfMemoryError("Maillage trop grand pour les tableaux Java");
        }

        int vertexCount = (int) vertexCountLong;
        int positionCount = Math.multiplyExact(vertexCount, 3);
        int texCoordCount = Math.multiplyExact(vertexCount, 2);

        MemoryDiagnostics.mark("MESHING ALLOC EXACT " + vertexCount + " sommets");
        float[] positions = new float[positionCount];
        float[] normals = new float[positionCount];
        float[] texCoords = new float[texCoordCount];

        MeshPart direct = new MeshPart(positions, normals, texCoords);
        MemoryDiagnostics.mark("MESHING WRITE DIRECT");
        polygonizeRangeInto(
                field,
                width,
                height,
                depth,
                atlas,
                isoLevel,
                projectionGuide,
                0,
                height - 1,
                direct
        );
        if (direct.vertexCount() != vertexCount) {
            throw new IllegalStateException(
                    "Meshing two-pass incohérent : attendu " + vertexCount
                            + ", écrit " + direct.vertexCount()
            );
        }

        // Les indices sont séquentiels car marching tetrahedra émet encore
        // trois sommets par triangle. Ils sont alloués seulement après la
        // géométrie afin d'éviter tout buffer intermédiaire supplémentaire.
        int[] indices = new int[vertexCount];
        for (int i = 0; i < vertexCount; i++) {
            indices[i] = i;
        }
        MemoryDiagnostics.mark("MESHING TWO-PASS OK");
        return new MeshData(positions, normals, texCoords, indices);
    }

    private static long countVerticesRange(
            float[] field,
            int width,
            int height,
            int depth,
            float isoLevel,
            int startY,
            int endY
    ) {
        long vertices = 0L;
        float[] cubeValues = new float[8];
        for (int y = startY; y < endY; y++) {
            for (int x = 0; x < width - 1; x++) {
                for (int z = 0; z < depth - 1; z++) {
                    float minimum = Float.POSITIVE_INFINITY;
                    float maximum = Float.NEGATIVE_INFINITY;
                    for (int corner = 0; corner < 8; corner++) {
                        int cx = x + CORNER_OFFSETS[corner][0];
                        int cy = y + CORNER_OFFSETS[corner][1];
                        int cz = z + CORNER_OFFSETS[corner][2];
                        float value = field[index(cx, cy, cz, width, depth)];
                        cubeValues[corner] = value;
                        minimum = Math.min(minimum, value);
                        maximum = Math.max(maximum, value);
                    }
                    if (minimum >= isoLevel || maximum < isoLevel) {
                        continue;
                    }
                    for (int[] tetrahedron : TETRAHEDRA) {
                        int intersectionCount = 0;
                        for (int[] edge : TETRA_EDGES) {
                            int cornerA = tetrahedron[edge[0]];
                            int cornerB = tetrahedron[edge[1]];
                            float valueA = cubeValues[cornerA];
                            float valueB = cubeValues[cornerB];
                            if ((valueA >= isoLevel) != (valueB >= isoLevel)) {
                                intersectionCount++;
                            }
                        }
                        if (intersectionCount == 3) {
                            vertices += 3L;
                        } else if (intersectionCount == 4) {
                            vertices += 6L;
                        }
                    }
                }
            }
        }
        return vertices;
    }

'''
text = replace_once(text, marker, two_pass_methods + marker, "méthodes two-pass")

old_mesh_part = """    private static final class MeshPart {
        final FloatBuilder positions;
        final FloatBuilder normals;
        final FloatBuilder texCoords;

        MeshPart(int initialVertexCapacity) {
            positions = new FloatBuilder(initialVertexCapacity * 3);
            normals = new FloatBuilder(initialVertexCapacity * 3);
            texCoords = new FloatBuilder(initialVertexCapacity * 2);
        }
    }
"""
new_mesh_part = """    private static final class MeshPart {
        final FloatBuilder positions;
        final FloatBuilder normals;
        final FloatBuilder texCoords;

        MeshPart(int initialVertexCapacity) {
            positions = new FloatBuilder(initialVertexCapacity * 3);
            normals = new FloatBuilder(initialVertexCapacity * 3);
            texCoords = new FloatBuilder(initialVertexCapacity * 2);
        }

        MeshPart(float[] positions, float[] normals, float[] texCoords) {
            this.positions = new FloatBuilder(positions);
            this.normals = new FloatBuilder(normals);
            this.texCoords = new FloatBuilder(texCoords);
        }

        int vertexCount() {
            return positions.size() / 3;
        }
    }
"""
text = replace_once(text, old_mesh_part, new_mesh_part, "MeshPart direct")

old_float_fields = """    static final class FloatBuilder {
        private float[] values;
        private int size;

        FloatBuilder(int initialCapacity) {
            values = new float[Math.max(16, initialCapacity)];
        }
"""
new_float_fields = """    static final class FloatBuilder {
        private float[] values;
        private int size;
        private final boolean fixed;

        FloatBuilder(int initialCapacity) {
            values = new float[Math.max(16, initialCapacity)];
            fixed = false;
        }

        FloatBuilder(float[] target) {
            if (target == null) {
                throw new IllegalArgumentException("Buffer direct null");
            }
            values = target;
            fixed = true;
        }
"""
text = replace_once(text, old_float_fields, new_float_fields, "FloatBuilder direct")

old_ensure = """        private void ensure(int additional) {
            int required = size + additional;
            if (required > values.length) {
                values = Arrays.copyOf(values, Math.max(required, values.length * 2));
            }
        }
"""
new_ensure = """        private void ensure(int additional) {
            int required = size + additional;
            if (required > values.length) {
                if (fixed) {
                    throw new IllegalStateException(
                            "Buffer meshing exact dépassé : " + required + "/" + values.length
                    );
                }
                values = Arrays.copyOf(values, Math.max(required, values.length * 2));
            }
        }
"""
text = replace_once(text, old_ensure, new_ensure, "FloatBuilder exact sans croissance")

MESHER.write_text(text, encoding="utf-8")
print(
    "V9.5.2 two-pass mesher applied: grille extrême comptée puis écrite directement; "
    "aucun MeshPart géant dupliqué par merge; résolution et triangles conservés"
)
