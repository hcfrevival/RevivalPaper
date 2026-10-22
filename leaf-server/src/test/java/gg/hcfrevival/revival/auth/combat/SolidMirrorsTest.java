package gg.hcfrevival.revival.auth.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalRegistries;
import gg.hcfrevival.revival.RevivalTest;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.VecDelta;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.PositionPath;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * {@link SolidMirrors}: the client's copies of the boats, shulkers and happy ghasts its player collides with
 * (auth-sim-requests §2). The verification rig (~/Developer/tools/auth-rig/combat, SolidVerify) replays
 * {@link SolidStreams} on real 26.3 client entities and on the mirror: 1,200,000 ticks, every exact copy's box,
 * position, rotation, boat interpolation and shulker peek bit-identical, the collision order the client's
 * {@code EntitySectionStorage} order (900,140 checks), 0 differences. It prints the fingerprint pinned here.
 */
@RevivalTest
@RevivalRegistries
class SolidMirrorsTest {

    /** {@code SolidVerify}: "fingerprint seed=3 trials=300 ticks=200". */
    static final long FINGERPRINT = 0x7D4E6C44C7C5A3ADL;

    @Test
    void streamsMatchTheRig() {
        final Random random = new Random(3L);
        long hash = 0L;
        for (int i = 0; i < 300; i++) {
            hash = SolidStreams.run(SolidStreams.adversarial(random, 200), new SolidMirrors(), hash);
        }
        assertEquals(FINGERPRINT, hash, String.format("0x%016XL", hash));
    }

    @Test
    void typesThePlayerCollidesWith() {
        assertEquals(SolidMirrors.BOAT, SolidMirrors.kindOf(EntityTypes.OAK_BOAT));
        assertEquals(SolidMirrors.BOAT, SolidMirrors.kindOf(EntityTypes.BAMBOO_CHEST_RAFT));
        assertEquals(SolidMirrors.BOAT, SolidMirrors.kindOf(EntityTypes.PALE_OAK_CHEST_BOAT));
        assertEquals(SolidMirrors.SHULKER, SolidMirrors.kindOf(EntityTypes.SHULKER));
        assertEquals(SolidMirrors.GHAST, SolidMirrors.kindOf(EntityTypes.HAPPY_GHAST));
        assertEquals(0, SolidMirrors.kindOf(EntityTypes.MINECART));
        assertEquals(0, SolidMirrors.kindOf(EntityTypes.PLAYER));
        assertEquals(0, SolidMirrors.kindOf(EntityTypes.GHAST));
    }

    @Test
    void aBoatReachesItsTargetInThreeLinearSteps() {
        final SolidMirrors mirrors = new SolidMirrors();
        mirrors.apply(add(7, EntityTypes.OAK_BOAT, 0.0, 64.0, 0.0));
        final double[] box = new double[6];
        assertTrue(mirrors.box(7, box));
        assertEquals(-0.6875, box[0], 0.0);
        assertEquals(64.5625, box[4], 0.0);
        // 3 blocks east in one move: VecDeltaCodec units of 1/4096
        mirrors.apply(new ClientboundMoveEntityPacket.Pos(7, new VecDelta.Linear((short) (3 * 4096), (short) 0, (short) 0), true));
        assertTrue(mirrors.box(7, box));
        assertEquals(-0.6875, box[0], 0.0, "the move only sets the target");
        final double[] expected = {1.0, 2.0, 3.0}; // x + 1/3 (3 - x), x + 1/2 (3 - x), x + 1 (3 - x)
        for (final double x : expected) {
            tick(mirrors);
            assertTrue(mirrors.box(7, box));
            assertEquals(x - 0.6875, box[0], 0.0);
        }
        tick(mirrors);
        assertTrue(mirrors.box(7, box));
        assertEquals(3.0 - 0.6875, box[0], 0.0, "then it rests");
        assertTrue(mirrors.exact(7));
    }

