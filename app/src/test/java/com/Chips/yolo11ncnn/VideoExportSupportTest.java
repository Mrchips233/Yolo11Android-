package com.Chips.yolo11ncnn;

import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class VideoExportSupportTest {
    @Test public void preservesActualPtsAndUsesFirstFrameForPlaybackTime() {
        String row = VideoExportSupport.emptyFrameRow(2, 1_234_567, 1_000_000,
                "frame_000000002.jpg", 720, 1280);
        List<String> fields = parse(row);
        assertEquals(16, fields.size());
        assertEquals("2", fields.get(0));
        assertEquals("1234567", fields.get(1));
        assertEquals("00:00:01.234567", fields.get(2));
        assertEquals("234567", fields.get(3));
        assertEquals("00:00:00.234567", fields.get(4));
        assertEquals("frame_000000002.jpg", fields.get(5));
        for (int i = 6; i <= 12; i++) assertEquals("", fields.get(i));
        assertEquals("720", fields.get(13));
        assertEquals("1280", fields.get(14));
        assertEquals("empty", fields.get(15));
    }

    @Test public void quotesProviderNamesWithoutBreakingCsvColumns() {
        String image = "图像,\"特殊\".jpg";
        List<String> fields = parse(VideoExportSupport.emptyFrameRow(1, 3_600_000_001L,
                3_600_000_001L, image, 100, 200));
        assertEquals(image, fields.get(5));
        assertEquals("01:00:00.000001", fields.get(2));
        assertEquals("00:00:00.000000", fields.get(4));
    }

    @Test public void preservesNegativePtsInsteadOfInventingFrameRateTimestamps() {
        assertEquals("-00:00:00.000001", VideoExportSupport.timestamp(-1));
        assertEquals("frame_000000001.jpg", VideoExportSupport.frameName(1));
        assertEquals("0.465123", VideoExportSupport.number(0.465123f));
    }

    @Test public void limitedRangeBlackAndWhiteConvertWithoutTint() throws Exception {
        int[] result = convert(new byte[]{16, (byte)235}, new byte[]{(byte)128},
                new byte[]{(byte)128}, 2, 1, 0, 0, 2, 1, 2, 2);
        assertArrayEquals(new int[]{0xff000000, 0xffffffff}, result);
    }

    @Test public void oddCropOriginUsesCorrectChromaForEveryPixel() throws Exception {
        byte[] y = new byte[16];
        java.util.Arrays.fill(y, (byte)128);
        int[] result = convert(y, new byte[]{64, (byte)128, (byte)192, (byte)255},
                new byte[]{(byte)128, (byte)128, (byte)128, (byte)128},
                4, 2, 1, 1, 2, 2, 2, 1);
        assertEquals(4, result.length);
        assertTrue((result[0] & 255) < (result[1] & 255));
        assertEquals(128, result[1] & 255);
        assertTrue((result[2] & 255) > (result[1] & 255));
        assertEquals(255, result[3] & 255);
    }

    @Test public void videoColorStandardChangesConversionRatherThanAssumingBt601() throws Exception {
        byte[] y = {(byte)128};
        byte[] u = {80};
        byte[] v = {(byte)180};
        assertNotEquals(convert(y, u, v, 1, 1, 0, 0, 1, 1, 1, 2)[0],
                convert(y, u, v, 1, 1, 0, 0, 1, 1, 2, 2)[0]);
    }

    @Test(expected = IOException.class) public void truncatedDecodePlaneFailsExplicitly() throws Exception {
        convert(new byte[]{16}, new byte[]{(byte)128}, new byte[]{(byte)128},
                2, 1, 0, 0, 2, 1, 2, 2);
    }

    private static int[] convert(byte[] y, byte[] u, byte[] v, int yStride, int uvStride,
                                 int left, int top, int width, int height, int standard, int range) throws IOException {
        return VideoExportSupport.yuvToArgb(new ByteBuffer[]{ByteBuffer.wrap(y), ByteBuffer.wrap(u), ByteBuffer.wrap(v)},
                new int[]{yStride, uvStride, uvStride}, new int[]{1, 1, 1},
                left, top, width, height, standard, range);
    }

    private static List<String> parse(String row) {
        List<String> result = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < row.length(); i++) {
            char c = row.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < row.length() && row.charAt(i + 1) == '"') { field.append('"'); i++; }
                else quoted = !quoted;
            } else if (c == ',' && !quoted) {
                result.add(field.toString()); field.setLength(0);
            } else if ((c == '\r' || c == '\n') && !quoted) { break; }
            else field.append(c);
        }
        result.add(field.toString());
        return result;
    }
}
