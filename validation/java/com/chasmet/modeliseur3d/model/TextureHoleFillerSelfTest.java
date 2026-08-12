package com.chasmet.modeliseur3d.model;

/** Pure-Java checks preventing black/transparent atlas holes. */
public final class TextureHoleFillerSelfTest {
    private TextureHoleFillerSelfTest() {
    }

    public static void main(String[] args) {
        fillsTheWholeCellFromNearestColours();
        preservesForegroundRgb();
        rejectsAnEmptyTexture();
        System.out.println("TextureHoleFillerSelfTest: OK");
    }

    private static void fillsTheWholeCellFromNearestColours() {
        int width = 5;
        int height = 4;
        int[] pixels = new int[width * height];
        pixels[1 + width] = 0xFFC86432;
        pixels[4 + 3 * width] = 0xFF2850DC;

        int foreground = TextureHoleFiller.fillNearestOpaque(
                pixels,
                width,
                height,
                24
        );
        check(foreground == 2, "Both colour seeds must be detected");
        for (int pixel : pixels) {
            check((pixel >>> 24) == 0xFF,
                    "Every atlas texel must be opaque after filling");
        }
        check(pixels[0] == 0xFFC86432,
                "The upper-left hole must inherit the horse colour");
        check(pixels[4] == 0xFF2850DC,
                "Nearest-colour propagation must not use a dark background");
    }

    private static void preservesForegroundRgb() {
        int[] pixels = {0x7F123456, 0x00000000};
        TextureHoleFiller.fillNearestOpaque(pixels, 2, 1, 24);
        check(pixels[0] == 0xFF123456,
                "Foreground RGB must remain unchanged");
        check(pixels[1] == 0xFF123456,
                "Transparent neighbour must inherit foreground RGB");
    }

    private static void rejectsAnEmptyTexture() {
        boolean rejected = false;
        try {
            TextureHoleFiller.fillNearestOpaque(new int[9], 3, 3, 24);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        check(rejected, "An empty atlas cell must fail explicitly");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
