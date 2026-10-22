package gg.hcfrevival.revival.auth.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalRegistries;
import gg.hcfrevival.revival.RevivalTest;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.VecDelta;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** The per-viewer book: arrival ticks, the pick-time sample, history, packet routing (report 03 §7.1-7.3). */
@RevivalTest
@RevivalRegistries
class MirrorBookTest {

    private static final int T = 7;

    private static ClientboundAddEntityPacket add(final int id, final double x) {
        return new ClientboundAddEntityPacket(id, new UUID(id, id), x, 64.0, 0.0, 0.0F, 0.0F, EntityTypes.PLAYER, 0, Vec3.ZERO, 0.0);
    }

    private static ClientboundMoveEntityPacket.PosRot stepX(final int id, final int units) {
        return new ClientboundMoveEntityPacket.PosRot(id, new VecDelta.Stepped(List.of(new VecDelta.Stepped.DeltaStep((short) units, (short) 0, (short) 0, 2))),
            (byte) 0, (byte) 0, true);
    }

    @Test
    void eventsApplyAtTheirArrivalTickAndThePickSeesTheBoxBeforeThatTicksInterpolation() {
        final MirrorBook book = new MirrorBook(2, 20);
        book.onViewerTickEnd(0);
        book.onPacket(add(T, 0.0), 1, 100L);
        book.onPacket(stepX(T, 4096), 1, 101L); // same bundle: both applied before client tick 1
        book.onViewerTickEnd(1);
        final MirrorBook.Sample sample = new MirrorBook.Sample();
        assertTrue(book.sample(T, 1, sample));
        assertTrue(sample.pickable);
        assertTrue(sample.exact);
        assertEquals(-0.3F, sample.minX, 0.0); // spawn position: the step only starts moving in tick 1's interpolation
        assertEquals(100L, sample.stamp);
        book.onViewerTickEnd(2);
        assertTrue(book.sample(T, 2, sample));
        assertEquals(0.5 - 0.3F, sample.minX, 0.0); // after tick 1's interpolate: half the 2-tick step
        assertEquals(101L, sample.stamp);
        assertFalse(book.sample(T, 3, sample)); // not run yet
    }

    @Test
    void eventsForALaterTickWaitAndLateOnesMarkTheTargetInexact() {
        final MirrorBook book = new MirrorBook(2, 20);
        book.onPacket(add(T, 0.0), 0, 0L);
        book.onViewerTickEnd(0);
        book.onPacket(stepX(T, 4096), 3, 1L);
        book.onViewerTickEnd(1);
        book.onViewerTickEnd(2);
        assertEquals(0, book.mirror(T).queuedSteps());
        book.onViewerTickEnd(3);
        assertTrue(book.exactAtPick(T, 3));
        book.onPacket(stepX(T, 4096), 3, 2L); // tick 3 already ran
        assertEquals(1L, book.lateEvents());
        book.onViewerTickEnd(4);
        assertFalse(book.exactAtPick(T, 4));
    }

    @Test
    void aPacketWithOnlyAnUpperBoundArrivalMakesItsTargetInexactUntilRespawned() {
        final MirrorBook book = new MirrorBook(2, 20);
        book.onPacket(add(T, 0.0), 1, 0L, true);
        book.onViewerTickEnd(1);
        book.onPacket(stepX(T, 4096), 2, 1L, false); // sent unbundled: applied at or before tick 2
        book.onViewerTickEnd(2);
        assertTrue(book.exactAtPick(T, 1));
        assertFalse(book.exactAtPick(T, 2));
        book.onPacket(add(T, 0.0), 3, 2L, true);
        book.onViewerTickEnd(3);
        assertTrue(book.exactAtPick(T, 3));
    }

    @Test
    void historyIsBoundedAndGapsAreRunTickByTick() {
        final MirrorBook book = new MirrorBook(2, 3);
        book.onPacket(add(T, 0.0), 10, 0L);
        book.onViewerTickEnd(10);
        book.onViewerTickEnd(14); // 11..14 run one by one
        assertEquals(14, book.lastTick());
        assertNotNull(book.boxAtPick(T, 14));
        assertNotNull(book.boxAtPick(T, 12));
        assertNull(book.boxAtPick(T, 11)); // only 3 ticks kept
    }

    @Test
    void removalHidesTheTargetAndPoseChangesItsBox() {
        final MirrorBook book = new MirrorBook(2, 20);
        book.onPacket(add(T, 0.0), 1, 0L);
        book.onPacket(new ClientboundSetEntityDataPacket(T, List.of(MirrorStreams.poseValue(Pose.CROUCHING))), 1, 0L);
        book.onViewerTickEnd(1);
        final AABB box = book.boxAtPick(T, 1);
        assertNotNull(box);
        assertEquals(64.0 + 1.5F, box.maxY);
        book.onPacket(new ClientboundRemoveEntitiesPacket(T), 2, 0L);
        book.onViewerTickEnd(2);
        assertNull(book.boxAtPick(T, 2));
        assertTrue(book.knows(T));
    }

    @Test
    void packetsForUnknownPlayersAndOtherEntitiesAreIgnored() {
        final MirrorBook book = new MirrorBook(2, 20);
        assertFalse(book.onPacket(stepX(99, 4096), 1, 0L));
        assertFalse(book.onPacket(new ClientboundAddEntityPacket(98, UUID.randomUUID(), 0.0, 0.0, 0.0, 0.0F, 0.0F, EntityTypes.ZOMBIE, 0, Vec3.ZERO, 0.0), 1, 0L));
        assertEquals(0, book.size());
    }

    @Test
    void ridingMakesTheTargetInexact() {
        final MirrorBook book = new MirrorBook(2, 20);
        book.onPacket(add(T, 0.0), 1, 0L);
        book.onEvent(50, new MirrorEvent.Passengers(new int[] {T}), 1);
        book.onViewerTickEnd(1);
        assertFalse(book.exactAtPick(T, 1));
        assertTrue(book.mirror(T).passenger());
        book.onEvent(50, new MirrorEvent.Passengers(new int[0]), 2);
        book.onViewerTickEnd(2);
        assertFalse(book.mirror(T).passenger());
    }

    @Test
    void aClearDropsEveryMirror() {
        final MirrorBook book = new MirrorBook(2, 20);
        book.onPacket(add(T, 0.0), 1, 0L);
        book.onPacket(add(T + 1, 5.0), 1, 0L);
        book.onEvent(-1, MirrorEvent.CLEAR, 2);
        book.onViewerTickEnd(2);
        assertNull(book.boxAtPick(T, 2));
        assertNull(book.boxAtPick(T + 1, 2));
    }
}
