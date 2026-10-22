package gg.hcfrevival.revival.auth.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.HandlerNames;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.network.protocol.game.GameProtocols;
import org.jspecify.annotations.Nullable;

/**
 * The bypass audit (design §15.1): physics packets that reach this client without passing our choke point
 * ({@code ServerCommonPacketListenerImpl.send}), so no ping brackets them and the simulation never hears of them.
 * <ul>
 *   <li>the packet-level handler sits on the tail side of the encoder and sees every {@link Packet} written to the
 *       channel (after the unbundler, so bundle members one by one): a teleport, rotation, own velocity, explosion
 *       knock or block update that {@link Transactions} did not log is a bypass (ProtocolLib with filters off,
 *       {@code Connection.send} from a plugin);</li>
 *   <li>the raw handler sits on the head side of the encoder and reads the packet id of every {@link ByteBuf}: one of
 *       those packet types that no {@link Packet} encoded is a pre-encoded write (PacketEvents, GrimAC's setbacks and
 *       transactions), decoded here to find out what it was.</li>
 * </ul>
 * Bypasses are queued for the main thread, which logs them like any packet but with upper-bound timing (the next ping's
 * ack), counts them and warns once. Both handlers run on the channel's event loop; the expectation queue is filled by
 * {@link Transactions} before the write is issued.
 */
public final class BypassAuditor {

    public static final String PACKET_HANDLER = "revival_auth_audit";
    public static final String RAW_HANDLER = "revival_auth_audit_raw";

    /** How far into the expectation queue a written packet is looked for (packets that were never written are skipped). */
    static final int SCAN = 64;

    // Raw types: the packet types the audit cares about, by their play-protocol network id
    static final int RAW_MOTION = 0;
    static final int RAW_POSITION = 1;
    static final int RAW_ROTATION = 2;
    static final int RAW_EXPLODE = 3;
    static final int RAW_BLOCK = 4;
    static final int RAW_SECTION = 5;
    static final int RAW_TYPES = 6;
    private static final int[] RAW_BY_ID = rawIds();

    private final int selfId;
    private final ConcurrentLinkedQueue<Packet<?>> expected;
    private final ConcurrentLinkedQueue<Packet<?>> bypassed = new ConcurrentLinkedQueue<>();
    private final AtomicLong packetBypasses = new AtomicLong();
    private final AtomicLong rawBypasses = new AtomicLong();
    private final @Nullable RegistryAccess registries;
    // Event loop only: packets of each raw type encoded since the last raw check
    private final int[] rawEncoded = new int[RAW_TYPES];
    private @Nullable Channel installedOn;