    @Test
    void aFarSyncSnapsAndAShiftedTargetIsInexact() {
        final SolidMirrors mirrors = new SolidMirrors();
        mirrors.apply(add(7, EntityTypes.OAK_BOAT, 0.0, 64.0, 0.0));
        mirrors.apply(new ClientboundEntityPositionSyncPacket(7, PositionPath.of(new Vec3(100.0, 64.0, 0.0)), 0.0F, 0.0F, true));
        final double[] box = new double[6];
        assertTrue(mirrors.box(7, box));
        assertEquals(100.0 - 0.6875, box[0], 0.0, "over 64 blocks: snapTo");
        assertTrue(mirrors.exact(7));
        // A short sync starts an interpolation; a far one during it snaps, and the next step shifts the target by the jump,
        // only where the client's own world has room: no longer known
        mirrors.apply(new ClientboundEntityPositionSyncPacket(7, PositionPath.of(new Vec3(101.0, 64.0, 0.0)), 0.0F, 0.0F, true));
        mirrors.apply(new ClientboundEntityPositionSyncPacket(7, PositionPath.of(new Vec3(300.0, 64.0, 0.0)), 0.0F, 0.0F, true));
        tick(mirrors);
        assertFalse(mirrors.exact(7));
        final IntArrayList repairs = new IntArrayList();
        mirrors.takeRepairs(repairs);
        assertEquals(IntArrayList.of(7), repairs, "re-paired once");
        mirrors.apply(add(7, EntityTypes.OAK_BOAT, 300.0, 64.0, 0.0));
        assertTrue(mirrors.exact(7), "a logged spawn makes it exact again");
    }

    @Test
    void aShulkerOpensItsLidAFiftiethATick() {
        final SolidMirrors mirrors = new SolidMirrors();
        mirrors.apply(add(9, EntityTypes.SHULKER, 4.3, 63.6, 8.9));
        final double[] box = new double[6];
        assertTrue(mirrors.box(9, box));
        assertEquals(4.0, box[0], 0.0, "snapped to the block centre: 4.5 ± 0.5");
        assertEquals(64.0, box[1], 0.0, "floor(y + 0.5)");
        assertEquals(65.0, box[4], 0.0, "closed");
        mirrors.apply(new ClientboundSetEntityDataPacket(9, List.of(SynchedEntityData.DataValue.create(SolidStreams.PEEK, (byte) 100))));
        assertTrue(mirrors.box(9, box));
        assertEquals(65.0, box[4], 0.0, "the raw peek moves the client's own peek in its ticks");
        float peek = 0.0F;
        for (int i = 0; i < 3; i++) {
            final float old = peek;
            peek = net.minecraft.util.Mth.clamp(peek + 0.05F, 0.0F, 1.0F);
            final boolean pushed = mirrors.tick(false, 1.0F, 4.0, 65.0, 8.0, 4.6, 66.8, 8.6);
            final AABB expected = Shulker.getProgressAabb(1.0F, Direction.UP, SolidMirrors.physicalPeek(peek), new Vec3(4.5, 64.0, 8.5));
            assertTrue(mirrors.box(9, box));
            assertEquals(expected.maxY, box[4], 0.0);
            assertTrue(pushed, "a player standing on it is lifted by the opening lid (tick " + i + ", " + old + " → " + peek + ")");
        }
        mirrors.apply(new ClientboundSetEntityDataPacket(9, List.of(SynchedEntityData.DataValue.create(SolidStreams.ATTACH_FACE, Direction.WEST))));
        final AABB sideways = Shulker.getProgressAabb(1.0F, Direction.EAST, SolidMirrors.physicalPeek(peek), new Vec3(4.5, 64.0, 8.5));
        assertTrue(mirrors.box(9, box));
        assertEquals(sideways.maxX, box[3], 0.0, "a new attach face rebuilds the box at once");
    }

