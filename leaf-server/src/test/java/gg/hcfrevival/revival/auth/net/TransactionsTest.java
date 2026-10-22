package gg.hcfrevival.revival.auth.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalRegistries;
import gg.hcfrevival.revival.RevivalTest;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Design §3.2 and §15.1: ping ids, wrapping, coalescing, FIFO acks, deadlines. */
@RevivalTest
@RevivalRegistries
class TransactionsTest {

    private Transactions transactions;
    private CapturingConnection connection;
    private TestContext context;

    @BeforeEach
    void setUp() {
        this.transactions = new Transactions();
        this.connection = new CapturingConnection();
        this.context = new TestContext();
    }

    private Packet<?> selfMotion(final double y) {
        return new ClientboundSetEntityMotionPacket(this.context.self, new Vec3(0.0, y, 0.0));
    }

    private Packet<?> otherMotion() {
        return new ClientboundSetEntityMotionPacket(this.context.self + 1, new Vec3(0.0, 0.4, 0.0));
    }

    private void send(final Packet<?> packet) {
        this.transactions.send(this.connection, packet, null, true, this.context);
    }

    private static List<Packet<?>> contents(final Packet<?> bundle) {
        final List<Packet<?>> out = new ArrayList<>();
        for (final Packet<? super ClientGamePacketListener> sub : ((ClientboundBundlePacket) bundle).subPackets()) {
            out.add(sub);
        }
        return out;
    }

    private static int pingId(final Packet<?> packet) {
        return ((ClientboundPingPacket) packet).getId();
    }

    @Test
    void physicsPacketGoesOutBundledBehindOurPing() {
        final Packet<?> motion = this.selfMotion(0.4);
        this.send(motion);
        assertEquals(1, this.connection.sent.size());
        final List<Packet<?>> bundle = contents(this.connection.sent.getFirst());
        assertEquals(2, bundle.size());
        final int id = pingId(bundle.get(0));
        assertTrue(Transactions.ours(id), "id " + id + " is in our band");
        assertTrue(id <= Transactions.PING_MAX && id >= Transactions.PING_MIN);
        assertTrue(id < -32768, "below GrimAC's short range");
        assertSame(motion, bundle.get(1));
        assertEquals(1, this.transactions.outstandingPings());
        assertEquals(1, this.transactions.outstandingEntries());
    }

    @Test
    void otherPacketsGoOutUntouched() {
        final Packet<?> other = this.otherMotion();
        this.send(other);
        assertEquals(List.of(other), this.connection.sent);
        assertEquals(0, this.transactions.outstandingPings());
    }

    @Test
    void idsCountDownInsideTheBand() {
        this.send(this.selfMotion(0.1));
        this.send(this.selfMotion(0.2));
        final int first = pingId(contents(this.connection.sent.get(0)).get(0));
        final int second = pingId(contents(this.connection.sent.get(1)).get(0));
        assertEquals(first - 1, second);
    }

    @Test
    void pongReleasesEntriesInSendOrderOldestFirst() {
        final Packet<?> a = this.selfMotion(0.1);
        final Packet<?> b = this.selfMotion(0.2);
        this.send(a);
        this.send(b);
        final int first = pingId(contents(this.connection.sent.get(0)).get(0));
        final int second = pingId(contents(this.connection.sent.get(1)).get(0));
        final Transactions.Acked out = new Transactions.Acked();
        assertEquals(Transactions.ACKED, this.transactions.ack(first, 0, out));
        assertEquals(1, out.size());
        assertSame(a, out.packet(0));
        assertEquals(OutboundClassifier.SELF_MOTION, out.kind(0));
        assertFalse(out.upperBound(0));
        assertEquals(Transactions.ACKED, this.transactions.ack(second, 0, out));
        assertEquals(2, out.size());
        assertSame(b, out.packet(1));
        assertEquals(0, this.transactions.outstandingPings());
    }

    @Test
    void skippedPingsAreAViolationButStillRelease() {
        this.send(this.selfMotion(0.1));
        this.send(this.selfMotion(0.2));
        final int second = pingId(contents(this.connection.sent.get(1)).get(0));
        final Transactions.Acked out = new Transactions.Acked();
        assertEquals(Transactions.SKIPPED, this.transactions.ack(second, 0, out));
        assertEquals(2, out.size(), "everything up to the acked ping is released");
        assertEquals(1, this.transactions.violationCount());
        assertEquals(1, this.transactions.skippedCount());
    }

    @Test
    void duplicatesAreIgnoredAndUnknownIdsAreViolations() {
        this.send(this.selfMotion(0.1));
        final int id = pingId(contents(this.connection.sent.get(0)).get(0));
        final Transactions.Acked out = new Transactions.Acked();
        assertEquals(Transactions.ACKED, this.transactions.ack(id, 0, out));
        assertEquals(Transactions.DUPLICATE, this.transactions.ack(id, 0, out));
        assertEquals(Transactions.UNKNOWN, this.transactions.ack(id - 1000, 0, out));
        assertEquals(1, this.transactions.violationCount());
        assertEquals(1, out.size());
    }