    final ChannelHandler packetLevel = new ChannelOutboundHandlerAdapter() {
        @Override
        public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) throws Exception {
            if (msg instanceof final Packet<?> packet) {
                BypassAuditor.this.onPacket(packet);
            }
            super.write(ctx, msg, promise);
        }
    };

    final ChannelHandler rawLevel = new ChannelOutboundHandlerAdapter() {
        @Override
        public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) throws Exception {
            if (msg instanceof final ByteBuf buf) {
                BypassAuditor.this.onRaw(buf);
            }
            super.write(ctx, msg, promise);
        }
    };

    public BypassAuditor(final int selfId, final ConcurrentLinkedQueue<Packet<?>> expected, final @Nullable RegistryAccess registries) {
        this.selfId = selfId;
        this.expected = expected;
        this.registries = registries;
    }

    /** Puts both handlers around the encoder. Returns false when the channel has no game encoder (a fake connection). */
    public boolean install(final Channel channel) {
        final ChannelPipeline pipeline = channel.pipeline();
        if (pipeline.get(HandlerNames.ENCODER) == null) {
            return false;
        }
        this.uninstall();
        pipeline.addAfter(HandlerNames.ENCODER, PACKET_HANDLER, this.packetLevel);
        pipeline.addBefore(HandlerNames.ENCODER, RAW_HANDLER, this.rawLevel);
        this.installedOn = channel;
        return true;
    }

    public void uninstall() {
        final Channel channel = this.installedOn;
        if (channel == null) {
            return;
        }
        this.installedOn = null;
        final ChannelPipeline pipeline = channel.pipeline();
        if (pipeline.get(PACKET_HANDLER) != null) {
            pipeline.remove(PACKET_HANDLER);
        }
        if (pipeline.get(RAW_HANDLER) != null) {
            pipeline.remove(RAW_HANDLER);
        }
    }

    public boolean installed() {
        return this.installedOn != null;
    }

    /** The next bypassed packet for the main thread, or null. */
    public @Nullable Packet<?> pollBypassed() {
        return this.bypassed.poll();
    }

    public long packetBypasses() {
        return this.packetBypasses.get();
    }

    public long rawBypasses() {
        return this.rawBypasses.get();
    }

    // Packet level ----------------------------------------------------------------------------------------------------

    void onPacket(final Packet<?> packet) {
        if (packet instanceof final BundlePacket<?> bundle) {
            // Ahead of the unbundler after a protocol reconfiguration: the members are what counts
            for (final Packet<?> sub : bundle.subPackets()) {
                this.onPacket(sub);
            }
            return;
        }
        final int raw = rawType(packet.type());
        if (raw < 0) {
            return;
        }
        this.rawEncoded[raw]++;
        if (!audited(packet, this.selfId)) {
            return;
        }
        if (!this.claim(packet)) {
            this.packetBypasses.incrementAndGet();
            this.bypassed.add(packet);
        }
    }

    // Finds the packet among the next expected ones; the ones before it were logged but never written. By identity, else
    // by content (most packets are records): a plugin that swaps a packet object for an equal copy is no bypass, and a
    // swapped one with other content is (the client got what was written, not what was logged)
    private boolean claim(final Packet<?> packet) {
        int index = 0;
        int equalAt = -1;
        boolean found = false;
        for (final Iterator<Packet<?>> it = this.expected.iterator(); it.hasNext() && index < SCAN; index++) {
            final Packet<?> next = it.next();
            if (next == packet) {
                found = true;
                break;
            }
            if (equalAt < 0 && next.getClass() == packet.getClass() && next.equals(packet)) {
                equalAt = index;
            }
        }
        if (!found && equalAt >= 0) {
            index = equalAt;
            found = true;
        }
        if (!found) {
            // Review E13: logged packets that were replaced on the way never match; past SCAN of them the queue would
            // hide every later match and grow for the session. Keep half a scan of them, oldest dropped first, so the
            // next logged packets still fall inside the scan
            int excess = this.expected.size() - SCAN / 2;
            while (excess-- > 0) {
                this.expected.poll();
            }
            return false;
        }
        for (int i = 0; i <= index; i++) {
            this.expected.poll();
        }
        return true;
    }

    /** Whether the audit cares about this packet for this client: its own teleport, rotation, velocity, knock, or a block. */
    static boolean audited(final Packet<?> packet, final int self) {
        return switch (packet) {
            case final ClientboundSetEntityMotionPacket motion -> motion.id() == self;
            case final ClientboundExplodePacket explode -> explode.playerKnockback().isPresent();
            case final ClientboundPlayerPositionPacket ignored -> true;
            case final ClientboundPlayerRotationPacket ignored -> true;
            case final ClientboundBlockUpdatePacket ignored -> true;
            case final ClientboundSectionBlocksUpdatePacket ignored -> true;
            default -> false;
        };
    }

    // Raw level ---------------------------------------------------------------------------------------------------------

    void onRaw(final ByteBuf buf) {
        if (!buf.isReadable()) {
            return;
        }
        // Read the id from a duplicate: the buffer goes on to the compressor exactly as it came
        final ByteBuf peek = buf.duplicate();
        final int id;
        try {
            id = VarInt.read(peek);
        } catch (final RuntimeException e) {
            return;
        }
        final int bodyStart = peek.readerIndex();
        if (id < 0 || id >= RAW_BY_ID.length || RAW_BY_ID[id] < 0) {
            return;
        }
        final int raw = RAW_BY_ID[id];
        if (this.rawEncoded[raw] > 0) {
            this.rawEncoded[raw]--; // one of the packets the packet level saw, now encoded
            return;
        }
        final Packet<?> decoded = this.decode(raw, buf.duplicate().readerIndex(bodyStart));
        if (decoded != null && audited(decoded, this.selfId)) {
            this.rawBypasses.incrementAndGet();
            this.bypassed.add(decoded);
        }
    }

    private @Nullable Packet<?> decode(final int raw, final ByteBuf body) {
        try {
            return switch (raw) {
                case RAW_MOTION -> ClientboundSetEntityMotionPacket.STREAM_CODEC.decode(body);
                case RAW_POSITION -> ClientboundPlayerPositionPacket.STREAM_CODEC.decode(new FriendlyByteBuf(body));
                case RAW_ROTATION -> ClientboundPlayerRotationPacket.STREAM_CODEC.decode(new FriendlyByteBuf(body));
                case RAW_SECTION -> ClientboundSectionBlocksUpdatePacket.STREAM_CODEC.decode(new FriendlyByteBuf(body));
                case RAW_EXPLODE -> this.registries == null ? null : ClientboundExplodePacket.STREAM_CODEC.decode(new RegistryFriendlyByteBuf(body, this.registries));
                case RAW_BLOCK -> this.registries == null ? null : ClientboundBlockUpdatePacket.STREAM_CODEC.decode(new RegistryFriendlyByteBuf(body, this.registries));
                default -> null;
            };
        } catch (final RuntimeException e) {
            return null; // not ours to understand: counted by nothing, written as it came
        }
    }

    static int rawType(final PacketType<?> type) {
        if (type == GamePacketTypes.CLIENTBOUND_SET_ENTITY_MOTION) {
            return RAW_MOTION;
        } else if (type == GamePacketTypes.CLIENTBOUND_PLAYER_POSITION) {
            return RAW_POSITION;
        } else if (type == GamePacketTypes.CLIENTBOUND_PLAYER_ROTATION) {
            return RAW_ROTATION;
        } else if (type == GamePacketTypes.CLIENTBOUND_EXPLODE) {
            return RAW_EXPLODE;
        } else if (type == GamePacketTypes.CLIENTBOUND_BLOCK_UPDATE) {
            return RAW_BLOCK;
        } else if (type == GamePacketTypes.CLIENTBOUND_SECTION_BLOCKS_UPDATE) {
            return RAW_SECTION;
        }
        return -1;
    }

    // The play protocol's network id of each audited type
    private static int[] rawIds() {
        final int[] max = {0};
        GameProtocols.CLIENTBOUND_TEMPLATE.details().listPackets((type, id) -> max[0] = Math.max(max[0], id));
        final int[] byId = new int[max[0] + 1];
        java.util.Arrays.fill(byId, -1);
        GameProtocols.CLIENTBOUND_TEMPLATE.details().listPackets((type, id) -> byId[id] = rawType(type));
        return byId;
    }

    /** The network id of an audited raw type (tests). */
    static int networkId(final int raw) {
        for (int id = 0; id < RAW_BY_ID.length; id++) {
            if (RAW_BY_ID[id] == raw) {
                return id;
            }
        }
        return -1;
    }
}
