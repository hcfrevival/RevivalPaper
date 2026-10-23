package gg.hcfrevival.revival.auth;

import gg.hcfrevival.revival.auth.net.ClientStream;
import gg.hcfrevival.revival.auth.sim.ExemptReason;
import gg.hcfrevival.revival.auth.sim.MovementSim;
import gg.hcfrevival.revival.auth.sim.SimAttributes;
import gg.hcfrevival.revival.auth.sim.SimPlayer;
import gg.hcfrevival.revival.auth.sim.SimTickInput;
import gg.hcfrevival.revival.auth.view.ClientView;
import gg.hcfrevival.revival.auth.view.WorldView;
import java.util.List;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.GameType;
import org.jspecify.annotations.Nullable;

/**
 * The movement port ({@code auth.sim}: {@link MovementSim} over a {@link SimPlayer}) behind the netcode's
 * {@link Simulation} calls. The client-view state the port reads lives in {@link SimPlayer}; this class keeps it in step
 * with {@link ClientView} (items, effects, abilities, game mode, food, riding/dead/camera) before every tick, forwards
 * attribute snapshots as they are acked, and mirrors the port's own sprint toggles into {@link ClientView}'s attributes so
 * both copies of the client's attribute history agree.
 */
public final class PortSimulation implements Simulation {

    private static final EquipmentSlot[] GLIDER_SLOTS = EquipmentSlot.values();

    private final ServerPlayer owner;
    private final MovementSim sim = new MovementSim();
    private final SimTickInput input = new SimTickInput();
    private SimPlayer player = new SimPlayer();
    private final SimPlayer belief = new SimPlayer();
    // The state before the last tick and that tick's input, for the hotbar branch (§15.4)
    private final SimPlayer previous = new SimPlayer();
    private boolean previousValid;
    // The state before the last tick's attack, for the attack-strength branch (§15.4)
    private final SimPlayer beforeAttack = new SimPlayer();
    private boolean attackedThisTick;
    private boolean attackedLastTick;
    private final SimPlayer alternative = new SimPlayer();
    private final SimPlayer forecastTruth = new SimPlayer();
    private final SimPlayer forecastClient = new SimPlayer();
    private Input previousKeys = Input.EMPTY;
    private float previousYRot;
    private float previousXRot;
    private boolean beliefOn;
    private boolean beliefValid;
    // The belief's last tick read a hole (a block the client broke and the server kept)
    private boolean beliefHole;
    private boolean seeded;
    private long sprintMismatches;
    private long flightMismatches;

    public PortSimulation(final ServerPlayer owner) {
        this.owner = owner;
    }

    /** The port's state (the reconciler forks it; tests and the inspector read it). */
    public SimPlayer state() {
        return this.player;
    }

    public MovementSim movement() {
        return this.sim;
    }

    public long sprintMismatches() {
        return this.sprintMismatches;
    }

    public long flightMismatches() {
        return this.flightMismatches;
    }

    @Override
    public int unsupported(final ClientView view) {
        this.sync(view);
        final ExemptReason reason = MovementSim.exemptReason(this.player);
        return switch (reason) {
            case NONE -> AuthStats.EXEMPT_NONE;
            case PASSENGER -> AuthStats.EXEMPT_RIDING;
            case SLEEPING -> AuthStats.EXEMPT_SLEEPING;
            case DEAD -> AuthStats.EXEMPT_DEAD;
            case SPECTATOR_CAMERA -> AuthStats.EXEMPT_SPECTATOR_CAMERA;
            default -> AuthStats.EXEMPT_SIMULATION_BASE + reason.ordinal();
        };
    }

    @Override
    public boolean seeded() {
        return this.seeded;
    }

    @Override
    public void seed(final double x, final double y, final double z, final double vx, final double vy, final double vz,
                     final float yRot, final float xRot, final boolean onGround, final ClientView view, final WorldView world) {
        final SimPlayer p = this.player;
        p.setPos(x, y, z);
        p.xo = x;
        p.yo = y;
        p.zo = z;
        p.vx = vx;
        p.vy = vy;
        p.vz = vz;
        p.yRot = p.yRotO = yRot;
        p.xRot = p.xRotO = xRot;
        p.onGround = onGround;
        this.sync(view);
        this.seeded = true;
        this.beliefValid = false;
        this.previousValid = false;
    }

    private final double[] seedVelocity = new double[3];

