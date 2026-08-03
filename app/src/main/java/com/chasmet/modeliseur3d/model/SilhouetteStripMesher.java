package com.chasmet.modeliseur3d.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Construit une surface continue à partir de bandes de silhouettes.
 *
 * Chaque vue fournit, pour chaque hauteur, une borne gauche et une borne droite.
 * Ces bandes deviennent des demi-plans 2D dont l'intersection forme une coupe
 * convexe. La coupe est ensuite rééchantillonnée en anneau régulier afin de
 * produire un maillage étanche sans voxel ni cubes visibles.
 */
final class SilhouetteStripMesher {
    private static final float EPSILON = 1.0e-5f;
    private static final float INITIAL_EXTENT = 1.35f;

    private SilhouetteStripMesher() {
    }

    static Sweep build(float[][] left, float[][] right, int sectors) {
        validate(left, right, sectors);
        int viewCount = left.length;
        int rows = left[0].length;
        float[] centersX = new float[rows];
        float[] centersZ = new float[rows];
        float[] radii = new float[rows * sectors];

        for (int row = 0; row < rows; row++) {
            List<Point> polygon = initialPolygon();
            int usableViews = 0;
            for (int view = 0; view < viewCount; view++) {
                float minimum = left[view][row];
                float maximum = right[view][row];
                if (!Float.isFinite(minimum)
                        || !Float.isFinite(maximum)
                        || maximum - minimum < 0.012f) {
                    continue;
                }
                usableViews++;
                double angle = Math.PI * 2.0 * view / viewCount;
                float nx = (float) Math.cos(angle);
                float nz = (float) Math.sin(angle);
                polygon = clip(polygon, nx, nz, maximum);
                polygon = clip(polygon, -nx, -nz, -minimum);
                if (polygon.size() < 3) {
                    break;
                }
            }

            if (usableViews < 2 || polygon.size() < 3) {
                polygon = fallbackPolygon(left, right, row, viewCount);
            }

            Point center = centroid(polygon);
            centersX[row] = center.x;
            centersZ[row] = center.z;
            for (int sector = 0; sector < sectors; sector++) {
                double angle = Math.PI * 2.0 * sector / sectors;
                float dx = (float) Math.cos(angle);
                float dz = (float) Math.sin(angle);
                float radius = rayRadius(polygon, center, dx, dz);
                radii[row * sectors + sector] = Math.max(0.012f, radius);
            }
        }

        repairInvalidRows(centersX, centersZ, radii, rows, sectors);
        smoothCenters(centersX, centersZ);
        smoothRadii(radii, rows, sectors, 2);
        taperEnds(radii, rows, sectors);
        return createSweep(centersX, centersZ, radii, rows, sectors);
    }

    private static void validate(float[][] left, float[][] right, int sectors) {
        if (left == null || right == null || left.length < 2
                || left.length != right.length) {
            throw new IllegalArgumentException("Bandes de silhouettes invalides");
        }
        if (sectors < 12 || sectors > 96) {
            throw new IllegalArgumentException("Nombre de secteurs invalide");
        }
        int rows = left[0].length;
        if (rows < 12) {
            throw new IllegalArgumentException("Nombre de lignes insuffisant");
        }
        for (int view = 0; view < left.length; view++) {
            if (left[view] == null || right[view] == null
                    || left[view].length != rows
                    || right[view].length != rows) {
                throw new IllegalArgumentException("Dimensions de bandes incohérentes");
            }
        }
    }

    private static List<Point> initialPolygon() {
        List<Point> polygon = new ArrayList<>(4);
        polygon.add(new Point(-INITIAL_EXTENT, -INITIAL_EXTENT));
        polygon.add(new Point(INITIAL_EXTENT, -INITIAL_EXTENT));
        polygon.add(new Point(INITIAL_EXTENT, INITIAL_EXTENT));
        polygon.add(new Point(-INITIAL_EXTENT, INITIAL_EXTENT));
        return polygon;
    }

