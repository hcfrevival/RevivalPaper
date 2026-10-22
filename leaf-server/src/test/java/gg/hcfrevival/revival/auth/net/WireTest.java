package gg.hcfrevival.revival.auth.net;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gg.hcfrevival.revival.RevivalTest;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Random;
import net.minecraft.network.LpVec3;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** Report 02 §3.3: the velocity a SetEntityMotion gives the client is LpVec3's decoded value, bit for bit. */
@RevivalTest
class WireTest {

    private static void check(final double x, final double y, final double z) {
        final ByteBuf buffer = Unpooled.buffer();
        LpVec3.write(buffer, new Vec3(x, y, z));
        final Vec3 decoded = LpVec3.read(buffer);
        final double[] out = new double[3];
        Wire.lpVec3(x, y, z, out);
        assertEquals(Double.doubleToRawLongBits(decoded.x), Double.doubleToRawLongBits(out[0]), "x of " + x + "," + y + "," + z);
        assertEquals(Double.doubleToRawLongBits(decoded.y), Double.doubleToRawLongBits(out[1]), "y of " + x + "," + y + "," + z);
        assertEquals(Double.doubleToRawLongBits(decoded.z), Double.doubleToRawLongBits(out[2]), "z of " + x + "," + y + "," + z);
    }

    @Test
    void matchesTheCodecBitForBit() {
        final Random random = new Random(26_3L);
        for (int i = 0; i < 100_000; i++) {
            final double scale = switch (i % 4) {
                case 0 -> 0.5;
                case 1 -> 1.0;
                case 2 -> 4.0;
                default -> 40.0;
            };
            check((random.nextDouble() * 2 - 1) * scale, (random.nextDouble() * 2 - 1) * scale, (random.nextDouble() * 2 - 1) * scale);
        }
    }

    @Test
    void edges() {
        check(0.0, 0.0, 0.0);
        check(3.0E-5, 0.0, 0.0);      // below the minimum: the whole vector is zero
        check(0.0, 0.4, 0.0);         // decodes as 0.3999877...
        check(0.0, 0.3608, 0.4);      // the 1.7 standing knock
        check(-1.0, 1.0, 0.999999);
        check(Double.NaN, 0.5, 0.0);
        check(1.0E11, 0.0, -1.0E11);  // clamped
    }
}
