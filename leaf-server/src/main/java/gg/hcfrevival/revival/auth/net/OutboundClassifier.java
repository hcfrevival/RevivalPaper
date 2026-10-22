package gg.hcfrevival.revival.auth.net;

import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.world.entity.EntityType;

/**
 * Which server→client packets change what the player's client simulates (report 04 §7 table), and so are logged
 * under a transaction and applied to the server's copy of the client at the client tick their ping is acked.
 * <p>
 * Lookup is one identity hash on {@code packet.type()}, then an entity-id test for the packets that only matter
 * when they are about this player (or, for the attacker's lag-compensation mirrors, about another player).
 */
public final class OutboundClassifier {

    public static final int NONE = 0;
    /** {@code SetEntityMotion} for self: velocity replaced by the LpVec3-decoded value. */
    public static final int SELF_MOTION = 1;
    /** {@code PlayerPosition}: teleport or correction. */
    public static final int SELF_POSITION = 2;
    /** {@code PlayerRotation}: rotation only; the client answers with a {@code MovePlayer.Rot} with false flags. */
    public static final int SELF_ROTATION = 3;
    /** {@code Explode} with player knockback: velocity added. */
    public static final int EXPLODE = 4;
    public static final int ABILITIES = 5;
    public static final int SELF_ATTRIBUTES = 6;
    public static final int SELF_EFFECT_ADD = 7;
    public static final int SELF_EFFECT_REMOVE = 8;
    public static final int SELF_ENTITY_DATA = 9;
    public static final int BLOCK_UPDATE = 10;
    public static final int SECTION_UPDATE = 11;
    public static final int BLOCK_ACK = 12;
    /** Chunk data: never bundled (Paper's anti-xray readiness and extra packets); a ping goes right after it. */
    public static final int CHUNK = 13;
    public static final int FORGET_CHUNK = 14;
    /** {@code GameEvent CHANGE_GAME_MODE}. */
    public static final int GAME_MODE = 15;
    public static final int RESPAWN = 16;
    public static final int SELF_PASSENGERS = 17;
    /** Inventory slots, container content and self equipment: the client-visible items. */
    public static final int INVENTORY = 18;
    public static final int HELD_SLOT = 19;
    /** {@code SetHealth}: food level gates sprinting. */
    public static final int HEALTH = 20;
    public static final int BORDER = 21;
    /** {@code TickingState}/{@code TickingStep}: the client's own tick period. */
    public static final int TICKING = 22;
    /** {@code EntityEvent} for self (9 completes item use). */
    public static final int SELF_ENTITY_EVENT = 23;
    /** Packets that change another player as this client sees it: the viewer's lag-compensation mirrors. */
    public static final int REMOTE_PLAYER = 24;
    /** {@code AddTransientBlock}: render only, logged for traces. */
    public static final int TRANSIENT_BLOCK = 25;
    public static final int COOLDOWN = 26;
    /** {@code Login}: never bundled; a ping goes right after it. */
    public static final int LOGIN = 27;
    /** {@code PlayerInfoUpdate} with a game mode for self: the tab-list mode {@code isSpectator()} reads. Bundled, never trailed. */
    public static final int SELF_INFO = 28;
    /** {@code SetPlayerTeam}: the client's collision rule for itself (the authority precondition). */
    public static final int TEAM = 29;
    /** {@code BlockEvent} of a piston or a shulker box: their moving blocks and lids move the player on the client. */
    public static final int PISTON = 30;
    /** {@code SetCamera}: spectating another entity (exempt). */
    public static final int CAMERA = 31;
    /** {@code EntityEvent 31}: a fishing hook pulls its hooked entity on the client. */
    public static final int ROD_PULL = 32;
    /**
     * A firework rocket's {@code AddEntity}, its entity data (the attachment) or its {@code RemoveEntities}: a rocket
     * attached to the player boosts it on the client every tick it is held (auth-sim §3.2).
     */
    public static final int ROCKET = 33;

    public static final int KINDS = 34;