    private static List<Point> clip(
            List<Point> input,
            float nx,
            float nz,
            float limit
    ) {
        if (input.isEmpty()) {
            return input;
        }
        List<Point> output = new ArrayList<>(input.size() + 2);
        Point previous = input.get(input.size() - 1);
        float previousDistance = dot(previous, nx, nz) - limit;
        boolean previousInside = previousDistance <= EPSILON;

        for (Point current : input) {
            float currentDistance = dot(current, nx, nz) - limit;
            boolean currentInside = currentDistance <= EPSILON;
            if (currentInside != previousInside) {
                float denominator = previousDistance - currentDistance;
                float amount = Math.abs(denominator) < EPSILON
                        ? 0.5f
                        : previousDistance / denominator;
                amount = clamp(amount, 0.0f, 1.0f);
                output.add(new Point(
                        previous.x + (current.x - previous.x) * amount,
                        previous.z + (current.z - previous.z) * amount
                ));
            }
            if (currentInside) {
                output.add(current);
            }
            previous = current;
            previousDistance = currentDistance;
            previousInside = currentInside;
        }
        return output;
    }

    private static float dot(Point point, float nx, float nz) {
        return point.x * nx + point.z * nz;
    }

    private static List<Point> fallbackPolygon(
            float[][] left,
            float[][] right,
            int row,
            int viewCount
    ) {
        float[] widths = new float[viewCount];
        float[] centers = new float[viewCount];
        int count = 0;
        for (int view = 0; view < viewCount; view++) {
            float minimum = left[view][row];
            float maximum = right[view][row];
            if (Float.isFinite(minimum) && Float.isFinite(maximum)
                    && maximum > minimum) {
                widths[count] = (maximum - minimum) * 0.5f;
                centers[count] = (maximum + minimum) * 0.5f;
                count++;
            }
        }
        float radius = count == 0 ? 0.08f : median(widths, count);
        float center = count == 0 ? 0.0f : median(centers, count);
        radius = Math.max(0.025f, radius);
        List<Point> polygon = new ArrayList<>(24);
        for (int index = 0; index < 24; index++) {
            double angle = Math.PI * 2.0 * index / 24.0;
            polygon.add(new Point(
                    center + radius * (float) Math.cos(angle),
                    radius * (float) Math.sin(angle)
            ));
        }
        return polygon;
    }

    private static float median(float[] values, int count) {
        float[] copy = Arrays.copyOf(values, count);
        Arrays.sort(copy);
        int middle = count / 2;
        return (count & 1) == 0
                ? (copy[middle - 1] + copy[middle]) * 0.5f
                : copy[middle];
    }

    private static Point centroid(List<Point> polygon) {
        float signedArea = 0.0f;
        float centerX = 0.0f;
        float centerZ = 0.0f;
        for (int index = 0; index < polygon.size(); index++) {
            Point first = polygon.get(index);
            Point second = polygon.get((index + 1) % polygon.size());
            float cross = first.x * second.z - second.x * first.z;
            signedArea += cross;
            centerX += (first.x + second.x) * cross;
            centerZ += (first.z + second.z) * cross;
        }
        if (Math.abs(signedArea) < EPSILON) {
            centerX = 0.0f;
            centerZ = 0.0f;
            for (Point point : polygon) {
                centerX += point.x;
                centerZ += point.z;
            }
            float denominator = Math.max(1, polygon.size());
            return new Point(centerX / denominator, centerZ / denominator);
        }
        float denominator = 3.0f * signedArea;
        return new Point(centerX / denominator, centerZ / denominator);
    }

    private static float rayRadius(
            List<Point> polygon,
            Point center,
            float dx,
            float dz
    ) {
        float best = Float.POSITIVE_INFINITY;
        for (int index = 0; index < polygon.size(); index++) {
            Point first = polygon.get(index);
            Point second = polygon.get((index + 1) % polygon.size());
            float edgeX = second.x - first.x;
            float edgeZ = second.z - first.z;
            float offsetX = first.x - center.x;
            float offsetZ = first.z - center.z;
            float denominator = cross(dx, dz, edgeX, edgeZ);
            if (Math.abs(denominator) < EPSILON) {
                continue;
            }
            float distance = cross(offsetX, offsetZ, edgeX, edgeZ) / denominator;
            float edgeAmount = cross(offsetX, offsetZ, dx, dz) / denominator;
            if (distance >= 0.0f
                    && edgeAmount >= -EPSILON
                    && edgeAmount <= 1.0f + EPSILON) {
                best = Math.min(best, distance);
            }
        }
        return Float.isFinite(best) ? best : 0.04f;
    }