    @Test
    void ghastsCollideByTheirRules() {
        final SolidMirrors mirrors = new SolidMirrors();
        mirrors.apply(add(11, EntityTypes.HAPPY_GHAST, 0.0, 70.0, 0.0));
        final List<int[]> rules = new ArrayList<>();
        final SolidMirrors.Sink sink = (minX, minY, minZ, maxX, maxY, maxZ, x, y, z, rule) -> rules.add(new int[] {rule, (int) (maxY - minY)});
        mirrors.collect(-10.0, 60.0, -10.0, 10.0, 80.0, 10.0, sink);
        assertEquals(1, rules.size());
        assertEquals(SolidMirrors.RULE_SOLID_FROM_ABOVE, rules.getFirst()[0], "moving: only a player on top");
        assertEquals(4, rules.getFirst()[1]);
        mirrors.apply(new ClientboundSetEntityDataPacket(11, List.of(SynchedEntityData.DataValue.create(SolidStreams.STAYS_STILL, true))));
        rules.clear();
        mirrors.collect(-10.0, 60.0, -10.0, 10.0, 80.0, 10.0, sink);
        assertEquals(SolidMirrors.RULE_SOLID, rules.getFirst()[0], "on its still timeout: solid");
        mirrors.apply(new ClientboundSetEntityDataPacket(11, List.of(SynchedEntityData.DataValue.create(SolidStreams.BABY, true))));
        rules.clear();
        mirrors.collect(-10.0, 60.0, -10.0, 10.0, 80.0, 10.0, sink);
        assertTrue(rules.isEmpty(), "a baby never collides");
        mirrors.apply(new ClientboundSetEntityDataPacket(11, List.of(SynchedEntityData.DataValue.create(SolidStreams.BABY, false),
            SynchedEntityData.DataValue.create(SolidStreams.HEALTH, 0.0F))));
        mirrors.collect(-10.0, 60.0, -10.0, 10.0, 80.0, 10.0, sink);
        assertTrue(rules.isEmpty(), "nor a dead one");
    }

    @Test
    void handedInTheClientsSectionOrder() {
        final SolidMirrors mirrors = new SolidMirrors();
        mirrors.apply(add(1, EntityTypes.OAK_BOAT, 17.0, 64.0, 0.0)); // section x 1
        mirrors.apply(add(2, EntityTypes.OAK_BOAT, 14.0, 64.0, 0.0)); // section x 0
        mirrors.apply(add(3, EntityTypes.OAK_BOAT, 15.5, 64.0, 0.0)); // section x 0, entered after 2
        final List<Double> xs = new ArrayList<>();
        mirrors.collect(0.0, 60.0, -5.0, 30.0, 70.0, 5.0, (minX, minY, minZ, maxX, maxY, maxZ, x, y, z, rule) -> xs.add(x));
        assertEquals(List.of(14.0, 15.5, 17.0), xs);
        // Moving into another section puts it last there; moving within one keeps its place
        mirrors.apply(new ClientboundEntityPositionSyncPacket(1, PositionPath.of(new Vec3(100.0, 64.0, 0.0)), 0.0F, 0.0F, true));
        mirrors.apply(new ClientboundEntityPositionSyncPacket(1, PositionPath.of(new Vec3(13.0, 64.0, 0.0)), 0.0F, 0.0F, true));
        xs.clear();
        mirrors.collect(0.0, 60.0, -5.0, 30.0, 70.0, 5.0, (minX, minY, minZ, maxX, maxY, maxZ, x, y, z, rule) -> xs.add(x));
        assertEquals(List.of(14.0, 15.5, 13.0), xs);
    }

    @Test
    void passengersAndDrivenVehicles() {
        final SolidMirrors mirrors = new SolidMirrors();
        mirrors.setSelf(5);
        mirrors.apply(add(7, EntityTypes.OAK_BOAT, 0.0, 64.0, 0.0));
        mirrors.apply(add(9, EntityTypes.SHULKER, 3.0, 64.0, 0.0));
        mirrors.apply(gg.hcfrevival.revival.auth.combat.SolidStreams.passengers(5, 9));
        final List<Double> xs = new ArrayList<>();
        final SolidMirrors.Sink sink = (minX, minY, minZ, maxX, maxY, maxZ, x, y, z, rule) -> xs.add(x);
        mirrors.collect(-10.0, 60.0, -10.0, 10.0, 70.0, 10.0, sink);
        assertEquals(List.of(0.0), xs, "a shulker riding the player never collides with it");
        assertTrue(mirrors.ridesSelf(9));
        assertFalse(mirrors.trusted(9));
        // Driving the boat: its physics are the client's own
        mirrors.apply(gg.hcfrevival.revival.auth.combat.SolidStreams.passengers(7, 5));
        assertFalse(mirrors.trusted(7));
        mirrors.apply(new ClientboundMoveEntityPacket.Pos(7, new VecDelta.Linear((short) 4096, (short) 0, (short) 0), true));
        tick(mirrors);
        final double[] box = new double[6];
        assertTrue(mirrors.box(7, box));
        assertEquals(-0.6875, box[0], 0.0, "a vehicle the client drives ignores the server's moves");
        final IntArrayList repairs = new IntArrayList();
        mirrors.takeRepairs(repairs);
        assertTrue(repairs.isEmpty());
        mirrors.apply(gg.hcfrevival.revival.auth.combat.SolidStreams.passengers(7));
        mirrors.takeRepairs(repairs);
        assertEquals(IntArrayList.of(7), repairs, "left where the client drove it: re-paired");
        mirrors.apply(new ClientboundRemoveEntitiesPacket(7, 9));
        assertEquals(0, mirrors.size());
    }