    // Families: the packet type alone decides, or it decides together with the entity id
    private static final int F_SELF_ID = -1;        // SetEntityMotion, effects, entity event: self or nothing
    private static final int F_SELF_OR_REMOTE = -2; // entity data, attributes, passengers: self, a tracked player, or nothing
    private static final int F_REMOTE = -3;         // moves, syncs, teleports of other entities
    private static final int F_ADD = -4;
    private static final int F_CONTAINER = -5;
    private static final int F_GAME_EVENT = -6;
    private static final int F_EXPLODE = -7;
    private static final int F_EQUIPMENT = -8;
    private static final int F_INFO = -9;
    private static final int F_BLOCK_EVENT = -10;

    private static final Reference2IntOpenHashMap<PacketType<?>> BY_TYPE = new Reference2IntOpenHashMap<>();

    static {
        BY_TYPE.defaultReturnValue(NONE);
        BY_TYPE.put(GamePacketTypes.CLIENTBOUND_SET_ENTITY_MOTION, F_SELF_ID);
        BY_TYPE.put(GamePacketTypes.CLIENTBOUND_PLAYER_POSITION, SELF_POSITION);
        BY_TYPE.put(GamePacketTypes.CLIENTBOUND_PLAYER_ROTATION, SELF_ROTATION);
        BY_TYPE.put(GamePacketTypes.CLIENTBOUND_EXPLODE, F_EXPLODE);
        BY_TYPE.put(GamePacketTypes.CLIENTBOUND_PLAYER_ABILITIES, ABILITIES);
        BY_TYPE.put(GamePacketTypes.CLIENTBOUND_UPDATE_ATTRIBUTES, F_SELF_OR_REMOTE);
        BY_TYPE.put(GamePacketTypes.CLIENTBOUND_UPDATE_MOB_EFFECT, F_SELF_ID);
        BY_TYPE.put(GamePacketTypes.CLIENTBOUND_REMOVE_MOB_EFFECT, F_SELF_ID);
        BY_TYPE.put(GamePacketTypes.CLIENTBOUND_SET_ENTITY_DATA, F_SELF_OR_REMOTE);
        BY_TYPE.put(GamePacketTypes.CLIENTBOUND_BLOCK_UPDATE, BLOCK_UPDATE);
        BY_TYPE.put(GamePacketTypes.CLIENTBOUND_SECTION_BLOCKS_UPDATE, SECTION_UPDATE);
        BY_TYPE.put(GamePacketTypes.CLIENTBOUND_BLOCK_CHANGED_ACK, BLOCK_ACK);
        BY_TYPE.put(GamePacketTypes.CLIENTBOUND_LEVEL_CHUNK_WITH_LIGHT, CHUNK);
        BY_TYPE.put(GamePacketTypes.CLIENTBOUND_FORGET_LEVEL_CHUNK, FORGET_CHUNK);
        BY_TYPE.put(GamePacketTypes.CLIENTBOUND_ADD_TRANSIENT_BLOCK, TRANSIENT_BLOCK);
        BY_TYPE.put(GamePacketTypes.CLIENTBOUND_GAME_EVENT, F_GAME_EVENT);
        BY_TYPE.put(GamePacketTypes.CLIENTBOUND_RESPAWN, RESPAWN);
        BY_TYPE.put(GamePacketTypes.CLIENTBOUND_LOGIN, LOGIN);
        BY_TYPE.put(GamePacketTypes.CLIENTBOUND_SET_PASSENGERS, F_SELF_OR_REMOTE);
        BY_TYPE.put(GamePacketTypes.CLIENTBOUND_CONTAINER_SET_SLOT, F_CONTAINER);
        BY_TYPE.put(GamePacketTypes.CLIENTBOUND_CONTAINER_SET_CONTENT, F_CONTAINER);
        BY_TYPE.put(GamePacketTypes.CLIENTBOUND_SET_PLAYER_INVENTORY, INVENTORY);
        BY_TYPE.put(GamePacketTypes.CLIENTBOUND_SET_EQUIPMENT, F_EQUIPMENT);
        BY_TYPE.put(GamePacketTypes.CLIENTBOUND_SET_HELD_SLOT, HELD_SLOT);
        BY_TYPE.put(GamePacketTypes.CLIENTBOUND_SET_HEALTH, HEALTH);
        BY_TYPE.put(GamePacketTypes.CLIENTBOUND_INITIALIZE_BORDER, BORDER);
        BY_TYPE.put(GamePacketTypes.CLIENTBOUND_SET_BORDER_CENTER, BORDER);
        BY_TYPE.put(GamePacketTypes.CLIENTBOUND_SET_BORDER_LERP_SIZE, BORDER);
        BY_TYPE.put(GamePacketTypes.CLIENTBOUND_SET_BORDER_SIZE, BORDER);
        BY_TYPE.put(GamePacketTypes.CLIENTBOUND_TICKING_STATE, TICKING);
        BY_TYPE.put(GamePacketTypes.CLIENTBOUND_TICKING_STEP, TICKING);
        BY_TYPE.put(GamePacketTypes.CLIENTBOUND_ENTITY_EVENT, F_SELF_ID);
        BY_TYPE.put(GamePacketTypes.CLIENTBOUND_COOLDOWN, COOLDOWN);
        BY_TYPE.put(GamePacketTypes.CLIENTBOUND_ADD_ENTITY, F_ADD);
        BY_TYPE.put(GamePacketTypes.CLIENTBOUND_REMOVE_ENTITIES, F_REMOTE);
        BY_TYPE.put(GamePacketTypes.CLIENTBOUND_MOVE_ENTITY_POS, F_REMOTE);
        BY_TYPE.put(GamePacketTypes.CLIENTBOUND_MOVE_ENTITY_POS_ROT, F_REMOTE);
        BY_TYPE.put(GamePacketTypes.CLIENTBOUND_MOVE_ENTITY_ROT, F_REMOTE);
        BY_TYPE.put(GamePacketTypes.CLIENTBOUND_ENTITY_POSITION_SYNC, F_REMOTE);
        BY_TYPE.put(GamePacketTypes.CLIENTBOUND_TELEPORT_ENTITY, F_REMOTE);
        BY_TYPE.put(GamePacketTypes.CLIENTBOUND_PLAYER_INFO_UPDATE, F_INFO);
        BY_TYPE.put(GamePacketTypes.CLIENTBOUND_SET_PLAYER_TEAM, TEAM);
        BY_TYPE.put(GamePacketTypes.CLIENTBOUND_BLOCK_EVENT, F_BLOCK_EVENT);
        BY_TYPE.put(GamePacketTypes.CLIENTBOUND_SET_CAMERA, CAMERA);
    }

