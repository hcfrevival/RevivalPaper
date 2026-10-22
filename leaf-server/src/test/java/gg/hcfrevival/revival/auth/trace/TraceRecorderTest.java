package gg.hcfrevival.revival.auth.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gg.hcfrevival.revival.RevivalTest;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** RVTR v1 (report 07 §4.2): XOR-delta fields decode bit for bit. */
@RevivalTest
class TraceRecorderTest {

    // The reader side of TraceRecorder.writeXor
    private static long readXor(final ByteBuf b, final int width) {
        final int header = b.readUnsignedByte();
        final int leading = header >> 4;
        final int trailing = header & 15;
        if (leading == width) {
            return 0L;
        }
        long value = 0L;
        for (int i = width - 1 - leading; i >= trailing; i--) {
            value |= (long) b.readUnsignedByte() << (i * 8);
        }
        return value;
    }

    @Test
    void xorDeltasRoundTrip() {
        final Random random = new Random(7);
        final ByteBuf buffer = Unpooled.buffer();
        long previous = 0L;
        for (int i = 0; i < 10_000; i++) {
            final long bits = Double.doubleToRawLongBits(random.nextDouble() * 200 - 100);
            final long value = i % 5 == 0 ? previous : bits;
            buffer.clear();
            TraceRecorder.writeXor(buffer, value ^ previous, 8);
            assertEquals(value, readXor(buffer, 8) ^ previous);
            previous = value;
        }
        for (int i = 0; i < 10_000; i++) {
            final long bits = Float.floatToRawIntBits(random.nextFloat() * 360) & 0xFFFFFFFFL;
            buffer.clear();
            TraceRecorder.writeXor(buffer, bits, 4);
            assertEquals(bits, readXor(buffer, 4));
        }
    }
}