    private static float cross(float ax, float az, float bx, float bz) {
        return ax * bz - az * bx;
    }

    private static void repairInvalidRows(
            float[] centersX,
            float[] centersZ,
            float[] radii,
            int rows,
            int sectors
    ) {
        for (int row = 0; row < rows; row++) {
            boolean valid = Float.isFinite(centersX[row])
                    && Float.isFinite(centersZ[row]);
            for (int sector = 0; valid && sector < sectors; sector++) {
                valid = Float.isFinite(radii[row * sectors + sector]);
            }
            if (valid) {
                continue;
            }
            int replacement = nearestValidRow(
                    centersX,
                    centersZ,
                    radii,
                    rows,
                    sectors,
                    row
            );
            if (replacement < 0) {
                centersX[row] = 0.0f;
                centersZ[row] = 0.0f;
                Arrays.fill(
                        radii,
                        row * sectors,
                        (row + 1) * sectors,
                        0.05f
                );
            } else {
                centersX[row] = centersX[replacement];
                centersZ[row] = centersZ[replacement];
                System.arraycopy(
                        radii,
                        replacement * sectors,
                        radii,
                        row * sectors,
                        sectors
                );
            }
        }
    }

    private static int nearestValidRow(
            float[] centersX,
            float[] centersZ,
            float[] radii,
            int rows,
            int sectors,
            int target
    ) {
        for (int distance = 1; distance < rows; distance++) {
            int before = target - distance;
            if (before >= 0 && rowIsValid(
                    centersX,
                    centersZ,
                    radii,
                    sectors,
                    before
            )) {
                return before;
            }
            int after = target + distance;
            if (after < rows && rowIsValid(
                    centersX,
                    centersZ,
                    radii,
                    sectors,
                    after
            )) {
                return after;
            }
        }
        return -1;
    }

    private static boolean rowIsValid(
            float[] centersX,
            float[] centersZ,
            float[] radii,
            int sectors,
            int row
    ) {
        if (!Float.isFinite(centersX[row]) || !Float.isFinite(centersZ[row])) {
            return false;
        }
        for (int sector = 0; sector < sectors; sector++) {
            if (!Float.isFinite(radii[row * sectors + sector])) {
                return false;
            }
        }
        return true;
    }

    private static void smoothCenters(float[] x, float[] z) {
        for (int pass = 0; pass < 2; pass++) {
            float[] sourceX = Arrays.copyOf(x, x.length);
            float[] sourceZ = Arrays.copyOf(z, z.length);
            for (int row = 1; row < x.length - 1; row++) {
                x[row] = sourceX[row] * 0.60f
                        + (sourceX[row - 1] + sourceX[row + 1]) * 0.20f;
                z[row] = sourceZ[row] * 0.60f
                        + (sourceZ[row - 1] + sourceZ[row + 1]) * 0.20f;
            }
        }
    }

    private static void smoothRadii(
            float[] radii,
            int rows,
            int sectors,
            int passes
    ) {
        for (int pass = 0; pass < passes; pass++) {
            float[] source = Arrays.copyOf(radii, radii.length);
            for (int row = 0; row < rows; row++) {
                for (int sector = 0; sector < sectors; sector++) {
                    int previousSector = (sector + sectors - 1) % sectors;
                    int nextSector = (sector + 1) % sectors;
                    float value = source[row * sectors + sector] * 0.60f
                            + source[row * sectors + previousSector] * 0.10f
                            + source[row * sectors + nextSector] * 0.10f;
                    float weight = 0.80f;
                    if (row > 0) {
                        value += source[(row - 1) * sectors + sector] * 0.10f;
                        weight += 0.10f;
                    }
                    if (row + 1 < rows) {
                        value += source[(row + 1) * sectors + sector] * 0.10f;
                        weight += 0.10f;
                    }
                    radii[row * sectors + sector] = Math.max(
                            0.012f,
                            value / weight
                    );
                }
            }
        }
    }

