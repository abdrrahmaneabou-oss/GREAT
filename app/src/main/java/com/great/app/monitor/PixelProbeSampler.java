package com.great.app.monitor;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Direct plane reads with PixelTrigger's cached, at-most-five-point cross plan. */
public final class PixelProbeSampler {
    private float cachedX = -1, cachedY = -1;
    private final int[] dx = new int[5], dy = new int[5], probes = new int[5];
    private int count;

    public PixelSample sample(ByteBuffer buffer, int pixelStride, int rowStride,
                              int left, int top, int right, int bottom,
                              int centerX, int centerY, float radiusX, float radiusY) {
        if (buffer == null || pixelStride < 3 || rowStride <= 0
                || centerX < left || centerX >= right || centerY < top || centerY >= bottom) return null;
        plan(Math.max(.5f, radiusX), Math.max(.5f, radiusY));
        int n = 0, base = buffer.position();
        for (int i = 0; i < count; i++) {
            int x = centerX + dx[i], y = centerY + dy[i];
            if (x < left || x >= right || y < top || y >= bottom) continue;
            long offset = base + (long) y * rowStride + (long) x * pixelStride;
            if (offset < 0 || offset + 2 >= buffer.limit()) continue;
            int o = (int) offset;
            probes[n++] = ((buffer.get(o) & 255) << 16) | ((buffer.get(o + 1) & 255) << 8)
                    | (buffer.get(o + 2) & 255);
        }
        return n == 0 ? null : new PixelSample(Arrays.copyOf(probes, n));
    }

    private void plan(float rx, float ry) {
        if (Float.compare(rx, cachedX) == 0 && Float.compare(ry, cachedY) == 0) return;
        cachedX = rx; cachedY = ry; count = 0;
        int sx = Math.max(1, (int) Math.floor(rx)), sy = Math.max(1, (int) Math.floor(ry));
        add(0, 0); add(-sx, 0); add(sx, 0); add(0, -sy); add(0, sy);
    }

    private void add(int x, int y) {
        float nx = x / cachedX, ny = y / cachedY;
        if (nx * nx + ny * ny > 1f) return;
        for (int i = 0; i < count; i++) if (dx[i] == x && dy[i] == y) return;
        dx[count] = x; dy[count++] = y;
    }
}
