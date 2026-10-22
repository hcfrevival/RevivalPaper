package gg.hcfrevival.revival.combat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalRegistries;
import gg.hcfrevival.revival.RevivalTest;
import gg.hcfrevival.revival.auth.combat.MirrorBook;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.VecDelta;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * The shooter's view for lag-compensated projectile hits (docs/revival/projectiles.md §2.3): the target's box on the
 * shooter's client after its newest tick, which is the box the client's next pick sees, exact and within the cap.
 */
@RevivalTest
@RevivalRegistries
class ProjectileRewindTest {

    private static final int TARGET = 7;
    private static final double W = 0.6F / 2.0F;
    private static final double H = 1.8F;

    private static ClientboundAddEntityPacket add(final double x) {
        return new ClientboundAddEntityPacket(TARGET, new UUID(TARGET, TARGET), x, 64.0, 0.0, 0.0F, 0.0F, EntityTypes.PLAYER, 0, Vec3.ZERO, 0.0);
    }

    // One K2 step of `units` / 4096 blocks along x, over 2 ticks
    private static ClientboundMoveEntityPacket.PosRot stepX(final int units) {
        return new ClientboundMoveEntityPacket.PosRot(TARGET,
            new VecDelta.Stepped(List.of(new VecDelta.Stepped.DeltaStep((short) units, (short) 0, (short) 0, 2))), (byte) 0, (byte) 0, true);
    }

    @Test
    void theViewIsTheMirrorAfterTheShootersNewestTickWhichItsNextPickSees() {
        final MirrorBook book = new MirrorBook(2, 20);
        final double[] out = new double[6];
        book.onPacket(add(0.0), 1, 100L);
        assertFalse(ProjectileRewind.viewBox(book, TARGET, 100L, 300L, out)); // no client tick run yet
        book.onViewerTickEnd(1);
        assertTrue(ProjectileRewind.viewBox(book, TARGET, 100L, 300L, out));
        assertArrayEquals(new double[] {-W, 64.0, -W, W, 64.0 + H, W}, out, 0.0);

        book.onPacket(stepX(4096), 2, 150L); // one block along x, applied before client tick 2
        book.onViewerTickEnd(2);
        assertTrue(ProjectileRewind.viewBox(book, TARGET, 200L, 300L, out));
        assertEquals(0.5 - W, out[0], 0.0); // tick 2's interpolation has run half the 2-tick step
        book.onViewerTickEnd(3);
        final MirrorBook.Sample sample = new MirrorBook.Sample();
        assertTrue(book.sample(TARGET, 3, sample));
        assertEquals(out[0], sample.minX, 0.0); // ... which is what client tick 3's pick saw
    }

    @Test
    void theCapIsInclusiveAndMeasuredFromTheStepOnScreen() {
        final MirrorBook book = new MirrorBook(2, 20);
        final double[] out = new double[6];
        book.onPacket(add(0.0), 1, 1_000L);
        book.onViewerTickEnd(1);
        assertTrue(ProjectileRewind.viewBox(book, TARGET, 1_300L, 300L, out));
        assertFalse(ProjectileRewind.viewBox(book, TARGET, 1_301L, 300L, out));
    }

    @Test
    void unknownRemovedAndInexactTargetsFallBackToTheServerBox() {
        final MirrorBook book = new MirrorBook(2, 20);
        final double[] out = new double[6];
        book.onPacket(add(0.0), 1, 0L);
        book.onViewerTickEnd(1);
        assertFalse(ProjectileRewind.viewBox(book, TARGET + 1, 0L, 300L, out));
        book.onPacket(stepX(4096), 2, 0L, false); // unbundled: arrival is only an upper bound
        book.onViewerTickEnd(2);
        assertFalse(ProjectileRewind.viewBox(book, TARGET, 0L, 300L, out));
        book.onPacket(add(0.0), 3, 0L);
        book.onViewerTickEnd(3);
        assertTrue(ProjectileRewind.viewBox(book, TARGET, 0L, 300L, out)); // a respawn makes it exact again
        book.onPacket(new ClientboundRemoveEntitiesPacket(TARGET), 4, 0L);
        book.onViewerTickEnd(4);
        assertFalse(ProjectileRewind.viewBox(book, TARGET, 0L, 300L, out));
    }
}
