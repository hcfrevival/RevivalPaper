package gg.hcfrevival.revival.auth;

import gg.hcfrevival.revival.auth.view.ClientView;
import gg.hcfrevival.revival.auth.view.WorldView;
import java.util.Set;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.GameType;

/**
 * What the netcode needs from the movement simulation of one player: the exact vanilla 26.3 client port in
 * {@code gg.hcfrevival.revival.auth.sim} (report 01 §11). The netcode decides <em>when</em> (transactions, windows) and
 * hands the simulation <em>what</em> the client applied, in client order:
 * <ol>
 *   <li>drain-time changes from acked packets ({@link #setVelocity}, {@link #addVelocity}, {@link #position},
 *       {@link #rotation}, {@link #snapTo}, entity-data echoes; attributes, effects, abilities, items, food and the
 *       world come from {@link ClientView}/{@link WorldView}, which the simulation reads);</li>
 *   <li>the pre-physics actions of the tick's keybinds ({@link #clientAttack}, {@link #startUsingItem},
 *       {@link #releaseUsingItem});</li>
 *   <li>{@link #tick}.</li>
 * </ol>
 * Until the port lands, {@link EchoSimulation} stands in: it follows the client's claims and reports every tick as
 * exempt ({@link AuthStats#EXEMPT_NO_SIMULATION}).
 */
public interface Simulation {

    /**
     * Why the next tick can't be simulated ({@code AuthStats.EXEMPT_*}), or 0. States the port doesn't cover yet answer
     * here (creative flight, gliding, riptide...); the netcode adds the ones it knows (riding, sleeping, dead, not loaded).
     */
    int unsupported(ClientView view);

    /** Whether the simulation has a starting state. An unseeded simulation is seeded from the next position the client reports. */
    boolean seeded();

    /** Starts from a known client state (login, respawn, teleport accept, leaving an exempt state). */
    void seed(double x, double y, double z, double vx, double vy, double vz, float yRot, float xRot, boolean onGround, ClientView view, WorldView world);

    /** Forgets the state: the next claim with a position seeds it again. */
    void unseed();

    /**
     * Moves the simulated position onto the client's claim and nothing else (design §6.2: a claim within
     * {@code rebase-epsilon} is adopted to kill float drift; in observe mode every claim is adopted after measuring).
     */
    void rebase(double x, double y, double z);

    // Drain-time changes ---------------------------------------------------------------------------------------------

    /** {@code SetEntityMotion} for self: {@code lerpMotion} replaces the velocity with the LpVec3-decoded value. */
    void setVelocity(double x, double y, double z);

    /** {@code Explode} knockback: {@code push} adds it. */
    void addVelocity(double x, double y, double z);

    /** {@code PlayerPosition}: {@code setValuesFromPositionPacket} (position, velocity, rotation, old position). */
    void position(PositionMoveRotation change, Set<Relative> relatives);

    /** {@code PlayerRotation}: rotation only, then {@code setOldRot}. */
    void rotation(float yRot, boolean relativeY, float xRot, boolean relativeX);

    /** Block-prediction revert: {@code absSnapTo} (position and old position, velocity kept). */
    void snapTo(double x, double y, double z);

    /** Self entity-data echo of the shared flags byte (sprint bit 3, swim bit 4, fall-flying bit 7). */
    void echoSharedFlags(byte flags);

    /** Self entity-data echo of the pose: {@code refreshDimensions} until the end of the next {@code Player.tick}. */
    void echoPose(Pose pose);

    /** Self entity-data echo of the using-item flags: {@code LocalPlayer.onSyncedDataUpdated} starts or stops item use. */
    void echoUsingItem(boolean using, InteractionHand hand);

    /** {@code EntityEvent} 9 for self: {@code completeUsingItem}. */
    void completeUsingItem();

    /** {@code UpdateAttributes} for self, as the client applies it ({@code ClientView} keeps its own copy too). */
    void attributes(java.util.List<net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket.AttributeSnapshot> snapshots);

    /** {@code GameEvent CHANGE_GAME_MODE}: the local mode (entering spectator zeroes the vertical velocity). */
    void localGameMode(net.minecraft.world.level.GameType mode);

    /** Respawn: a new local player (velocity, sprint and rotation kept only with entity data; attributes per their flag). */
    void respawn(boolean keepEntityData, boolean keepAttributeModifiers);

    // Pre-physics actions of the tick's keybinds --------------------------------------------------------------------

    /**
     * The client's own attack prediction ({@code Player.attack} on the client, report 02 §2.4.1): on a sprinting hit with
     * attack strength above 0.9 against a target whose {@code hurtClient} is true (remote players), with knockback above
     * zero, velocity x/z ×0.6 and sprint off.
     */
    void clientAttack(boolean targetIsPlayer, ClientView view);

    /** The client predicted item use in this tick's keybinds ({@code LocalPlayer.startUsingItem}). */
    void startUsingItem(InteractionHand hand);

    /** {@code RELEASE_USE_ITEM} in this tick's keybinds: use ends before this tick's movement. */
    void releaseUsingItem();

    // The tick -------------------------------------------------------------------------------------------------------

    /**
     * One client tick ({@code Minecraft.tick} from the entity tick on, report 01 §11.4 steps 3-14).
     *
     * @param keys the tick's {@code Input}
     * @param previousKeys the previous tick's {@code Input} (double-tap sprint, crouch)
     * @param yRot the tick's rotation (raw client floats)
     * @param sprintCommand the window's sprint command ({@code ClientStream.SPRINT_*}), a cross-check for the port's sprint logic
     * @param flyingCommand -1 none, 0 or 1: the window's {@code PlayerAbilities} flying flag (aiStep)
     * @param fallFlyingCommand whether the window has {@code START_FALL_FLYING}
     */
    void tick(Input keys, Input previousKeys, float yRot, float xRot, int sprintCommand, int flyingCommand, boolean fallFlyingCommand,
              ClientView view, WorldView world);

