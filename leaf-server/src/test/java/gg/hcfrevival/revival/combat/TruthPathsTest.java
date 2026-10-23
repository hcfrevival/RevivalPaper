package gg.hcfrevival.revival.combat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import gg.hcfrevival.revival.RevivalTest;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** Review 3 #3: a player outside authority is judged past the cap only where it has stood since its tracker last sent it. */
@RevivalTest
class TruthPathsTest {

    @Test
    void aStillPlayerOutsideAuthorityIsItsOwnTruthAtAnyAge() {
        final Vec3 sent = new Vec3(10.5, -60.0, -3.25);
        final double[] out = new double[3];
        assertEquals(1000L, TruthPaths.standing(sent, 10.5, -60.0, -3.25, 1000L, out), "where its tracker last sent it");
        assertArrayEquals(new double[] {10.5, -60.0, -3.25}, out);
        assertEquals(1000L, TruthPaths.standing(sent, 10.5027, -60.0, -3.25, 1000L, out), "a move its tracker doesn't send");
        assertEquals(10.5027, out[0], "judged where it is");
        assertEquals(Long.MIN_VALUE, TruthPaths.standing(sent, 10.5028, -60.0, -3.25, 1000L, out), "moved: nothing known that new");
        assertEquals(Long.MIN_VALUE, TruthPaths.standing(sent, 10.5, -59.0, -3.25, 1000L, out));
    }
}