    private static void taperEnds(float[] radii, int rows, int sectors) {
        int taperRows = Math.max(2, Math.min(6, rows / 18));
        for (int row = 0; row < taperRows; row++) {
            float amount = (row + 1.0f) / (taperRows + 1.0f);
            float scale = 0.16f + 0.84f * amount;
            int opposite = rows - 1 - row;
            for (int sector = 0; sector < sectors; sector++) {
                radii[row * sectors + sector] *= scale;
                radii[opposite * sectors + sector] *= scale;
            }
        }
    }

    private static Sweep createSweep(
            float[] centersX,
            float[] centersZ,
            float[] radii,
            int rows,
            int sectors
    ) {
        int ringVertexCount = rows * (sectors + 1);
        int topCenter = ringVertexCount;
        int bottomCenter = ringVertexCount + 1;
        int vertexCount = ringVertexCount + 2;
        float[] positions = new float[vertexCount * 3];
        float[] texCoords = new float[vertexCount * 2];
        float[] ringX = new float[rows * sectors];
        float[] ringZ = new float[rows * sectors];

        for (int row = 0; row < rows; row++) {
            float v = row / (float) Math.max(1, rows - 1);
            float y = 1.0f - 2.0f * v;
            for (int sector = 0; sector <= sectors; sector++) {
                int wrapped = sector % sectors;
                double angle = Math.PI * 2.0 * wrapped / sectors;
                float x = centersX[row]
                        + radii[row * sectors + wrapped] * (float) Math.cos(angle);
                float z = centersZ[row]
                        + radii[row * sectors + wrapped] * (float) Math.sin(angle);
                int vertex = row * (sectors + 1) + sector;
                positions[vertex * 3] = x;
                positions[vertex * 3 + 1] = y;
                positions[vertex * 3 + 2] = z;
                texCoords[vertex * 2] = sector / (float) sectors;
                texCoords[vertex * 2 + 1] = v;
                if (sector < sectors) {
                    ringX[row * sectors + sector] = x;
                    ringZ[row * sectors + sector] = z;
                }
            }
        }

        positions[topCenter * 3] = centersX[0];
        positions[topCenter * 3 + 1] = 1.0f;
        positions[topCenter * 3 + 2] = centersZ[0];
        texCoords[topCenter * 2] = 0.5f;
        texCoords[topCenter * 2 + 1] = 0.0f;

        positions[bottomCenter * 3] = centersX[rows - 1];
        positions[bottomCenter * 3 + 1] = -1.0f;
        positions[bottomCenter * 3 + 2] = centersZ[rows - 1];
        texCoords[bottomCenter * 2] = 0.5f;
        texCoords[bottomCenter * 2 + 1] = 1.0f;

        int sideIndexCount = (rows - 1) * sectors * 6;
        int capIndexCount = sectors * 6;
        int[] indices = new int[sideIndexCount + capIndexCount];
        int cursor = 0;
        for (int row = 0; row < rows - 1; row++) {
            int current = row * (sectors + 1);
            int next = (row + 1) * (sectors + 1);
            for (int sector = 0; sector < sectors; sector++) {
                int a = current + sector;
                int b = current + sector + 1;
                int c = next + sector;
                int d = next + sector + 1;
                indices[cursor++] = a;
                indices[cursor++] = c;
                indices[cursor++] = b;
                indices[cursor++] = b;
                indices[cursor++] = c;
                indices[cursor++] = d;
            }
        }
        for (int sector = 0; sector < sectors; sector++) {
            indices[cursor++] = topCenter;
            indices[cursor++] = sector + 1;
            indices[cursor++] = sector;
            int bottomStart = (rows - 1) * (sectors + 1);
            indices[cursor++] = bottomCenter;
            indices[cursor++] = bottomStart + sector;
            indices[cursor++] = bottomStart + sector + 1;
        }

        float[] normals = calculateNormals(positions, indices);
        MeshData mesh = new MeshData(positions, normals, texCoords, indices);
        return new Sweep(mesh, ringX, ringZ, rows, sectors);
    }