    @Test
    void placeholdersUntilALoggedSpawn() {
        final SolidMirrors mirrors = new SolidMirrors();
        mirrors.markUnknown(7);
        assertFalse(mirrors.trusted(7));
        assertTrue(mirrors.anyUntrusted());
        final List<Double> xs = new ArrayList<>();
        mirrors.collect(-1.0E9, -1.0E9, -1.0E9, 1.0E9, 1.0E9, 1.0E9, (minX, minY, minZ, maxX, maxY, maxZ, x, y, z, rule) -> xs.add(x));
        assertTrue(xs.isEmpty(), "a placeholder has no box to hand over");
        mirrors.apply(add(7, EntityTypes.OAK_BOAT, 0.0, 64.0, 0.0));
        assertTrue(mirrors.trusted(7));
        assertFalse(mirrors.anyUntrusted());
    }

    private static void tick(final SolidMirrors mirrors) {
        mirrors.tick(false, 1.0F, 1.0E9, 1.0E9, 1.0E9, 1.0E9, 1.0E9, 1.0E9);
    }

    private static ClientboundAddEntityPacket add(final int id, final EntityType<?> type, final double x, final double y, final double z) {
        return new ClientboundAddEntityPacket(id, new UUID(id, 1L), x, y, z, 0.0F, 0.0F, type, 0, Vec3.ZERO, 0.0);
    }

    @Test
    void removingFromTheMiddleKeepsEveryOtherCopy() {
        // Review PT2-07 / X2-06: removal swaps the last copy into the slot
        final SolidMirrors mirrors = new SolidMirrors();
        mirrors.apply(add(1, EntityTypes.OAK_BOAT, 0.0, 64.0, 0.0));
        mirrors.apply(add(2, EntityTypes.SHULKER, 4.3, 63.6, 8.9));
        mirrors.apply(add(3, EntityTypes.OAK_BOAT, 10.0, 64.0, 0.0));
        mirrors.apply(new net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket(1));
        assertEquals(2, mirrors.size());
        final double[] box = new double[6];
        assertTrue(mirrors.box(2, box) && mirrors.box(3, box), "the others stay");
        mirrors.apply(new net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket(3));
        mirrors.apply(add(4, EntityTypes.OAK_BOAT, 20.0, 64.0, 0.0));
        assertEquals(2, mirrors.size());
        assertTrue(mirrors.box(2, box) && mirrors.box(4, box));
    }

    @Test
    void aForcedPacketLeavesTheCopyUntrusted() {
        // Review R2C-06: forced at its deadline, the copy changed before the client's did
        final SolidMirrors mirrors = new SolidMirrors();
        mirrors.apply(add(7, EntityTypes.OAK_BOAT, 0.0, 64.0, 0.0));
        assertTrue(mirrors.exact(7));
        mirrors.apply(new ClientboundEntityPositionSyncPacket(7, PositionPath.of(new Vec3(0.5, 64.0, 0.0)), 0.0F, 0.0F, true), false);
        org.junit.jupiter.api.Assertions.assertFalse(mirrors.exact(7), "inexact until re-paired");
        final it.unimi.dsi.fastutil.ints.IntArrayList repairs = new it.unimi.dsi.fastutil.ints.IntArrayList();
        mirrors.takeRepairs(repairs);
        assertEquals(1, repairs.size(), "queued for a re-pair");
    }
}