    private OutboundClassifier() {
    }

    /**
     * What the classifier and {@link Transactions} need from a packet's class, worked out once per class (auth-load §9:
     * every packet to every player passes here). Every packet's {@code type()} returns its class's constant, so the class
     * decides the family.
     */
    static final class ClassInfo {
        /** {@code BY_TYPE} of the class's type: a kind, or a negative family that the entity id decides. */
        final int family;
        /** Never terminal, never held (Paper's {@code isReady}/{@code getExtraPackets} not overridden), not the login: always bundleable. */
        final boolean plain;
        /** {@code AddEntity}, {@code RemoveEntities}, {@code Respawn} or {@code Login}: the tracked-entity sets look at it. */
        final boolean tracked;

        private ClassInfo(final Packet<?> packet) {
            final Class<?> type = packet.getClass();
            this.family = BY_TYPE.getInt(packet.type());
            this.plain = !(packet instanceof net.minecraft.network.protocol.game.ClientboundLoginPacket)
                && inherited(type, "isTerminal") && inherited(type, "isReady") && inherited(type, "getExtraPackets");
            this.tracked = packet instanceof ClientboundAddEntityPacket || packet instanceof net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
                || packet instanceof net.minecraft.network.protocol.game.ClientboundRespawnPacket || packet instanceof net.minecraft.network.protocol.game.ClientboundLoginPacket;
        }