    private static float[] calculateNormals(float[] positions, int[] indices) {
        float[] normals = new float[positions.length];
        for (int index = 0; index < indices.length; index += 3) {
            int ia = indices[index] * 3;
            int ib = indices[index + 1] * 3;
            int ic = indices[index + 2] * 3;
            float abx = positions[ib] - positions[ia];
            float aby = positions[ib + 1] - positions[ia + 1];
            float abz = positions[ib + 2] - positions[ia + 2];
            float acx = positions[ic] - positions[ia];
            float acy = positions[ic + 1] - positions[ia + 1];
            float acz = positions[ic + 2] - positions[ia + 2];
            float nx = aby * acz - abz * acy;
            float ny = abz * acx - abx * acz;
            float nz = abx * acy - aby * acx;
            addNormal(normals, ia, nx, ny, nz);
            addNormal(normals, ib, nx, ny, nz);
            addNormal(normals, ic, nx, ny, nz);
        }
        for (int index = 0; index < normals.length; index += 3) {
            float nx = normals[index];
            float ny = normals[index + 1];
            float nz = normals[index + 2];
            float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (length < EPSILON) {
                normals[index] = 0.0f;
                normals[index + 1] = 1.0f;
                normals[index + 2] = 0.0f;
            } else {
                normals[index] = nx / length;
                normals[index + 1] = ny / length;
                normals[index + 2] = nz / length;
            }
        }
        return normals;
    }

    private static void addNormal(
            float[] normals,
            int offset,
            float x,
            float y,
            float z
    ) {
        normals[offset] += x;
        normals[offset + 1] += y;
        normals[offset + 2] += z;
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static final class Point {
        final float x;
        final float z;

        Point(float x, float z) {
            this.x = x;
            this.z = z;
        }
    }

    static final class Sweep {
        private final MeshData mesh;
        private final float[] ringX;
        private final float[] ringZ;
        private final int rows;
        private final int sectors;

        Sweep(
                MeshData mesh,
                float[] ringX,
                float[] ringZ,
                int rows,
                int sectors
        ) {
            this.mesh = mesh;
            this.ringX = ringX;
            this.ringZ = ringZ;
            this.rows = rows;
            this.sectors = sectors;
        }

        MeshData getMesh() {
            return mesh;
        }

        int getRows() {
            return rows;
        }

        int getSectors() {
            return sectors;
        }

        int getSurfaceSampleCount() {
            return rows * sectors;
        }

        float sampleX(float normalizedU, float normalizedV) {
            return sample(ringX, normalizedU, normalizedV);
        }

        float sampleZ(float normalizedU, float normalizedV) {
            return sample(ringZ, normalizedU, normalizedV);
        }

        private float sample(
                float[] values,
                float normalizedU,
                float normalizedV
        ) {
            float wrappedU = normalizedU - (float) Math.floor(normalizedU);
            float rowPosition = clamp(normalizedV, 0.0f, 1.0f)
                    * Math.max(1, rows - 1);
            float sectorPosition = wrappedU * sectors;
            int row0 = Math.min(rows - 1, (int) Math.floor(rowPosition));
            int row1 = Math.min(rows - 1, row0 + 1);
            int sector0 = ((int) Math.floor(sectorPosition)) % sectors;
            int sector1 = (sector0 + 1) % sectors;
            float rowAmount = rowPosition - row0;
            float sectorAmount = sectorPosition - (float) Math.floor(sectorPosition);
            float first = lerp(
                    values[row0 * sectors + sector0],
                    values[row0 * sectors + sector1],
                    sectorAmount
            );
            float second = lerp(
                    values[row1 * sectors + sector0],
                    values[row1 * sectors + sector1],
                    sectorAmount
            );
            return lerp(first, second, rowAmount);
        }

        private static float lerp(float first, float second, float amount) {
            return first + (second - first) * amount;
        }
    }
}
