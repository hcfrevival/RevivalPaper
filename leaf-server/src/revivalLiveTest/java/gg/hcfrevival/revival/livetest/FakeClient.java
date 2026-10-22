package gg.hcfrevival.revival.livetest;

import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * A fake player whose connection behaves like a vanilla 26.3 client's at the packet level (report 07 §3.2):
 * <ul>
 *   <li>its connection is in the server's connection list, so {@code ServerGamePacketListenerImpl.tick} runs at the real
 *       point of the server tick;</li>
 *   <li>it answers what a client must: pings with pongs (from inside bundles, in the order the bundle holds them),
 *       teleports with one {@code AcceptTeleportation} carrying the resulting position, keep-alives, chunk batches;</li>
 *   <li>one {@link #clientTick} sends a client tick in vanilla order: drain replies first (the client handles packets at
 *       frame start), then actions, input if changed, the sprint command if changed, the move packet
 *       {@code LocalPlayer.sendPosition} would choose, and {@code ServerboundClientTickEndPacket}.</li>
 * </ul>
 * Called from the main thread, packets are handled at once, as the packet processor would.
 */
final class FakeClient {

    /** A packet the server sent, stamped with the server tick, as the client handled it (bundles unwrapped). */
    record Received(int serverTick, Packet<?> packet, boolean inBundle) {
    }

    final class FakeConnection extends Connection {
        FakeConnection() {
            super(PacketFlow.SERVERBOUND);
            this.channel = new EmbeddedChannel();
        }

        @Override
        public void send(final Packet<?> packet, final @Nullable ChannelFutureListener listener, final boolean flush) {
            FakeClient.this.raw.add(written(packet));
            FakeClient.this.receive(packet, false);
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        // No pipeline to reconfigure, but Connection.tick only ticks the listener it holds (ServerGamePacketListenerImpl.tick:
        // keep-alive, tickPlayer, the teleport and block-ack bookkeeping), so hold it
        @Override
        public <T extends PacketListener> void setupInboundProtocol(final ProtocolInfo<T> protocol, final T packetListener) {
            try {
                final java.lang.reflect.Field field = Connection.class.getDeclaredField("packetListener");
                field.setAccessible(true);
                field.set(this, packetListener);
            } catch (final ReflectiveOperationException e) {
                throw new IllegalStateException("Connection.packetListener", e);
            }
        }

        @Override
        public void setupOutboundProtocol(final ProtocolInfo<?> protocol) {
        }

        @Override
        public void setReadOnly() {
        }

        @Override
        public void handleDisconnection() {
        }
    }

    // A bundle as the wire carries it: its members when it was written. Netty unbundles at the write, and the server
    // reuses a tick bundle's list once its ping is acked (Transactions, review PT-16)
    private static Packet<?> written(final Packet<?> packet) {
        if (packet instanceof final net.minecraft.network.protocol.game.ClientboundBundlePacket bundle) {
            final List<Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener>> members = new ArrayList<>();
            for (final Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener> sub : bundle.subPackets()) {
                members.add(sub);
            }
            return new net.minecraft.network.protocol.game.ClientboundBundlePacket(members);
        }
        return packet;
    }

    /** Every packet object written to the connection, bundles not unwrapped (a bundle as it was written). */
    final List<Packet<?>> raw = new CopyOnWriteArrayList<>();
    final List<Received> received = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<Packet<? super ServerGamePacketListener>> replies = new ConcurrentLinkedQueue<>();
    private final MinecraftServer server;
    private final FakeConnection connection;
    final ServerPlayer player;

    private PositionMoveRotation clientState = new PositionMoveRotation(Vec3.ZERO, Vec3.ZERO, 0.0F, 0.0F);
    private Input lastInput = Input.EMPTY;
    private boolean wasSprinting;
    private Vec3 lastSentPos = Vec3.ZERO;
    private float lastSentYRot;
    private float lastSentXRot;
    private boolean lastOnGround;
    private boolean lastHorizontalCollision;
    private int positionReminder;
    private boolean loaded;
    /** Chunks this client holds: what it was sent, minus what it was told to forget. */
    final it.unimi.dsi.fastutil.longs.LongOpenHashSet chunks = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    int ticks;

    FakeClient(final MinecraftServer server, final ServerLevel level, final String name) {
        this.server = server;
        this.connection = new FakeConnection();
        final GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(("revival-auth-" + name).getBytes()), name);
        this.player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        this.player.isRealPlayer = true;
        server.getPlayerList().placeNewPlayer(this.connection, this.player, CommonListenerCookie.createInitial(profile, false));
        server.getConnection().getConnections().add(this.connection);
    }

    // Clientbound ---------------------------------------------------------------------------------------------------

    void receive(final Packet<?> packet, final boolean inBundle) {
        if (packet instanceof final BundlePacket<?> bundle) {
            for (final Packet<?> sub : bundle.subPackets()) {
                this.receive(sub, true);
            }
            return;
        }
        this.received.add(new Received(MinecraftServer.currentTick, packet, inBundle));
        switch (packet) {
            case final net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket chunk -> this.chunks.add(net.minecraft.world.level.ChunkPos.pack(chunk.x(), chunk.z()));
            case final net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket forget -> this.chunks.remove(net.minecraft.world.level.ChunkPos.pack(forget.pos().x(), forget.pos().z()));
            case final net.minecraft.network.protocol.game.ClientboundRespawnPacket ignored -> this.chunks.clear();
            case final ClientboundPingPacket ping -> this.replies.add(new ServerboundPongPacket(ping.getId()));
            // Netty-thread replies on a real client; queued here so they never re-enter the server's send
            case final ClientboundKeepAlivePacket keepAlive -> this.replies.add(new ServerboundKeepAlivePacket(keepAlive.getId()));
            case final ClientboundChunkBatchFinishedPacket ignored -> this.replies.add(new ServerboundChunkBatchReceivedPacket(20.0F));
            case final ClientboundPlayerPositionPacket teleport -> {
                // ClientPacketListener.handleMovePlayer: apply, then accept with the resulting position and rotation
                this.clientState = PositionMoveRotation.calculateAbsolute(this.clientState, teleport.change(), teleport.relatives());
                final Vec3 p = this.clientState.position();
                this.replies.add(new ServerboundAcceptTeleportationPacket(teleport.id(), p.x, p.y, p.z, this.clientState.yRot(), this.clientState.xRot()));
            }
            default -> {
            }
        }
    }

