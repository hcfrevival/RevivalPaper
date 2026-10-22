package gg.hcfrevival.revival.auth.combat;

import gg.hcfrevival.revival.auth.replicate.PathFeed;
import io.netty.buffer.Unpooled;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.protocol.game.VecDelta;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.PositionPath;
import net.minecraft.world.entity.PositionStep;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/**
 * Reproducible packet streams about one remote player, for proving {@link RemoteMirror} bit-exact. The verification rig
 * (~/Developer/tools/auth-rig/combat) compiles this same file and replays every stream on the real 26.3 client classes;
 * {@code MirrorFingerprintTest} replays them on the mirror alone and checks the rig's fingerprint.
 * <p>
 * Two kinds of trial: {@link #adversarial} (every packet kind the client handles for a remote player, with odd tick
 * offsets, repeats, snaps, rotations past ±180°, poses, scales, despawns) and {@link #fed} (a walking/jumping/teleporting
 * player replicated through {@link PathFeed} with K = 2, delivered with jitter and silent ticks). Movement packets are
 * passed through their real stream codecs, so what is fed is what a client decodes.
 */
public final class MirrorStreams {

    public static final int ENTITY_ID = 4242;
    public static final UUID PROFILE = new UUID(0x5EEDL, 0xC0FFEEL);

    /** Something applied before a client tick: a packet, as the client decoded it. */
    public record Op(Packet<?> packet) {
    }

    /**
     * One stream: {@code ticks.get(k)} are the packets drained before client tick {@code k}, in order.
     *
     * @param tickSpeed the client's {@code getRelativeTickSpeed()}
     */
    public record Trial(List<List<Op>> ticks, float tickSpeed) {
    }

    private static final EntityDataAccessor<Pose> DATA_POSE = poseAccessor();

