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
    void pongsForPingsAResetDroppedAreNoViolation() {
        // Review reactivation-stale-pongs-kick: authority off and on again within an RTT
        this.send(this.selfMotion(0.1));
        this.send(this.selfMotion(0.2));
        final int first = pingId(contents(this.connection.sent.get(0)).get(0));
        final int second = pingId(contents(this.connection.sent.get(1)).get(0));
        this.transactions.reset();
        final Transactions.Acked out = new Transactions.Acked();
        assertEquals(Transactions.DUPLICATE, this.transactions.ack(first, 0, out), "answered after the reset: nothing left to apply");
        assertEquals(Transactions.DUPLICATE, this.transactions.ack(second, 0, out));
        assertEquals(0, this.transactions.violationCount());
        assertEquals(0, out.size());
        // A pong for a ping sent after the reset ends the tolerance: an old id now is unknown again
        this.send(this.selfMotion(0.3));
        final int third = pingId(contents(this.connection.sent.get(2)).get(0));
        assertEquals(Transactions.ACKED, this.transactions.ack(third, 0, out));
        assertEquals(Transactions.UNKNOWN, this.transactions.ack(first, 0, out));
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
    void aKeepAliveIsNeverBundled() {
        // Torture freeze1: a vanilla client answers a lone keep-alive on its network thread, a bundled one only when its
        // main thread runs; after a stall it answered them out of order and Paper kicked it
        this.context.coalesce = true;
        final Packet<?> a = this.selfMotion(0.1);
        final Packet<?> keepAlive = new net.minecraft.network.protocol.common.ClientboundKeepAlivePacket(42L);
        final Packet<?> b = this.selfMotion(0.2);
        this.send(a);
        this.send(keepAlive);
        this.send(b);
        assertEquals(List.of(keepAlive), this.connection.sent, "the keep-alive goes out at once, ahead of the open bundle");
        this.transactions.endOfTick(this.connection, this.context, true);
        assertEquals(2, this.connection.sent.size());
        final List<Packet<?>> bundle = contents(this.connection.sent.get(1));
        assertEquals(3, bundle.size(), "the bundle stays open across it: its ping and both motions");
        assertSame(a, bundle.get(1));
        assertSame(b, bundle.get(2));
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
        assertTrue(this.transactions.forcedOutstanding(), "the client hasn't applied it yet");
        final Transactions.Acked out = new Transactions.Acked();
        assertEquals(Transactions.ACKED, this.transactions.ack(id, 0, out), "the ping stays in the FIFO");
        // Torture F2/F5: its real pong hands the knock back once, marked: the drain the client applies it in
        assertEquals(1, out.size());
        assertSame(a, out.packet(0));
        assertEquals(OutboundClassifier.SELF_MOTION | OutboundClassifier.LANDED, out.kind(0));
        assertEquals(1, this.transactions.forcedCount());
        assertFalse(this.transactions.forcedOutstanding());
    }

    @Test
    void deadlinesCountPacedWindowsUpToACap() {
        // Torture F3: a catch-up burst after a stall doesn't count, but no more than the cap of windows in all
        this.context.window = 10;
        this.context.paced = 10;
        this.send(this.selfMotion(0.1));
        final Transactions.Acked forced = new Transactions.Acked();
        assertEquals(0, this.transactions.forceDue(20, 11, 4, 14, forced), "10 windows on, but only 1 paced");
        assertEquals(1, this.transactions.forceDue(20, 14, 4, 14, forced), "4 paced windows on");
        this.context.window = 30;
        this.context.paced = 12;
        this.send(this.selfMotion(0.2));
        assertEquals(1, this.transactions.forceDue(44, 13, 4, 14, forced), "14 windows on, paced or not");
    }

    @Test
    void stampsNeverGoBack() {
        // An extrapolated stamp (an upstream stall) can be ahead of the next window's own: the FIFO stays in due order
        this.context.window = 15;
        this.send(this.selfMotion(0.1));
        this.context.window = 12;
        this.send(this.selfMotion(0.2));
        final Transactions.Acked forced = new Transactions.Acked();
        assertEquals(0, this.transactions.forceDue(18, 4, forced), "the second ping took the first's stamp, 15");
        assertEquals(2, this.transactions.forceDue(19, 4, forced));
    }

    @Test
    void knocksInFlightAreTheUnackedAndTheForcedOnes() {
        // Torture F5: a correction sent now reaches the client after these
        this.context.window = 10;
        final Packet<?> a = this.selfMotion(0.1);
        this.send(a);
        final int first = pingId(contents(this.connection.sent.getLast()).get(0));
        final Packet<?> b = this.selfMotion(0.2);
        this.send(b);
        final Transactions.Acked knocks = new Transactions.Acked();
        assertEquals(2, this.transactions.inFlightKnocks(knocks));
        assertFalse(knocks.upperBound(0) || knocks.upperBound(1), "logged, not forced: the truth takes them at their pongs");
        final Transactions.Acked forced = new Transactions.Acked();
        assertEquals(2, this.transactions.forceDue(14, 4, forced));
        knocks.clear();
        assertEquals(2, this.transactions.inFlightKnocks(knocks));
        assertTrue(knocks.upperBound(0) && knocks.upperBound(1), "forced: the truth has them already");
        assertEquals(Transactions.ACKED, this.transactions.ack(first, 0, new Transactions.Acked()));
        knocks.clear();
        assertEquals(1, this.transactions.inFlightKnocks(knocks), "the first landed");
        assertSame(b, knocks.packet(0));
    }

    private Iterable<?> tickBundle(final double y) {
        this.connection.sent.clear();
        this.send(this.selfMotion(y));
        this.transactions.endOfTick(this.connection, this.context, true);
        return ((ClientboundBundlePacket) this.connection.sent.getLast()).subPackets();
    }

    @Test
    void aTicksBundleListIsReusedOnceItsPingIsAcked() {
        // Review PT-16: one list per viewer per tick otherwise. Only an ack proves the client read the bundle
        this.context.coalesce = true;
        final Iterable<?> first = this.tickBundle(0.1);
        final int firstPing = pingId((Packet<?>) first.iterator().next());
        final Iterable<?> second = this.tickBundle(0.2);
        assertTrue(first != second, "unacked: the first list may still be on its way");
        final Transactions.Acked out = new Transactions.Acked();
        assertEquals(Transactions.ACKED, this.transactions.ack(firstPing, 0, out));
        final Iterable<?> third = this.tickBundle(0.3);
        assertSame(first, third, "acked: its list carries a later tick");
        assertEquals(2, contents(this.connection.sent.getLast()).size());
        assertTrue(second != third);
    }

    @Test
    void aForcedPingReusesNothingAndALaterAckReusesEverythingBeforeIt() {
        this.context.coalesce = true;
        this.context.window = 10;
        final Iterable<?> first = this.tickBundle(0.1);
        final Iterable<?> second = this.tickBundle(0.2);
        final int secondPing = pingId((Packet<?>) second.iterator().next());
        final Transactions.Acked forced = new Transactions.Acked();
        assertEquals(2, this.transactions.forceDue(20, 4, forced));
        final Iterable<?> third = this.tickBundle(0.3);
        assertTrue(third != first && third != second, "forced is not read");
        // The client skipped the first pong: the second still proves both bundles were read
        assertEquals(Transactions.SKIPPED, this.transactions.ack(secondPing, 0, new Transactions.Acked()));
        final Iterable<?> fourth = this.tickBundle(0.4);
        final Iterable<?> fifth = this.tickBundle(0.5);
        assertTrue((fourth == first || fourth == second) && (fifth == first || fifth == second) && fourth != fifth);
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

    // NAS glitch 2026-10-22: a correction is forecast to where pings of its flush kind land, measured from the window the
    // client was in at the flush to the window the pong comes in

    // Landings of the client's windows so far (every sample here is from a window under 1000)
    private int landing(final int kind) {
        return this.transactions.landingWindows(kind, 1000, 1000);
    }

    private int newestPing() {
        final Packet<?> last = this.connection.sent.getLast();
        return last instanceof ClientboundPingPacket ping ? ping.getId() : pingId(contents(last).get(0));
    }

    @Test
    void aFlushBetweenTicksLandsWhereItsPongComes() {
        this.context.window = 10;
        this.send(this.selfMotion(0.1)); // not coalesced: flushed at once
        final Transactions.Acked out = new Transactions.Acked();
        assertEquals(Transactions.ACKED, this.transactions.ack(this.newestPing(), 0, 12, out));
        assertEquals(2, this.landing(LandingLag.SCOPE));
        assertEquals(-1, this.landing(LandingLag.TICK));
    }

    @Test
    void aFlushInABurstCountsFromTheWindowsWaitingBehindIt() {
        // NW-1: a 2 s freeze's burst runs window by window; the first one's flush went out with nine more TickEnds off the
        // wire behind it, and stamped with its own window it read as a landing of 11 at RTT 80
        this.context.window = 228;
        this.context.landing = 237; // nine TickEnds wait for the main thread
        this.send(this.selfMotion(0.1));
        final Transactions.Acked out = new Transactions.Acked();
        assertEquals(Transactions.ACKED, this.transactions.ack(this.newestPing(), 0, 239, out));
        assertEquals(2, this.landing(LandingLag.SCOPE));
    }

    @Test
    void theTickBundleCountsFromTheWindowRunningAtTheTickEnd() {
        this.context.coalesce = true;
        this.context.window = 5;
        this.transactions.send(this.connection, this.selfMotion(0.1), null, false, this.context); // opens the tick's bundle
        this.context.window = 6; // a TickEnd came in during the same tick
        this.transactions.endOfTick(this.connection, this.context, true);
        final Transactions.Acked out = new Transactions.Acked();
        assertEquals(Transactions.ACKED, this.transactions.ack(this.newestPing(), 0, 7, out));
        assertEquals(1, this.landing(LandingLag.TICK));
        assertEquals(-1, this.landing(LandingLag.SCOPE));
    }

    @Test
    void theAsyncTrackersPingsSayNothingOfLanding() {
        this.context.main = false;
        this.send(this.selfMotion(0.1));
        final Transactions.Acked out = new Transactions.Acked();
        assertEquals(Transactions.ACKED, this.transactions.ack(this.newestPing(), 0, 9, out));
        assertEquals(-1, this.landing(LandingLag.SCOPE));
    }

    @Test
    void aProbeGoesOutOnlyWhenTheScopeSentNothing() {
        this.context.window = 3;
        this.transactions.flushOpen(this.connection, this.context, true);
        assertEquals(1, this.connection.sent.size());
        assertInstanceOf(ClientboundPingPacket.class, this.connection.sent.getFirst(), "a lone ping");
        assertEquals(1, this.transactions.probes());
        final Transactions.Acked out = new Transactions.Acked();
        assertEquals(Transactions.ACKED, this.transactions.ack(this.newestPing(), 0, 3, out), "back within the window it went out in");
        assertEquals(0, this.landing(LandingLag.SCOPE));
        // A scope that gathered a bundle flushes it, its own ping measuring the landing
        this.context.coalesce = true;
        this.transactions.send(this.connection, this.selfMotion(0.2), null, false, this.context);
        this.transactions.flushOpen(this.connection, this.context, true);
        assertEquals(2, this.connection.sent.size());
        assertEquals(1, this.transactions.probes());
    }

    @Test
    void aPingFlushedInAStallIsNoLanding() {
        // Its window was extrapolated (no TickEnd for a while): a client freeze would make it look like any landing
        this.context.ticking = false;
        this.send(this.selfMotion(0.1));
        final Transactions.Acked out = new Transactions.Acked();
        assertEquals(Transactions.ACKED, this.transactions.ack(this.newestPing(), 0, 9, out));
        assertEquals(-1, this.landing(LandingLag.SCOPE));
    }

    @Test
    void skippedPingsAreNotLandings() {
        this.send(this.selfMotion(0.1));
        this.send(this.selfMotion(0.2));
        final Transactions.Acked out = new Transactions.Acked();
        assertEquals(Transactions.SKIPPED, this.transactions.ack(this.newestPing(), 0, 4, out));
        assertEquals(-1, this.landing(LandingLag.SCOPE));
    }

    @Test
    void aPingTooOldIsDueWhateverTheWindowsSay() {
        // Review X2-02: a client that stalled (stamps run ahead) or bunches its windows can't hold a pong past the wall
        this.context.window = 50;
        this.send(this.selfMotion(0.3));
        final Transactions.Acked out = new Transactions.Acked();
        final long now = System.nanoTime();
        assertEquals(0, this.transactions.forceDue(51, 51, 5, 15, now, 1_000_000_000L, out), "young, and not past its windows");
        assertEquals(1, this.transactions.forceDue(51, 51, 5, 15, now + 1_000_000_000L, 1_000_000_000L, out), "a second old: due");
        assertEquals(1, out.size());
    }
}
