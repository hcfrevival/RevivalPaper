package gg.hcfrevival.revival.auth.combat;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.protocol.game.VecDeltaCodec;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.PositionPath;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * One viewer's client copies of the entities its local player collides with (auth-sim-requests §2): boats (every
 * {@code AbstractBoat}: boats, chest boats, rafts), shulkers and happy ghasts, rebuilt from the packets that viewer
 * applied, at the boxes its client holds while its player ticks.
 * <ul>
 *   <li><b>Boats</b>: {@code LinearInterpolationHandler} with 3 steps. A port of it and of
 *       {@code AbstractInterpolationHandler} (interpolateTo, interpolate, the target shift after a snap) with
 *       {@code Entity.snapTo/setPos/setRot}; box {@code EntityDimensions.makeBoundingBox} of the type.</li>
 *   <li><b>Shulkers</b>: {@code InterpolationHandler.NO_OP}: a move sets the position at once, {@code Shulker.setPos}
 *       snapping it to its block's centre; box {@code Shulker.getProgressAabb} from the live scale ({@code SCALE}, at
 *       most 3), the attach face and the client's own peek, which each shulker tick moves 0.05 towards the raw peek
 *       ({@code updatePeekAmount}).</li>
 *   <li><b>Happy ghasts</b>: a {@code LivingEntity}, so {@code SteppedInterpolationHandler}: {@link RemoteMirror} with
 *       the type's update interval; box 4 x 4 times the scale ({@code SCALE}, at most 1) as of its last
 *       {@code refreshDimensions}.</li>
 * </ul>
 * Packets are applied when the client applies them (its drain, before its tick). {@link #tick} is the client's tick of
 * these entities: every entity that joined the client's level after its local player ticks after that player in the
 * same client tick ({@code ClientLevel.tickingEntities} keeps join order), so the player of client tick N collides
 * with each one as its tick N-1 left it plus that drain's packets. After a respawn in the same dimension the new player
 * joins last: the entities already there tick before it from then on ({@link #playerRejoined}).
 * <p>
 * A copy is <i>exact</i> while its box is the client's bit for bit. It stops being exact for what the client does with
 * its own world (a boat's target shifted only where the client has room for it, a shulker lid shoving a boat), a
 * vehicle this client drives (its own physics), or a passenger (its vehicle places it); {@link #takeRepairs} hands
 * those to the netcode, which re-pairs the entity so a logged spawn makes it exact again.
 * <p>
 * Shared by the server's model of each client and by the live tests' model clients. Owned by one thread.
 */
public final class SolidMirrors {

    public static final int BOAT = 1;
    public static final int SHULKER = 2;
    public static final int GHAST = 3;

    /** {@code SimWorld.ENTITY_SOLID} / {@code ENTITY_SOLID_FROM_ABOVE} (repeated so the rig compiles without auth.sim). */
    public static final int RULE_SOLID = 0;
    public static final int RULE_SOLID_FROM_ABOVE = 1;

    /** {@code distanceToSqr > 4096.0}: the client snaps instead of interpolating. */
    static final double SNAP_DISTANCE_SQR = 4096.0;
    /** {@code AbstractInterpolationHandler.adjustInterpolationTargetFromDeltas}: smaller jumps are ignored. */
    static final double ADJUST_DISTANCE_SQR = 9.999999747378752E-6;
    /** {@code AbstractBoat.createInterpolationHandler}: {@code LinearInterpolationHandler.create(this, 3)}. */
    static final int BOAT_STEPS = 3;

    private static final Set<EntityType<?>> BOATS = typesOf(AbstractBoat.class);
    static final int ATTACH_FACE_ID = dataId(Shulker.class, "DATA_ATTACH_FACE_ID");
    static final int PEEK_ID = dataId(Shulker.class, "DATA_PEEK_ID");
    static final int STAYS_STILL_ID = dataId(HappyGhast.class, "STAYS_STILL");
    static final int BABY_ID = dataId(net.minecraft.world.entity.AgeableMob.class, "DATA_BABY_ID");
    static final int HEALTH_ID = dataId(net.minecraft.world.entity.LivingEntity.class, "DATA_HEALTH_ID");
    static final int POSE_ID = dataId(net.minecraft.world.entity.Entity.class, "DATA_POSE");
    /** HappyGhast.BABY_DIMENSIONS and LivingEntity.SLEEPING_DIMENSIONS (their sizes; eye heights don't matter here). */
    private static final EntityDimensions GHAST_BABY_DIMENSIONS = EntityTypes.HAPPY_GHAST.getDimensions().scale(0.2375F);
    private static final EntityDimensions SLEEPING_DIMENSIONS = EntityDimensions.fixed(0.2F, 0.2F);

    /** One entity on the viewer's client. */
    static final class Solid {
        int id;
        // Its slot in `all` (review PT2-07, X2-06: removal swaps the last copy in, O(1) instead of a list scan)
        int index;
        int kind;
        /** A placeholder: the client holds this entity in a state the log never saw (before activation, a lost log). */
        boolean unknown;
        // Entity.position, rotation, the packet position codec
        double x;
        double y;
        double z;
        float yRot;
        float xRot;
        final VecDeltaCodec codec = new VecDeltaCodec();
        // EntityDimensions: width / 2.0F and height (a boat's type; a ghast's as of its last refreshDimensions)
        float halfWidth;
        float height;
        // the box the client holds
        double minX;
        double minY;
        double minZ;
        double maxX;
        double maxY;
        double maxZ;
        boolean exact = true;
        /** Queued for {@link #takeRepairs} already (once per spawn). */
        boolean repairQueued;
        // the client's entity section and when the entity entered it (EntitySectionStorage keeps entry order)
        long section;
        long entered;
        /** Ticks before the local player in a client tick (it was there when the player rejoined the level). */
        boolean beforePlayer;
        int tickCount;
        // passengers: the vehicle it rides (-1 none); whether this client drives it (its first passenger is the player)
        int vehicle = -1;
        boolean driven;
        // boats: LinearInterpolationHandler.InterpolationData and lastPositionAndRotation
        double targetX;
        double targetY;
        double targetZ;
        float targetYRot;
        float targetXRot;
        int remaining;
        double lastX;
        double lastY;
        double lastZ;
        float lastYRot;
        float lastXRot;
        // shulkers
        Direction attachFace = Direction.DOWN;
        int rawPeek;
        float peek;
        float peekO;
        // living (shulkers, ghasts): health as last sent, the live scale attribute
        float health = 1.0F;
        float scale = 1.0F;
        boolean scaleDirty;
        // ghasts
        @Nullable RemoteMirror stepped;
        boolean staysStill;
        boolean baby;
        boolean sleeping;
    }

    private final Int2ObjectOpenHashMap<Solid> byId = new Int2ObjectOpenHashMap<>();
    private final List<Solid> all = new ArrayList<>();
    private final IntArrayList repairs = new IntArrayList();
    private int self = Integer.MIN_VALUE;
    private long enterCounter;
    private int[] order = new int[16];
    private int orderSize;

    // ------------------------------------------------------------------------------------------------------------------
    // Packets
    // ------------------------------------------------------------------------------------------------------------------

    /** The kind of entity an {@code AddEntity} of {@code type} makes here, or 0 for one the player doesn't collide with. */
    public static int kindOf(final EntityType<?> type) {
        if (BOATS.contains(type)) {
            return BOAT;
        }
        if (type == EntityTypes.SHULKER) {
            return SHULKER;
        }
        return type == EntityTypes.HAPPY_GHAST ? GHAST : 0;
    }

    /** The local player's entity id (passengers of it never collide; a vehicle it drives moves by its own physics). */
    public void setSelf(final int id) {
        this.self = id;
    }

    /** Whether this client holds entity {@code id} (or may: a placeholder). */
    public boolean has(final int id) {
        return this.byId.containsKey(id);
    }

    /**
     * Whether the client's copy of entity {@code id} is known exactly, or it holds no such entity (nothing to collide
     * with that the port doesn't have).
     */
    public boolean trusted(final int id) {
        final Solid s = this.byId.get(id);
        return s == null || s.exact && !s.unknown && s.vehicle == -1 && !s.driven;
    }

    /** Applies one packet about these entities, as the client's handler does (ClientPacketListener). */
    /**
     * As {@link #apply(Packet)}; {@code exact} false for an entry forced at its deadline (review R2C-06): the copy
     * changes before the client's does, so it is untrusted and re-paired once acks catch up, like a remote player's
     * mirror, instead of colliding as exact ticks early.
     */
    public void apply(final Packet<?> packet, final boolean exact) {
        this.apply(packet);
        if (exact) {
            return;
        }
        final int id = switch (packet) {
            case final ClientboundAddEntityPacket add -> add.getId();
            case final ClientboundMoveEntityPacket move -> MirrorEvent.entityId(move);
            case final ClientboundEntityPositionSyncPacket sync -> sync.id();
            case final ClientboundTeleportEntityPacket teleport -> teleport.id();
            case final ClientboundSetEntityDataPacket data -> data.id();
            case final ClientboundUpdateAttributesPacket attributes -> attributes.getEntityId();
            case final ClientboundSetPassengersPacket passengers -> passengers.getVehicle();
            default -> -1;
        };
        final Solid s = id == -1 ? null : this.byId.get(id);
        if (s != null) {
            this.broke(s);
        }
    }

    public void apply(final Packet<?> packet) {
        switch (packet) {
            case final ClientboundAddEntityPacket add -> this.add(add);
            case final ClientboundRemoveEntitiesPacket remove -> {
                for (int i = 0, n = remove.entityIds().size(); i < n; i++) {
                    this.remove(remove.entityIds().getInt(i));
                }
            }
            case final ClientboundMoveEntityPacket move -> this.move(move);
            case final ClientboundEntityPositionSyncPacket sync -> this.sync(sync);
            case final ClientboundTeleportEntityPacket teleport -> this.teleport(teleport);
            case final ClientboundSetEntityDataPacket data -> this.data(data);
            case final ClientboundUpdateAttributesPacket attributes -> this.attributes(attributes);
            case final ClientboundSetPassengersPacket passengers -> this.passengers(passengers.getVehicle(), passengers.getPassengers());
            default -> {
            }
        }
    }

    /** The client drops every entity with its level (login, a respawn into another dimension). */
    public void clear() {
        this.byId.clear();
        this.all.clear();
        this.repairs.clear();
        this.movable = 0;
    }

    /**
     * A respawn in the same dimension: the new local player joins the level last, after every entity still there, which
     * then ticks before it. The log dropped those entities with the respawn ({@code Transactions} forgets every tracked
     * id), so they are re-paired too (Paper's respawn has already unpaired everything, so there are rarely any).
     */
    public void playerRejoined() {
        for (int i = 0, n = this.all.size(); i < n; i++) {
            final Solid s = this.all.get(i);
            s.beforePlayer = true;
            this.broke(s);
        }
    }

    /** A lost log: no copy held is known any more (each stays untrusted until a logged spawn replaces it). */
    public void distrustAll() {
        for (int i = 0, n = this.all.size(); i < n; i++) {
            final Solid s = this.all.get(i);
            s.exact = false;
            s.repairQueued = true;
        }
    }

    /**
     * The client holds entity {@code id} in a state this log never saw (authority starting, a lost log): untrusted until
     * a logged spawn replaces it. The netcode re-pairs it.
     */
    public void markUnknown(final int id) {
        final Solid held = this.byId.get(id);
        if (held != null) {
            held.exact = false;
            held.repairQueued = true;
            return;
        }
        final Solid s = new Solid();
        s.id = id;
        s.unknown = true;
        s.exact = false;
        s.repairQueued = true;
        this.byId.put(id, s);
        s.index = this.all.size();
        this.all.add(s);
        if (s.kind != SHULKER && s.kind != 0) {
            this.movable++;
        }
    }

    public int size() {
        return this.all.size();
    }

    // ClientPacketListener.handleAddEntity: a fresh entity (ClientLevel.addEntity replaces one with the same id), then
    // recreateFromPacket
    private void add(final ClientboundAddEntityPacket packet) {
        final int kind = kindOf(packet.getType());
        if (kind == 0) {
            return;
        }
        this.remove(packet.getId());
        final Solid s = new Solid();
        s.id = packet.getId();
        s.kind = kind;
        final EntityDimensions dimensions = packet.getType().getDimensions();
        s.halfWidth = dimensions.width() / 2.0F;
        s.height = dimensions.height();
        s.codec.setBase(new Vec3(packet.getX(), packet.getY(), packet.getZ())); // syncPacketPositionCodec
        if (kind == GHAST) {
            // LivingEntity.recreateFromPacket: absSnapTo (x and z clamped) on a fresh stepped handler
            s.stepped = new RemoteMirror(EntityTypes.HAPPY_GHAST.updateInterval());
            s.stepped.spawn(packet.getX(), packet.getY(), packet.getZ(), packet.getYRot(), packet.getXRot(), 0L);
            this.followStepped(s);
        } else if (kind == SHULKER) {
            // LivingEntity.recreateFromPacket → absSnapTo: x and z clamped, then Shulker.setPos snaps to the block centre
            this.setPos(s, Mth.clamp(packet.getX(), -3.0E7, 3.0E7), packet.getY(), Mth.clamp(packet.getZ(), -3.0E7, 3.0E7));
        } else {
            // Entity.recreateFromPacket → snapTo(x, y, z, yRot, xRot): setPosRaw, rotation, reapplyPosition
            setYRot(s, packet.getYRot());
            setXRot(s, packet.getXRot());
            this.setPos(s, packet.getX(), packet.getY(), packet.getZ());
        }
        this.byId.put(s.id, s);
        s.index = this.all.size();
        this.all.add(s);
        if (s.kind != SHULKER && s.kind != 0) {
            this.movable++;
        }
    }

    private void remove(final int id) {
        final Solid s = this.byId.remove(id);
        if (s == null) {
            return;
        }
        final Solid last = this.all.remove(this.all.size() - 1);
        if (last != s) {
            this.all.set(s.index, last); // order in `all` means nothing: collect sorts into the client's order
            last.index = s.index;
        }
        if (s.kind != SHULKER && s.kind != 0) {
            this.movable--;
        }
    }

    // Boats and happy ghasts held: what an opening shulker lid can push (no scan of every copy for a lid with none)
    private int movable;

    // ClientPacketListener.handleMoveEntity
    private void move(final ClientboundMoveEntityPacket packet) {
        final Solid s = this.held(MirrorEvent.entityId(packet));
        if (s == null) {
            return;
        }
        if (s.kind == GHAST) {
            if (!s.driven) {
                s.stepped.move(packet.hasPosition(), packet.hasRotation(), packet.getPositionDelta(), packet.getYRot(), packet.getXRot(), 0L);
                this.followStepped(s);
            }
            return;
        }
        if (packet.hasPosition()) {
            final PositionPath path = packet.getPositionDelta().decode(s.codec);
            final Vec3 end = path.endPosition();
            s.codec.setBase(end);
            if (!s.driven) { // a vehicle this client drives only takes the codec base
                this.moveOrInterpolateTo(s, end, packet.getYRot(), packet.getXRot(), packet.hasRotation());
            }
        } else if (packet.hasRotation() && !s.driven) {
            this.moveOrInterpolateTo(s, null, packet.getYRot(), packet.getXRot(), true);
        }
    }

    // ClientPacketListener.handleEntityPositionSync, for an entity the client ticks
    private void sync(final ClientboundEntityPositionSyncPacket packet) {
        final Solid s = this.held(packet.id());
        if (s == null) {
            return;
        }
        final Vec3 end = packet.position().endPosition();
        if (s.kind == GHAST) {
            if (!s.driven) {
                s.stepped.sync(packet.position(), packet.yRot(), packet.xRot(), 0L);
                this.followStepped(s);
            }
            return;
        }
        s.codec.setBase(end);
        if (s.driven) {
            return;
        }
        if (distanceToSqr(s.x, s.y, s.z, end.x, end.y, end.z) > SNAP_DISTANCE_SQR) {
            this.snapTo(s, end.x, end.y, end.z, packet.yRot(), packet.xRot());
        } else {
            this.moveOrInterpolateTo(s, end, packet.yRot(), packet.xRot(), true);
        }
    }

    // ClientPacketListener.handleTeleportEntity → setValuesFromPositionPacket(..., interpolate: the entity ticks)
    private void teleport(final ClientboundTeleportEntityPacket packet) {
        final Solid s = this.held(packet.id());
        if (s == null) {
            return;
        }
        if (s.kind == GHAST) {
            s.stepped.teleport(packet.change(), packet.relatives(), 0L);
            this.followStepped(s);
            return;
        }
        // PositionMoveRotation.of(entity): getClientPositionAndRotation, the target while one is active
        final boolean active = s.kind == BOAT && s.remaining > 0;
        final PositionMoveRotation current = new PositionMoveRotation(active ? new Vec3(s.targetX, s.targetY, s.targetZ) : new Vec3(s.x, s.y, s.z),
            Vec3.ZERO, active ? s.targetYRot : s.yRot, active ? s.targetXRot : s.xRot);
        final PositionMoveRotation absolute = PositionMoveRotation.calculateAbsolute(current, packet.change(), packet.relatives());
        final Vec3 position = absolute.position();
        if (current.position().distanceToSqr(position) > SNAP_DISTANCE_SQR) {
            // entity.setPos, setYRot, setXRot (setOldPosAndRot: no box)
            this.setPos(s, position.x, position.y, position.z);
            setYRot(s, absolute.yRot());
            setXRot(s, absolute.xRot());
        } else {
            this.moveOrInterpolateTo(s, position, absolute.yRot(), absolute.xRot(), true);
        }
    }

    // ClientPacketListener.handleSetEntityData: assignValues, with each type's onSyncedDataUpdated
    private void data(final ClientboundSetEntityDataPacket packet) {
        final Solid s = this.held(packet.id());
        if (s == null || s.kind == BOAT) {
            return;
        }
        for (final SynchedEntityData.DataValue<?> value : packet.packedItems()) {
            final int id = value.id();
            if (id == HEALTH_ID && value.value() instanceof final Float health) {
                s.health = health;
            } else if (s.kind == SHULKER && id == ATTACH_FACE_ID && value.value() instanceof final Direction face) {
                s.attachFace = face;
                this.shulkerBox(s); // Shulker.onSyncedDataUpdated: setBoundingBox(makeBoundingBox())
            } else if (s.kind == SHULKER && id == PEEK_ID && value.value() instanceof final Byte peek) {
                s.rawPeek = peek;
            } else if (s.kind == SHULKER && id == POSE_ID) {
                this.shulkerBox(s); // Entity.onSyncedDataUpdated → refreshDimensions → reapplyPosition
            } else if (s.kind == GHAST && id == STAYS_STILL_ID && value.value() instanceof final Boolean still) {
                s.staysStill = still;
            } else if (s.kind == GHAST && (id == BABY_ID || id == POSE_ID)) {
                if (value.value() instanceof final Boolean baby) {
                    s.baby = baby;
                } else if (value.value() instanceof final net.minecraft.world.entity.Pose pose) {
                    s.sleeping = pose == net.minecraft.world.entity.Pose.SLEEPING;
                }
                // AgeableMob / Entity.onSyncedDataUpdated → refreshDimensions with the live scale
                ghastDimensions(s);
                this.ghastBox(s);
            }
        }
    }

    // ClientPacketListener.handleUpdateAttributes: the scale attribute changes now; the dimensions follow in the tick
    private void attributes(final ClientboundUpdateAttributesPacket packet) {
        final Solid s = this.held(packet.getEntityId());
        if (s == null || s.kind == BOAT) {
            return;
        }
        final MirrorEvent.SetScale scale = MirrorEvent.scale(packet);
        if (scale != null) {
            s.scale = scale.scale();
            s.scaleDirty = true;
        }
    }

    // ClientPacketListener.handleSetEntityPassengersPacket: the vehicle ejects every passenger, then the listed ones ride
    private void passengers(final int vehicle, final int[] passengers) {
        for (int i = 0, n = this.all.size(); i < n; i++) {
            final Solid s = this.all.get(i);
            if (s.vehicle == vehicle) {
                s.vehicle = -1; // dismounted where its vehicle left it: still inexact (repaired once known again)
            }
        }
        final Solid carrier = this.byId.get(vehicle);
        if (carrier != null) {
            final boolean driven = passengers.length > 0 && passengers[0] == this.self;
            final boolean wasDriven = carrier.driven;
            carrier.driven = driven;
            if (driven) {
                carrier.exact = false;
            } else if (wasDriven) {
                this.broke(carrier); // the client left it where its own physics put it
            }
        }
        for (final int passenger : passengers) {
            final Solid s = this.byId.get(passenger);
            if (s != null) {
                s.vehicle = vehicle;
                s.exact = false; // its vehicle places it every tick (ClientLevel.tickPassenger)
            }
        }
    }

    private @Nullable Solid held(final int id) {
        final Solid s = this.byId.get(id);
        return s == null || s.unknown ? null : s;
    }

    // ------------------------------------------------------------------------------------------------------------------
    // The client tick of these entities
    // ------------------------------------------------------------------------------------------------------------------

    /**
     * Ticks, as ClientLevel.tickEntities does, every entity on one side of the local player: {@code beforePlayer} the
     * ones that tick before it (there when it rejoined the level), else the ones after it (every other). Passengers are
     * ticked by their vehicles. Returns whether a shulker's lid opened into the player's box {@code pMin..pMax}: that
     * tick moves the player ({@code Shulker.onPeekAmountChange}), an external mover.
     *
     * @param tickSpeed {@code ClientLevel.getRelativeTickSpeed()} for the ghasts' stepped interpolation
     */
    public boolean tick(final boolean beforePlayer, final float tickSpeed, final double pMinX, final double pMinY, final double pMinZ,
                        final double pMaxX, final double pMaxY, final double pMaxZ) {
        boolean pushed = false;
        for (int i = 0, n = this.all.size(); i < n; i++) {
            final Solid s = this.all.get(i);
            if (s.beforePlayer != beforePlayer || s.unknown || s.vehicle != -1) {
                continue;
            }
            s.tickCount++;
            switch (s.kind) {
                case BOAT -> {
                    if (!s.driven) {
                        this.interpolate(s);
                    }
                }
                case SHULKER -> pushed |= this.shulkerTick(s, pMinX, pMinY, pMinZ, pMaxX, pMaxY, pMaxZ);
                case GHAST -> {
                    if (!s.driven) {
                        s.stepped.tick(tickSpeed);
                    }
                    if (s.scaleDirty) {
                        s.scaleDirty = false; // refreshDirtyAttributes → refreshDimensions with the live scale
                        ghastDimensions(s);
                    }
                    this.followStepped(s);
                }
                default -> {
                }
            }
        }
        return pushed;
    }

    // AbstractInterpolationHandler.interpolate with LinearInterpolationHandler.doInterpolate
    private void interpolate(final Solid s) {
        if (s.remaining <= 0) {
            s.remaining = 0; // cancel()
            return;
        }
        // adjustInterpolationTargetFromDeltas: the entity moved since the last step (a snap while interpolating)
        final double dx = s.x - s.lastX;
        final double dy = s.y - s.lastY;
        final double dz = s.z - s.lastZ;
        if (dx * dx + dy * dy + dz * dz > ADJUST_DISTANCE_SQR) {
            // The client shifts the target only if its world has room for the box there. shortcut: assume it has
            this.broke(s);
            s.targetX += dx;
            s.targetY += dy;
            s.targetZ += dz;
        }
        s.targetYRot += s.yRot - s.lastYRot; // PositionAndRotation.Mutable.addRotation
        s.targetXRot += s.xRot - s.lastXRot;
        final double alpha = 1.0 / s.remaining;
        // Vec3.lerp → Mth.lerp(a, this, target) = this + a * (target - this)
        final double newX = s.x + alpha * (s.targetX - s.x);
        final double newY = s.y + alpha * (s.targetY - s.y);
        final double newZ = s.z + alpha * (s.targetZ - s.z);
        final float newYRot = (float) Mth.rotLerp(alpha, (double) s.yRot, (double) s.targetYRot);
        final float newXRot = (float) Mth.lerp(alpha, (double) s.xRot, (double) s.targetXRot);
        this.setPos(s, newX, newY, newZ);
        setRot(s, newYRot, newXRot);
        s.remaining--;
        s.lastX = s.x; // setLastPositionAndRotation
        s.lastY = s.y;
        s.lastZ = s.z;
        s.lastYRot = s.yRot;
        s.lastXRot = s.xRot;
    }

    // Shulker.tick on the client: LivingEntity's (refreshDirtyAttributes at its end), then updatePeekAmount and
    // onPeekAmountChange
    private boolean shulkerTick(final Solid s, final double pMinX, final double pMinY, final double pMinZ, final double pMaxX, final double pMaxY,
                                final double pMaxZ) {
        if (s.scaleDirty) {
            s.scaleDirty = false;
            this.shulkerBox(s); // refreshDimensions → reapplyPosition: the box with the live scale
        }
        s.peekO = s.peek;
        final float target = s.rawPeek * 0.01F;
        if (s.peek == target) {
            return false;
        }
        if (s.peek > target) {
            s.peek = Mth.clamp(s.peek - 0.05F, target, 1.0F);
        } else {
            s.peek = Mth.clamp(s.peek + 0.05F, 0.0F, target);
        }
        this.shulkerBox(s); // reapplyPosition, refreshDimensions
        final float physical = physicalPeek(s.peek);
        final float physicalOld = physicalPeek(s.peekO);
        final float scale = shulkerScale(s);
        if ((physical - physicalOld) * scale <= 0.0F) {
            return false;
        }
        // Every entity in the opening slab moves (Entity.move(SHULKER)): the player, and boats or ghasts this copy then
        // no longer follows
        final AABB slab = Shulker.getProgressDeltaAabb(scale, s.attachFace.getOpposite(), physicalOld, physical, new Vec3(s.x, s.y, s.z));
        for (int i = 0, n = this.movable == 0 ? 0 : this.all.size(); i < n; i++) {
            final Solid other = this.all.get(i);
            if (other.kind != SHULKER && !other.unknown && slab.intersects(other.minX, other.minY, other.minZ, other.maxX, other.maxY, other.maxZ)) {
                this.broke(other);
            }
        }
        return slab.intersects(pMinX, pMinY, pMinZ, pMaxX, pMaxY, pMaxZ);
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Entity primitives
    // ------------------------------------------------------------------------------------------------------------------

    // Entity.moveOrInterpolateTo(position, yRot, xRot, hasRotation): a boat's handler interpolates; a shulker's NO_OP
    // handler refuses, so the entity is set at once
    private void moveOrInterpolateTo(final Solid s, final @Nullable Vec3 position, final float yRot, final float xRot, final boolean hasRotation) {
        if (s.kind == BOAT) {
            // AbstractInterpolationHandler.interpolateTo: current = the target while active, else the entity
            final boolean active = s.remaining > 0;
            final double endX = position != null ? position.x : active ? s.targetX : s.x;
            final double endY = position != null ? position.y : active ? s.targetY : s.y;
            final double endZ = position != null ? position.z : active ? s.targetZ : s.z;
            final float toYRot = hasRotation ? yRot : active ? s.targetYRot : s.yRot;
            final float toXRot = hasRotation ? xRot : active ? s.targetXRot : s.xRot;
            // restart unless the same target is active (PositionAndRotation.is: ==, ==, Vec3.equals)
            if (!active || !(s.targetYRot == toYRot && s.targetXRot == toXRot && Double.compare(s.targetX, endX) == 0
                && Double.compare(s.targetY, endY) == 0 && Double.compare(s.targetZ, endZ) == 0)) {
                s.targetX = endX; // startInterpolating
                s.targetY = endY;
                s.targetZ = endZ;
                s.targetYRot = toYRot;
                s.targetXRot = toXRot;
                s.remaining = BOAT_STEPS;
                s.lastX = s.x; // setLastPositionAndRotation
                s.lastY = s.y;
                s.lastZ = s.z;
                s.lastYRot = s.yRot;
                s.lastXRot = s.xRot;
            }
            return;
        }
        if (position != null) {
            this.setPos(s, position.x, position.y, position.z);
        }
        if (hasRotation) {
            setRot(s, yRot, xRot);
        }
    }

    // Entity.snapTo(x, y, z, yRot, xRot): setPosRaw, rotation, setOldPosAndRot, reapplyPosition (no cancel). The raw
    // position comes first: a shulker's setPos inside reapplyPosition then sees no block change and keeps its peek, and
    // the raw position's section counts (a shulker snapped back into its old section re-enters it last)
    private void snapTo(final Solid s, final double x, final double y, final double z, final float yRot, final float xRot) {
        s.x = x;
        s.y = y;
        s.z = z;
        this.enter(s);
        setYRot(s, yRot);
        setXRot(s, xRot);
        this.setPos(s, x, y, z);
    }

    // Entity.setPos (Shulker.setPos snaps to its block's centre; a move to another block zeroes its peek) → the box
    private void setPos(final Solid s, final double x, final double y, final double z) {
        if (s.kind == SHULKER) {
            final int oldX = Mth.floor(s.x);
            final int oldY = Mth.floor(s.y);
            final int oldZ = Mth.floor(s.z);
            s.x = Mth.floor(x) + 0.5;
            s.y = Mth.floor(y + 0.5);
            s.z = Mth.floor(z) + 0.5;
            if (s.tickCount != 0 && (Mth.floor(s.x) != oldX || Mth.floor(s.y) != oldY || Mth.floor(s.z) != oldZ)) {
                s.rawPeek = 0; // the client sets its own DATA_PEEK_ID to 0
            }
            this.shulkerBox(s);
        } else {
            s.x = x;
            s.y = y;
            s.z = z;
            // EntityDimensions.makeBoundingBox: x - w, y, z - w, x + w, y + h, z + w with float w, h
            s.minX = x - s.halfWidth;
            s.minY = y;
            s.minZ = z - s.halfWidth;
            s.maxX = x + s.halfWidth;
            s.maxY = y + s.height;
            s.maxZ = z + s.halfWidth;
        }
        this.enter(s);
    }

    // A ghast's position is its stepped mirror's; the box its dimensions there
    private void followStepped(final Solid s) {
        final RemoteMirror m = s.stepped;
        s.x = m.x();
        s.y = m.y();
        s.z = m.z();
        if (!m.exact()) {
            this.broke(s);
        }
        this.ghastBox(s);
        this.enter(s);
    }

    // EntityDimensions.makeBoundingBox(position) with the dimensions of the last refreshDimensions
    private void ghastBox(final Solid s) {
        s.minX = s.x - s.halfWidth;
        s.minY = s.y;
        s.minZ = s.z - s.halfWidth;
        s.maxX = s.x + s.halfWidth;
        s.maxY = s.y + s.height;
        s.maxZ = s.z + s.halfWidth;
    }

    // Entity.refreshDimensions → LivingEntity.getDimensions(pose): SLEEPING_DIMENSIONS, else HappyGhast's default
    // (BABY_DIMENSIONS for a baby, else the type's 4 x 4) scaled by getScale() (HappyGhast.sanitizeScale: at most 1)
    private static void ghastDimensions(final Solid s) {
        final EntityDimensions dimensions = s.sleeping ? SLEEPING_DIMENSIONS
            : (s.baby ? GHAST_BABY_DIMENSIONS : EntityTypes.HAPPY_GHAST.getDimensions()).scale(Math.min(s.scale, 1.0F));
        s.halfWidth = dimensions.width() / 2.0F;
        s.height = dimensions.height();
    }

    // Shulker.makeBoundingBox: getProgressAabb(getScale(), attachFace.getOpposite(), physicalPeek(currentPeekAmount), pos)
    private void shulkerBox(final Solid s) {
        final AABB box = Shulker.getProgressAabb(shulkerScale(s), s.attachFace.getOpposite(), physicalPeek(s.peek), new Vec3(s.x, s.y, s.z));
        s.minX = box.minX;
        s.minY = box.minY;
        s.minZ = box.minZ;
        s.maxX = box.maxX;
        s.maxY = box.maxY;
        s.maxZ = box.maxZ;
    }

    // LivingEntity.getScale → Shulker.sanitizeScale: the live attribute, at most 3
    private static float shulkerScale(final Solid s) {
        return Math.min(s.scale, 3.0F);
    }

    // Shulker.getPhysicalPeek
    static float physicalPeek(final float amount) {
        return 0.5F - Mth.sin((0.5F + amount) * 3.1415927F) * 0.5F;
    }

    // Entity.setRot: setYRot(yRot % 360.0F), setXRot(xRot % 360.0F)
    private static void setRot(final Solid s, final float yRot, final float xRot) {
        setYRot(s, yRot % 360.0F);
        setXRot(s, xRot % 360.0F);
    }

    // Entity.setYRot / setXRot: a non-finite value is dropped; pitch is clamped
    private static void setYRot(final Solid s, final float yRot) {
        if (Float.isFinite(yRot)) {
            s.yRot = yRot;
        }
    }

    private static void setXRot(final Solid s, final float xRot) {
        if (Float.isFinite(xRot)) {
            s.xRot = Math.clamp(xRot % 360.0F, -90.0F, 90.0F);
        }
    }

    // Entity.setPosRaw → EntityInLevelCallback.onMove: a new section puts the entity at the end of that section's list
    private void enter(final Solid s) {
        final long section = SectionPos.asLong(SectionPos.blockToSectionCoord(Mth.floor(s.x)), SectionPos.blockToSectionCoord(Mth.floor(s.y)),
            SectionPos.blockToSectionCoord(Mth.floor(s.z)));
        if (s.entered == 0L || section != s.section) {
            s.section = section;
            s.entered = ++this.enterCounter;
        }
    }

    private void broke(final Solid s) {
        s.exact = false;
        if (!s.repairQueued && !s.driven && s.vehicle == -1) {
            s.repairQueued = true;
            this.repairs.add(s.id);
        }
    }

    private static double distanceToSqr(final double ax, final double ay, final double az, final double bx, final double by, final double bz) {
        final double dx = ax - bx;
        final double dy = ay - by;
        final double dz = az - bz;
        return dx * dx + dy * dy + dz * dz;
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Collision candidates
    // ------------------------------------------------------------------------------------------------------------------

    /** Receives candidates ({@code SimBoxList.add}). */
    public interface Sink {
        void add(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, double x, double y, double z, int rule);
    }

    /**
     * Every entity the player may collide with whose box intersects or touches the area, in the client's order
     * ({@code EntitySectionStorage.getEntities}: section x ascending, then the section key's z and y bits unsigned, then
     * the order each entered its section), with its rule (SimWorld.collectEntityColliders): boats; live shulkers; live
     * adult ghasts, solid while still, else only from above. Never a placeholder or a passenger (its vehicle places it;
     * one riding the player never collides).
     */
    public void collect(final double minX, final double minY, final double minZ, final double maxX, final double maxY, final double maxZ, final Sink out) {
        this.orderSize = 0;
        for (int i = 0, n = this.all.size(); i < n; i++) {
            final Solid s = this.all.get(i);
            if (s.unknown || s.vehicle != -1 || s.maxX < minX || s.minX > maxX || s.maxY < minY || s.minY > maxY || s.maxZ < minZ || s.minZ > maxZ) {
                continue;
            }
            if (s.kind != BOAT && !(s.health > 0.0F) || s.kind == GHAST && s.baby) {
                continue;
            }
            if (this.orderSize == this.order.length) {
                this.order = Arrays.copyOf(this.order, this.order.length * 2);
            }
            int at = this.orderSize++; // insertion sort: a handful of entities
            while (at > 0 && before(s, this.all.get(this.order[at - 1]))) {
                this.order[at] = this.order[at - 1];
                at--;
            }
            this.order[at] = i;
        }
        for (int k = 0; k < this.orderSize; k++) {
            final Solid s = this.all.get(this.order[k]);
            final int rule = s.kind == GHAST && !s.staysStill ? RULE_SOLID_FROM_ABOVE : RULE_SOLID;
            out.add(s.minX, s.minY, s.minZ, s.maxX, s.maxY, s.maxZ, s.x, s.y, s.z, rule);
        }
    }

    // EntitySectionStorage order: section x ascending, then the key's low 42 bits (z, then y) unsigned, then entry order
    private static boolean before(final Solid a, final Solid b) {
        final int ax = SectionPos.x(a.section);
        final int bx = SectionPos.x(b.section);
        if (ax != bx) {
            return ax < bx;
        }
        final int rest = Long.compareUnsigned(a.section & 0x3FFFFFFFFFFL, b.section & 0x3FFFFFFFFFFL);
        if (rest != 0) {
            return rest < 0;
        }
        return a.entered < b.entered;
    }

    /**
     * Whether a held copy that is not exact (placeholders aside: the caller tests those at the server's box) has its last
     * known box within the area: the port's collision with it may not be the client's.
     */
    public boolean untrustedWithin(final double minX, final double minY, final double minZ, final double maxX, final double maxY, final double maxZ) {
        for (int i = 0, n = this.all.size(); i < n; i++) {
            final Solid s = this.all.get(i);
            if (s.exact || s.unknown || s.vehicle == this.self) {
                continue;
            }
            if (s.maxX >= minX && s.minX <= maxX && s.maxY >= minY && s.minY <= maxY && s.maxZ >= minZ && s.minZ <= maxZ) {
                return true;
            }
        }
        return false;
    }

    /** Whether any copy is untrusted (a placeholder, inexact, a passenger, driven): the caller's server-side scan is needed. */
    public boolean anyUntrusted() {
        for (int i = 0, n = this.all.size(); i < n; i++) {
            final Solid s = this.all.get(i);
            if (!s.exact || s.unknown || s.vehicle != -1 || s.driven) {
                return true;
            }
        }
        return false;
    }

    /** Whether entity {@code id} rides this client's player (it never collides, wherever the server has it). */
    public boolean ridesSelf(final int id) {
        final Solid s = this.byId.get(id);
        return s != null && this.self != Integer.MIN_VALUE && s.vehicle == this.self;
    }

    /**
     * Moves the ids of copies that went inexact for a reason a fresh spawn fixes (once per spawn) to {@code out}: the
     * netcode re-pairs them, and their logged {@code AddEntity} replaces the copy.
     */
    public void takeRepairs(final IntArrayList out) {
        out.addAll(this.repairs);
        this.repairs.clear();
    }

    /** Whether entity {@code id}'s copy is exact (true when not held). */
    public boolean exact(final int id) {
        final Solid s = this.byId.get(id);
        return s == null || s.exact;
    }

    /** The box of entity {@code id} as the client holds it now: {@code out[0..5]}; false if not held. (Tests, rig.) */
    public boolean box(final int id, final double[] out) {
        final Solid s = this.held(id);
        if (s == null) {
            return false;
        }
        out[0] = s.minX;
        out[1] = s.minY;
        out[2] = s.minZ;
        out[3] = s.maxX;
        out[4] = s.maxY;
        out[5] = s.maxZ;
        return true;
    }

    /** Entity {@code id}'s position and rotation as the client holds them: {@code out[0..4]} (x, y, z, yRot, xRot). (Rig.) */
    public boolean position(final int id, final double[] out) {
        final Solid s = this.held(id);
        if (s == null) {
            return false;
        }
        out[0] = s.x;
        out[1] = s.y;
        out[2] = s.z;
        out[3] = s.yRot;
        out[4] = s.xRot;
        return true;
    }

    /** A boat's interpolation target and remaining steps ({@code out[0..4]}: x, y, z, yRot, xRot; returns the steps). (Rig.) */
    public int boatTarget(final int id, final double[] out) {
        final Solid s = this.held(id);
        if (s == null || s.kind != BOAT) {
            return -1;
        }
        out[0] = s.targetX;
        out[1] = s.targetY;
        out[2] = s.targetZ;
        out[3] = s.targetYRot;
        out[4] = s.targetXRot;
        return s.remaining;
    }

    /** A shulker's client peek ({@code currentPeekAmount}), NaN if not held. (Rig.) */
    public float peek(final int id) {
        final Solid s = this.held(id);
        return s == null ? Float.NaN : s.peek;
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Setup
    // ------------------------------------------------------------------------------------------------------------------

    // Every EntityTypes constant whose entity class extends base (EntityType<Boat>, <ChestBoat>, <Raft>, <ChestRaft>)
    private static Set<EntityType<?>> typesOf(final Class<?> base) {
        final HashSet<EntityType<?>> types = new HashSet<>();
        try {
            for (final Field field : EntityTypes.class.getFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == EntityType.class
                    && field.getGenericType() instanceof final ParameterizedType generic
                    && generic.getActualTypeArguments()[0] instanceof final Class<?> entity && base.isAssignableFrom(entity)) {
                    types.add((EntityType<?>) field.get(null));
                }
            }
        } catch (final IllegalAccessException e) {
            throw new ExceptionInInitializerError(e);
        }
        return Set.copyOf(types);
    }

    // The id of an EntityDataAccessor vanilla keeps private
    private static int dataId(final Class<?> owner, final String field) {
        try {
            return ((EntityDataAccessor<?>) MethodHandles.privateLookupIn(owner, MethodHandles.lookup())
                .findStaticVarHandle(owner, field, EntityDataAccessor.class).get()).id();
        } catch (final ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