    @SuppressWarnings("unchecked")
    private static EntityDataAccessor<Pose> poseAccessor() {
        try {
            final Field field = Entity.class.getDeclaredField("DATA_POSE");
            field.setAccessible(true);
            return (EntityDataAccessor<Pose>) field.get(null);
        } catch (final ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private MirrorStreams() {
    }

    /** A {@code DATA_POSE} value as a {@code ClientboundSetEntityDataPacket} carries it. */
    public static SynchedEntityData.DataValue<Pose> poseValue(final Pose pose) {
        return SynchedEntityData.DataValue.create(DATA_POSE, pose);
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Applying and fingerprinting (the mirror side; the rig has the client side)
    // ------------------------------------------------------------------------------------------------------------------

    /** The event a packet makes for the mirror, or null when it doesn't touch the target. */
    public static MirrorEvent event(final Packet<?> packet, final long stamp) {
        return switch (packet) {
            case final ClientboundAddEntityPacket add -> MirrorEvent.of(add, stamp);
            case final ClientboundRemoveEntitiesPacket remove -> MirrorEvent.REMOVE;
            case final ClientboundMoveEntityPacket move -> MirrorEvent.of(move, stamp);
            case final ClientboundEntityPositionSyncPacket sync -> MirrorEvent.of(sync, stamp);
            case final ClientboundTeleportEntityPacket teleport -> MirrorEvent.of(teleport, stamp);
            case final ClientboundSetEntityDataPacket data -> MirrorEvent.pose(data);
            case final ClientboundUpdateAttributesPacket attributes -> MirrorEvent.scale(attributes);
            default -> throw new IllegalArgumentException(packet.toString());
        };
    }

    /** Runs a trial on a mirror alone and folds every pick-time and post-tick state into {@code hash}. */
    public static long run(final Trial trial, final RemoteMirror mirror, long hash) {
        for (int tick = 0; tick < trial.ticks().size(); tick++) {
            for (final Op op : trial.ticks().get(tick)) {
                final MirrorEvent event = event(op.packet(), tick);
                if (event != null) {
                    mirror.apply(event);
                }
            }
            hash = fold(hash, mirror); // the box the attack ray of this tick sees
            mirror.tick(trial.tickSpeed());
            hash = fold(hash, mirror);
        }
        return hash;
    }

    /** Folds the observable state: presence, box, position, rotation, and the interpolator's own state. */
    public static long fold(long hash, final RemoteMirror m) {
        hash = mix(hash, m.present() ? 1 : 0);
        if (!m.present()) {
            return hash;
        }
        hash = mix(hash, Double.doubleToRawLongBits(m.minX()));
        hash = mix(hash, Double.doubleToRawLongBits(m.minY()));
        hash = mix(hash, Double.doubleToRawLongBits(m.minZ()));
        hash = mix(hash, Double.doubleToRawLongBits(m.maxX()));
        hash = mix(hash, Double.doubleToRawLongBits(m.maxY()));
        hash = mix(hash, Double.doubleToRawLongBits(m.maxZ()));
        hash = mix(hash, Double.doubleToRawLongBits(m.x()));
        hash = mix(hash, Double.doubleToRawLongBits(m.y()));
        hash = mix(hash, Double.doubleToRawLongBits(m.z()));
        hash = mix(hash, Float.floatToRawIntBits(m.yRot()));
        hash = mix(hash, Float.floatToRawIntBits(m.xRot()));
        hash = mix(hash, Float.floatToRawIntBits(m.currentStepTicks()));
        hash = mix(hash, Float.floatToRawIntBits(m.remainingTicks()));
        hash = mix(hash, Float.floatToRawIntBits(m.interpolationSpeed()));
        return mix(hash, m.queuedSteps());
    }

    private static long mix(final long hash, final long value) {
        long h = (hash ^ value) * 0x9E3779B97F4A7C15L;
        h ^= h >>> 29;
        return h;
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Adversarial streams
    // ------------------------------------------------------------------------------------------------------------------

    /** Every packet kind, with the corner cases the client's handlers branch on. */
    public static Trial adversarial(final Random random, final int length) {
        final List<List<Op>> ticks = new ArrayList<>(length);
        final float tickSpeed = random.nextInt(5) == 0 ? new float[] {1.25F, 1.5F, 2.0F, 3.7F}[random.nextInt(4)] : 1.0F;
        final double[] pos = origin(random);
        byte yByte = (byte) random.nextInt(256);
        byte xByte = (byte) (random.nextInt(181) - 90);
        for (int tick = 0; tick < length; tick++) {
            final List<Op> ops = new ArrayList<>(3);
            if (tick == 0) {
                ops.add(new Op(add(pos, yByte, xByte)));
            }
            final int roll = random.nextInt(100);
            final int count = roll < 35 ? 0 : roll < 75 ? 1 : roll < 90 ? 2 : 3;
            for (int i = 0; i < count; i++) {
                final int kind = random.nextInt(1000);
                if (random.nextInt(4) != 0) {
                    yByte = (byte) random.nextInt(256);
                    xByte = (byte) random.nextInt(256);
                }
                if (kind < 600) {
                    ops.add(new Op(move(random, yByte, xByte)));
                    drift(random, pos);
                } else if (kind < 720) {
                    ops.add(new Op(sync(random, pos)));
                } else if (kind < 790) {
                    ops.add(new Op(teleport(random, pos)));
                } else if (kind < 880) {
                    ops.add(new Op(new ClientboundSetEntityDataPacket(ENTITY_ID, List.of(SynchedEntityData.DataValue.create(DATA_POSE, pose(random))))));
                } else if (kind < 950) {
                    ops.add(new Op(scale(random)));
                } else if (kind < 975) {
                    ops.add(new Op(new ClientboundRemoveEntitiesPacket(ENTITY_ID)));
                } else {
                    ops.add(new Op(add(pos, yByte, xByte)));
                }
            }
            ticks.add(ops);
        }
        return new Trial(ticks, tickSpeed);
    }

    private static double[] origin(final Random random) {
        return switch (random.nextInt(6)) {
            case 0 -> new double[] {0.0, 0.0, 0.0};
            case 1 -> new double[] {random.nextInt(2000) - 1000, 64.0, random.nextInt(2000) - 1000};
            case 2 -> new double[] {random.nextInt(200) + 1.0E-7, 63.99999990000001, -random.nextInt(200) - 0.3};
            case 3 -> new double[] {(random.nextBoolean() ? 1 : -1) * (3.0E7 + random.nextDouble() * 10.0), 70.0, 2.9999999E7};
            default -> new double[] {(random.nextDouble() - 0.5) * 20000.0, random.nextDouble() * 300.0 - 60.0, (random.nextDouble() - 0.5) * 20000.0};
        };
    }

    private static void drift(final Random random, final double[] pos) {
        pos[0] += (random.nextDouble() - 0.5) * 2.0;
        pos[1] += (random.nextDouble() - 0.5) * 0.8;
        pos[2] += (random.nextDouble() - 0.5) * 2.0;
    }

    private static ClientboundAddEntityPacket add(final double[] pos, final byte yByte, final byte xByte) {
        return new ClientboundAddEntityPacket(ENTITY_ID, PROFILE, pos[0], pos[1], pos[2], Mth.unpackDegrees(xByte), Mth.unpackDegrees(yByte),
            EntityTypes.PLAYER, 0, Vec3.ZERO, Mth.unpackDegrees(yByte));
    }

    private static short axis(final Random random) {
        final int roll = random.nextInt(100);
        if (roll < 30) {
            return 0;
        }
        if (roll < 90) {
            return (short) (random.nextInt(4001) - 2000);
        }
        return (short) (random.nextInt(65536) - 32768);
    }

    private static int stepTicks(final Random random) {
        final int roll = random.nextInt(100);
        if (roll < 2) {
            return 0;
        }
        if (roll < 50) {
            return 2;
        }
        if (roll < 70) {
            return 1;
        }
        return 1 + random.nextInt(20);
    }

    private static ClientboundMoveEntityPacket move(final Random random, final byte yByte, final byte xByte) {
        final boolean onGround = random.nextBoolean();
        final int kind = random.nextInt(10);
        if (kind < 2) {
            return roundTrip(new ClientboundMoveEntityPacket.Rot(ENTITY_ID, yByte, xByte, onGround));
        }
        final VecDelta delta;
        if (random.nextInt(10) < 4) {
            delta = new VecDelta.Linear(axis(random), axis(random), axis(random));
        } else {
            final int steps = 1 + random.nextInt(3);
            final List<VecDelta.Stepped.DeltaStep> list = new ArrayList<>(steps);
            final boolean still = random.nextInt(6) == 0;
            for (int i = 0; i < steps; i++) {
                list.add(still ? new VecDelta.Stepped.DeltaStep((short) 0, (short) 0, (short) 0, stepTicks(random))
                    : new VecDelta.Stepped.DeltaStep(axis(random), axis(random), axis(random), stepTicks(random)));
            }
            delta = new VecDelta.Stepped(list);
        }
        return kind < 6 ? roundTrip(new ClientboundMoveEntityPacket.PosRot(ENTITY_ID, delta, yByte, xByte, onGround))
            : roundTrip(new ClientboundMoveEntityPacket.Pos(ENTITY_ID, delta, onGround));
    }

    private static float rotation(final Random random) {
        return switch (random.nextInt(20)) {
            case 0 -> -0.0F;
            case 1 -> Float.NaN;
            case 2 -> 360.0F;
            case 3 -> -720.5F;
            case 4, 5, 6, 7 -> Mth.unpackDegrees((byte) random.nextInt(256));
            default -> (random.nextFloat() - 0.5F) * 1440.0F;
        };
    }

    private static Vec3 near(final Random random, final double[] pos, final boolean far) {
        final double range = far ? 200.0 : 16.0;
        if (random.nextInt(10) == 0) {
            return new Vec3(pos[0], pos[1], pos[2]); // same as the last absolute position
        }
        return new Vec3(pos[0] + (random.nextDouble() - 0.5) * range, pos[1] + (random.nextDouble() - 0.5) * range / 4.0,
            pos[2] + (random.nextDouble() - 0.5) * range);
    }

    private static ClientboundEntityPositionSyncPacket sync(final Random random, final double[] pos) {
        final boolean far = random.nextInt(5) == 0;
        final PositionPath path;
        if (random.nextBoolean()) {
            path = PositionPath.of(near(random, pos, far));
        } else {
            final int steps = 1 + random.nextInt(3);
            final List<PositionStep> list = new ArrayList<>(steps);
            for (int i = 0; i < steps; i++) {
                list.add(new PositionStep(near(random, pos, far), stepTicks(random)));
            }
            path = PositionPath.stepped(list);
        }
        final Vec3 end = path.endPosition();
        pos[0] = end.x;
        pos[1] = end.y;
        pos[2] = end.z;
        return roundTrip(new ClientboundEntityPositionSyncPacket(ENTITY_ID, path, rotation(random), rotation(random), random.nextBoolean()));
    }

    private static ClientboundTeleportEntityPacket teleport(final Random random, final double[] pos) {
        final Set<Relative> relatives = EnumSet.noneOf(Relative.class);
        for (final Relative relative : Relative.values()) {
            if (random.nextInt(3) == 0) {
                relatives.add(relative);
            }
        }
        final boolean far = random.nextInt(5) == 0;
        final Vec3 target = near(random, pos, far);
        final Vec3 change = relatives.contains(Relative.X) || relatives.contains(Relative.Y) || relatives.contains(Relative.Z)
            ? new Vec3((random.nextDouble() - 0.5) * (far ? 200.0 : 4.0), (random.nextDouble() - 0.5) * 2.0, (random.nextDouble() - 0.5) * (far ? 200.0 : 4.0))
            : target;
        pos[0] = target.x;
        pos[1] = target.y;
        pos[2] = target.z;
        final PositionMoveRotation values = new PositionMoveRotation(change, new Vec3(random.nextDouble(), 0.0, -random.nextDouble()), rotation(random),
            rotation(random));
        return roundTrip(ClientboundTeleportEntityPacket.teleport(ENTITY_ID, values, relatives, random.nextBoolean()));
    }

    private static Pose pose(final Random random) {
        final int roll = random.nextInt(10);
        if (roll < 3) {
            return Pose.STANDING;
        }
        if (roll < 6) {
            return Pose.CROUCHING;
        }
        final Pose[] all = Pose.values();
        return all[random.nextInt(all.length)];
    }

    private static ClientboundUpdateAttributesPacket scale(final Random random) {
        final double base = switch (random.nextInt(4)) {
            case 0 -> 1.0;
            case 1 -> 0.5;
            case 2 -> 2.0;
            default -> 0.0625 + random.nextDouble() * 15.9;
        };
        // The public constructor snapshots instances (the snapshot-list one is private in vanilla)
        final AttributeInstance instance = new AttributeInstance(Attributes.SCALE, ignored -> {
        });
        instance.setBaseValue(base);
        final int count = random.nextInt(3);
        for (int i = 0; i < count; i++) {
            instance.addTransientModifier(new AttributeModifier(Identifier.withDefaultNamespace("rig_" + i), (random.nextDouble() - 0.3) * 2.0,
                AttributeModifier.Operation.values()[random.nextInt(3)]));
        }
        return new ClientboundUpdateAttributesPacket(ENTITY_ID, List.of(instance));
    }

    // ------------------------------------------------------------------------------------------------------------------
    // PathFeed streams (what the authoritative server will actually send)
    // ------------------------------------------------------------------------------------------------------------------

    /**
     * A player walking, sprint-jumping, standing still, turning and teleporting, one authoritative step per client tick
     * (sometimes 0 or 2 per server tick), replicated by {@link PathFeed} and delivered to the viewer in one bundle per
     * server tick, with a latency that jitters by up to {@code maxJitter} ticks (order kept).
     */
    public static Trial fed(final Random random, final int length, final int maxJitter, final PathFeed.Settings settings) {
        final List<List<Op>> ticks = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            ticks.add(new ArrayList<>(2));
        }
        final PathFeed feed = new PathFeed(ENTITY_ID, 64, settings);
        final PathFeed.Viewer viewer = feed.viewer();
        final double[] pos = {random.nextInt(2000) - 1000 + random.nextDouble(), 64.0, random.nextInt(2000) - 1000 + random.nextDouble()};
        float yRot = random.nextFloat() * 360.0F - 180.0F;
        float xRot = random.nextFloat() * 60.0F - 30.0F;
        double vy = 0.0;
        int mode = 0;
        int modeTicks = 0;
        int lastArrival = 0;
        final List<Packet<?>> bundle = new ArrayList<>();
        final PathFeed.Sink sink = (packet, stamp) -> bundle.add(roundTrip(packet));
        for (int server = 0; server < length; server++) {
            bundle.clear();
            if (server == 0) {
                bundle.add(add(pos, Mth.packDegrees(yRot), Mth.packDegrees(xRot)));
                viewer.spawned(pos[0], pos[1], pos[2], yRot, xRot, true, server);
            }
            // The player's client ticks that reached the server this tick
            final int roll = random.nextInt(100);
            final int steps = roll < 6 ? 0 : roll < 94 ? 1 : 2;
            for (int s = 0; s < steps; s++) {
                if (--modeTicks <= 0) {
                    mode = random.nextInt(10);
                    modeTicks = 5 + random.nextInt(40);
                }
                switch (mode) {
                    case 0, 1 -> {
                        // still: maybe turning the head
                        if (random.nextInt(3) == 0) {
                            yRot += (random.nextFloat() - 0.5F) * 30.0F;
                        }
                    }
                    case 2 -> {
                        // teleport: an 8-40 block hop (EntityPositionSync) or past the respawn distance
                        final double d = random.nextInt(4) == 0 ? 60.0 + random.nextDouble() * 200.0 : 9.0 + random.nextDouble() * 31.0;
                        final double a = random.nextDouble() * Math.PI * 2.0;
                        pos[0] += Math.cos(a) * d;
                        pos[2] += Math.sin(a) * d;
                        mode = 0;
                    }
                    default -> {
                        // walking or sprint-jumping, turning
                        yRot += (random.nextFloat() - 0.5F) * 20.0F;
                        xRot = Mth.clamp(xRot + (random.nextFloat() - 0.5F) * 6.0F, -90.0F, 90.0F);
                        final double speed = mode >= 7 ? 0.28 : 0.215;
                        final double rad = Math.toRadians(yRot);
                        pos[0] += -Math.sin(rad) * speed;
                        pos[2] += Math.cos(rad) * speed;
                        if (mode >= 8 && pos[1] <= 64.0 && random.nextInt(4) == 0) {
                            vy = 0.42;
                        }
                        pos[1] += vy;
                        vy = (vy - 0.08) * 0.98;
                        if (pos[1] <= 64.0) {
                            pos[1] = 64.0;
                            vy = 0.0;
                        }
                    }
                }
                feed.append(pos[0], pos[1], pos[2], yRot, xRot, pos[1] <= 64.0, server);
            }
            if (viewer.drain(sink, Double.NaN, Double.NaN, Double.NaN) == PathFeed.Result.RESPAWN) {
                bundle.add(new ClientboundRemoveEntitiesPacket(ENTITY_ID));
                bundle.add(add(pos, Mth.packDegrees(yRot), Mth.packDegrees(xRot)));
                viewer.spawned(pos[0], pos[1], pos[2], yRot, xRot, pos[1] <= 64.0, server);
                viewer.drain(sink, Double.NaN, Double.NaN, Double.NaN);
            }
            if (bundle.isEmpty()) {
                continue;
            }
            // Delivered before client tick server + 2 + jitter, never overtaking the previous bundle
            final int arrival = Math.max(lastArrival, server + 2 + (maxJitter == 0 ? 0 : random.nextInt(maxJitter + 1)));
            lastArrival = arrival;
            if (arrival < length) {
                for (final Packet<?> packet : bundle) {
                    ticks.get(arrival).add(new Op(packet));
                }
            }
        }
        return new Trial(ticks, 1.0F);
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Codec round trips: the client sees the decoded packet
    // ------------------------------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static <P extends Packet<?>> P roundTrip(final P packet) {
        final FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            switch (packet) {
                case final ClientboundMoveEntityPacket.PosRot p -> {
                    ClientboundMoveEntityPacket.PosRot.STREAM_CODEC.encode(buffer, p);
                    return (P) ClientboundMoveEntityPacket.PosRot.STREAM_CODEC.decode(buffer);
                }
                case final ClientboundMoveEntityPacket.Pos p -> {
                    ClientboundMoveEntityPacket.Pos.STREAM_CODEC.encode(buffer, p);
                    return (P) ClientboundMoveEntityPacket.Pos.STREAM_CODEC.decode(buffer);
                }
                case final ClientboundMoveEntityPacket.Rot p -> {
                    ClientboundMoveEntityPacket.Rot.STREAM_CODEC.encode(buffer, p);
                    return (P) ClientboundMoveEntityPacket.Rot.STREAM_CODEC.decode(buffer);
                }
                case final ClientboundEntityPositionSyncPacket p -> {
                    ClientboundEntityPositionSyncPacket.STREAM_CODEC.encode(buffer, p);
                    return (P) ClientboundEntityPositionSyncPacket.STREAM_CODEC.decode(buffer);
                }
                case final ClientboundTeleportEntityPacket p -> {
                    ClientboundTeleportEntityPacket.STREAM_CODEC.encode(buffer, p);
                    return (P) ClientboundTeleportEntityPacket.STREAM_CODEC.decode(buffer);
                }
                default -> {
                    return packet;
                }
            }
        } finally {
            buffer.release();
        }
    }
}
