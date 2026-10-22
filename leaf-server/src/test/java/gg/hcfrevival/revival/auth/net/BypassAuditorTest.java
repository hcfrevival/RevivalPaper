package gg.hcfrevival.revival.auth.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalRegistries;
import gg.hcfrevival.revival.RevivalTest;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.network.HandlerNames;
import net.minecraft.network.VarInt;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** §15.1: physics packets written around the choke point are found, as Packet objects and pre-encoded. */
@RevivalTest
@RevivalRegistries
class BypassAuditorTest {

    private static final int SELF = 7;

    private static EmbeddedChannel channel(final BypassAuditor audit) {
        final EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast(HandlerNames.ENCODER, new ChannelOutboundHandlerAdapter()); // stands in for the game encoder
        assertTrue(audit.install(channel));
        return channel;
    }

    private static ByteBuf encoded(final ClientboundSetEntityMotionPacket packet) {
        final ByteBuf buf = Unpooled.buffer();
        VarInt.write(buf, BypassAuditor.networkId(BypassAuditor.RAW_MOTION));
        ClientboundSetEntityMotionPacket.STREAM_CODEC.encode(buf, packet);
        return buf;
    }

    @Test
    void aLoggedPacketPassesAnUnloggedOneIsABypass() {
        final ConcurrentLinkedQueue<Packet<?>> expected = new ConcurrentLinkedQueue<>();
        final BypassAuditor audit = new BypassAuditor(SELF, expected, null);
        final EmbeddedChannel channel = channel(audit);
        final ClientboundSetEntityMotionPacket ours = new ClientboundSetEntityMotionPacket(SELF, new Vec3(0.0, 0.42, 0.0));
        expected.add(ours);
        channel.writeOutbound(ours);
        assertNull(audit.pollBypassed());
        assertTrue(expected.isEmpty());
        final ClientboundSetEntityMotionPacket direct = new ClientboundSetEntityMotionPacket(SELF, new Vec3(0.0, 0.42, 0.0));
        channel.writeOutbound(direct);
        assertSame(direct, audit.pollBypassed(), "an equal packet is not the one we logged: identity counts");
        assertEquals(1, audit.packetBypasses());
        // Another entity's velocity is not this client's physics
        channel.writeOutbound(new ClientboundSetEntityMotionPacket(SELF + 1, Vec3.ZERO));
        assertNull(audit.pollBypassed());
    }

    @Test
    void loggedPacketsThatNeverGotWrittenAreSkipped() {
        final ConcurrentLinkedQueue<Packet<?>> expected = new ConcurrentLinkedQueue<>();
        final BypassAuditor audit = new BypassAuditor(SELF, expected, null);
        final EmbeddedChannel channel = channel(audit);
        final ClientboundSetEntityMotionPacket lost = new ClientboundSetEntityMotionPacket(SELF, Vec3.ZERO);
        final ClientboundSetEntityMotionPacket written = new ClientboundSetEntityMotionPacket(SELF, new Vec3(1.0, 0.0, 0.0));
        expected.add(lost);
        expected.add(written);
        channel.writeOutbound(written);
        assertNull(audit.pollBypassed());
        assertTrue(expected.isEmpty());
    }

    @Test
    void aPreEncodedVelocityIsDecodedAsABypass() {
        final BypassAuditor audit = new BypassAuditor(SELF, new ConcurrentLinkedQueue<>(), null);
        final EmbeddedChannel channel = channel(audit);
        // An encoder output for a Packet the packet level saw: not a bypass
        final ClientboundSetEntityMotionPacket ours = new ClientboundSetEntityMotionPacket(SELF + 1, Vec3.ZERO);
        channel.writeOutbound(ours);
        channel.writeOutbound(encoded(ours));
        assertNull(audit.pollBypassed());
        assertEquals(0, audit.rawBypasses());
        // PacketEvents-style: bytes written straight to the channel
        final ByteBuf raw = encoded(new ClientboundSetEntityMotionPacket(SELF, new Vec3(0.0, 0.5, 0.0)));
        final int readable = raw.readableBytes();
        channel.writeOutbound(raw);
        final Packet<?> decoded = audit.pollBypassed();
        assertTrue(decoded instanceof final ClientboundSetEntityMotionPacket motion && motion.id() == SELF && Math.abs(motion.movement().y - 0.5) < 1.0E-3,
            "decoded: " + decoded);
        assertEquals(1, audit.rawBypasses());
        assertEquals(readable, raw.readableBytes(), "the bytes go on untouched");
    }

    @Test
    void uninstallRemovesBothHandlers() {
        final BypassAuditor audit = new BypassAuditor(SELF, new ConcurrentLinkedQueue<>(), null);
        final EmbeddedChannel channel = channel(audit);
        audit.uninstall();
        assertFalse(audit.installed());
        assertNull(channel.pipeline().get(BypassAuditor.PACKET_HANDLER));
        assertNull(channel.pipeline().get(BypassAuditor.RAW_HANDLER));
        assertFalse(new BypassAuditor(SELF, new ConcurrentLinkedQueue<>(), null).install(new EmbeddedChannel()), "no game encoder, no audit");
    }
}
