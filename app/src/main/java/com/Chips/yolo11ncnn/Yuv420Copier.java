package com.Chips.yolo11ncnn;

import java.nio.ByteBuffer;

/** Copies one cropped image plane without assuming tightly packed YUV buffers. */
final class Yuv420Copier {
    private Yuv420Copier() { }

    static void copyPlane(ByteBuffer input, int rowStride, int pixelStride,
                          int left, int top, int width, int height, byte[] output, int offset) {
        if (rowStride <= 0 || pixelStride <= 0 || left < 0 || top < 0 || width <= 0 || height <= 0
                || offset < 0 || (long) offset + (long) width * height > output.length
                || ((long) left + width - 1) * pixelStride >= rowStride) {
            throw new IllegalArgumentException("无效的 YUV 平面尺寸");
        }
        ByteBuffer source = input.duplicate();
        long first = source.position() + (long) top * rowStride + (long) left * pixelStride;
        long last = first + (long) (height - 1) * rowStride + (long) (width - 1) * pixelStride;
        if (first < 0 || last >= source.limit()) throw new IllegalArgumentException("YUV 平面数据不完整");
        for (int row = 0; row < height; row++) {
            int start = (int) (first + (long) row * rowStride);
            if (pixelStride == 1) {
                source.position(start);
                source.get(output, offset, width);
                offset += width;
            } else {
                for (int column = 0; column < width; column++) {
                    output[offset++] = source.get(start + column * pixelStride);
                }
            }
        }
    }
}