        // Whether the class keeps Packet's default for this no-argument method
        private static boolean inherited(final Class<?> type, final String method) {
            try {
                return type.getMethod(method).getDeclaringClass() == Packet.class;
            } catch (final NoSuchMethodException e) {
                return false;
            }
        }
    }

    // One slot per packet class, filled from the first instance seen (benign race: the info is immutable and the same
    // whichever thread computes it)
    private static final ClassValue<ClassInfo[]> INFO = new ClassValue<>() {
        @Override
        protected ClassInfo[] computeValue(final Class<?> type) {
            return new ClassInfo[1];
        }
    };

    static ClassInfo info(final Packet<?> packet) {
        final ClassInfo[] slot = INFO.get(packet.getClass());
        ClassInfo info = slot[0];
        if (info == null) {
            info = new ClassInfo(packet);
            slot[0] = info;
        }
        return info;
    }

    /**
     * The kind of {@code packet} for the client of entity {@code self}, or {@link #NONE}.
     *
     * @param trackedPlayers entity ids of the other players this client has been sent (kept by the caller from
     *                       {@code AddEntity}/{@code RemoveEntities}); moves of anything else are not logged
     */
    public static int classify(final Packet<?> packet, final int self, final java.util.UUID selfUuid, final IntSet trackedPlayers) {
        return classify(packet, self, selfUuid, trackedPlayers, it.unimi.dsi.fastutil.ints.IntSets.EMPTY_SET);
    }

    /**
     * As above, with {@code trackedRockets}: entity ids of the firework rockets this client has been sent (their entity
     * data and removal matter too).
     */
    public static int classify(final Packet<?> packet, final int self, final java.util.UUID selfUuid, final IntSet trackedPlayers, final IntSet trackedRockets) {
        return classify(info(packet).family, packet, self, selfUuid, trackedPlayers, trackedRockets);
    }

    static int classify(final int family, final Packet<?> packet, final int self, final java.util.UUID selfUuid, final IntSet trackedPlayers, final IntSet trackedRockets) {
        if (family >= 0) {
            return family;
        }
        return switch (family) {
            case F_SELF_ID -> selfKind(packet, self);
            case F_SELF_OR_REMOTE -> {
                final int id = entityId(packet);
                if (id == self || packet instanceof final ClientboundSetPassengersPacket passengers && carries(passengers, self)) {
                    yield packet instanceof ClientboundSetEntityDataPacket ? SELF_ENTITY_DATA
                        : packet instanceof ClientboundUpdateAttributesPacket ? SELF_ATTRIBUTES : SELF_PASSENGERS;
                }
                yield trackedPlayers.contains(id) ? REMOTE_PLAYER
                    : packet instanceof ClientboundSetEntityDataPacket && trackedRockets.contains(id) ? ROCKET : NONE;
            }
            case F_REMOTE -> remote(packet, trackedPlayers, trackedRockets);
            case F_ADD -> {
                final net.minecraft.world.entity.EntityType<?> type = ((ClientboundAddEntityPacket) packet).getType();
                yield type == net.minecraft.world.entity.EntityTypes.PLAYER ? REMOTE_PLAYER : type == net.minecraft.world.entity.EntityTypes.FIREWORK_ROCKET ? ROCKET : NONE;
            }
            case F_CONTAINER -> {
                final int container = packet instanceof final ClientboundContainerSetSlotPacket slot ? slot.getContainerId()
                    : ((ClientboundContainerSetContentPacket) packet).containerId();
                // Container 0 is the player's own inventory menu; others still hold the hotbar while open
                yield container >= 0 ? INVENTORY : NONE;
            }
            case F_GAME_EVENT -> ((ClientboundGameEventPacket) packet).getEvent() == ClientboundGameEventPacket.CHANGE_GAME_MODE ? GAME_MODE : NONE;
            case F_EXPLODE -> ((ClientboundExplodePacket) packet).playerKnockback().isPresent() ? EXPLODE : NONE;
            case F_EQUIPMENT -> ((ClientboundSetEquipmentPacket) packet).getEntity() == self ? INVENTORY : NONE;
            case F_INFO -> selfGameMode((net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket) packet, selfUuid) ? SELF_INFO : NONE;
            case F_BLOCK_EVENT -> {
                final net.minecraft.world.level.block.Block block = ((net.minecraft.network.protocol.game.ClientboundBlockEventPacket) packet).getBlock();
                yield block instanceof net.minecraft.world.level.block.piston.PistonBaseBlock || block instanceof net.minecraft.world.level.block.ShulkerBoxBlock ? PISTON : NONE;
            }
            default -> NONE;
        };
    }

