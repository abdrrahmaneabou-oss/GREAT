package com.great.app.shizuku;

/** Maps the touchscreen controller's raw ABS_MT axes into current display coordinates. */
final class TouchCoordinateMapper {
    private TouchCoordinateMapper() { }

    static float[] map(int rawX, int rawY,
                       int minX, int maxX, int minY, int maxY,
                       int screenWidth, int screenHeight, int rotation) {
        float nx = normalize(rawX, minX, maxX);
        float ny = normalize(rawY, minY, maxY);
        float x;
        float y;
        switch (rotation & 3) {
            case 1 -> {
                x = (1f - ny) * screenWidth;
                y = nx * screenHeight;
            }
            case 2 -> {
                x = (1f - nx) * screenWidth;
                y = (1f - ny) * screenHeight;
            }
            case 3 -> {
                x = ny * screenWidth;
                y = (1f - nx) * screenHeight;
            }
            default -> {
                x = nx * screenWidth;
                y = ny * screenHeight;
            }
        }
        return new float[]{clamp(x, 0f, screenWidth), clamp(y, 0f, screenHeight)};
    }

    private static float normalize(int value, int min, int max) {
        if (max <= min) return 0f;
        return clamp((value - min) / (float) (max - min), 0f, 1f);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