    /** Packets of one type the server sent since index {@code from} of {@link #received}. */
    <P extends Packet<?>> List<P> receivedOfType(final Class<P> type, final int from) {
        final List<P> out = new ArrayList<>();
        for (int i = from; i < this.received.size(); i++) {
            if (type.isInstance(this.received.get(i).packet())) {
                out.add(type.cast(this.received.get(i).packet()));
            }
        }
        return out;
    }

    /** The position the client holds after the teleports it applied. */
    Vec3 clientPosition() {
        return this.clientState.position();
    }

    // Serverbound ---------------------------------------------------------------------------------------------------

    /** Sends the queued drain replies (pongs, teleport accepts) now, as a frame with no tick would. */
    void drain() {
        Packet<? super ServerGamePacketListener> reply;
        while ((reply = this.replies.poll()) != null) {
            this.deliver(reply);
        }
    }

    /**
     * One client tick. {@code actions} are keybind packets sent before movement; {@code position}, {@code onGround} and
     * {@code horizontalCollision} are what the client computed. Mirrors LocalPlayer.sendChanges/sendPosition (26.3).
     */
    void clientTick(final List<Packet<? super ServerGamePacketListener>> actions, final Input input, final boolean sprinting,
                    final Vec3 position, final float yRot, final float xRot, final boolean onGround, final boolean horizontalCollision) {
        final ArrayDeque<Packet<? super ServerGamePacketListener>> batch = new ArrayDeque<>();
        Packet<? super ServerGamePacketListener> reply;
        while ((reply = this.replies.poll()) != null) {
            batch.add(reply);
        }
        if (!this.loaded && this.chunks.contains(net.minecraft.world.level.ChunkPos.pack(net.minecraft.util.Mth.floor(this.clientState.position().x) >> 4,
            net.minecraft.util.Mth.floor(this.clientState.position().z) >> 4))) {
            // ClientPacketListener.tick → notifyPlayerLoaded, once the loading screen has the chunk the player is in
            // (LevelLoadStatusManager); until then the client holds still on the loading screen
            batch.add(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
            this.loaded = true;
        }
        batch.addAll(actions);
        if (!input.equals(this.lastInput)) {
            batch.add(new ServerboundPlayerInputPacket(input));
            this.lastInput = input;
        }
        if (sprinting != this.wasSprinting) {
            batch.add(new ServerboundPlayerCommandPacket(this.player, sprinting
                ? ServerboundPlayerCommandPacket.Action.START_SPRINTING : ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
            this.wasSprinting = sprinting;
        }
        this.positionReminder++;
        final boolean move = position.distanceToSqr(this.lastSentPos) > 4.0E-8 || this.positionReminder >= 20; // Mth.square(2.0E-4)
        final boolean rot = yRot != this.lastSentYRot || xRot != this.lastSentXRot;
        if (move && rot) {
            batch.add(new ServerboundMovePlayerPacket.PosRot(position, yRot, xRot, onGround, horizontalCollision));
        } else if (move) {
            batch.add(new ServerboundMovePlayerPacket.Pos(position, onGround, horizontalCollision));
        } else if (rot) {
            batch.add(new ServerboundMovePlayerPacket.Rot(yRot, xRot, onGround, horizontalCollision));
        } else if (onGround != this.lastOnGround || horizontalCollision != this.lastHorizontalCollision) {
            batch.add(new ServerboundMovePlayerPacket.StatusOnly(onGround, horizontalCollision));
        }
        if (move) {
            this.lastSentPos = position;
            this.positionReminder = 0;
        }
        if (rot) {
            this.lastSentYRot = yRot;
            this.lastSentXRot = xRot;
        }
        this.lastOnGround = onGround;
        this.lastHorizontalCollision = horizontalCollision;
        this.clientState = new PositionMoveRotation(position, this.clientState.deltaMovement(), yRot, xRot);
        batch.add(ServerboundClientTickEndPacket.INSTANCE);
        for (final Packet<? super ServerGamePacketListener> packet : batch) {
            this.deliver(packet);
        }
        this.ticks++;
    }

    /** Hands a packet to the server the way Connection.channelRead0 would: handled now on the main thread, else queued. */
    @SuppressWarnings("unchecked")
    void deliver(final Packet<? super ServerGamePacketListener> packet) {
        final ServerGamePacketListener listener = this.player.connection;
        if (this.server.packetProcessor().isSameThread()) {
            ((Packet<ServerGamePacketListener>) packet).handle(listener);
        } else {
            this.server.packetProcessor().scheduleIfPossible(listener, (Packet<ServerGamePacketListener>) packet);
        }
    }

    /** Whether the client has reported {@code PlayerLoaded}: before that LocalPlayer.tick returns at once (no physics). */
    boolean loaded() {
        return this.loaded;
    }

    /** A new local player (respawn): it reports {@code PlayerLoaded} again on its first tick. */
    void respawned() {
        this.loaded = false;
    }

    void disconnect() {
        this.server.getConnection().getConnections().remove(this.connection);
        this.server.getPlayerList().remove(this.player);
    }
}
