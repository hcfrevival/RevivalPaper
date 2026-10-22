package gg.hcfrevival.revival.auth.trace;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.VarLong;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

/**
 * Writes one connection's RVTR v1 trace (report 07 §4): serverbound records in the order the main thread handled them
 * (the client's order), clientbound records in wire order (written under the transaction lock), interleaved as they
 * happen. Every STATE between PING k-1 and PING k belongs to transaction k; its effect applies to the tick whose frame
 * holds PONG k.
 * <p>
 * File: {@code <trace.directory>/<uuid>/<startEpochMs>.rvtr}. The 5-byte magic {@code RVTR 1} is plain; everything after
 * it, header included, is in zlib blocks of up to 64 KB of records: {@code [u32 compressed length][u32 raw length][data]},
 * so a crash loses at most one block. Blocks are compressed and written on a background thread.
 * <p>
 * v1 limits: no world snapshot is taken (the header's snapshot hash is zero and the region box is the ±48 block cube
 * around the player at start); chunk packets are not recorded.
 */
public final class TraceRecorder implements AutoCloseable {

    static final byte TICK_END = 0x01;
    static final byte INPUT = 0x02;
    static final byte MOVE = 0x03;
    static final byte COMMAND = 0x04;
    static final byte PONG = 0x05;
    static final byte TELEPORT_ACK = 0x06;
    static final byte ACTION = 0x07;
    static final byte SERVER_TICK = 0x40;
    static final byte PING = 0x41;
    static final byte STATE = 0x42;
    static final byte SIM = 0x43;
    static final byte CORRECTION = 0x44;
    static final byte MARK = 0x45;
    static final byte REMOTE = 0x46;

    static final int BLOCK = 64 * 1024;