    @Override
    public boolean seedFromMove(final double fromX, final double fromY, final double fromZ, final double toX, final double toY, final double toZ,
                                final @Nullable Input keys, final float yRot, final float xRot, final boolean onGround, final ClientView view,
                                final WorldView world) {
        this.seed(toX, toY, toZ, 0.0, 0.0, 0.0, yRot, xRot, onGround, view, world);
        // p is the state the tick started from (its position and velocity are not read): the seed, synced from the view.
        // With the tick's sent keys the port drops the cases the keys decide (auth-sim §3.4); seeding only, never per tick
        final int why = keys != null
            ? this.sim.velocityAfterMove(this.player, keys, fromX, fromY, fromZ, toX, toY, toZ, world, this.seedVelocity)
            : this.sim.velocityAfterMove(this.player, fromX, fromY, fromZ, toX, toY, toZ, world, this.seedVelocity);
        final double[] v = this.seedVelocity;
        if ((why & MovementSim.VELOCITY_NO_TICK) == 0 && Double.isFinite(v[0]) && Double.isFinite(v[1]) && Double.isFinite(v[2])) {
            this.sim.setVelocity(this.player, v[0], v[1], v[2]);
        }
        return why == 0;
    }

    @Override
    public void unseed() {
        this.seeded = false;
    }

    @Override
    public void rebase(final double x, final double y, final double z) {
        this.player.setPos(x, y, z);
    }

    @Override
    public void setVelocity(final double x, final double y, final double z) {
        this.sim.setVelocity(this.player, x, y, z);
    }

    @Override
    public void addVelocity(final double x, final double y, final double z) {
        this.sim.addVelocity(this.player, x, y, z);
    }

    @Override
    public void position(final PositionMoveRotation change, final Set<Relative> relatives) {
        this.sim.teleport(this.player, change, relatives);
        this.seeded = true;
        this.previousValid = false;
    }

    @Override
    public void rotation(final float yRot, final boolean relativeY, final float xRot, final boolean relativeX) {
        this.sim.rotation(this.player, yRot, relativeY, xRot, relativeX);
    }

    @Override
    public void snapTo(final double x, final double y, final double z) {
        this.sim.snapTo(this.player, x, y, z);
    }

    @Override
    public void echoSharedFlags(final byte flags) {
        this.sim.applySharedFlags(this.player, flags);
    }

    @Override
    public void echoPose(final Pose pose) {
        this.sim.applyPose(this.player, pose);
    }

    @Override
    public void echoUsingItem(final boolean using, final InteractionHand hand) {
        this.sim.echoUsingItem(this.player, using, hand, this.useDuration(hand));
    }

    @Override
    public void completeUsingItem() {
        this.sim.completeUsingItem(this.player);
    }

    // shortcut: attribute syncs and the self echoes below (flags, pose, use) reach the truth only; the belief takes them by
    // re-basing on an exact claim. A correction forecast while the client is off the truth across one of them (a speed
    // effect's sync, a sprint echo) misses by it. Give the belief its own copy, forced entries excepted as for knocks
    // (beliefVelocity), if a correction after one shows a residual
    @Override
    public void attributes(final List<ClientboundUpdateAttributesPacket.AttributeSnapshot> snapshots) {
        for (final ClientboundUpdateAttributesPacket.AttributeSnapshot snapshot : snapshots) {
            List<net.minecraft.world.entity.ai.attributes.AttributeModifier> modifiers = List.copyOf(snapshot.modifiers());
            if (snapshot.attribute().equals(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED)) {
                // §15.4: the truth has the sprint modifier only while it sprints. The client copies whatever the sync says
                // (MC-69459), so a sync sent while sprinting and acked after the sprint ended would keep sprint speed
                modifiers = sprintFromState(modifiers, this.player.sprinting);
            }
            this.sim.applyAttributeUpdate(this.player, snapshot.attribute(), snapshot.base(), modifiers);
        }
    }