    // Belief: the client's own state, for corrections (design §15.5) ----------------------------------------------

    /** Turns the belief on (enforce) or off; while on, it is stepped with the truth's inputs every tick. */
    default void trackBelief(final boolean on) {
    }

    /**
     * After the tick's comparison: an exact claim re-bases the belief on the truth; a diverged claim moves the belief's
     * position onto the claim (the client is where it says, moving as its own state says).
     */
    default void belief(final boolean exact, final double claimX, final double claimY, final double claimZ) {
    }

    /**
     * The correction that lands the client on the truth at the predicted apply tick: truth and belief are both run
     * {@code ticksAhead} ticks further with the latest input, and {@code out} gets {@code Δpos} (0..2) and {@code Δvel}
     * (3..5); with room for 13, also whether the client's box would then be inside a block (6, 1 or 0) and the truth's
     * position (7..9) and velocity (10..12) at that tick. Returns false when there is no belief to compare with.
     */
    default boolean correction(final int ticksAhead, final Input keys, final float yRot, final float xRot, final ClientView view, final WorldView world,
                               final double[] out) {
        return false;
    }

    /**
     * Seeds at {@code to} with the velocity a vanilla client holds after a tick whose move went from {@code from} to
     * {@code to} (auth-sim-requests §1). Returns false when that velocity is not known bit for bit (it then seeds the most
     * likely one); not supported: false and nothing seeded.
     */
    default boolean seedFromMove(final double fromX, final double fromY, final double fromZ, final double toX, final double toY, final double toZ,
                                 final float yRot, final float xRot, final boolean onGround, final ClientView view, final WorldView world) {
        return false;
    }

    /** Whether the belief is being kept and holds a state. */
    default boolean beliefValid() {
        return false;
    }

    /** The belief becomes the truth's state at {@code (x, y, z)} moving at {@code (vx, vy, vz)}: the client's, as an accept shows it. */
    default void rebaseBelief(final double x, final double y, final double z, final double vx, final double vy, final double vz) {
    }

    /** Whether the client's own state (the belief) has its box inside a block's collision shape. */
    default boolean beliefInsideShape(final WorldView world) {
        return false;
    }

    /** Our correction reached the client: its own state (the belief) takes it as the client does ({@code handleMovePlayer}). */
    default void beliefCorrected(final PositionMoveRotation change, final Set<Relative> relatives) {
    }

    // State after the last tick --------------------------------------------------------------------------------------

    double x();

    double y();

    double z();

    /** Position at the start of the last tick ({@code xo}): the attack ray's eye is {@code xo + 1.0 * (x - xo)}. */
    double xo();

    double yo();

    double zo();

    double vx();

    double vy();

    double vz();

    float yRot();

    float xRot();

    boolean onGround();

    boolean horizontalCollision();

    double fallDistance();

    boolean sprinting();

    /** Eye height of the pose the player has now (for the next tick's attack ray). */
    float eyeHeight();

    /** {@code getBoundingBox()} now. */
    net.minecraft.world.phys.AABB box();

    /** Shared flag 4 as the client holds it. */
    default boolean swimming() {
        return false;
    }

    /** Shared flag 7 as the client holds it. */
    default boolean fallFlying() {
        return false;
    }

    /** {@code startedUsingItem} as the client holds it. */
    default boolean usingItem() {
        return false;
    }

    /**
     * Bounded branching for the hotbar ambiguity (report 02 H9, §15.4): a {@code SetCarriedItem} at the start of window
     * {@code T} means the slot changed before tick {@code T} (scroll) or before tick {@code T-1} (number key, sent with the
     * next tick's {@code gameMode.tick}). Re-runs the last tick as if {@code slot} had been selected before it; when that
     * reproduces the last claim exactly, keeps it and returns true, else leaves the state as it was.
     */
    default boolean retryWithSlot(final int slot, final ClientView view, final WorldView world, final double claimX, final double claimY, final double claimZ) {
        return false;
    }

    /**
     * Bounded branching for the attack-strength reset tick (§15.4): whether the last tick's attack on a player was at
     * full strength decides the attacker's own sprint-hit slowdown, and the client's strength ticker can be one reset
     * off ours (an item change applied a tick earlier or later). Re-runs the last tick with the other outcome; keeps it
     * when that reproduces the last claim exactly.
     */
    default boolean retryAttackStrength(final ClientView view, final WorldView world, final double claimX, final double claimY, final double claimZ) {
        return false;
    }

    /**
     * The rebase guard's collision side (§15.5): whether moving the truth's box by {@code (dx, dy, dz)} is unobstructed,
     * so adopting a claim there can't put it into or against a shape.
     */
    default boolean clearPath(final double dx, final double dy, final double dz, final WorldView world) {
        return false;
    }

    /** The client's tab-list game mode for itself ({@code PlayerInfoUpdate} UPDATE_GAME_MODE), at its ack. */
    default void tabListGameMode(final GameType mode) {
    }

    /**
     * Something outside the player's own tick may move it in the client's level this tick (§15.6: a piston, a shulker
     * box or shulker, a solid entity, a geyser, a rod pull): the tick is exempt.
     */
    default void externalMover(final boolean moving) {
    }

    /** Firework rockets attached to the player that the client holds: each boosts it after every tick (auth-sim §3.2). */
    default void attachedRockets(final int count) {
    }

    Pose pose();
}
