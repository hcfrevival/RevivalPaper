package gg.hcfrevival.revival.auth.combat;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.Direction;
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
import net.minecraft.world.entity.EntityType;
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
 * Reproducible packet streams about the boats, shulkers and happy ghasts a client holds, for proving {@link SolidMirrors}
 * bit-exact. The verification rig (~/Developer/tools/auth-rig/combat, {@code SolidVerify}) compiles this same file and
 * replays every stream on real 26.3 client entities; {@code SolidMirrorsTest} replays them on the mirror alone and
 * checks the rig's fingerprint. Movement packets go through their real stream codecs.
 */
public final class SolidStreams {

    /** The entity ids a trial uses (one type each per trial). */
    public static final int[] IDS = {501, 502, 503, 504};

    /**
     * One stream: {@code ticks.get(k)} are the packets drained before client tick {@code k}, in order.
     *
     * @param types     the type of each of {@link #IDS} in this trial
     * @param tickSpeed the client's {@code getRelativeTickSpeed()}
     */
    public record Trial(List<List<Packet<?>>> ticks, EntityType<?>[] types, float tickSpeed) {
    }

    static final EntityDataAccessor<Pose> POSE = accessor(Entity.class, "DATA_POSE");
    static final EntityDataAccessor<Direction> ATTACH_FACE = accessor(net.minecraft.world.entity.monster.Shulker.class, "DATA_ATTACH_FACE_ID");
    static final EntityDataAccessor<Byte> PEEK = accessor(net.minecraft.world.entity.monster.Shulker.class, "DATA_PEEK_ID");
    static final EntityDataAccessor<Boolean> STAYS_STILL = accessor(net.minecraft.world.entity.animal.happyghast.HappyGhast.class, "STAYS_STILL");
    static final EntityDataAccessor<Boolean> BABY = accessor(net.minecraft.world.entity.AgeableMob.class, "DATA_BABY_ID");
    static final EntityDataAccessor<Float> HEALTH = accessor(net.minecraft.world.entity.LivingEntity.class, "DATA_HEALTH_ID");

    private static final EntityType<?>[] TYPES = {EntityTypes.OAK_BOAT, EntityTypes.BAMBOO_RAFT, EntityTypes.SPRUCE_CHEST_BOAT, EntityTypes.SHULKER,
        EntityTypes.SHULKER, EntityTypes.HAPPY_GHAST};

