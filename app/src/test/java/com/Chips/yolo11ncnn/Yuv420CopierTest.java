package com.Chips.yolo11ncnn;

import org.junit.Test;
import java.nio.ByteBuffer;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class Yuv420CopierTest {
    @Test public void copiesPaddedCroppedLumaWithoutChangingBufferPosition() {
        ByteBuffer plane = ByteBuffer.wrap(new byte[]{99, 0, 1, 2, 3, 99, 4, 5, 6, 7, 99});
        plane.position(1);
        byte[] result = new byte[4];
        Yuv420Copier.copyPlane(plane, 5, 1, 1, 0, 2, 2, result, 0);
        assertArrayEquals(new byte[]{1, 2, 5, 6}, result);
        assertEquals(1, plane.position());
    }

    @Test public void copiesInterleavedChromaUsingPixelStrideAndCrop() {
        ByteBuffer plane = ByteBuffer.wrap(new byte[]{0, 99, 1, 99, 2, 99, 3, 99,
                4, 99, 5, 99, 6, 99, 7});
        byte[] result = new byte[6];
        Yuv420Copier.copyPlane(plane, 8, 2, 1, 0, 2, 2, result, 1);
        assertArrayEquals(new byte[]{0, 1, 2, 5, 6, 0}, result);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsTruncatedPlaneInsteadOfWritingWrongColors() {
        Yuv420Copier.copyPlane(ByteBuffer.wrap(new byte[3]), 4, 1, 0, 0, 4, 1, new byte[4], 0);
    }
}