    // The modifiers with the sprinting one present iff {@code sprinting}
    static List<net.minecraft.world.entity.ai.attributes.AttributeModifier> sprintFromState(
        final List<net.minecraft.world.entity.ai.attributes.AttributeModifier> modifiers, final boolean sprinting) {
        boolean has = false;
        for (final net.minecraft.world.entity.ai.attributes.AttributeModifier modifier : modifiers) {
            has |= modifier.id().equals(gg.hcfrevival.revival.auth.view.ClientAttributes.SPRINTING_MODIFIER_ID);
        }
        if (has == sprinting) {
            return modifiers;
        }
        final java.util.ArrayList<net.minecraft.world.entity.ai.attributes.AttributeModifier> out = new java.util.ArrayList<>(modifiers.size() + 1);
        for (final net.minecraft.world.entity.ai.attributes.AttributeModifier modifier : modifiers) {
            if (!modifier.id().equals(gg.hcfrevival.revival.auth.view.ClientAttributes.SPRINTING_MODIFIER_ID)) {
                out.add(modifier);
            }
        }
        if (sprinting) {
            out.add(gg.hcfrevival.revival.auth.view.ClientAttributes.SPEED_MODIFIER_SPRINTING);
        }
        return out;
    }

    @Override
    public void localGameMode(final GameType mode) {
        this.sim.setLocalGameMode(this.player, mode);
    }

    @Override
    public void tabListGameMode(final GameType mode) {
        this.sim.setTabListGameMode(this.player, mode); // before sync copies the view's mode: onGameModeChanged sees the change
    }

    @Override
    public void externalMover(final boolean moving) {
        this.player.externalMover = moving;
    }

    @Override
    public void attachedRockets(final int count) {
        this.rockets = count;
    }

    private int rockets;

    // The client's tick, then its attached rockets' ticks (they tick after the player, in the order they were added)
    private void tickWithRockets(final SimPlayer p, final WorldView world) {
        this.sim.tick(p, this.input, world);
        for (int i = 0; i < this.rockets; i++) {
            this.sim.fireworkBoost(p);
        }
    }

    @Override
    public void respawn(final boolean keepEntityData, final boolean keepAttributeModifiers) {
        final SimPlayer old = this.player;
        final SimPlayer fresh = new SimPlayer();
        if (keepAttributeModifiers) {
            fresh.attributes.copyFrom(old.attributes);
        } else {
            for (final Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute : MOVEMENT_ATTRIBUTES) {
                final SimAttributes.Instance from = old.attributes.get(attribute);
                final SimAttributes.Instance to = fresh.attributes.get(attribute);
                if (from != null && to != null) {
                    to.setBaseValue(from.baseValue());
                }
            }
        }
        if (keepEntityData) {
            fresh.vx = old.vx;
            fresh.vy = old.vy;
            fresh.vz = old.vz;
            fresh.yRot = old.yRot;
            fresh.xRot = old.xRot;
            fresh.sprinting = old.sprinting;
            fresh.swimming = old.swimming;
            fresh.fallFlying = old.fallFlying;
            fresh.pose = old.pose;
            fresh.keys = old.keys;
        } else {
            fresh.yRot = -180.0F;
        }
        // The client's hidden options survive a new LocalPlayer (auth-sim §2, §3.1)
        fresh.autoJumpOption = old.autoJumpOption;
        fresh.sprintWindow = old.sprintWindow;
        this.player = fresh;
        this.seeded = false;
        this.beliefValid = false;
        this.previousValid = false;
    }

    private static final List<Holder<net.minecraft.world.entity.ai.attributes.Attribute>> MOVEMENT_ATTRIBUTES = List.of(
        net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED, net.minecraft.world.entity.ai.attributes.Attributes.SNEAKING_SPEED,
        net.minecraft.world.entity.ai.attributes.Attributes.JUMP_STRENGTH, net.minecraft.world.entity.ai.attributes.Attributes.STEP_HEIGHT,
        net.minecraft.world.entity.ai.attributes.Attributes.GRAVITY, net.minecraft.world.entity.ai.attributes.Attributes.SCALE,
        net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_EFFICIENCY, net.minecraft.world.entity.ai.attributes.Attributes.WATER_MOVEMENT_EFFICIENCY,
        net.minecraft.world.entity.ai.attributes.Attributes.FRICTION_MODIFIER, net.minecraft.world.entity.ai.attributes.Attributes.AIR_DRAG_MODIFIER,
        net.minecraft.world.entity.ai.attributes.Attributes.BOUNCINESS, net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED,
        net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_KNOCKBACK, net.minecraft.world.entity.ai.attributes.Attributes.ENTITY_INTERACTION_RANGE,
        net.minecraft.world.entity.ai.attributes.Attributes.BLOCK_INTERACTION_RANGE);

