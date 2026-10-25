package com.Chips.yolo11ncnn;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Locale;

/** Platform-free formatting and YUV conversion used by the streaming video exporter. */
final class VideoExportSupport {
    private VideoExportSupport() { }

    static String csv(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    static String frameName(long frameIndex) {
        if (frameIndex < 1) throw new IllegalArgumentException("Frame index must be positive");
        return String.format(Locale.ROOT, "frame_%09d.jpg", frameIndex);
    }

    static String timestamp(long microseconds) {
        if (microseconds == Long.MIN_VALUE) throw new IllegalArgumentException("Invalid timestamp");
        String sign = microseconds < 0 ? "-" : "";
        long absolute = Math.abs(microseconds);
        return String.format(Locale.ROOT, "%s%02d:%02d:%02d.%06d", sign,
                absolute / 3_600_000_000L, absolute / 60_000_000 % 60,
                absolute / 1_000_000 % 60, absolute % 1_000_000);
    }

    static String number(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            throw new IllegalArgumentException("Non-finite detection value");
        }
        return String.format(Locale.ROOT, "%.6f", value);
    }

    static String framePrefix(long index, long ptsUs, long firstPtsUs, String imageName) {
        long elapsedUs = ptsUs - firstPtsUs;
        return index + "," + ptsUs + "," + csv(timestamp(ptsUs)) + "," + elapsedUs + ","
                + csv(timestamp(elapsedUs)) + "," + csv(imageName) + ",";
    }

    static String emptyFrameRow(long index, long ptsUs, long firstPtsUs, String imageName, int width, int height) {
        return framePrefix(index, ptsUs, firstPtsUs, imageName) + ",,,,,,," + width + "," + height + ",empty\r\n";
    }

    /**
     * Read a cropped YUV420 image, including odd crop origins and interleaved/padded planes.
     * colorStandard uses MediaFormat values: 1=BT709, 2/4=BT601, 6=BT2020.
     * colorRange uses MediaFormat values: 1=full, 2=limited. Missing values use BT601 limited.
     */
    static int[] yuvToArgb(ByteBuffer[] planes, int[] rowStrides, int[] pixelStrides,
                           int left, int top, int width, int height,
                           int colorStandard, int colorRange) throws IOException {
        if (planes.length != 3 || rowStrides.length != 3 || pixelStrides.length != 3
                || left < 0 || top < 0 || width <= 0 || height <= 0
                || (long) width * height > 4096L * 2160) {
            throw new IOException("视频裁切尺寸无效或超过支持的 4096×2160 像素上限");
        }
        // Copying only the crop also validates every source-plane bound before conversion.
        int chromaLeft = left / 2;
        int chromaTop = top / 2;
        int chromaWidth = (left + width + 1) / 2 - chromaLeft;
        int chromaHeight = (top + height + 1) / 2 - chromaTop;
        byte[] y = new byte[width * height];
        byte[] u = new byte[chromaWidth * chromaHeight];
        byte[] v = new byte[u.length];
        try {
            Yuv420Copier.copyPlane(planes[0], rowStrides[0], pixelStrides[0],
                    left, top, width, height, y, 0);
            Yuv420Copier.copyPlane(planes[1], rowStrides[1], pixelStrides[1],
                    chromaLeft, chromaTop, chromaWidth, chromaHeight, u, 0);
            Yuv420Copier.copyPlane(planes[2], rowStrides[2], pixelStrides[2],
                    chromaLeft, chromaTop, chromaWidth, chromaHeight, v, 0);
        } catch (IllegalArgumentException exception) {
            throw new IOException("解码器提供的 YUV 平面不完整", exception);
        }
        double kr = colorStandard == 1 ? 0.2126 : colorStandard == 6 ? 0.2627 : 0.299;
        double kb = colorStandard == 1 ? 0.0722 : colorStandard == 6 ? 0.0593 : 0.114;
        double kg = 1 - kr - kb;
        boolean full = colorRange == 1;
        double yScale = full ? 1 : 255.0 / 219;
        double cScale = full ? 1 : 255.0 / 224;
        // Fixed-point coefficients avoid allocating objects or doing floating point per pixel.
        int ym = (int) Math.round(yScale * 65536);
        int rv = (int) Math.round(2 * (1 - kr) * cScale * 65536);
        int bu = (int) Math.round(2 * (1 - kb) * cScale * 65536);
        int gu = (int) Math.round(-2 * kb * (1 - kb) / kg * cScale * 65536);
        int gv = (int) Math.round(-2 * kr * (1 - kr) / kg * cScale * 65536);
        int[] argb = new int[width * height];
        for (int row = 0; row < height; row++) {
            int chromaRow = ((top + row) / 2 - chromaTop) * chromaWidth;
            for (int column = 0; column < width; column++) {
                int index = row * width + column;
                int chromaIndex = chromaRow + (left + column) / 2 - chromaLeft;
                int luminance = ((y[index] & 255) - (full ? 0 : 16)) * ym;
                int cb = (u[chromaIndex] & 255) - 128;
                int cr = (v[chromaIndex] & 255) - 128;
                int r = clip((luminance + rv * cr + 32768) >> 16);
                int g = clip((luminance + gu * cb + gv * cr + 32768) >> 16);
                int b = clip((luminance + bu * cb + 32768) >> 16);
                argb[index] = 0xff000000 | (r << 16) | (g << 8) | b;
            }
        }
        return argb;
    }

    private static int clip(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