    @SuppressWarnings("unchecked")
    private static <T> EntityDataAccessor<T> accessor(final Class<?> owner, final String name) {
        try {
            final Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return (EntityDataAccessor<T>) field.get(null);
        } catch (final ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private SolidStreams() {
    }

    /** {@code ClientboundSetPassengersPacket} from ids (vanilla builds it only from an entity), through its codec. */
    public static net.minecraft.network.protocol.game.ClientboundSetPassengersPacket passengers(final int vehicle, final int... passengers) {
        final net.minecraft.network.FriendlyByteBuf buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            buffer.writeVarInt(vehicle);
            buffer.writeVarIntArray(passengers);
            return net.minecraft.network.protocol.game.ClientboundSetPassengersPacket.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    // ------------------------------------------------------------------------------------------------------------------
    // The mirror side and its fingerprint (the rig has the client side)
    // ------------------------------------------------------------------------------------------------------------------

    /** Runs a trial on the mirrors alone and folds every pre-tick and post-tick state into {@code hash}. */
    public static long run(final Trial trial, final SolidMirrors mirrors, long hash) {
        final double[] box = new double[6];
        for (int tick = 0; tick < trial.ticks().size(); tick++) {
            for (final Packet<?> packet : trial.ticks().get(tick)) {
                mirrors.apply(packet);
            }
            hash = fold(hash, mirrors, box);
            mirrors.tick(false, trial.tickSpeed(), 1.0E9, 1.0E9, 1.0E9, 1.0E9, 1.0E9, 1.0E9);
            hash = fold(hash, mirrors, box);
        }
        return hash;
    }

    /** Folds presence, exactness and the box of each id. */
    public static long fold(long hash, final SolidMirrors mirrors, final double[] box) {
        for (final int id : IDS) {
            final boolean held = mirrors.box(id, box);
            hash = mix(hash, held ? mirrors.exact(id) ? 2 : 1 : 0);
            if (held) {
                for (final double v : box) {
                    hash = mix(hash, Double.doubleToRawLongBits(v));
                }
            }
        }
        return hash;
    }

    private static long mix(final long hash, final long value) {
        long h = (hash ^ value) * 0x9E3779B97F4A7C15L;
        h ^= h >>> 29;
        return h;
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Streams
    // ------------------------------------------------------------------------------------------------------------------

    /**
     * One to four entities, each a boat, a raft, a chest boat, a shulker or a happy ghast, and every packet kind the
     * client handles for them: moves (linear and stepped deltas, rotation only), syncs and teleports near and past the
     * 64-block snap, entity data (attach face, peek, health, still timeout, baby, pose), scale, despawns and respawns.
     */
    public static Trial adversarial(final Random random, final int length) {
        final int count = 1 + random.nextInt(IDS.length);
        final EntityType<?>[] types = new EntityType<?>[count];
        final double[][] pos = new double[count][];
        for (int i = 0; i < count; i++) {
            types[i] = TYPES[random.nextInt(TYPES.length)];
            pos[i] = origin(random, i);
        }
        final float tickSpeed = random.nextInt(6) == 0 ? new float[] {1.25F, 2.0F, 3.7F}[random.nextInt(3)] : 1.0F;
        final List<List<Packet<?>>> ticks = new ArrayList<>(length);
        for (int tick = 0; tick < length; tick++) {
            final List<Packet<?>> ops = new ArrayList<>(4);
            if (tick == 0) {
                for (int i = 0; i < count; i++) {
                    ops.add(add(random, IDS[i], types[i], pos[i]));
                }
            }
            final int roll = random.nextInt(100);
            final int n = roll < 30 ? 0 : roll < 65 ? 1 : roll < 85 ? 2 : 4;
            for (int k = 0; k < n; k++) {
                final int i = random.nextInt(count);
                final int id = IDS[i];
                final EntityType<?> type = types[i];
                final boolean living = type == EntityTypes.SHULKER || type == EntityTypes.HAPPY_GHAST;
                final int kind = random.nextInt(1000);
                if (kind < 450) {
                    ops.add(move(random, id));
                    drift(random, pos[i]);
                } else if (kind < 580) {
                    ops.add(sync(random, id, pos[i]));
                } else if (kind < 680) {
                    ops.add(teleport(random, id, pos[i]));
                } else if (kind < 850) {
                    ops.add(data(random, id, type));
                } else if (kind < 910 && living) {
                    ops.add(scale(random, id));
                } else if (kind < 940) {
                    ops.add(new ClientboundRemoveEntitiesPacket(id));
                } else if (kind < 980) {
                    ops.add(add(random, id, type, pos[i]));
                }
            }
            ticks.add(ops);
        }
        return new Trial(ticks, types, tickSpeed);
    }

    private static double[] origin(final Random random, final int index) {
        return switch (random.nextInt(5)) {
            // Next to each other near a section corner: the client's entity order shows (one corner where section z and y
            // change sign, so the key's two's-complement bits order them)
            case 0 -> new double[] {15.0 + index * 0.75 + random.nextDouble() * 1.5, 63.0 + random.nextDouble() * 2.0, -16.5 + random.nextDouble() * 1.5};
            case 1 -> new double[] {15.0 + index * 0.75 + random.nextDouble() * 1.5, -1.0 + random.nextDouble() * 2.0, -1.0 + random.nextDouble() * 2.0};
            case 2 -> new double[] {random.nextInt(2000) - 1000 + random.nextDouble(), 64.0, random.nextInt(2000) - 1000 + random.nextDouble()};
            case 3 -> new double[] {(random.nextBoolean() ? 1 : -1) * (3.0E7 + random.nextDouble() * 10.0), 70.0, 2.9999999E7};
            default -> new double[] {(random.nextDouble() - 0.5) * 20000.0, random.nextDouble() * 300.0 - 60.0, (random.nextDouble() - 0.5) * 20000.0};
        };
    }

    private static void drift(final Random random, final double[] pos) {
        pos[0] += (random.nextDouble() - 0.5) * 2.0;
        pos[1] += (random.nextDouble() - 0.5) * 0.8;
        pos[2] += (random.nextDouble() - 0.5) * 2.0;
    }

    private static ClientboundAddEntityPacket add(final Random random, final int id, final EntityType<?> type, final double[] pos) {
        final byte yByte = (byte) random.nextInt(256);
        final byte xByte = (byte) random.nextInt(256);
        return new ClientboundAddEntityPacket(id, new UUID(id, random.nextLong()), pos[0], pos[1], pos[2], Mth.unpackDegrees(xByte),
            Mth.unpackDegrees(yByte), type, 0, Vec3.ZERO, Mth.unpackDegrees(yByte));
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

    private static ClientboundMoveEntityPacket move(final Random random, final int id) {
        final boolean onGround = random.nextBoolean();
        final byte yByte = (byte) random.nextInt(256);
        final byte xByte = (byte) random.nextInt(256);
        final int kind = random.nextInt(10);
        if (kind < 2) {
            return MirrorStreams.roundTrip(new ClientboundMoveEntityPacket.Rot(id, yByte, xByte, onGround));
        }
        final VecDelta delta;
        if (random.nextInt(10) < 8) {
            delta = new VecDelta.Linear(axis(random), axis(random), axis(random));
        } else {
            final int steps = 1 + random.nextInt(3);
            final List<VecDelta.Stepped.DeltaStep> list = new ArrayList<>(steps);
            for (int i = 0; i < steps; i++) {
                list.add(new VecDelta.Stepped.DeltaStep(axis(random), axis(random), axis(random), 1 + random.nextInt(3)));
            }
            delta = new VecDelta.Stepped(list);
        }
        return kind < 6 ? MirrorStreams.roundTrip(new ClientboundMoveEntityPacket.PosRot(id, delta, yByte, xByte, onGround))
            : MirrorStreams.roundTrip(new ClientboundMoveEntityPacket.Pos(id, delta, onGround));
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
        final double range = far ? 200.0 : 6.0;
        if (random.nextInt(8) == 0) {
            return new Vec3(pos[0], pos[1], pos[2]); // the same target again
        }
        return new Vec3(pos[0] + (random.nextDouble() - 0.5) * range, pos[1] + (random.nextDouble() - 0.5) * range / 4.0,
            pos[2] + (random.nextDouble() - 0.5) * range);
    }

    private static ClientboundEntityPositionSyncPacket sync(final Random random, final int id, final double[] pos) {
        final boolean far = random.nextInt(5) == 0;
        final PositionPath path;
        if (random.nextInt(4) != 0) {
            path = PositionPath.of(near(random, pos, far));
        } else {
            final int steps = 1 + random.nextInt(3);
            final List<PositionStep> list = new ArrayList<>(steps);
            for (int i = 0; i < steps; i++) {
                list.add(new PositionStep(near(random, pos, far), 1 + random.nextInt(3)));
            }
            path = PositionPath.stepped(list);
        }
        final Vec3 end = path.endPosition();
        pos[0] = end.x;
        pos[1] = end.y;
        pos[2] = end.z;
        return MirrorStreams.roundTrip(new ClientboundEntityPositionSyncPacket(id, path, rotation(random), rotation(random), random.nextBoolean()));
    }

    private static ClientboundTeleportEntityPacket teleport(final Random random, final int id, final double[] pos) {
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
        return MirrorStreams.roundTrip(ClientboundTeleportEntityPacket.teleport(id, values, relatives, random.nextBoolean()));
    }

    private static ClientboundSetEntityDataPacket data(final Random random, final int id, final EntityType<?> type) {
        final List<SynchedEntityData.DataValue<?>> values = new ArrayList<>(2);
        final int roll = random.nextInt(10);
        if (type == EntityTypes.SHULKER) {
            if (roll < 4) {
                final int peek = switch (random.nextInt(5)) {
                    case 0 -> 0;
                    case 1 -> 100;
                    case 2 -> 30;
                    default -> random.nextInt(101);
                };
                values.add(SynchedEntityData.DataValue.create(PEEK, (byte) peek));
            } else if (roll < 7) {
                values.add(SynchedEntityData.DataValue.create(ATTACH_FACE, Direction.values()[random.nextInt(6)]));
            } else if (roll < 9) {
                values.add(SynchedEntityData.DataValue.create(HEALTH, random.nextInt(4) == 0 ? 0.0F : 1.0F + random.nextFloat() * 29.0F));
            } else {
                values.add(SynchedEntityData.DataValue.create(POSE, random.nextBoolean() ? Pose.STANDING : Pose.DYING));
            }
        } else if (type == EntityTypes.HAPPY_GHAST) {
            if (roll < 4) {
                values.add(SynchedEntityData.DataValue.create(STAYS_STILL, random.nextBoolean()));
            } else if (roll < 6) {
                values.add(SynchedEntityData.DataValue.create(BABY, random.nextInt(3) == 0));
            } else if (roll < 8) {
                values.add(SynchedEntityData.DataValue.create(HEALTH, random.nextInt(4) == 0 ? 0.0F : 1.0F + random.nextFloat() * 19.0F));
            } else {
                values.add(SynchedEntityData.DataValue.create(POSE, random.nextBoolean() ? Pose.STANDING : Pose.DYING));
            }
        } else {
            values.add(SynchedEntityData.DataValue.create(POSE, Pose.STANDING));
        }
        return new ClientboundSetEntityDataPacket(id, values);
    }

    private static ClientboundUpdateAttributesPacket scale(final Random random, final int id) {
        final double base = switch (random.nextInt(4)) {
            case 0 -> 1.0;
            case 1 -> 0.5;
            case 2 -> 2.5;
            default -> 0.0625 + random.nextDouble() * 15.9;
        };
        final AttributeInstance instance = new AttributeInstance(Attributes.SCALE, ignored -> {
        });
        instance.setBaseValue(base);
        if (random.nextInt(3) == 0) {
            instance.addTransientModifier(new AttributeModifier(Identifier.withDefaultNamespace("rig_scale"), (random.nextDouble() - 0.3) * 2.0,
                AttributeModifier.Operation.values()[random.nextInt(3)]));
        }
        return new ClientboundUpdateAttributesPacket(id, List.of(instance));
    }
}