    @Override
    public void clientAttack(final boolean targetIsPlayer, final ClientView view) {
        if (this.beliefOn && targetIsPlayer && !this.attackedThisTick) {
            this.beforeAttack.copyFrom(this.player);
            this.attackedThisTick = true;
        }
        final boolean before = this.player.sprinting;
        this.sim.clientAttack(this.player, targetIsPlayer);
        if (before != this.player.sprinting) {
            view.attributes.setSprinting(this.player.sprinting);
        }
        if (this.beliefOn && this.beliefValid) {
            this.sim.clientAttack(this.belief, targetIsPlayer); // the client slowed itself wherever it is, on the truth or off it
        }
    }

    @Override
    public void startUsingItem(final InteractionHand hand) {
        this.sim.startUsingItem(this.player, hand, this.useDuration(hand));
        if (this.beliefOn && this.beliefValid) {
            this.sim.startUsingItem(this.belief, hand, this.useDuration(hand));
        }
    }

    @Override
    public void startUsingItem(final InteractionHand hand, final ClientView view) {
        this.player.mainHand = view.items.mainHand();
        this.player.offHand = view.items.hand(InteractionHand.OFF_HAND);
        if (this.beliefOn && this.beliefValid) {
            this.belief.mainHand = this.player.mainHand;
            this.belief.offHand = this.player.offHand;
        }
        this.startUsingItem(hand);
    }

    @Override
    public void releaseUsingItem() {
        this.sim.releaseUsingItem(this.player);
        if (this.beliefOn && this.beliefValid) {
            this.sim.releaseUsingItem(this.belief);
        }
    }

    @Override
    public void resetAttackStrength() {
        // MultiPlayerGameMode.stopDestroyBlock -> resetAttackStrengthTicker, in the drain before the tick
        this.player.attackStrengthTicker = 0;
        this.belief.attackStrengthTicker = 0;
        this.player.strengthUncertain = false;
        this.belief.strengthUncertain = false;
    }

    @Override
    public void trackBelief(final boolean on) {
        this.beliefOn = on;
        if (!on) {
            this.beliefValid = false;
        }
    }

    @Override
    public void belief(final boolean exact, final double claimX, final double claimY, final double claimZ) {
        if (!this.beliefOn) {
            return;
        }
        if (exact || !this.beliefValid) {
            this.belief.copyFrom(this.player);
            this.beliefValid = true;
        }
        if (!exact) {
            this.belief.setPos(claimX, claimY, claimZ);
        }
    }

    @Override
    public boolean beliefValid() {
        return this.beliefOn && this.beliefValid;
    }

    @Override
    public void rebaseBelief(final double x, final double y, final double z, final double vx, final double vy, final double vz) {
        if (!this.beliefOn) {
            return;
        }
        this.belief.copyFrom(this.player);
        this.belief.setPos(x, y, z);
        this.sim.setVelocity(this.belief, vx, vy, vz);
        this.beliefValid = true;
    }

    @Override
    public void beliefCorrected(final PositionMoveRotation change, final Set<Relative> relatives) {
        if (this.beliefOn && this.beliefValid) {
            this.sim.teleport(this.belief, change, relatives);
        }
    }

    @Override
    public void beliefVelocity(final double x, final double y, final double z, final boolean add) {
        if (this.beliefOn && this.beliefValid) {
            if (add) {
                this.sim.addVelocity(this.belief, x, y, z);
            } else {
                this.sim.setVelocity(this.belief, x, y, z);
            }
        }
    }

    @Override
    public void tickBelief(final Input keys, final float yRot, final float xRot, final ClientView view, final WorldView world) {
        this.beliefHole = false;
        if (this.beliefOn && this.beliefValid) {
            this.stepBelief(keys, yRot, xRot, view, world);
        }
    }

    // The client's own state moves with its own inputs too (§15.5), in its own world (denied breaks it still has)
    private void stepBelief(final Input keys, final float yRot, final float xRot, final ClientView view, final WorldView world) {
        this.sync(this.belief, view);
        this.input.set(keys, yRot, xRot);
        world.takeHoleRead();
        world.readAsClient(true);
        try {
            this.tickWithRockets(this.belief, world);
        } finally {
            world.readAsClient(false);
            this.beliefHole = world.takeHoleRead();
        }
    }

    @Override
    public boolean beliefTouchedHole() {
        return this.beliefHole;
    }

    @Override
    public double beliefPos(final int axis) {
        final SimPlayer p = this.beliefOn && this.beliefValid ? this.belief : this.player;
        return axis == 0 ? p.x : axis == 1 ? p.y : p.z;
    }