    private static final Logger LOGGER = LogManager.getLogger("RevivalPaper");
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "RevivalPaper trace writer");
        thread.setDaemon(true);
        return thread;
    });

    private final Path file;
    private final OutputStream out;
    private final ByteBuf records = Unpooled.buffer(BLOCK + 4096);
    private final ByteBuf scratch;
    private final StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> clientbound;
    private final StreamCodec<ByteBuf, Packet<? super ServerGamePacketListener>> serverbound;
    private final RegistryAccess registries;

    private long lastTickEndNanos;
    private int lastServerTick;
    private int lastPingId;
    private int lastPongId;
    private long lastPosX;
    private long lastPosY;
    private long lastPosZ;
    private int lastYRot;
    private int lastXRot;
    private long records_;
    private boolean closed;

    private TraceRecorder(final Path file, final OutputStream out, final RegistryAccess registries, final boolean infiniteMaterials) {
        this.file = file;
        this.out = out;
        this.registries = registries;
        this.scratch = new RegistryFriendlyByteBuf(Unpooled.buffer(256), registries);
        this.clientbound = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registries)).codec();
        this.serverbound = GameProtocols.SERVERBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registries), new GameProtocols.Context() {
            @Override
            public boolean hasInfiniteMaterials() {
                return infiniteMaterials;
            }

            @Override
            public boolean canUseCommandBlocks() {
                return true;
            }
        }).codec();
    }

    /** Starts a trace for {@code player}; the file is created at once. */
    public static TraceRecorder start(final Path directory, final ServerPlayer player, final String configFingerprintSource) throws IOException {
        final long epochMs = System.currentTimeMillis();
        final Path file = directory.resolve(player.getUUID().toString()).resolve(epochMs + ".rvtr");
        Files.createDirectories(file.getParent());
        final OutputStream out = Files.newOutputStream(file);
        out.write(new byte[]{'R', 'V', 'T', 'R', 1});
        final TraceRecorder recorder = new TraceRecorder(file, out, player.level().registryAccess(), player.hasInfiniteMaterials());
        recorder.header(player, epochMs, configFingerprintSource);
        return recorder;
    }

    public Path file() {
        return this.file;
    }

    public synchronized long recordCount() {
        return this.records_;
    }

    // Header -------------------------------------------------------------------------------------------------------

    private synchronized void header(final ServerPlayer player, final long epochMs, final String fingerprintSource) {
        final ByteBuf b = this.records;
        VarInt.write(b, SharedConstants.getProtocolVersion());
        VarInt.write(b, SharedConstants.getCurrentVersion().dataVersion().version());
        writeUtf(b, org.bukkit.Bukkit.getVersion());
        final UUID uuid = player.getUUID();
        b.writeLong(uuid.getMostSignificantBits());
        b.writeLong(uuid.getLeastSignificantBits());
        VarInt.write(b, player.getId());
        writeUtf(b, player.getGameProfile().name());
        this.lastServerTick = MinecraftServer.currentTick;
        this.lastTickEndNanos = System.nanoTime();
        VarLong.write(b, this.lastServerTick);
        VarLong.write(b, this.lastTickEndNanos);
        VarLong.write(b, epochMs);
        // World snapshot: none in v1 (zero hash), the dimension and a ±48 block region around the player
        b.writeZero(32);
        writeUtf(b, player.level().dimension().identifier().toString());
        final int px = player.getBlockX();
        final int py = player.getBlockY();
        final int pz = player.getBlockZ();
        writeZigZag(b, px - 48);
        writeZigZag(b, py - 48);
        writeZigZag(b, pz - 48);
        writeZigZag(b, px + 48);
        writeZigZag(b, py + 48);
        writeZigZag(b, pz + 48);
        // Initial self state
        b.writeDouble(player.getX());
        b.writeDouble(player.getY());
        b.writeDouble(player.getZ());
        b.writeDouble(player.getDeltaMovement().x);
        b.writeDouble(player.getDeltaMovement().y);
        b.writeDouble(player.getDeltaMovement().z);
        b.writeFloat(player.getYRot());
        b.writeFloat(player.getXRot());
        b.writeBoolean(player.onGround());
        b.writeBoolean(player.horizontalCollision);
        b.writeBoolean(player.isSprinting());
        b.writeBoolean(player.isShiftKeyDown());
        b.writeBoolean(player.isFallFlying());
        b.writeBoolean(player.isSwimming());
        VarInt.write(b, player.getPose().id());
        int abilityFlags = 0;
        if (player.getAbilities().invulnerable) {
            abilityFlags |= 1;
        }
        if (player.getAbilities().flying) {
            abilityFlags |= 2;
        }
        if (player.getAbilities().mayfly) {
            abilityFlags |= 4;
        }
        if (player.getAbilities().instabuild) {
            abilityFlags |= 8;
        }
        b.writeByte(abilityFlags);
        b.writeFloat(player.getAbilities().getFlyingSpeed());
        b.writeFloat(player.getAbilities().getWalkingSpeed());
        VarInt.write(b, player.getFoodData().getFoodLevel());
        VarInt.write(b, player.getInventory().getSelectedSlot());
        final RegistryFriendlyByteBuf items = new RegistryFriendlyByteBuf(b, this.registries);
        for (int slot = 0; slot < 9; slot++) {
            ItemStack.OPTIONAL_STREAM_CODEC.encode(items, player.getInventory().getItem(slot));
        }
        ItemStack.OPTIONAL_STREAM_CODEC.encode(items, player.getOffhandItem());
        final java.util.Collection<AttributeInstance> attributes = player.getAttributes().getSyncableAttributes();
        VarInt.write(b, attributes.size());
        for (final AttributeInstance attribute : attributes) {
            writeUtf(b, attribute.getAttribute().getRegisteredName());
            b.writeDouble(attribute.getBaseValue());
            final java.util.Set<AttributeModifier> modifiers = attribute.getModifiers();
            VarInt.write(b, modifiers.size());
            for (final AttributeModifier modifier : modifiers) {
                writeUtf(b, modifier.id().toString());
                b.writeDouble(modifier.amount());
                b.writeByte(modifier.operation().id());
            }
        }
        final java.util.Collection<MobEffectInstance> effects = player.getActiveEffects();
        VarInt.write(b, effects.size());
        for (final MobEffectInstance effect : effects) {
            MobEffectInstance.STREAM_CODEC.encode(items, effect);
        }
        VarInt.write(b, player.gameMode().getId());
        b.writeDouble(player.fallDistance);
        b.writeLong(fingerprint(fingerprintSource));
    }

    // Serverbound ---------------------------------------------------------------------------------------------------

    public synchronized void tickEnd() {
        final long now = System.nanoTime();
        this.records.writeByte(TICK_END);
        VarInt.write(this.records, (int) Math.min(Integer.MAX_VALUE, (now - this.lastTickEndNanos) / 1000L));
        this.lastTickEndNanos = now;
        this.flushIfFull();
    }

    public synchronized void input(final Input input) {
        this.records.writeByte(INPUT);
        Input.STREAM_CODEC.encode(new net.minecraft.network.FriendlyByteBuf(this.records), input);
        this.flushIfFull();
    }

    public synchronized void move(final boolean hasPos, final boolean hasRot, final double x, final double y, final double z,
                                  final float yRot, final float xRot, final boolean onGround, final boolean horizontalCollision) {
        final ByteBuf b = this.records;
        b.writeByte(MOVE);
        b.writeByte((hasPos ? 1 : 0) | (hasRot ? 2 : 0) | (onGround ? 4 : 0) | (horizontalCollision ? 8 : 0));
        if (hasPos) {
            final long bx = Double.doubleToRawLongBits(x);
            final long by = Double.doubleToRawLongBits(y);
            final long bz = Double.doubleToRawLongBits(z);
            writeXor(b, bx ^ this.lastPosX, 8);
            writeXor(b, by ^ this.lastPosY, 8);
            writeXor(b, bz ^ this.lastPosZ, 8);
            this.lastPosX = bx;
            this.lastPosY = by;
            this.lastPosZ = bz;
        }
        if (hasRot) {
            final int ry = Float.floatToRawIntBits(yRot);
            final int rx = Float.floatToRawIntBits(xRot);
            writeXor(b, (ry ^ this.lastYRot) & 0xFFFFFFFFL, 4);
            writeXor(b, (rx ^ this.lastXRot) & 0xFFFFFFFFL, 4);
            this.lastYRot = ry;
            this.lastXRot = rx;
        }
        this.flushIfFull();
    }

    public synchronized void command(final int action, final int data) {
        this.records.writeByte(COMMAND);
        this.records.writeByte(action);
        VarInt.write(this.records, data);
        this.flushIfFull();
    }

    public synchronized void pong(final int id) {
        this.records.writeByte(PONG);
        writeZigZag(this.records, id - (this.lastPongId - 1));
        this.lastPongId = id;
        this.flushIfFull();
    }

    public synchronized void teleportAck(final int id, final double x, final double y, final double z, final float yRot, final float xRot) {
        final ByteBuf b = this.records;
        b.writeByte(TELEPORT_ACK);
        VarInt.write(b, id);
        b.writeDouble(x);
        b.writeDouble(y);
        b.writeDouble(z);
        b.writeFloat(yRot);
        b.writeFloat(xRot);
        this.flushIfFull();
    }

    /** A tick-group action packet (attack, use, dig, slot, abilities, loaded...), vanilla-encoded. */
    public synchronized void action(final Packet<? super ServerGamePacketListener> packet) {
        this.encoded(ACTION, packet, true);
    }

    // Clientbound (called under the transaction lock: wire order) ------------------------------------------------------

    public synchronized void serverTick(final int tick) {
        if (tick == this.lastServerTick) {
            return;
        }
        this.records.writeByte(SERVER_TICK);
        VarInt.write(this.records, tick - this.lastServerTick);
        this.lastServerTick = tick;
        this.flushIfFull();
    }

    public synchronized void ping(final int id) {
        this.serverTick(MinecraftServer.currentTick);
        this.records.writeByte(PING);
        writeZigZag(this.records, id - this.lastPingId);
        this.lastPingId = id;
        this.flushIfFull();
    }

    /** A logged physics packet. Chunk data is skipped (see the class comment); other players' packets go to REMOTE. */
    public synchronized void state(final Packet<?> packet) {
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket) {
            return;
        }
        this.encoded(isRemote(packet) ? REMOTE : STATE, packet, false);
    }

    // Server results ------------------------------------------------------------------------------------------------

    /** The simulation's result for one client tick: a CRC of the position and velocity bits plus a flags byte. */
    public synchronized void sim(final double x, final double y, final double z, final double vx, final double vy, final double vz, final int flags) {
        final CRC32 crc = new CRC32();
        final ByteBuf bits = Unpooled.buffer(48);
        bits.writeDouble(x).writeDouble(y).writeDouble(z).writeDouble(vx).writeDouble(vy).writeDouble(vz);
        crc.update(bits.nioBuffer());
        this.records.writeByte(SIM);
        this.records.writeInt((int) crc.getValue());
        this.records.writeByte(flags);
        this.flushIfFull();
    }

    public synchronized void correction(final int reason, final double dx, final double dy, final double dz) {
        this.records.writeByte(CORRECTION);
        this.records.writeByte(reason);
        this.records.writeFloat((float) dx);
        this.records.writeFloat((float) dy);
        this.records.writeFloat((float) dz);
        this.flushIfFull();
    }

    public synchronized void mark(final String note) {
        this.records.writeByte(MARK);
        writeUtf(this.records, note);
        this.flushIfFull();
    }

    @Override
    public synchronized void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.flushBlock();
        final OutputStream stream = this.out;
        WRITER.execute(() -> {
            try {
                stream.close();
            } catch (final IOException e) {
                LOGGER.warn("Could not close trace {}", this.file, e);
            }
        });
    }

    /** Waits for queued blocks to reach the disk (tests). */
    public static void drain() throws InterruptedException {
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        WRITER.execute(latch::countDown);
        latch.await(10, TimeUnit.SECONDS);
    }

    // Encoding --------------------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void encoded(final byte tag, final Packet<?> packet, final boolean serverbound) {
        if (this.closed) {
            return;
        }
        final ByteBuf body = this.scratch;
        body.clear();
        try {
            if (serverbound) {
                this.serverbound.encode(body, (Packet<? super ServerGamePacketListener>) packet);
            } else {
                this.clientbound.encode(body, (Packet<? super ClientGamePacketListener>) packet);
            }
        } catch (final RuntimeException e) {
            LOGGER.debug("Trace could not encode {}", packet.getClass().getSimpleName(), e);
            return;
        }
        final int packetId = VarInt.read(body);
        this.records.writeByte(tag);
        VarInt.write(this.records, packetId);
        VarInt.write(this.records, body.readableBytes());
        this.records.writeBytes(body);
        this.flushIfFull();
    }

    private static boolean isRemote(final Packet<?> packet) {
        return packet instanceof net.minecraft.network.protocol.game.ClientboundMoveEntityPacket
            || packet instanceof net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket
            || packet instanceof net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket
            || packet instanceof net.minecraft.network.protocol.game.ClientboundAddEntityPacket
            || packet instanceof net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
    }

    private void flushIfFull() {
        this.records_++;
        if (this.records.readableBytes() >= BLOCK) {
            this.flushBlock();
        }
    }

    private void flushBlock() {
        final int length = this.records.readableBytes();
        if (length == 0) {
            return;
        }
        final byte[] raw = new byte[length];
        this.records.readBytes(raw);
        this.records.clear();
        final OutputStream stream = this.out;
        final Path path = this.file;
        WRITER.execute(() -> {
            final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
            try {
                deflater.setInput(raw);
                deflater.finish();
                final java.io.ByteArrayOutputStream compressed = new java.io.ByteArrayOutputStream(raw.length / 2 + 64);
                final byte[] chunk = new byte[16384];
                while (!deflater.finished()) {
                    final int n = deflater.deflate(chunk);
                    compressed.write(chunk, 0, n);
                }
                stream.write(frame(compressed.toByteArray(), compressed.size(), raw.length));
                stream.flush();
            } catch (final IOException e) {
                LOGGER.warn("Could not write trace {}", path, e);
            } finally {
                deflater.end();
            }
        });
    }

    private static byte[] frame(final byte[] data, final int size, final int rawLength) {
        final byte[] out = new byte[8 + size];
        out[0] = (byte) (size >>> 24);
        out[1] = (byte) (size >>> 16);
        out[2] = (byte) (size >>> 8);
        out[3] = (byte) size;
        out[4] = (byte) (rawLength >>> 24);
        out[5] = (byte) (rawLength >>> 16);
        out[6] = (byte) (rawLength >>> 8);
        out[7] = (byte) rawLength;
        System.arraycopy(data, 0, out, 8, size);
        return out;
    }

    /** XOR delta: one header byte {@code leadingZeroBytes << 4 | trailingZeroBytes}, then the bytes between. */
    static void writeXor(final ByteBuf b, final long value, final int width) {
        if (value == 0L) {
            b.writeByte(width << 4);
            return;
        }
        final int leading = (Long.numberOfLeadingZeros(value) - (64 - width * 8)) / 8;
        final int trailing = Long.numberOfTrailingZeros(value) / 8;
        b.writeByte(leading << 4 | trailing);
        for (int i = width - 1 - leading; i >= trailing; i--) {
            b.writeByte((int) (value >>> (i * 8)));
        }
    }

    static void writeZigZag(final ByteBuf b, final int value) {
        VarInt.write(b, (value << 1) ^ (value >> 31));
    }

    private static void writeUtf(final ByteBuf b, final String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        VarInt.write(b, bytes.length);
        b.writeBytes(bytes);
    }

    private static long fingerprint(final String source) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < source.length(); i++) {
            hash ^= source.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    @Override
    public String toString() {
        return "TraceRecorder[" + this.file + "]";
    }

    /** For tests: the bytes of the current unflushed block. */
    synchronized byte @Nullable [] pendingBytes() {
        final byte[] bytes = new byte[this.records.readableBytes()];
        this.records.getBytes(this.records.readerIndex(), bytes);
        return bytes;
    }
}