    /** Whether the classifier logs this packet type at all (a quick test for bundles). */
    public static boolean mayMatter(final Packet<?> packet) {
        return info(packet).family != NONE;
    }

    /** Packets that must not be bundled: the ping goes right after them (rule A, report 02 §6). */
    public static boolean trailingPing(final int kind) {
        return kind == CHUNK || kind == LOGIN;
    }

    private static int selfKind(final Packet<?> packet, final int self) {
        return switch (packet) {
            case final ClientboundSetEntityMotionPacket motion -> motion.id() == self ? SELF_MOTION : NONE;
            case final ClientboundUpdateMobEffectPacket effect -> effect.getEntityId() == self ? SELF_EFFECT_ADD : NONE;
            case final ClientboundRemoveMobEffectPacket effect -> effect.entityId() == self ? SELF_EFFECT_REMOVE : NONE;
            case final ClientboundEntityEventPacket event -> event.revivalEntityId() == self ? SELF_ENTITY_EVENT
                : event.getEventId() == net.minecraft.world.entity.EntityEvent.FISHING_ROD_REEL_IN ? ROD_PULL : NONE;
            default -> NONE;
        };
    }

    private static int remote(final Packet<?> packet, final IntSet trackedPlayers, final IntSet trackedRockets) {
        if (packet instanceof final net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket remove && !trackedRockets.isEmpty()) {
            boolean rocket = false;
            for (int i = 0, n = remove.entityIds().size(); i < n; i++) {
                final int id = remove.entityIds().getInt(i);
                if (trackedPlayers.contains(id)) {
                    return REMOTE_PLAYER; // the apply drops any rockets in it too
                }
                rocket |= trackedRockets.contains(id);
            }
            return rocket ? ROCKET : NONE;
        }
        if (trackedPlayers.isEmpty()) {
            return NONE;
        }
        return switch (packet) {
            case final ClientboundMoveEntityPacket move -> trackedPlayers.contains(move.revivalEntityId()) ? REMOTE_PLAYER : NONE;
            case final ClientboundEntityPositionSyncPacket sync -> trackedPlayers.contains(sync.id()) ? REMOTE_PLAYER : NONE;
            case final ClientboundTeleportEntityPacket teleport -> trackedPlayers.contains(teleport.id()) ? REMOTE_PLAYER : NONE;
            case final net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket remove -> {
                for (int i = 0, n = remove.entityIds().size(); i < n; i++) {
                    if (trackedPlayers.contains(remove.entityIds().getInt(i))) {
                        yield REMOTE_PLAYER;
                    }
                }
                yield NONE;
            }
            default -> NONE;
        };
    }

    /** The entity a self-or-remote packet is about. */
    public static int entityId(final Packet<?> packet) {
        return switch (packet) {
            case final ClientboundSetEntityDataPacket data -> data.id();
            case final ClientboundUpdateAttributesPacket attributes -> attributes.getEntityId();
            case final ClientboundSetPassengersPacket passengers -> passengers.getVehicle();
            default -> Integer.MIN_VALUE;
        };
    }

    private static boolean selfGameMode(final net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket packet, final java.util.UUID self) {
        if (!packet.actions().contains(net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE)) {
            return false;
        }
        for (final net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Entry entry : packet.entries()) {
            if (self.equals(entry.profileId())) {
                return true;
            }
        }
        return false;
    }

    private static boolean carries(final ClientboundSetPassengersPacket packet, final int self) {
        for (final int passenger : packet.getPassengers()) {
            if (passenger == self) {
                return true;
            }
        }
        return false;
    }
}