    @Test
    void foreignIdsPassThrough() {
        final Transactions.Acked out = new Transactions.Acked();
        assertEquals(Transactions.FOREIGN, this.transactions.ack(5, 0, out));
        assertEquals(Transactions.FOREIGN, this.transactions.ack(-(42 & 0x7FFF), 0, out), "GrimAC's ids are not ours");
        assertEquals(0, this.transactions.violationCount());
    }

    @Test
    void packetsThatCantBeBundledGetATrailingPing() {
        final Packet<?> notReady = new ClientboundBlockUpdatePacket(BlockPos.ZERO, Blocks.STONE.defaultBlockState()) {
            @Override
            public boolean isReady() {
                return false;
            }
        };
        this.send(notReady);
        assertEquals(2, this.connection.sent.size());
        assertSame(notReady, this.connection.sent.get(0));
        final int id = pingId(this.connection.sent.get(1));
        final Transactions.Acked out = new Transactions.Acked();
        this.transactions.ack(id, 0, out);
        assertEquals(1, out.size());
        assertTrue(out.upperBound(0), "applied at or before the ack tick");
        assertEquals(OutboundClassifier.BLOCK_UPDATE, out.kind(0));
    }

    @Test
    void aServerTickGathersEverythingAfterTheFirstPhysicsPacketIntoOneBundle() {
        this.context.coalesce = true;
        final Packet<?> before = this.otherMotion();
        final Packet<?> a = this.selfMotion(0.1);
        final Packet<?> between = this.otherMotion();
        final Packet<?> b = this.selfMotion(0.2);
        this.send(before);
        this.send(a);
        this.send(between);
        this.send(b);
        assertEquals(List.of(before), this.connection.sent, "the bundle waits for the end of the tick");
        this.transactions.endOfTick(this.connection, this.context, true);
        assertEquals(2, this.connection.sent.size());
        final List<Packet<?>> bundle = contents(this.connection.sent.get(1));
        assertEquals(4, bundle.size());
        assertInstanceOf(ClientboundPingPacket.class, bundle.get(0));
        assertSame(a, bundle.get(1));
        assertSame(between, bundle.get(2), "order is kept: nothing overtakes the bundle");
        assertSame(b, bundle.get(3));
        assertEquals(1, this.transactions.outstandingPings(), "one ping for the whole tick");
        final Transactions.Acked out = new Transactions.Acked();
        this.transactions.ack(pingId(bundle.get(0)), 0, out);
        assertEquals(2, out.size());
        assertSame(a, out.packet(0));
        assertSame(b, out.packet(1));
    }

    @Test
    void aTickWithNothingToSayStillSendsAHeartbeat() {
        this.context.coalesce = true;
        this.transactions.endOfTick(this.connection, this.context, true);
        assertEquals(1, this.connection.sent.size());
        assertInstanceOf(ClientboundPingPacket.class, this.connection.sent.getFirst());
        this.connection.sent.clear();
        this.send(this.selfMotion(0.3));
        this.transactions.endOfTick(this.connection, this.context, true);
        assertEquals(1, this.connection.sent.size(), "the tick's bundle carried the ping: no heartbeat on top");
    }

    @Test
    void trackerBundlesGetOurPingInserted() {
        final Packet<?> a = this.selfMotion(0.1);
        final Packet<?> other = this.otherMotion();
        @SuppressWarnings("unchecked")
        final ClientboundBundlePacket tracker = new ClientboundBundlePacket(List.of((Packet<? super ClientGamePacketListener>) other, (Packet<? super ClientGamePacketListener>) a));
        this.send(tracker);
        final List<Packet<?>> bundle = contents(this.connection.sent.getFirst());
        assertEquals(3, bundle.size());
        assertInstanceOf(ClientboundPingPacket.class, bundle.get(0));
        assertSame(other, bundle.get(1));
        assertSame(a, bundle.get(2));
    }

    @Test
    void entriesPastTheirDeadlineAreForcedAndTheLatePongReleasesNothing() {
        this.context.window = 10;
        final Packet<?> a = this.selfMotion(0.1);
        this.send(a);
        final int id = pingId(contents(this.connection.sent.getFirst()).get(0));
        final Transactions.Acked forced = new Transactions.Acked();
        assertEquals(0, this.transactions.forceDue(13, 4, forced), "not due yet");
        assertEquals(1, this.transactions.forceDue(14, 4, forced));
        assertEquals(1, forced.size());
        assertSame(a, forced.packet(0));
        final Transactions.Acked out = new Transactions.Acked();
        assertEquals(Transactions.ACKED, this.transactions.ack(id, 0, out), "the ping stays in the FIFO");
        assertEquals(0, out.size());
        assertEquals(1, this.transactions.forcedCount());
    }

    @Test
    void aReplicatedStepKeepsItsOwnStamp() {
        this.context.stamp = 123_456L;
        this.send(this.selfMotion(0.1));
        this.context.stamp = 0L;
        this.send(this.selfMotion(0.2));
        final Transactions.Acked out = new Transactions.Acked();
        this.transactions.ack(pingId(contents(this.connection.sent.get(1)).get(0)), 0, out);
        assertEquals(123_456L, out.sentMillis(0), "the step's stamp, not the send time");
        assertTrue(out.sentMillis(1) > 1_000_000_000_000L, "otherwise the send time");
    }
}