    @Override
    public double beliefDistance(final double x, final double y, final double z) {
        if (!this.beliefOn || !this.beliefValid) {
            return Double.NaN;
        }
        final double dx = this.belief.x - x;
        final double dy = this.belief.y - y;
        final double dz = this.belief.z - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Override
    public boolean snapBeliefOut(final net.minecraft.core.BlockPos pos, final net.minecraft.world.level.block.state.BlockState state, final double x,
                                 final double y, final double z) {
        if (!this.beliefOn || !this.beliefValid || !collides(this.belief.bb, pos, state)) {
            return false;
        }
        this.sim.snapTo(this.belief, x, y, z);
        return true;
    }

    // Entity.isColliding(pos, state): the block's collision shape there overlaps the box
    static boolean collides(final gg.hcfrevival.revival.auth.sim.SimBox bb, final net.minecraft.core.BlockPos pos,
                            final net.minecraft.world.level.block.state.BlockState state) {
        final net.minecraft.world.phys.shapes.VoxelShape shape = state.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, pos,
            net.minecraft.world.phys.shapes.CollisionContext.empty());
        if (shape.isEmpty()) {
            return false;
        }
        return net.minecraft.world.phys.shapes.Shapes.joinIsNotEmpty(shape.move(pos.getX(), pos.getY(), pos.getZ()),
            net.minecraft.world.phys.shapes.Shapes.create(bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ),
            net.minecraft.world.phys.shapes.BooleanOp.AND);
    }

    @Override
    public boolean correction(final int ticksAhead, final Input keys, final float yRot, final float xRot, final ClientView view, final WorldView world,
                              final double[] out) {
        return this.correction(ticksAhead, keys, yRot, xRot, view, world, out, null);
    }

    @Override
    public boolean correction(final int ticksAhead, final Input keys, final float yRot, final float xRot, final ClientView view, final WorldView world,
                              final double[] out, final gg.hcfrevival.revival.auth.net.Transactions.@Nullable Acked knocks) {
        if (!this.beliefValid) {
            return false;
        }
        final SimPlayer truth = this.forecastTruth;
        truth.copyFrom(this.player);
        final SimPlayer client = this.forecastClient;
        client.copyFrom(this.belief);
        if (knocks != null) {
            for (int i = 0; i < knocks.size(); i++) {
                this.knock(client, knocks.packet(i));
                if (!knocks.upperBound(i)) {
                    this.knock(truth, knocks.packet(i)); // not forced: the truth takes it at its pong, before the correction's
                }
            }
        }
        this.input.set(keys, yRot, xRot);
        for (int i = 0; i < ticksAhead; i++) {
            this.sync(truth, view);
            this.tickWithRockets(truth, world);
            this.sync(client, view);
            world.readAsClient(true); // the client's own world: the breaks it predicted that the server denied
            try {
                this.tickWithRockets(client, world);
            } finally {
                world.readAsClient(false);
            }
        }
        out[0] = truth.x - client.x;
        out[1] = truth.y - client.y;
        out[2] = truth.z - client.z;
        out[3] = truth.vx - client.vx;
        out[4] = truth.vy - client.vy;
        out[5] = truth.vz - client.vz;
        if (out.length >= 13) {
            world.readAsClient(true);
            try {
                out[6] = insideShape(client.bb, world) ? 1.0 : 0.0;
            } finally {
                world.readAsClient(false);
            }
            out[7] = truth.x;
            out[8] = truth.y;
            out[9] = truth.z;
            out[10] = truth.vx;
            out[11] = truth.vy;
            out[12] = truth.vz;
        }
        return true;
    }

