package gg.hcfrevival.revival.auth.replicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalTest;
import gg.hcfrevival.revival.auth.combat.MirrorEvent;
import gg.hcfrevival.revival.auth.combat.RemoteMirror;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.VecDelta;
import net.minecraft.network.protocol.game.VecDeltaCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.PositionPath;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * K = 2 replication (report 03 §6, R1-R6): packets through the real 26.3 codecs, the viewer's codec base against the real
 * {@code VecDeltaCodec}, and a {@link RemoteMirror} fed under jitter. Bit-exactness of the mirror against the real client
 * for these streams is the rig's job (MirrorVerify, "fed" trials).
 */
@RevivalTest
class PathFeedTest {

    private static final int ID = 77;
    private static final double UNIT = 1.0 / 4096.0;

    /** Encodes and decodes a packet with its stream codec: what the client receives. */
    @SuppressWarnings("unchecked")
    static Packet<ClientGamePacketListener> wire(final Packet<ClientGamePacketListener> packet) {
        final FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            if (packet instanceof final ClientboundMoveEntityPacket.PosRot move) {
                ClientboundMoveEntityPacket.PosRot.STREAM_CODEC.encode(buffer, move);
                return ClientboundMoveEntityPacket.PosRot.STREAM_CODEC.decode(buffer);
            }
            final ClientboundEntityPositionSyncPacket sync = (ClientboundEntityPositionSyncPacket) packet;
            ClientboundEntityPositionSyncPacket.STREAM_CODEC.encode(buffer, sync);
            return ClientboundEntityPositionSyncPacket.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    /** One server tick of packets for a viewer, already through the wire. */
    static List<Packet<ClientGamePacketListener>> drain(final PathFeed.Viewer viewer) {
        final List<Packet<ClientGamePacketListener>> out = new ArrayList<>();
        viewer.drain((packet, stamp) -> out.add(wire(packet)), Double.NaN, Double.NaN, Double.NaN);
        return out;
    }

    static void apply(final RemoteMirror mirror, final Packet<ClientGamePacketListener> packet) {
        if (packet instanceof final ClientboundMoveEntityPacket move) {
            mirror.apply(MirrorEvent.of(move, 0L));
        } else {
            mirror.apply(MirrorEvent.of((ClientboundEntityPositionSyncPacket) packet, 0L));
        }
    }

    @Test
    void eachStepIsOnePosRotWithATwoTickSteppedDeltaThatSurvivesTheWire() {
        final PathFeed feed = new PathFeed(ID, 64, PathFeed.Settings.DEFAULT);
        final PathFeed.Viewer viewer = feed.viewer();
        viewer.spawned(10.0, 64.0, 10.0, 0.0F, 0.0F, true, 0L);
        feed.append(10.3, 64.42, 10.1, 33.3F, -12.5F, false, 1L);
        final List<Packet<ClientGamePacketListener>> sent = new ArrayList<>();
        viewer.drain((packet, stamp) -> sent.add(packet), Double.NaN, Double.NaN, Double.NaN);
        assertEquals(2, sent.size()); // the pre-roll hold, then the step
        final ClientboundMoveEntityPacket.PosRot step = (ClientboundMoveEntityPacket.PosRot) sent.get(1);
        final ClientboundMoveEntityPacket.PosRot decoded = (ClientboundMoveEntityPacket.PosRot) wire(step);
        assertEquals(ID, MirrorEvent.entityId(decoded));
        final VecDelta.Stepped delta = assertInstanceOf(VecDelta.Stepped.class, decoded.getPositionDelta());
        assertEquals(List.of(new VecDelta.Stepped.DeltaStep((short) Math.round(0.3 * 4096.0), (short) Math.round(0.42 * 4096.0),
            (short) Math.round(0.1 * 4096.0 + 0.0), 2)).size(), delta.steps().size());
        assertEquals(2, delta.steps().getFirst().ticks());
        assertEquals(Mth.unpackDegrees(Mth.packDegrees(33.3F)), decoded.getYRot());
        assertEquals(Mth.unpackDegrees(Mth.packDegrees(-12.5F)), decoded.getXRot());
        assertTrue(decoded.hasPosition() && decoded.hasRotation());
        assertEquals(false, decoded.isOnGround());
    }

    @Test
    void theViewersCodecBaseIsTheRealCodecsAfterEveryPacket() {
        final PathFeed feed = new PathFeed(ID, 64, PathFeed.Settings.DEFAULT);
        final PathFeed.Viewer viewer = feed.viewer();
        final VecDeltaCodec client = new VecDeltaCodec();
        viewer.spawned(0.123456789, 70.000001, -5.5, 0.0F, 0.0F, true, 0L);
        client.setBase(new Vec3(0.123456789, 70.000001, -5.5)); // LivingEntity.recreateFromPacket: syncPacketPositionCodec
        final java.util.Random random = new java.util.Random(5L);
        double x = 0.123456789;
        double y = 70.000001;
        double z = -5.5;
        float yRot = 0.0F;
        for (int tick = 0; tick < 2000; tick++) {
            final int kind = random.nextInt(20);
            if (kind == 0) {
                x += 20.0 * (random.nextBoolean() ? 1 : -1); // past 8 blocks: EntityPositionSync
            } else if (kind < 6) {
                // still: keep-alive nudges
            } else {
                x += (random.nextDouble() - 0.5) * 0.6;
                y += random.nextInt(3) == 0 ? (random.nextDouble() - 0.5) * 0.5 : 0.0;
                z += (random.nextDouble() - 0.5) * 0.6;
                yRot = random.nextFloat() * 360.0F;
            }
            feed.append(x, y, z, yRot, 0.0F, true, tick);
            for (final Packet<ClientGamePacketListener> packet : drain(viewer)) {
                if (packet instanceof final ClientboundMoveEntityPacket move) {
                    final PositionPath path = move.getPositionDelta().decode(client); // ClientPacketListener.handleMoveEntity
                    client.setBase(path.endPosition());
                } else {
                    client.setBase(((ClientboundEntityPositionSyncPacket) packet).position().endPosition());
                }
            }
            assertEquals(client.getBase().x, viewer.baseX());
            assertEquals(client.getBase().y, viewer.baseY());
            assertEquals(client.getBase().z, viewer.baseZ());
        }
        assertTrue(viewer.sentSyncs() > 50);
        assertTrue(viewer.keepAlives() > 50);
    }

    @Test
    void movingStepsLandOnTheCodecGridAndJumpsPastEightBlocksAreExact() {
        final PathFeed feed = new PathFeed(ID, 64, PathFeed.Settings.DEFAULT);
        final PathFeed.Viewer viewer = feed.viewer();
        viewer.spawned(0.0, 64.0, 0.0, 0.0F, 0.0F, true, 0L);
        drain(viewer);
        feed.append(0.31, 64.2, -0.77, 0.0F, 0.0F, true, 1L);
        drain(viewer);
        assertEquals(Math.round(0.31 * 4096.0) / 4096.0, viewer.baseX());
        assertEquals(Math.round(64.2 * 4096.0) / 4096.0, viewer.baseY());
        assertEquals(Math.round(-0.77 * 4096.0) / 4096.0, viewer.baseZ());
        feed.append(20.123456789, 64.0, 0.0, 0.0F, 0.0F, true, 2L);
        final List<Packet<ClientGamePacketListener>> jump = drain(viewer);
        final ClientboundEntityPositionSyncPacket sync = assertInstanceOf(ClientboundEntityPositionSyncPacket.class, jump.getFirst());
        final PositionPath.Stepped path = assertInstanceOf(PositionPath.Stepped.class, sync.position()); // never an empty Stepped
        assertEquals(1, path.steps().size());
        assertEquals(2, path.steps().getFirst().tickOffset());
        assertEquals(20.123456789, viewer.baseX()); // full precision
    }

    @Test
    void jumpsTheClientWouldSnapAskForARespawn() {
        final PathFeed feed = new PathFeed(ID, 64, PathFeed.Settings.DEFAULT);
        final PathFeed.Viewer viewer = feed.viewer();
        viewer.spawned(0.0, 64.0, 0.0, 0.0F, 0.0F, true, 0L);
        drain(viewer);
        feed.append(100.0, 64.0, 0.0, 0.0F, 0.0F, true, 1L);
        assertEquals(PathFeed.Result.RESPAWN, viewer.drain((packet, stamp) -> {
        }, Double.NaN, Double.NaN, Double.NaN));
        // A sync within the distance of the last end but far from what the viewer still shows also re-pairs
        viewer.spawned(100.0, 64.0, 0.0, 0.0F, 0.0F, true, 1L);
        drain(viewer);
        feed.append(130.0, 64.0, 0.0, 0.0F, 0.0F, true, 2L);
        assertEquals(PathFeed.Result.RESPAWN, viewer.drain((packet, stamp) -> {
        }, 50.0, 64.0, 0.0));
        assertEquals(PathFeed.Result.SENT, viewer.drain((packet, stamp) -> {
        }, 100.0, 64.0, 0.0));
    }

    @Test
    void aViewerThatFellBehindTheRingIsRespawned() {
        final PathFeed feed = new PathFeed(ID, 8, PathFeed.Settings.DEFAULT);
        final PathFeed.Viewer viewer = feed.viewer();
        viewer.spawned(0.0, 64.0, 0.0, 0.0F, 0.0F, true, 0L);
        for (int i = 0; i < 9; i++) {
            feed.append(i * 0.1, 64.0, 0.0, 0.0F, 0.0F, true, i);
        }
        assertEquals(PathFeed.Result.RESPAWN, viewer.drain((packet, stamp) -> {
        }, Double.NaN, Double.NaN, Double.NaN));
    }

    /** Walks at a constant 0.25 blocks per tick and returns the mirror's freeze ticks (empty queue while moving). */
    private static int walk(final int silentFrom, final int silentTicks, final PathFeed.Settings settings, final RemoteMirror mirror, final double[] lastSent) {
        final PathFeed feed = new PathFeed(ID, 64, settings);
        final PathFeed.Viewer viewer = feed.viewer();
        mirror.spawn(0.0, 64.0, 0.0, 0.0F, 0.0F, 0L);
        viewer.spawned(0.0, 64.0, 0.0, 0.0F, 0.0F, true, 0L);
        final List<Packet<ClientGamePacketListener>> held = new ArrayList<>();
        int freezes = 0;
        for (int tick = 0; tick < 200; tick++) {
            feed.append(0.25 * (tick + 1), 64.0, 0.0, 0.0F, 0.0F, true, tick);
            held.addAll(drain(viewer));
            final boolean silent = tick >= silentFrom && tick < silentFrom + silentTicks;
            if (!silent) {
                for (final Packet<ClientGamePacketListener> packet : held) {
                    apply(mirror, packet);
                }
                held.clear();
            }
            if (tick > 5 && mirror.queuedSteps() == 0) {
                freezes++; // interpolate() will cancel: the player stops on screen
            }
            mirror.tick(1.0F);
        }
        lastSent[0] = 0.25 * 200;
        return freezes;
    }

    @Test
    void upToTwoSilentTicksSlowThePlayerDownInsteadOfFreezingIt() {
        final double[] last = new double[1];
        assertEquals(0, walk(Integer.MAX_VALUE, 0, PathFeed.Settings.DEFAULT, new RemoteMirror(2), last));
        assertEquals(0, walk(100, 1, PathFeed.Settings.DEFAULT, new RemoteMirror(2), last));
        assertEquals(0, walk(100, 2, PathFeed.Settings.DEFAULT, new RemoteMirror(2), last));
        // Three silent ticks drain the K = 2 buffer: the client cancels once (report 03 §6.2)
        assertEquals(1, walk(100, 3, PathFeed.Settings.DEFAULT, new RemoteMirror(2), last));
    }

    @Test
    void inSteadyStateTheViewerShowsTheMidpointOneAndAHalfStepsBehind() {
        final RemoteMirror mirror = new RemoteMirror(2);
        final double[] last = new double[1];
        walk(Integer.MAX_VALUE, 0, PathFeed.Settings.DEFAULT, mirror, last);
        // 0.25 is 1024 codec units: every decoded step is exact, and the display sits 1.5 steps behind the newest
        assertEquals(last[0] - 1.5 * 0.25, mirror.x(), 1.0E-3);
        assertEquals(64.0, mirror.y());
        assertEquals(2.0F, mirror.interpolationSpeed(), 1.0E-3F);
    }

    @Test
    void aStillPlayerIsKeptAliveOneCodecUnitAwayAndNeverFreezes() {
        final PathFeed feed = new PathFeed(ID, 64, PathFeed.Settings.DEFAULT);
        final PathFeed.Viewer viewer = feed.viewer();
        final RemoteMirror mirror = new RemoteMirror(2);
        mirror.spawn(5.5, 64.0, 5.5, 0.0F, 0.0F, 0L);
        viewer.spawned(5.5, 64.0, 5.5, 0.0F, 0.0F, true, 0L);
        for (int tick = 0; tick < 100; tick++) {
            feed.append(5.5, 64.0, 5.5, 0.0F, 0.0F, true, tick);
            for (final Packet<ClientGamePacketListener> packet : drain(viewer)) {
                apply(mirror, packet);
            }
            if (tick > 0) {
                assertTrue(mirror.queuedSteps() > 0, "starved at tick " + tick);
            }
            mirror.tick(1.0F);
            assertTrue(Math.abs(mirror.x() - 5.5) <= UNIT, "x " + mirror.x());
            assertEquals(5.5, mirror.z());
        }
        assertTrue(viewer.keepAlives() >= 49);
        // Without keep-alive every other step is ignored (target.is), the queue drains, and the next one restarts it with
        // currentStepTicks = 1 (report 03 §5.2, M2): a walk that starts then begins half a step in
        assertEquals(1, restarts(PathFeed.Settings.DEFAULT));
        assertTrue(restarts(new PathFeed.Settings(2, false, true, 48.0)) >= 49);
    }

    /** A player standing still for 100 ticks: how often the viewer's queue was empty when a packet arrived. */
    private static int restarts(final PathFeed.Settings settings) {
        final PathFeed feed = new PathFeed(ID, 64, settings);
        final PathFeed.Viewer viewer = feed.viewer();
        final RemoteMirror mirror = new RemoteMirror(2);
        mirror.spawn(5.5, 64.0, 5.5, 0.0F, 0.0F, 0L);
        viewer.spawned(5.5, 64.0, 5.5, 0.0F, 0.0F, true, 0L);
        int restarts = 0;
        for (int tick = 0; tick < 100; tick++) {
            feed.append(5.5, 64.0, 5.5, 0.0F, 0.0F, true, tick);
            final boolean idle = mirror.queuedSteps() == 0;
            for (final Packet<ClientGamePacketListener> packet : drain(viewer)) {
                apply(mirror, packet);
            }
            if (idle && mirror.queuedSteps() > 0) {
                restarts++;
            }
            mirror.tick(1.0F);
        }
        return restarts;
    }

    @Test
    void thePreRollMakesTheFirstRenderedTickTheSpawnPoint() {
        final PathFeed feed = new PathFeed(ID, 64, PathFeed.Settings.DEFAULT);
        final PathFeed.Viewer viewer = feed.viewer();
        final RemoteMirror mirror = new RemoteMirror(2);
        mirror.spawn(1.0, 64.0, 1.0, 0.0F, 0.0F, 0L);
        viewer.spawned(1.0, 64.0, 1.0, 0.0F, 0.0F, true, 0L);
        feed.append(1.25, 64.0, 1.0, 0.0F, 0.0F, true, 1L);
        for (final Packet<ClientGamePacketListener> packet : drain(viewer)) {
            apply(mirror, packet);
        }
        mirror.tick(1.0F);
        assertEquals(1.0, mirror.x()); // currentStepTicks = 1 lands inside the hold, not half-way to 1.25
        // without it the first tick jumps half a step
        final PathFeed bare = new PathFeed(ID, 64, new PathFeed.Settings(2, true, false, 48.0));
        final PathFeed.Viewer cold = bare.viewer();
        final RemoteMirror jumpy = new RemoteMirror(2);
        jumpy.spawn(1.0, 64.0, 1.0, 0.0F, 0.0F, 0L);
        cold.spawned(1.0, 64.0, 1.0, 0.0F, 0.0F, true, 0L);
        bare.append(1.25, 64.0, 1.0, 0.0F, 0.0F, true, 1L);
        for (final Packet<ClientGamePacketListener> packet : drain(cold)) {
            apply(jumpy, packet);
        }
        jumpy.tick(1.0F);
        assertEquals(1.125, jumpy.x());
    }
}
