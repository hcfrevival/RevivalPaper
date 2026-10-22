package gg.hcfrevival.revival.auth.combat;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.protocol.game.VecDelta;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.PositionPath;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.jspecify.annotations.Nullable;

/**
 * One clientbound packet's effect on one remote player, as a {@link RemoteMirror} applies it. Built from the packet the
 * server wrote to the viewer (the {@code of(...)} factories read exactly the values the 26.3 client reads) and handed to
 * {@link MirrorBook#onEvent} with the viewer's client tick it was applied before.
 * <p>
 * {@code stamp} on the movement events is the server time, in ms, of the position the event carries: for
 * {@link gg.hcfrevival.revival.auth.replicate.PathFeed} packets the time the target's tick was simulated, otherwise the
 * send time. {@link HitValidator} measures the rewind of a hit from it.
 */
public sealed interface MirrorEvent {

    /**
     * {@code ClientboundAddEntityPacket} for a player: {@code ClientPacketListener.handleAddEntity} (26.3 :574-596),
     * {@code LivingEntity.recreateFromPacket} (:4016-4031), {@code RemotePlayer.recreateFromPacket} (:82-85).
     * {@code yRot}/{@code xRot} are {@code packet.getYRot()}/{@code getXRot()}, already unpacked from bytes.
     */
    record Spawn(double x, double y, double z, float yRot, float xRot, long stamp) implements MirrorEvent {
    }

    /** {@code ClientboundRemoveEntitiesPacket} listing the target ({@code handleRemoveEntities}, :777-791). */
    record Remove() implements MirrorEvent {
    }

    /**
     * {@code ClientboundMoveEntityPacket} Pos/PosRot/Rot ({@code handleMoveEntity}, :728-755). {@code delta} is the
     * packet's {@code VecDelta} as decoded on the wire ({@code Linear} or {@code Stepped}), rotations unpacked.
     */
    record Move(boolean hasPosition, boolean hasRotation, VecDelta delta, float yRot, float xRot, long stamp) implements MirrorEvent {
    }

    /** {@code ClientboundEntityPositionSyncPacket} ({@code handleEntityPositionSync}, :649-674). */
    record Sync(PositionPath path, float yRot, float xRot, long stamp) implements MirrorEvent {
    }

    /** {@code ClientboundTeleportEntityPacket} ({@code handleTeleportEntity} :677-698, {@code setValuesFromPositionPacket} :809-829). */
    record Teleport(PositionMoveRotation change, Set<Relative> relatives, long stamp) implements MirrorEvent {
    }

    /** {@code DATA_POSE} in a {@code ClientboundSetEntityDataPacket}: dimensions change at packet time (Entity.java:3520-3524). */
    record SetPose(Pose pose) implements MirrorEvent {
    }

    /**
     * The client's {@code minecraft:scale} value after a {@code ClientboundUpdateAttributesPacket}. It reaches the box only
     * in the target's next entity tick ({@code LivingEntity.tick} → {@code refreshDirtyAttributes}, :2868, :1135-1157).
     */
    record SetScale(float scale) implements MirrorEvent {
    }

    /** The target starts or stops riding on the viewer's client ({@code ClientboundSetPassengersPacket}). */
    record Riding(boolean riding) implements MirrorEvent {
    }

    /** The target's tab-list game mode became or stopped being spectator: spectators are not pickable (Player.java:1644). */
    record Spectator(boolean spectator) implements MirrorEvent {
    }

    /** Something reached the target on the viewer that this model can't time or replay (a packet outside a pinged bundle). */
    record Inexact() implements MirrorEvent {
    }

    /** The viewer's client dropped its level ({@code Respawn}, dimension change): every mirror goes. The target id is ignored. */
    record Clear() implements MirrorEvent {
    }

    /**
     * {@code ClientboundSetPassengersPacket} ({@code handleSetEntityPassengersPacket}, :1147-1178): the vehicle ejects
     * everyone, then each listed entity starts riding it. A book-level event: the target id is the vehicle's.
     */
    record Passengers(int[] passengers) implements MirrorEvent {
    }

    Remove REMOVE = new Remove();
    Inexact INEXACT = new Inexact();
    Clear CLEAR = new Clear();

    static Spawn of(final ClientboundAddEntityPacket packet, final long stamp) {
        return new Spawn(packet.getX(), packet.getY(), packet.getZ(), packet.getYRot(), packet.getXRot(), stamp);
    }

    static Move of(final ClientboundMoveEntityPacket packet, final long stamp) {
        return new Move(packet.hasPosition(), packet.hasRotation(), packet.getPositionDelta(), packet.getYRot(), packet.getXRot(), stamp);
    }

    static Sync of(final ClientboundEntityPositionSyncPacket packet, final long stamp) {
        return new Sync(packet.position(), packet.yRot(), packet.xRot(), stamp);
    }

    static Teleport of(final ClientboundTeleportEntityPacket packet, final long stamp) {
        return new Teleport(packet.change(), packet.relatives(), stamp);
    }

    /** The pose in the packet, or null when it doesn't set one. A player's only {@code Pose}-typed data value is {@code DATA_POSE}. */
    static @Nullable SetPose pose(final ClientboundSetEntityDataPacket packet) {
        SetPose pose = null;
        for (final SynchedEntityData.DataValue<?> value : packet.packedItems()) {
            if (value.value() instanceof final Pose p) {
                pose = new SetPose(p); // assignValues applies in order: the last one wins
            }
        }
        return pose;
    }

    /**
     * The scale the client computes from the packet, or null when it has no {@code minecraft:scale} snapshot. The client
     * resets the instance ({@code setBaseValue}, {@code removeModifiers}, {@code addTransientModifier} each,
     * ClientPacketListener.java:2377-2386), so a fresh instance with the same inputs computes the same value.
     */
    static @Nullable SetScale scale(final ClientboundUpdateAttributesPacket packet) {
        SetScale scale = null;
        for (final ClientboundUpdateAttributesPacket.AttributeSnapshot snapshot : packet.getValues()) {
            final Holder<Attribute> attribute = snapshot.attribute();
            if (attribute.value() != Attributes.SCALE.value()) {
                continue;
            }
            final AttributeInstance instance = new AttributeInstance(attribute, ignored -> {
            });
            instance.setBaseValue(snapshot.base());
            for (final AttributeModifier modifier : snapshot.modifiers()) {
                instance.addTransientModifier(modifier);
            }
            // LivingEntity.getScale (:572-575): sanitizeScale((float) value), the identity for players
            scale = new SetScale((float) instance.getValue());
        }
        return scale;
    }

    /**
     * The entity id of a move packet. Vanilla keeps it in a protected field with no getter; read it through a VarHandle so
     * this works on the vanilla jar (rig) and before any accessor patch exists.
     */
    static int entityId(final ClientboundMoveEntityPacket packet) {
        return (int) Ids.MOVE_ENTITY_ID.get(packet);
    }

    /** Holder for the VarHandle (interfaces can't have private static fields). */
    final class Ids {
        static final VarHandle MOVE_ENTITY_ID;

        static {
            try {
                MOVE_ENTITY_ID = MethodHandles.privateLookupIn(ClientboundMoveEntityPacket.class, MethodHandles.lookup())
                    .findVarHandle(ClientboundMoveEntityPacket.class, "entityId", int.class);
            } catch (final ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private Ids() {
        }
    }
}