    @Override
    public double velocityGap(final gg.hcfrevival.revival.auth.net.Transactions.@Nullable Acked knocks) {
        if (!this.beliefOn || !this.beliefValid) {
            return Double.NaN;
        }
        double tx = this.player.vx;
        double ty = this.player.vy;
        double tz = this.player.vz;
        double cx = this.belief.vx;
        double cy = this.belief.vy;
        double cz = this.belief.vz;
        if (knocks != null) {
            for (int i = 0; i < knocks.size(); i++) {
                final boolean truthToo = !knocks.upperBound(i);
                if (knocks.packet(i) instanceof final net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket motion) {
                    gg.hcfrevival.revival.auth.net.Wire.lpVec3(motion.movement().x, motion.movement().y, motion.movement().z, this.knockDecoded);
                    cx = this.knockDecoded[0];
                    cy = this.knockDecoded[1];
                    cz = this.knockDecoded[2];
                    if (truthToo) {
                        tx = cx;
                        ty = cy;
                        tz = cz;
                    }
                } else if (knocks.packet(i) instanceof final net.minecraft.network.protocol.game.ClientboundExplodePacket explode
                    && explode.playerKnockback().isPresent()) {
                    final net.minecraft.world.phys.Vec3 k = explode.playerKnockback().get();
                    if (Double.isFinite(k.x) && Double.isFinite(k.y) && Double.isFinite(k.z)) {
                        cx += k.x;
                        cy += k.y;
                        cz += k.z;
                        if (truthToo) {
                            tx += k.x;
                            ty += k.y;
                            tz += k.z;
                        }
                    }
                }
            }
        }
        final double dx = tx - cx;
        final double dy = ty - cy;
        final double dz = tz - cz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private final double[] knockDecoded = new double[3];

    // A knock as the client applies it: SetEntityMotion replaces the velocity (LpVec3-decoded), Explode adds
    private void knock(final SimPlayer p, final net.minecraft.network.protocol.Packet<?> packet) {
        if (packet instanceof final net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket motion) {
            gg.hcfrevival.revival.auth.net.Wire.lpVec3(motion.movement().x, motion.movement().y, motion.movement().z, this.knockDecoded);
            this.sim.setVelocity(p, this.knockDecoded[0], this.knockDecoded[1], this.knockDecoded[2]);
        } else if (packet instanceof final net.minecraft.network.protocol.game.ClientboundExplodePacket explode && explode.playerKnockback().isPresent()) {
            final net.minecraft.world.phys.Vec3 k = explode.playerKnockback().get();
            if (Double.isFinite(k.x) && Double.isFinite(k.y) && Double.isFinite(k.z)) {
                this.sim.addVelocity(p, k.x, k.y, k.z);
            }
        }
    }

    /** Whether the client's box would be inside a block's collision shape, where its own collision no longer stops it. */
    @Override
    public boolean beliefInsideShape(final WorldView world) {
        if (!this.beliefValid) {
            return false;
        }
        world.readAsClient(true);
        try {
            return insideShape(this.belief.bb, world);
        } finally {
            world.readAsClient(false);
        }
    }

    // A box overlapping a collision shape by more than the collision epsilon (Shapes.collide ignores less)
    static boolean insideShape(final gg.hcfrevival.revival.auth.sim.SimBox bb, final WorldView world) {
        final double inset = 1.0E-7;
        final net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(bb.minX + inset, bb.minY + inset, bb.minZ + inset,
            bb.maxX - inset, bb.maxY - inset, bb.maxZ - inset);
        final int minX = net.minecraft.util.Mth.floor(box.minX);
        final int minY = net.minecraft.util.Mth.floor(box.minY);
        final int minZ = net.minecraft.util.Mth.floor(box.minZ);
        final int maxX = net.minecraft.util.Mth.floor(box.maxX);
        final int maxY = net.minecraft.util.Mth.floor(box.maxY);
        final int maxZ = net.minecraft.util.Mth.floor(box.maxZ);
        final net.minecraft.core.BlockPos.MutableBlockPos pos = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    final net.minecraft.world.level.block.state.BlockState state = world.blockState(x, y, z);
                    if (state.isAir()) {
                        continue;
                    }
                    pos.set(x, y, z);
                    final net.minecraft.world.phys.shapes.VoxelShape shape = state.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, pos);
                    if (!shape.isEmpty() && shape.move(x, y, z).toAabbs().stream().anyMatch(box::intersects)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public void tick(final Input keys, final Input previousKeys, final float yRot, final float xRot, final int sprintCommand,
                     final int flyingCommand, final boolean fallFlyingCommand, final ClientView view, final WorldView world) {
        final SimPlayer p = this.player;
        this.beliefHole = false;
        if (this.beliefOn && this.beliefValid) {
            this.stepBelief(keys, yRot, xRot, view, world);
        }
        this.sync(view);
        if (this.beliefOn) {
            this.previous.copyFrom(p);
            this.previousValid = true;
            this.previousKeys = keys;
            this.previousYRot = yRot;
            this.previousXRot = xRot;
        }
        this.attackedLastTick = this.attackedThisTick;
        this.attackedThisTick = false;
        final boolean wasSprinting = p.sprinting;
        this.input.set(keys, yRot, xRot);
        this.tickWithRockets(p, world);
        if (wasSprinting != p.sprinting) {
            view.attributes.setSprinting(p.sprinting);
        }
        view.setFlying(p.flying); // the client toggles flight itself in aiStep; its PlayerAbilities report is a cross-check
        // Cross-checks against what the client reported for this tick
        if (sprintCommand == ClientStream.SPRINT_START && !p.sprinting || sprintCommand == ClientStream.SPRINT_STOP && p.sprinting
            || sprintCommand == ClientStream.SPRINT_NONE && wasSprinting != p.sprinting) {
            this.sprintMismatches++;
        }
        if (flyingCommand >= 0 && p.flying != (flyingCommand == 1)) {
            this.flightMismatches++;
        }
    }

    private void sync(final ClientView view) {
        this.sync(this.player, view);
    }

    @Override
    public boolean retryWithSlot(final int slot, final ClientView view, final WorldView world, final double claimX, final double claimY, final double claimZ) {
        if (!this.previousValid) {
            return false;
        }
        final SimPlayer alternative = this.alternative;
        alternative.copyFrom(this.previous);
        this.sync(alternative, view);
        alternative.mainHand = view.items.get(slot);
        return this.keepIfClaimed(alternative, view, world, claimX, claimY, claimZ);
    }

    @Override
    public boolean retryAttackStrength(final ClientView view, final WorldView world, final double claimX, final double claimY, final double claimZ) {
        if (!this.previousValid || !this.attackedLastTick) {
            return false;
        }
        final SimPlayer alternative = this.alternative;
        alternative.copyFrom(this.beforeAttack);
        // The attack as the port ran it (MovementSim.clientAttack, Player.java:1799-1801 and 1867-1871), then flipped
        final float delay = (float) (1.0 / alternative.attributes.attackSpeed.value() * 20.0);
        final int ticker = alternative.attackStrengthTicker;
        final boolean fullStrength = fullStrength(ticker, delay);
        // Only to a strength the client's ticker could have had, and only while the ticker counts from a reset the port
        // can't pin to a tick (an item change, H9): its one tick either way (the threshold), or reset when that change was
        // a tick ago. Counted from an attack or a dig's stop the ticker is exact, so a 26.x weapon's full-strength sprint hit
        // isn't explained by a weak one (fable review #7b: KeepSprint on every axe hit). A legacy sword's maxed attack
        // speed is full strength at any ticker (pvp-issues SP-3)
        if (!alternative.strengthUncertain || fullStrength(ticker + 1, delay) == fullStrength
            && (ticker == 0 || fullStrength(ticker - 1, delay) == fullStrength)
            && (alternative.itemSwapTicker > 1 || fullStrength(0, delay) == fullStrength)) {
            return false;
        }
        final float base = (float) alternative.attributes.attackKnockback.value();
        final boolean slowed = base + (alternative.sprinting && fullStrength ? 0.5F : 0.0F) > 0.0F;
        final boolean flipped = base + (alternative.sprinting && !fullStrength ? 0.5F : 0.0F) > 0.0F;
        if (slowed == flipped) {
            return false; // the strength didn't decide the attacker's own movement
        }
        if (flipped) {
            alternative.vx *= 0.6;
            alternative.vz *= 0.6;
            MovementSim.setSprinting(alternative, false);
        }
        alternative.attackStrengthTicker = 0;
        alternative.itemSwapTicker = 0;
        alternative.strengthUncertain = false; // reset by the attack, at a known tick
        this.sync(alternative, view);
        return this.keepIfClaimed(alternative, view, world, claimX, claimY, claimZ);
    }

    // Player.getAttackStrengthScale(0.5F) > 0.9 with the attack strength ticker at {@code ticker}
    private static boolean fullStrength(final int ticker, final float delay) {
        return Mth.clamp((ticker + 0.5F) / delay, 0.0F, 1.0F) > 0.9F;
    }

    // Runs the last tick's input on the alternative; keeps it when it lands exactly on the claim
    private boolean keepIfClaimed(final SimPlayer alternative, final ClientView view, final WorldView world, final double claimX, final double claimY,
                                  final double claimZ) {
        this.input.set(this.previousKeys, this.previousYRot, this.previousXRot);
        this.tickWithRockets(alternative, world);
        if (alternative.x != claimX || alternative.y != claimY || alternative.z != claimZ) {
            return false;
        }
        if (alternative.sprinting != this.player.sprinting) {
            view.attributes.setSprinting(alternative.sprinting);
        }
        this.player.copyFrom(alternative);
        this.beliefValid = false;
        return true;
    }

    private final double[] collided = new double[3];

    @Override
    public boolean clearPath(final double dx, final double dy, final double dz, final WorldView world) {
        this.sim.collide(this.player, world, dx, dy, dz, this.collided);
        return this.collided[0] == dx && this.collided[1] == dy && this.collided[2] == dz;
    }

    @Override
    public boolean swimming() {
        return this.player.swimming;
    }

    @Override
    public boolean fallFlying() {
        return this.player.fallFlying;
    }

    @Override
    public boolean usingItem() {
        return this.player.usingItem;
    }

    @Override
    public net.minecraft.world.item.ItemStack useItem() {
        return this.player.useItem;
    }

    // Client-view state the port reads, from ClientView
    private void sync(final SimPlayer p, final ClientView view) {
        p.mainHand = view.items.mainHand();
        p.offHand = view.items.hand(InteractionHand.OFF_HAND);
        p.feet = view.items.equipment(EquipmentSlot.FEET);
        boolean glider = false;
        for (final EquipmentSlot slot : GLIDER_SLOTS) {
            final ItemStack stack = view.items.equipment(slot);
            if (stack.has(DataComponents.GLIDER) && !stack.nextDamageWillBreak()) {
                final Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
                glider |= equippable != null && equippable.slot() == slot;
            }
        }
        p.gliderAvailable = glider;
        effect(p, view, MobEffects.JUMP_BOOST);
        effect(p, view, MobEffects.SLOW_FALLING);
        effect(p, view, MobEffects.LEVITATION);
        effect(p, view, MobEffects.DOLPHINS_GRACE);
        effect(p, view, MobEffects.BLINDNESS);
        effect(p, view, MobEffects.WEAVING);
        p.flying = view.flying();
        p.mayfly = view.mayfly();
        p.instabuild = view.instabuild();
        p.flyingSpeed = view.flyingSpeed();
        p.spectator = view.spectator();
        p.localSpectator = view.gameMode() == GameType.SPECTATOR;
        p.foodLevel = view.foodLevel();
        p.noGravity = view.noGravity();
        p.passenger = view.passenger();
        p.dead = view.dead();
        p.cameraIsSelf = !view.cameraOther();
        p.sleeping = view.pose() == Pose.SLEEPING;
    }

    private static void effect(final SimPlayer p, final ClientView view, final Holder<MobEffect> effect) {
        final ClientView.Effect held = view.effect(effect);
        if (held == null) {
            p.removeEffect(effect);
        } else {
            p.addEffect(effect, held.amplifier());
        }
    }

    // ItemStack.getUseDuration(entity) of the client's stack in that hand
    private int useDuration(final InteractionHand hand) {
        final ItemStack stack = hand == InteractionHand.MAIN_HAND ? this.player.mainHand : this.player.offHand;
        return stack.isEmpty() ? 0 : stack.getUseDuration(this.owner);
    }

    // State ---------------------------------------------------------------------------------------------------------

    @Override
    public double x() {
        return this.player.x;
    }

    @Override
    public double y() {
        return this.player.y;
    }

    @Override
    public double z() {
        return this.player.z;
    }

    @Override
    public double xo() {
        return this.player.xo;
    }

    @Override
    public double yo() {
        return this.player.yo;
    }

    @Override
    public double zo() {
        return this.player.zo;
    }

    @Override
    public double vx() {
        return this.player.vx;
    }

    @Override
    public double vy() {
        return this.player.vy;
    }

    @Override
    public double vz() {
        return this.player.vz;
    }

    @Override
    public float yRot() {
        return this.player.yRot;
    }

    @Override
    public float xRot() {
        return this.player.xRot;
    }

    @Override
    public boolean onGround() {
        return this.player.onGround;
    }

    @Override
    public boolean horizontalCollision() {
        return this.player.horizontalCollision;
    }

    @Override
    public double fallDistance() {
        return this.player.fallDistance;
    }

    @Override
    public boolean sprinting() {
        return this.player.sprinting;
    }

    @Override
    public float eyeHeight() {
        return this.player.eyeHeight;
    }

    @Override
    public net.minecraft.world.phys.AABB box() {
        final gg.hcfrevival.revival.auth.sim.SimBox bb = this.player.bb;
        return new net.minecraft.world.phys.AABB(bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ);
    }

    @Override
    public Pose pose() {
        return this.player.pose;
    }
}
